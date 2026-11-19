package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.slot
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Textos, destinatarios y objetivos de los avisos (sin base de datos). */
class EmpleadosNotifyTest {
    private val site = UUID.randomUUID()
    private val names = mapOf(site to "La Jolla")
    // Lunes 23 de noviembre de 2026.
    private val monday = LocalDate.of(2026, 11, 23)

    private fun working(start: String, end: String, siteId: UUID? = site) =
        DaySchedule.Working(listOf(DaySlot(LocalTime.parse(start), LocalTime.parse(end), siteId, EmpleadosSchedule.SourceShift)))

    @Test
    fun dayLabelIsAbbreviatedInSpanish() {
        assertEquals("lun 23 nov", EmpleadosNotify.dayLabel(monday))
        assertEquals("dom 1 mar", EmpleadosNotify.dayLabel(LocalDate.of(2026, 3, 1)))
        assertEquals("mié 18 nov", EmpleadosNotify.dayLabel(LocalDate.of(2026, 11, 18)))
    }

    @Test
    fun shiftTextShowsHoursAndSite() {
        assertEquals("lun 23 nov: 08:00–16:00 · La Jolla", EmpleadosNotify.shiftText(monday, working("08:00", "16:00"), names))
        assertEquals("lun 23 nov: 08:00–16:00", EmpleadosNotify.shiftText(monday, working("08:00", "16:00", null), names))
    }

    @Test
    fun shiftTextForSeveralSlotsOffAndAbsence() {
        val two = DaySchedule.Working(
            listOf(
                DaySlot(LocalTime.of(8, 0), LocalTime.of(12, 0), site, EmpleadosSchedule.SourceHabitual),
                DaySlot(LocalTime.of(13, 0), LocalTime.of(17, 0), null, EmpleadosSchedule.SourceHabitual),
            ),
        )
        assertEquals("lun 23 nov: 08:00–12:00 · La Jolla, 13:00–17:00", EmpleadosNotify.shiftText(monday, two, names))
        assertEquals("lun 23 nov: libre", EmpleadosNotify.shiftText(monday, DaySchedule.Off(EmpleadosSchedule.SourceShift), names))
        assertEquals("lun 23 nov: Vacaciones", EmpleadosNotify.shiftText(monday, DaySchedule.Absent(AbsenceKind.Vacation), names))
    }

    @Test
    fun weeklyTextListsSlotsByDay() {
        val employee = UUID.randomUUID()
        val text = EmpleadosNotify.weeklyText(listOf(slot(employee, 3, "08:00", "16:00", site), slot(employee, 1, "09:00", "17:00")), names)
        assertEquals("lun 09:00–17:00, mié 08:00–16:00 · La Jolla", text)
        assertEquals("sin horario habitual", EmpleadosNotify.weeklyText(emptyList(), names))
    }

    @Test
    fun onlyTodayAndFutureChangesNotify() {
        val today = LocalDate.of(2026, 11, 18)
        assertTrue(EmpleadosNotify.shouldNotify(today, today))
        assertTrue(EmpleadosNotify.shouldNotify(today.plusDays(1), today))
        assertFalse(EmpleadosNotify.shouldNotify(today.minusDays(1), today))
    }

    @Test
    fun theAuthorIsNeverNotifiedAndRecipientsAreUnique() {
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()
        assertEquals(listOf(other), EmpleadosNotify.recipients(listOf(me, other, other), me))
        assertEquals(listOf(me), EmpleadosNotify.recipients(listOf(me), null))
        assertTrue(EmpleadosNotify.recipients(listOf(me), me).isEmpty())
    }

    @Test
    fun mineTargetPointsAtTheIsoWeek() {
        val target = EmpleadosNotify.mineTarget(LocalDate.of(2026, 11, 26))
        assertEquals("mine", target.screen)
        assertEquals(mapOf("week" to "2026-11-23"), target.params)
        assertEquals("absences", EmpleadosNotify.AbsencesTarget.screen)
        assertTrue(EmpleadosNotify.AbsencesTarget.params.isEmpty())
    }

    // Sin notificador no se hace nada ni se toca la base de datos: un aviso nunca rompe la operacion.
    @Test
    fun withoutANotifierNothingHappens() {
        val db = EmpleadosFixtures.noDatabase()
        val id = UUID.randomUUID()
        val today = LocalDate.of(2026, 11, 18)
        EmpleadosNotify.shiftChanged(db, null, id, listOf(today), null, today)
        EmpleadosNotify.weeklyChanged(db, null, id, null, today)
        EmpleadosNotify.absenceRequested(db, null, "Ana", AbsenceInput(id, AbsenceKind.Vacation, today, today), null)
        EmpleadosNotify.absenceDecided(db, null, EmpleadosFixtures.absence(id, today, today), true, "", null)
    }
}
