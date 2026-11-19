package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.ui.UiBoard
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiOpenModule
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Constructores puros de las pantallas nativas: agrupación por fechas, botones según rol y estado, solo lectura y el contrato de la UI. */
class MantenimientoScreensTest {
    // Un miércoles.
    private val today = LocalDate.of(2026, 11, 18)
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val users = listOf(MantenimientoUser(me, "Yo", "yo@example.com"), MantenimientoUser(other, "Otro", "otro@example.com"))
    private val noAssets = AssetOptions(emptyList())

    private fun scope(execute: Boolean = false, manage: Boolean = false) = MantenimientoScope(me, execute, manage)

    private fun context(scope: MantenimientoScope, import: Boolean = false) =
        MantenimientoScreens.Context(scope, users, listOf(Property(UUID.randomUUID(), "Torre", 1, false)), today, importEnabled = import)

    private fun due(date: LocalDate?, status: OrderStatus = OrderStatus.Open) = MantenimientoFixtures.order(status = status, dueDate = date)

    private fun titles(buckets: List<Pair<String, List<Order>>>) = buckets.map { it.first }

    // ---- agrupación de Próximos ----

    @Test
    fun upcomingBucketsSplitByDateLimits() {
        val orders = listOf(
            due(today.minusDays(1)),
            due(today),
            due(today.plusDays(7)),
            due(today.plusDays(8)),
            due(today.plusDays(30)),
            due(today.plusDays(31)),
            due(null),
            due(today.minusDays(3), OrderStatus.Done),
            due(today, OrderStatus.Cancelled),
        )
        val buckets = MantenimientoScreens.upcomingBuckets(orders, today)
        assertEquals(listOf("Vencidas", "Próximos 7 días", "Próximos 30 días", "Más adelante", "Sin fecha"), titles(buckets))
        assertEquals(listOf(1, 2, 2, 1, 1), buckets.map { it.second.size })
    }

    @Test
    fun upcomingBucketsLeaveEmptyGroupsOut() {
        assertEquals(listOf("Próximos 7 días"), titles(MantenimientoScreens.upcomingBuckets(listOf(due(today)), today)))
        assertTrue(MantenimientoScreens.upcomingBuckets(emptyList(), today).isEmpty())
    }

    // ---- navegación ----

    @Test
    fun navigationShowsSectionsByRole() {
        fun ids(scope: MantenimientoScope, import: Boolean = false) = MantenimientoScreens.navigation(scope, "upcoming", import).items.map { it.id }
        assertEquals(listOf("upcoming", "mine", "assets", "plans"), ids(scope()))
        assertEquals(listOf("upcoming", "mine", "orders", "assets", "plans"), ids(scope(execute = true)))
        assertEquals(listOf("upcoming", "mine", "orders", "assets", "plans", "admin"), ids(scope(manage = true)))
        assertEquals("importar", ids(scope(), import = true).last())
    }

    // ---- botones de estado ----

    private fun labels(scope: MantenimientoScope, order: Order) = MantenimientoScreens.statusActions(scope, order).map { it.label }

    @Test
    fun workerSeesWorkButtonsOnAssignedOrdersOnly() {
        val worker = scope(execute = true)
        assertEquals(listOf("Empezar", "Poner en espera", "Completar"), labels(worker, due(null).copy(assigneeId = me)))
        assertEquals(listOf("Volver a abierta", "Poner en espera", "Completar"), labels(worker, due(null, OrderStatus.InProgress).copy(assigneeId = me)))
        // Sin responsable también puede trabajarla; con otro responsable no.
        assertTrue(labels(worker, due(null)).isNotEmpty())
        assertTrue(labels(worker, due(null).copy(assigneeId = other)).isEmpty())
        // Una orden completada solo la reabre quien gestiona.
        assertTrue(labels(worker, due(null, OrderStatus.Done).copy(assigneeId = me)).isEmpty())
    }

