package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.ui.UiAction
import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiBarChart
import com.ideasdeveloper.idc.ui.UiBoard
import com.ideasdeveloper.idc.ui.UiBoardCard
import com.ideasdeveloper.idc.ui.UiBoardColumn
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiChartPoint
import com.ideasdeveloper.idc.ui.UiColumn
import com.ideasdeveloper.idc.ui.UiDivider
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiFiles
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
import com.ideasdeveloper.idc.ui.UiOpenModule
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiSuggest
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTimeline
import com.ideasdeveloper.idc.ui.UiTimelineEntry
import com.ideasdeveloper.idc.ui.UiTone
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** A button that moves a task to [status]; [confirm] is asked first when set. */
data class StatusAction(val label: String, val status: TaskStatus, val style: String = "primary", val confirm: String? = null)

/**
 * Native screens of the module. Every builder is pure: it receives data already loaded (and filtered by the scope) and
 * returns a [UiScreen]; TareasRoutes loads the data and TareasActions parses what the forms send.
 */
object TareasScreens {
    const val Route: String = TareasModule.UiRoute
    const val ListLimit = 200
    const val KanbanLimit = 50
    const val ViewList = "list"
    const val ViewKanban = "kanban"
    const val ViewAgenda = "agenda"
    const val ViewWorkload = "workload"
    const val AgendaWeeks = 8
    const val CustomerSuggestRoute = "$Route/suggest/customers"
    private const val RetiredUser = "Usuario retirado"
    private val Views = listOf(ViewList to "Lista", ViewKanban to "Kanban", ViewAgenda to "Agenda", ViewWorkload to "Carga")
    private val StampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Labels of the assignable users by id. */
    class UserNames(users: List<TareasUser>) {
        private val byId = users.associate { it.id to it.label }
        fun label(id: UUID): String = byId[id] ?: RetiredUser
        fun labels(ids: Collection<UUID>): List<String> = ids.map(::label)
    }

    /** What every screen needs: who looks ([scope]), the assignable [users], today and whether the user may see customers. */
    data class Context(
        val scope: TareasScope,
        val users: List<TareasUser>,
        val today: LocalDate,
        val canSeeCustomers: Boolean = false,
        // The user holds tareas.import and some import source is active: the "Importar" item shows.
        val importEnabled: Boolean = false,
        val zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val names: UserNames = UserNames(users)
    }

    /**
     * Data of a board screen: [tasks] for list and agenda (loaded with one extra row over [ListLimit] to know it was cut),
     * [columns] for the kanban (one extra row over [KanbanLimit] per state) and [workload] for the load view.
     */
    data class BoardData(
        val board: Board,
        val groups: List<BoardGroup>,
        val tasks: List<Task> = emptyList(),
        val columns: Map<TaskStatus, List<Task>> = emptyMap(),
        val workload: List<WorkloadRow> = emptyList(),
    )

    data class TaskDetail(
        val task: Task,
        val groups: List<BoardGroup>,
        val checklist: List<ChecklistItem>,
        val comments: List<TaskComment>,
        val activity: List<TaskActivity>,
        val attachments: List<TaskAttachment>,
        val customerName: String? = null,
    )

    // ---- tones and badges ----

    fun statusTone(status: TaskStatus): String = when (status) {
        TaskStatus.Review -> UiTone.Info
        TaskStatus.Done -> UiTone.Success
        else -> UiTone.Neutral
    }

    fun priorityTone(priority: TaskPriority): String = when (priority) {
        TaskPriority.Urgent -> UiTone.Danger
        TaskPriority.High -> UiTone.Warning
        else -> UiTone.Neutral
    }

    fun taskBadges(task: Task, today: LocalDate, withStatus: Boolean = true): List<UiBadge> = buildList {
        if (task.isOverdue(today)) add(UiBadge("Vencida", UiTone.Danger))
        if (task.priority != TaskPriority.Medium) add(UiBadge(task.priority.label, priorityTone(task.priority)))
        if (withStatus) add(UiBadge(task.status.label, statusTone(task.status)))
    }

    // ---- navigation ----

    fun navigation(scope: TareasScope, selected: String, import: Boolean = false): UiNavigation = UiNavigation(
        title = TareasModule.displayName,
        items = listOfNotNull(
            UiNavItem("mine", "Mis tareas", UiNavigate("mine")),
            UiNavItem("boards", "Tableros", UiNavigate("boards")),
            UiNavItem("admin", "Administrar", UiNavigate("admin")).takeIf { scope.admin },
            UiNavItem("importar", "Importar", UiNavigate("importar")).takeIf { import },
        ),
        selected = selected,
    )

