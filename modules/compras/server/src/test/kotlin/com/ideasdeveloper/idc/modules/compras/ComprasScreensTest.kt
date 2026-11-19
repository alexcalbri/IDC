package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.ui.UiBoard
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiColumn
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComprasScreensTest {
    private val V = ComprasModule.View

    private fun actor(vararg granted: String, owner: Boolean = false, id: UUID = UUID.randomUUID()) =
        ComprasTestSupport.actor(id, *granted, owner = owner)

    private fun walk(nodes: List<UiNode>): List<UiNode> = nodes.flatMap { node ->
        listOf(node) + when (node) {
            is UiColumn -> walk(node.children)
            is UiRow -> walk(node.children)
            is UiGrid -> walk(node.children)
            is UiCard -> walk(node.children + node.actions)
            is UiForm -> listOfNotNull(node.submit)
            else -> emptyList()
        }
    }

    private fun buttons(screen: UiScreen): List<String> = walk(screen.body).filterIsInstance<UiButton>().map { it.label }

    private fun navLabels(screen: UiScreen): List<String> = screen.navigation!!.items.map { it.label }

    private fun roundTrip(screen: UiScreen) {
        val json = UiJson.encodeToString(UiScreen.serializer(), screen)
        val back = UiJson.decodeFromString(UiScreen.serializer(), json)
        assertEquals(screen.title, back.title)
        assertEquals(screen.body.size, back.body.size)
    }

    private val emptyHome = ComprasScreens.HomeData(0, 0, 0, null, emptyList())

    @Test
    fun menuDependsOnPermissions() {
        val plain = actor(V)
        assertEquals(listOf("Inicio", "Solicitudes", "Tablero", "Altas y criticas"), navLabels(ComprasScreens.homeScreen(plain, emptyHome)))
        val approver = actor(V, ComprasModule.Approve)
        assertTrue("Por aprobar" in navLabels(ComprasScreens.homeScreen(approver, emptyHome)))
        val viewAll = actor(V, ComprasModule.ViewAll)
        val labels = navLabels(ComprasScreens.homeScreen(viewAll, emptyHome))
        assertTrue("Totales" in labels)
        assertFalse("Ajustes" in labels)
        val manager = actor(V, ComprasModule.Manage)
        assertTrue("Ajustes" in navLabels(ComprasScreens.homeScreen(manager, emptyHome)))
        assertTrue("Totales" in navLabels(ComprasScreens.homeScreen(manager, emptyHome)))
        assertTrue(navLabels(ComprasScreens.homeScreen(actor(owner = true), emptyHome)).containsAll(listOf("Totales", "Ajustes", "Por aprobar")))
    }

    @Test
    fun canOpenChecksExactPermission() {
        val plain = actor(V)
        assertFalse(ComprasScreens.canOpen("totals", plain::can))
        assertFalse(ComprasScreens.canOpen("settings", plain::can))
        assertFalse(ComprasScreens.canOpen("new", plain::can))
        assertFalse(ComprasScreens.canOpen("approvals", plain::can))
        assertTrue(ComprasScreens.canOpen("requests", plain::can))
        assertFalse(ComprasScreens.canOpen("requests", actor()::can))
        assertTrue(ComprasScreens.canOpen("new", actor(V, ComprasModule.Create)::can))
    }

    @Test
    fun newRequestButtonNeedsCreate() {
        assertFalse("Nueva solicitud" in buttons(ComprasScreens.homeScreen(actor(V), emptyHome)))
        assertTrue("Nueva solicitud" in buttons(ComprasScreens.homeScreen(actor(V, ComprasModule.Create), emptyHome)))
        val data = ComprasScreens.RequestsData(emptyMap(), emptyList(), emptyList(), 0, 50)
        assertFalse("Nueva solicitud" in buttons(ComprasScreens.requestsScreen(actor(V), data)))
        assertTrue("Nueva solicitud" in buttons(ComprasScreens.requestsScreen(actor(V, ComprasModule.Create), data)))
    }

    @Test
    fun boardHasOneColumnPerStatusWithRejectedLast() {
        val columns = ComprasScreens.BoardStatuses.map { ComprasScreens.BoardColumnData(it, emptyList(), 0) }
        val screen = ComprasScreens.boardScreen(actor(V, ComprasModule.Create), columns)
        val board = walk(screen.body).filterIsInstance<UiBoard>().single()
        assertEquals(
            listOf("draft", "quoting", "pending_approval", "approved", "in_progress", "completed", "rejected"),
            board.columns.map { it.id },
        )
        assertNotNull(board.move)
        assertNull(ComprasScreens.boardScreen(actor(V), columns).body.filterIsInstance<UiBoard>().single().move)
    }

    @Test
    fun boardCapsCardsAndShowsCount() {
        val requester = UUID.randomUUID()
        val cards = (1..60).map { ComprasTestSupport.request(requesterId = requester) }
        val screen = ComprasScreens.boardScreen(actor(V, ComprasModule.ViewAll), listOf(ComprasScreens.BoardColumnData(RequestStatus.Draft, cards, 60)))
        val column = screen.body.filterIsInstance<UiBoard>().single().columns.single()
        assertEquals(50, column.cards.size)
        assertEquals("60 solicitudes", column.caption)
    }

    private fun detail(request: PurchaseRequest, actor: Actor, approver: Approver? = null) = ComprasScreens.detailScreen(
        actor,
        ComprasScreens.DetailData(request, approver, emptyList(), emptyList(), emptyList(), emptyList()),
    )

    @Test
    fun detailShowsOnlyValidButtons() {
        val me = UUID.randomUUID()
        val mine = actor(V, ComprasModule.Create, id = me)
        val draft = buttons(detail(ComprasTestSupport.request(requesterId = me, status = RequestStatus.Draft), mine))
        assertTrue("Enviar a cotizacion" in draft)
        assertTrue("Enviar a aprobacion" in draft)
        assertTrue("Cancelar solicitud" in draft)
        assertFalse("Aprobar" in draft)
        assertFalse("Completar" in draft)

        val someoneElse = actor(V, ComprasModule.Create)
        assertTrue(buttons(detail(ComprasTestSupport.request(status = RequestStatus.Draft), someoneElse)).none { it == "Enviar a aprobacion" })
    }

    @Test
    fun approveAndRejectDependOnLimit() {
        val boss = UUID.randomUUID()
        val approver = ComprasTestSupport.approver(boss, "1000")
        val pending = ComprasTestSupport.request(status = RequestStatus.PendingApproval, amount = "500.00")
        val inside = buttons(detail(pending, actor(V, ComprasModule.Approve, id = boss), approver))
        assertTrue("Aprobar" in inside)
        assertTrue("Rechazar" in inside)
        val big = ComprasTestSupport.request(status = RequestStatus.PendingApproval, amount = "5000.00")
        val outside = buttons(detail(big, actor(V, ComprasModule.Approve, id = boss), approver))
        assertFalse("Aprobar" in outside)
        assertTrue("Rechazar" in outside)
        assertFalse("Aprobar" in buttons(detail(pending, actor(V, ComprasModule.Create), null)))
    }

    @Test
    fun editFormOnlyWhileEditable() {
        val me = UUID.randomUUID()
        val mine = actor(V, ComprasModule.Create, id = me)
        assertTrue("Guardar cambios" in buttons(detail(ComprasTestSupport.request(requesterId = me, status = RequestStatus.Quoting), mine)))
        assertFalse("Guardar cambios" in buttons(detail(ComprasTestSupport.request(requesterId = me, status = RequestStatus.Approved), mine)))
    }

    @Test
    fun transitionLabels() {
        assertEquals("Retirar", ComprasScreens.transitionLabel(RequestStatus.PendingApproval, RequestStatus.Draft))
        assertEquals("Reabrir", ComprasScreens.transitionLabel(RequestStatus.Rejected, RequestStatus.Draft))
        assertEquals("Completar", ComprasScreens.transitionLabel(RequestStatus.InProgress, RequestStatus.Completed))
    }

    @Test
    fun highListsCriticalFirstAndSkipsClosed() {
        val a = ComprasTestSupport.request(status = RequestStatus.Approved).copy(priority = Priority.High, requiredDate = java.time.LocalDate.of(2026, 1, 1))
        val b = ComprasTestSupport.request(status = RequestStatus.Draft).copy(priority = Priority.Critical, requiredDate = java.time.LocalDate.of(2026, 6, 1))
        val closed = ComprasTestSupport.request(status = RequestStatus.Completed).copy(priority = Priority.Critical)
        assertEquals(listOf(b.id, a.id), ComprasScreens.sortHigh(listOf(a, closed, b)).map { it.id })
    }

    @Test
    fun totalsSeparatedByCurrency() {
        val rows = listOf(
            TotalRow("Hotel", "USD", BigDecimal("1200.50"), 2),
            TotalRow("Hotel", "PAB", BigDecimal("10.00"), 1),
        )
        val data = ComprasScreens.TotalsData(java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31), rows, rows, listOf(TotalRow("approved", "USD", BigDecimal("5"), 1)))
        val screen = ComprasScreens.totalsScreen(actor(V, ComprasModule.ViewAll), data)
        roundTrip(screen)
        val sums = ComprasScreens.sumByCurrency(rows)
        assertEquals(listOf("PAB", "USD"), sums.map { it.currency })
        assertEquals(BigDecimal("1200.50"), sums.last().total)
    }

    @Test
    fun everyScreenSerializes() {
        val me = UUID.randomUUID()
        val all = actor(V, ComprasModule.Create, ComprasModule.ViewAll, ComprasModule.Approve, ComprasModule.Manage, id = me)
        val request = ComprasTestSupport.request(requesterId = me, status = RequestStatus.Quoting)
        val quote = Quote(UUID.randomUUID(), request.id, "Proveedor", BigDecimal("100.00"), "USD", "", false, me, "Persona", Instant.EPOCH)
        val event = RequestEvent(1, request.id, Instant.EPOCH, me, "Persona", "status", RequestStatus.Draft, RequestStatus.Quoting, "")
        val departments = listOf(Department(UUID.randomUUID(), "Hotel", true, 0))
        roundTrip(ComprasScreens.homeScreen(all, ComprasScreens.HomeData(1, 2, 3, listOf(TotalRow("", "USD", BigDecimal("10"), 1)), listOf(request))))
        roundTrip(ComprasScreens.requestsScreen(all, ComprasScreens.RequestsData(mapOf("q" to "x"), departments, listOf(request), 120, 50)))
        roundTrip(ComprasScreens.boardScreen(all, listOf(ComprasScreens.BoardColumnData(RequestStatus.Draft, listOf(request), 1))))
        roundTrip(ComprasScreens.approvalsScreen(all, ComprasScreens.ApprovalsData(listOf(request), null)))
        roundTrip(ComprasScreens.highScreen(all, listOf(request)))
        roundTrip(ComprasScreens.newScreen(all, ComprasScreens.NewData(departments, "USD")))
        roundTrip(ComprasScreens.detailScreen(all, ComprasScreens.DetailData(request, null, listOf(quote), emptyList(), listOf(event), departments)))
        val user = CompanyUser(UUID.randomUUID(), "Ana", "ana@x.com", true)
        roundTrip(
            ComprasAdminScreens.settingsScreen(
                all,
                ComprasAdminScreens.SettingsData("USD", departments, listOf(ComprasTestSupport.approver(user.id, null)), listOf(user), setOf(user.id)),
            ),
        )
    }

    @Test
    fun userLabelsAreUnique() {
        val a = CompanyUser(UUID.randomUUID(), "Ana", "", true)
        val b = CompanyUser(UUID.randomUUID(), "Ana", "", true)
        val labels = ComprasAdminScreens.userLabels(listOf(a, b))
        assertEquals(2, labels.size)
        assertEquals(a, labels["Ana"])
        assertEquals(b, labels["Ana [2]"])
    }

    @Test
    fun filterAndDraftParsing() {
        val dept = Department(UUID.randomUUID(), "Hotel", true, 0)
        val filter = ComprasScreens.filterOf(mapOf("fStatus" to "Borrador", "fPriority" to "Alta", "fDepartment" to "Hotel", "mine" to "true", "q" to " sabanas "), listOf(dept))
        assertEquals(RequestStatus.Draft, filter.status)
        assertEquals(Priority.High, filter.priority)
        assertEquals(dept.id, filter.departmentId)
        assertTrue(filter.mine)
        assertEquals("sabanas", filter.text)
        assertNull(ComprasScreens.filterOf(mapOf("fStatus" to "Todos"), emptyList()).status)

        val draft = ComprasActions.draftOf(
            mapOf("title" to "Sabanas", "department" to "Hotel", "amount" to "1,234.50", "currency" to "", "priority" to "Critica", "requiredDate" to "2026-12-01"),
            listOf(dept), "USD",
        )
        assertEquals(dept.id, draft.departmentId)
        assertEquals(BigDecimal("1234.50"), draft.estimatedAmount)
        assertEquals("USD", draft.currency)
        assertEquals(Priority.Critical, draft.priority)
        assertFailsValidation { ComprasActions.draftOf(mapOf("title" to "x", "department" to "Nada"), listOf(dept), "USD") }
        assertFailsValidation { ComprasActions.draftOf(mapOf("title" to "x", "requiredDate" to "manana"), listOf(dept), "USD") }
        assertFailsValidation { ComprasActions.draftOf(mapOf("title" to "x", "amount" to "-5"), listOf(dept), "USD") }
    }

    private fun assertFailsValidation(block: () -> Unit) {
        try {
            block()
        } catch (_: ComprasValidationException) {
            return
        }
        throw AssertionError("Se esperaba ComprasValidationException")
    }

    @Test
    fun operationPermissions() {
        fun can(vararg granted: String): (String) -> Boolean = { it in granted }
        fun p(op: String, vararg extra: Pair<String, String>) = mapOf("op" to op) + extra
        assertFalse(ComprasActions.permitted("new", p("create"), can(V)))
        assertTrue(ComprasActions.permitted("new", p("create"), can(V, ComprasModule.Create)))
        assertFalse(ComprasActions.permitted("new", p("create"), can(ComprasModule.Create)))
        assertFalse(ComprasActions.permitted("request", p("transition", "to" to "approved"), can(V, ComprasModule.Create, ComprasModule.Manage)))
        assertTrue(ComprasActions.permitted("request", p("transition", "to" to "approved"), can(V, ComprasModule.Approve)))
        assertTrue(ComprasActions.permitted("request", p("transition", "to" to "quoting"), can(V, ComprasModule.Create)))
        assertFalse(ComprasActions.permitted("request", p("transition", "to" to "quoting"), can(V)))
        assertFalse(ComprasActions.permitted("request", p("transition", "to" to "nada"), can(V, ComprasModule.Manage)))
        assertFalse(ComprasActions.permitted("settings", p("approverSave"), can(V, ComprasModule.Create, ComprasModule.Approve)))
        assertTrue(ComprasActions.permitted("settings", p("approverSave"), can(V, ComprasModule.Manage)))
        assertFalse(ComprasActions.permitted("approvals", p("approve"), can(V, ComprasModule.Manage)))
        assertFalse(ComprasActions.permitted("settings", p("inventada"), can(V, ComprasModule.Manage)))
    }

    @Test
    fun everyOperationNeedsItsExactPermission() {
        fun allowed(screen: String, op: String, to: String? = null, vararg granted: String): Boolean {
            val params = mapOf("op" to op) + listOfNotNull(to?.let { "to" to it })
            return ComprasActions.permitted(screen, params) { it in granted }
        }
        val v = ComprasModule.View
        // Ninguna operacion pasa sin view.
        listOf("new" to "create", "request" to "save", "request" to "saveFiles", "approvals" to "approve", "settings" to "currency", "board" to "move").forEach { (screen, op) ->
            assertFalse(allowed(screen, op, null, ComprasModule.Create, ComprasModule.Manage, ComprasModule.Approve), "$screen:$op sin view")
        }
        // create
        assertTrue(allowed("new", "create", null, v, ComprasModule.Create))
        assertFalse(allowed("new", "create", null, v, ComprasModule.Manage))
        // create o manage
        listOf("save", "quoteAdd", "quoteSelect", "quoteDelete", "saveFiles").forEach { op ->
            assertTrue(allowed("request", op, null, v, ComprasModule.Create), op)
            assertTrue(allowed("request", op, null, v, ComprasModule.Manage), op)
            assertFalse(allowed("request", op, null, v, ComprasModule.Approve, ComprasModule.ViewAll), op)
        }
        // approve
        listOf("approve", "reject").forEach { op ->
            assertTrue(allowed("approvals", op, null, v, ComprasModule.Approve), op)
            assertFalse(allowed("approvals", op, null, v, ComprasModule.Create, ComprasModule.Manage), op)
        }
        // manage
        listOf("currency", "deptSave", "deptActive", "approverSave", "approverRemove").forEach { op ->
            assertTrue(allowed("settings", op, null, v, ComprasModule.Manage), op)
            assertFalse(allowed("settings", op, null, v, ComprasModule.Create, ComprasModule.Approve, ComprasModule.ViewAll), op)
        }
        // tablero y transiciones
        assertTrue(allowed("board", "move", null, v, ComprasModule.Create))
        assertFalse(allowed("board", "move", null, v, ComprasModule.ViewAll))
        assertTrue(allowed("request", "transition", "rejected", v, ComprasModule.Approve))
        assertFalse(allowed("request", "transition", "rejected", v, ComprasModule.Manage))
        assertTrue(allowed("request", "transition", "completed", v, ComprasModule.Manage))
        assertFalse(allowed("request", "transition", "completed", v, ComprasModule.ViewAll))
        // desconocidas
        assertFalse(allowed("request", "borrarTodo", null, v, ComprasModule.Create, ComprasModule.Manage, ComprasModule.Approve))
        assertFalse(allowed("home", "save", null, v, ComprasModule.Create, ComprasModule.Manage, ComprasModule.Approve))
        assertFalse(ComprasActions.permitted("settings", emptyMap()) { true })
    }

    @Test
    fun importNavAndOperationPermissions() {
        val v = ComprasModule.View
        fun labels(vararg granted: String) = navLabels(ComprasScreens.homeScreen(actor(*granted), emptyHome))
        assertFalse("Importar" in labels(v))
        assertFalse("Importar" in labels(v, ComprasModule.Create, ComprasModule.Approve, ComprasModule.ViewAll))
        assertTrue("Importar" in labels(v, ComprasModule.Import))
        // Solo compras.import abre Importar; manage no basta.
        assertFalse("Importar" in labels(v, ComprasModule.Manage))
        assertFalse(ComprasScreens.canOpen("import", actor(v, ComprasModule.Manage)::can))
        assertTrue(ComprasScreens.canOpen("import", actor(v, ComprasModule.Import)::can))
        assertFalse(ComprasScreens.canOpen("import", actor(ComprasModule.Import)::can))

        // La operacion "<fuente>:<op>" pide compras.import (manage solo abre la pantalla).
        fun allowed(op: String?, vararg granted: String) =
            ComprasActions.permitted("import", listOfNotNull(op?.let { "op" to it }).toMap()) { it in granted }
        assertTrue(allowed("monday:token", v, ComprasModule.Import))
        assertFalse(allowed("monday:token", v, ComprasModule.Manage, ComprasModule.Create))
        assertFalse(allowed("monday:token", ComprasModule.Import))
        assertFalse(allowed("token", v, ComprasModule.Import))
        assertFalse(allowed(null, v, ComprasModule.Import))
    }

    private class FakeSource(override val id: String) : ComprasImportSource {
        override val label = id
        override val extensionId = "compras_$id"
        override fun screen(ctx: ComprasImportContext, params: Map<String, String>): List<com.ideasdeveloper.idc.ui.UiNode> = emptyList()
        override fun action(ctx: ComprasImportContext, op: String, values: Map<String, String>): com.ideasdeveloper.idc.ui.UiNotice? = null
        override fun runPending(ctx: ComprasImportContext, budget: Int) = Unit
    }

    @Test
    fun onlyEnabledSourcesAreUsedAndAFailureCountsAsDisabled() {
        val sources = listOf(FakeSource("a"), FakeSource("b"), FakeSource("c"))
        val active = ComprasActions.activeSources(sources) {
            when (it.id) {
                "a" -> true
                "b" -> false
                else -> throw IllegalStateException("fallo")
            }
        }
        assertEquals(listOf("a"), active.map { it.id })
        assertTrue(ComprasActions.activeSources(sources) { false }.isEmpty())
    }

    @Test
    fun importScreenListsSourcesOrExplainsHowToInstall() {
        val manager = actor(V, ComprasModule.Import)
        val empty = ComprasScreens.importScreen(manager, emptyList())
        roundTrip(empty)
        assertEquals("Importar", empty.title)
        assertTrue(empty.body.size >= 2)
        val withSource = ComprasScreens.importScreen(manager, listOf(ComprasScreens.ImportCard("monday", "Monday", listOf(com.ideasdeveloper.idc.ui.UiText("hola")))))
        roundTrip(withSource)
        assertTrue(walk(withSource.body).any { it is UiCard })
    }

    @Test
    fun downloadContentTypeAllowList() {
        assertEquals("application/pdf", ComprasDownloads.safeType("application/pdf"))
        assertEquals("image/png", ComprasDownloads.safeType("IMAGE/PNG; charset=x"))
        assertEquals("text/csv", ComprasDownloads.safeType("text/csv"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ComprasDownloads.safeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals("application/msword", ComprasDownloads.safeType("application/msword"))
        listOf("image/svg+xml", "text/html", "application/xhtml+xml", "text/javascript", "application/vnd.openxmlformats-officedocument.", "", "basura").forEach {
            assertEquals("application/octet-stream", ComprasDownloads.safeType(it), it)
        }
        assertEquals("application/octet-stream", ComprasDownloads.safeType(null))
    }

    @Test
    fun saveFilesNeverDeletesOnBadInput() {
        assertFailsValidation { ComprasActions.strictFileIds(emptyMap()) }
        assertFailsValidation { ComprasActions.strictFileIds(mapOf("files" to "no es json")) }
        assertFailsValidation { ComprasActions.strictFileIds(mapOf("files" to "[{\"id\":\"no-uuid\",\"name\":\"a\"}]")) }
        assertEquals(emptyList(), ComprasActions.strictFileIds(mapOf("files" to "")))
        val id = UUID.randomUUID()
        assertEquals(listOf(id), ComprasActions.strictFileIds(mapOf("files" to "[{\"id\":\"$id\",\"name\":\"a.pdf\"}]")))
    }

    @Test
    fun pageLimitIsClamped() {
        assertEquals(50, ComprasScreens.pageLimit(emptyMap()))
        assertEquals(100, ComprasScreens.pageLimit(mapOf("limit" to "100")))
        assertEquals(200, ComprasScreens.pageLimit(mapOf("limit" to "99999")))
        assertEquals(50, ComprasScreens.pageLimit(mapOf("limit" to "x")))
    }
}
