package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.employee
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.scope
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiScreen
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pantallas por rol: navegacion, lo que cada nivel de ficha deja ver y el contrato de la interfaz (JSON que se puede leer de vuelta). */
class EmpleadosScreensTest {
    // Un miercoles: su semana ISO empieza el lunes 2026-11-16.
    private val today = LocalDate.of(2026, 11, 18)
    private val monday = LocalDate.of(2026, 11, 16)
    private val me = UUID.randomUUID()
    private val site = CatalogItem(UUID.randomUUID(), "La Jolla", 0, false)
    private val department = CatalogItem(UUID.randomUUID(), "Mantenimiento", 0, false)

    private fun json(screen: UiScreen): String {
        val text = UiJson.encodeToString(UiScreen.serializer(), screen)
        // El contrato: lo que se envia se puede leer de vuelta.
        assertEquals(screen.title, UiJson.decodeFromString(UiScreen.serializer(), text).title)
        return text
    }

    private fun context(scope: EmpleadosScope, import: Boolean = false) = EmpleadosScreens.Context(scope, today, import)

    private val viewer get() = scope(myEmployeeId = me)
    private val shifter get() = scope(myEmployeeId = me, shifts = true)
    private val manager get() = scope(manage = true)

    private fun navIds(scope: EmpleadosScope, import: Boolean = false) = EmpleadosScreens.navigation(scope, "mine", import).items.map { it.id }

    // ---- navegacion ----

    @Test
    fun navigationFollowsTheRole() {
        assertEquals(listOf("mine", "team", "absences"), navIds(viewer))
        assertEquals(listOf("mine", "team", "week", "absences"), navIds(shifter))
        assertEquals(listOf("mine", "team", "week", "absences", "admin"), navIds(manager))
        assertEquals(listOf("mine", "team", "absences", "importar"), navIds(viewer, import = true))
    }

    @Test
    fun theModuleOpensOnMyScheduleAndKeepsItsRoute() {
        val ui = EmpleadosServerModule.definition.ui
        assertEquals("native", ui?.type)
        assertEquals("/modules/empleados/ui", ui?.entry)
        assertEquals("mine", ui?.home)
    }

    // ---- Mi horario ----

    private fun mineData(employee: Employee?) = EmpleadosScreens.MineData(
        employee = employee,
        week = monday,
        days = (0..6).map { monday.plusDays(it.toLong()) }.map { it to (if (it.dayOfWeek.value <= 5) DaySchedule.Working(listOf(DaySlot(LocalTime.of(8, 0), LocalTime.of(16, 0), site.id, "habitual"))) else DaySchedule.Off(null)) },
        weekly = listOf(EmpleadosFixtures.slot(employee?.id ?: me, 1, "08:00", "16:00")),
        absences = listOf(EmpleadosFixtures.absence(employee?.id ?: me, monday.plusDays(14), monday.plusDays(18), AbsenceStatus.Pending)),
        siteNames = mapOf(site.id to site.name),
    )

    @Test
    fun withoutALinkedRecordMineSaysSo() {
        val text = json(EmpleadosScreens.mine(context(viewer), mineData(null)))
        assertTrue(EmpleadosService.NoRecord in text)
        assertFalse("Pedir ausencia" in text)
    }

    @Test
    fun mineShowsTheWeekTheHabitualHoursAndTheAbsenceForm() {
        val text = json(EmpleadosScreens.mine(context(viewer), mineData(employee(id = me, level = FieldLevel.Own))))
        assertTrue("lun 16 nov" in text)
        assertTrue("08:00–16:00 · La Jolla" in text)
        assertTrue("Horario habitual" in text)
        assertTrue("Pedir ausencia" in text)
        // Una solicitud pendiente se puede cancelar.
        assertTrue("absence-cancel" in text)
        assertTrue("2026-11-23" in text)
    }

    // ---- Equipo ----

    @Test
    fun teamForAViewerNeverShowsContactData() {
        val basic = employee(name = "Beto", level = FieldLevel.Basic, jobTitle = "Técnico")
        val text = json(EmpleadosScreens.team(context(viewer), EmpleadosScreens.TeamData(listOf(basic), listOf(site), listOf(department)), emptyMap()))
        assertTrue("Beto" in text)
        assertFalse("555-0100" in text)
        assertFalse("ana@example.com" in text)
        // Quien no ve bajas no ve ni el filtro de estado ni el boton de nueva ficha.
        assertFalse("Bajas" in text)
        assertFalse("Nueva ficha" in text)
    }