    private fun screen(context: Context, title: String, selected: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(context.scope, selected, context.importEnabled), notice = notice, body = body)

    private fun openTask(task: Task): UiAction = UiNavigate("task", mapOf("id" to task.id.toString()))

    private fun taskItem(context: Context, task: Task, withBoard: Boolean = true): UiListItem = UiListItem(
        title = task.title,
        subtitle = listOfNotNull(
            task.boardName.takeIf { withBoard },
            task.groupName?.takeIf { it.isNotBlank() },
            task.assignees.takeIf { it.isNotEmpty() }?.let { context.names.labels(it).joinToString(", ") },
        ).joinToString(" · "),
        trailing = task.dueDate?.toString().orEmpty(),
        badges = taskBadges(task, context.today),
        action = openTask(task),
    )

    private fun hint(text: String, tone: String? = null) = UiText(text, UiTextStyle.Caption, tone)

    // ---- Mis tareas ----

    /** Open tasks of the user grouped by due date; empty groups are left out. */
    fun mineBuckets(tasks: List<Task>, today: LocalDate): List<Pair<String, List<Task>>> {
        val open = tasks.filter { it.status.isOpen }
        val week = today.plusDays(7)
        return listOf(
            "Vencidas" to open.filter { it.isOverdue(today) },
            "Hoy" to open.filter { it.dueDate == today },
            "Próximos 7 días" to open.filter { val due = it.dueDate; due != null && due.isAfter(today) && !due.isAfter(week) },
            "Más adelante" to open.filter { it.dueDate?.isAfter(week) == true },
            "Sin fecha" to open.filter { it.dueDate == null },
        ).filter { it.second.isNotEmpty() }
    }

    fun mine(context: Context, tasks: List<Task>, notice: UiNotice? = null): UiScreen {
        val ordered = tasks.distinctBy { it.id }
            .sortedWith(compareBy<Task>({ it.dueDate == null }, { it.dueDate }, { it.priority.ordinal }, { it.title.lowercase() }))
        val shown = ordered.take(ListLimit)
        val me = context.scope.userId
        val overdue = shown.count { it.isOverdue(context.today) }
        val toReview = shown.count { it.status == TaskStatus.Review && me != null && it.reviewerId == me }
        val body = buildList<UiNode> {
            add(
                UiGrid(
                    listOf(
                        UiCard(listOf(UiMetric("Abiertas", shown.size.toString()))),
                        UiCard(
                            listOf(
                                UiMetric(
                                    "Vencidas", overdue.toString(),
                                    caption = if (overdue > 0) "Atiéndelas primero" else "Al día",
                                    captionTone = if (overdue > 0) UiTone.Danger else UiTone.Success,
                                ),
                            ),
                        ),
                        UiCard(listOf(UiMetric("Por revisar", toReview.toString(), captionTone = if (toReview > 0) UiTone.Info else UiTone.Neutral))),
                    ),
                    minItemWidth = 180,
                ),
            )
            if (ordered.size > ListLimit) add(hint("Se muestran las primeras $ListLimit tareas, las más próximas a vencer.", UiTone.Warning))
            val buckets = mineBuckets(shown, context.today)
            if (buckets.isEmpty()) {
                add(
                    UiCard(
                        listOf(
                            UiText(if (me == null) "Esta cuenta no recibe tareas: abre un tablero para verlas." else "No tienes tareas abiertas."),
                            UiButton("Ver tableros", UiNavigate("boards"), style = "secondary"),
                        ),
                    ),
                )
            } else {
                buckets.forEach { (title, group) -> add(UiCard(listOf(UiList(group.map { taskItem(context, it) })), title = "$title (${group.size})")) }
            }
        }
        return screen(context, "Mis tareas", "mine", notice, body)
    }

    // ---- Tableros ----

    private fun boardOpen(board: Board): UiAction = UiNavigate("board", mapOf("id" to board.id.toString(), "view" to ViewList))

