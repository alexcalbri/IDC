package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import com.ideasdeveloper.idc.server.modules.NotificationTarget
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Result of one run: [status] is one of the statuses of crm_automation_runs; [recorded] false when nothing was written (conditions not met, already run). */
data class CrmRunOutcome(val status: String, val detail: String, val recorded: Boolean = true) {
    companion object {
        const val Done = "done"
        const val Failed = "failed"
        const val MissingData = "missing_data"
        const val Warned = "warned"
        const val Skipped = "skipped"
    }
}

/**
 * Keeps automations from chaining without end: an action (a stage change, a field) can trigger event rules, which can do
 * the same. A rule never runs inside itself and the chain stops after [MaxDepth] levels. Also lets a bulk process
 * (an import) change leads without firing event rules.
 */
object CrmAutomationGuard {
    const val MaxDepth = 3
    private val active = ThreadLocal.withInitial { java.util.ArrayDeque<UUID>() }
    private val quiet = ThreadLocal.withInitial { 0 }

    fun isActive(id: UUID) = id in active.get()

    /** Event rules do not fire: inside [suppressed] or too deep in a chain. */
    fun blocked() = quiet.get() > 0 || active.get().size >= MaxDepth

    fun <T> within(id: UUID, block: () -> T): T {
        val stack = active.get()
        stack.addLast(id)
        try {
            return block()
        } finally {
            stack.removeLast()
        }
    }

    fun <T> suppressed(block: () -> T): T {
        quiet.set(quiet.get() + 1)
        try {
            return block()
        } finally {
            quiet.set(quiet.get() - 1)
        }
    }
}

/** Notices of the automations: in the app and by email (the email goes to the one the user wrote in Equipo). */
object CrmAutomationNotices {
    const val OutboxKind = "automation"
    private val log = LoggerFactory.getLogger(CrmAutomationNotices::class.java)

    /** Users a "notify" action tells. */
    fun recipients(tenant: Connection, who: CrmNotifyWho, chosen: List<UUID>, lead: CrmLead): List<UUID> = when (who) {
        CrmNotifyWho.Assigned -> lead.ownerUserIds
        CrmNotifyWho.Users -> chosen
        CrmNotifyWho.SeeAll -> seeAll(tenant)
    }.distinct()

    /** Who is told that data is missing: the users chosen in the rule, else the assigned users of the lead, else the ones that see every lead. */
    fun missingRecipients(tenant: Connection, automation: CrmAutomation, lead: CrmLead): List<UUID> = when {
        automation.notifyUserIds.isNotEmpty() -> automation.notifyUserIds.distinct()
        lead.ownerUserIds.isNotEmpty() -> lead.ownerUserIds.distinct()
        else -> seeAll(tenant)
    }

    private fun seeAll(tenant: Connection): List<UUID> = CrmRepository.users(tenant).filter { CrmNotify.userScope(tenant, it.id).seesAll }.map { it.id }

    /** Tells [userIds]: notice in the app (it opens the lead) and, when they have an email, an email. Never fails the caller. Returns how many users. */
    fun deliver(tenant: Connection, notifier: ModuleNotifier?, lead: CrmLead, userIds: List<UUID>, title: String, text: String): Int {
        if (userIds.isEmpty()) return 0
        val settings = runCatching { CrmNotify.userSettings(tenant) }.getOrDefault(emptyMap())
        userIds.forEach { userId ->
            runCatching {
                notifier?.notify(tenant, userId, title, text, NotificationTarget("lead", mapOf("id" to lead.id.toString())))
            }.onFailure { log.warn("No se pudo crear el aviso de una automatizacion ({}).", it.javaClass.simpleName) }
            runCatching {
                settings[userId]?.email?.trim()?.takeIf { it.isNotEmpty() }?.let { to ->
                    CrmNotify.enqueue(tenant, OutboxKind, to, CrmMail.plain(title, "$text\n\nLead: ${lead.name}"), lead.id)
                }
            }.onFailure { log.warn("No se pudo preparar el correo de una automatizacion ({}).", it.javaClass.simpleName) }
        }
        return userIds.size
    }

