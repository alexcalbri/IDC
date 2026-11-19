package com.ideasdeveloper.idc.modules.empleados

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Reglas puras de la importacion: vista previa, recortes, horario limpio y filas que se omiten sin tocar la base de datos. */
class EmpleadosImportApplyTest {
    private fun row(key: String, name: String = "Empleado $key", site: String? = null, department: String? = null, email: String = "", weekly: List<ImportedSlot> = emptyList()) =
        ImportedEmployee(key = "monday:$key", fullName = name, site = site, department = department, email = email, weekly = weekly)

    private fun slot(day: Int, start: String, end: String) = ImportedSlot(day, LocalTime.parse(start), LocalTime.parse(end))

    @Test
    fun summaryCountsEmployeesSitesDepartmentsAndSchedules() {
        val rows = listOf(
            row("1", site = "La Jolla", department = "Mantenimiento", weekly = listOf(slot(1, "08:00", "16:00"))),
            row("2", site = "la jolla", department = "Mantenimiento"),
            row("3", site = "Del Mar", department = "Limpieza", weekly = listOf(slot(2, "08:00", "16:00"), slot(3, "08:00", "16:00"))),
            row("4"),
        )
        val summary = EmpleadosImportApply.summary(rows, emptySet())
        assertEquals(4, summary.employees)
        assertEquals(listOf("La Jolla", "Del Mar"), summary.sites)
        assertEquals(listOf("Mantenimiento", "Limpieza"), summary.departments)
        assertEquals(2, summary.withSchedule)
        assertTrue(summary.problems.isEmpty())
    }

    @Test
    fun summaryListsEmployeesWhoseEmailMatchesAUser() {
        val rows = listOf(row("1", "Ana", email = "ANA@Empresa.com"), row("2", "Luis", email = "luis@otra.com"), row("3", "Sin correo"))
        val summary = EmpleadosImportApply.summary(rows, setOf("ana@empresa.com"))
        // Solo informativo: posibles cuentas (nombre y correo); la importacion nunca enlaza.
        assertEquals(listOf("Ana (ANA@Empresa.com)"), summary.linkedUsers)
    }

    @Test
    fun summaryReportsRowsWithoutNameOrKeyAndRepeatedKeys() {
        val rows = listOf(
            row("1"),
            row("2", name = "  "),
            ImportedEmployee(key = " ", fullName = "Sin clave"),
            row("1", name = "Repetida"),
            ImportedEmployee(key = "k".repeat(201), fullName = "Clave larga"),
        )
        val summary = EmpleadosImportApply.summary(rows, emptySet())
        assertEquals(1, summary.employees)
        assertEquals(4, summary.problems.size)
    }

    @Test
    fun summaryReportsInvalidSlotsAndEmails() {
        val bad = row("1", "Ana", email = "no-es-correo", weekly = listOf(slot(9, "08:00", "16:00"), slot(1, "08:00", "16:00")))
        val summary = EmpleadosImportApply.summary(listOf(bad), emptySet())
        assertEquals(1, summary.employees)
        assertEquals(2, summary.problems.size)
        // La franja valida sigue contando.
        assertEquals(1, summary.withSchedule)
    }

    @Test
    fun summaryReportsPhonesThatGoToTheNotes() {
        val summary = EmpleadosImportApply.summary(listOf(ImportedEmployee("monday:1", "Ana", phone = "llamar a casa"), ImportedEmployee("monday:2", "Beto", phone = "555-0100")), emptySet())
        assertEquals(1, summary.problems.size)
        assertTrue("Ana" in summary.problems.single())
    }

    @Test
    fun outcomesCarryAnOptionalWarningWithoutBreakingTheOldShape() {
        val id = java.util.UUID.randomUUID()
        assertEquals(null, EmpleadosImportOutcome.Created(id).warning)
        assertEquals("aviso", EmpleadosImportOutcome.Updated(id, "aviso").warning)
    }

    @Test
    fun summaryLimitsTheProblemsListed() {
        val rows = List(80) { row("$it", name = "") }
        assertEquals(50, EmpleadosImportApply.summary(rows, emptySet()).problems.size)
    }

    @Test
    fun clipCutsWithAnEllipsis() {
        assertEquals("abc", EmpleadosImportApply.clip("abc", 3))
        assertEquals("ab…", EmpleadosImportApply.clip("abcdef", 3))
        assertEquals(160, EmpleadosImportApply.clip("x".repeat(500), 160).length)
        assertTrue(EmpleadosImportApply.clip("x".repeat(500), 160).endsWith("…"))
    }

    @Test
    fun cleanSlotsDropsInvalidRepeatedAndOverlappingOnes() {
        val clean = EmpleadosImportApply.cleanSlots(
            listOf(
                slot(1, "08:00", "16:00"),
                slot(1, "08:00", "16:00"),
                slot(1, "12:00", "20:00"),
                slot(0, "08:00", "16:00"),
                slot(8, "08:00", "16:00"),
                slot(2, "08:00", "08:00"),
                slot(3, "22:00", "06:00"),
            ),
        )
        assertEquals(
            listOf(
                Triple(1, LocalTime.of(8, 0), LocalTime.of(16, 0)),
                Triple(3, LocalTime.of(22, 0), LocalTime.of(6, 0)),
            ),
            clean,
        )
    }

    @Test
    fun cleanSlotsKeepsAtMostFourPerDayOrderedByStart() {
        val slots = (0 until 6).map { slot(1, "%02d:00".format(6 + it * 3), "%02d:30".format(6 + it * 3)) }.reversed()
        val clean = EmpleadosImportApply.cleanSlots(slots)
        assertEquals(4, clean.size)
        assertEquals(listOf(6, 9, 12, 15), clean.map { it.second.hour })
    }

    @Test
    fun cleanSlotsOrdersByWeekday() {
        val clean = EmpleadosImportApply.cleanSlots(listOf(slot(5, "08:00", "16:00"), slot(2, "08:00", "16:00")))
        assertEquals(listOf(2, 5), clean.map { it.first })
    }

    // La conexion falla ante cualquier uso: estas filas se omiten antes de tocar la base de datos.
    private val noDatabase = EmpleadosFixtures.noDatabase()

    @Test
    fun aRowWithoutNameIsSkippedBeforeTouchingTheDatabase() {
        assertIs<EmpleadosImportOutcome.Skipped>(EmpleadosImportApply.apply(noDatabase, row("1", name = "   "), emptyMap(), null))
    }

    @Test
    fun aRowWithAnInvalidKeyIsSkippedBeforeTouchingTheDatabase() {
        assertIs<EmpleadosImportOutcome.Skipped>(EmpleadosImportApply.apply(noDatabase, ImportedEmployee(key = "  ", fullName = "Ana"), emptyMap(), null))
        assertIs<EmpleadosImportOutcome.Skipped>(EmpleadosImportApply.apply(noDatabase, ImportedEmployee(key = "k".repeat(201), fullName = "Ana"), emptyMap(), null))
    }
}
