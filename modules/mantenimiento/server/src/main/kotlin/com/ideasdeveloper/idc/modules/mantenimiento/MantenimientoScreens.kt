package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.ui.UiAction
import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiBoard
import com.ideasdeveloper.idc.ui.UiBoardCard
import com.ideasdeveloper.idc.ui.UiBoardColumn
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiDivider
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldCondition
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
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTimeline
import com.ideasdeveloper.idc.ui.UiTimelineEntry
import com.ideasdeveloper.idc.ui.UiTone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Un botón que pasa la orden a [status]; [confirm] se pregunta antes cuando existe. */
data class StatusAction(val label: String, val status: OrderStatus, val style: String = "primary", val confirm: String? = null)

/**
 * Pantallas nativas del módulo. Cada constructor es puro: recibe datos ya cargados (y filtrados por el scope) y devuelve un
 * [UiScreen]; MantenimientoRoutes carga los datos y MantenimientoActions lee lo que envían los formularios.
 */
object MantenimientoScreens {
    const val Route: String = MantenimientoModule.UiRoute
    const val ListLimit = 200
    const val KanbanLimit = 50
    const val HistoryLimit = 50
    const val ViewList = "list"
    const val ViewBoard = "board"
    private val Views = listOf(ViewList to "Lista", ViewBoard to "Tablero")
    private val StampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Etiquetas de los usuarios asignables por id. */
    class UserNames(users: List<MantenimientoUser>) {
        private val byId = users.associate { it.id to it.label }
        fun label(id: UUID): String = byId[id] ?: MantenimientoActions.RetiredUser
    }

    /** Lo que toda pantalla necesita: quién mira ([scope]), los usuarios asignables, las propiedades y hoy. */
    data class Context(
        val scope: MantenimientoScope,
        val users: List<MantenimientoUser>,
        val properties: List<Property>,
        val today: LocalDate,
        // El usuario tiene mantenimiento.import y alguna fuente está activa: aparece "Importar".
        val importEnabled: Boolean = false,
        val zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val names: UserNames = UserNames(users)
    }

    /** Datos de la pantalla Órdenes: [orders] para la lista (con una fila de más sobre [ListLimit]) o [columns] para el tablero. */
    data class OrdersData(val orders: List<Order> = emptyList(), val columns: Map<OrderStatus, List<Order>> = emptyMap())

    data class OrderDetail(
        val order: Order,
        val checklist: List<OrderCheck>,
        val notes: List<OrderNote>,
        val attachments: List<Attachment>,
        val assets: AssetOptions,
        val plan: Plan? = null,
        // Compras está activo y el usuario puede usar su herramienta ahora mismo.
        val purchaseAvailable: Boolean = false,
    )

    // ---- tonos e insignias ----

    fun statusTone(status: OrderStatus): String = when (status) {
        OrderStatus.Done -> UiTone.Success
        OrderStatus.Rejected -> UiTone.Danger
        OrderStatus.Requested, OrderStatus.Quoting, OrderStatus.InProgress -> UiTone.Info
        OrderStatus.OnHold -> UiTone.Warning
        OrderStatus.Open, OrderStatus.Cancelled -> UiTone.Neutral
    }

    fun priorityTone(priority: OrderPriority): String = when (priority) {
        OrderPriority.Urgent -> UiTone.Danger
        OrderPriority.High -> UiTone.Warning
        else -> UiTone.Neutral
    }

    fun orderBadges(order: Order, today: LocalDate, withStatus: Boolean = true): List<UiBadge> = buildList {
        if (order.isOverdue(today)) add(UiBadge("Vencida", UiTone.Danger))
        if (order.priority != OrderPriority.Medium) add(UiBadge(order.priority.label, priorityTone(order.priority)))
        if (order.kind == OrderKind.Project) add(UiBadge(OrderKind.Project.label, UiTone.Neutral))
        if (withStatus) add(UiBadge(order.status.label, statusTone(order.status)))
    }

    // ---- navegación ----

    fun navigation(scope: MantenimientoScope, selected: String, import: Boolean = false): UiNavigation = UiNavigation(
        title = MantenimientoModule.displayName,
        items = listOfNotNull(
            UiNavItem("upcoming", "Próximos", UiNavigate("upcoming")),
            UiNavItem("mine", "Mis órdenes", UiNavigate("mine")),
            UiNavItem("orders", "Órdenes", UiNavigate("orders")).takeIf { scope.seesAll },
            UiNavItem("assets", "Activos", UiNavigate("assets")),
            UiNavItem("plans", "Planes", UiNavigate("plans")),
            UiNavItem("admin", "Ajustes", UiNavigate("admin")).takeIf { scope.canManage },
            UiNavItem("importar", "Importar", UiNavigate("importar")).takeIf { import },
        ),
        selected = selected,
    )

    private fun screen(context: Context, title: String, selected: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(context.scope, selected, context.importEnabled), notice = notice, body = body)

    private fun openOrder(order: Order): UiAction = UiNavigate("order", mapOf("id" to order.id.toString()))

    private fun hint(text: String, tone: String? = null) = UiText(text, UiTextStyle.Caption, tone)

    private fun sectionTitle(text: String) = UiText(text, UiTextStyle.Subtitle)

    fun stamp(instant: Instant, zone: ZoneId): String = StampFormat.withZone(zone).format(instant)

    /** Los botones para reportar una avería o solicitar un proyecto, a mano de todo el personal. */
    fun quickActions(): UiNode = UiRow(
        listOf(
            UiButton("Reportar avería", UiNavigate("new-order", mapOf("kind" to OrderKind.Corrective.id))),
            UiButton("Solicitar proyecto", UiNavigate("new-order", mapOf("kind" to OrderKind.Project.id)), style = "secondary"),
        ),
    )