    @Test
    fun teamForAShiftManagerShowsThePhoneAndTheStatusFilter() {
        val contact = employee(name = "Beto", level = FieldLevel.Contact)
        val text = json(EmpleadosScreens.team(context(shifter), EmpleadosScreens.TeamData(listOf(contact), listOf(site), listOf(department)), emptyMap()))
        assertTrue("555-0100" in text)
        assertTrue("Bajas" in text)
        assertFalse("Nueva ficha" in text)
    }

    @Test
    fun teamForAManagerOffersNewRecordsAndMarksInactiveOnes() {
        val inactive = employee(name = "Cora", status = EmployeeStatus.Inactive, level = FieldLevel.Full)
        val text = json(EmpleadosScreens.team(context(manager), EmpleadosScreens.TeamData(listOf(inactive), emptyList(), emptyList()), mapOf("status" to "Todos")))
        assertTrue("Nueva ficha" in text)
        assertTrue("\"Baja\"" in text)
    }

    @Test
    fun teamCutsTheListAndWarns() {
        val many = List(EmpleadosScreens.ListLimit + 1) { employee(name = "Persona $it") }
        val text = json(EmpleadosScreens.team(context(viewer), EmpleadosScreens.TeamData(many, emptyList(), emptyList()), emptyMap()))
        assertTrue("primeros ${EmpleadosScreens.ListLimit}" in text)
        assertFalse("Persona ${EmpleadosScreens.ListLimit}\"" in text)
    }

    // ---- Ficha ----

    private fun detail(employee: Employee) = EmpleadosScreens.EmployeeDetail(
        employee = employee,
        sites = listOf(site),
        departments = listOf(department),
        users = listOf(UserRef(UUID.randomUUID(), "Ana", "ana@example.com")),
        weekly = listOf(EmpleadosFixtures.slot(employee.id, 1, "08:00", "16:00")),
        upcoming = listOf(today to DaySchedule.Working(listOf(DaySlot(LocalTime.of(8, 0), LocalTime.of(16, 0), site.id, "shift", UUID.randomUUID())))),
        siteNames = mapOf(site.id to site.name),
    )

    @Test
    fun theRecordOfOthersIsReadOnlyForAViewer() {
        val text = json(EmpleadosScreens.employee(context(viewer), detail(employee(name = "Beto", level = FieldLevel.Basic))))
        assertTrue("Beto" in text)
        assertFalse("555-0100" in text)
        assertFalse("Nota privada" in text)
        assertFalse("\"id\":\"employee\"" in text)
        assertFalse("Horario habitual" in text)
    }

    @Test
    fun theOwnRecordShowsContactAndHireDateButNoNotesAndNoForm() {
        val own = employee(id = me, name = "Ana", level = FieldLevel.Own)
        val text = json(EmpleadosScreens.employee(context(viewer), detail(own)))
        assertTrue("555-0100" in text)
        assertTrue("2024-03-01" in text)
        assertFalse("Nota privada" in text)
        assertFalse("\"id\":\"employee\"" in text)
    }

    @Test
    fun aShiftManagerSeesContactAndEditsTheSchedule() {
        val text = json(EmpleadosScreens.employee(context(shifter), detail(employee(name = "Beto", level = FieldLevel.Contact))))
        assertTrue("555-0100" in text)
        assertTrue("Horario habitual" in text)
        assertTrue("slot-add" in text)
        assertTrue("Próximos turnos" in text)
        // Un turno concreto lleva a su pantalla.
        assertTrue("\"screen\":\"shift\"" in text)
        assertFalse("Nota privada" in text)
        assertFalse("\"id\":\"employee\"" in text)
    }

    @Test
    fun aManagerEditsTheWholeRecord() {
        val text = json(EmpleadosScreens.employee(context(manager), detail(employee(name = "Beto", level = FieldLevel.Full))))
        assertTrue("\"id\":\"employee\"" in text)
        assertTrue("Nota privada" in text)
        assertTrue("\"key\":\"notes\"" in text)
        assertTrue("\"key\":\"user\"" in text)
        assertTrue("Dar de baja" in text)
    }

