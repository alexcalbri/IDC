package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.employee
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Validacion de lo que escriben formularios y agentes, y la lectura pura de los formularios. */
class EmpleadosInputTest {
    private val today = LocalDate.of(2026, 11, 18)

    private fun valid(vararg changes: (EmployeeInput) -> EmployeeInput): EmployeeInput =
        changes.fold(EmployeeInput(fullName = "Ana Ruiz")) { input, change -> change(input) }

    private fun problems(input: EmployeeInput) = input.validate(today)

    // ---- ficha ----

    @Test
    fun aMinimalRecordIsValid() {
        assertTrue(problems(valid()).isEmpty())
    }

    @Test
    fun nameIsRequiredAndLimited() {
        assertEquals(1, problems(valid({ it.copy(fullName = "  ") })).size)
        assertTrue(problems(valid({ it.copy(fullName = "x".repeat(160)) })).isEmpty())
        assertEquals(1, problems(valid({ it.copy(fullName = "x".repeat(161)) })).size)
    }

    @Test
    fun jobTitleAndAvailabilityAndNotesAreLimited() {
        assertTrue(problems(valid({ it.copy(jobTitle = "x".repeat(120), availability = "x".repeat(500), notes = "x".repeat(5000)) })).isEmpty())
        assertEquals(1, problems(valid({ it.copy(jobTitle = "x".repeat(121)) })).size)
        assertEquals(1, problems(valid({ it.copy(availability = "x".repeat(501)) })).size)
        assertEquals(1, problems(valid({ it.copy(notes = "x".repeat(5001)) })).size)
    }

    @Test
    fun phoneFormat() {
        listOf("555-0100", "+1 (555) 010-0100", "5550100 x23", "55.5010.0").forEach {
            assertTrue(problems(valid({ input -> input.copy(phone = it) })).isEmpty(), it)
        }
        listOf("abc", "555;0100", "<script>", "1".repeat(41)).forEach {
            assertEquals(1, problems(valid({ input -> input.copy(phone = it) })).size, it)
        }
    }

    @Test
    fun emailFormat() {
        assertTrue(problems(valid({ it.copy(email = "ana@example.com") })).isEmpty())
        listOf("ana", "ana@", "@example.com", "ana@example", "ana @example.com", "a@b@c.com").forEach {
            assertEquals(1, problems(valid({ input -> input.copy(email = it) })).size, it)
        }
        assertEquals(1, problems(valid({ it.copy(email = "a".repeat(250) + "@b.co") })).size)
    }

    @Test
    fun hireDateRange() {
        assertTrue(problems(valid({ it.copy(hireDate = today) })).isEmpty())
        assertTrue(problems(valid({ it.copy(hireDate = today.plusYears(1)) })).isEmpty())
        assertEquals(1, problems(valid({ it.copy(hireDate = today.plusYears(1).plusDays(1)) })).size)
        assertEquals(1, problems(valid({ it.copy(hireDate = LocalDate.of(1949, 12, 31)) })).size)
    }

    @Test
    fun severalProblemsAreAllReported() {
        assertEquals(3, problems(valid({ it.copy(fullName = "", phone = "abc", email = "x") })).size)
    }

    // ---- turnos ----

    private val employeeId = UUID.randomUUID()
    private val date = LocalDate.of(2026, 11, 20)

    @Test
    fun aWorkShiftNeedsDifferentStartAndEnd() {
        assertTrue(ShiftInput(employeeId, date, ShiftKind.Work, LocalTime.of(8, 0), LocalTime.of(16, 0)).validate().isEmpty())
        assertTrue(ShiftInput(employeeId, date, ShiftKind.Work, LocalTime.of(22, 0), LocalTime.of(6, 0)).validate().isEmpty())
        assertEquals(1, ShiftInput(employeeId, date, ShiftKind.Work, null, LocalTime.of(16, 0)).validate().size)
        assertEquals(1, ShiftInput(employeeId, date, ShiftKind.Work, LocalTime.of(8, 0), LocalTime.of(8, 0)).validate().size)
    }

    @Test
    fun anOffShiftNeedsNoHours() {
        assertTrue(ShiftInput(employeeId, date, ShiftKind.Off).validate().isEmpty())
    }

    @Test
    fun shiftDateAndNoteLimits() {
        assertEquals(1, ShiftInput(employeeId, LocalDate.of(1999, 12, 31), ShiftKind.Off).validate().size)
        assertEquals(1, ShiftInput(employeeId, LocalDate.of(2101, 1, 1), ShiftKind.Off).validate().size)
        assertEquals(1, ShiftInput(employeeId, date, ShiftKind.Off, note = "x".repeat(501)).validate().size)
        assertTrue(ShiftInput(employeeId, date, ShiftKind.Off, note = "x".repeat(500)).validate().isEmpty())
    }

    // ---- ausencias ----

