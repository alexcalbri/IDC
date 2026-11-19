package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiBarChart
import com.ideasdeveloper.idc.ui.UiBoard
import com.ideasdeveloper.idc.ui.UiBoardCard
import com.ideasdeveloper.idc.ui.UiBoardColumn
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiChartPoint
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldValues
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
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTimeline
import com.ideasdeveloper.idc.ui.UiTimelineEntry
import com.ideasdeveloper.idc.ui.UiTone
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Pantallas de Compras con piezas genericas de UI. Los constructores `...Screen` son puros: reciben los datos
 * ya cargados y devuelven la [UiScreen], asi se prueban sin base de datos. [build] carga los datos (JDBC,
 * bloqueante: llamar dentro de withContext(Dispatchers.IO)) y llama al constructor de cada pantalla.
 * El dinero se muestra con [ComprasMoney.format]; solo el grafico de barras convierte a Double.
 */
object ComprasScreens {
    const val UiRoute = "/modules/compras/ui"

    /** Donde la app sube y baja los archivos de los formularios (ver ComprasServerModule). */
    const val FilesRoute = "$UiRoute/files"
    const val All = "Todos"
    const val NoDepartment = "Sin departamento"
    const val PageSize = 50
    const val MaxPageSize = 200
    const val BoardPerColumn = 50
    const val Dot = " · "
    val Files = UiFiles(upload = FilesRoute, download = FilesRoute, maxFileBytes = ComprasAttachments.MaxFileBytes)

    /** Entrada del menu del modulo. */
    data class Section(val id: String, val label: String)

    // ---- Datos ya cargados ----

    data class HomeData(
        val pendingForMe: Int,
        val myOpen: Int,
        val highOpen: Int,
        /** Aprobado este mes por moneda; null si el actor no ve totales. */
        val approvedThisMonth: List<TotalRow>?,
        val recent: List<PurchaseRequest>,
    )

    data class RequestsData(
        val params: Map<String, String>,
        val departments: List<Department>,
        val items: List<PurchaseRequest>,
        val total: Int,
        val limit: Int,
    )

    class BoardColumnData(val status: RequestStatus, val cards: List<PurchaseRequest>, val total: Int)

    data class ApprovalsData(val items: List<PurchaseRequest>, val approver: Approver?)

    data class TotalsData(
        val from: LocalDate,
        val to: LocalDate,
        val byDepartment: List<TotalRow>,
        val byMonth: List<TotalRow>,
        val byStatus: List<TotalRow>,
    )

    data class DetailData(
        val request: PurchaseRequest,
        val approver: Approver?,
        val quotes: List<Quote>,
        val attachments: List<ComprasAttachments.Attachment>,
        val events: List<RequestEvent>,
        val departments: List<Department>,
    )

    data class NewData(val departments: List<Department>, val defaultCurrency: String)

    // ---- Menu y permisos ----

    private val Titles = mapOf(
        "home" to "Inicio",
        "requests" to "Solicitudes",
        "board" to "Tablero",
        "approvals" to "Por aprobar",
        "high" to "Altas y criticas",
        "totals" to "Totales",
        "import" to "Importar",
        "request" to "Solicitud",
        "new" to "Nueva solicitud",
        "settings" to "Ajustes",
    )

    val Screens: Set<String> = Titles.keys

    fun isScreen(id: String): Boolean = id in Screens

    fun title(screen: String): String = Titles[screen] ?: ComprasModule.displayName

    /** Quien con [can] puede abrir [screen]. El detalle y la nueva solicitud no estan en el menu. */
    fun canOpen(screen: String, can: (String) -> Boolean): Boolean = when (screen) {
        "home", "requests", "board", "high", "request" -> can(ComprasModule.View)
        "approvals" -> can(ComprasModule.Approve) && can(ComprasModule.View)
        "totals" -> can(ComprasModule.View) && (can(ComprasModule.ViewAll) || can(ComprasModule.Manage))
        "new" -> can(ComprasModule.View) && can(ComprasModule.Create)
        "import" -> can(ComprasModule.View) && can(ComprasModule.Import)
        "settings" -> can(ComprasModule.View) && can(ComprasModule.Manage)
        else -> false
    }

    /** Secciones del menu para quien tiene [can]. */
    fun menu(can: (String) -> Boolean): List<Section> =
        listOf("home", "requests", "board", "approvals", "high", "totals", "import", "settings")
            .filter { canOpen(it, can) }
            .map { Section(it, Titles.getValue(it)) }