    /** Title and text of "faltan datos": what is missing, in which lead and when the rule runs (or why it did not run). */
    fun missingTexts(automation: CrmAutomation, lead: CrmLead, missing: List<String>, whenText: String?, ran: Boolean): Pair<String, String> {
        val title = "Faltan datos: ${automation.name}"
        val what = "Al lead ${lead.name} le falta ${CrmAutomationRequirements.sentence(missing)}."
        val tail = when {
            whenText != null && !ran -> " La automatizacion se ejecuta $whenText: completalo antes."
            ran -> " La automatizacion no se ejecuto."
            else -> ""
        }
        return title to what + tail
    }
}

/**
 * Runs the automations. [run] is the only place an automation executes: conditions, the data the actions need, the actions
 * in order (stopping at the first failure), the record in crm_automation_runs and the note in the lead's activity.
 */
object CrmAutomationEngine {
    private val log = LoggerFactory.getLogger(CrmAutomationEngine::class.java)

    /** What ids and names mean in this company (read once per run). */
    fun loadRefs(tenant: Connection): CrmAutomationRefs = CrmAutomationRefs(
        stages = CrmRepository.stages(tenant),
        interests = CrmRepository.interests(tenant),
        templates = CrmMail.templates(tenant),
        users = CrmRepository.users(tenant),
        fields = CrmFields.all(tenant),
        cloudbedsReady = CrmModuleExtensions.hasAction("cloudbeds_reserve") && CrmModuleExtensions.cloudbedsActive(tenant),
    )

    /** The mapped stay fields' labels, for "falta la fecha de ingreso". */
    fun stayLabels(refs: CrmAutomationRefs, mapping: CrmStayMapping): CrmStayLabels {
        return CrmStayLabels(
            start = refs.fields.firstOrNull { it.key == mapping.startKey }?.label?.lowercase() ?: CrmStayLabels().start,
            end = refs.fields.firstOrNull { it.key == mapping.endKey }?.label?.lowercase() ?: CrmStayLabels().end,
        )
    }

    /** What the lead has; the Cloudbeds data is only read when some action needs it. */
    fun facts(tenant: Connection, lead: CrmLead, actions: List<CrmAction>, refs: CrmAutomationRefs): CrmLeadFacts {
        if (actions.none { it.kind.isCloudbeds }) return CrmLeadFacts(email = lead.email, startDate = lead.targetStart, endDate = lead.targetEnd, cloudbedsReady = refs.cloudbedsReady)
        val rooms = CrmHost.chosenRooms(tenant, lead.id).map { it.startsOn to it.endsOn }
        return CrmLeadFacts(
            email = lead.email, startDate = lead.targetStart, endDate = lead.targetEnd, chosenRooms = rooms,
            reservationStatuses = CrmHost.reservationsForLead(tenant, lead.id).map { it.status }, cloudbedsReady = refs.cloudbedsReady,
            guestCountry = CrmHost.cloudbedsSettings(tenant).defaultCountry.isNotEmpty(),
        )
    }

    /**
     * Runs [automation] for [lead] now (buttons, agents and the date job: the caller waits). [runKey] says which execution this is
     * (see [CrmAutomationKeys]); the same key never runs twice. [actor] is who pressed a button (null for the job). A rule whose
     * conditions are not met does nothing and records nothing. [refs] can be passed to avoid reading them again for every lead.
     */
    fun run(
        tenant: Connection,
        automation: CrmAutomation,
        lead: CrmLead,
        runKey: String,
        actor: CrmActor? = null,
        notifier: ModuleNotifier? = CrmLeadEvents.access?.notificationsFor(CrmModule.id),
        http: CrmHttp = CrmJavaHttp,
        now: Instant = Instant.now(),
        refs: CrmAutomationRefs? = null,
    ): CrmRunOutcome {
        // Conditions first: nothing heavy is read for a lead that does not qualify.
        CrmAutomationConditions.failing(automation.conditions, lead)?.let { failing ->
            return CrmRunOutcome(CrmRunOutcome.Skipped, "No se cumple la condicion: ${CrmAutomationConditions.label(failing, refs ?: loadRefs(tenant))}.", recorded = false)
        }
        if (!CrmAutomationStore.claim(tenant, automation.id, lead.id, runKey)) {
            return CrmRunOutcome(CrmRunOutcome.Skipped, "Ya se ejecuto para este lead.", recorded = false)
        }
        return runClaimed(tenant, automation, lead, runKey, actor, notifier, http, now, refs)
    }