    @Test
    fun aManagerCanReactivateAnInactiveRecord() {
        val text = json(EmpleadosScreens.employee(context(manager), detail(employee(status = EmployeeStatus.Inactive, level = FieldLevel.Full))))
        assertTrue("Reactivar" in text)
        assertFalse("Dar de baja" in text)
    }

    @Test
    fun newRecordFormHasTheUserField() {
        val text = json(EmpleadosScreens.employeeNew(context(manager), listOf(site), listOf(department), emptyList()))
        assertTrue("Crear ficha" in text)
        assertTrue("\"key\":\"full_name\"" in text)
    }

    // ---- Semana ----

    @Test
    fun weekCellsShowHoursSiteStarAndAbsences() {
        val other = UUID.randomUUID()
        val names = mapOf(site.id to "La Jolla", other to "Del Mar")
        val person = employee(siteId = site.id, site = "La Jolla")
        fun slot(siteId: UUID?, source: String) = DaySlot(LocalTime.of(8, 0), LocalTime.of(16, 0), siteId, source)
        assertEquals("08:00–16:00", EmpleadosScreens.weekCell(person, DaySchedule.Working(listOf(slot(site.id, "habitual"))), names))
        assertEquals("08:00–16:00*", EmpleadosScreens.weekCell(person, DaySchedule.Working(listOf(slot(site.id, "shift"))), names))
        assertEquals("08:00–16:00 (Del Mar)", EmpleadosScreens.weekCell(person, DaySchedule.Working(listOf(slot(other, "habitual"))), names))
        assertEquals("Libre", EmpleadosScreens.weekCell(person, DaySchedule.Off(null), names))
        assertEquals("Libre*", EmpleadosScreens.weekCell(person, DaySchedule.Off("shift"), names))
        assertEquals("Vacaciones", EmpleadosScreens.weekCell(person, DaySchedule.Absent(AbsenceKind.Vacation), names))
        assertEquals("Enfermedad", EmpleadosScreens.weekCell(person, DaySchedule.Absent(AbsenceKind.Sick), names))
    }

    private fun weekData(employees: List<Employee>, cut: Boolean = false): EmpleadosScreens.WeekData {
        val weekly = employees.associate { it.id to listOf(EmpleadosFixtures.slot(it.id, 1, "08:00", "16:00")) }
        val shifts = employees.take(1).associate { it.id to listOf(EmpleadosFixtures.off(it.id, monday.plusDays(1))) }
        return EmpleadosScreens.WeekData(
            start = monday,
            employees = employees,
            cut = cut,
            schedules = ScheduleData(weekly, shifts, emptyMap()),
            sites = listOf(site),
            departments = listOf(department),
            shifts = shifts.values.flatten(),
            allEmployees = employees,
            siteNames = mapOf(site.id to site.name),
        )
    }

    @Test
    fun weekGroupsEmployeesBySiteAndMarksConcreteShifts() {
        val a = employee(name = "Ana", siteId = site.id, site = site.name)
        val b = employee(name = "Beto")
        val text = json(EmpleadosScreens.week(context(shifter), weekData(listOf(a, b)), emptyMap()))
        assertTrue("\"title\":\"La Jolla\"" in text)
        assertTrue("\"title\":\"Sin sede\"" in text)
        assertTrue("08:00–16:00" in text)
        assertTrue("Libre*" in text)
        assertTrue("Nuevo turno" in text)
        assertTrue("Turnos de la semana" in text)
        // Siete dias de la semana ISO como columnas.
        assertTrue("\"label\":\"Lun 16\"" in text)
        assertTrue("\"label\":\"Dom 22\"" in text)
    }

    @Test
    fun weekWarnsWhenTheListWasCut() {
        val text = json(EmpleadosScreens.week(context(shifter), weekData(listOf(employee()), cut = true), emptyMap()))
        assertTrue("primeros ${EmpleadosRepository.MaxWeekEmployees}" in text)
    }

    @Test
    fun weekNavigationKeepsTheFilters() {
        val text = json(EmpleadosScreens.week(context(shifter), weekData(listOf(employee())), mapOf("site" to "La Jolla", "department" to "Todos")))
        assertTrue("\"start\":\"2026-11-23\"" in text)
        assertTrue("\"start\":\"2026-11-09\"" in text)
        assertTrue("\"site\":\"La Jolla\"" in text)
    }

    // ---- Turno ----

