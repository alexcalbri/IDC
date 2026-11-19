package com.ideasdeveloper.idc.modules.mantenimiento

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recurrencia de los planes (`PlanSchedule`), pura. */
class PlanScheduleTest {
    private fun date(text: String) = LocalDate.parse(text)

    @Test
    fun advanceAddsOneMonthThreeMonthsOneYearOrNDays() {
        assertEquals(date("2026-12-15"), PlanSchedule.advance(date("2026-11-15"), PlanFrequency.Monthly, null))
        assertEquals(date("2027-02-15"), PlanSchedule.advance(date("2026-11-15"), PlanFrequency.Quarterly, null))
        assertEquals(date("2027-11-15"), PlanSchedule.advance(date("2026-11-15"), PlanFrequency.Yearly, null))
        assertEquals(date("2026-11-22"), PlanSchedule.advance(date("2026-11-15"), PlanFrequency.EveryNDays, 7))
    }

    @Test
    fun monthEndIsClippedToTheLastDayOfTheNextMonth() {
        assertEquals(date("2026-02-28"), PlanSchedule.advance(date("2026-01-31"), PlanFrequency.Monthly, null))
        assertEquals(date("2028-02-29"), PlanSchedule.advance(date("2028-01-31"), PlanFrequency.Monthly, null))
        assertEquals(date("2026-11-30"), PlanSchedule.advance(date("2026-08-31"), PlanFrequency.Quarterly, null))
        assertEquals(date("2025-02-28"), PlanSchedule.advance(date("2024-02-29"), PlanFrequency.Yearly, null))
    }

    @Test
    fun everyNDaysUsesAtLeastOneDay() {
        assertEquals(date("2026-11-16"), PlanSchedule.advance(date("2026-11-15"), PlanFrequency.EveryNDays, null))
        assertEquals(date("2026-11-16"), PlanSchedule.advance(date("2026-11-15"), PlanFrequency.EveryNDays, 0))
    }

    @Test
    fun fixedPlanAdvancesOnceWhenNothingWasMissed() {
        val next = PlanSchedule.afterGeneration(date("2026-11-20"), PlanFrequency.Monthly, null, ScheduleMode.Fixed, date("2026-11-18"))
        assertEquals(date("2026-12-20"), next)
    }

    @Test
    fun fixedPlanSkipsTheDatesMissedDuringALongDelay() {
        // Atraso grande: el plan estaba en el 2026-01-10 y hoy es 2026-11-18, con frecuencia mensual.
        val next = PlanSchedule.afterGeneration(date("2026-01-10"), PlanFrequency.Monthly, null, ScheduleMode.Fixed, date("2026-11-18"))
        assertEquals(date("2026-12-10"), next)
        assertFalse(next!!.isBefore(date("2026-11-18")))
        // Cada 30 días tras casi un año de atraso: la fecha nunca queda en el pasado.
        val daily = PlanSchedule.afterGeneration(date("2025-12-01"), PlanFrequency.EveryNDays, 30, ScheduleMode.Fixed, date("2026-11-18"))!!
        assertFalse(daily.isBefore(date("2026-11-18")))
        assertTrue(daily.isBefore(date("2026-11-18").plusDays(31)))
    }

    @Test
    fun fixedPlanDueTodayStaysToday() {
        // La fecha avanzada igual a hoy no es pasado: no se avanza otra vez.
        val next = PlanSchedule.afterGeneration(date("2026-10-18"), PlanFrequency.Monthly, null, ScheduleMode.Fixed, date("2026-11-18"))
        assertEquals(date("2026-11-18"), next)
    }

    @Test
    fun floatingPlanWaitsForTheOrderToClose() {
        assertNull(PlanSchedule.afterGeneration(date("2026-11-20"), PlanFrequency.Monthly, null, ScheduleMode.Floating, date("2026-11-18")))
        assertEquals(date("2027-01-05"), PlanSchedule.afterClose(date("2026-12-05"), PlanFrequency.Monthly, null))
        assertEquals(date("2026-12-18"), PlanSchedule.afterCancel(date("2026-11-18"), PlanFrequency.Monthly, null))
        assertEquals(date("2026-12-18"), PlanSchedule.afterClose(date("2026-11-18"), PlanFrequency.EveryNDays, 30))
    }

    @Test
    fun fixedMonthlyFromMonthEndDoesNotDriftBecauseItCountsFromTheAnchor() {
        val anchor = date("2026-01-31")
        val today = date("2026-01-01")
        val first = PlanSchedule.afterGeneration(anchor, PlanFrequency.Monthly, null, ScheduleMode.Fixed, today, anchor)
        assertEquals(date("2026-02-28"), first)
        val second = PlanSchedule.afterGeneration(first!!, PlanFrequency.Monthly, null, ScheduleMode.Fixed, today, anchor)
        assertEquals(date("2026-03-31"), second)
        val third = PlanSchedule.afterGeneration(second!!, PlanFrequency.Monthly, null, ScheduleMode.Fixed, today, anchor)
        assertEquals(date("2026-04-30"), third)
    }