    /**
     * Executes a run whose row is already claimed ("running": by [run] or by the job from a "pending" one) and ALWAYS finishes that row:
     * whatever fails, it ends failed with the reason, never "running" forever.
     */
    fun runClaimed(
        tenant: Connection, automation: CrmAutomation, lead: CrmLead, runKey: String, actor: CrmActor?,
        notifier: ModuleNotifier?, http: CrmHttp, now: Instant = Instant.now(), refs: CrmAutomationRefs? = null,
    ): CrmRunOutcome {
        val outcome = try {
            execute(tenant, automation, lead, runKey, actor, notifier, http, refs ?: loadRefs(tenant), now)
        } catch (failure: Exception) {
            CrmRunOutcome(CrmRunOutcome.Failed, "Fallo antes de terminar: ${failureText(failure)}")
        }
        // The state of the row is the record; a failure writing it is only logged (the job marks a stuck row failed later).
        runCatching { CrmAutomationStore.finish(tenant, automation.id, lead.id, runKey, outcome.status, outcome.detail) }
            .onFailure { log.warn("No se pudo cerrar una ejecucion de automatizacion ({}).", it.javaClass.simpleName) }
        return outcome
    }

    private fun execute(
        tenant: Connection, automation: CrmAutomation, lead: CrmLead, runKey: String, actor: CrmActor?,
        notifier: ModuleNotifier?, http: CrmHttp, refs: CrmAutomationRefs, now: Instant,
    ): CrmRunOutcome {
        val doer = actor ?: CrmActor(null, "Automatizacion ${automation.name}")
        // The conditions are checked again: a queued run may wait a minute and the lead can change meanwhile.
        CrmAutomationConditions.failing(automation.conditions, lead)?.let { failing ->
            return CrmRunOutcome(CrmRunOutcome.Skipped, "No se cumple la condicion: ${CrmAutomationConditions.label(failing, refs)}.")
        }
        // Automations never send more than a few template emails to one lead per hour.
        if (automation.trigger.type != CrmTriggerType.Manual && automation.actions.any { it.kind == CrmActionKind.SendTemplate } &&
            CrmRunStates.templateLimitReached(CrmAutomationStore.templateMailsLastHour(tenant, lead.id))
        ) {
            val detail = "Limite de ${CrmRunStates.MaxTemplateMailsPerHour} correos de plantilla por hora para este lead: no se envio."
            record(tenant, lead, automation, detail, doer)
            return CrmRunOutcome(CrmRunOutcome.Skipped, detail)
        }
        val stay = CrmStay.effective(CrmStay.load(tenant), refs.fields)
        val missing = CrmAutomationRequirements.missing(automation.actions, facts(tenant, lead, automation.actions, refs), stayLabels(refs, stay), stay)
        if (missing.isNotEmpty()) {
            val (title, text) = CrmAutomationNotices.missingTexts(automation, lead, missing, null, ran = true)
            val detail = "Faltan datos: ${CrmAutomationRequirements.sentence(missing)}. No se ejecuto."
            record(tenant, lead, automation, detail, doer)
            // A button answers to the person who pressed it. Date rules tell once per date (their key); event rules once per day, not per event.
            val tell = when (automation.trigger.type) {
                CrmTriggerType.Manual -> false
                CrmTriggerType.Date -> true
                else -> {
                    val key = CrmAutomationKeys.missing(now.atZone(CrmZone.of(tenant)).toLocalDate())
                    val first = CrmAutomationStore.claim(tenant, automation.id, lead.id, key)
                    if (first) CrmAutomationStore.finish(tenant, automation.id, lead.id, key, CrmRunOutcome.MissingData, "Aviso de datos que faltan enviado: $detail")
                    first
                }
            }
            if (tell) CrmAutomationNotices.deliver(tenant, notifier, lead, CrmAutomationNotices.missingRecipients(tenant, automation, lead), title, text)
            return CrmRunOutcome(CrmRunOutcome.MissingData, detail)
        }
        val results = mutableListOf<String>()
        var step = 0
        val outcome = try {
            CrmAutomationGuard.within(automation.id) {
                automation.actions.forEachIndexed { index, action ->
                    step = index + 1
                    val current = CrmRepository.lead(tenant, lead.id) ?: throw CrmValidationException("El lead ya no existe.")
                    results += executeAction(tenant, automation, action, current, refs, doer, notifier, http)
                }
            }
            CrmRunOutcome(CrmRunOutcome.Done, results.joinToString(" "))
        } catch (failure: Exception) {
            val message = failureText(failure)
            val done = if (results.isEmpty()) "" else "Hecho: ${results.joinToString(" ")} "
            CrmRunOutcome(CrmRunOutcome.Failed, "${done}Fallo la accion $step (${automation.actions.getOrNull(step - 1)?.let { CrmAutomationSummary.action(it, refs) }.orEmpty()}): $message")
        }
        record(tenant, lead, automation, outcome.detail, doer)
        return outcome
    }

