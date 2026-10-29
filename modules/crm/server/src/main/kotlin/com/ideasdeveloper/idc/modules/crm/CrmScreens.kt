package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.SmtpSettings
import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiBoard
import com.ideasdeveloper.idc.ui.UiBoardCard
import com.ideasdeveloper.idc.ui.UiBoardColumn
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNavItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNavigation
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTimeline
import com.ideasdeveloper.idc.ui.UiTimelineEntry
import com.ideasdeveloper.idc.ui.UiTone
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** CRM screens built from generic UI pieces; the app draws them with the user's theme. */
object CrmScreens {
    const val UiRoute = "/modules/crm/ui"

    const val View = "crm.view"
    const val Manage = "crm.leads.manage"
    const val Delete = "crm.leads.delete"
    const val Reception = "crm.reception"
    const val SeeAll = "crm.leads.all"
    const val Settings = "crm.settings"
    const val Assign = "crm.assign"

    private const val BoardPerStage = 30
    private const val PageSize = 50
    private const val AllOption = "Todos"
    private const val MineOption = "Mis leads"
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private data class Section(val id: String, val label: String, val permission: String, val needsInterests: Boolean = false)

    private val sections = listOf(
        Section("pipeline", "Pipeline", View),
        Section("leads", "Leads", View),
        Section("reception", "Recepcion", Reception, needsInterests = true),
        Section("followups", "Seguimientos", View),
        Section("assignments", "Asignaciones", Assign),
        Section("new", "Nuevo lead", Manage),
        Section("settings", "Configuracion", Settings),
        Section("mail", "Correo", Settings),
        Section("templates", "Plantillas", Settings),
        Section("spam", "Anti-spam", Settings),
        Section("team", "Equipo", Settings),
        Section("integrations", "Integraciones", Settings),
    )

    // Screens outside the navigation.
    private val hidden = mapOf("lead" to View, "properties" to Manage)

    fun isScreen(id: String) = sections.any { it.id == id } || id in hidden

    fun permissionFor(screen: String): String? = sections.firstOrNull { it.id == screen }?.permission ?: hidden[screen]

    fun home(permissions: ModulePermissions): String? = sections.firstOrNull { permissions.has(it.permission) }?.id

    /** Everything a screen needs, loaded once per request. */
    class Context(
        val tenant: Connection,
        val permissions: ModulePermissions,
        val scope: CrmScope,
        val stages: List<CrmStage>,
        val interests: List<CrmInterest>,
        val users: List<CrmUser>,
        // Server SMTP, used when the company sends with it.
        val serverMail: SmtpSettings? = null,
        // Public origin of this server (https://host), for the webhook URLs shown in Integraciones.
        val publicBase: String = "",
        val today: LocalDate = LocalDate.now(),
    ) {
        val names = CrmNames(users, interests)
        val interestsEnabled get() = scope.interestsEnabled
        fun stage(id: UUID) = stages.firstOrNull { it.id == id }

        // Interests the user may see and assign (all of them with "see all").
        val usableInterests get() = interests.filter { scope.seesAll || it.id in scope.interests }

        // Who may choose a lead's responsible user: assigners and users that see every lead.
        val canAssign get() = permissions.has(Assign) || scope.seesAll

        companion object {
            fun load(tenant: Connection, permissions: ModulePermissions, serverMail: SmtpSettings? = null, publicBase: String = "") = Context(
                tenant, permissions, CrmRepository.scope(tenant, permissions),
                CrmRepository.stages(tenant), CrmRepository.interests(tenant), CrmRepository.users(tenant), serverMail, publicBase = publicBase,
            )
        }
    }