    private fun orderItem(context: Context, order: Order): UiListItem = UiListItem(
        title = "${order.displayNumber} · ${order.title}",
        subtitle = listOfNotNull(
            order.propertyName?.takeIf { it.isNotBlank() },
            order.assetName?.takeIf { it.isNotBlank() },
            order.assigneeId?.let { context.names.label(it) },
        ).joinToString(" · "),
        trailing = order.dueDate?.toString().orEmpty(),
        badges = orderBadges(order, context.today),
        action = openOrder(order),
    )

    private fun cutNotice(count: Int): UiNode? =
        if (count > ListLimit) hint("Se muestran las primeras $ListLimit órdenes: usa los filtros para acotar.", UiTone.Warning) else null

    // ---- Próximos ----

    /** Órdenes abiertas agrupadas por fecha límite; los grupos vacíos no aparecen. */
    fun upcomingBuckets(orders: List<Order>, today: LocalDate): List<Pair<String, List<Order>>> {
        val open = orders.filter { it.status.isActive }
        val week = today.plusDays(7)
        val month = today.plusDays(30)
        fun dueBetween(order: Order, from: LocalDate, to: LocalDate): Boolean {
            val due = order.dueDate ?: return false
            return !due.isBefore(from) && !due.isAfter(to)
        }
        return listOf(
            "Vencidas" to open.filter { it.isOverdue(today) },
            "Próximos 7 días" to open.filter { dueBetween(it, today, week) },
            "Próximos 30 días" to open.filter { dueBetween(it, week.plusDays(1), month) },
            "Más adelante" to open.filter { it.dueDate?.isAfter(month) == true },
            "Sin fecha" to open.filter { it.dueDate == null },
        ).filter { it.second.isNotEmpty() }
    }

    private fun planItem(plan: Plan): UiListItem = UiListItem(
        title = plan.title,
        subtitle = listOfNotNull(plan.propertyName, plan.assetName, plan.frequency.label.takeIf { plan.frequency != PlanFrequency.EveryNDays } ?: "Cada ${plan.intervalDays} días").joinToString(" · "),
        trailing = plan.nextDue?.toString().orEmpty(),
        badges = listOfNotNull(UiBadge("Inactivo", UiTone.Neutral).takeIf { !plan.active }),
        action = UiNavigate("plan", mapOf("id" to plan.id.toString())),
    )

    fun upcoming(
        context: Context,
        metrics: OrderMetrics,
        orders: List<Order>,
        plansSoon: List<Plan>,
        property: Property?,
        notice: UiNotice? = null,
    ): UiScreen {
        val shown = orders.take(ListLimit)
        val filter = UiCard(
            listOf(
                UiForm(
                    "filters",
                    listOf(UiField("property", "Propiedad", type = "dropdown", options = listOf(MantenimientoActions.AllOption) + context.properties.map { it.name })),
                    mapOf("property" to (property?.name ?: MantenimientoActions.AllOption)),
                    submit = UiButton("Filtrar", UiNavigate("upcoming", formId = "filters"), style = "secondary"),
                    minFieldWidth = 220,
                ),
            ),
        )
        val body = buildList<UiNode> {
            add(quickActions())
            if (context.properties.isNotEmpty()) add(filter)
            add(
                UiGrid(
                    listOf(
                        UiCard(listOf(UiMetric("Abiertas", metrics.open.toString()))),
                        UiCard(
                            listOf(
                                UiMetric(
                                    "Vencidas", metrics.overdue.toString(),
                                    caption = if (metrics.overdue > 0) "Atiéndelas primero" else "Al día",
                                    captionTone = if (metrics.overdue > 0) UiTone.Danger else UiTone.Success,
                                ),
                            ),
                        ),
                        UiCard(listOf(UiMetric("Por aprobar", metrics.pendingApproval.toString(), captionTone = if (metrics.pendingApproval > 0) UiTone.Info else UiTone.Neutral))),
                        UiCard(listOf(UiMetric("Completadas este mes", metrics.completedThisMonth.toString()))),
                    ),
                    minItemWidth = 180,
                ),
            )
            cutNotice(orders.size)?.let { add(it) }
            val buckets = upcomingBuckets(shown, context.today)
            if (buckets.isEmpty()) {
                add(UiCard(listOf(UiText("No hay órdenes abiertas."))))
            } else {
                buckets.forEach { (title, group) -> add(UiCard(listOf(UiList(group.map { orderItem(context, it) })), title = "$title (${group.size})")) }
            }
            if (plansSoon.isNotEmpty()) {
                add(UiCard(listOf(UiList(plansSoon.map { planItem(it) })), title = "Planes que generan pronto (${plansSoon.size})"))
            }
        }
        return screen(context, "Próximos", "upcoming", notice, body)
    }

    // ---- Mis órdenes ----

    fun mine(context: Context, orders: List<Order>, notice: UiNotice? = null): UiScreen {
        val shown = orders.distinctBy { it.id }.take(ListLimit)
        val me = context.scope.userId
        val assigned = shown.filter { it.assigneeId == me && me != null }
        val reported = shown.filter { it.requestedBy == me && me != null && it.assigneeId != me }
        val body = buildList<UiNode> {
            add(quickActions())
            cutNotice(orders.size)?.let { add(it) }
            if (assigned.isEmpty() && reported.isEmpty()) {
                add(UiCard(listOf(UiText(if (me == null) "Esta cuenta no recibe órdenes: abre Órdenes para verlas." else "No tienes órdenes abiertas."))))
            }
            if (assigned.isNotEmpty()) add(UiCard(listOf(UiList(assigned.map { orderItem(context, it) })), title = "Asignadas a mí (${assigned.size})"))
            if (reported.isNotEmpty()) add(UiCard(listOf(UiList(reported.map { orderItem(context, it) })), title = "Reportadas por mí (${reported.size})"))
        }
        return screen(context, "Mis órdenes", "mine", notice, body)
    }

