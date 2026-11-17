package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import java.util.UUID

/** Actions on many leads at once, shared by the bulk bar of the list view of "Leads" and the agent tools. */
object CrmBulkActions {
    // Most checkboxes read from one request.
    const val MaxSelected = 500

    /**
     * Ids the bulk form selected, always inside [pageIds] (the leads of the page the form was drawn for):
     * with "all" on, the whole page; otherwise the pages leads whose checkbox is "true". Ids that are not
     * on the page are ignored, so a stale or forged form never reaches other leads.
     */
    fun selectedIds(values: Map<String, String>, pageIds: List<UUID>): List<UUID> {
        if (values["all"] == "true") return pageIds.take(MaxSelected)
        val checked = values.filter { it.key.startsWith("lead:") && it.value == "true" }.keys.take(MaxSelected)
            .mapNotNull { runCatching { UUID.fromString(it.removePrefix("lead:")) }.getOrNull() }.toSet()
        return pageIds.filter { it in checked }
    }

    /** Why a lead seen only through a mention is left out of any bulk action. */
    const val ReadOnlyReason = "solo los ves por una mencion (lectura y notas)"

    /**
     * Splits [leads] into the ones the user may act on (inside their scope) and the ones they only see through a mention,
     * which no bulk action touches. A defense on top of the screens, which never offer those leads to the bulk bar.
     */
    fun writable(scope: CrmScope, leads: List<CrmLead>): Pair<List<CrmLead>, List<CrmLead>> =
        leads.partition { scope.canSee(it.interestId, it.ownerUserIds) }

    /** The user [value] names to add or remove; an empty, unknown or "Sin asignar" value is an error, never a silent change. */
    fun userFor(users: List<CrmUser>, value: String): UUID =
        users.firstOrNull { it.name == value }?.id ?: throw CrmActionNotice("Elige el usuario.")

    /** The assigned users of [leads] that no longer have access to the CRM, named "<usuario> (sin acceso)". */
    fun legacyOf(context: CrmScreens.Context, leads: List<CrmLead>): List<CrmUser> =
        CrmRepository.legacyUsers(context.tenant, context.users, leads.flatMap { it.ownerUserIds })

    /** Whether [target] (the users a lead would end up with) differs from the [current] ones: a lead that would not change is skipped. */
    fun changesAssignees(current: List<UUID>, target: Collection<UUID>): Boolean = target.toSet() != current.toSet()

    /** "3 lead(s) movidos a X. 2 omitidos: motivo (2)." ; nothing done is a warning. */
    fun summary(done: Int, doneText: String, skipped: Map<String, Int>): String {
        val omitted = skipped.values.sum()
        if (omitted == 0) return "$done $doneText"
        val reasons = skipped.entries.joinToString("; ") { "${it.key} (${it.value})" }
        val text = "$done actualizados, $omitted omitidos: $reasons."
        if (done == 0) throw CrmActionNotice(text, warning = true)
        return "$done $doneText $text"
    }

    /**
     * Adds, removes or replaces assigned users in each of [leads] (already filtered to the user's scope). A lead that
     * would not change is skipped and counted, so repeating the action changes nothing. The users really added get
     * "Te asignaron un lead" through [notifier] (never the user doing it). Throws [CrmActionNotice]; returns the message.
     */
    fun assign(context: CrmScreens.Context, requested: List<CrmLead>, mode: CrmAssignees.Mode, ids: List<UUID>, actor: CrmActor, notifier: ModuleNotifier? = null): String {
        if (!context.canAssign) throw CrmActionNotice("No tienes permiso para asignar leads.")
        if (requested.isEmpty()) throw CrmActionNotice("Marca al menos un lead.", warning = true)
        if (mode != CrmAssignees.Mode.Replace && ids.isEmpty()) throw CrmActionNotice("Elige el usuario.")
        // Users without access can be taken out but never added.
        if (!CrmAssignees.canApply(mode, ids, context.users.map { it.id })) throw CrmActionNotice("Ese usuario no tiene acceso al CRM.")
        val (leads, readOnly) = writable(context.scope, requested)
        val skipped = linkedMapOf<String, Int>()
        if (readOnly.isNotEmpty()) skipped[ReadOnlyReason] = readOnly.size
        var done = 0
        leads.forEach { lead ->
            // "Sin asignar" takes everybody out; a replacement keeps the assigned users that can no longer be chosen.
            val target = if (mode == CrmAssignees.Mode.Replace && ids.isEmpty()) emptyList()
            else CrmAssignees.combineKeepingInactive(lead.ownerUserIds, mode, ids, context.users.map { it.id })
            if (!changesAssignees(lead.ownerUserIds, target)) {
                val reason = when (mode) {
                    CrmAssignees.Mode.Add -> "ya lo tenian asignado"
                    CrmAssignees.Mode.Remove -> "no lo tenian asignado"
                    CrmAssignees.Mode.Replace -> if (ids.isEmpty()) "ya estaban sin asignar" else "ya tenian esos asignados"
                }
                skipped[reason] = (skipped[reason] ?: 0) + 1
                return@forEach
            }
            try {
                // The change and its history entry go together.
                val change = CrmRepository.assign(context.tenant, lead, mode, if (mode == CrmAssignees.Mode.Replace) target else ids, context.names, actor, " (en lote)")
                CrmNotify.assigned(context.tenant, notifier, lead.id, change.added, actor.userId)
                done++
            } catch (failure: CrmValidationException) {
                val reason = failure.message.orEmpty().ifBlank { "no se pudieron asignar" }
                skipped[reason] = (skipped[reason] ?: 0) + 1
            }
        }
        val who = CrmAssignees.names(context.names, ids)
        val doneText = when (mode) {
            CrmAssignees.Mode.Add -> "lead(s) con $who anadido a los asignados."
            CrmAssignees.Mode.Remove -> "lead(s) sin $who entre los asignados."
            CrmAssignees.Mode.Replace -> if (ids.isEmpty()) "lead(s) sin asignados." else "lead(s) asignados a $who."
        }
        return summary(done, doneText, skipped)
    }