    /**
     * The job of the event rules (every minute): marks stuck runs failed, claims up to [limit] queued runs and executes them. Returns how many were claimed.
     */
    fun processPending(tenant: Connection, notifier: ModuleNotifier?, http: CrmHttp = CrmJavaHttp, limit: Int = 50, now: Instant = Instant.now()): Int {
        CrmAutomationStore.failStale(tenant, now)
        val pending = CrmAutomationStore.claimPending(tenant, limit)
        if (pending.isEmpty()) return 0
        val automations = CrmAutomationStore.all(tenant).associateBy { it.id }
        val refs = loadRefs(tenant)
        pending.forEach { row ->
            runCatching {
                val automation = automations[row.automationId]
                val lead = CrmRepository.lead(tenant, row.leadId)
                when {
                    automation == null || !automation.enabled -> CrmAutomationStore.finish(tenant, row.automationId, row.leadId, row.runKey, CrmRunOutcome.Skipped, "La automatizacion esta apagada o ya no existe.")
                    lead == null -> CrmAutomationStore.finish(tenant, row.automationId, row.leadId, row.runKey, CrmRunOutcome.Failed, "El lead ya no existe.")
                    else -> runClaimed(tenant, automation, lead, row.runKey, null, notifier, http, now, refs)
                }
            }.onFailure {
                log.warn("Fallo una automatizacion en cola ({}).", it.javaClass.simpleName)
                runCatching { CrmAutomationStore.finish(tenant, row.automationId, row.leadId, row.runKey, CrmRunOutcome.Failed, "Fallo antes de empezar.") }
            }
        }
        return pending.size
    }

    private fun failureText(failure: Exception): String = when (failure) {
        is CrmActionNotice, is CrmValidationException, is CrmCloudbedsException -> failure.message.orEmpty()
        else -> {
            log.warn("Fallo inesperado en una automatizacion ({}).", failure.javaClass.simpleName)
            "error inesperado (${failure.javaClass.simpleName})"
        }
    }

    // The note in the lead's activity. A rule that sends a template already logged that; this one says the rule ran.
    private fun record(tenant: Connection, lead: CrmLead, automation: CrmAutomation, detail: String, actor: CrmActor) {
        runCatching { CrmRepository.addActivity(tenant, lead.id, "item", "Automatizacion ${automation.name}: $detail", actor) }
            .onFailure { log.warn("No se pudo anotar una automatizacion en la actividad ({}).", it.javaClass.simpleName) }
    }