    @Test
    fun absenceDatesMustBeOrderedAndShort() {
        val start = LocalDate.of(2026, 12, 1)
        assertTrue(AbsenceInput(employeeId, AbsenceKind.Vacation, start, start).validate().isEmpty())
        assertTrue(AbsenceInput(employeeId, AbsenceKind.Vacation, start, start.plusDays(366)).validate().isEmpty())
        assertEquals(1, AbsenceInput(employeeId, AbsenceKind.Vacation, start, start.plusDays(367)).validate().size)
        assertEquals(1, AbsenceInput(employeeId, AbsenceKind.Vacation, start, start.minusDays(1)).validate().size)
    }

    @Test
    fun absenceReasonIsLimited() {
        val start = LocalDate.of(2026, 12, 1)
        assertTrue(AbsenceInput(employeeId, AbsenceKind.Sick, start, start, "x".repeat(1000)).validate().isEmpty())
        assertEquals(1, AbsenceInput(employeeId, AbsenceKind.Sick, start, start, "x".repeat(1001)).validate().size)
    }

    // ---- lectura de formularios ----

    @Test
    fun datesAndTimesAreParsedStrictly() {
        assertNull(EmpleadosActions.date(""))
        assertEquals(LocalDate.of(2026, 11, 18), EmpleadosActions.date(" 2026-11-18 "))
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.date("18/11/2026") }
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.requiredDate("", "La fecha") }
        assertNull(EmpleadosActions.time(""))
        assertEquals(LocalTime.of(8, 0), EmpleadosActions.time("8:00"))
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.time("25:00") }
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.requiredTime(null, "La hora") }
    }

    @Test
    fun weekdayAndKindsAreReadByTheirLabels() {
        assertEquals(1, EmpleadosActions.weekday("Lunes"))
        assertEquals(3, EmpleadosActions.weekday("miércoles"))
        assertEquals(7, EmpleadosActions.weekday("DOMINGO"))
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.weekday("Funday") }
        assertEquals(ShiftKind.Work, EmpleadosActions.shiftKind(""))
        assertEquals(ShiftKind.Off, EmpleadosActions.shiftKind("Libre"))
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.shiftKind("Otro") }
        assertEquals(AbsenceKind.Sick, EmpleadosActions.absenceKind("Enfermedad"))
        assertEquals(AbsenceKind.Vacation, EmpleadosActions.absenceKind("vacation"))
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.absenceKind("") }
    }

    @Test
    fun employeeLabelsAreUniqueAndResolveBack() {
        val a1 = employee(name = "Ana Ruiz", jobTitle = "Técnico")
        val a2 = employee(name = "ana ruiz", jobTitle = "Recepción")
        val a3 = employee(name = "Ana Ruiz", jobTitle = "Técnico")
        val b = employee(name = "Beto", jobTitle = "")
        val labels = EmpleadosActions.employeeLabels(listOf(a1, a2, a3, b))
        assertEquals(4, labels.values.map { it.lowercase() }.toSet().size)
        assertEquals("Beto", labels[b.id])
        assertEquals("Ana Ruiz · Técnico", labels[a1.id])
        assertEquals("ana ruiz · Recepción", labels[a2.id])
        listOf(a1, a2, a3, b).forEach { assertEquals(it.id, EmpleadosActions.employeeId(labels[it.id], labels)) }
    }

    @Test
    fun anUnknownEmployeeLabelIsAnError() {
        val labels = EmpleadosActions.employeeLabels(listOf(employee(name = "Ana")))
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.employeeId("Zoe", labels) }
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.employeeId("", labels) }
    }

    private val site = CatalogItem(UUID.randomUUID(), "La Jolla", 0, false)
    private val department = CatalogItem(UUID.randomUUID(), "Mantenimiento", 0, false)

    @Test
    fun theEmployeeFormBecomesAnInput() {
        val user = UserRef(UUID.randomUUID(), "Ana", "ana@example.com")
        val input = EmpleadosActions.employeeInput(
            mapOf(
                "full_name" to " Ana Ruiz ", "job_title" to "Técnico", "department" to "mantenimiento", "site" to "La Jolla",
                "phone" to "555-0100", "email" to "ana@example.com", "hire_date" to "2024-03-01", "availability" to "Mañanas", "notes" to "ok", "user" to "Ana",
            ),
            listOf(site), listOf(department), listOf(user), null, today,
        )
        assertEquals("Ana Ruiz", input.fullName)
        assertEquals(site.id, input.siteId)
        assertEquals(department.id, input.departmentId)
        assertEquals(user.id, input.userId)
        assertEquals(LocalDate.of(2024, 3, 1), input.hireDate)
    }

    @Test
    fun noSiteNoDepartmentNoUserMeanNull() {
        val input = EmpleadosActions.employeeInput(
            mapOf("full_name" to "Ana", "site" to EmpleadosActions.NoSite, "department" to EmpleadosActions.NoDepartment, "user" to EmpleadosActions.NoUser),
            listOf(site), listOf(department), emptyList(), null, today,
        )
        assertNull(input.siteId)
        assertNull(input.departmentId)
        assertNull(input.userId)
    }

    @Test
    fun unknownSiteDepartmentOrUserIsRejected() {
        fun parse(vararg pairs: Pair<String, String>) =
            EmpleadosActions.employeeInput(mapOf("full_name" to "Ana") + pairs, listOf(site), listOf(department), emptyList(), null, today)
        assertFailsWith<EmpleadosValidationException> { parse("site" to "Otra") }
        assertFailsWith<EmpleadosValidationException> { parse("department" to "Otro") }
        assertFailsWith<EmpleadosValidationException> { parse("user" to "Zoe") }
    }

    @Test
    fun withoutUsersListTheLinkedUserIsKept() {
        val linked = UUID.randomUUID()
        val existing = employee().copy(userId = linked, level = FieldLevel.Full)
        val input = EmpleadosActions.employeeInput(mapOf("full_name" to "Ana"), emptyList(), emptyList(), null, existing, today)
        assertEquals(linked, input.userId)
    }

    @Test
    fun invalidFormValuesAreReportedTogether() {
        val failure = assertFailsWith<EmpleadosValidationException> {
            EmpleadosActions.employeeInput(mapOf("full_name" to "", "phone" to "abc"), emptyList(), emptyList(), emptyList(), null, today)
        }
        assertTrue("nombre" in failure.message.orEmpty().lowercase())
        assertTrue("teléfono" in failure.message.orEmpty().lowercase())
    }

    @Test
    fun filtersDefaultToActiveAndIgnoreUnknownLabels() {
        val sites = listOf(site)
        val departments = listOf(department)
        val none = EmpleadosActions.filter(emptyMap(), sites, departments, 10)
        assertEquals(EmployeeStatus.Active, none.status)
        assertNull(none.siteId)
        val filtered = EmpleadosActions.filter(mapOf("q" to " ana ", "site" to "la jolla", "department" to "Mantenimiento", "status" to "Bajas"), sites, departments, 10)
        assertEquals("ana", filtered.query)
        assertEquals(site.id, filtered.siteId)
        assertEquals(department.id, filtered.departmentId)
        assertEquals(EmployeeStatus.Inactive, filtered.status)
        val all = EmpleadosActions.filter(mapOf("site" to "Todos", "department" to "Nada", "status" to "Todos"), sites, departments, 10)
        assertNull(all.siteId)
        assertNull(all.departmentId)
        assertNull(all.status)
    }

    @Test
    fun filterParamsKeepOnlyWhatIsSet() {
        assertEquals(mapOf("site" to "La Jolla"), EmpleadosActions.filterParams(mapOf("site" to "La Jolla", "department" to "Todos", "q" to " ", "other" to "x")))
    }

    @Test
    fun weekOfFallsBackToThisWeek() {
        assertEquals(LocalDate.of(2026, 11, 16), EmpleadosActions.weekOf(null, today))
        assertEquals(LocalDate.of(2026, 11, 16), EmpleadosActions.weekOf("garbage", today))
        assertEquals(LocalDate.of(2026, 11, 23), EmpleadosActions.weekOf("2026-11-26", today))
    }

    @Test
    fun formBodiesMustBeJsonObjects() {
        assertEquals(mapOf("a" to "1", "b" to ""), EmpleadosActions.formValues("""{"a":"1","b":null}"""))
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.formValues("[1,2]") }
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.formValues("no json") }
        assertFailsWith<EmpleadosValidationException> { EmpleadosActions.formValues("x".repeat(1_000_001)) }
    }

    // ---- permisos de las rutas ----

    @Test
    fun routePermissionsFollowTheDesign() {
        val view = setOf(EmpleadosModule.View, EmpleadosModule.Shifts, EmpleadosModule.Manage)
        val shifts = setOf(EmpleadosModule.Shifts, EmpleadosModule.Manage)
        val manage = setOf(EmpleadosModule.Manage)
        fun required(screen: String, op: String? = null) = EmpleadosActions.requiredPermissions(screen, op).toSet()
        assertEquals(view, required("mine"))
        assertEquals(view, required("team"))
        assertEquals(view, required("employee"))
        assertEquals(view, required("absences"))
        assertEquals(view, required("absences", "absence-request"))
        assertEquals(shifts, required("absences", "decide-approve"))
        assertEquals(shifts, required("absences", "decide-reject"))
        assertEquals(shifts, required("absences", "register"))
        assertEquals(shifts, required("week"))
        assertEquals(shifts, required("shift", "move"))
        assertEquals(shifts, required("employee", "slot-add"))
        assertEquals(shifts, required("employee", "slot-remove"))
        assertEquals(manage, required("employee", "save"))
        assertEquals(manage, required("employee", "status"))
        assertEquals(manage, required("employee-new"))
        assertEquals(manage, required("admin"))
        assertEquals(setOf(EmpleadosModule.Import), required("importar"))
    }
}