    @Test
    fun managerCancelsReopensAndDecidesProjects() {
        val manager = scope(manage = true)
        assertTrue("Cancelar orden" in labels(manager, due(null)))
        assertEquals(listOf("Reabrir"), labels(manager, due(null, OrderStatus.Done)))
        val project = MantenimientoFixtures.order(kind = OrderKind.Project, status = OrderStatus.Requested)
        assertEquals(listOf("Pasar a cotización", "Aprobar", "Cancelar orden"), labels(manager, project))
        assertTrue(MantenimientoScreens.canReject(manager, project))
        assertFalse(MantenimientoScreens.canReject(scope(execute = true), project))
        assertTrue(labels(scope(execute = true), project).isEmpty())
        // Un proyecto en cotización puede volver a pendiente.
        assertTrue("Volver a pendiente" in labels(manager, project.copy(status = OrderStatus.Quoting)))
        // Rechazar nunca es un botón directo: pide el motivo en la tarjeta de decisión.
        assertTrue(MantenimientoScreens.statusActions(manager, project).none { it.status == OrderStatus.Rejected })
    }

    @Test
    fun correctiveOrdersNeverOfferProjectStates() {
        val offered = MantenimientoScreens.statusActions(scope(manage = true), due(null)).map { it.status }
        assertTrue(offered.none { it in setOf(OrderStatus.Requested, OrderStatus.Quoting, OrderStatus.Rejected) })
    }

    // ---- botones de compra ----

    private fun detail(order: Order, purchase: Boolean) = MantenimientoScreens.OrderDetail(order, emptyList(), emptyList(), emptyList(), noAssets, null, purchase)

    @Test
    fun purchaseButtonsOnlyWhenComprasIsAvailableForTheUser() {
        val order = due(null).copy(assigneeId = me)
        assertTrue(MantenimientoScreens.purchaseButtons(scope(execute = true), detail(order, false)).isEmpty())
        val request = MantenimientoScreens.purchaseButtons(scope(execute = true), detail(order, true)).single()
        assertEquals("Solicitar compra", request.label)
        assertIs<UiRequest>(request.action)
        // Quien solo ve la orden no la solicita.
        assertTrue(MantenimientoScreens.purchaseButtons(scope(), detail(order.copy(requestedBy = me, assigneeId = null), true)).isEmpty())
    }

    @Test
    fun linkedRequestShowsViewButtonOnlyWhenAvailable() {
        val requestId = UUID.randomUUID()
        val order = due(null).copy(requestedBy = me, purchaseRequestId = requestId, purchaseFolio = "SC-0001")
        assertTrue(MantenimientoScreens.purchaseButtons(scope(), detail(order, false)).isEmpty())
        val view = MantenimientoScreens.purchaseButtons(scope(), detail(order, true)).single()
        assertEquals("Ver solicitud de compra", view.label)
        val action = assertIs<UiOpenModule>(view.action)
        assertEquals("compras", action.moduleId)
        assertEquals(requestId.toString(), action.params["id"])
    }

    // ---- pantalla de la orden: solo lectura y botones ----

    private fun orderForm(screen: UiScreen): UiForm =
        screen.body.filterIsInstance<UiCard>().flatMap { it.children }.filterIsInstance<UiForm>().first { it.id == "order" }

    private fun allButtons(nodes: List<UiNode>): List<UiButton> = nodes.flatMap { node ->
        when (node) {
            is UiButton -> listOf(node)
            is UiRow -> allButtons(node.children)
            is UiCard -> allButtons(node.children)
            else -> emptyList()
        }
    }

    @Test
    fun managerEditsEverythingAndSeesFilesInTheForm() {
        val screen = MantenimientoScreens.order(context(scope(manage = true)), detail(due(null).copy(assigneeId = other), false))
        val form = orderForm(screen)
        assertNotNull(form.submit)
        assertFalse(form.fields.first { it.key == "title" }.readOnly)
        assertFalse(form.fields.first { it.key == "assignee" }.readOnly)
        assertTrue(form.fields.first { it.key == "kind" }.readOnly)
        assertTrue(form.fields.any { it.key == "files" })
    }