    private fun executeAction(
        tenant: Connection, automation: CrmAutomation, action: CrmAction, lead: CrmLead, refs: CrmAutomationRefs,
        actor: CrmActor, notifier: ModuleNotifier?, http: CrmHttp,
    ): String = when (action.kind) {
        CrmActionKind.SendTemplate -> {
            val templateId = action.templateId ?: throw CrmValidationException("La accion no tiene plantilla.")
            val name = refs.templates.firstOrNull { it.id == templateId }?.name ?: throw CrmValidationException("Esa plantilla ya no existe.")
            val sent = CrmTemplateMailer.send(tenant, templateId, lead.id, actor, http = http)
            "Plantilla $name enviada a ${sent.to}${if (sent.mail.translated) " (traducida al ${sent.mail.language})" else ""}."
        }
        CrmActionKind.SetStage -> {
            val stage = refs.stages.firstOrNull { it.id == action.stageId } ?: throw CrmValidationException("Esa etapa ya no existe.")
            if (lead.stageId == stage.id) {
                "El lead ya estaba en ${stage.name}."
            } else if (CrmRepository.wonNeedsReservation(tenant, lead.id, stage)) {
                // Same rule as the board and the bulk move: with rooms chosen, a lead is won by confirming the reservation.
                throw CrmValidationException("${lead.name} tiene habitaciones asignadas: confirma la reserva desde su ficha.")
            } else {
                CrmRepository.moveLead(tenant, lead, stage, refs.stages, actor)
                "Paso a ${stage.name}."
            }
        }
        CrmActionKind.SetField -> setField(tenant, lead, action.field, action.value, refs, actor)
        CrmActionKind.Notify -> {
            val who = CrmAutomationNotices.recipients(tenant, action.who, action.userIds, lead)
            val title = action.title.ifEmpty { "Aviso: ${automation.name}" }
            val text = action.text.ifEmpty { "Automatizacion ${automation.name}." }
            val told = CrmAutomationNotices.deliver(tenant, notifier, lead, who, title, "${lead.name}: $text")
            if (told == 0) "Aviso sin destinatarios (nadie asignado o elegido)." else "Aviso enviado a $told usuario(s)."
        }
        CrmActionKind.CloudbedsReserve, CrmActionKind.CloudbedsCheckIn -> {
            val provider = CrmModuleExtensions.providerFor(action.kind.id)
                ?: throw CrmValidationException(CrmCloudbedsMissing)
            provider.execute(action.kind.id, CrmActionContext(tenant, lead.id, lead, refs.stages, actor)).message
        }
    }

    /** Writes [value] ("" clears) in a custom field (or the follow-up date) of the lead, like the lead form does, and fires the field_changed rules. */
    fun setField(tenant: Connection, lead: CrmLead, key: String, value: String, refs: CrmAutomationRefs, actor: CrmActor): String {
        if (key == CrmAutomationRefs.FollowUpField) {
            val date = value.takeIf { it.isNotEmpty() }?.let { text ->
                runCatching { LocalDate.parse(text) }.getOrElse { throw CrmValidationException("La fecha de seguimiento usa el formato AAAA-MM-DD.") }
            }
            if (date == lead.followUpOn) return "${CrmAutomationRefs.FollowUpLabel} ya era ${date ?: "(vacio)"}."
            tenant.prepareStatement("UPDATE crm_leads SET follow_up_on = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
                update.setObject(1, date)
                update.setObject(2, lead.id)
                update.executeUpdate()
            }
            CrmRepository.addActivity(tenant, lead.id, "updated", "Seguimiento: ${lead.followUpOn ?: "(vacio)"} -> ${date ?: "(vacio)"}", actor)
            CrmAutomationEvents.fieldsChanged(tenant, lead.id, listOf(key))
            return "${CrmAutomationRefs.FollowUpLabel}: ${date ?: "(vacio)"}."
        }
        val field = refs.fields.firstOrNull { it.key == key && it.type != CrmFieldType.Files && it.type != CrmFieldType.Button } ?: throw CrmValidationException("Ese campo ya no existe.")
        val changes = CrmFields.parseValues(mapOf(key to value), listOf(field.copy(required = false)), lead.customFields)
        val after = CrmFields.merge(lead.customFields, changes)
        val lines = CrmFields.changes(refs.fields, lead.customFields, after)
        if (lines.isEmpty()) return "${field.label} ya tenia ese valor."
        // A mapped stay date keeps its rule: the end is never before the start.
        CrmStay.datesAfterSave(CrmStay.effective(CrmStay.load(tenant), refs.fields), refs.fields, lead.customFields, changes)
        tenant.prepareStatement("UPDATE crm_leads SET custom_fields = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setString(1, after.toString())
            update.setObject(2, lead.id)
            update.executeUpdate()
        }
        CrmStay.resync(tenant, lead.id)
        CrmRepository.addActivity(tenant, lead.id, "updated", lines.joinToString("\n"), actor)
        CrmAutomationEvents.fieldsChanged(tenant, lead.id, listOf(key))
        return "${field.label}: ${CrmFields.display(after[key]).ifEmpty { "(vacio)" }}."
    }