    fun boards(context: Context, boards: List<Board>, notice: UiNotice? = null): UiScreen {
        val body = buildList<UiNode> {
            if (context.scope.admin) add(UiRow(listOf(UiButton("Nuevo tablero", UiNavigate("board-edit")))))
            if (boards.isEmpty()) {
                add(UiCard(listOf(UiText(if (context.scope.admin) "Aún no hay tableros: crea el primero." else "No hay tableros disponibles para ti."))))
            } else {
                add(
                    UiGrid(
                        boards.map { board ->
                            UiCard(
                                children = listOfNotNull(
                                    board.description.takeIf { it.isNotBlank() }?.let { hint(it) },
                                    UiText("${board.openTasks} abiertas"),
                                    UiText("${board.overdueTasks} vencidas", tone = UiTone.Danger).takeIf { board.overdueTasks > 0 },
                                    hint("Privado: solo sus miembros").takeIf { board.visibility == BoardVisibility.Private },
                                ),
                                title = board.name,
                                actions = listOf(UiButton("Abrir", boardOpen(board), style = "secondary")),
                            )
                        },
                        minItemWidth = 240,
                    ),
                )
            }
        }
        return screen(context, "Tableros", "boards", notice, body)
    }

    // ---- Board ----

    /** The view asked for; anything unknown is the list. */
    fun viewOf(params: Map<String, String>): String = Views.firstOrNull { it.first == params["view"] }?.first ?: ViewList

    private fun filterForm(context: Context, board: Board, view: String, params: Map<String, String>): UiNode {
        val people = listOf(TareasActions.AllOption) + context.users.map { it.label }
        val fields = buildList {
            add(UiField("q", "Buscar", placeholder = "Título o detalle"))
            add(UiField("assignee", "Responsable", type = "dropdown", options = people))
            if (view != ViewKanban) add(UiField("status", "Estado", type = "dropdown", options = listOf(TareasActions.AllOption) + TaskStatus.entries.map { it.label }))
            add(UiField("due", "Vencimiento", type = "dropdown", options = listOf(TareasActions.AllOption) + DueFilter.entries.map { it.label }))
        }
        val values = fields.associate { field ->
            field.key to (params[field.key]?.takeIf { it.isNotBlank() } ?: if (field.type == "dropdown") TareasActions.AllOption else "")
        }
        return UiCard(
            listOf(
                UiForm(
                    "filters", fields, values,
                    submit = UiButton("Filtrar", UiNavigate("board", mapOf("id" to board.id.toString(), "view" to view), formId = "filters"), style = "secondary"),
                    minFieldWidth = 200,
                ),
            ),
        )
    }

    fun board(context: Context, data: BoardData, params: Map<String, String>, notice: UiNotice? = null): UiScreen {
        val board = data.board
        val view = viewOf(params)
        val withFilters = view != ViewWorkload
        val selector = UiRow(
            Views.map { (id, label) ->
                UiButton(
                    label,
                    UiNavigate("board", mapOf("id" to board.id.toString(), "view" to id), formId = if (withFilters) "filters" else null),
                    style = if (id == view) "primary" else "secondary",
                )
            } + listOfNotNull(
                UiButton("Nueva tarea", UiNavigate("new-task", mapOf("board" to board.id.toString()))).takeIf { context.scope.canManage && !board.archived },
                UiButton("Editar tablero", UiNavigate("board-edit", mapOf("id" to board.id.toString())), style = "secondary").takeIf { context.scope.admin },
            ),
        )
        val body = buildList<UiNode> {
            if (board.description.isNotBlank()) add(hint(board.description))
            add(selector)
            if (withFilters) add(filterForm(context, board, view, params))
            addAll(
                when (view) {
                    ViewKanban -> kanbanView(context, data, params)
                    ViewAgenda -> agendaView(context, data)
                    ViewWorkload -> workloadView(context, data)
                    else -> listView(context, data)
                },
            )
        }
        return screen(context, board.name, "boards", notice, body)
    }

    private fun cutNotice(tasks: List<Task>): UiNode? =
        if (tasks.size > ListLimit) hint("Se muestran las primeras $ListLimit tareas: usa los filtros para acotar.", UiTone.Warning) else null