    /** Seccion del menu que queda marcada cuando se abre [screen]. */
    fun selectedOf(screen: String): String = when (screen) {
        "request", "new" -> "requests"
        else -> screen
    }

    fun frame(actor: Actor, selected: String, title: String, notice: UiNotice?, body: List<UiNode>) = UiScreen(
        title = title,
        navigation = UiNavigation(
            title = ComprasModule.displayName,
            items = menu(actor::can).map { UiNavItem(it.id, it.label, UiNavigate(it.id)) },
            selected = selectedOf(selected),
        ),
        notice = notice,
        body = listOf<UiNode>(UiText(title, UiTextStyle.Title)) + body,
    )

    // ---- Formato ----

    private val DateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun time(instant: Instant): String = DateTime.format(instant)

    fun money(amount: BigDecimal?, currency: String): String = amount?.let { ComprasMoney.format(it, currency) } ?: "Sin monto"

    fun requestTitle(request: PurchaseRequest) = request.folio + Dot + request.title

    fun badges(request: PurchaseRequest): List<UiBadge> =
        listOf(UiBadge(request.status.label, request.status.tone), UiBadge(request.priority.label, request.priority.tone))

    private fun open(request: PurchaseRequest) = UiNavigate("request", mapOf("id" to request.id.toString()))

    private fun subtitle(request: PurchaseRequest): String = listOfNotNull(
        request.departmentName ?: NoDepartment,
        request.requesterName,
        request.requiredDate?.let { "Requerida $it" },
    ).joinToString(Dot)

    fun listItem(request: PurchaseRequest) = UiListItem(
        title = requestTitle(request),
        subtitle = subtitle(request),
        trailing = money(request.approvalAmount, request.approvalCurrency),
        badges = badges(request),
        action = open(request),
    )

    /** Suma por moneda de filas de totales (cualquier etiqueta), ordenada por moneda. */
    fun sumByCurrency(rows: List<TotalRow>): List<TotalRow> =
        rows.groupBy { it.currency }.toSortedMap().map { (currency, group) ->
            TotalRow("", currency, group.fold(BigDecimal.ZERO) { sum, row -> sum + row.total }, group.sumOf { it.count })
        }

    // ---- Filtros de la lista ----

    /** Filtro de la lista a partir de los parametros del formulario (los nombres que muestran los desplegables). */
    fun filterOf(params: Map<String, String>, departments: List<Department>): RequestFilter = RequestFilter(
        status = RequestStatus.entries.firstOrNull { it.label == params["fStatus"] },
        priority = Priority.entries.firstOrNull { it.label == params["fPriority"] },
        departmentId = departments.firstOrNull { it.name == params["fDepartment"] }?.id,
        mine = params["mine"] == "true",
        text = params["q"]?.trim()?.takeIf { it.isNotEmpty() },
    )

    private val FilterKeys = setOf("q", "fStatus", "fPriority", "fDepartment", "mine")

    private fun keepFilters(params: Map<String, String>): Map<String, String> =
        params.filterKeys { it in FilterKeys }.filterValues { it.isNotBlank() && it != All && it != "false" }

    fun pageLimit(params: Map<String, String>): Int = (params["limit"]?.toIntOrNull() ?: PageSize).coerceIn(PageSize, MaxPageSize)

    // ---- Carga ----

    /** Carga los datos de [screen] y arma la pantalla. Bloqueante (JDBC). */
    fun build(
        tenant: Connection,
        actor: Actor,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice? = null,
        submitted: Map<String, String>? = null,
        // Contexto para las fuentes de importacion (solo la pantalla Importar lo pide).
        importContext: (() -> ComprasImportContext)? = null,
    ): UiScreen {
        if (!canOpen(screen, actor::can)) {
            return frame(actor, "home", ComprasModule.displayName, UiNotice("No tienes permiso para ver esta seccion.", UiTone.Danger), emptyList())
        }
        return when (screen) {
            "requests" -> loadRequests(tenant, actor, params, notice)
            "board" -> loadBoard(tenant, actor, notice)
            "approvals" -> approvalsScreen(
                actor,
                ApprovalsData(ComprasRepository.list(tenant, actor, RequestFilter(pendingMyApproval = true), MaxPageSize, 0), ComprasRepository.approver(tenant, actor)),
                notice,
            )
            "high" -> highScreen(actor, ComprasRepository.list(tenant, actor, RequestFilter(highOnly = true), MaxPageSize, 0), notice)
            "totals" -> loadTotals(tenant, actor, params, notice)
            "request" -> loadDetail(tenant, actor, params, notice, submitted)
            "new" -> newScreen(actor, NewData(ComprasRepository.departments(tenant, onlyActive = true), ComprasRepository.settings(tenant).defaultCurrency), notice, submitted)
            "import" -> {
                val ctx = importContext?.invoke()
                val cards = if (ctx == null) emptyList() else ComprasActions.activeSources(ComprasImportSources.all) { it.isEnabled(ctx) }.map { ImportCard(it.id, it.label, it.screen(ctx, params)) }
                importScreen(actor, cards, notice)
            }
            "settings" -> ComprasAdminScreens.build(tenant, actor, notice, submitted)
            else -> loadHome(tenant, actor, notice)
        }
    }