    // ---- Date rules (job every 5 minutes) ----

    /** (lead, value of the date) of the leads a date rule can run or warn for (see [CrmAutomationSchedule.dueQuery]). */
    private fun leadsWithDate(tenant: Connection, automation: CrmAutomation, from: LocalDate, to: LocalDate, limit: Int): List<Pair<UUID, LocalDate>> {
        val query = CrmAutomationSchedule.dueQuery(automation.trigger.field, automation.id, from, to, limit)
        return tenant.prepareStatement(query.sql).use { statement ->
            query.params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getObject(2, LocalDate::class.java)) }
            }
        }
    }

    /**
     * One pass of the date rules: for each enabled date rule, the leads whose run time arrived (within the catch-up day) run
     * once per value of the date, and the leads that reach the warning time with data missing are warned once. Returns how
     * many executions or warnings were recorded.
     */
    fun runDue(tenant: Connection, notifier: ModuleNotifier?, now: Instant = Instant.now(), http: CrmHttp = CrmJavaHttp): Int {
        val automations = CrmAutomationStore.enabledOf(tenant, CrmTriggerType.Date)
        if (automations.isEmpty()) return 0
        val zone = CrmZone.of(tenant)
        val today = now.atZone(zone).toLocalDate()
        var count = 0
        automations.forEach { automation ->
            runCatching {
                val (from, to) = CrmAutomationSchedule.window(automation.trigger, automation.warnBeforeMinutes, today)
                val candidates = leadsWithDate(tenant, automation, from, to, 500)
                if (candidates.isEmpty()) return@runCatching
                // Read once per rule and pass, not once per lead.
                val refs = loadRefs(tenant)
                val recorded = CrmAutomationStore.recordedKeys(tenant, automation.id, candidates.map { it.first })
                candidates.forEach { (leadId, value) ->
                    val dateKey = CrmAutomationKeys.date(value)
                    when {
                        CrmAutomationSchedule.isDue(value, automation.trigger, zone, now) -> {
                            if ((leadId to dateKey) in recorded) return@forEach
                            val lead = CrmRepository.lead(tenant, leadId) ?: return@forEach
                            if (run(tenant, automation, lead, dateKey, null, notifier, http, now, refs).recorded) count++
                        }
                        CrmAutomationSchedule.isWarnDue(value, automation.trigger, automation.warnBeforeMinutes, zone, now) -> {
                            if ((leadId to CrmAutomationKeys.warn(value)) in recorded || (leadId to dateKey) in recorded) return@forEach
                            val lead = CrmRepository.lead(tenant, leadId) ?: return@forEach
                            if (warn(tenant, automation, lead, value, zone, notifier, refs)) count++
                        }
                        else -> Unit
                    }
                    Unit
                }
            }.onFailure { log.warn("Fallo el repaso de una automatizacion de fecha ({}).", it.javaClass.simpleName) }
        }
        return count
    }

    /** The warning of a date rule: tells who must complete the data when something the actions need is missing. True when it warned. */
    fun warn(tenant: Connection, automation: CrmAutomation, lead: CrmLead, value: LocalDate, zone: java.time.ZoneId, notifier: ModuleNotifier?, known: CrmAutomationRefs? = null): Boolean {
        if (CrmAutomationConditions.failing(automation.conditions, lead) != null) return false
        val refs = known ?: loadRefs(tenant)
        val stay = CrmStay.effective(CrmStay.load(tenant), refs.fields)
        val missing = CrmAutomationRequirements.missing(automation.actions, facts(tenant, lead, automation.actions, refs), stayLabels(refs, stay), stay)
        if (missing.isEmpty()) return false
        val key = CrmAutomationKeys.warn(value)
        if (!CrmAutomationStore.claim(tenant, automation.id, lead.id, key)) return false
        val runAt = CrmAutomationSchedule.runAt(value, automation.trigger, zone)
        val (title, text) = CrmAutomationNotices.missingTexts(automation, lead, missing, CrmAutomationSchedule.whenText(runAt), ran = false)
        val detail = "Aviso: falta ${CrmAutomationRequirements.sentence(missing)}. Se ejecuta ${CrmAutomationSchedule.whenText(runAt)}."
        CrmAutomationStore.finish(tenant, automation.id, lead.id, key, CrmRunOutcome.Warned, detail)
        CrmAutomationNotices.deliver(tenant, notifier, lead, CrmAutomationNotices.missingRecipients(tenant, automation, lead), title, text)
        return true
    }
}