    // ---- Órdenes ----

    fun viewOf(params: Map<String, String>): String = Views.firstOrNull { it.first == params["view"] }?.first ?: ViewList

    private fun filterForm(context: Context, view: String, params: Map<String, String>): UiNode {
        val all = MantenimientoActions.AllOption
        val fields = buildList {
            add(UiField("q", "Buscar", placeholder = "Folio, título o descripción"))
            if (view != ViewBoard) add(UiField("status", "Estado", type = "dropdown", options = listOf(all) + OrderStatus.entries.map { it.label }))
            add(UiField("kind", "Tipo", type = "dropdown", options = listOf(all) + OrderKind.entries.map { it.label }))
            add(UiField("property", "Propiedad", type = "dropdown", options = listOf(all) + context.properties.map { it.name }))
            add(UiField("assignee", "Responsable", type = "dropdown", options = listOf(all) + context.users.map { it.label }))
        }
        val values = fields.associate { field ->
            field.key to (params[field.key]?.takeIf { it.isNotBlank() } ?: if (field.type == "dropdown") all else "")
        }
        return UiCard(
            listOf(
                UiForm(
                    "filters", fields, values,
                    submit = UiButton("Filtrar", UiNavigate("orders", mapOf("view" to view), formId = "filters"), style = "secondary"),
                    minFieldWidth = 200,
                ),
            ),
        )
    }

    fun orders(context: Context, data: OrdersData, params: Map<String, String>, notice: UiNotice? = null): UiScreen {
        val view = viewOf(params)
        val selector = UiRow(
            Views.map { (id, label) -> UiButton(label, UiNavigate("orders", mapOf("view" to id), formId = "filters"), style = if (id == view) "primary" else "secondary") } +
                listOf(
                    UiButton("Reportar avería", UiNavigate("new-order", mapOf("kind" to OrderKind.Corrective.id)), style = "secondary"),
                    UiButton("Solicitar proyecto", UiNavigate("new-order", mapOf("kind" to OrderKind.Project.id)), style = "secondary"),
                ),
        )
        val body = buildList<UiNode> {
            add(selector)
            add(filterForm(context, view, params))
            if (view == ViewBoard) {
                addAll(boardView(context, data, params))
            } else {
                cutNotice(data.orders.size)?.let { add(it) }
                add(UiCard(listOf(UiList(data.orders.take(ListLimit).map { orderItem(context, it) }, emptyText = "No hay órdenes con estos filtros.")), title = "Órdenes"))
            }
        }
        return screen(context, "Órdenes", "orders", notice, body)
    }

    /** Estados que muestra el tablero: los de decisión y el rechazo solo aparecen si tienen tarjetas. */
    fun boardStatuses(columns: Map<OrderStatus, List<Order>>): List<OrderStatus> = OrderStatus.entries.filter { status ->
        status !in setOf(OrderStatus.Requested, OrderStatus.Quoting, OrderStatus.Rejected) || columns[status].orEmpty().isNotEmpty()
    }

    private fun boardView(context: Context, data: OrdersData, params: Map<String, String>): List<UiNode> {
        val keep = MantenimientoActions.filterParams(params) - "status"
        val move = UiRequest("$Route/orders", params = keep + mapOf("view" to ViewBoard, "op" to "move"))
        val columns = boardStatuses(data.columns).map { status ->
            val orders = data.columns[status].orEmpty()
            UiBoardColumn(
                id = status.id,
                title = status.label,
                tone = statusTone(status),
                caption = if (orders.size > KanbanLimit) "Mostrando $KanbanLimit: usa los filtros para ver el resto" else "",
                cards = orders.take(KanbanLimit).map { order ->
                    UiBoardCard(
                        id = order.id.toString(),
                        title = "${order.displayNumber} · ${order.title}",
                        subtitle = listOfNotNull(order.propertyName, order.assetName).joinToString(" · "),
                        details = listOfNotNull(
                            order.assigneeId?.let { "Responsable: ${context.names.label(it)}" },
                            order.dueDate?.let { "Vence: $it" },
                            order.checklistTotal.takeIf { it > 0 }?.let { "Lista: ${order.checklistDone}/$it" },
                        ),
                        badges = orderBadges(order, context.today, withStatus = false),
                        action = openOrder(order),
                    )
                },
            )
        }
        return listOf(UiBoard(columns, move = move, emptyText = "Sin órdenes."))
    }

    // ---- Orden ----

    private fun fileRefs(attachments: List<Attachment>): List<UiFileRef> = attachments.map { UiFileRef(it.id.toString(), it.name, it.path, it.size) }

    private fun filesField(readOnly: Boolean) = UiField(
        "files", "Adjuntos", type = "files", readOnly = readOnly,
        files = UiFiles(upload = "$Route/files", download = "$Route/files"),
    )

