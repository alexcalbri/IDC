package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.absence
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.off
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.slot
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.work
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Calculo del horario: habitual, turnos concretos, ausencias, semana ISO y validacion de franjas. */
class EmpleadosScheduleTest {
    private val employee = UUID.randomUUID()
    private val site = UUID.randomUUID()
    private val other = UUID.randomUUID()

    // Lunes 2026-11-16 ... domingo 2026-11-22.
    private val monday = LocalDate.of(2026, 11, 16)
    private val wednesday = LocalDate.of(2026, 11, 18)
    private val weekly = listOf(
        slot(employee, 1, "08:00", "16:00"),
        slot(employee, 3, "08:00", "12:00"),
        slot(employee, 3, "13:00", "17:00", other),
    )

    private fun resolve(date: LocalDate, shifts: List<Shift> = emptyList(), absences: List<Absence> = emptyList(), slots: List<WeeklySlot> = weekly) =
        EmpleadosSchedule.resolve(date, slots, shifts, absences, site)

    @Test
    fun usesTheHabitualHoursOfTheWeekday() {
        val day = assertIs<DaySchedule.Working>(resolve(monday))
        assertEquals(1, day.slots.size)
        assertEquals(LocalTime.of(8, 0), day.slots[0].start)
        assertEquals(LocalTime.of(16, 0), day.slots[0].end)
        assertEquals(EmpleadosSchedule.SourceHabitual, day.slots[0].source)
        assertNull(day.slots[0].shiftId)
    }

    @Test
    fun habitualSlotWithoutSiteUsesTheEmployeeSite() {
        val day = assertIs<DaySchedule.Working>(resolve(monday))
        assertEquals(site, day.slots[0].siteId)
    }