/**
 * Event rules: lead created, stage changed, field changed and message received. They run right after the change was saved
 * (callers fire them once the data is committed), one run per event, best effort: a failing rule never fails the change.
 */
object CrmAutomationEvents {
    private val log = LoggerFactory.getLogger(CrmAutomationEvents::class.java)

    fun leadCreated(tenant: Connection, leadId: UUID) = dispatch(tenant, CrmTriggerType.LeadCreated, leadId) { true }

    fun stageChanged(tenant: Connection, leadId: UUID, stageId: UUID) =
        dispatch(tenant, CrmTriggerType.StageChanged, leadId) { it.trigger.stageId == stageId }

    /** [keys]: custom field keys (or the follow-up key) whose value changed. */
    fun fieldsChanged(tenant: Connection, leadId: UUID, keys: Collection<String>) {
        if (keys.isEmpty()) return
        dispatch(tenant, CrmTriggerType.FieldChanged, leadId) { it.trigger.field in keys }
    }

    fun messageReceived(tenant: Connection, leadId: UUID) = dispatch(tenant, CrmTriggerType.MessageReceived, leadId) { true }

    /** Runs [block] (an import, for example) without firing event rules. */
    fun <T> suppressed(block: () -> T): T = CrmAutomationGuard.suppressed(block)

    /**
     * Only queues: one "pending" row per rule that matches (the job of the events runs it within a minute), so a save, a webhook or a bulk move
     * never waits for a translation or Cloudbeds. Conditions are checked here to queue only what can run. message_received queues one run per
     * rule, lead and day; every rule is also capped per lead and hour, which cuts loops between rules that trigger each other.
     */
    private fun dispatch(tenant: Connection, type: CrmTriggerType, leadId: UUID, matches: (CrmAutomation) -> Boolean) {
        if (CrmAutomationGuard.blocked()) return
        try {
            val automations = CrmAutomationStore.enabledOf(tenant, type).filter(matches).filter { !CrmAutomationGuard.isActive(it.id) }
            if (automations.isEmpty()) return
            val lead = CrmRepository.lead(tenant, leadId) ?: return
            val now = Instant.now()
            val day = now.atZone(CrmZone.of(tenant)).toLocalDate()
            automations.forEach { automation ->
                runCatching {
                    if (CrmAutomationConditions.failing(automation.conditions, lead) != null) return@runCatching
                    if (CrmRunStates.eventLoopLimitReached(CrmAutomationStore.recentRunCount(tenant, automation.id, lead.id, 60))) return@runCatching
                    val key = if (type == CrmTriggerType.MessageReceived) CrmAutomationKeys.message(day) else CrmAutomationKeys.event(now, UUID.randomUUID().toString().take(8))
                    CrmAutomationStore.enqueue(tenant, automation.id, lead.id, key)
                }.onFailure { log.warn("No se pudo poner en cola una automatizacion de evento ({}).", it.javaClass.simpleName) }
            }
        } catch (failure: Exception) {
            log.warn("No se pudieron revisar las automatizaciones de evento ({}).", failure.javaClass.simpleName)
        }
    }
}
