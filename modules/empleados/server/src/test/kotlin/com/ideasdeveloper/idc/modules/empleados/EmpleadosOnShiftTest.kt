package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.absence
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.employee
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.scope
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.slot
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.work
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `computeOnShift` (calculo puro de quien esta de turno), solapes entre dias y la regla de no aprobar las propias ausencias. */
class EmpleadosOnShiftTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val tuesday = monday.plusDays(1)
    private val siteA = UUID.randomUUID()
    private val siteB = UUID.randomUUID()
    private val names = mapOf(siteA to "La Jolla", siteB to "Del Mar")

    private fun t(text: String) = LocalTime.parse(text)

    private fun data(
        weekly: List<WeeklySlot> = emptyList(),
        shifts: List<Shift> = emptyList(),
        absences: List<Absence> = emptyList(),
    ) = ScheduleData(weekly.groupBy { it.employeeId }, shifts.groupBy { it.employeeId }, absences.groupBy { it.employeeId })

    private fun on(
        date: LocalDate,
        employees: List<Employee>,
        data: ScheduleData,
        siteId: UUID? = null,
        at: LocalTime? = null,
        users: Map<UUID, UUID> = emptyMap(),
    ) = EmpleadosExternal.computeOnShift(date, siteId, at, employees, data, names, users)

    @Test
    fun withoutATimeEveryoneWorkingTheDateAppearsOnce() {
        val ana = employee(name = "Ana", siteId = siteA, site = "La Jolla")
        val beto = employee(name = "Beto")
        val result = on(monday, listOf(ana, beto), data(weekly = listOf(slot(ana.id, 1, "08:00", "12:00"), slot(ana.id, 1, "13:00", "17:00"), slot(beto.id, 2, "08:00", "16:00"))))
        assertEquals(listOf(ana.id), result.map { it.employeeId })
        // Sin hora: la primera franja del dia.
        assertEquals(t("08:00"), result.single().start)
        assertEquals("La Jolla", result.single().site)
        assertEquals("habitual", result.single().source)
    }

    @Test
    fun withATimeOnlyTheSlotThatContainsItCounts() {
        val ana = employee(name = "Ana")
        val d = data(weekly = listOf(slot(ana.id, 1, "08:00", "12:00"), slot(ana.id, 1, "13:00", "17:00")))
        assertEquals(t("13:00"), on(monday, listOf(ana), d, at = t("14:00")).single().start)
        assertEquals(t("08:00"), on(monday, listOf(ana), d, at = t("08:00")).single().start)
        assertTrue(on(monday, listOf(ana), d, at = t("12:30")).isEmpty())
        assertTrue(on(monday, listOf(ana), d, at = t("17:00")).isEmpty())
        assertTrue(on(monday, listOf(ana), d, at = t("07:59")).isEmpty())
    }

    @Test
    fun theTailOfYesterdaysOvernightShiftCountsAtDawn() {
        val ana = employee(name = "Ana")
        val d = data(weekly = listOf(slot(ana.id, 1, "22:00", "06:00")))
        // Martes a las 03:00: sigue en el turno que empezo el lunes a las 22:00.
        val result = on(tuesday, listOf(ana), d, at = t("03:00"))
        assertEquals(1, result.size)
        assertEquals(t("22:00"), result.single().start)
        assertEquals(t("06:00"), result.single().end)
        assertTrue(on(tuesday, listOf(ana), d, at = t("06:00")).isEmpty())
        // El lunes a las 03:00 no: la cola es del domingo y no hay turno el domingo.
        assertTrue(on(monday, listOf(ana), d, at = t("03:00")).isEmpty())
        // El lunes a las 23:00 si, por el inicio del turno de ese dia.
        assertEquals(1, on(monday, listOf(ana), d, at = t("23:00")).size)
    }

    @Test
    fun anApprovedAbsenceTodayDoesNotCancelYesterdaysNightTail() {
        val ana = employee(name = "Ana")
        val d = data(
            weekly = listOf(slot(ana.id, 1, "22:00", "06:00")),
            absences = listOf(absence(ana.id, tuesday, tuesday)),
        )
        // Trabajo la noche del lunes: a las 03:00 del martes sigue trabajando aunque el martes sea de vacaciones.
        assertEquals(1, on(tuesday, listOf(ana), d, at = t("03:00")).size)
        // Pero una ausencia el dia del turno si lo anula.
        val absentMonday = data(weekly = listOf(slot(ana.id, 1, "22:00", "06:00")), absences = listOf(absence(ana.id, monday, monday)))
        assertTrue(on(tuesday, listOf(ana), absentMonday, at = t("03:00")).isEmpty())
        assertTrue(on(monday, listOf(ana), absentMonday, at = t("23:00")).isEmpty())
    }

    @Test
    fun theDayOfTheAbsenceHasNobodyWithoutATime() {
        val ana = employee(name = "Ana")
        val d = data(weekly = listOf(slot(ana.id, 1, "08:00", "16:00")), absences = listOf(absence(ana.id, monday, monday.plusDays(3))))
        assertTrue(on(monday, listOf(ana), d).isEmpty())
    }

    @Test
    fun theSiteOfTheSlotWinsOverTheSiteOfTheRecord() {
        val ana = employee(name = "Ana", siteId = siteA, site = "La Jolla")
        val d = data(weekly = listOf(slot(ana.id, 1, "08:00", "16:00", siteB)))
        assertEquals(1, on(monday, listOf(ana), d, siteId = siteB).size)
        assertEquals("Del Mar", on(monday, listOf(ana), d, siteId = siteB).single().site)
        assertTrue(on(monday, listOf(ana), d, siteId = siteA).isEmpty())
    }

    @Test
    fun aSlotWithoutSiteUsesTheSiteOfTheRecord() {
        val ana = employee(name = "Ana", siteId = siteA, site = "La Jolla")
        val d = data(weekly = listOf(slot(ana.id, 1, "08:00", "16:00")))
        assertEquals(1, on(monday, listOf(ana), d, siteId = siteA).size)
        assertTrue(on(monday, listOf(ana), d, siteId = siteB).isEmpty())
    }

    @Test
    fun inactiveEmployeesNeverAppear() {
        val gone = employee(name = "Baja", status = EmployeeStatus.Inactive)
        val d = data(weekly = listOf(slot(gone.id, 1, "08:00", "16:00")))
        assertTrue(on(monday, listOf(gone), d).isEmpty())
    }

    @Test
    fun aConcreteShiftReplacesTheHabitualOneAndIsMarked() {
        val ana = employee(name = "Ana")
        val d = data(weekly = listOf(slot(ana.id, 1, "08:00", "16:00")), shifts = listOf(work(ana.id, monday, "14:00", "22:00")))
        val result = on(monday, listOf(ana), d).single()
        assertEquals(t("14:00"), result.start)
        assertEquals("shift", result.source)
    }

    @Test
    fun resultsAreOrderedByStartThenNameAndKeepTheLinkedUser() {
        val ana = employee(name = "Ana")
        val beto = employee(name = "Beto")
        val cora = employee(name = "Cora")
        val account = UUID.randomUUID()
        val d = data(weekly = listOf(slot(cora.id, 1, "06:00", "14:00"), slot(beto.id, 1, "08:00", "16:00"), slot(ana.id, 1, "08:00", "16:00")))
        val result = on(monday, listOf(ana, beto, cora), d, users = mapOf(ana.id to account))
        assertEquals(listOf("Cora", "Ana", "Beto"), result.map { it.name })
        assertEquals(account, result.first { it.name == "Ana" }.userId)
        assertNull(result.first { it.name == "Beto" }.userId)
    }

    // ---- solapes entre dias ----

    private fun daySlot(start: String, end: String) = DaySlot(t(start), t(end), null, "habitual")

    @Test
    fun anOvernightTailOfYesterdayBlocksAnEarlyStart() {
        val previous = listOf(daySlot("22:00", "06:00"))
        assertNotNull(EmpleadosSchedule.crossDayProblem(t("05:00"), t("09:00"), previous, emptyList()))
        assertNull(EmpleadosSchedule.crossDayProblem(t("06:00"), t("09:00"), previous, emptyList()))
        assertNull(EmpleadosSchedule.crossDayProblem(t("08:00"), t("16:00"), previous, emptyList()))
    }

    @Test
    fun aDaytimeSlotYesterdayNeverBlocks() {
        assertNull(EmpleadosSchedule.crossDayProblem(t("00:00"), t("09:00"), listOf(daySlot("08:00", "16:00")), emptyList()))
    }

    @Test
    fun anOvernightSlotCannotRunIntoTomorrowsStart() {
        val next = listOf(daySlot("05:00", "13:00"))
        assertNotNull(EmpleadosSchedule.crossDayProblem(t("22:00"), t("06:00"), emptyList(), next))
        assertNull(EmpleadosSchedule.crossDayProblem(t("22:00"), t("05:00"), emptyList(), next))
        // Un turno de dia no tiene cola.
        assertNull(EmpleadosSchedule.crossDayProblem(t("08:00"), t("16:00"), emptyList(), next))
    }

    // ---- autoaprobacion ----

    private val me = UUID.randomUUID()

    @Test
    fun nobodyDecidesTheirOwnAbsenceExceptOwners() {
        val shifter = scope(myEmployeeId = me, shifts = true)
        assertFalse(shifter.mayDecideAbsenceOf(me))
        assertTrue(shifter.mayDecideAbsenceOf(UUID.randomUUID()))
        assertFalse(scope(myEmployeeId = me, manage = true).mayDecideAbsenceOf(me))
        assertTrue(scope(myEmployeeId = me, owner = true).mayDecideAbsenceOf(me))
        // El dueno del servidor no tiene ficha: puede decidir cualquiera.
        assertTrue(scope(myEmployeeId = null, owner = true).mayDecideAbsenceOf(me))
    }

    @Test
    fun ownersAreManagersToo() {
        val owner = scope(owner = true)
        assertTrue(owner.canManage && owner.canShifts && owner.owner)
    }
}
