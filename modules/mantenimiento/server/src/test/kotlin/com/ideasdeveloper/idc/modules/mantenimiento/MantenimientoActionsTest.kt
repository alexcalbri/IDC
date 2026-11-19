package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lectura pura de lo que envían los formularios, reglas de quién puede qué y descargas seguras. */
class MantenimientoActionsTest {
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val users = listOf(MantenimientoUser(me, "Yo", null), MantenimientoUser(other, "Otro", null))
    private val tower = Property(UUID.randomUUID(), "Torre", 1, false)
    private val asset = Asset(UUID.randomUUID(), tower.id, "Torre", "Elevador", "Sótano", "", "", false, Instant.EPOCH, Instant.EPOCH)
    private val lookups = Lookups(users, listOf(tower), AssetOptions(listOf(asset)))

    @Test
    fun formValuesKeepStringsAndEncodeArrays() {
        val values = MantenimientoActions.formValues("""{"title":"Fuga","people":["A","B"],"n":3,"x":null}""")
        assertEquals("Fuga", values["title"])
        assertEquals(listOf("A", "B"), UiFieldValues.options(values.getValue("people")))
        assertFailsWith<MantenimientoValidationException> { MantenimientoActions.formValues("no es json") }
        assertFailsWith<MantenimientoValidationException> { MantenimientoActions.formValues("x".repeat(1_000_001)) }
    }

    @Test
    fun parsesDatesAmountsAndCurrency() {
        assertNull(MantenimientoActions.date(" "))
        assertEquals(LocalDate.of(2026, 11, 20), MantenimientoActions.date("2026-11-20"))
        assertFailsWith<MantenimientoValidationException> { MantenimientoActions.date("20/11/2026") }
        assertEquals(BigDecimal("12.50"), MantenimientoActions.amount("12,50", "El costo"))
        assertNull(MantenimientoActions.amount("", "El costo"))
        assertFailsWith<MantenimientoValidationException> { MantenimientoActions.amount("doce", "El costo") }
        assertEquals("EUR", MantenimientoActions.currency(" eur "))
        assertEquals("USD", MantenimientoActions.currency(""))
    }

    @Test
    fun peoplePropertiesAndAssetsAcceptOnlyTheListedOnes() {
        assertEquals(me, MantenimientoActions.userId("yo", users))
        assertNull(MantenimientoActions.userId(MantenimientoActions.NoAssignee, users))
        assertFailsWith<MantenimientoValidationException> { MantenimientoActions.userId("Intruso", users) }
        // Un responsable retirado se conserva si no se toca el campo.
        assertEquals(other, MantenimientoActions.userId(MantenimientoActions.RetiredUser, emptyList(), other))
        assertFailsWith<MantenimientoValidationException> { MantenimientoActions.userId(MantenimientoActions.RetiredUser, emptyList(), null) }
        assertEquals(tower.id, MantenimientoActions.propertyId("torre", listOf(tower)))
        assertFailsWith<MantenimientoValidationException> { MantenimientoActions.propertyId("Otra", listOf(tower)) }
        assertEquals(asset.id, lookups.assets.id(lookups.assets.label(asset.id)))
        assertNull(lookups.assets.id(MantenimientoActions.NoAsset))
        assertFailsWith<MantenimientoValidationException> { lookups.assets.id("Inventado") }
    }

    @Test
    fun repeatedAssetNamesGetUniqueLabels() {
        val a = asset.copy(id = UUID.randomUUID(), name = "Bomba", propertyName = "Torre")
        val b = asset.copy(id = UUID.randomUUID(), name = "Bomba", propertyName = "Torre")
        val c = asset.copy(id = UUID.randomUUID(), name = "Bomba", propertyName = null)
        val options = AssetOptions(listOf(a, b, c))
        assertEquals(3, options.labels.map { it.lowercase() }.toSet().size)
        listOf(a, b, c).forEach { assertEquals(it.id, options.id(options.label(it.id))) }
    }

    @Test
    fun onlyManagersChangeAssigneeAndDueDate() {
        val values = mapOf("title" to " Fuga ", "assignee" to "Otro", "due" to "2026-12-01", "priority" to "Alta", "quoted_by" to "Proveedor")
        val reporter = MantenimientoActions.orderInput(OrderKind.Corrective, values, lookups, canManage = false)
        assertEquals("Fuga", reporter.title)
        assertNull(reporter.assigneeId)
        assertNull(reporter.dueDate)
        assertEquals("", reporter.quotedBy)
        assertEquals(OrderPriority.High, reporter.priority)
        val manager = MantenimientoActions.orderInput(OrderKind.Corrective, values, lookups, canManage = true)
        assertEquals(other, manager.assigneeId)
        assertEquals(LocalDate.of(2026, 12, 1), manager.dueDate)
        assertEquals("Proveedor", manager.quotedBy)
    }