    @Test
    fun fixedYearlyFromLeapDayReturnsToTheLeapDay() {
        val anchor = date("2024-02-29")
        val today = date("2024-01-01")
        var due = anchor
        val seen = mutableListOf<LocalDate>()
        repeat(4) {
            due = PlanSchedule.afterGeneration(due, PlanFrequency.Yearly, null, ScheduleMode.Fixed, today, anchor)!!
            seen += due
        }
        assertEquals(listOf(date("2025-02-28"), date("2026-02-28"), date("2027-02-28"), date("2028-02-29")), seen)
    }

    @Test
    fun fixedQuarterlyAndEveryNDaysCountFromTheAnchor() {
        val today = date("2026-01-01")
        assertEquals(date("2026-11-30"), PlanSchedule.occurrence(date("2026-08-31"), PlanFrequency.Quarterly, null, 1))
        assertEquals(date("2027-02-28"), PlanSchedule.afterGeneration(date("2026-11-30"), PlanFrequency.Quarterly, null, ScheduleMode.Fixed, today, date("2026-08-31")))
        assertEquals(date("2026-03-02"), PlanSchedule.occurrence(date("2026-01-01"), PlanFrequency.EveryNDays, 20, 3))
    }

    @Test
    fun missedOccurrencesAreSkippedCountingFromTheAnchor() {
        // Ancla 31-ene, última generada 28-feb, hoy 15-jun: la siguiente es el 30-jun (no el 31-mar ni mayo).
        val next = PlanSchedule.afterGeneration(date("2026-02-28"), PlanFrequency.Monthly, null, ScheduleMode.Fixed, date("2026-06-15"), date("2026-01-31"))
        assertEquals(date("2026-06-30"), next)
    }

    @Test
    fun theStepCapStopsAnImpossiblyOldPlan() {
        val start = date("2000-01-01")
        val next = PlanSchedule.afterGeneration(start, PlanFrequency.EveryNDays, 1, ScheduleMode.Fixed, date("2999-01-01"))!!
        assertEquals(PlanSchedule.occurrence(start, PlanFrequency.EveryNDays, 1, PlanSchedule.MaxSteps), next)
        assertTrue(next.isBefore(date("2999-01-01")))
    }

    @Test
    fun floatingChainsFromTheCloseOrCancelDate() {
        // Cerrar el 31-ene con frecuencia mensual cuenta desde ese día.
        assertEquals(date("2026-02-28"), PlanSchedule.afterClose(date("2026-01-31"), PlanFrequency.Monthly, null))
        assertEquals(date("2026-04-15"), PlanSchedule.afterClose(date("2026-01-15"), PlanFrequency.Quarterly, null))
        assertEquals(date("2027-03-01"), PlanSchedule.afterClose(date("2026-03-01"), PlanFrequency.Yearly, null))
        assertEquals(date("2026-03-04"), PlanSchedule.afterClose(date("2026-02-25"), PlanFrequency.EveryNDays, 7))
        // Cancelar cuenta desde hoy, no desde la fecha del plan.
        assertEquals(date("2026-12-31"), PlanSchedule.afterCancel(date("2026-11-30"), PlanFrequency.Monthly, null).plusDays(1))
        assertEquals(date("2026-12-01"), PlanSchedule.afterCancel(date("2026-11-24"), PlanFrequency.EveryNDays, 7))
        // El plan flotante nunca calcula nada al generar.
        assertNull(PlanSchedule.afterGeneration(date("2026-01-31"), PlanFrequency.Monthly, null, ScheduleMode.Floating, date("2026-01-01"), date("2026-01-31")))
    }

    @Test
    fun generationStartsWhenTheLeadTimeArrivesAndNoOrderIsOpen() {
        val today = date("2026-11-18")
        assertTrue(PlanSchedule.due(date("2026-11-25"), 7, today, hasOpenOrder = false))
        assertTrue(PlanSchedule.due(date("2026-11-10"), 0, today, hasOpenOrder = false))
        assertFalse(PlanSchedule.due(date("2026-11-26"), 7, today, hasOpenOrder = false))
        assertFalse(PlanSchedule.due(date("2026-11-25"), 7, today, hasOpenOrder = true))
        assertFalse(PlanSchedule.due(null, 7, today, hasOpenOrder = false))
    }
}