    private fun listView(context: Context, data: BoardData): List<UiNode> {
        val shown = data.tasks.take(ListLimit)
        val groups = data.groups.filter { !it.archived }
        val canAdd = context.scope.canManage && !data.board.archived
        fun add(groupId: UUID?) = UiButton(
            "Nueva tarea",
            UiNavigate("new-task", buildMap { put("board", data.board.id.toString()); groupId?.let { put("group", it.toString()) } }),
            style = "secondary",
        ).takeIf { canAdd }
        val ids = groups.map { it.id }.toSet()
        val loose = shown.filter { it.groupId == null || it.groupId !in ids }
        val cards = groups.map { group ->
            val tasks = shown.filter { it.groupId == group.id }
            UiCard(
                listOf(UiList(tasks.map { taskItem(context, it, withBoard = false) }, emptyText = "Sin tareas en este grupo.")),
                title = "${group.name} (${tasks.size})",
                actions = listOfNotNull(add(group.id)),
            )
        } + listOfNotNull(
            UiCard(
                listOf(UiList(loose.map { taskItem(context, it, withBoard = false) }, emptyText = "No hay tareas con estos filtros.")),
                title = "${TareasActions.NoGroup} (${loose.size})",
                actions = listOfNotNull(add(null)),
            ).takeIf { loose.isNotEmpty() || groups.isEmpty() },
        )
        return listOfNotNull(cutNotice(data.tasks)) + cards
    }

    private fun kanbanView(context: Context, data: BoardData, params: Map<String, String>): List<UiNode> {
        val keep = TareasActions.filterParams(params) - "status"
        val move = UiRequest(
            "$Route/board",
            params = keep + mapOf("id" to data.board.id.toString(), "view" to ViewKanban, "op" to "move"),
        )
        val columns = TaskStatus.entries.map { status ->
            val tasks = data.columns[status].orEmpty()
            UiBoardColumn(
                id = status.code,
                title = status.label,
                tone = statusTone(status),
                caption = if (tasks.size > KanbanLimit) "Mostrando $KanbanLimit: usa los filtros para ver el resto" else "",
                cards = tasks.take(KanbanLimit).map { task ->
                    UiBoardCard(
                        id = task.id.toString(),
                        title = task.title,
                        subtitle = task.groupName.orEmpty(),
                        details = listOfNotNull(
                            task.assignees.takeIf { it.isNotEmpty() }?.let { "Responsables: ${context.names.labels(it).joinToString(", ")}" },
                            task.dueDate?.let { "Vence: $it" },
                            task.checklistTotal.takeIf { it > 0 }?.let { "Lista: ${task.checklistDone}/$it" },
                        ),
                        badges = taskBadges(task, context.today, withStatus = false),
                        action = openTask(task),
                    )
                },
            )
        }
        return listOf(UiBoard(columns, move = move, emptyText = "Sin tareas."))
    }

    /** Start (Monday) of the ISO week of [date]. */
    private fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Agenda groups in order: overdue, earlier closed ones, one per ISO week from this week to [AgendaWeeks] weeks ahead,
     * later, and without date. Empty groups are left out.
     */
    fun agendaBuckets(tasks: List<Task>, today: LocalDate): List<Pair<String, List<Task>>> {
        val thisWeek = weekStart(today)
        fun key(task: Task): Pair<Int, String> {
            val due = task.dueDate ?: return 200 to "Sin fecha"
            if (task.isOverdue(today)) return 0 to "Vencidas"
            if (due.isBefore(thisWeek)) return 1 to "Anteriores"
            val weeks = ChronoUnit.WEEKS.between(thisWeek, weekStart(due)).toInt()
            if (weeks > AgendaWeeks) return 100 to "Más adelante"
            val start = thisWeek.plusWeeks(weeks.toLong())
            return 10 + weeks to "Semana ${start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}: $start a ${start.plusDays(6)}"
        }
        return tasks.groupBy { key(it) }.entries.sortedBy { it.key.first }.map { it.key.second to it.value }
    }

    private fun agendaView(context: Context, data: BoardData): List<UiNode> {
        val shown = data.tasks.take(ListLimit)
        val buckets = agendaBuckets(shown, context.today)
        val cards = if (buckets.isEmpty()) {
            listOf(UiCard(listOf(UiText("No hay tareas con estos filtros."))))
        } else {
            buckets.map { (title, tasks) -> UiCard(listOf(UiList(tasks.map { taskItem(context, it, withBoard = false) })), title = "$title (${tasks.size})") }
        }
        return listOfNotNull(cutNotice(data.tasks)) + cards
    }