    fun build(
        screen: String,
        context: Context,
        params: Map<String, String>,
        notice: UiNotice? = null,
        submitted: Map<String, String>? = null,
    ): UiScreen {
        val permission = permissionFor(screen)!!
        val section = sections.firstOrNull { it.id == screen }
        val title = section?.label ?: "Lead"
        if (!context.permissions.has(permission) || section?.needsInterests == true && !context.interestsEnabled) {
            return frame(context, screen, title, UiNotice("No tienes permiso para ver esta seccion.", UiTone.Danger), emptyList())
        }
        if (screen == "properties") {
            val lead = params["lead"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmRepository.lead(context.tenant, it) }
                ?.takeIf { context.scope.canSee(it.interestId, it.ownerUserId) }
                ?: return frame(context, "leads", "Propiedades", UiNotice("Ese lead no existe o no tienes acceso.", UiTone.Warning), emptyList())
            return frame(context, "leads", "Propiedades", notice, CrmWordPressScreens.properties(context, lead, params), showTitle = false)
        }
        if (screen == "lead") {
            val lead = params["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmRepository.lead(context.tenant, it) }
                ?.takeIf { context.scope.canSee(it.interestId, it.ownerUserId) }
                ?: return frame(context, "leads", "Lead", notice ?: UiNotice("Ese lead no existe o no tienes acceso.", UiTone.Warning), leads(context, emptyMap()))
            return frame(context, "leads", lead.name, notice, leadDetail(context, lead, submitted), showTitle = false)
        }
        val body = when (screen) {
            "pipeline" -> pipeline(context, params)
            "leads" -> leads(context, params)
            "reception" -> reception(context, params)
            "followups" -> followUps(context, params)
            "new" -> newLead(context, submitted)
            "assignments" -> CrmAdminScreens.assignments(context)
            "mail" -> CrmAdminScreens.mail(context, submitted)
            "templates" -> CrmAdminScreens.templates(context, params, submitted)
            "spam" -> CrmAdminScreens.spam(context, submitted)
            "team" -> CrmAdminScreens.team(context)
            "integrations" -> CrmIntegrationScreens.integrations(context, params, submitted)
            else -> settings(context, params, submitted)
        }
        return frame(context, screen, title, notice, body)
    }

    private fun frame(context: Context, selected: String, title: String, notice: UiNotice?, body: List<UiNode>, showTitle: Boolean = true) = UiScreen(
        title = title,
        navigation = UiNavigation(
            title = CrmModule.displayName,
            items = sections
                .filter { context.permissions.has(it.permission) && (!it.needsInterests || context.interestsEnabled) }
                .map { UiNavItem(it.id, it.label, UiNavigate(it.id)) },
            selected = selected,
        ),
        notice = notice,
        body = (if (showTitle) listOf<UiNode>(UiText(title, UiTextStyle.Title)) else emptyList()) + body,
    )

    // ---- Filters ----

    // Filter values travel as the names shown in the dropdowns (fInterest, fOwner, fStage, q).
    fun filter(context: Context, params: Map<String, String>): CrmLeadFilter {
        val interest = params["fInterest"].orEmpty()
        val owner = params["fOwner"].orEmpty()
        return CrmLeadFilter(
            interestId = context.interests.firstOrNull { it.name == interest }?.id,
            withoutInterest = interest == CrmForms.NoInterest,
            ownerUserId = if (owner == MineOption) context.scope.userId else context.users.firstOrNull { it.name == owner }?.id,
            unassigned = owner == CrmForms.Unassigned,
            stageId = context.stages.firstOrNull { it.name == params["fStage"] }?.id,
            search = params["q"].orEmpty().take(100),
        )
    }

    private fun filterParams(params: Map<String, String>) = params.filterKeys { it in setOf("fInterest", "fOwner", "fStage", "q") }.filterValues { it.isNotBlank() }

    private fun filterForm(context: Context, screen: String, params: Map<String, String>, withStage: Boolean, withSearch: Boolean): UiNode {
        val fields = buildList {
            if (withSearch) add(UiField("q", "Buscar", placeholder = "Nombre, correo, telefono, origen o mensaje"))
            if (context.interestsEnabled) {
                val options = listOf(AllOption) + context.usableInterests.map { it.name } + listOfNotNull(CrmForms.NoInterest.takeIf { context.scope.seesAll || context.scope.reception })
                add(UiField("fInterest", "Interes", type = "dropdown", options = options))
            }
            val owners = listOf(AllOption) + listOfNotNull(MineOption.takeIf { context.scope.userId != null }) + CrmForms.Unassigned + context.users.map { it.name }
            add(UiField("fOwner", "Responsable", type = "dropdown", options = owners))
            if (withStage) add(UiField("fStage", "Etapa", type = "dropdown", options = listOf(AllOption) + context.stages.map { it.name }))
        }
        val values = fields.associate { it.key to (params[it.key] ?: if (it.type == "dropdown") AllOption else "") }
        return UiCard(
            listOf(
                UiForm(
                    "filters", fields, values,
                    submit = UiButton("Filtrar", UiNavigate(screen, formId = "filters"), style = "secondary"),
                    minFieldWidth = 200,
                ),
            ),
        )
    }

    // ---- Pipeline ----

    private fun pipeline(context: Context, params: Map<String, String>): List<UiNode> {
        val filter = filter(context, params)
        val (byStage, totals) = CrmRepository.board(context.tenant, context.scope, filter, BoardPerStage)
        val canMove = context.permissions.has(Manage)
        val keep = filterParams(params)
        val columns = context.stages.map { stage ->
            val cards = byStage[stage.id].orEmpty()
            val total = totals[stage.id] ?: 0
            UiBoardColumn(
                id = stage.id.toString(),
                title = stage.name,
                tone = stage.tone.id,
                caption = if (total > cards.size) "Mostrando ${cards.size} de $total" else "",
                cards = cards.map { card(context, it, stage) },
                footer = if (total > cards.size) UiButton("Ver todos", UiNavigate("leads", keep + ("fStage" to stage.name)), style = "secondary") else null,
            )
        }
        return listOf(
            summary(context),
            UiRow(
                listOfNotNull(
                    UiButton("Nuevo lead", UiNavigate("new")).takeIf { canMove },
                    UiButton("Ver como lista", UiNavigate("leads", keep), style = "secondary"),
                ),
            ),
            filterForm(context, "pipeline", params, withStage = false, withSearch = true),
            UiBoard(
                columns = columns,
                move = if (canMove) UiRequest(UiRoute + "/pipeline", params = keep + ("op" to "move")) else null,
                emptyText = "Crea etapas en Configuracion.",
            ),
        )
    }

    private fun summary(context: Context): UiNode {
        val summary = CrmRepository.summary(context.tenant, context.scope, context.today.minusDays(29), context.today)
        return UiGrid(
            listOf(
                UiCard(listOf(UiMetric("Leads abiertos", summary.open.toString()))),
                UiCard(listOf(UiMetric("Nuevos (30 dias)", summary.createdSince.toString()))),
                UiCard(listOf(UiMetric("Ganados (30 dias)", summary.wonSince.toString(), captionTone = UiTone.Success))),
                UiCard(
                    listOf(
                        UiMetric(
                            "Seguimientos vencidos", summary.overdue.toString(),
                            caption = if (summary.overdue > 0) "Revisa Seguimientos" else "Al dia",
                            captionTone = if (summary.overdue > 0) UiTone.Danger else UiTone.Success,
                        ),
                    ),
                ),
            ),
            minItemWidth = 180,
        )
    }

    private fun badges(context: Context, lead: CrmLead, stage: CrmStage?): List<UiBadge> = buildList {
        if (context.interestsEnabled) {
            add(if (lead.interestId == null) UiBadge(CrmForms.NoInterest, UiTone.Warning) else UiBadge(context.names.interest(lead.interestId), UiTone.Info))
        }
        if (stage?.kind == StageKind.Open) followUpState(lead.followUpOn, context.today)?.let { (label, tone) -> add(UiBadge(label, tone)) }
        lead.suggestedOwnerUserId?.let { add(UiBadge("Sugerido: ${context.names.user(it)}", UiTone.Info)) }
        if (lead.reviewReason.isNotEmpty()) add(UiBadge("Por revisar", UiTone.Danger))
    }

    private fun card(context: Context, lead: CrmLead, stage: CrmStage) = UiBoardCard(
        id = lead.id.toString(),
        title = lead.name,
        subtitle = lead.message.ifBlank { lead.source }.replace('\n', ' ').let { if (it.length > 90) it.take(87) + "..." else it },
        details = listOfNotNull(
            "Responsable: ${context.names.user(lead.ownerUserId)}",
            dates(lead)?.let { "Fechas: $it" },
            lead.estimatedValue?.let { "Valor: ${it.toPlainString()}" },
        ),
        badges = badges(context, lead, stage),
        action = UiNavigate("lead", mapOf("id" to lead.id.toString())),
    )

    private fun dates(lead: CrmLead): String? = when {
        lead.targetStart != null && lead.targetEnd != null -> "${lead.targetStart} a ${lead.targetEnd}"
        else -> (lead.targetStart ?: lead.targetEnd)?.toString()
    }

    // ---- Lists ----

    private fun listItem(context: Context, lead: CrmLead) = UiListItem(
        title = lead.name,
        subtitle = listOf(context.stage(lead.stageId)?.name.orEmpty(), lead.email, lead.phone, context.names.user(lead.ownerUserId))
            .filter { it.isNotBlank() }.joinToString(" - "),
        trailing = lead.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
        badges = badges(context, lead, context.stage(lead.stageId)),
        action = UiNavigate("lead", mapOf("id" to lead.id.toString())),
    )

    private fun pager(screen: String, params: Map<String, String>, page: Int, total: Int): UiNode? {
        val pages = (total + PageSize - 1) / PageSize
        if (pages <= 1) return null
        val keep = filterParams(params)
        return UiRow(
            listOfNotNull(
                UiButton("Anterior", UiNavigate(screen, keep + ("page" to (page - 1).toString())), style = "secondary").takeIf { page > 1 },
                UiText("Pagina $page de $pages", UiTextStyle.Caption),
                UiButton("Siguiente", UiNavigate(screen, keep + ("page" to (page + 1).toString())), style = "secondary").takeIf { page < pages },
            ),
        )
    }

    private fun leads(context: Context, params: Map<String, String>): List<UiNode> {
        val filter = filter(context, params)
        val total = CrmRepository.count(context.tenant, context.scope, filter)
        val page = (params["page"]?.toIntOrNull() ?: 1).coerceIn(1, maxOf(1, (total + PageSize - 1) / PageSize))
        val leads = CrmRepository.leads(context.tenant, context.scope, filter, PageSize, (page - 1) * PageSize)
        return listOfNotNull(
            filterForm(context, "leads", params, withStage = true, withSearch = true),
            UiCard(
                listOfNotNull(UiList(leads.map { listItem(context, it) }, emptyText = "No hay leads con estos filtros."), pager("leads", params, page, total)),
                title = "$total lead(s)",
                actions = listOfNotNull(UiButton("Nuevo lead", UiNavigate("new")).takeIf { context.permissions.has(Manage) }),
            ),
        )
    }

    private fun reception(context: Context, params: Map<String, String>): List<UiNode> {
        val filter = CrmLeadFilter(withoutInterest = true)
        val total = CrmRepository.count(context.tenant, context.scope, filter)
        val page = (params["page"]?.toIntOrNull() ?: 1).coerceIn(1, maxOf(1, (total + PageSize - 1) / PageSize))
        val leads = CrmRepository.leads(context.tenant, context.scope, filter, PageSize, (page - 1) * PageSize)
        return listOf(
            UiText("Leads que todavia no tienen interes. Abre cada uno, elige su interes y quedara visible para los usuarios de ese interes."),
            UiCard(
                listOfNotNull(UiList(leads.map { listItem(context, it) }, emptyText = "No hay leads por categorizar."), pager("reception", params, page, total)),
                title = "$total por categorizar",
            ),
        )
    }

    private fun followUps(context: Context, params: Map<String, String>): List<UiNode> {
        val filter = filter(context, params)
        val leads = CrmRepository.followUps(context.tenant, context.scope, filter, context.today.plusDays(7), 200)
        val overdue = leads.filter { it.followUpOn!!.isBefore(context.today) }
        val today = leads.filter { it.followUpOn == context.today }
        val upcoming = leads.filter { it.followUpOn!!.isAfter(context.today) }
        fun group(title: String, items: List<CrmLead>, empty: String) = UiCard(
            listOf(UiList(items.map { listItem(context, it).copy(trailing = it.followUpOn.toString()) }, emptyText = empty)),
            title = "$title (${items.size})",
        )
        return listOf(
            filterForm(context, "followups", params, withStage = false, withSearch = false),
            group("Vencidos", overdue, "Sin seguimientos vencidos."),
            group("Hoy", today, "Nada para hoy."),
            group("Proximos 7 dias", upcoming, "Sin seguimientos en la proxima semana."),
        )
    }

    // ---- Lead form ----

    private fun leadFields(context: Context, readOnly: Boolean, currentInterest: UUID?): List<UiField> = buildList {
        add(UiField("name", "Nombre", required = true, readOnly = readOnly, section = "Contacto"))
        add(UiField("email", "Correo", type = "email", readOnly = readOnly, section = "Contacto"))
        add(UiField("phone", "Telefono", type = "phone", readOnly = readOnly, section = "Contacto"))
        add(UiField("language", "Idioma", readOnly = readOnly, section = "Contacto"))
        add(UiField("stage", "Etapa", type = "dropdown", required = true, readOnly = readOnly, options = context.stages.map { it.name }, section = "Seguimiento"))
        if (context.interestsEnabled) {
            val usable = context.usableInterests.map { it.name }
            val current = currentInterest?.let { context.names.interest(it) }
            val options = listOfNotNull(CrmForms.NoInterest.takeIf { context.scope.seesAll || context.scope.reception || currentInterest == null }) +
                (usable + listOfNotNull(current)).distinct()
            add(UiField("interest", "Interes", type = "dropdown", readOnly = readOnly, options = options, section = "Seguimiento"))
        }
        add(
            UiField(
                "owner", "Responsable", type = "dropdown", readOnly = readOnly || !context.canAssign,
                options = listOf(CrmForms.Unassigned) + context.users.map { it.name }, section = "Seguimiento",
            ),
        )
        add(UiField("followUpOn", "Proximo seguimiento", type = "date", readOnly = readOnly, section = "Seguimiento"))
        add(UiField("source", "Origen", readOnly = readOnly, placeholder = "Sitio web, WhatsApp, referido...", section = "Seguimiento"))
        add(UiField("targetStart", "Fecha de inicio", type = "date", readOnly = readOnly, help = "Reserva, visita o mudanza.", section = "Lo que busca"))
        add(UiField("targetEnd", "Fecha de fin", type = "date", readOnly = readOnly, section = "Lo que busca"))
        add(UiField("estimatedValue", "Valor estimado", type = "number", readOnly = readOnly, section = "Lo que busca"))
        add(UiField("message", "Mensaje del lead", type = "textarea", readOnly = readOnly, section = "Mensaje y detalles"))
        add(UiField("details", "Detalles", type = "textarea", readOnly = readOnly, help = "Cotizacion, personas, preferencias...", section = "Mensaje y detalles"))
    }

    fun leadValues(context: Context, lead: CrmLead): Map<String, String> = mapOf(
        "name" to lead.name,
        "email" to lead.email,
        "phone" to lead.phone,
        "language" to lead.language,
        "stage" to context.stage(lead.stageId)?.name.orEmpty(),
        "interest" to context.names.interest(lead.interestId),
        "owner" to context.names.user(lead.ownerUserId),
        "followUpOn" to lead.followUpOn?.toString().orEmpty(),
        "source" to lead.source,
        "targetStart" to lead.targetStart?.toString().orEmpty(),
        "targetEnd" to lead.targetEnd?.toString().orEmpty(),
        "estimatedValue" to lead.estimatedValue?.toPlainString().orEmpty(),
        "message" to lead.message,
        "details" to lead.details,
    )

    private fun newLead(context: Context, submitted: Map<String, String>?): List<UiNode> {
        if (context.stages.isEmpty()) return listOf(UiText("Crea al menos una etapa en Configuracion.", tone = UiTone.Warning))
        val defaults = mapOf(
            "stage" to context.stages.first().name,
            "interest" to if (context.interestsEnabled && !context.scope.seesAll && !context.scope.reception) context.usableInterests.firstOrNull()?.name.orEmpty() else CrmForms.NoInterest,
            "owner" to (context.scope.userId?.let { id -> context.users.firstOrNull { it.id == id }?.name } ?: CrmForms.Unassigned),
        )
        return listOf(
            UiText("Correo y telefono son opcionales. Si ya existe un cliente con ese correo (o, sin correo, con ese telefono), el lead se vincula a el sin cambiar sus datos."),
            UiCard(
                listOf(
                    UiForm(
                        "lead", leadFields(context, readOnly = false, currentInterest = null), defaults + submitted.orEmpty(),
                        submit = UiButton("Crear lead", UiRequest(UiRoute + "/new", params = mapOf("op" to "create"), formId = "lead")),
                    ),
                ),
            ),
        )
    }

    private fun leadDetail(context: Context, lead: CrmLead, submitted: Map<String, String>?): List<UiNode> {
        val stage = context.stage(lead.stageId)
        val canEdit = context.permissions.has(Manage)
        val id = mapOf("id" to lead.id.toString())
        val header = UiRow(
            listOfNotNull(
                UiText(lead.name, UiTextStyle.Title),
                stage?.let { UiStatus(it.name, it.tone.id) },
                UiText("Creado ${lead.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()}", UiTextStyle.Caption),
            ),
        )
        val actions = UiRow(
            listOfNotNull(
                UiButton("Volver al pipeline", UiNavigate("pipeline"), style = "secondary"),
                UiButton(
                    "Eliminar lead", UiRequest(UiRoute + "/lead", params = id + ("op" to "delete"), confirm = "Se elimina el lead y su actividad; el cliente queda en Clientes. Continuar?"),
                    style = "danger",
                ).takeIf { context.permissions.has(Delete) },
            ),
        )
        val form = UiCard(
            listOf(
                UiForm(
                    "lead", leadFields(context, readOnly = !canEdit, currentInterest = lead.interestId), leadValues(context, lead) + submitted.orEmpty(),
                    submit = if (canEdit) UiButton("Guardar cambios", UiRequest(UiRoute + "/lead", params = id + ("op" to "save"), formId = "lead")) else null,
                ),
            ),
            title = "Datos del lead",
        )
        val note = UiCard(
            listOf(
                UiForm(
                    "note", listOf(UiField("note", "Nota", type = "textarea", required = true, placeholder = "Llamada, respuesta enviada, acuerdos...")),
                    submit = UiButton("Agregar nota", UiRequest(UiRoute + "/lead", params = id + ("op" to "note"), formId = "note")),
                ),
            ),
            title = "Agregar nota",
        )
        val timeline = UiCard(
            listOf(UiTimeline(CrmRepository.activity(context.tenant, lead.id, 100).map { entry(it) })),
            title = "Actividad",
        )
        val review = lead.reviewReason.takeIf { it.isNotEmpty() }?.let { reason ->
            UiCard(
                listOf(UiText(reason, tone = UiTone.Danger)),
                title = "Por revisar",
                actions = listOfNotNull(UiButton("Marcar revisado", UiRequest(UiRoute + "/lead", params = id + ("op" to "reviewed")), style = "secondary").takeIf { canEdit }),
            )
        }
        val suggestion = lead.suggestedOwnerUserId?.let { suggested ->
            UiCard(
                listOf(UiText("El origen del lead sugiere como responsable a ${context.names.user(suggested)}.")),
                title = "Responsable sugerido",
                actions = if (context.canAssign) listOf(
                    UiButton("Aprobar", UiRequest(UiRoute + "/lead", params = id + ("op" to "approve"))),
                    UiButton("Descartar", UiRequest(UiRoute + "/lead", params = id + ("op" to "discard")), style = "secondary"),
                ) else emptyList(),
            )
        }
        val conversation = CrmAdminScreens.conversation(context, lead, canEdit, submitted)
        val items = CrmWordPressScreens.items(context, lead, canEdit)
        return listOfNotNull(header, actions, review, suggestion, form, items, conversation, note.takeIf { canEdit }, timeline)
    }

    private fun entry(activity: CrmActivity): UiTimelineEntry {
        val (title, tone) = when (activity.kind) {
            "created" -> "Lead creado" to UiTone.Info
            "note" -> "Nota" to UiTone.Neutral
            "stage" -> "Cambio de etapa" to UiTone.Success
            "owner" -> "Responsable" to UiTone.Warning
            "interest" -> "Interes" to UiTone.Info
            else -> "Actualizado" to UiTone.Neutral
        }
        val body = if (activity.kind == "stage" && activity.fromStage != null && activity.toStage != null) "${activity.fromStage} -> ${activity.toStage}" else activity.body
        return UiTimelineEntry(title, body, "${activity.author} - ${time(activity.createdAt)}", tone)
    }

    private fun time(instant: Instant) = timeFormat.format(instant.atZone(ZoneId.systemDefault()))

    // ---- Settings ----

    private fun settings(context: Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val editingStage = params["stage"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { context.stage(it) }
        val editingInterest = params["interest"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id -> context.interests.firstOrNull { it.id == id } }
        val nodes = mutableListOf<UiNode>()

        nodes += UiCard(
            listOf(
                UiText(
                    "Activa los intereses si los leads se reparten por linea de negocio (surf, yoga, eventos...) o por tipo de propiedad. " +
                        "Cada usuario vera solo los leads de los intereses que le asignes; quien tenga \"Ver todos los leads\" ve todo y los leads " +
                        "sin interes esperan en Recepcion.",
                ),
                UiForm(
                    "interestsToggle",
                    listOf(UiField("enabled", "Clasificar leads por interes", type = "boolean")),
                    mapOf("enabled" to context.interestsEnabled.toString()),
                    submit = UiButton("Guardar", UiRequest(UiRoute + "/settings", params = mapOf("op" to "interests"), formId = "interestsToggle"), style = "secondary"),
                ),
            ),
            title = "Intereses",
        )

        if (context.interestsEnabled) {
            val rows = context.interests.map { interest ->
                UiRow(
                    listOf(
                        UiText(interest.name),
                        UiButton("Renombrar", UiNavigate("settings", mapOf("interest" to interest.id.toString())), style = "secondary"),
                        UiButton(
                            "Eliminar",
                            UiRequest(UiRoute + "/settings", params = mapOf("op" to "interestDelete", "id" to interest.id.toString()), confirm = "Sus leads volveran a Recepcion. Continuar?"),
                            style = "danger",
                        ),
                    ),
                )
            }
            val interestParams = mapOf("op" to "interestSave") + listOfNotNull(editingInterest?.let { "id" to it.id.toString() })
            nodes += UiCard(
                rows.ifEmpty { listOf(UiText("Aun no hay intereses.", tone = UiTone.Warning)) } + UiForm(
                    "interest",
                    listOf(UiField("name", if (editingInterest == null) "Nuevo interes" else "Nuevo nombre de ${editingInterest.name}", required = true)),
                    mapOf("name" to (submitted?.get("name") ?: editingInterest?.name.orEmpty())),
                    submit = UiButton(if (editingInterest == null) "Agregar interes" else "Guardar nombre", UiRequest(UiRoute + "/settings", params = interestParams, formId = "interest")),
                ),
                title = "Catalogo de intereses",
            )

            if (context.interests.isNotEmpty()) {
                val assigned = CrmRepository.userInterests(context.tenant)
                val userForms = context.users.map { user ->
                    val formId = "user_${user.id}"
                    UiCard(
                        listOf(
                            UiForm(
                                formId,
                                context.interests.map { UiField(it.id.toString(), it.name, type = "boolean") },
                                context.interests.associate { it.id.toString() to (it.id in assigned[user.id].orEmpty()).toString() },
                                submit = UiButton("Guardar", UiRequest(UiRoute + "/settings", params = mapOf("op" to "userInterests", "user" to user.id.toString()), formId = formId), style = "secondary"),
                                minFieldWidth = 160,
                            ),
                        ),
                        title = user.name,
                    )
                }
                nodes += UiCard(
                    listOf(UiText("Marca los intereses de cada usuario. Los usuarios con \"Ver todos los leads\" y el dueno de la empresa ven todo.")) +
                        userForms.ifEmpty { listOf(UiText("Ningun usuario tiene permisos del CRM todavia.", tone = UiTone.Warning)) },
                    title = "Intereses por usuario",
                )
            }
        }

        val stageRows = context.stages.map { stage ->
            UiRow(
                listOf(
                    UiStatus("${stage.position}. ${stage.name} (${stage.kind.label})", stage.tone.id),
                    UiButton("Editar", UiNavigate("settings", mapOf("stage" to stage.id.toString())), style = "secondary"),
                    UiButton(
                        "Eliminar",
                        UiRequest(UiRoute + "/settings", params = mapOf("op" to "stageDelete", "id" to stage.id.toString()), confirm = "Eliminar la etapa ${stage.name}?"),
                        style = "danger",
                    ),
                ),
            )
        }
        val stageValues = editingStage?.let { mapOf("name" to it.name, "kind" to it.kind.label, "tone" to it.tone.label, "position" to it.position.toString()) }
            ?: mapOf("kind" to StageKind.Open.label, "tone" to StageTone.Neutral.label, "position" to ((context.stages.maxOfOrNull { it.position } ?: -1) + 1).toString())
        val stageParams = mapOf("op" to "stageSave") + listOfNotNull(editingStage?.let { "id" to it.id.toString() })
        nodes += UiCard(
            stageRows + UiForm(
                "stage",
                listOf(
                    UiField("name", "Nombre", required = true, section = if (editingStage == null) "Nueva etapa" else "Editar ${editingStage.name}"),
                    UiField("kind", "Tipo", type = "dropdown", options = StageKind.entries.map { it.label }, help = "Ganada y Perdida cierran el lead.", section = if (editingStage == null) "Nueva etapa" else "Editar ${editingStage.name}"),
                    UiField("tone", "Color", type = "dropdown", options = StageTone.entries.map { it.label }, section = if (editingStage == null) "Nueva etapa" else "Editar ${editingStage.name}"),
                    UiField("position", "Posicion", type = "number", help = "Orden en el pipeline, de izquierda a derecha.", section = if (editingStage == null) "Nueva etapa" else "Editar ${editingStage.name}"),
                ),
                stageValues + submitted.orEmpty().filterKeys { it in setOf("name", "kind", "tone", "position") },
                submit = UiButton(if (editingStage == null) "Agregar etapa" else "Guardar etapa", UiRequest(UiRoute + "/settings", params = stageParams, formId = "stage")),
                minFieldWidth = 200,
            ),
            title = "Etapas del pipeline",
        )

        if (CrmRepository.leadCount(context.tenant) == 0) {
            nodes += UiCard(
                listOf(
                    UiText("Mientras no haya leads puedes reemplazar las etapas por una plantilla."),
                    UiRow(
                        CrmTemplates.all.map { template ->
                            UiButton(
                                template.label,
                                UiRequest(
                                    UiRoute + "/settings", params = mapOf("op" to "template", "template" to template.id),
                                    confirm = "Se reemplazan las etapas actuales por: ${template.stages.joinToString { it.name }}. Continuar?",
                                ),
                                style = "secondary",
                            )
                        },
                    ),
                ),
                title = "Plantillas de etapas",
            )
        }
        return nodes
    }
}