    private fun loadHome(tenant: Connection, actor: Actor, notice: UiNotice?): UiScreen {
        val openStatuses = RequestStatus.entries.filter { it.open }
        val today = LocalDate.now()
        val data = HomeData(
            pendingForMe = if (actor.can(ComprasModule.Approve)) ComprasRepository.countPendingForMe(tenant, actor) else 0,
            myOpen = openStatuses.sumOf { ComprasRepository.count(tenant, actor, RequestFilter(status = it, mine = true)) },
            highOpen = ComprasRepository.count(tenant, actor, RequestFilter(highOnly = true)),
            approvedThisMonth = if (ComprasVisibility.seesAll(actor)) {
                sumByCurrency(ComprasRepository.totalsByDepartment(tenant, actor, ComprasRepository.TotalsFilter(from = today.withDayOfMonth(1), to = today)))
            } else {
                null
            },
            recent = ComprasRepository.list(tenant, actor, RequestFilter(), 10, 0),
        )
        return homeScreen(actor, data, notice)
    }

    private fun loadRequests(tenant: Connection, actor: Actor, params: Map<String, String>, notice: UiNotice?): UiScreen {
        val departments = ComprasRepository.departments(tenant)
        val filter = filterOf(params, departments)
        val limit = pageLimit(params)
        val items = ComprasRepository.list(tenant, actor, filter, limit, 0)
        val total = ComprasRepository.count(tenant, actor, filter)
        return requestsScreen(actor, RequestsData(params, departments.filter { it.active }, items, total, limit), notice)
    }

    private fun loadBoard(tenant: Connection, actor: Actor, notice: UiNotice?): UiScreen {
        val columns = BoardStatuses.map { status ->
            val filter = RequestFilter(status = status)
            BoardColumnData(
                status,
                ComprasRepository.list(tenant, actor, filter, BoardPerColumn, 0),
                ComprasRepository.count(tenant, actor, filter),
            )
        }
        return boardScreen(actor, columns, notice)
    }