    private fun workloadView(context: Context, data: BoardData): List<UiNode> {
        if (data.workload.isEmpty()) return listOf(UiCard(listOf(UiText("Nadie tiene tareas abiertas en este tablero."))))
        val rows = data.workload.sortedByDescending { it.open }
        return listOf(
            UiCard(
                listOf(UiBarChart(rows.map { UiChartPoint(context.names.label(it.userId), it.open.toDouble()) })),
                title = "Tareas abiertas por responsable",
            ),
            UiCard(
                listOf(
                    UiTable(
                        columns = listOf(UiTableColumn("person", "Persona"), UiTableColumn("open", "Abiertas"), UiTableColumn("overdue", "Vencidas"), UiTableColumn("review", "En revisión")),
                        rows = rows.map {
                            mapOf("person" to context.names.label(it.userId), "open" to it.open.toString(), "overdue" to it.overdue.toString(), "review" to it.review.toString())
                        },
                        emptyText = "Sin datos.",
                    ),
                ),
                title = "Carga por persona",
            ),
        )
    }

    // ---- Task form (shared by "task" and "new-task") ----

    private fun groupOptions(groups: List<BoardGroup>): List<String> = listOf(TareasActions.NoGroup) + groups.filter { !it.archived }.map { it.name }

    private fun fileRefs(attachments: List<TaskAttachment>): List<UiFileRef> = attachments.map { UiFileRef(it.id.toString(), it.name, it.path, it.size) }

    private fun filesField(readOnly: Boolean) = UiField(
        "files", "Adjuntos", type = "files", readOnly = readOnly,
        files = UiFiles(upload = "$Route/files", download = "$Route/files"),
    )

    /** Fields of the task form; the customer needs [withCustomer] (clientes.view) and the files [withFiles]. */
    fun taskFields(context: Context, groups: List<BoardGroup>, readOnly: Boolean, withFiles: Boolean = true): List<UiField> = buildList {
        val people = context.users.map { it.label }
        add(UiField("title", "Título", required = true, readOnly = readOnly))
        add(UiField("detail", "Detalle", type = "textarea", readOnly = readOnly))
        add(UiField("priority", "Prioridad", type = "dropdown", options = TaskPriority.entries.map { it.label }, readOnly = readOnly))
        add(UiField("due", "Fecha límite", type = "date", readOnly = readOnly))
        add(UiField("group", "Grupo", type = "dropdown", options = groupOptions(groups), readOnly = readOnly))
        add(UiField("assignees", "Responsables", type = "dropdown", multiple = true, options = people, readOnly = readOnly))
        add(UiField("reviewer", "Revisor", type = "dropdown", options = listOf(TareasActions.NoReviewer) + people, readOnly = readOnly))
        if (context.canSeeCustomers) {
            add(
                UiField(
                    "customer", "Cliente", readOnly = readOnly, placeholder = "Escribe para buscar",
                    help = "Elige un cliente de la lista de sugerencias.",
                    suggest = UiSuggest(CustomerSuggestRoute),
                ),
            )
        }
        if (withFiles) add(filesField(readOnly))
    }

    private fun taskValues(context: Context, groups: List<BoardGroup>, task: Task?, detail: TaskDetail?): Map<String, String> = buildMap {
        put("title", task?.title.orEmpty())
        put("detail", task?.detail.orEmpty())
        put("priority", (task?.priority ?: TaskPriority.Medium).label)
        put("due", task?.dueDate?.toString().orEmpty())
        put("group", task?.groupId?.let { id -> groups.firstOrNull { it.id == id }?.name }.orEmpty().ifBlank { TareasActions.NoGroup })
        put("assignees", UiFieldValues.options(task?.assignees.orEmpty().filter { id -> context.users.any { it.id == id } }.map { context.names.label(it) }))
        put("reviewer", task?.reviewerId?.takeIf { id -> context.users.any { it.id == id } }?.let { context.names.label(it) } ?: TareasActions.NoReviewer)
        if (context.canSeeCustomers && task?.customerId != null) {
            put("customer", detail?.customerName ?: "Cliente no disponible")
            put("customer_id", task.customerId.toString())
        }
        if (detail != null) put("files", UiFieldValues.files(fileRefs(detail.attachments)))
    }

    private fun sectionTitle(text: String) = UiText(text, UiTextStyle.Subtitle)

    // ---- Task ----

