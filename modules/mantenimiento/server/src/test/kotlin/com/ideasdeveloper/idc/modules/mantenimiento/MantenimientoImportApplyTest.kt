package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoFixtures.user
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reglas puras de la importación (resumen, correos, textos, estados). La escritura en base de datos no se prueba aquí. */
class MantenimientoImportApplyTest {
    private val ana = user("Ana", "Ana@Empresa.com")
    private val luis = user("Luis", "luis@empresa.com")
    private val users = listOf(ana, luis)

    private fun plan(key: String, title: String = "Revisar", asset: String? = null, email: String? = null, name: String? = null, checks: Int = 0) = ImportedPlan(
        key = key, title = title, propertyName = "Sede", assetName = asset, frequency = PlanFrequency.Monthly,
        assigneeEmail = email, assigneeName = name, checklist = List(checks) { ImportedCheck("$key:$it", "paso $it") },
    )

    private fun order(key: String, title: String = "Fuga", property: String? = null, email: String? = null, notes: Int = 0, kind: OrderKind = OrderKind.Corrective, status: OrderStatus = OrderStatus.Open) = ImportedOrder(
        key = key, kind = kind, status = status, title = title, propertyName = property, assigneeEmail = email,
        notes = List(notes) { ImportedNote("$key:n$it", "Ana", null, "nota $it") },
    )

    @Test
    fun matchUserIgnoresCaseAndSpaces() {
        assertEquals(ana, MantenimientoImportApply.matchUser("  ana@empresa.COM ", users))
        assertNull(MantenimientoImportApply.matchUser("otro@empresa.com", users))
        assertNull(MantenimientoImportApply.matchUser("  ", users))
        assertNull(MantenimientoImportApply.matchUser(null, users))
        assertNull(MantenimientoImportApply.matchUser("ana@empresa.com", listOf(user("Sin correo", null))))
    }