    @Test
    fun theShiftScreenMovesOrDeletes() {
        val worker = employee(name = "Ana")
        val shift = EmpleadosFixtures.work(worker.id, monday, "08:00", "16:00", site.id)
        val text = json(EmpleadosScreens.shift(context(shifter), shift, "Ana", listOf(site), mapOf(site.id to site.name)))
        assertTrue("\"op\":\"move\"" in text)
        assertTrue("\"op\":\"delete\"" in text)
        assertTrue("¿Borrar este turno?" in text)
        assertTrue("\"key\":\"start\"" in text)
        val off = json(EmpleadosScreens.shift(context(shifter), EmpleadosFixtures.off(worker.id, monday), "Ana", listOf(site), emptyMap()))
        assertFalse("\"key\":\"start\"" in off)
    }

    // ---- Ausencias ----

    @Test
    fun absencesForAViewerHaveNoDecisions() {
        val data = EmpleadosScreens.AbsencesData(true, emptyList(), emptyList(), emptyList(), emptyList())
        val text = json(EmpleadosScreens.absences(context(viewer), data))
        assertTrue("Pedir ausencia" in text)
        assertFalse("Pendientes" in text)
        assertFalse("Registrar ausencia" in text)
        assertFalse("decide-approve" in text)
    }

    @Test
    fun absencesWithoutARecordSayWhyAndNothingCanBeRequested() {
        val data = EmpleadosScreens.AbsencesData(false, emptyList(), emptyList(), emptyList(), emptyList())
        val text = json(EmpleadosScreens.absences(context(viewer), data))
        assertTrue(EmpleadosService.NoRecord in text)
        assertFalse("absence-request" in text)
    }

    @Test
    fun absencesForAShiftManagerCanApproveRejectAndRegister() {
        val worker = employee(name = "Beto")
        val pending = AbsenceRow(EmpleadosFixtures.absence(worker.id, monday.plusDays(7), monday.plusDays(9), AbsenceStatus.Pending), "Beto")
        val approved = AbsenceRow(EmpleadosFixtures.absence(worker.id, monday.plusDays(14), monday.plusDays(15)), "Beto")
        val data = EmpleadosScreens.AbsencesData(true, emptyList(), listOf(pending), listOf(approved), listOf(worker))
        val text = json(EmpleadosScreens.absences(context(shifter), data))
        assertTrue("Pendientes (1)" in text)
        assertTrue("decide-approve" in text)
        assertTrue("decide-reject" in text)
        assertTrue("Registrar ausencia" in text)
        assertTrue("Próximas aprobadas" in text)
    }

    @Test
    fun aFailedFormComesBackWithWhatTheUserTyped() {
        val data = EmpleadosScreens.AbsencesData(true, emptyList(), emptyList(), emptyList(), emptyList())
        val text = json(EmpleadosScreens.absences(context(viewer), data, submitted = mapOf("reason" to "Boda de mi hermana"), submittedForm = "absence-request"))
        assertTrue("Boda de mi hermana" in text)
        val other = json(EmpleadosScreens.absences(context(viewer), data, submitted = mapOf("reason" to "Boda de mi hermana"), submittedForm = "absence-register"))
        assertFalse("Boda de mi hermana" in other)
    }

    // ---- Administrar ----

    @Test
    fun adminListsSitesAndDepartmentsWithTheirOperations() {
        val text = json(EmpleadosScreens.admin(context(manager), listOf(site), listOf(department)))
        listOf("site-rename", "site-up", "site-down", "site-archive", "site-add", "dept-rename", "dept-archive", "dept-add", "La Jolla", "Mantenimiento").forEach {
            assertTrue(it in text, it)
        }
    }

    // ---- textos ----

    @Test
    fun dayTextShowsHoursSitesAndStates() {
        val names = mapOf(site.id to "La Jolla")
        val working = DaySchedule.Working(listOf(DaySlot(LocalTime.of(8, 0), LocalTime.of(16, 0), site.id, "habitual")))
        assertEquals("08:00–16:00", EmpleadosScreens.dayText(working))
        assertEquals("08:00–16:00 · La Jolla", EmpleadosScreens.dayText(working, names, showSites = true))
        assertEquals("Libre", EmpleadosScreens.dayText(DaySchedule.Off(null)))
        assertEquals("Permiso", EmpleadosScreens.dayText(DaySchedule.Absent(AbsenceKind.Leave)))
    }
}