    /** Buttons to change the status that [scope] may use on [task]. The service still applies the rules. */
    fun statusActions(scope: TareasScope, task: Task): List<StatusAction> {
        if (task.archivedAt != null || !scope.canWorkOnTask(task)) return emptyList()
        val finish = if (task.reviewerId != null && !scope.canReview(task)) {
            StatusAction("Enviar a revisión", TaskStatus.Review)
        } else {
            StatusAction("Marcar hecha", TaskStatus.Done)
        }
        val main = when (task.status) {
            TaskStatus.Pending -> listOf(StatusAction("Empezar", TaskStatus.InProgress, style = "secondary"), finish)
            TaskStatus.InProgress -> listOf(finish)
            TaskStatus.Review -> if (scope.canReview(task)) {
                listOf(StatusAction("Aprobar", TaskStatus.Done), StatusAction("Devolver", TaskStatus.InProgress, style = "secondary"))
            } else {
                emptyList()
            }
            TaskStatus.Done, TaskStatus.Cancelled -> listOf(StatusAction("Reabrir", TaskStatus.InProgress, style = "secondary"))
        }
        val cancel = StatusAction("Cancelar tarea", TaskStatus.Cancelled, style = "danger", confirm = "¿Cancelar esta tarea?")
        return main + listOfNotNull(cancel.takeIf { task.status.isOpen && scope.canEditTask(task) })
    }