    @Test
    fun clipEndsWithAnEllipsisWithinTheLimit() {
        assertEquals("corto", MantenimientoImportApply.clip("corto", 10))
        val clipped = MantenimientoImportApply.clip("a".repeat(50), 10)
        assertEquals(10, clipped.length)
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun noteTextCarriesAuthorAndDate() {
        val at = Instant.parse("2026-11-18T10:30:00Z")
        val text = MantenimientoImportApply.noteText("Ana", at, "  Listo  ", ZoneOffset.UTC)
        assertEquals("[Ana · 2026-11-18 10:30] Listo", text)
        assertEquals("[Ana] Listo", MantenimientoImportApply.noteText("Ana", null, "Listo"))
        assertEquals("Listo", MantenimientoImportApply.noteText("", null, "Listo"))
        assertEquals(MantenimientoRepository.MaxNoteLength, MantenimientoImportApply.noteText("Ana", null, "x".repeat(9000)).length)
    }

    @Test
    fun descriptionKeepsTheNoteAboutUnmatchedPeopleEvenWhenTheBodyIsLong() {
        val text = MantenimientoImportApply.descriptionWithNote("d".repeat(6000), listOf("Pedro", "Sofía"), 5000)
        assertEquals(5000, text.length)
        assertTrue(text.endsWith("Responsable en Monday sin usuario: Pedro, Sofía"))
        assertEquals("Hola", MantenimientoImportApply.descriptionWithNote(" Hola ", emptyList(), 5000))
        assertEquals("Responsable en Monday sin usuario: Pedro", MantenimientoImportApply.descriptionWithNote("", listOf("Pedro"), 5000))
    }

    @Test
    fun oldOpenOrdersDoNotFireReminders() {
        val today = LocalDate.of(2026, 11, 18)
        assertTrue(MantenimientoImportApply.suppressesReminders(OrderStatus.Open, today.plusDays(1), today))
        assertTrue(MantenimientoImportApply.suppressesReminders(OrderStatus.InProgress, today.minusDays(30), today))
        assertFalse(MantenimientoImportApply.suppressesReminders(OrderStatus.Open, today.plusDays(2), today))
        assertFalse(MantenimientoImportApply.suppressesReminders(OrderStatus.Done, today.minusDays(1), today))
        assertFalse(MantenimientoImportApply.suppressesReminders(OrderStatus.Open, null, today))
    }

    @Test
    fun projectStatesOnlyKeptForProjects() {
        assertEquals(OrderStatus.Requested, MantenimientoImportApply.normalizeStatus(OrderKind.Project, OrderStatus.Requested))
        assertEquals(OrderStatus.Open, MantenimientoImportApply.normalizeStatus(OrderKind.Corrective, OrderStatus.Requested))
        assertEquals(OrderStatus.Open, MantenimientoImportApply.normalizeStatus(OrderKind.Preventive, OrderStatus.Quoting))
        assertEquals(OrderStatus.Cancelled, MantenimientoImportApply.normalizeStatus(OrderKind.Corrective, OrderStatus.Rejected))
        assertEquals(OrderStatus.Done, MantenimientoImportApply.normalizeStatus(OrderKind.Corrective, OrderStatus.Done))
    }

    @Test
    fun planSummaryCountsValidRowsAndReportsProblems() {
        val rows = listOf(
            plan("monday:1", asset = "Caldera", email = "ANA@empresa.com", checks = 3),
            plan("monday:2", asset = "caldera", email = "nadie@empresa.com"),
            plan("monday:2", title = "Repetido"),
            plan("", title = "Sin clave"),
            plan("monday:3", title = "  "),
            plan("monday:4", name = "Pedro"),
            plan("monday:5").copy(frequency = PlanFrequency.EveryNDays, intervalDays = null),
        )
        val summary = MantenimientoImportApply.summarizePlans(rows, users, files = 2)
        assertEquals(4, summary.plans)
        assertEquals(0, summary.orders)
        assertEquals(listOf("Sede"), summary.properties)
        assertEquals(listOf("Caldera"), summary.assets)
        assertEquals(3, summary.checklistItems)
        assertEquals(2, summary.files)
        assertEquals(listOf("Ana"), summary.matchedPeople)
        assertEquals(listOf("nadie@empresa.com", "Pedro"), summary.unmatchedPeople)
        assertTrue(summary.problems.any { "monday:2" in it && "más de una vez" in it })
        assertTrue(summary.problems.any { "identificador" in it })
        assertTrue(summary.problems.any { "monday:3" in it && "título" in it })
        assertTrue(summary.problems.any { "monday:5" in it && "días" in it })
    }

    @Test
    fun orderSummaryCountsNotesChecklistAndPeople() {
        val rows = listOf(
            order("monday:10", property = "Sede Norte", email = "luis@empresa.com", notes = 2).copy(checklist = listOf(ImportedCheck("a", "x"), ImportedCheck("b", "y", done = true))),
            order("monday:11", property = "sede norte", email = "nadie@x.com", notes = 1),
            order("monday:12", kind = OrderKind.Corrective, status = OrderStatus.Requested),
            order("monday:13", kind = OrderKind.Project, status = OrderStatus.Requested),
        )
        val summary = MantenimientoImportApply.summarizeOrders(rows, users)
        assertEquals(4, summary.orders)
        assertEquals(0, summary.plans)
        assertEquals(listOf("Sede Norte"), summary.properties)
        assertEquals(2, summary.checklistItems)
        assertEquals(3, summary.notes)
        assertEquals(listOf("Luis"), summary.matchedPeople)
        assertEquals(listOf("nadie@x.com"), summary.unmatchedPeople)
        assertEquals(1, summary.problems.size)
        assertTrue("monday:12" in summary.problems.first())
    }

    @Test
    fun emptyImportHasAnEmptySummary() {
        val summary = MantenimientoImportApply.summarizeOrders(emptyList(), users)
        assertEquals(0, summary.orders)
        assertTrue(summary.problems.isEmpty() && summary.matchedPeople.isEmpty() && summary.unmatchedPeople.isEmpty())
    }
}