    /** Los botones de estado que [scope] puede usar en [order]; rechazar va aparte (pide un motivo). El repositorio vuelve a aplicar las reglas. */
    fun statusActions(scope: MantenimientoScope, order: Order): List<StatusAction> {
        if (order.archivedAt != null) return emptyList()
        return OrderTransitions.targets(order.kind, order.status, TransitionRole.of(scope, order)).mapNotNull { to ->
            when {
                OrderTransitions.requiresReason(to) -> null
                OrderTransitions.isApproval(order.kind, order.status, to) -> StatusAction("Aprobar", to)
                to == OrderStatus.Quoting -> StatusAction("Pasar a cotización", to, "secondary")
                to == OrderStatus.Requested -> StatusAction("Volver a pendiente", to, "secondary")
                to == OrderStatus.InProgress && order.status == OrderStatus.Done -> StatusAction("Reabrir", to, "secondary")
                to == OrderStatus.InProgress && order.status == OrderStatus.OnHold -> StatusAction("Reanudar", to)
                to == OrderStatus.InProgress -> StatusAction("Empezar", to)
                to == OrderStatus.OnHold -> StatusAction("Poner en espera", to, "secondary")
                to == OrderStatus.Open -> StatusAction("Volver a abierta", to, "secondary")
                to == OrderStatus.Done -> StatusAction("Completar", to)
                to == OrderStatus.Cancelled -> StatusAction("Cancelar orden", to, "danger", "¿Cancelar esta orden?")
                else -> null
            }
        }
    }

    /** Se puede rechazar (decidir un proyecto) y hay que pedir el motivo. */
    fun canReject(scope: MantenimientoScope, order: Order): Boolean =
        order.archivedAt == null && OrderTransitions.allowed(order.kind, order.status, OrderStatus.Rejected, TransitionRole.of(scope, order))