    @Test
    fun fieldsTheFormDidNotShowKeepTheirValue() {
        val existing = MantenimientoFixtures.order(kind = OrderKind.Project, status = OrderStatus.Open).copy(
            assigneeId = other, dueDate = LocalDate.of(2026, 12, 5), department = "Ventas", costAmount = BigDecimal("5.00"), assetId = asset.id,
        )
        val input = MantenimientoActions.orderInput(OrderKind.Project, mapOf("title" to "Nuevo título"), lookups, canManage = true, existing = existing)
        assertEquals("Nuevo título", input.title)
        assertEquals(other, input.assigneeId)
        assertEquals(LocalDate.of(2026, 12, 5), input.dueDate)
        assertEquals("Ventas", input.department)
        assertEquals(BigDecimal("5.00"), input.costAmount)
        assertEquals(asset.id, input.assetId)
        // Quitar el responsable es una elección explícita.
        assertNull(MantenimientoActions.orderInput(OrderKind.Project, mapOf("assignee" to "Sin responsable"), lookups, true, existing).assigneeId)
    }

    @Test
    fun workValuesKeepWhatWasNotSent() {
        val existing = MantenimientoFixtures.order().copy(costAmount = BigDecimal("3.00"), costCurrency = "EUR", resolution = "Listo")
        val work = MantenimientoActions.workValues(mapOf("resolution" to "Cambié la pieza"), existing)
        assertEquals("Cambié la pieza", work.resolution)
        assertEquals(BigDecimal("3.00"), work.costAmount)
        assertEquals("EUR", work.costCurrency)
        assertNull(MantenimientoActions.workValues(mapOf("cost" to ""), existing).costAmount)
    }

    @Test
    fun planInputReadsFrequencyAndIntervalTogether() {
        val values = mapOf(
            "title" to "Revisar extintores", "frequency" to "Cada cierto número de días", "interval" to "45", "next_due" to "2026-12-01",
            "lead_days" to "5", "priority" to "Baja", "schedule_mode" to "Desde el último cierre", "assignee" to "Yo",
        )
        val input = MantenimientoActions.planInput(values, lookups, listOf("Uno"))
        assertEquals(PlanFrequency.EveryNDays, input.frequency)
        assertEquals(45, input.intervalDays)
        assertEquals(ScheduleMode.Floating, input.scheduleMode)
        assertEquals(me, input.defaultAssignee)
        assertEquals(5, input.leadDays)
        assertTrue(input.validate().isEmpty())
        // Con otra frecuencia los días se ignoran.
        assertNull(MantenimientoActions.planInput(values + ("frequency" to "Mensual"), lookups, emptyList()).intervalDays)
        assertFailsWith<MantenimientoValidationException> { MantenimientoActions.planInput(values + ("frequency" to "Cada luna"), lookups, emptyList()) }
    }