    /**
     * Applies [action] (one of the CrmImportScreens.Action* labels) to [leads], which must already be
     * filtered to the user's scope. [value] is the stage name or the user name (add / remove an assigned user;
     * "Sin asignar" takes no value and removes everybody). A lead that cannot take the action is skipped and
     * counted. Users added get "Te asignaron un lead" through [notifier]. Throws [CrmActionNotice]; returns the success message.
     */
    fun apply(context: CrmScreens.Context, requested: List<CrmLead>, action: String, value: String, actor: CrmActor, notifier: ModuleNotifier? = null): String {
        val tenant = context.tenant
        if (requested.isEmpty()) throw CrmActionNotice("Marca al menos un lead.", warning = true)
        // Leads seen only through a mention are read-only: they never reach an action (not even delete).
        val (leads, readOnly) = writable(context.scope, requested)
        val skipped = linkedMapOf<String, Int>()
        if (readOnly.isNotEmpty()) skipped[ReadOnlyReason] = readOnly.size
        // Nothing left to act on: the message says why (they were all read-only).
        if (leads.isEmpty()) return summary(0, "", skipped)
        fun skip(reason: String, count: Int = 1) { skipped[reason] = (skipped[reason] ?: 0) + count }
        return when (action) {
            CrmImportScreens.ActionStage -> {
                val stage = context.stages.firstOrNull { it.name == value } ?: throw CrmActionNotice("Elige la etapa.")
                var done = 0
                leads.forEach { lead ->
                    if (CrmRepository.wonNeedsReservation(tenant, lead.id, stage)) {
                        skip("tienen habitaciones elegidas sin reserva: confirma la reserva desde su ficha")
                        return@forEach
                    }
                    try {
                        CrmRepository.moveLead(tenant, lead, stage, context.stages, actor)
                        done++
                    } catch (failure: CrmValidationException) {
                        skip(failure.message.orEmpty().ifBlank { "no se pudieron mover" })
                    }
                }
                summary(done, "lead(s) movidos a ${stage.name}.", skipped)
            }
            CrmImportScreens.ActionAddOwner, CrmImportScreens.ActionRemoveOwner ->
                assign(
                    context, leads, if (action == CrmImportScreens.ActionAddOwner) CrmAssignees.Mode.Add else CrmAssignees.Mode.Remove,
                    // "Quitar" also accepts assigned users that no longer have access ("<usuario> (sin acceso)").
                    listOf(userFor(context.users + legacyOf(context, leads), value)), actor, notifier,
                )
            // "Sin asignar": takes everybody out.
            CrmImportScreens.ActionClearOwners -> assign(context, leads, CrmAssignees.Mode.Replace, emptyList(), actor, notifier)
            CrmImportScreens.ActionArchive -> {
                val archived = leads.count { CrmRepository.archiveLead(tenant, it.id, actor) }
                if (archived < leads.size) skip("ya estaban archivados", leads.size - archived)
                summary(archived, "lead(s) archivados.", skipped)
            }
            CrmImportScreens.ActionRestore -> {
                val restored = leads.count { CrmRepository.restoreLead(tenant, it.id, actor) }
                if (restored < leads.size) skip("ya estaban abiertos", leads.size - restored)
                summary(restored, "lead(s) restaurados.", skipped)
            }
            CrmImportScreens.ActionDelete -> {
                if (!context.permissions.has(CrmScreens.Delete)) throw CrmActionNotice("No tienes permiso para eliminar leads.")
                leads.forEach { CrmRepository.deleteLead(tenant, it.id) }
                summary(leads.size, "lead(s) eliminados (los clientes quedan en Clientes).", skipped)
            }
            else -> throw CrmActionNotice("Elige la accion.")
        }
    }
}