    @Test
    fun workerOnlyEditsCostAndResolution() {
        val screen = MantenimientoScreens.order(context(scope(execute = true)), detail(due(null).copy(assigneeId = me), false))
        val form = orderForm(screen)
        assertNotNull(form.submit)
        assertTrue(form.fields.first { it.key == "title" }.readOnly)
        assertTrue(form.fields.first { it.key == "assignee" }.readOnly)
        assertFalse(form.fields.first { it.key == "cost" }.readOnly)
        assertFalse(form.fields.first { it.key == "resolution" }.readOnly)
        // Los adjuntos van en un formulario aparte.
        assertTrue(form.fields.none { it.key == "files" })
        assertTrue(screen.body.filterIsInstance<UiCard>().any { card -> card.children.filterIsInstance<UiForm>().any { it.id == "attachments" } })
    }

    @Test
    fun viewerWhoReportedCanOnlyAttachAndNote() {
        val order = due(null).copy(requestedBy = me)
        val screen = MantenimientoScreens.order(context(scope()), detail(order, false))
        val form = orderForm(screen)
        assertNull(form.submit)
        assertTrue(form.fields.all { it.readOnly })
        assertTrue(allButtons(screen.body).none { it.label == "Tomar orden" || it.label == "Completar" })
        val forms = screen.body.filterIsInstance<UiCard>().flatMap { it.children }.filterIsInstance<UiForm>().map { it.id }
        assertTrue("attachments" in forms && "note" in forms)
        assertTrue("check" !in forms)
    }

    @Test
    fun costAndResolutionOnlyShowWhileWorkingOrDone() {
        val manager = context(scope(manage = true))
        fun keys(order: Order) = orderForm(MantenimientoScreens.order(manager, detail(order, false))).fields.map { it.key }
        assertTrue("cost" in keys(due(null)))
        assertTrue("cost" in keys(due(null, OrderStatus.Done)))
        val project = MantenimientoFixtures.order(kind = OrderKind.Project, status = OrderStatus.Requested)
        assertTrue("cost" !in keys(project))
        assertTrue("quote" in keys(project))
    }

    @Test
    fun takeButtonOnlyForUnassignedWorkableOrders() {
        val worker = context(scope(execute = true))
        fun hasTake(order: Order) = allButtons(MantenimientoScreens.order(worker, detail(order, false)).body).any { it.label == "Tomar orden" }
        assertTrue(hasTake(due(null)))
        assertFalse(hasTake(due(null).copy(assigneeId = me)))
        assertFalse(hasTake(due(null, OrderStatus.Done)))
    }

    @Test
    fun completeButtonCarriesTheFormOnlyWhenThereIsAForm() {
        fun complete(scope: MantenimientoScope, order: Order) =
            allButtons(MantenimientoScreens.order(context(scope), detail(order, false)).body).first { it.label == "Completar" }.action
        assertEquals("order", complete(scope(execute = true), due(null).copy(assigneeId = me)).formId)
    }

    @Test
    fun rejectionCardOnlyForManagersDecidingProjects() {
        val project = MantenimientoFixtures.order(kind = OrderKind.Project, status = OrderStatus.Requested)
        fun hasDecision(scope: MantenimientoScope) = MantenimientoScreens.order(context(scope), detail(project.copy(requestedBy = me), false)).body
            .filterIsInstance<UiCard>().any { it.title == "Decisión" }
        assertTrue(hasDecision(scope(manage = true)))
        assertFalse(hasDecision(scope(execute = true)))
        assertFalse(hasDecision(scope()))
    }

    // ---- tablero ----

    @Test
    fun boardHidesEmptyDecisionColumns() {
        val empty = OrderStatus.entries.associateWith { emptyList<Order>() }
        val shown = MantenimientoScreens.boardStatuses(empty)
        assertEquals(listOf(OrderStatus.Open, OrderStatus.InProgress, OrderStatus.OnHold, OrderStatus.Done, OrderStatus.Cancelled), shown)
        val withProject = empty + (OrderStatus.Requested to listOf(MantenimientoFixtures.order(kind = OrderKind.Project, status = OrderStatus.Requested)))
        assertTrue(OrderStatus.Requested in MantenimientoScreens.boardStatuses(withProject))
    }