    private fun taskRequest(op: String, task: Task, extra: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest("$Route/task", params = mapOf("op" to op, "id" to task.id.toString()) + extra, confirm = confirm, formId = formId)

    fun stamp(instant: Instant, zone: ZoneId): String = StampFormat.withZone(zone).format(instant)

    fun task(context: Context, detail: TaskDetail, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val task = detail.task
        val scope = context.scope
        // An archived task (only administrators open it) is read-only: no state, form or checklist changes.
        val archived = task.archivedAt != null
        val canEdit = scope.canEditTask(task) && !archived
        val canWork = scope.canWorkOnTask(task) && !archived
        val boardLink = UiButton("Volver al tablero", UiNavigate("board", mapOf("id" to task.boardId.toString(), "view" to ViewList)), style = "secondary")
        val statusButtons = statusActions(scope, task).map { action ->
            UiButton(action.label, taskRequest("status", task, mapOf("status" to action.status.code), confirm = action.confirm), style = action.style)
        }
        val actions = UiRow(
            statusButtons + listOfNotNull(
                UiButton("Archivar", taskRequest("archive", task, confirm = "¿Archivar esta tarea?"), style = "danger").takeIf { canEdit },
                UiButton("Abrir cliente", UiOpenModule("clientes", "customer", mapOf("id" to task.customerId.toString())), style = "secondary")
                    .takeIf { context.canSeeCustomers && task.customerId != null },
                boardLink,
            ),
        )
        val header = UiRow(
            listOfNotNull(
                UiStatus(task.status.label, statusTone(task.status)),
                UiStatus(task.priority.label, priorityTone(task.priority)),
                UiStatus("Vencida", UiTone.Danger).takeIf { task.isOverdue(context.today) },
                hint(listOfNotNull(task.boardName, task.groupName?.takeIf { it.isNotBlank() }, task.dueDate?.let { "Vence $it" }).joinToString(" · ")),
            ),
            spacing = 8,
        )
        // A person who only works the task (not edit) keeps the form read-only and attaches files apart.
        val workerOnly = !canEdit && canWork
        val values = taskValues(context, detail.groups, task, detail) + (submitted.orEmpty())
        val form = UiForm(
            id = "task",
            fields = taskFields(context, detail.groups, readOnly = !canEdit, withFiles = !workerOnly),
            values = values,
            submit = UiButton("Guardar", taskRequest("save", task, formId = "task")).takeIf { canEdit },
        )
        val body = buildList<UiNode> {
            if (archived) add(hint("Tarea archivada: solo lectura.", UiTone.Warning))
            add(header)
            add(actions)
            add(UiCard(listOf(form), title = "Detalles"))
            if (workerOnly) {
                add(
                    UiCard(
                        listOf(
                            UiForm(
                                id = "attachments",
                                fields = listOf(filesField(readOnly = false)),
                                values = mapOf("files" to UiFieldValues.files(fileRefs(detail.attachments))),
                                submit = UiButton("Guardar adjuntos", taskRequest("files", task, formId = "attachments"), style = "secondary"),
                            ),
                        ),
                        title = "Adjuntos",
                    ),
                )
            }
            add(checklistCard(task, detail.checklist, canWork, canEdit))
            add(commentsCard(context, task, detail.comments, canWork))
            add(
                UiCard(
                    listOf(
                        UiTimeline(
                            detail.activity.map { UiTimelineEntry(it.detail.ifBlank { it.kind }, caption = listOf(it.authorName, stamp(it.createdAt, context.zone)).filter { part -> part.isNotBlank() }.joinToString(" - ")) },
                            emptyText = "Sin actividad.",
                        ),
                    ),
                    title = "Actividad",
                    collapsible = true,
                    expanded = false,
                ),
            )
        }
        return screen(context, task.title, "boards", notice, body)
    }

    private fun checklistCard(task: Task, items: List<ChecklistItem>, canWork: Boolean, canEdit: Boolean): UiNode {
        val rows = items.map { item ->
            UiRow(
                listOfNotNull(
                    UiButton(
                        (if (item.done) "[x] " else "[ ] ") + item.text,
                        taskRequest("check-toggle", task, mapOf("item" to item.id.toString(), "done" to (!item.done).toString())),
                        style = "secondary",
                        enabled = canWork,
                    ),
                    UiButton("Quitar", taskRequest("check-remove", task, mapOf("item" to item.id.toString())), style = "danger").takeIf { canEdit },
                ),
                spacing = 8,
            )
        }
        val children = buildList<UiNode> {
            if (rows.isEmpty()) add(hint("Sin elementos."))
            addAll(rows)
            if (canWork) {
                add(
                    UiForm(
                        id = "check",
                        fields = listOf(UiField("text", "Nuevo elemento", required = true)),
                        submit = UiButton("Añadir", taskRequest("check-add", task, formId = "check"), style = "secondary"),
                    ),
                )
            }
        }
        val title = if (task.checklistTotal > 0) "Lista de comprobación (${task.checklistDone}/${task.checklistTotal})" else "Lista de comprobación"
        return UiCard(children, title = title)
    }

    private fun commentsCard(context: Context, task: Task, comments: List<TaskComment>, canWork: Boolean): UiNode {
        val children = buildList<UiNode> {
            add(
                UiTimeline(
                    comments.map { UiTimelineEntry(it.authorName, it.body, stamp(it.createdAt, context.zone)) },
                    emptyText = "Aún no hay comentarios.",
                ),
            )
            if (canWork) {
                add(
                    UiForm(
                        id = "comment",
                        fields = listOf(UiField("body", "Comentario", type = "textarea", required = true)),
                        submit = UiButton("Comentar", taskRequest("comment", task, formId = "comment"), style = "secondary"),
                    ),
                )
            }
        }
        return UiCard(children, title = "Comentarios (${comments.size})")
    }

    // ---- New task ----

    /** [groupId] preselects a group (the "Nueva tarea" button of a group card). */
    fun newTask(context: Context, board: Board, groups: List<BoardGroup>, groupId: UUID?, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val base = taskValues(context, groups, null, null) +
            (groupId?.let { id -> groups.firstOrNull { it.id == id }?.name }?.let { mapOf("group" to it) } ?: emptyMap())
        val form = UiForm(
            id = "task",
            fields = taskFields(context, groups, readOnly = false),
            values = base + submitted.orEmpty(),
            submit = UiButton("Crear tarea", UiRequest("$Route/new-task", params = mapOf("op" to "create", "board" to board.id.toString()), formId = "task")),
        )
        val body = listOf(
            hint("Tablero: ${board.name}"),
            UiCard(listOf(form), title = "Nueva tarea"),
            UiRow(listOf(UiButton("Cancelar", UiNavigate("board", mapOf("id" to board.id.toString(), "view" to ViewList)), style = "secondary"))),
        )
        return screen(context, "Nueva tarea", "boards", notice, body)
    }

    // ---- Administrar ----

    fun admin(context: Context, boards: List<Board>, notice: UiNotice? = null): UiScreen {
        val items = boards.map { board ->
            UiListItem(
                title = board.name,
                subtitle = "${board.openTasks} abiertas · ${board.overdueTasks} vencidas" +
                    if (board.visibility == BoardVisibility.Private) " · ${board.members.size} miembros" else "",
                badges = listOfNotNull(
                    UiBadge(board.visibility.label, if (board.visibility == BoardVisibility.Private) UiTone.Warning else UiTone.Neutral),
                    UiBadge("Archivado", UiTone.Neutral).takeIf { board.archived },
                ),
                action = UiNavigate("board-edit", mapOf("id" to board.id.toString())),
            )
        }
        val body = listOf(
            UiRow(listOf(UiButton("Nuevo tablero", UiNavigate("board-edit")))),
            UiCard(listOf(UiList(items, emptyText = "Aún no hay tableros.")), title = "Tableros"),
        )
        return screen(context, "Administrar", "admin", notice, body)
    }

    // ---- Board edit ----

    /** [board] is null for a new board. */
    fun boardEdit(context: Context, board: Board?, groups: List<BoardGroup>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val idParams = board?.let { mapOf("id" to it.id.toString()) }.orEmpty()
        fun request(op: String, extra: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
            UiRequest("$Route/board-edit", params = mapOf("op" to op) + idParams + extra, confirm = confirm, formId = formId)

        val values = mapOf(
            "name" to board?.name.orEmpty(),
            "description" to board?.description.orEmpty(),
            "visibility" to (board?.visibility ?: BoardVisibility.Company).label,
            "members" to UiFieldValues.options(board?.members.orEmpty().filter { id -> context.users.any { it.id == id } }.map { context.names.label(it) }),
        ) + submitted.orEmpty()
        val form = UiForm(
            id = "board",
            fields = listOf(
                UiField("name", "Nombre", required = true),
                UiField("description", "Descripción", type = "textarea"),
                UiField("visibility", "Visibilidad", type = "dropdown", options = BoardVisibility.entries.map { it.label }, help = "Un tablero privado solo lo ven sus miembros y quien administra tableros."),
                UiField("members", "Miembros", type = "dropdown", multiple = true, options = context.users.map { it.label }, help = "Solo cuenta en tableros privados."),
            ),
            values = values,
            submit = UiButton(if (board == null) "Crear tablero" else "Guardar", request("board-save", formId = "board")),
        )
        val body = buildList<UiNode> {
            add(UiCard(listOf(form), title = "Tablero"))
            if (board != null) {
                add(groupsCard(groups, board, ::request))
                add(
                    UiRow(
                        listOfNotNull(
                            UiButton("Abrir tablero", UiNavigate("board", mapOf("id" to board.id.toString(), "view" to ViewList)), style = "secondary"),
                            UiButton("Archivar tablero", request("board-archive", confirm = "¿Archivar este tablero? Sus tareas dejan de verse."), style = "danger").takeIf { !board.archived },
                        ),
                    ),
                )
            }
        }
        return screen(context, board?.name ?: "Nuevo tablero", "admin", notice, body)
    }

    private fun groupsCard(
        groups: List<BoardGroup>,
        board: Board,
        request: (String, Map<String, String>, String?, String?) -> UiRequest,
    ): UiNode {
        val active = groups.filter { !it.archived }
        val rows = active.map { group ->
            val params = mapOf("group" to group.id.toString())
            UiColumn(
                listOf(
                    UiForm(
                        id = "group-${group.id}",
                        fields = listOf(UiField("name", "Nombre del grupo", required = true)),
                        values = mapOf("name" to group.name),
                        submit = UiButton("Renombrar", request("group-rename", params, null, "group-${group.id}"), style = "secondary"),
                    ),
                    UiRow(
                        listOf(
                            UiButton("Subir", request("group-up", params, null, null), style = "secondary"),
                            UiButton("Bajar", request("group-down", params, null, null), style = "secondary"),
                            UiButton("Archivar", request("group-archive", params, "¿Archivar el grupo ${group.name}? Sus tareas quedan sin grupo.", null), style = "danger"),
                        ),
                        spacing = 8,
                    ),
                ),
                spacing = 8,
            )
        }
        val children = buildList<UiNode> {
            if (rows.isEmpty()) add(hint("Este tablero aún no tiene grupos (por ejemplo \"Esta semana\" o \"Próxima semana\")."))
            rows.forEachIndexed { index, row ->
                if (index > 0) add(UiDivider())
                add(row)
            }
            add(UiDivider())
            add(sectionTitle("Nuevo grupo"))
            add(
                UiForm(
                    id = "group-new",
                    fields = listOf(UiField("name", "Nombre", required = true)),
                    submit = UiButton("Añadir grupo", request("group-add", emptyMap(), null, "group-new"), style = "secondary"),
                ),
            )
        }
        return UiCard(children, title = "Grupos de ${board.name}")
    }
}
