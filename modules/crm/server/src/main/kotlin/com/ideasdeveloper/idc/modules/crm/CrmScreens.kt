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
import com.ideasdeveloper.idc.ui.UiFieldCondition
import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFiles
import com.ideasdeveloper.idc.ui.UiSuggest
import com.ideasdeveloper.idc.ui.UiSuggestions
import com.ideasdeveloper.idc.ui.UiSuggestion
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNavItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiOpenExtensions
import com.ideasdeveloper.idc.ui.UiOpenModule
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

    const val MentionedBadge = "Mencionado"
    private const val BoardPerStage = 30
    private const val PageSize = 50
    private const val AllOption = "Todos"
    private const val MineOption = "Mis leads"
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // [alsoPermission] opens the section too (assigners reach the suggestions in "Sin calificar").
    private data class Section(val id: String, val label: String, val permission: String, val alsoPermission: String? = null) {
        fun allows(permissions: ModulePermissions) = permissions.has(permission) || alsoPermission != null && permissions.has(alsoPermission)
    }

    // The menu: Nuevo lead, Leads (pipeline and list), Sin calificar, Seguimientos and Configuracion (tabs).
    private val sections = listOf(
        Section("new", "Nuevo lead", Manage),
        Section("leads", "Leads", View),
        // Id "reception" and the Reception permission stay: "Recepcion" is a role.
        Section("reception", "Sin calificar", Reception, alsoPermission = Assign),
        Section("followups", "Seguimientos", View),
        Section("settings", "Configuracion", Settings),
    )

    /** A tab of "Configuracion": [screen] is the id that opens it (the old section ids), [permission] who sees the tab. */
    class SettingsTab(val screen: String, val label: String, val permission: String)

    private val settingsTabs = listOf(
        SettingsTab("settings", "General", Settings),
        SettingsTab("automations", "Automatizaciones", Settings),
        SettingsTab("team", "Equipo", Settings),
        SettingsTab("mail", "Correo", Settings),
        SettingsTab("templates", "Plantillas", Settings),
        SettingsTab("spam", "Anti-spam", Settings),
        SettingsTab("integrations", "Integraciones", Settings),
        SettingsTab("import", "Importar", Settings),
    )

    // Screens outside the menu: the lead detail, the "pipeline" view of Leads, the other tabs of Configuracion
    // (their old section ids) and the screens opened from a lead or an import.
    private val hidden = mapOf("lead" to View, "pipeline" to View, "properties" to Manage, "rooms" to Manage, "importjob" to Settings) +
        settingsTabs.filter { it.screen != "settings" }.associate { it.screen to it.permission }

    // Old screen ids that still open: "Asignaciones" now lives in "Sin calificar" and "Acciones en lote" in the list of Leads.
    private val aliases = mapOf("assignments" to "reception", "bulk" to "leads")

    fun canonical(id: String) = aliases[id] ?: id

    // View of Leads an old route opens ("pipeline", "bulk"); null: the one the user used last.
    private val forcedViews = mapOf("pipeline" to "pipeline", "bulk" to "list")

    fun forcedView(id: String): String? = forcedViews[id]

    const val ViewKey = "view"
    const val ViewPipeline = "pipeline"
    const val ViewList = "list"
    private val leadViewIds = setOf(ViewPipeline, ViewList)

    /** The view sent with the request wins over the remembered one; none (or an unknown one) keeps the remembered one. */
    fun resolveLeadsView(param: String?, stored: String): String = param?.takeIf { it in leadViewIds } ?: stored.takeIf { it in leadViewIds } ?: ViewPipeline

    // The tab of Configuracion a screen belongs to (null: it is not in Configuracion).
    fun settingsTabOf(id: String): String? = canonical(id).let { screen -> if (screen == "importjob") "import" else screen.takeIf { s -> settingsTabs.any { it.screen == s } } }

    /** Screens of the tabs of Configuracion the user sees (a tab with its own permission hides without it). */
    fun visibleTabs(permissions: ModulePermissions): List<String> = settingsTabs.filter { permissions.has(it.permission) }.map { it.screen }

    // Menu item a screen belongs to.
    private fun menuItemOf(screen: String): String = when {
        screen in setOf("pipeline", "leads", "lead", "rooms", "properties") -> "leads"
        settingsTabOf(screen) != null -> "settings"
        else -> screen
    }

    fun isScreen(id: String) = canonical(id).let { screen -> sections.any { it.id == screen } || screen in hidden }

    fun permissionFor(screen: String): String? = canonical(screen).let { id -> sections.firstOrNull { it.id == id }?.permission ?: hidden[id] }

    // First screen a user opens: the leads, then what is waiting for them, then the rest.
    private val homeOrder = listOf("leads", "reception", "followups", "new", "settings")

    fun home(permissions: ModulePermissions): String? = homeOrder.firstOrNull { canOpen(it, permissions) }

    // Whether the user may open the screen [id] (a section with an alternative permission counts both).
    fun canOpen(id: String, permissions: ModulePermissions): Boolean {
        val screen = canonical(id)
        sections.firstOrNull { it.id == screen }?.let { return it.allows(permissions) }
        return hidden[screen]?.let { permissions.has(it) } ?: false
    }

    // Ids of the sections the user sees in the menu ([interestsEnabled]: no section depends on it today).
    fun menu(permissions: ModulePermissions, interestsEnabled: Boolean): List<String> =
        sections.filter { it.allows(permissions) }.map { it.id }

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
        // Custom lead fields defined in Configuracion.
        val fields: List<CrmField> = emptyList(),
        // Sections of the custom fields and the date fields that are the check-in and check-out.
        val sections: List<CrmFieldSection> = emptyList(),
        val stay: CrmStayMapping = CrmStayMapping.None,
    ) {
        val names = CrmNames(users, interests)
        val interestsEnabled get() = scope.interestsEnabled
        fun stage(id: UUID) = stages.firstOrNull { it.id == id }

        // Interests the user may see and assign (all of them with "see all").
        val usableInterests get() = interests.filter { scope.seesAll || it.id in scope.interests }

        // Who may choose a lead's responsible user: assigners and users that see every lead.
        val canAssign get() = permissions.has(Assign) || scope.seesAll

        // The leads where the user has an active mention (one read, only when needed).
        val mentionedLeadIds: Set<UUID> by lazy { scope.userId?.let { CrmMentions.leadIds(tenant, it) }.orEmpty() }

        /** How the user reaches [lead]: with their scope (full) or only through a mention (read and notes). Writes ask [CrmMentions.LeadAccess.canWrite]. */
        fun readAccess(lead: CrmLead): CrmMentions.LeadAccess =
            if (scope.canSee(lead.interestId, lead.ownerUserIds)) CrmMentions.LeadAccess.Full
            else CrmMentions.leadAccess(scope, lead.interestId, lead.ownerUserIds, lead.id in mentionedLeadIds, lead.archivedAt != null)

        // Users a note can mention: anybody with access to the CRM.
        val mentionable: List<CrmUser> by lazy { CrmRepository.mentionableUsers(tenant) }

        companion object {
            fun load(tenant: Connection, permissions: ModulePermissions, serverMail: SmtpSettings? = null, publicBase: String = "") = Context(
                tenant, permissions, CrmRepository.scope(tenant, permissions),
                CrmRepository.stages(tenant), CrmRepository.interests(tenant), CrmRepository.users(tenant), serverMail, publicBase = publicBase,
                fields = CrmFields.all(tenant), sections = CrmFieldSections.all(tenant), stay = CrmStay.load(tenant),
            )
        }
    }

    fun build(
        requested: String,
        context: Context,
        params: Map<String, String>,
        notice: UiNotice? = null,
        submitted: Map<String, String>? = null,
    ): UiScreen {
        val screen = canonical(requested)
        val permission = permissionFor(screen)!!
        val section = sections.firstOrNull { it.id == screen }
        val title = when {
            section != null -> section.label
            screen == "pipeline" -> "Leads"
            settingsTabOf(screen) != null -> "Configuracion"
            else -> "Lead"
        }
        val allowed = section?.allows(context.permissions) ?: context.permissions.has(permission)
        // Old routes: "pipeline" and "bulk" open their own view of Leads; the other screens ignore it.
        val forced = forcedView(requested)
        if (!allowed) {
            return frame(context, screen, title, UiNotice("No tienes permiso para ver esta seccion.", UiTone.Danger), emptyList())
        }
        if (screen == "rooms") {
            val lead = params["lead"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmRepository.lead(context.tenant, it) }
                ?.takeIf { context.scope.canSee(it.interestId, it.ownerUserIds) }
                ?: return frame(context, "leads", "Habitaciones", UiNotice("Ese lead no existe o no tienes acceso.", UiTone.Warning), emptyList())
            return frame(context, "leads", "Habitaciones", notice, CrmCloudbedsScreens.rooms(context, lead, params), showTitle = false)
        }
        if (screen == "properties") {
            val lead = params["lead"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmRepository.lead(context.tenant, it) }
                ?.takeIf { context.scope.canSee(it.interestId, it.ownerUserIds) }
                ?: return frame(context, "leads", "Propiedades", UiNotice("Ese lead no existe o no tienes acceso.", UiTone.Warning), emptyList())
            return frame(context, "leads", "Propiedades", notice, CrmWordPressScreens.properties(context, lead, params), showTitle = false)
        }
        if (screen == "lead") {
            // Opening the lead also works through a mention (read-only, with notes): see CrmMentions.LeadAccess.
            val lead = params["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmRepository.lead(context.tenant, it) }
                ?.takeIf { context.readAccess(it).canRead }
                ?: return frame(context, "leads", "Lead", notice ?: UiNotice("Ese lead no existe o no tienes acceso.", UiTone.Warning), leadViews(context, null, emptyMap()))
            return frame(context, "leads", lead.name, notice, leadDetail(context, lead, submitted), showTitle = false)
        }
        val body = when (screen) {
            "pipeline", "leads" -> leadViews(context, forced, params)
            "reception" -> reception(context, params)
            "followups" -> followUps(context, params)
            "new" -> newLead(context, submitted)
            "mail" -> tabBar(context, screen) + CrmAdminScreens.mail(context, submitted)
            "templates" -> tabBar(context, screen) + CrmAdminScreens.templates(context, params, submitted)
            "spam" -> tabBar(context, screen) + CrmAdminScreens.spam(context, submitted)
            "team" -> tabBar(context, screen) + CrmAdminScreens.team(context)
            "integrations" -> tabBar(context, screen) + CrmIntegrationScreens.integrations(context, params, submitted)
            "import" -> tabBar(context, screen) + CrmImportScreens.imports(context, params)
            "importjob" -> tabBar(context, screen) + CrmImportScreens.job(context, params)
            "automations" -> tabBar(context, screen) + automations(context, params, submitted)
            else -> tabBar(context, screen) + settings(context, params, submitted)
        }
        return frame(context, screen, title, notice, body)
    }

    private fun frame(context: Context, selected: String, title: String, notice: UiNotice?, body: List<UiNode>, showTitle: Boolean = true) = UiScreen(
        title = title,
        navigation = UiNavigation(
            title = CrmModule.displayName,
            items = sections
                .filter { it.id in menu(context.permissions, context.interestsEnabled) }
                .map { UiNavItem(it.id, it.label, UiNavigate(it.id)) },
            selected = menuItemOf(selected),
        ),
        notice = notice,
        body = (if (showTitle) listOf<UiNode>(UiText(title, UiTextStyle.Title)) else emptyList()) + body,
    )

    // Tabs of Configuracion: a row of buttons (the module navigation has one level), the current one in primary.
    private fun tabBar(context: Context, current: String): List<UiNode> {
        val selected = settingsTabOf(current)
        val tabs = settingsTabs.filter { context.permissions.has(it.permission) }
        val buttons = tabs.map { UiButton(it.label, UiNavigate(it.screen), style = if (it.screen == selected) "primary" else "secondary") }
        // Ultima pestana: abre la pantalla nativa de extensiones filtrada al CRM (apps viejas la ignoran).
        val extensions = UiButton("Extensiones", UiOpenExtensions(CrmModule.id), style = "secondary").takeIf { context.permissions.has(Settings) }
        return listOf(UiRow(buttons + listOfNotNull(extensions)))
    }

    // Tab Automatizaciones: the rules of the company (CrmAutomationScreens) and the exit rule.
    private fun automations(context: Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> =
        CrmAutomationScreens.tab(context, params, submitted) + CrmExitScreens.card(context, submitted)

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

    // Request parameter with the assigned users (ids separated by comma) a lead form was drawn with.
    const val OwnersBeforeKey = "owners0"

    private const val ShowArchivedKey = "showArchived"
    private const val ArchivedColumn = "archived"

    // [keep]: the filters and page of the view, so the user comes back to the same place.
    private fun restoreRequest(lead: CrmLead, from: String?, keep: Map<String, String> = emptyMap()) =
        UiRequest(UiRoute + "/lead", params = keep + mapOf("id" to lead.id.toString(), "op" to "restore") + listOfNotNull(from?.let { "from" to it }))

    /**
     * Cards and list rows only open the lead, so the archived ones of a view get their "Restaurar" buttons
     * in a card of their own below it (null when there is nothing to restore or the user cannot).
     */
    private fun restoreCard(context: Context, archived: List<CrmLead>, from: String, keep: Map<String, String>): UiNode? {
        if (!context.permissions.has(Manage) || archived.isEmpty()) return null
        return UiCard(
            archived.map { lead ->
                UiRow(listOf(UiText(lead.name), UiButton("Restaurar", restoreRequest(lead, from, keep), style = "secondary")))
            },
            title = "Restaurar archivados (${archived.size})",
            collapsible = true,
            expanded = false,
        )
    }

    private fun filterParams(params: Map<String, String>) = params.filterKeys { it in setOf("fInterest", "fOwner", "fStage", "q", ShowArchivedKey) }.filterValues { it.isNotBlank() }

    /** The checkbox sent with the filters wins over the remembered one; none sent keeps the remembered one. */
    fun resolveShowArchived(param: String?, stored: Boolean): Boolean = param?.let { it == "true" } ?: stored

    /**
     * "Mostrar archivados" of the lead views. Remembered per user in crm_user_settings; the server owner
     * has no user row, so it only lasts while the filter is sent.
     */
    private fun showArchived(context: Context, params: Map<String, String>): Boolean {
        val userId = context.scope.userId
        val stored = userId?.let { CrmRepository.showArchived(context.tenant, it) } ?: false
        val chosen = resolveShowArchived(params[ShowArchivedKey], stored)
        if (userId != null && chosen != stored) CrmRepository.setShowArchived(context.tenant, userId, chosen)
        return chosen
    }


    private fun filterForm(
        context: Context,
        screen: String,
        params: Map<String, String>,
        withStage: Boolean,
        withSearch: Boolean,
        // Pipeline and list: the "Mostrar archivados" checkbox, with the state [archivedShown].
        archivedShown: Boolean? = null,
        // Sent along with the form values by "Filtrar" (the view of Leads).
        submitParams: Map<String, String> = emptyMap(),
    ): UiNode {
        val fields = buildList {
            if (withSearch) add(UiField("q", "Buscar", placeholder = "Nombre, correo, telefono, origen o mensaje"))
            if (context.interestsEnabled) {
                val options = listOf(AllOption) + context.usableInterests.map { it.name } + listOfNotNull(CrmForms.NoInterest.takeIf { context.scope.seesAll || context.scope.reception })
                add(UiField("fInterest", "Interes", type = "dropdown", options = options))
            }
            val owners = listOf(AllOption) + listOfNotNull(MineOption.takeIf { context.scope.userId != null }) + CrmForms.Unassigned + context.users.map { it.name }
            add(UiField("fOwner", "Asignado", type = "dropdown", options = owners))
            if (withStage) add(UiField("fStage", "Etapa", type = "dropdown", options = listOf(AllOption) + context.stages.map { it.name }))
            if (archivedShown != null) add(UiField(ShowArchivedKey, "Mostrar archivados", type = "boolean"))
        }
        val values = fields.associate {
            it.key to when {
                it.key == ShowArchivedKey -> archivedShown.toString()
                else -> params[it.key] ?: if (it.type == "dropdown") AllOption else ""
            }
        }
        return UiCard(
            listOf(
                UiForm(
                    "filters", fields, values,
                    submit = UiButton("Filtrar", UiNavigate(screen, submitParams, formId = "filters"), style = "secondary"),
                    minFieldWidth = 200,
                ),
            ),
        )
    }

    // ---- Pipeline ----

    /**
     * "Leads": the pipeline and the list share the filters (interest, "Mis leads", search and "Mostrar archivados")
     * and a selector. [forced] is the view an old route asks for; otherwise the one sent with the request, or
     * the last one the user chose.
     */
    private fun leadViews(context: Context, forced: String?, params: Map<String, String>): List<UiNode> {
        val view = leadsView(context, forced, params)
        val showArchived = showArchived(context, params)
        val canManage = context.permissions.has(Manage)
        val selector = UiRow(
            listOfNotNull(
                // The filter form travels with the switch, so both views show the same filters.
                UiButton("Pipeline", UiNavigate("leads", mapOf(ViewKey to ViewPipeline), formId = "filters"), style = if (view == ViewPipeline) "primary" else "secondary"),
                UiButton("Lista", UiNavigate("leads", mapOf(ViewKey to ViewList), formId = "filters"), style = if (view == ViewList) "primary" else "secondary"),
                UiButton("Nuevo lead", UiNavigate("new")).takeIf { canManage },
            ),
        )
        val filters = filterForm(context, "leads", params, withStage = view == ViewList, withSearch = true, archivedShown = showArchived, submitParams = mapOf(ViewKey to view))
        return if (view == ViewPipeline) {
            listOf(summary(context), selector, filters) + pipelineBody(context, params, showArchived)
        } else {
            listOf(selector, filters) + listBody(context, params, showArchived)
        }
    }

    /**
     * The view to show. The user's choice is remembered in crm_user_settings; the server owner has no user
     * row, so it only lasts while the view is sent. An old route that forces a view does not change the choice.
     */
    private fun leadsView(context: Context, forced: String?, params: Map<String, String>): String {
        if (forced != null) return forced
        val userId = context.scope.userId
        val stored = userId?.let { CrmRepository.leadsView(context.tenant, it) } ?: ViewPipeline
        val chosen = resolveLeadsView(params[ViewKey], stored)
        if (userId != null && chosen != stored) CrmRepository.setLeadsView(context.tenant, userId, chosen)
        return chosen
    }

    private fun pipelineBody(context: Context, params: Map<String, String>, showArchived: Boolean): List<UiNode> {
        // The stage filter is the list's: the board always shows every stage.
        // The board also shows the leads the user was mentioned in (read-only: moving them is refused).
        val filter = filter(context, params - "fStage").copy(withMentions = true)
        val (byStage, totals) = CrmRepository.board(context.tenant, context.scope, filter, BoardPerStage)
        val canMove = context.permissions.has(Manage)
        val keep = filterParams(params) - "fStage" + (ShowArchivedKey to showArchived.toString())
        val stageColumns = context.stages.map { stage ->
            val cards = byStage[stage.id].orEmpty()
            val total = totals[stage.id] ?: 0
            UiBoardColumn(
                id = stage.id.toString(),
                title = stage.name,
                tone = stage.tone.id,
                caption = if (total > cards.size) "Mostrando ${cards.size} de $total" else "",
                cards = cards.map { card(context, it, stage) },
                footer = if (total > cards.size) UiButton("Ver todos", UiNavigate("bulk", keep + ("fStage" to stage.name)), style = "secondary") else null,
            )
        }
        // After the last stage: leads archived (they are left out of the stage columns above).
        var archivedShownLeads = emptyList<CrmLead>()
        val archivedColumn = if (showArchived) {
            val archivedFilter = filter.copy(archived = true)
            val archivedTotal = CrmRepository.count(context.tenant, context.scope, archivedFilter)
            val archived = CrmRepository.leads(context.tenant, context.scope, archivedFilter, BoardPerStage, 0)
            archivedShownLeads = archived
            listOf(
                UiBoardColumn(
                    id = ArchivedColumn,
                    title = "Archivados",
                    tone = StageTone.Neutral.id,
                    caption = if (archivedTotal > archived.size) "Mostrando ${archived.size} de $archivedTotal" else "",
                    cards = archived.map { card(context, it, context.stage(it.stageId)) },
                    footer = if (archivedTotal > archived.size) UiButton("Ver todos", UiNavigate("bulk", keep), style = "secondary") else null,
                ),
            )
        } else {
            emptyList()
        }
        return listOfNotNull(
            UiBoard(
                columns = stageColumns + archivedColumn,
                move = if (canMove) UiRequest(UiRoute + "/pipeline", params = keep + ("op" to "move")) else null,
                emptyText = "Crea etapas en Configuracion.",
                actions = CrmBoardActions.declared(context, keep, archivedColumn = showArchived),
            ),
            restoreCard(context, archivedShownLeads.filter { context.readAccess(it).canWrite }, "pipeline", keep),
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
        if (lead.archivedAt != null) add(UiBadge("Archivado", UiTone.Neutral))
        // Seen only because of a mention (not inside the user's scope): read and notes.
        if (context.readAccess(lead) == CrmMentions.LeadAccess.ReadNotes) add(UiBadge(MentionedBadge, UiTone.Info))
    }

    private fun card(context: Context, lead: CrmLead, stage: CrmStage?) = UiBoardCard(
        id = lead.id.toString(),
        title = lead.name,
        subtitle = lead.message.ifBlank { lead.source }.replace('\n', ' ').let { if (it.length > 90) it.take(87) + "..." else it },
        details = listOfNotNull(
            // Initials of everybody assigned; the names are in the lead.
            lead.ownerUserIds.takeIf { it.isNotEmpty() }?.let { "Asignados: ${CrmAssignees.initialsLabels(context.names, it).joinToString(", ")}" },
            // Archived leads sit outside the stages: say where they were.
            stage?.takeIf { lead.archivedAt != null }?.let { "Etapa: ${it.name}" },
            lead.estimatedValue?.let { "Valor: ${it.toPlainString()}" },
        ) + CrmStay.cardLines(lead, CrmStay.effective(context.stay, context.fields), context.fields),
        badges = badges(context, lead, stage),
        action = UiNavigate("lead", mapOf("id" to lead.id.toString())),
    )

    // ---- Lists ----

    private fun listItem(context: Context, lead: CrmLead, suggestion: Boolean = false) = UiListItem(
        title = lead.name,
        subtitle = listOf(
            context.stage(lead.stageId)?.name.orEmpty(), lead.email, lead.phone,
            lead.ownerUserIds.takeIf { it.isNotEmpty() }?.let { CrmAssignees.initialsLabels(context.names, it).joinToString(", ") }.orEmpty(),
            lead.suggestedOwnerUserId.takeIf { suggestion }?.let { "Sugerido: ${context.names.user(it)}" }.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString(" - "),
        trailing = lead.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
        badges = badges(context, lead, context.stage(lead.stageId)),
        action = UiNavigate("lead", mapOf("id" to lead.id.toString())),
    )

    private fun pager(screen: String, params: Map<String, String>, page: Int, total: Int, extra: Map<String, String> = emptyMap()): UiNode? {
        val pages = (total + PageSize - 1) / PageSize
        if (pages <= 1) return null
        val keep = filterParams(params) + extra
        return UiRow(
            listOfNotNull(
                UiButton("Anterior", UiNavigate(screen, keep + ("page" to (page - 1).toString())), style = "secondary").takeIf { page > 1 },
                UiText("Pagina $page de $pages", UiTextStyle.Caption),
                UiButton("Siguiente", UiNavigate(screen, keep + ("page" to (page + 1).toString())), style = "secondary").takeIf { page < pages },
            ),
        )
    }

    /** The page of the list view the filters and page of [params] show. */
    class ListPage(val leads: List<CrmLead>, val total: Int, val page: Int) {
        /** The leads of the page the user may act on (bulk bar, restore): the ones seen through a mention only are read-only. */
        fun writable(context: Context): List<CrmLead> = leads.filter { context.readAccess(it).canWrite }
    }

    /** What the list view shows for [params]; the bulk bar uses it to know which leads the checkboxes were drawn for. */
    fun listPage(context: Context, params: Map<String, String>, showArchived: Boolean = showArchived(context, params)): ListPage {
        // Archived leads come mixed in, marked "Archivado".
        val filter = filter(context, params).copy(includeArchived = showArchived, withMentions = true)
        val total = CrmRepository.count(context.tenant, context.scope, filter)
        val page = (params["page"]?.toIntOrNull() ?: 1).coerceIn(1, maxOf(1, (total + PageSize - 1) / PageSize))
        return ListPage(CrmRepository.leads(context.tenant, context.scope, filter, PageSize, (page - 1) * PageSize), total, page)
    }

    private fun listBody(context: Context, params: Map<String, String>, showArchived: Boolean): List<UiNode> {
        val listed = listPage(context, params, showArchived)
        val (leads, total, page) = Triple(listed.leads, listed.total, listed.page)
        // Checkboxes and restore buttons only for the leads the user may act on (never the ones seen through a mention).
        val writable = listed.writable(context)
        val keep = filterParams(params) + (ShowArchivedKey to showArchived.toString()) + ("page" to page.toString())
        return listOfNotNull(
            UiCard(
                listOfNotNull(
                    UiList(leads.map { listItem(context, it) }, emptyText = "No hay leads con estos filtros."),
                    pager("leads", params, page, total, mapOf(ViewKey to ViewList)),
                ),
                title = "$total lead(s)",
            ),
            // Only with crm.leads.manage: one checkbox per lead of this page and the action.
            CrmImportScreens.bulkCard(context, writable, page, total, keep - "page" + (ViewKey to ViewList)),
            restoreCard(context, writable.filter { it.archivedAt != null }, "list", keep),
        )
    }

    private fun reception(context: Context, params: Map<String, String>): List<UiNode> {
        val filter = CrmLeadFilter(unqualified = true)
        val total = CrmRepository.count(context.tenant, context.scope, filter)
        val page = (params["page"]?.toIntOrNull() ?: 1).coerceIn(1, maxOf(1, (total + PageSize - 1) / PageSize))
        val leads = CrmRepository.leads(context.tenant, context.scope, filter, PageSize, (page - 1) * PageSize)
        // Every pending suggestion in the user's scope, even for leads already qualified or on another page.
        val suggested = if (context.canAssign) CrmRepository.pendingSuggestions(context.tenant, context.scope, 100) else emptyList()
        val suggestions = UiCard(
            suggested.map { lead ->
                val id = mapOf("id" to lead.id.toString(), "from" to "reception")
                UiRow(
                    listOfNotNull(
                        UiText("${lead.name} - Sugerido: ${context.names.user(lead.suggestedOwnerUserId)}"),
                        UiButton("Aprobar", UiRequest(UiRoute + "/lead", params = id + ("op" to "approve"))),
                        UiButton("Descartar", UiRequest(UiRoute + "/lead", params = id + ("op" to "discard")), style = "secondary"),
                    ),
                )
            },
            title = "Sugerencias de asignado (${suggested.size})",
        ).takeIf { suggested.isNotEmpty() }
        return listOfNotNull(
            UiText("Leads sin usuarios asignados y sin interes. Abre cada uno y dale un interes o asignalo a alguien: saldra de esta lista en cuanto tenga al menos uno de los dos. El usuario que sugiere el origen del lead se aprueba (se anade a los asignados) o se descarta aqui."),
            suggestions,
            UiCard(
                listOfNotNull(UiList(leads.map { listItem(context, it, suggestion = context.canAssign) }, emptyText = "No hay leads por calificar."), pager("reception", params, page, total)),
                title = "$total sin calificar",
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

    // Route the new lead form asks while the contact is typed, to reuse an existing customer.
    const val CustomerSuggestRoute = "$UiRoute/suggest/customers"

    // Route the note of a lead asks while "@" is typed (the lead id goes as ?lead=).
    const val MentionSuggestRoute = "$UiRoute/suggest/mentions"

    private fun leadFields(
        context: Context,
        readOnly: Boolean,
        currentInterest: UUID?,
        // Assigned users without access to the CRM: listed as "(sin acceso)" so they can be unchecked, never offered to others.
        legacyOwners: List<CrmUser> = emptyList(),
        suggestCustomers: Boolean = false,
        showCustomerCode: Boolean = false,
    ): List<UiField> {
        val suggest = UiSuggest(CustomerSuggestRoute).takeIf { suggestCustomers }
        val customer = CrmFieldSections.system.first { it.first == CrmFieldSections.Customer }.second
        val followup = CrmFieldSections.system.first { it.first == CrmFieldSections.Followup }.second
        val message = CrmFieldSections.system.first { it.first == CrmFieldSections.Message }.second
        // The built-in fields of each built-in section.
        val builtIn: Map<String, List<UiField>> = mapOf(
            CrmFieldSections.Customer to buildList {
                add(UiField("name", "Nombre", required = true, readOnly = readOnly, section = customer, suggest = suggest))
                add(
                    UiField(
                        "email", "Correo", type = "email", readOnly = readOnly, section = customer, suggest = suggest?.copy(minLength = 3),
                        help = if (suggestCustomers) "Si ya hay un cliente con este correo o telefono, el lead se le asigna." else "",
                    ),
                )
                add(UiField("phone", "Telefono", type = "phone", readOnly = readOnly, section = customer, suggest = suggest?.copy(minLength = 3)))
                if (showCustomerCode) add(UiField("customerCode", "Codigo del cliente", readOnly = true, section = customer))
                add(UiField("language", "Idioma", readOnly = readOnly, section = customer))
            },
            CrmFieldSections.Followup to buildList {
                add(UiField("stage", "Etapa", type = "dropdown", required = true, readOnly = readOnly, options = context.stages.map { it.name }, section = followup))
                if (context.interestsEnabled) {
                    val usable = context.usableInterests.map { it.name }
                    val current = currentInterest?.let { context.names.interest(it) }
                    val options = listOfNotNull(CrmForms.NoInterest.takeIf { context.scope.seesAll || context.scope.reception || currentInterest == null }) +
                        (usable + listOfNotNull(current)).distinct()
                    add(UiField("interest", "Interes", type = "dropdown", readOnly = readOnly, options = options, section = followup))
                }
                add(
                    UiField(
                        "owner", "Asignado", type = "dropdown", multiple = true, readOnly = readOnly || !context.canAssign,
                        options = (context.users + legacyOwners).map { it.name }, section = followup,
                        help = "Pueden ser varias personas; todas ven el lead y reciben sus seguimientos.",
                    ),
                )
                add(UiField("followUpOn", "Proximo seguimiento", type = "date", readOnly = readOnly, section = followup))
                add(UiField("source", "Origen", readOnly = readOnly, placeholder = "Sitio web, WhatsApp, referido...", section = followup))
                add(UiField("estimatedValue", "Valor estimado", type = "number", readOnly = readOnly, section = followup))
            },
            CrmFieldSections.Message to listOf(
                UiField("message", "Mensaje del lead", type = "textarea", readOnly = readOnly, section = message),
                UiField("details", "Detalles", type = "textarea", readOnly = readOnly, help = "Cotizacion, personas y lo propio de esta oportunidad. Gustos y preferencias que valen siempre van en Notas del cliente.", section = message),
            ),
            CrmFieldSections.Documents to listOf(
                UiField(
                    CrmFields.Documents, CrmFields.DocumentsLabel, type = "files", readOnly = readOnly, files = Files, section = CrmFields.DocumentsLabel,
                    help = "Adjunta documentos o carpetas completas. Se guardan al guardar el lead.",
                ),
            ),
        )
        // Walk the sections in order: the built-in fields of a built-in section first, then the custom fields placed in it.
        val sections = CrmFieldSections.sorted(context.sections)
        return buildList {
            sections.forEach { section ->
                section.systemKey?.let { builtIn[it] }?.let { addAll(it) }
                context.fields.filter { it.sectionId == section.id && it.type != CrmFieldType.Button }.sortedWith(compareBy({ it.position }, { it.label.lowercase() }))
                    .forEach { add(customField(it, readOnly, section.name)) }
            }
            // The fields without section (or whose section is gone) go last, in "Campos personalizados".
            val placed = sections.map { it.id }.toSet()
            context.fields.filter { (it.sectionId == null || it.sectionId !in placed) && it.type != CrmFieldType.Button }.sortedWith(compareBy({ it.position }, { it.label.lowercase() }))
                .forEach { add(customField(it, readOnly, CrmFields.Section)) }
        }
    }

    // Sections of the lead form that start open: the ones used every time, and the custom fields when one is required.
    private fun openLeadSections(context: Context): List<String> =
        listOf(CrmFieldSections.system[0].second, CrmFieldSections.system[1].second) + context.fields.filter { it.required }.map { CrmFieldSections.title(it, context.sections) }.distinct()

    // Where the app uploads and downloads the files of the lead form (see CrmServerModule).
    const val FilesRoute = "$UiRoute/files"
    private val Files = UiFiles(upload = FilesRoute, download = FilesRoute, maxFileBytes = CrmAttachments.MaxFileBytes)

    private fun customField(field: CrmField, readOnly: Boolean, section: String) = UiField(
        field.key,
        field.label,
        type = field.type.uiType,
        required = field.required,
        readOnly = readOnly,
        options = field.options,
        section = section,
        multiple = field.multiple,
        files = Files.takeIf { field.type == CrmFieldType.Files },
    )

    /** Suggestions of the new lead form: choosing one fills the contact and links that customer. */
    fun customerSuggestions(customers: List<CrmRepository.CustomerSuggestion>): UiSuggestions {
        val exact = customers.any { it.exact }
        return UiSuggestions(
            heading = if (exact) "Ya existe un cliente con este correo o telefono: el lead se le asignara." else "Clientes existentes",
            items = customers.map { customer ->
                val leads = when (customer.openLeads) {
                    0 -> ""
                    1 -> "1 lead abierto"
                    else -> "${customer.openLeads} leads abiertos"
                }
                UiSuggestion(
                    title = customer.name.ifBlank { customer.email.ifBlank { customer.phone } },
                    subtitle = listOf(customer.email, customer.phone, leads).filter { it.isNotBlank() }.joinToString(" - "),
                    values = mapOf("name" to customer.name, "email" to customer.email, "phone" to customer.phone, "customerId" to customer.id.toString()),
                )
            },
        )
    }

    fun leadValues(context: Context, lead: CrmLead, legacyOwners: List<CrmUser> = emptyList()): Map<String, String> = mapOf(
        "name" to lead.name,
        "email" to lead.email,
        "phone" to lead.phone,
        "customerCode" to lead.customerCode,
        "language" to lead.language,
        "stage" to context.stage(lead.stageId)?.name.orEmpty(),
        "interest" to context.names.interest(lead.interestId),
        "owner" to UiFieldValues.options(lead.ownerUserIds.mapNotNull { id -> (context.users + legacyOwners).firstOrNull { it.id == id }?.name }),
        "followUpOn" to lead.followUpOn?.toString().orEmpty(),
        "source" to lead.source,
        "estimatedValue" to lead.estimatedValue?.toPlainString().orEmpty(),
        "message" to lead.message,
        "details" to lead.details,
    ) + CrmFields.formValues(context.fields, lead.customFields) + fileValues(context, lead)

    // Files of the lead for its files fields; fields without files get "".
    private fun fileValues(context: Context, lead: CrmLead): Map<String, String> {
        val stored = CrmAttachments.forLead(context.tenant, lead.id)
        val keys = listOf(CrmFields.Documents) + context.fields.filter { it.type == CrmFieldType.Files }.map { it.key }
        return keys.associateWith { UiFieldValues.files(stored[it].orEmpty()) }
    }

    private fun newLead(context: Context, submitted: Map<String, String>?): List<UiNode> {
        if (context.stages.isEmpty()) return listOf(UiText("Crea al menos una etapa en Configuracion.", tone = UiTone.Warning))
        val defaults = mapOf(
            "stage" to context.stages.first().name,
            "interest" to if (context.interestsEnabled && !context.scope.seesAll && !context.scope.reception) context.usableInterests.firstOrNull()?.name.orEmpty() else CrmForms.NoInterest,
            "owner" to UiFieldValues.options(listOfNotNull(context.scope.userId?.let { id -> context.users.firstOrNull { it.id == id }?.name })),
            // Every custom field is sent even if left untouched, so required ones are checked.
        ) + context.fields.filter { it.type != CrmFieldType.Button }.associate { it.key to "" } + (CrmFields.Documents to "")
        return listOf(
            UiText("Correo y telefono son opcionales. Mientras escribes se sugieren los clientes existentes; si ya hay uno con ese correo o telefono, el lead se le asigna en lugar de crear otro."),
            UiCard(
                listOf(
                    UiForm(
                        "lead", leadFields(context, readOnly = false, currentInterest = null, suggestCustomers = true), defaults + submitted.orEmpty(),
                        submit = UiButton("Crear lead", UiRequest(UiRoute + "/new", params = mapOf("op" to "create"), formId = "lead")),
                        collapsibleSections = true,
                        openSections = openLeadSections(context),
                    ),
                ),
            ),
        )
    }

    private fun leadDetail(context: Context, lead: CrmLead, submitted: Map<String, String>?): List<UiNode> {
        val stage = context.stage(lead.stageId)
        // Through a mention only (not in the user's scope) the lead is read-only: notes are the one thing they can add.
        val access = context.readAccess(lead)
        val canEdit = context.permissions.has(Manage) && access.canWrite
        val canNote = CrmMentions.canAddNote(access, context.permissions.has(Manage))
        val id = mapOf("id" to lead.id.toString())
        val header = UiRow(
            listOfNotNull(
                UiText(lead.name, UiTextStyle.Title),
                stage?.let { UiStatus(it.name, it.tone.id) },
                lead.archivedAt?.let { UiStatus("Archivado", UiTone.Neutral) },
                UiText("Creado ${lead.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()}", UiTextStyle.Caption),
            ),
        )
        val actions = UiRow(
            listOfNotNull(
                // An archived lead does not move: it comes back first, to the stage it had.
                UiButton("Restaurar", restoreRequest(lead, null)).takeIf { lead.archivedAt != null && canEdit },
                UiButton("Volver a Leads", UiNavigate("leads"), style = "secondary"),
                UiButton(
                    "Eliminar lead", UiRequest(UiRoute + "/lead", params = id + ("op" to "delete"), confirm = "Se elimina el lead y su actividad; el cliente queda en Clientes. Continuar?"),
                    style = "danger",
                ).takeIf { context.permissions.has(Delete) && access.canWrite },
            ),
        )
        val legacyOwners = CrmRepository.legacyUsers(context.tenant, context.users, lead.ownerUserIds)
        val form = UiCard(
            listOf(
                UiForm(
                    "lead",
                    leadFields(context, readOnly = !canEdit, currentInterest = lead.interestId, legacyOwners = legacyOwners, showCustomerCode = context.scope.seesAll),
                    // The customer code (WiFi portal credential) is only for who sees every lead.
                    leadValues(context, lead, legacyOwners).let { if (context.scope.seesAll) it else it - "customerCode" } + submitted.orEmpty() +
                        (if (context.scope.seesAll) mapOf("customerCode" to lead.customerCode) else emptyMap()),
                    // owners0: the assigned users the form was drawn with; saving applies only the difference to them.
                    submit = if (canEdit) UiButton("Guardar cambios", UiRequest(UiRoute + "/lead", params = id + ("op" to "save") + (OwnersBeforeKey to lead.ownerUserIds.joinToString(",")), formId = "lead")) else null,
                    collapsibleSections = true,
                    openSections = openLeadSections(context),
                ),
            ),
            title = "Datos del lead",
        )
        // "Mencionar a" next to the note; the text also recognizes @Nombre and @correo, and the app suggests users while "@" is typed.
        val mentionOptions = context.mentionable.filter { it.id != context.scope.userId }.map { it.name }
        val note = UiCard(
            listOf(
                UiForm(
                    "note",
                    listOfNotNull(
                        UiField(
                            "note", "Nota del lead", type = "textarea", required = true, placeholder = "Llamada, respuesta enviada, acuerdos... Escribe @ para mencionar a alguien.",
                            // The lead goes in the route: the app only adds q and field (and the form values).
                            suggest = UiSuggest(MentionSuggestRoute + "?lead=" + lead.id, minLength = 0, trigger = "@"),
                        ),
                        UiField(
                            "mentions", "Mencionar a", type = "dropdown", multiple = true, options = mentionOptions,
                            help = "Pueden leer este lead y responder con notas, aunque no sea suyo. Reciben un aviso.",
                        ).takeIf { mentionOptions.isNotEmpty() },
                    ),
                    submit = UiButton("Agregar nota", UiRequest(UiRoute + "/lead", params = id + ("op" to "note"), formId = "note")),
                ),
            ),
            title = "Notas del lead",
        )
        val mentions = mentionsCard(context, lead, id, CrmMentions.canRevoke(access, context.permissions.has(Manage)))
        // Tastes and preferences of the customer (Clientes), so whoever attends the lead does not ask them again.
        // Through a mention only the notes and the conversation show: no customer notes, items, reservations or suggestions.
        val customerNotes = CrmCustomerEvents.access?.takeIf { access.canWrite }?.customerNotes(context.tenant, lead.customerId)?.let { notes ->
            UiCard(
                listOf(
                    UiTimeline(
                        notes.map { note ->
                            UiTimelineEntry(
                                note.body,
                                caption = listOf(note.author, note.companyName, note.at.atZone(ZoneId.systemDefault()).toLocalDate().toString())
                                    .filter { it.isNotBlank() }.joinToString(" - "),
                            )
                        },
                        emptyText = "Sin notas del cliente. Anota en su ficha sus gustos y preferencias para no preguntarlos en cada lead.",
                    ),
                ),
                title = "Notas del cliente",
                actions = listOf(UiButton("Abrir ficha", UiOpenModule("clientes", "customer", mapOf("id" to lead.customerId.toString())), style = "secondary")),
            )
        }
        val noteMentions = CrmMentions.byNote(context.tenant, lead.id)
        val timeline = UiCard(
            listOf(
                UiTimeline(
                    CrmRepository.activity(context.tenant, lead.id, 100).map { activity ->
                        val mentioned = activity.id?.let { noteMentions[it] }.orEmpty().map { user -> CrmRepository.displayNames(context.tenant, context.names, listOf(user)) }
                        entry(activity, mentioned)
                    },
                ),
            ),
            title = "Actividad",
        )
        val review = lead.reviewReason.takeIf { it.isNotEmpty() && access.canWrite }?.let { reason ->
            UiCard(
                listOf(UiText(reason, tone = UiTone.Danger)),
                title = "Por revisar",
                actions = listOfNotNull(UiButton("Marcar revisado", UiRequest(UiRoute + "/lead", params = id + ("op" to "reviewed")), style = "secondary").takeIf { canEdit }),
            )
        }
        val suggestion = lead.suggestedOwnerUserId?.takeIf { access.canWrite }?.let { suggested ->
            UiCard(
                listOf(UiText("El origen del lead sugiere asignar a ${context.names.user(suggested)}. Al aprobar se anade a los asignados.")),
                title = "Asignado sugerido",
                actions = if (context.canAssign && access.canWrite) listOf(
                    UiButton("Aprobar", UiRequest(UiRoute + "/lead", params = id + ("op" to "approve"))),
                    UiButton("Descartar", UiRequest(UiRoute + "/lead", params = id + ("op" to "discard")), style = "secondary"),
                ) else emptyList(),
            )
        }
        val conversation = CrmAdminScreens.conversation(context, lead, canEdit, submitted)
        val items = CrmWordPressScreens.items(context, lead, canEdit).takeIf { access.canWrite }
        val reservation = CrmCloudbedsScreens.reservation(context, lead, canEdit).takeIf { access.canWrite }
        val buttons = buttonsCard(context, lead, canEdit)
        return listOfNotNull(header, actions, review, suggestion, customerNotes, form, buttons, items, reservation, conversation, mentions, note.takeIf { canNote }, timeline)
    }

    /**
     * The button fields of the lead, grouped by the section they were placed in (the form has no buttons: they act, they hold no value).
     * Pressing one asks for confirmation and runs its template or manual automations; only who can edit the lead sees them.
     */
    private fun buttonsCard(context: Context, lead: CrmLead, canEdit: Boolean): UiNode? {
        if (!canEdit) return null
        val buttons = context.fields.filter { it.type == CrmFieldType.Button && !CrmButtons.read(it.config).isEmpty }
        if (buttons.isEmpty()) return null
        val ordered = CrmFieldSections.ordered(buttons, context.sections)
        val children = mutableListOf<UiNode>()
        ordered.groupBy { CrmFieldSections.title(it, context.sections) }.forEach { (section, group) ->
            children += UiText(section, UiTextStyle.Caption)
            children += UiRow(
                group.map { field ->
                    UiButton(
                        field.label,
                        UiRequest(UiRoute + "/lead", params = mapOf("id" to lead.id.toString(), "op" to "button", "field" to field.key), confirm = CrmButtons.confirmText(field)),
                        style = "secondary",
                    )
                },
            )
        }
        return UiCard(children, title = "Acciones")
    }

    /**
     * "Mencionados": who can read this lead because a note mentioned them, with "Quitar acceso" for whoever manages
     * the lead ([canRevoke]). Null when nobody is mentioned.
     */
    private fun mentionsCard(context: Context, lead: CrmLead, id: Map<String, String>, canRevoke: Boolean): UiNode? {
        val active = CrmMentions.active(context.tenant, lead.id)
        if (active.isEmpty()) return null
        return UiCard(
            listOf<UiNode>(
                UiText("Pueden leer este lead y responder con notas mientras este abierto. No pueden moverlo, editarlo ni asignarlo.", UiTextStyle.Caption),
            ) + active.map { mention ->
                UiRow(
                    listOfNotNull(
                        UiText(CrmRepository.displayNames(context.tenant, context.names, listOf(mention.userId))),
                        UiText(listOf(mention.byName.takeIf { it.isNotBlank() }?.let { "por $it" }.orEmpty(), time(mention.at)).filter { it.isNotBlank() }.joinToString(" - "), UiTextStyle.Caption),
                        UiButton(
                            "Quitar acceso", UiRequest(UiRoute + "/lead", params = id + ("op" to "unmention") + ("user" to mention.userId.toString()), confirm = "Deja de poder leer este lead, salvo que lo mencionen de nuevo. Continuar?"),
                            style = "secondary",
                        ).takeIf { canRevoke },
                    ),
                )
            },
            title = "Mencionados (${active.size})",
        )
    }

    // [mentioned]: names of the users the note mentions; the entry is highlighted (the app has no inline styles) and says who.
    private fun entry(activity: CrmActivity, mentioned: List<String> = emptyList()): UiTimelineEntry {
        val (title, tone) = when (activity.kind) {
            "created" -> "Lead creado" to UiTone.Info
            "note" -> if (mentioned.isEmpty()) "Nota del lead" to UiTone.Neutral else "Nota del lead - menciona a ${CrmMentions.label(mentioned)}" to UiTone.Info
            "stage" -> "Cambio de etapa" to UiTone.Success
            "owner" -> "Asignados" to UiTone.Warning
            "interest" -> "Interes" to UiTone.Info
            "files" -> "Archivos" to UiTone.Info
            else -> "Actualizado" to UiTone.Neutral
        }
        val body = if (activity.kind == "stage" && activity.fromStage != null && activity.toStage != null) "${activity.fromStage} -> ${activity.toStage}" else activity.body
        return UiTimelineEntry(title, body, "${activity.author} - ${time(activity.createdAt)}", tone)
    }

    private fun time(instant: Instant) = timeFormat.format(instant.atZone(ZoneId.systemDefault()))

    // ---- Settings ----

    private fun customFieldsCard(context: Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val templates = CrmMail.templates(context.tenant)
        val automations = CrmAutomationStore.all(context.tenant)
        val manual = automations.filter { it.trigger.type == CrmTriggerType.Manual }
        val editing = params["field"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id -> context.fields.firstOrNull { it.id == id } }
        val editingSection = params["section"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id -> context.sections.firstOrNull { it.id == id && !it.isSystem } }
        val mapped = CrmStay.effective(context.stay, context.fields)
        val rows = CrmFieldSections.ordered(context.fields, context.sections).map { field ->
            val detail = buildList {
                add(field.type.label)
                add(CrmFieldSections.title(field, context.sections))
                if (field.type == CrmFieldType.Dropdown) add(if (field.multiple) "varias opciones" else "una opcion")
                if (field.type == CrmFieldType.Button) add(CrmButtons.describe(field.config, templates, automations))
                if (field.required) add("obligatorio")
                if (field.key == mapped.startKey) add("ingreso de la estadia")
                if (field.key == mapped.endKey) add("salida de la estadia")
            }.joinToString(", ")
            UiRow(
                listOfNotNull(
                    UiText("${field.position}. ${field.label} ($detail)"),
                    UiButton("Editar", UiNavigate("settings", mapOf("field" to field.id.toString())), style = "secondary"),
                    UiButton(
                        "Eliminar",
                        UiRequest(
                            UiRoute + "/settings", params = mapOf("op" to "fieldDelete", "id" to field.id.toString()),
                            confirm = "Se elimina el campo ${field.label} y lo que tenga en todos los leads." +
                                (if (CrmStay.isMapped(mapped, field.key)) " Es una fecha de estadia: ${CrmStay.ConfirmClear.removeSuffix(" Continuar?")}" else "") + " Continuar?",
                        ),
                        style = "danger",
                    ),
                ),
            )
        }
        val section = if (editing == null) "Nuevo campo" else "Editar ${editing.label}"
        val dropdown = UiFieldCondition("type", listOf(CrmFieldType.Dropdown.label))
        val isButton = UiFieldCondition("type", listOf(CrmFieldType.Button.label))
        // The "Obligatorio" checkbox makes no sense in a button: shown for every other type.
        val notButton = UiFieldCondition("type", CrmFieldType.entries.filter { it != CrmFieldType.Button }.map { it.label })
        val formFields = listOf(
            UiField("label", "Nombre", required = true, section = section),
            UiField(
                "type", "Tipo", type = "dropdown", required = true, readOnly = editing != null, options = CrmFieldType.entries.map { it.label }, section = section,
                help = if (editing != null) "El tipo no cambia despues de crear el campo." else "",
            ),
            UiField(
                "section", "Seccion de la ficha", type = "dropdown", options = listOf(CrmFields.Section) + CrmFieldSections.sorted(context.sections).filter { s -> context.sections.any { it.id == s.id } }.map { it.name }, section = section,
                help = "Los campos sin seccion van en ${CrmFields.Section}.",
            ),
            UiField(
                "options", "Opciones de la lista", type = "textarea", required = true, section = section, visibleWhen = dropdown,
                help = "Una opcion por linea. Quitar una opcion no borra lo que ya tengan los leads, pero ya no se podra elegir.",
            ),
            UiField("multiple", "Permitir elegir varias opciones", type = "boolean", section = section, visibleWhen = dropdown),
            UiField(
                "buttonAction", "Que hace el boton", type = "dropdown", options = listOf(CrmButtons.ActionTemplate, CrmButtons.ActionAutomations), section = section, visibleWhen = isButton,
                help = "El boton aparece en la ficha del lead (no en el formulario), pide confirmacion y muestra el resultado.",
            ),
            UiField("buttonTemplate", "Plantilla", type = "dropdown", options = templates.map { it.name }, section = section, visibleWhen = UiFieldCondition("buttonAction", listOf(CrmButtons.ActionTemplate))),
            UiField(
                "buttonAutomations", "Automatizaciones manuales", type = "dropdown", multiple = true, options = manual.map { it.name }, section = section,
                visibleWhen = UiFieldCondition("buttonAction", listOf(CrmButtons.ActionAutomations)),
                help = "Se ejecutan en el orden elegido y se detienen en la primera que no termine. Se crean en Automatizaciones con el disparador Manual.",
            ),
            UiField("required", "Obligatorio", type = "boolean", section = section, visibleWhen = notButton),
            UiField("position", "Posicion", type = "number", help = "Orden dentro de su seccion en la ficha del lead.", section = section),
        )
        val values = editing?.let {
            mapOf(
                "label" to it.label, "type" to it.type.label, "options" to it.options.joinToString("\n"), "section" to CrmFieldSections.title(it, context.sections),
                "multiple" to it.multiple.toString(), "required" to it.required.toString(), "position" to it.position.toString(),
            ) + CrmButtons.formValues(it.config, templates, manual)
        } ?: mapOf("type" to CrmFieldType.Text.label, "section" to CrmFields.Section, "position" to ((context.fields.maxOfOrNull { it.position } ?: -1) + 1).toString())
        val saveParams = mapOf("op" to "fieldSave") + listOfNotNull(editing?.let { "id" to it.id.toString() })
        val fieldsCard = UiCard(
            listOf(UiText("Campos propios de la empresa que aparecen en la ficha de cada lead, en la seccion que elijas (sin seccion: ${CrmFields.Section}). Las fechas de la estadia se eligen en Integraciones.")) +
                rows.ifEmpty { listOf(UiText("Aun no hay campos personalizados.", tone = UiTone.Neutral)) } +
                listOfNotNull(UiButton("Nuevo campo", UiNavigate("settings"), style = "secondary").takeIf { editing != null }) +
                UiForm(
                    "customField", formFields,
                    values + submitted.orEmpty().filterKeys { it in setOf("label", "type", "section", "options", "multiple", "required", "position", "buttonAction", "buttonTemplate", "buttonAutomations") },
                    submit = UiButton(if (editing == null) "Agregar campo" else "Guardar campo", UiRequest(UiRoute + "/settings", params = saveParams, formId = "customField")),
                    minFieldWidth = 220,
                ),
            title = "Campos personalizados",
        )
        val ordered = CrmFieldSections.sorted(context.sections)
        val sectionRows = ordered.mapIndexed { index, item ->
            val move = { direction: String -> UiRequest(UiRoute + "/settings", params = mapOf("op" to "sectionMove", "id" to item.id.toString(), "dir" to direction)) }
            UiRow(
                listOfNotNull(
                    UiText("${index + 1}. ${item.name}${if (item.isSystem) " (fija)" else ""} (${context.fields.count { it.sectionId == item.id }} campos propios)"),
                    // "Datos del cliente" is always first: it never moves and nothing goes before it.
                    UiButton("Subir", move("up"), style = "secondary").takeIf { index > 1 },
                    UiButton("Bajar", move("down"), style = "secondary").takeIf { index > 0 && index < ordered.lastIndex },
                    UiButton("Renombrar", UiNavigate("settings", mapOf("section" to item.id.toString())), style = "secondary").takeIf { !item.isSystem },
                    UiButton(
                        "Eliminar",
                        UiRequest(
                            UiRoute + "/settings", params = mapOf("op" to "sectionDelete", "id" to item.id.toString()),
                            confirm = "Se elimina la seccion ${item.name}; sus campos quedan en ${CrmFields.Section}, sin borrarlos. Continuar?",
                        ),
                        style = "danger",
                    ).takeIf { !item.isSystem },
                ),
            )
        }
        val sectionTitle = if (editingSection == null) "Nueva seccion" else "Renombrar ${editingSection.name}"
        val sectionValues = editingSection?.let { mapOf("sectionName" to it.name) }.orEmpty()
        val sectionParams = mapOf("op" to "sectionSave") + listOfNotNull(editingSection?.let { "id" to it.id.toString() })
        val sectionsCard = UiCard(
            listOf(UiText("La ficha del lead se arma con estas secciones, en este orden. Las fijas (Datos del cliente, Seguimiento, Mensaje y detalles, Documentos) no se borran ni se renombran; puedes poner campos propios en cualquiera de ellas o en las que crees. Datos del cliente siempre va primero. Los campos sin seccion van al final, en ${CrmFields.Section}.")) +
                sectionRows +
                listOfNotNull(UiButton("Nueva seccion", UiNavigate("settings"), style = "secondary").takeIf { editingSection != null }) +
                UiForm(
                    "fieldSection",
                    listOf(UiField("sectionName", "Nombre", required = true, section = sectionTitle)),
                    sectionValues + submitted.orEmpty().filterKeys { it == "sectionName" },
                    submit = UiButton(if (editingSection == null) "Agregar seccion (al final)" else "Guardar nombre", UiRequest(UiRoute + "/settings", params = sectionParams, formId = "fieldSection")),
                    minFieldWidth = 220,
                ),
            title = "Secciones de la ficha",
        )
        return listOf(sectionsCard, fieldsCard)
    }

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
                    // One form per user: closed so the stages below stay at hand.
                    collapsible = true,
                    expanded = false,
                )
            }
        }

        nodes += customFieldsCard(context, params, submitted)
        nodes += CrmAdminScreens.digestCard(context)

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