    @Test
    fun checklistMovesStayInsideTheList() {
        val items = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), MantenimientoActions.moved(items, 1, up = true))
        assertEquals(listOf("a", "c", "b"), MantenimientoActions.moved(items, 1, up = false))
        assertEquals(items, MantenimientoActions.moved(items, 0, up = true))
        assertEquals(items, MantenimientoActions.moved(items, 2, up = false))
        assertEquals(items, MantenimientoActions.moved(items, -1, up = false))
    }

    @Test
    fun orderFilterReadsLabelsAndIgnoresAll() {
        val params = mapOf("status" to "En curso", "kind" to "Proyecto", "property" to "Torre", "assignee" to "Otro", "q" to " fuga ")
        val filter = MantenimientoActions.orderFilter(params, users, listOf(tower), board = false, limit = 201)
        assertEquals(OrderStatus.InProgress, filter.status)
        assertEquals(OrderKind.Project, filter.kind)
        assertEquals(tower.id, filter.propertyId)
        assertEquals(other, filter.assigneeId)
        assertEquals("fuga", filter.query)
        assertEquals(201, filter.limit)
        assertNull(MantenimientoActions.orderFilter(params, users, listOf(tower), board = true, limit = 10).status)
        val all = MantenimientoActions.orderFilter(mapOf("status" to "Todos", "property" to "Todos", "assignee" to "Desconocido"), users, listOf(tower), false, 10)
        assertNull(all.status)
        assertNull(all.propertyId)
        assertNull(all.assigneeId)
        assertEquals(mapOf("q" to "fuga", "kind" to "Proyecto"), MantenimientoActions.filterParams(mapOf("q" to "fuga", "kind" to "Proyecto", "status" to "Todos", "x" to "y")))
    }

    @Test
    fun fileChangesLinkNewOnesAndRemoveMissingOnes() {
        val kept = attachment()
        val removed = attachment()
        val fresh = UiFileRef(UUID.randomUUID().toString(), "foto.png", "", 10)
        val sent = UiFieldValues.files(listOf(UiFileRef(kept.id.toString(), kept.name, "", 1), fresh))
        val changes = MantenimientoActions.fileChanges(mapOf("files" to sent), listOf(kept, removed))
        assertEquals(listOf(fresh), changes.added)
        assertEquals(listOf(removed.id), changes.removed)
        // Un formulario sin la clave "files" no cambia nada.
        val none = MantenimientoActions.fileChanges(emptyMap(), listOf(kept))
        assertTrue(none.added.isEmpty() && none.removed.isEmpty())
    }

    private fun attachment() = Attachment(UUID.randomUUID(), UUID.randomUUID(), "a.pdf", "", "application/pdf", 1, null, Instant.EPOCH)

    // ---- reglas de quién puede qué ----

    @Test
    fun workRulesFollowTheScope() {
        val manager = MantenimientoScope(me, canExecute = false, canManage = true)
        val worker = MantenimientoScope(me, canExecute = true, canManage = false)
        val viewer = MantenimientoScope(me, canExecute = false, canManage = false)
        val open = MantenimientoFixtures.order(status = OrderStatus.Open, assigneeId = me)
        val done = open.copy(status = OrderStatus.Done)
        assertTrue(MantenimientoActions.canWorkNow(worker, open))
        assertFalse(MantenimientoActions.canWorkNow(worker, done))
        assertTrue(MantenimientoActions.canWorkNow(manager, done))
        assertFalse(MantenimientoActions.canWorkNow(viewer, open))
        // Quien reportó anota y adjunta aunque solo vea.
        val reported = open.copy(assigneeId = other, requestedBy = me)
        assertTrue(MantenimientoActions.canNote(viewer, reported))
        assertTrue(MantenimientoActions.canAttach(viewer, reported))
        assertFalse(MantenimientoActions.canNote(viewer, reported.copy(requestedBy = other)))
        // Cerrada la orden, el solicitante ya no anota ni adjunta.
        assertFalse(MantenimientoActions.canNote(viewer, reported.copy(status = OrderStatus.Done)))
        assertFalse(MantenimientoActions.canAttach(viewer, reported.copy(status = OrderStatus.Cancelled)))
        // La lista se trabaja mientras la orden está abierta; quien gestiona también al cerrarla.
        assertTrue(MantenimientoActions.canWorkChecklist(worker, open))
        assertFalse(MantenimientoActions.canWorkChecklist(worker, done))
        assertTrue(MantenimientoActions.canWorkChecklist(manager, done))
    }

    @Test
    fun serverOwnerHasNoCompanyUser() {
        val scope = MantenimientoScope.from(MantenimientoFixtures.permissions(UUID.randomUUID(), businessOwner = true, serverOwner = true))
        assertNull(scope.userId)
        assertTrue(scope.canManage)
        assertFalse(MantenimientoActions.isRequester(scope, MantenimientoFixtures.order(requestedBy = null)))
    }

    @Test
    fun routesAskTheMinimumPermissionPerScreen() {
        assertEquals(MantenimientoModule.Manage, MantenimientoActions.requiredPermission("admin", null))
        assertEquals(MantenimientoModule.Manage, MantenimientoActions.requiredPermission("admin", "prop-add"))
        assertEquals(MantenimientoModule.View, MantenimientoActions.requiredPermission("plan", null))
        assertEquals(MantenimientoModule.Manage, MantenimientoActions.requiredPermission("plan", "save"))
        assertEquals(MantenimientoModule.View, MantenimientoActions.requiredPermission("asset", null))
        assertEquals(MantenimientoModule.Manage, MantenimientoActions.requiredPermission("asset", "archive"))
        assertEquals(MantenimientoModule.Import, MantenimientoActions.requiredPermission("importar", "read"))
        assertEquals(MantenimientoModule.View, MantenimientoActions.requiredPermission("order", "status"))
        assertEquals(MantenimientoModule.View, MantenimientoActions.requiredPermission("new-order", "create"))
    }

    // ---- descargas seguras ----

    @Test
    fun downloadsOnlyKeepSafeContentTypes() {
        assertEquals("application/pdf", MantenimientoFiles.safeType("application/pdf; charset=binary"))
        assertEquals("image/png", MantenimientoFiles.safeType("IMAGE/PNG"))
        assertEquals("application/octet-stream", MantenimientoFiles.safeType("image/svg+xml"))
        assertEquals("application/octet-stream", MantenimientoFiles.safeType("text/html"))
        assertEquals("application/octet-stream", MantenimientoFiles.safeType(null))
        assertEquals("application/octet-stream", MantenimientoFiles.safeType("application/javascript"))
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals(docx, MantenimientoFiles.safeType(docx))
        assertEquals("application/octet-stream", MantenimientoFiles.safeType("application/vnd.openxmlformats-officedocument."))
    }
}