    private fun orderRequest(op: String, order: Order, extra: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest("$Route/order", params = mapOf("op" to op, "id" to order.id.toString()) + extra, confirm = confirm, formId = formId)

    /** Los botones de compra: solo si Compras está disponible para este usuario. */
    fun purchaseButtons(scope: MantenimientoScope, detail: OrderDetail): List<UiButton> {
        if (!detail.purchaseAvailable) return emptyList()
        val order = detail.order
        MantenimientoPurchases.openRequestAction(order)?.let { return listOf(UiButton("Ver solicitud de compra", it, style = "secondary")) }
        if (scope.canWork(order) && order.status.isActive) {
            return listOf(
                UiButton(
                    "Solicitar compra",
                    orderRequest("purchase", order, confirm = "¿Crear en Compras una solicitud borrador con los datos de esta orden?"),
                    style = "secondary",
                ),
            )
        }
        return emptyList()
    }

    private fun propertyOptions(context: Context, order: Order?): List<String> =
        listOf(MantenimientoActions.NoProperty) + (context.properties.map { it.name } + listOfNotNull(order?.propertyName)).distinctBy { it.lowercase() }

    private fun assigneeOptions(context: Context, order: Order?): List<String> {
        val retired = order?.assigneeId?.let { id -> context.users.none { it.id == id } } == true
        return listOf(MantenimientoActions.NoAssignee) + context.users.map { it.label } + listOfNotNull(MantenimientoActions.RetiredUser.takeIf { retired })
    }

    /** Campos del formulario de la orden: [canEdit] (gestionar) abre los datos; [canWork] abre costo real y resolución. */
    fun orderFields(context: Context, detail: OrderDetail, canEdit: Boolean, canWork: Boolean, withFiles: Boolean): List<UiField> = buildList {
        val order = detail.order
        add(UiField("title", "Título", required = true, readOnly = !canEdit, section = "Datos"))
        add(UiField("description", "Descripción", type = "textarea", readOnly = !canEdit, section = "Datos"))
        add(UiField("kind", "Tipo", readOnly = true, section = "Datos"))
        add(UiField("priority", "Prioridad", type = "dropdown", options = OrderPriority.entries.map { it.label }, readOnly = !canEdit, section = "Datos"))
        add(UiField("property", "Propiedad", type = "dropdown", options = propertyOptions(context, order), readOnly = !canEdit, section = "Datos"))
        add(UiField("asset", "Activo", type = "dropdown", options = listOf(MantenimientoActions.NoAsset) + detail.assets.labels, readOnly = !canEdit, section = "Datos"))
        add(UiField("due", "Fecha límite", type = "date", readOnly = !canEdit, section = "Datos"))
        add(UiField("assignee", "Responsable", type = "dropdown", options = assigneeOptions(context, order), readOnly = !canEdit, section = "Datos"))
        add(UiField("department", "Departamento", readOnly = !canEdit, section = "Datos"))
        if (order.kind == OrderKind.Project) {
            add(UiField("quote", "Cotización", type = "number", readOnly = !canEdit, section = "Cotización"))
            add(UiField("quote_currency", "Moneda de la cotización", readOnly = !canEdit, help = "Tres letras, por ejemplo USD.", section = "Cotización"))
            add(UiField("quoted_by", "Cotizado por", readOnly = !canEdit, section = "Cotización"))
        }
        if (showsWork(order)) {
            add(UiField("cost", "Costo real", type = "number", readOnly = !canWork, section = "Cierre"))
            add(UiField("cost_currency", "Moneda del costo", readOnly = !canWork, help = "Tres letras, por ejemplo USD.", section = "Cierre"))
            add(UiField("resolution", "Resolución", type = "textarea", readOnly = !canWork, help = "Al completar la orden se guardan el costo y la resolución escritos aquí.", section = "Cierre"))
        }
        if (withFiles) add(filesField(readOnly = !canEdit))
    }

    /** Costo real y resolución solo tienen sentido cuando la orden está en trabajo o ya se completó. */
    fun showsWork(order: Order): Boolean = order.status.isWorking || order.status == OrderStatus.Done

    private fun orderValues(context: Context, detail: OrderDetail, withFiles: Boolean): Map<String, String> = buildMap {
        val order = detail.order
        put("title", order.title)
        put("description", order.description)
        put("kind", order.kind.label)
        put("priority", order.priority.label)
        put("property", order.propertyName ?: MantenimientoActions.NoProperty)
        put("asset", detail.assets.label(order.assetId) ?: MantenimientoActions.NoAsset)
        put("due", order.dueDate?.toString().orEmpty())
        put("assignee", order.assigneeId?.let { context.names.label(it) } ?: MantenimientoActions.NoAssignee)
        put("department", order.department)
        put("quote", order.quoteAmount?.toPlainString().orEmpty())
        put("quote_currency", order.quoteCurrency)
        put("quoted_by", order.quotedBy)
        put("cost", order.costAmount?.toPlainString().orEmpty())
        put("cost_currency", order.costCurrency)
        put("resolution", order.resolution)
        if (withFiles) put("files", UiFieldValues.files(fileRefs(detail.attachments)))
    }

    fun order(context: Context, detail: OrderDetail, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val order = detail.order
        val scope = context.scope
        val canEdit = scope.canEdit(order)
        val canWork = MantenimientoActions.canWorkNow(scope, order)
        val hasForm = canEdit || canWork
        val canAttach = MantenimientoActions.canAttach(scope, order)
        val statusButtons = statusActions(scope, order).map { action ->
            UiButton(
                action.label,
                // Completar lleva el costo y la resolución del formulario.
                orderRequest("status", order, mapOf("status" to action.status.id), confirm = action.confirm, formId = "order".takeIf { action.status == OrderStatus.Done && hasForm }),
                style = action.style,
            )
        }
        val actions = UiRow(
            statusButtons + listOfNotNull(UiButton("Tomar orden", orderRequest("take", order)).takeIf { scope.canTake(order) }) +
                purchaseButtons(scope, detail) +
                listOf(UiButton("Volver", UiNavigate(if (scope.seesAll) "orders" else "mine"), style = "secondary")),
        )
        val header = UiRow(
            listOfNotNull(
                UiStatus(order.status.label, statusTone(order.status)),
                UiStatus(order.priority.label, priorityTone(order.priority)),
                UiStatus("Vencida", UiTone.Danger).takeIf { order.isOverdue(context.today) },
                hint(
                    listOfNotNull(
                        order.displayNumber,
                        order.kind.label,
                        order.requestedByName.takeIf { it.isNotBlank() }?.let { "Solicitada por $it" },
                        order.dueDate?.let { "Vence $it" },
                        order.purchaseFolio.takeIf { it.isNotBlank() }?.let { "Compra $it" },
                    ).joinToString(" · "),
                ),
            ),
            spacing = 8,
        )
        // Quien no gestiona adjunta en un formulario aparte: el principal no lleva el campo de archivos.
        val filesInMain = canEdit
        val form = UiForm(
            id = "order",
            fields = orderFields(context, detail, canEdit, canWork, withFiles = filesInMain),
            values = orderValues(context, detail, filesInMain) + submitted.orEmpty(),
            submit = UiButton("Guardar", orderRequest("save", order, formId = "order")).takeIf { hasForm },
        )
        val body = buildList<UiNode> {
            add(header)
            if (order.status == OrderStatus.Rejected && order.rejectedReason.isNotBlank()) add(hint("Motivo del rechazo: ${order.rejectedReason}", UiTone.Warning))
            add(actions)
            if (canReject(scope, order)) add(decisionCard(order))
            add(UiCard(listOf(form), title = "Detalles"))
            if (!filesInMain) add(attachmentsCard(order, detail.attachments, canAttach))
            add(checklistCard(order, detail.checklist, MantenimientoActions.canWorkChecklist(scope, order), canEdit))
            add(notesCard(context, order, detail.notes, MantenimientoActions.canNote(scope, order)))
            detail.plan?.let { plan ->
                add(
                    UiCard(
                        listOf(
                            hint("Esta orden la generó el plan \"${plan.title}\" (${plan.frequency.label.lowercase()})."),
                            UiButton("Ver plan", UiNavigate("plan", mapOf("id" to plan.id.toString())), style = "secondary"),
                        ),
                        title = "Plan de origen",
                    ),
                )
            }
        }
        return screen(context, "${order.displayNumber} · ${order.title}", if (scope.seesAll) "orders" else "mine", notice, body)
    }

    private fun decisionCard(order: Order): UiNode = UiCard(
        listOf(
            UiForm(
                id = "decision",
                fields = listOf(UiField("reason", "Motivo del rechazo", type = "textarea", help = "Obligatorio solo al rechazar.")),
            ),
            UiRow(
                listOf(
                    UiButton(
                        "Rechazar proyecto",
                        orderRequest("status", order, mapOf("status" to OrderStatus.Rejected.id), confirm = "¿Rechazar esta solicitud?", formId = "decision"),
                        style = "danger",
                    ),
                ),
            ),
        ),
        title = "Decisión",
    )

    private fun attachmentsCard(order: Order, attachments: List<Attachment>, canAttach: Boolean): UiNode {
        if (!canAttach && attachments.isEmpty()) return UiCard(listOf(hint("Sin adjuntos.")), title = "Adjuntos")
        return UiCard(
            listOf(
                UiForm(
                    id = "attachments",
                    fields = listOf(filesField(readOnly = !canAttach)),
                    values = mapOf("files" to UiFieldValues.files(fileRefs(attachments))),
                    submit = UiButton("Guardar adjuntos", orderRequest("files", order, formId = "attachments"), style = "secondary").takeIf { canAttach },
                ),
            ),
            title = "Adjuntos",
        )
    }

    private fun checklistCard(order: Order, items: List<OrderCheck>, canWork: Boolean, canEdit: Boolean): UiNode {
        val rows = items.map { item ->
            UiRow(
                listOfNotNull(
                    UiButton(
                        (if (item.done) "[x] " else "[ ] ") + item.text,
                        orderRequest("check-toggle", order, mapOf("item" to item.id.toString(), "done" to (!item.done).toString())),
                        style = "secondary",
                        enabled = canWork,
                    ),
                    UiButton("Quitar", orderRequest("check-remove", order, mapOf("item" to item.id.toString())), style = "danger").takeIf { canEdit },
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
                        submit = UiButton("Añadir", orderRequest("check-add", order, formId = "check"), style = "secondary"),
                    ),
                )
            }
        }
        val title = if (order.checklistTotal > 0) "Lista de comprobación (${order.checklistDone}/${order.checklistTotal})" else "Lista de comprobación"
        return UiCard(children, title = title)
    }