    private fun loadTotals(tenant: Connection, actor: Actor, params: Map<String, String>, notice: UiNotice?): UiScreen {
        val today = LocalDate.now()
        val parsedFrom = params["from"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val parsedTo = params["to"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val from = parsedFrom ?: today.withDayOfYear(1)
        val to = parsedTo ?: today
        if (from.isAfter(to)) {
            return totalsScreen(actor, TotalsData(from, to, emptyList(), emptyList(), emptyList()), UiNotice("La fecha inicial es posterior a la final.", UiTone.Warning))
        }
        val filter = ComprasRepository.TotalsFilter(from = from, to = to)
        val data = TotalsData(
            from, to,
            ComprasRepository.totalsByDepartment(tenant, actor, filter),
            ComprasRepository.totalsByMonth(tenant, actor, filter),
            ComprasRepository.totalsByStatus(tenant, actor, filter),
        )
        return totalsScreen(actor, data, notice)
    }

    private fun loadDetail(tenant: Connection, actor: Actor, params: Map<String, String>, notice: UiNotice?, submitted: Map<String, String>?): UiScreen {
        val id = params["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val request = id?.let { ComprasRepository.get(tenant, it, actor) }
            ?: return frame(actor, "requests", title("request"), notice ?: UiNotice("Esa solicitud no existe o no tienes acceso a ella.", UiTone.Warning), emptyList())
        val data = DetailData(
            request = request,
            approver = ComprasRepository.approver(tenant, actor),
            quotes = ComprasRepository.quotes(tenant, actor, request.id),
            attachments = ComprasAttachments.forRequest(tenant, actor, request.id),
            events = ComprasRepository.events(tenant, actor, request.id),
            departments = ComprasRepository.departments(tenant),
        )
        return detailScreen(actor, data, notice, submitted)
    }

    // ---- Importar ----

    /** Tarjeta de una fuente de importacion: sus piezas las arma la extension. */
    class ImportCard(val id: String, val label: String, val nodes: List<UiNode>)

    fun importScreen(actor: Actor, cards: List<ImportCard>, notice: UiNotice? = null): UiScreen {
        val body: List<UiNode> = if (cards.isEmpty()) {
            listOf(UiText("Instala la extension Importar desde Monday para traer tus tableros.", UiTextStyle.Caption))
        } else {
            cards.map { UiCard(it.nodes, title = it.label) }
        }
        return frame(actor, "import", title("import"), notice, body)
    }

    // ---- Inicio ----

    fun homeScreen(actor: Actor, data: HomeData, notice: UiNotice? = null): UiScreen {
        val metrics = buildList<UiNode> {
            if (actor.can(ComprasModule.Approve)) {
                add(
                    UiCard(
                        listOf(
                            UiMetric(
                                "Por aprobar por mi", data.pendingForMe.toString(),
                                caption = if (data.pendingForMe > 0) "Revisa Por aprobar" else "Al dia",
                                captionTone = if (data.pendingForMe > 0) UiTone.Warning else UiTone.Success,
                            ),
                        ),
                    ),
                )
            }
            add(UiCard(listOf(UiMetric("Mis abiertas", data.myOpen.toString()))))
            add(UiCard(listOf(UiMetric("Altas y criticas abiertas", data.highOpen.toString(), captionTone = UiTone.Warning))))
            data.approvedThisMonth?.let { rows ->
                if (rows.isEmpty()) {
                    add(UiCard(listOf(UiMetric("Aprobado este mes", "0.00"))))
                } else {
                    rows.forEach { row -> add(UiCard(listOf(UiMetric("Aprobado este mes (${row.currency})", money(row.total, row.currency), caption = "${row.count} solicitudes")))) }
                }
            }
        }
        val body = buildList<UiNode> {
            add(UiGrid(metrics, minItemWidth = 180))
            if (actor.can(ComprasModule.Create)) add(UiRow(listOf(UiButton("Nueva solicitud", UiNavigate("new")))))
            add(UiCard(listOf(UiList(data.recent.map(::listItem), emptyText = "Aun no hay solicitudes.")), title = "Mas recientes"))
        }
        return frame(actor, "home", title("home"), notice, body)
    }

    // ---- Solicitudes ----

    fun requestsScreen(actor: Actor, data: RequestsData, notice: UiNotice? = null): UiScreen {
        val fields = listOf(
            UiField("q", "Buscar", placeholder = "Folio, titulo, descripcion o solicitante"),
            UiField("fStatus", "Estado", type = "dropdown", options = listOf(All) + RequestStatus.entries.map { it.label }),
            UiField("fPriority", "Prioridad", type = "dropdown", options = listOf(All) + Priority.entries.map { it.label }),
            UiField("fDepartment", "Departamento", type = "dropdown", options = listOf(All) + data.departments.map { it.name }),
            UiField("mine", "Solo mias", type = "boolean"),
        )
        val values = fields.associate { field ->
            field.key to (data.params[field.key] ?: if (field.type == "dropdown") All else if (field.type == "boolean") "false" else "")
        }
        val filters = UiCard(
            listOf(
                UiForm(
                    "filters", fields, values,
                    submit = UiButton("Filtrar", UiNavigate("requests", formId = "filters"), style = "secondary"),
                    minFieldWidth = 200,
                ),
            ),
        )
        val more = if (data.items.size >= data.limit && data.total > data.items.size && data.limit < MaxPageSize) {
            listOf(UiButton("Ver mas", UiNavigate("requests", keepFilters(data.params) + ("limit" to (data.limit + PageSize).toString())), style = "secondary"))
        } else {
            emptyList()
        }
        val header = buildList<UiNode> {
            if (actor.can(ComprasModule.Create)) add(UiRow(listOf(UiButton("Nueva solicitud", UiNavigate("new")))))
            add(filters)
        }
        val caption = UiText("Mostrando ${data.items.size} de ${data.total} solicitudes.", UiTextStyle.Caption)
        return frame(actor, "requests", title("requests"), notice, header + UiList(data.items.map(::listItem), emptyText = "No hay solicitudes con esos filtros.") + caption + more)
    }

    // ---- Tablero ----

    /** Una columna por estado, sin canceladas y con rechazadas al final. */
    val BoardStatuses: List<RequestStatus> = RequestStatus.entries.filter { it != RequestStatus.Cancelled && it != RequestStatus.Rejected } + RequestStatus.Rejected

    fun boardCard(request: PurchaseRequest) = UiBoardCard(
        id = request.id.toString(),
        title = requestTitle(request),
        subtitle = subtitle(request),
        details = listOf(money(request.approvalAmount, request.approvalCurrency)),
        badges = listOf(UiBadge(request.priority.label, request.priority.tone)),
        action = open(request),
    )

    fun boardScreen(actor: Actor, columns: List<BoardColumnData>, notice: UiNotice? = null): UiScreen {
        val canMove = actor.can(ComprasModule.Create) || actor.can(ComprasModule.Manage) || actor.can(ComprasModule.Approve)
        val board = UiBoard(
            columns = columns.map { column ->
                UiBoardColumn(
                    id = column.status.id,
                    title = column.status.label,
                    tone = column.status.tone,
                    caption = if (column.total == 1) "1 solicitud" else "${column.total} solicitudes",
                    cards = column.cards.take(BoardPerColumn).map(::boardCard),
                )
            },
            move = if (canMove) UiRequest("$UiRoute/board", params = mapOf("op" to "move")) else null,
            emptyText = "Sin solicitudes.",
        )
        return frame(actor, "board", title("board"), notice, listOf(board))
    }

    // ---- Por aprobar ----

    fun approvalsScreen(actor: Actor, data: ApprovalsData, notice: UiNotice? = null): UiScreen {
        val body = buildList<UiNode> {
            val approver = data.approver
            if (!actor.isOwner) {
                if (approver == null) {
                    add(UiText("Tienes permiso para aprobar, pero no estas en la lista de aprobadores: pide a quien administra Compras que te agregue en Ajustes.", tone = UiTone.Warning))
                } else if (!approver.active) {
                    add(UiText("Tu aprobador esta inactivo: pide a quien administra Compras que lo active en Ajustes.", tone = UiTone.Warning))
                }
            }
            if (data.items.isEmpty()) {
                add(UiText("No hay solicitudes pendientes dentro de tu limite.", UiTextStyle.Caption))
            }
            data.items.forEach { request ->
                val form = "decision-${request.id}"
                val params = mapOf("id" to request.id.toString())
                add(
                    UiCard(
                        title = requestTitle(request),
                        children = listOf(
                            UiText(subtitle(request)),
                            UiText("Monto: " + money(request.approvalAmount, request.approvalCurrency), UiTextStyle.Subtitle),
                            UiForm(form, listOf(UiField("reason", "Motivo o comentario", type = "textarea", help = "Obligatorio al rechazar.")), minFieldWidth = 320),
                            UiRow(
                                listOf(
                                    UiButton("Aprobar", UiRequest("$UiRoute/approvals", params = params + ("op" to "approve"), formId = form)),
                                    UiButton("Rechazar", UiRequest("$UiRoute/approvals", params = params + ("op" to "reject"), formId = form), style = "danger"),
                                    UiButton("Ver detalle", open(request), style = "secondary"),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        return frame(actor, "approvals", title("approvals"), notice, body)
    }

    // ---- Altas y criticas ----

    /** Criticas primero, luego por fecha requerida (sin fecha al final) y por antiguedad. */
    fun sortHigh(items: List<PurchaseRequest>): List<PurchaseRequest> =
        items.filter { it.status.open }.sortedWith(
            compareByDescending<PurchaseRequest> { it.priority.rank }
                .thenBy { it.requiredDate == null }
                .thenBy { it.requiredDate ?: LocalDate.MAX }
                .thenBy { it.createdAt },
        )

    fun highScreen(actor: Actor, items: List<PurchaseRequest>, notice: UiNotice? = null): UiScreen =
        frame(
            actor, "high", title("high"), notice,
            listOf(UiList(sortHigh(items).map(::listItem), emptyText = "No hay solicitudes altas o criticas abiertas.")),
        )

    // ---- Totales ----

    private fun statusLabel(id: String) = RequestStatus.fromId(id)?.label ?: id

    private fun totalsBlock(title: String, rows: List<TotalRow>, labelOf: (String) -> String, chart: Boolean): UiNode {
        if (rows.isEmpty()) return UiCard(listOf(UiText("Sin datos en este periodo.", UiTextStyle.Caption)), title = title)
        val children = rows.groupBy { it.currency }.toSortedMap().flatMap { (currency, group) ->
            listOfNotNull<UiNode>(
                UiText(currency, UiTextStyle.Subtitle),
                UiTable(
                    columns = listOf(UiTableColumn("label", "Concepto"), UiTableColumn("count", "Solicitudes"), UiTableColumn("total", "Total")),
                    rows = group.map { mapOf("label" to labelOf(it.label), "count" to it.count.toString(), "total" to ComprasMoney.format(it.total, currency)) },
                ),
                // Double solo para dibujar el grafico: las tablas muestran el monto exacto.
                if (chart) UiBarChart(group.map { UiChartPoint(labelOf(it.label), it.total.toDouble()) }) else null,
            )
        }
        return UiCard(children, title = title)
    }

    fun totalsScreen(actor: Actor, data: TotalsData, notice: UiNotice? = null): UiScreen {
        val range = UiCard(
            listOf(
                UiForm(
                    "range",
                    listOf(UiField("from", "Desde", type = "date"), UiField("to", "Hasta", type = "date")),
                    mapOf("from" to data.from.toString(), "to" to data.to.toString()),
                    submit = UiButton("Ver", UiNavigate("totals", formId = "range"), style = "secondary"),
                    minFieldWidth = 200,
                ),
                UiText("Por departamento y por mes cuentan solicitudes aprobadas, compradas y completadas (por fecha de decision). Por estado cuenta las creadas en el rango. Sin conversion entre monedas.", UiTextStyle.Caption),
            ),
        )
        val body = listOf(
            range,
            totalsBlock("Por departamento", data.byDepartment, { it }, chart = true),
            totalsBlock("Por mes (ultimos 12)", data.byMonth, { it }, chart = true),
            totalsBlock("Por estado", data.byStatus, ::statusLabel, chart = false),
        )
        return frame(actor, "totals", title("totals"), notice, body)
    }

    // ---- Formularios de solicitud ----

    private fun departmentOptions(departments: List<Department>, current: String? = null): List<String> =
        listOf(NoDepartment) + (departments.filter { it.active }.map { it.name } + listOfNotNull(current)).distinct().filter { it != NoDepartment }

    private fun requestFields(departments: List<Department>, currentDepartment: String?, withFiles: Boolean): List<UiField> = buildList {
        add(UiField("title", "Titulo", required = true, placeholder = "Que se necesita comprar"))
        add(UiField("description", "Descripcion", type = "textarea"))
        add(UiField("department", "Departamento", type = "dropdown", options = departmentOptions(departments, currentDepartment)))
        add(UiField("amount", "Monto estimado", type = "number", help = "Puedes dejarlo vacio si vas a cotizar; para pedir aprobacion hace falta un monto."))
        add(UiField("currency", "Moneda", help = "Codigo de 3 letras, por ejemplo USD."))
        add(UiField("requiredDate", "Fecha requerida", type = "date"))
        add(UiField("priority", "Prioridad", type = "dropdown", options = Priority.entries.map { it.label }))
        if (withFiles) add(UiField("files", "Archivos de respaldo", type = "files", files = Files, help = "Facturas, proformas, cotizaciones..."))
    }

    fun valuesOf(request: PurchaseRequest): Map<String, String> = mapOf(
        "title" to request.title,
        "description" to request.description,
        "department" to (request.departmentName ?: NoDepartment),
        "amount" to (request.estimatedAmount?.toPlainString() ?: ""),
        "currency" to request.currency,
        "requiredDate" to (request.requiredDate?.toString() ?: ""),
        "priority" to request.priority.label,
    )

    fun newScreen(actor: Actor, data: NewData, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val values = submitted ?: mapOf("priority" to Priority.Medium.label, "currency" to data.defaultCurrency, "department" to NoDepartment)
        val body = listOf<UiNode>(
            UiCard(
                listOf(
                    UiForm(
                        "request", requestFields(data.departments, null, withFiles = true), values,
                        submit = UiButton("Guardar borrador", UiRequest("$UiRoute/new", params = mapOf("op" to "create"), formId = "request"), style = "secondary"),
                    ),
                    UiRow(listOf(UiButton("Enviar a aprobacion", UiRequest("$UiRoute/new", params = mapOf("op" to "create", "submit" to "true"), formId = "request")))),
                ),
            ),
        )
        return frame(actor, "new", title("new"), notice, body)
    }

    // ---- Detalle ----

    /** Texto del boton que lleva la solicitud de [from] a [to]. */
    fun transitionLabel(from: RequestStatus, to: RequestStatus): String = when (to) {
        RequestStatus.Quoting -> "Enviar a cotizacion"
        RequestStatus.PendingApproval -> "Enviar a aprobacion"
        RequestStatus.Approved -> "Aprobar"
        RequestStatus.Rejected -> "Rechazar"
        RequestStatus.Draft -> when (from) {
            RequestStatus.PendingApproval -> "Retirar"
            RequestStatus.Rejected -> "Reabrir"
            else -> "Volver a borrador"
        }
        RequestStatus.InProgress -> "Marcar comprada / en progreso"
        RequestStatus.Completed -> "Completar"
        RequestStatus.Cancelled -> "Cancelar solicitud"
    }

    private fun transitionButton(request: PurchaseRequest, to: RequestStatus) = UiButton(
        transitionLabel(request.status, to),
        UiRequest(
            "$UiRoute/request",
            params = mapOf("id" to request.id.toString(), "op" to "transition", "to" to to.id),
            confirm = if (to == RequestStatus.Cancelled) "Se cancela la solicitud y ya no se podra reabrir. Continuar?" else null,
        ),
        style = when (to) {
            RequestStatus.Cancelled -> "danger"
            RequestStatus.Draft -> "secondary"
            else -> "primary"
        },
    )

    private fun decisionText(request: PurchaseRequest): String? {
        val at = request.decidedAt ?: return null
        val who = request.decidedByName ?: "Alguien"
        val verb = if (request.status == RequestStatus.Rejected) "Rechazada" else "Aprobada"
        val amount = request.approvedAmount?.let { " Monto aprobado: ${money(it, request.approvedCurrency ?: request.approvalCurrency)}." }.orEmpty()
        val reason = request.decisionReason?.takeIf { it.isNotBlank() }?.let { " Motivo: $it" }.orEmpty()
        return "$verb por $who el ${time(at)}.$amount$reason"
    }

    private fun eventEntry(event: RequestEvent): UiTimelineEntry {
        val title = when (event.kind) {
            "created" -> "Solicitud creada"
            "edited" -> "Editada"
            "status" -> (event.fromStatus?.label?.let { "$it -> " }.orEmpty()) + (event.toStatus?.label ?: "")
            "quote" -> "Cotizacion"
            "attachment" -> "Archivos"
            else -> event.kind
        }
        return UiTimelineEntry(title, event.detail, "${event.actorName} - ${time(event.at)}", event.toStatus?.tone ?: UiTone.Neutral)
    }

    fun detailScreen(actor: Actor, data: DetailData, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val request = data.request
        val context = ComprasFlow.contextOf(request, data.approver)
        val targets = ComprasFlow.targets(request.status, actor, context)
        val canEdit = ComprasFlow.canEdit(request.status, actor, request.requesterId)
        val id = mapOf("id" to request.id.toString())

        val header = UiCard(
            title = requestTitle(request),
            children = buildList {
                add(UiRow(listOf(UiStatus(request.status.label, request.status.tone), UiStatus(request.priority.label, request.priority.tone))))
                add(
                    UiGrid(
                        listOf(
                            UiMetric("Monto a aprobar", money(request.approvalAmount, request.approvalCurrency), caption = if (request.approvedAmount != null) "Aprobado" else ""),
                            UiMetric("Departamento", request.departmentName ?: NoDepartment),
                            UiMetric("Solicitante", request.requesterName, caption = time(request.createdAt)),
                            UiMetric("Fecha requerida", request.requiredDate?.toString() ?: "Sin fecha"),
                        ),
                        minItemWidth = 180,
                    ),
                )
                if (request.description.isNotBlank()) add(UiText(request.description))
                decisionText(request)?.let { add(UiText(it, tone = if (request.status == RequestStatus.Rejected) UiTone.Danger else UiTone.Success)) }
            },
        )

        val actions = buildList<UiNode> {
            val plain = targets.filter { it != RequestStatus.Approved && it != RequestStatus.Rejected }
            if (plain.isNotEmpty()) add(UiRow(plain.map { transitionButton(request, it) }))
            val decisions = targets.filter { it == RequestStatus.Approved || it == RequestStatus.Rejected }
            if (decisions.isNotEmpty()) {
                add(
                    UiCard(
                        title = "Decision",
                        children = listOf(
                            UiForm("decision", listOf(UiField("reason", "Motivo o comentario", type = "textarea", help = "Obligatorio al rechazar.")), minFieldWidth = 320),
                            UiRow(
                                decisions.map { to ->
                                    UiButton(
                                        transitionLabel(request.status, to),
                                        UiRequest("$UiRoute/request", params = id + mapOf("op" to "transition", "to" to to.id), formId = "decision"),
                                        style = if (to == RequestStatus.Rejected) "danger" else "primary",
                                    )
                                },
                            ),
                        ),
                    ),
                )
            }
        }

        val editCard = if (canEdit) {
            val values = submitted?.takeIf { it.containsKey("title") } ?: valuesOf(request)
            listOf(
                UiCard(
                    title = "Datos de la solicitud",
                    children = listOf(
                        UiForm(
                            "request", requestFields(data.departments, request.departmentName, withFiles = false), values,
                            submit = UiButton("Guardar cambios", UiRequest("$UiRoute/request", params = id + ("op" to "save"), formId = "request")),
                        ),
                    ),
                ),
            )
        } else {
            emptyList()
        }

        val body = listOf<UiNode>(header) + actions + editCard + quotesCard(actor, data, canEdit, submitted) + filesCard(actor, data, context) +
            UiCard(listOf(UiTimeline(data.events.map(::eventEntry))), title = "Historial")
        return frame(actor, "requests", requestTitle(request), notice, body)
    }

    private fun quotesCard(actor: Actor, data: DetailData, canEdit: Boolean, submitted: Map<String, String>?): UiNode {
        val request = data.request
        val id = mapOf("id" to request.id.toString())
        val table = UiTable(
            columns = listOf(
                UiTableColumn("supplier", "Proveedor"), UiTableColumn("amount", "Monto"), UiTableColumn("notes", "Notas"), UiTableColumn("chosen", "Elegida"),
            ),
            rows = data.quotes.map {
                mapOf("supplier" to it.supplier, "amount" to money(it.amount, it.currency), "notes" to it.notes, "chosen" to if (it.selected) "Si" else "")
            },
            emptyText = "Sin cotizaciones (son opcionales).",
        )
        val rows = if (canEdit) {
            data.quotes.map { quote ->
                UiRow(
                    listOfNotNull(
                        UiText(quote.supplier + Dot + money(quote.amount, quote.currency)),
                        if (quote.selected) {
                            UiButton("Quitar eleccion", UiRequest("$UiRoute/request", params = id + mapOf("op" to "quoteSelect", "quote" to "none")), style = "secondary")
                        } else {
                            UiButton("Elegir", UiRequest("$UiRoute/request", params = id + mapOf("op" to "quoteSelect", "quote" to quote.id.toString())))
                        },
                        UiButton(
                            "Quitar",
                            UiRequest("$UiRoute/request", params = id + mapOf("op" to "quoteDelete", "quote" to quote.id.toString()), confirm = "Se quita la cotizacion de ${quote.supplier}. Continuar?"),
                            style = "danger",
                        ),
                    ),
                )
            }
        } else {
            emptyList()
        }
        val form = if (canEdit && data.quotes.size < ComprasRepository.MaxQuotesPerRequest) {
            val values = submitted?.takeIf { it.containsKey("supplier") } ?: mapOf("quoteCurrency" to request.currency)
            listOf(
                UiForm(
                    "quote",
                    listOf(
                        UiField("supplier", "Proveedor", required = true),
                        UiField("quoteAmount", "Monto", type = "number", required = true),
                        UiField("quoteCurrency", "Moneda", help = "Codigo de 3 letras."),
                        UiField("quoteNotes", "Notas", type = "textarea"),
                        UiField("quoteFiles", "Archivo de la cotizacion", type = "files", files = Files),
                    ),
                    values,
                    submit = UiButton("Agregar cotizacion", UiRequest("$UiRoute/request", params = id + ("op" to "quoteAdd"), formId = "quote"), style = "secondary"),
                ),
            )
        } else {
            emptyList()
        }
        return UiCard(listOf<UiNode>(table) + rows + form, title = "Cotizaciones")
    }

    private fun filesCard(actor: Actor, data: DetailData, context: FlowContext): List<UiNode> {
        val request = data.request
        val canAdd = ComprasFlow.canAddAttachment(request.status, actor, context)
        val canRemove = ComprasFlow.canRemoveAttachment(request.status, actor, request.requesterId)
        if (!canAdd && data.attachments.isEmpty()) return emptyList()
        val editable = canAdd || canRemove
        val form = UiForm(
            "files",
            listOf(
                UiField(
                    "files", "Archivos de respaldo", type = "files", readOnly = !editable, files = Files,
                    help = if (canRemove) "Facturas, proformas, cotizaciones..." else "Puedes agregar archivos; quitarlos solo en borrador o cotizacion.",
                ),
            ),
            mapOf("files" to UiFieldValues.files(data.attachments.map { it.ref })),
            submit = if (editable) UiButton("Guardar archivos", UiRequest("$UiRoute/request", params = mapOf("id" to request.id.toString(), "op" to "saveFiles"), formId = "files"), style = "secondary") else null,
        )
        return listOf(UiCard(listOf(form), title = "Archivos"))
    }
}