    @Test
    fun boardMovesThroughTheScreenRouteKeepingFilters() {
        val manager = context(scope(manage = true))
        val data = MantenimientoScreens.OrdersData(columns = OrderStatus.entries.associateWith { emptyList<Order>() })
        val screen = MantenimientoScreens.orders(manager, data, mapOf("view" to "board", "q" to "fuga", "status" to "Abierta"))
        val board = screen.body.filterIsInstance<UiBoard>().single()
        val move = assertNotNull(board.move)
        assertEquals("move", move.params["op"])
        assertEquals("fuga", move.params["q"])
        // El estado no se conserva: cada columna es un estado.
        assertNull(move.params["status"])
    }

    // ---- avería y proyecto nuevos ----

    @Test
    fun newOrderFormsDifferByKindAndRole() {
        fun keys(kind: OrderKind, scope: MantenimientoScope) = MantenimientoScreens.newOrderFields(context(scope), kind, noAssets).map { it.key }
        assertTrue("assignee" !in keys(OrderKind.Corrective, scope()))
        assertTrue("assignee" in keys(OrderKind.Corrective, scope(manage = true)))
        assertTrue("due" in keys(OrderKind.Corrective, scope(manage = true)))
        assertTrue("department" in keys(OrderKind.Project, scope()) && "quote" in keys(OrderKind.Project, scope()))
        assertEquals(OrderKind.Corrective, MantenimientoScreens.creatableKind("preventive"))
        assertEquals(OrderKind.Project, MantenimientoScreens.creatableKind("project"))
    }

    // ---- ajustes ----

    @Test
    fun adminOffersTareasFormOnlyWhenEditable() {
        val manager = context(scope(manage = true))
        val settings = MantenimientoSettings(null, null, 1)
        fun forms(choice: TareasChoice) = MantenimientoAdminScreens.admin(manager, emptyList(), settings, choice).body
            .filterIsInstance<UiCard>().flatMap { it.children }.filterIsInstance<UiForm>().map { it.id }
        assertTrue("tareas" in forms(TareasChoice(listOf(UUID.randomUUID() to "Mantenimiento"), true)))
        assertTrue("tareas" !in forms(TareasChoice(emptyList(), false, "no")))
        assertTrue("settings" in forms(TareasChoice(emptyList(), false)))
    }

    @Test
    fun tareasChoiceLabelsAreUnique() {
        val choice = TareasChoice(listOf(UUID.randomUUID() to "Equipo", UUID.randomUUID() to "equipo"), true)
        assertEquals(2, choice.labels.map { it.second.lowercase() }.toSet().size)
    }

    // ---- contrato de la UI ----

    @Test
    fun screensSerializeAndNavigateToKnownScreens() {
        val manager = context(scope(manage = true), import = true)
        val order = due(today).copy(assigneeId = me)
        val screens = listOf(
            MantenimientoScreens.upcoming(manager, OrderMetrics(1, 0, 0, 0), listOf(order), emptyList(), null),
            MantenimientoScreens.mine(manager, listOf(order)),
            MantenimientoScreens.order(manager, detail(order, true)),
            MantenimientoScreens.newOrder(manager, OrderKind.Project, noAssets),
            MantenimientoScreens.assets(manager, emptyList(), null, ""),
            MantenimientoScreens.asset(manager, null, emptyList(), emptyList()),
            MantenimientoScreens.plans(manager, emptyList()),
            MantenimientoScreens.plan(manager, null, emptyList(), emptyList(), noAssets),
        )
        screens.forEach { screen ->
            val json = UiJson.encodeToString(UiScreen.serializer(), screen)
            assertEquals(screen.title, UiJson.decodeFromString(UiScreen.serializer(), json).title)
        }
        val known = setOf("upcoming", "mine", "orders", "order", "new-order", "assets", "asset", "plans", "plan", "admin", "importar")
        MantenimientoScreens.navigation(scope(manage = true), "upcoming", true).items.forEach { assertTrue((it.action as UiNavigate).screen in known) }
    }
}