    private fun notesCard(context: Context, order: Order, notes: List<OrderNote>, canNote: Boolean): UiNode {
        val children = buildList<UiNode> {
            add(
                UiTimeline(
                    notes.map { note ->
                        val caption = listOf(note.authorName, stamp(note.createdAt, context.zone)).filter { it.isNotBlank() }.joinToString(" - ")
                        if (note.kind == "note") UiTimelineEntry(note.authorName.ifBlank { "Nota" }, note.body, stamp(note.createdAt, context.zone))
                        else UiTimelineEntry(note.body, caption = caption)
                    },
                    emptyText = "Aún no hay notas.",
                ),
            )
            if (canNote) {
                add(
                    UiForm(
                        id = "note",
                        fields = listOf(UiField("body", "Nota", type = "textarea", required = true)),
                        submit = UiButton("Anotar", orderRequest("note", order, formId = "note"), style = "secondary"),
                    ),
                )
            }
        }
        return UiCard(children, title = "Notas e historial (${notes.size})")
    }

    // ---- Nueva orden (avería o proyecto) ----

    /** Los tipos que se pueden crear a mano: las preventivas las genera un plan. */
    fun creatableKind(text: String?): OrderKind = OrderKind.fromId(text)?.takeIf { it != OrderKind.Preventive } ?: OrderKind.Corrective

    fun newOrderFields(context: Context, kind: OrderKind, assets: AssetOptions): List<UiField> = buildList {
        add(UiField("title", "Título", required = true))
        add(UiField("description", "Descripción", type = "textarea"))
        if (kind == OrderKind.Project) add(UiField("department", "Departamento"))
        add(UiField("property", "Propiedad", type = "dropdown", options = propertyOptions(context, null)))
        add(UiField("asset", "Activo", type = "dropdown", options = listOf(MantenimientoActions.NoAsset) + assets.labels))
        add(UiField("priority", "Prioridad", type = "dropdown", options = OrderPriority.entries.map { it.label }))
        if (kind == OrderKind.Corrective && context.scope.canManage) {
            add(UiField("assignee", "Responsable", type = "dropdown", options = assigneeOptions(context, null)))
            add(UiField("due", "Fecha límite", type = "date"))
        }
        if (kind == OrderKind.Project) {
            add(UiField("quote", "Cotización (opcional)", type = "number"))
            add(UiField("quote_currency", "Moneda de la cotización", help = "Tres letras, por ejemplo USD."))
        }
        add(filesField(readOnly = false))
    }

    fun newOrder(context: Context, kind: OrderKind, assets: AssetOptions, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val base = mapOf(
            "priority" to OrderPriority.Medium.label,
            "property" to MantenimientoActions.NoProperty,
            "asset" to MantenimientoActions.NoAsset,
            "assignee" to MantenimientoActions.NoAssignee,
            "quote_currency" to "USD",
        )
        val title = if (kind == OrderKind.Project) "Solicitar proyecto" else "Reportar avería"
        val form = UiForm(
            id = "order",
            fields = newOrderFields(context, kind, assets),
            values = base + submitted.orEmpty(),
            submit = UiButton(
                if (kind == OrderKind.Project) "Enviar solicitud" else "Reportar",
                UiRequest("$Route/new-order", params = mapOf("op" to "create", "kind" to kind.id), formId = "order"),
            ),
        )
        val body = listOf(
            UiRow(
                listOf(
                    UiButton("Reportar avería", UiNavigate("new-order", mapOf("kind" to OrderKind.Corrective.id)), style = if (kind == OrderKind.Corrective) "primary" else "secondary"),
                    UiButton("Solicitar proyecto", UiNavigate("new-order", mapOf("kind" to OrderKind.Project.id)), style = if (kind == OrderKind.Project) "primary" else "secondary"),
                ),
            ),
            if (kind == OrderKind.Project) hint("Un proyecto espera la aprobación de quien gestiona mantenimiento.") else hint("Cuenta qué falla y dónde: avisamos a quien gestiona mantenimiento."),
            UiCard(listOf(form), title = title),
            UiRow(listOf(UiButton("Cancelar", UiNavigate("mine"), style = "secondary"))),
        )
        return screen(context, title, "upcoming", notice, body)
    }

    // ---- Activos ----

    fun assets(context: Context, assets: List<Asset>, property: Property?, query: String, notice: UiNotice? = null): UiScreen {
        val filter = UiCard(
            listOf(
                UiForm(
                    "filters",
                    listOf(
                        UiField("q", "Buscar", placeholder = "Nombre, categoría o ubicación"),
                        UiField("property", "Propiedad", type = "dropdown", options = listOf(MantenimientoActions.AllOption) + context.properties.map { it.name }),
                    ),
                    mapOf("q" to query, "property" to (property?.name ?: MantenimientoActions.AllOption)),
                    submit = UiButton("Filtrar", UiNavigate("assets", formId = "filters"), style = "secondary"),
                    minFieldWidth = 220,
                ),
            ),
        )
        val items = assets.take(ListLimit).map { asset ->
            UiListItem(
                title = asset.name,
                subtitle = listOf(asset.category, asset.location).filter { it.isNotBlank() }.joinToString(" · "),
                trailing = asset.propertyName.orEmpty(),
                badges = listOfNotNull(UiBadge("Archivado", UiTone.Neutral).takeIf { asset.archived }),
                action = UiNavigate("asset", mapOf("id" to asset.id.toString())),
            )
        }
        val body = buildList<UiNode> {
            if (context.scope.canManage) add(UiRow(listOf(UiButton("Nuevo activo", UiNavigate("asset")))))
            add(filter)
            if (assets.size > ListLimit) add(hint("Se muestran los primeros $ListLimit activos: usa los filtros para acotar.", UiTone.Warning))
            add(UiCard(listOf(UiList(items, emptyText = "No hay activos con estos filtros.")), title = "Activos"))
        }
        return screen(context, "Activos", "assets", notice, body)
    }