    @Test
    fun habitualSlotKeepsItsOwnSite() {
        val day = assertIs<DaySchedule.Working>(resolve(wednesday))
        assertEquals(listOf(site, other), day.slots.map { it.siteId })
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(13, 0)), day.slots.map { it.start })
    }

    @Test
    fun aDayWithoutAnythingIsOffWithoutSource() {
        val day = assertIs<DaySchedule.Off>(resolve(LocalDate.of(2026, 11, 17)))
        assertNull(day.source)
    }

    @Test
    fun aConcreteShiftReplacesTheHabitualHoursOfThatDay() {
        val shift = work(employee, monday, "14:00", "22:00", other)
        val day = assertIs<DaySchedule.Working>(resolve(monday, listOf(shift)))
        assertEquals(1, day.slots.size)
        assertEquals(LocalTime.of(14, 0), day.slots[0].start)
        assertEquals(other, day.slots[0].siteId)
        assertEquals(EmpleadosSchedule.SourceShift, day.slots[0].source)
        assertEquals(shift.id, day.slots[0].shiftId)
    }

    @Test
    fun shiftsOfOtherDatesDoNotAffectTheDay() {
        val day = assertIs<DaySchedule.Working>(resolve(monday, listOf(work(employee, wednesday, "14:00", "22:00"))))
        assertEquals(EmpleadosSchedule.SourceHabitual, day.slots[0].source)
    }

    @Test
    fun severalShiftsAreOrderedByStart() {
        val late = work(employee, monday, "17:00", "21:00")
        val early = work(employee, monday, "07:00", "11:00")
        val day = assertIs<DaySchedule.Working>(resolve(monday, listOf(late, early)))
        assertEquals(listOf(LocalTime.of(7, 0), LocalTime.of(17, 0)), day.slots.map { it.start })
    }

    @Test
    fun anOffShiftMakesTheDayFreeEvenIfTheHabitualHasHours() {
        val day = assertIs<DaySchedule.Off>(resolve(monday, listOf(off(employee, monday))))
        assertEquals(EmpleadosSchedule.SourceShift, day.source)
    }

    @Test
    fun anApprovedAbsenceBeatsEverything() {
        val vacation = absence(employee, monday.minusDays(1), monday.plusDays(2))
        val day = assertIs<DaySchedule.Absent>(resolve(monday, listOf(work(employee, monday, "14:00", "22:00")), listOf(vacation)))
        assertEquals(AbsenceKind.Vacation, day.kind)
    }

    @Test
    fun absenceCoversBothEndsAndNothingOutside() {
        val sick = absence(employee, monday, wednesday, kind = AbsenceKind.Sick)
        assertIs<DaySchedule.Absent>(resolve(monday, absences = listOf(sick)))
        assertIs<DaySchedule.Absent>(resolve(wednesday, absences = listOf(sick)))
        // El lunes siguiente ya no esta cubierto.
        assertIs<DaySchedule.Working>(resolve(LocalDate.of(2026, 11, 23), absences = listOf(sick)))
    }

    @Test
    fun pendingRejectedAndCancelledAbsencesDoNotCount() {
        listOf(AbsenceStatus.Pending, AbsenceStatus.Rejected, AbsenceStatus.Cancelled).forEach { status ->
            val day = resolve(monday, absences = listOf(absence(employee, monday, monday, status = status)))
            assertIs<DaySchedule.Working>(day, status.code)
        }
    }

    @Test
    fun weekStartIsTheIsoMonday() {
        assertEquals(monday, EmpleadosSchedule.weekStart(monday))
        assertEquals(monday, EmpleadosSchedule.weekStart(wednesday))
        assertEquals(monday, EmpleadosSchedule.weekStart(LocalDate.of(2026, 11, 22)))
        assertEquals(LocalDate.of(2026, 11, 23), EmpleadosSchedule.weekStart(LocalDate.of(2026, 11, 23)))
        // Cruza el cambio de ano.
        assertEquals(LocalDate.of(2025, 12, 29), EmpleadosSchedule.weekStart(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun parseTimeAcceptsValidClockTimes() {
        assertEquals(LocalTime.of(8, 0), EmpleadosSchedule.parseTime("08:00"))
        assertEquals(LocalTime.of(8, 5), EmpleadosSchedule.parseTime("8:05"))
        assertEquals(LocalTime.of(0, 0), EmpleadosSchedule.parseTime("00:00"))
        assertEquals(LocalTime.of(23, 59), EmpleadosSchedule.parseTime(" 23:59 "))
    }

    @Test
    fun parseTimeRejectsEverythingElse() {
        listOf("", "  ", "24:00", "12:60", "8", "8:0", "08.00", "8am", "ab:cd", "08:00:00", "-1:00", null).forEach {
            assertNull(EmpleadosSchedule.parseTime(it), "'$it'")
        }
    }

    @Test
    fun formatsTimesAndRanges() {
        assertEquals("08:05", EmpleadosSchedule.formatTime(LocalTime.of(8, 5)))
        assertEquals("22:00–06:00", EmpleadosSchedule.formatRange(LocalTime.of(22, 0), LocalTime.of(6, 0)))
    }

    private fun t(text: String) = LocalTime.parse(text)

    @Test
    fun overlappingSlotsOfTheSameDayAreDetected() {
        assertTrue(EmpleadosSchedule.overlaps(t("08:00"), t("12:00"), t("11:00"), t("15:00")))
        assertTrue(EmpleadosSchedule.overlaps(t("08:00"), t("12:00"), t("09:00"), t("10:00")))
        assertFalse(EmpleadosSchedule.overlaps(t("08:00"), t("12:00"), t("12:00"), t("16:00")))
        assertFalse(EmpleadosSchedule.overlaps(t("08:00"), t("12:00"), t("13:00"), t("16:00")))
    }

    @Test
    fun anOvernightSlotCountsUntilMidnightOfItsOwnDay() {
        assertTrue(EmpleadosSchedule.overlaps(t("22:00"), t("06:00"), t("23:00"), t("23:30")))
        assertFalse(EmpleadosSchedule.overlaps(t("22:00"), t("06:00"), t("07:00"), t("09:00")))
        assertFalse(EmpleadosSchedule.overlaps(t("22:00"), t("06:00"), t("08:00"), t("21:00")))
    }

    @Test
    fun firstOverlapReturnsTheIndexesOrNull() {
        assertNull(EmpleadosSchedule.firstOverlap(listOf(t("08:00") to t("12:00"), t("13:00") to t("17:00"))))
        assertEquals(0 to 2, EmpleadosSchedule.firstOverlap(listOf(t("08:00") to t("12:00"), t("13:00") to t("17:00"), t("11:00") to t("14:00"))))
    }

    @Test
    fun slotsProblemChecksMaxEqualHoursAndOverlap() {
        val fine = listOf(t("08:00") to t("10:00"), t("11:00") to t("12:00"))
        assertNull(EmpleadosSchedule.slotsProblem(fine, 4))
        assertNotNull(EmpleadosSchedule.slotsProblem(List(5) { t("0$it:00") to t("0$it:30") }, 4))
        assertNotNull(EmpleadosSchedule.slotsProblem(listOf(t("08:00") to t("08:00")), 4))
        assertNotNull(EmpleadosSchedule.slotsProblem(listOf(t("08:00") to t("12:00"), t("11:00") to t("13:00")), 4))
    }

    @Test
    fun atTimeContainmentTreatsOvernightSlotsFromTheirStart() {
        val day = DaySlot(t("08:00"), t("16:00"), null, EmpleadosSchedule.SourceHabitual)
        assertTrue(EmpleadosSchedule.containsFromStart(day, t("08:00")))
        assertTrue(EmpleadosSchedule.containsFromStart(day, t("15:59")))
        assertFalse(EmpleadosSchedule.containsFromStart(day, t("16:00")))
        assertFalse(EmpleadosSchedule.containsFromStart(day, t("07:59")))
        val night = DaySlot(t("22:00"), t("06:00"), null, EmpleadosSchedule.SourceHabitual)
        assertTrue(night.overnight)
        assertTrue(EmpleadosSchedule.containsFromStart(night, t("23:30")))
        // La madrugada de este mismo dia pertenece al turno del dia anterior.
        assertFalse(EmpleadosSchedule.containsFromStart(night, t("03:00")))
        assertTrue(EmpleadosSchedule.overnightTailContains(night, t("03:00")))
        assertFalse(EmpleadosSchedule.overnightTailContains(night, t("06:00")))
        assertFalse(EmpleadosSchedule.overnightTailContains(day, t("03:00")))
    }

    @Test
    fun scheduleDataResolvesPerEmployee() {
        val data = ScheduleData(
            weekly = mapOf(employee to weekly),
            shifts = mapOf(employee to listOf(off(employee, monday))),
            absences = emptyMap(),
        )
        assertIs<DaySchedule.Off>(data.resolve(employee, monday, site))
        assertIs<DaySchedule.Working>(data.resolve(employee, wednesday, site))
        // Otro empleado sin datos: libre.
        assertIs<DaySchedule.Off>(data.resolve(UUID.randomUUID(), monday, null))
    }
}