    /** [asset] es null para un activo nuevo. */
    fun asset(
        context: Context,
        asset: Asset?,
        history: List<Order>,
        plans: List<Plan>,
        notice: UiNotice? = null,
        submitted: Map<String, String>? = null,
    ): UiScreen {
        val canManage = context.scope.canManage
        fun request(op: String, confirm: String? = null, formId: String? = null) =
            UiRequest("$Route/asset", params = mapOf("op" to op) + asset?.let { mapOf("id" to it.id.toString()) }.orEmpty(), confirm = confirm, formId = formId)

        val values = mapOf(
            "name" to asset?.name.orEmpty(),
            "property" to (asset?.propertyName ?: MantenimientoActions.NoProperty),
            "category" to asset?.category.orEmpty(),
            "location" to asset?.location.orEmpty(),
            "notes" to asset?.notes.orEmpty(),
        ) + submitted.orEmpty()
        val propertyOptions = listOf(MantenimientoActions.NoProperty) + (context.properties.map { it.name } + listOfNotNull(asset?.propertyName)).distinctBy { it.lowercase() }
        val form = UiForm(
            id = "asset",
            fields = listOf(
                UiField("name", "Nombre", required = true, readOnly = !canManage),
                UiField("property", "Propiedad", type = "dropdown", options = propertyOptions, readOnly = !canManage),
                UiField("category", "Categoría", readOnly = !canManage),
                UiField("location", "Ubicación", readOnly = !canManage),
                UiField("notes", "Notas", type = "textarea", readOnly = !canManage),
            ),
            values = values,
            submit = UiButton(if (asset == null) "Crear activo" else "Guardar", request("save", formId = "asset")).takeIf { canManage },
        )
        val body = buildList<UiNode> {
            if (asset?.archived == true) add(hint("Activo archivado.", UiTone.Warning))
            add(UiCard(listOf(form), title = "Activo"))
            if (asset != null) {
                if (canManage) {
                    add(
                        UiRow(
                            listOf(
                                if (asset.archived) UiButton("Restaurar activo", request("restore"), style = "secondary")
                                else UiButton("Archivar activo", request("archive", confirm = "¿Archivar este activo? Deja de ofrecerse en los formularios."), style = "danger"),
                            ),
                        ),
                    )
                }
                if (history.size > HistoryLimit) add(hint("Se muestran las últimas $HistoryLimit órdenes del activo.", UiTone.Warning))
                add(UiCard(listOf(UiList(history.take(HistoryLimit).map { orderItem(context, it) }, emptyText = "Este activo no tiene órdenes.")), title = "Historial"))
                add(UiCard(listOf(UiList(plans.map { planItem(it) }, emptyText = "Este activo no tiene planes.")), title = "Planes"))
            }
            add(UiRow(listOf(UiButton("Volver a activos", UiNavigate("assets"), style = "secondary"))))
        }
        return screen(context, asset?.name ?: "Nuevo activo", "assets", notice, body)
    }

    // ---- Planes ----

    fun plans(context: Context, plans: List<Plan>, notice: UiNotice? = null): UiScreen {
        val active = plans.filter { it.active }
        val inactive = plans.filterNot { it.active }
        val body = buildList<UiNode> {
            if (context.scope.canManage) add(UiRow(listOf(UiButton("Nuevo plan", UiNavigate("plan")))))
            add(UiCard(listOf(UiList(active.map { planItem(it) }, emptyText = "Aún no hay planes preventivos.")), title = "Planes activos (${active.size})"))
            if (inactive.isNotEmpty()) add(UiCard(listOf(UiList(inactive.map { planItem(it) })), title = "Planes inactivos (${inactive.size})", collapsible = true, expanded = false))
        }
        return screen(context, "Planes", "plans", notice, body)
    }

    /** [plan] es null para un plan nuevo. [checklist] son los elementos actuales y [generated] las últimas órdenes que generó. */
    fun plan(
        context: Context,
        plan: Plan?,
        checklist: List<PlanCheck>,
        generated: List<Order>,
        assets: AssetOptions,
        notice: UiNotice? = null,
        submitted: Map<String, String>? = null,
    ): UiScreen {
        val canManage = context.scope.canManage
        fun request(op: String, extra: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
            UiRequest("$Route/plan", params = mapOf("op" to op) + plan?.let { mapOf("id" to it.id.toString()) }.orEmpty() + extra, confirm = confirm, formId = formId)

        val propertyOptions = listOf(MantenimientoActions.NoProperty) + (context.properties.map { it.name } + listOfNotNull(plan?.propertyName)).distinctBy { it.lowercase() }
        val retired = plan?.defaultAssignee?.let { id -> context.users.none { it.id == id } } == true
        val assigneeOptions = listOf(MantenimientoActions.NoAssignee) + context.users.map { it.label } + listOfNotNull(MantenimientoActions.RetiredUser.takeIf { retired })
        val values = mapOf(
            "title" to plan?.title.orEmpty(),
            "description" to plan?.description.orEmpty(),
            "property" to (plan?.propertyName ?: MantenimientoActions.NoProperty),
            "asset" to (assets.label(plan?.assetId) ?: MantenimientoActions.NoAsset),
            "frequency" to (plan?.frequency ?: PlanFrequency.Monthly).label,
            "interval" to plan?.intervalDays?.toString().orEmpty(),
            "schedule_mode" to (plan?.scheduleMode ?: ScheduleMode.Fixed).label,
            "next_due" to plan?.nextDue?.toString().orEmpty(),
            "lead_days" to (plan?.leadDays ?: 7).toString(),
            "assignee" to (plan?.defaultAssignee?.let { context.names.label(it) } ?: MantenimientoActions.NoAssignee),
            "priority" to (plan?.priority ?: OrderPriority.Medium).label,
        ) + submitted.orEmpty()
        val form = UiForm(
            id = "plan",
            fields = listOf(
                UiField("title", "Título", required = true, readOnly = !canManage),
                UiField("description", "Descripción", type = "textarea", readOnly = !canManage),
                UiField("property", "Propiedad", type = "dropdown", options = propertyOptions, readOnly = !canManage),
                UiField("asset", "Activo", type = "dropdown", options = listOf(MantenimientoActions.NoAsset) + assets.labels, readOnly = !canManage),
                UiField("frequency", "Frecuencia", type = "dropdown", options = PlanFrequency.entries.map { it.label }, readOnly = !canManage),
                UiField(
                    "interval", "Cada cuántos días", type = "number", readOnly = !canManage,
                    visibleWhen = UiFieldCondition("frequency", listOf(PlanFrequency.EveryNDays.label)),
                ),
                UiField(
                    "schedule_mode", "Calendario", type = "dropdown", options = ScheduleMode.entries.map { it.label }, readOnly = !canManage,
                    help = "Calendario fijo: la fecha avanza sola. Desde el último cierre: la próxima fecha sale de completar la orden.",
                ),
                UiField("next_due", "Próxima fecha", type = "date", readOnly = !canManage),
                UiField("lead_days", "Días de anticipación", type = "number", readOnly = !canManage, help = "La orden se crea tantos días antes de la fecha."),
                UiField("assignee", "Responsable por defecto", type = "dropdown", options = assigneeOptions, readOnly = !canManage),
                UiField("priority", "Prioridad", type = "dropdown", options = OrderPriority.entries.map { it.label }, readOnly = !canManage),
            ),
            values = values,
            submit = UiButton(if (plan == null) "Crear plan" else "Guardar", request("save", formId = "plan")).takeIf { canManage },
        )
        val body = buildList<UiNode> {
            if (plan != null && !plan.active) add(hint("Plan inactivo: no genera órdenes.", UiTone.Warning))
            if (plan != null && plan.nextDue == null && plan.scheduleMode == ScheduleMode.Floating) {
                add(hint("Espera a que se complete su orden abierta para calcular la próxima fecha."))
            }
            add(UiCard(listOf(form), title = "Plan"))
            if (plan != null && canManage) {
                add(
                    UiRow(
                        listOf(
                            if (plan.active) UiButton("Desactivar plan", request("active", mapOf("active" to "false"), confirm = "¿Desactivar este plan? Deja de generar órdenes."), style = "danger")
                            else UiButton("Activar plan", request("active", mapOf("active" to "true")), style = "secondary"),
                        ),
                    ),
                )
            }
            if (plan != null) {
                add(planChecklistCard(checklist, canManage, ::request))
                if (generated.size > HistoryLimit) add(hint("Se muestran las últimas $HistoryLimit órdenes del plan.", UiTone.Warning))
                add(UiCard(listOf(UiList(generated.take(HistoryLimit).map { orderItem(context, it) }, emptyText = "Este plan aún no generó órdenes.")), title = "Órdenes generadas"))
            } else {
                add(hint("Guarda el plan para añadir su lista de comprobación."))
            }
            add(UiRow(listOf(UiButton("Volver a planes", UiNavigate("plans"), style = "secondary"))))
        }
        return screen(context, plan?.title ?: "Nuevo plan", "plans", notice, body)
    }

    private fun planChecklistCard(
        items: List<PlanCheck>,
        canManage: Boolean,
        request: (String, Map<String, String>, String?, String?) -> UiRequest,
    ): UiNode {
        val children = buildList<UiNode> {
            if (items.isEmpty()) add(hint("Sin elementos: la orden se crea sin lista de comprobación."))
            items.forEachIndexed { index, item ->
                if (index > 0) add(UiDivider())
                val params = mapOf("item" to item.id.toString())
                add(
                    UiRow(
                        listOfNotNull(
                            UiText(item.text),
                            UiButton("Subir", request("plan-check-up", params, null, null), style = "secondary").takeIf { canManage && index > 0 },
                            UiButton("Bajar", request("plan-check-down", params, null, null), style = "secondary").takeIf { canManage && index < items.lastIndex },
                            UiButton("Quitar", request("plan-check-remove", params, null, null), style = "danger").takeIf { canManage },
                        ),
                        spacing = 8,
                    ),
                )
            }
            if (canManage) {
                add(UiDivider())
                add(sectionTitle("Nuevo elemento"))
                add(
                    UiForm(
                        id = "plan-check",
                        fields = listOf(UiField("text", "Texto", required = true)),
                        submit = UiButton("Añadir", request("plan-check-add", emptyMap(), null, "plan-check"), style = "secondary"),
                    ),
                )
            }
        }
        return UiCard(children, title = "Lista de comprobación del plan")
    }
}
