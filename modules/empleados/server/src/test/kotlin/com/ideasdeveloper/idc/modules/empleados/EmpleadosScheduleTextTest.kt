package com.ideasdeveloper.idc.modules.empleados

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Lectura de textos libres de dias y horas (espanol e ingles) de la extension de Monday. */
class EmpleadosScheduleTextTest {
    private val all = (1..7).toSet()
    private val weekdays = (1..5).toSet()

    private fun days(text: String) = EmpleadosScheduleText.parseDays(text)

    private fun hours(text: String) = EmpleadosScheduleText.parseHours(text)

    private fun range(start: String, end: String) = LocalTime.parse(start) to LocalTime.parse(end)

    // ---- dias ----

    @Test
    fun daysRangesInSpanish() {
        assertEquals(weekdays, days("Lunes a Viernes"))
        assertEquals(weekdays, days("lunes a viernes"))
        assertEquals(weekdays, days("LUNES A VIERNES"))
        assertEquals(weekdays, days("De lunes a viernes"))
        assertEquals(weekdays, days("Lun-Vie"))
        assertEquals(weekdays, days("Lun - Vie"))
        assertEquals(weekdays, days("L-V"))
        assertEquals(weekdays, days("L a V"))
        assertEquals(weekdays, days("Lunes a Viernes."))
        assertEquals(setOf(2, 3, 4), days("Martes a Jueves"))
        assertEquals(setOf(2, 3, 4), days("Mar-Jue"))
        assertEquals(all, days("Lunes a Domingo"))
        assertEquals(setOf(6, 7), days("S-D"))
    }

    @Test
    fun daysRangesInEnglish() {
        assertEquals(weekdays, days("Mon-Fri"))
        assertEquals(weekdays, days("Monday to Friday"))
        assertEquals(weekdays, days("Mon thru Fri"))
        assertEquals(weekdays, days("Monday through Friday"))
        assertEquals(setOf(2, 3, 4), days("Tue-Thu"))
    }

    @Test
    fun daysLists() {
        assertEquals(setOf(1, 3), days("Lunes, Miércoles"))
        assertEquals(setOf(1, 3, 5), days("Lunes, Miércoles y Viernes"))
        assertEquals(setOf(1, 3, 5), days("Lun, Mié, Vie"))
        assertEquals(setOf(1, 2, 3, 4, 5), days("L, M, X, J, V"))
        assertEquals(setOf(1, 3, 5), days("Mon, Wed, Fri"))
        assertEquals(setOf(1, 3, 5), days("Mon/Wed/Fri"))
        assertEquals(setOf(2, 4), days("Tue and Thu"))
    }

    @Test
    fun daysMixedRangesAndLists() {
        assertEquals(setOf(1, 2, 3, 4, 5, 6), days("Lunes a Viernes y Sábado"))
        assertEquals(setOf(1, 2, 3, 4, 5, 7), days("Lun-Vie, Dom"))
    }

    @Test
    fun daysThatWrapAroundTheWeekend() {
        assertEquals(setOf(5, 6, 7, 1), days("Viernes a Lunes"))
        assertEquals(setOf(6, 7, 1), days("Sab-Lun"))
    }

    @Test
    fun daysAcceptPluralsAndAccents() {
        assertEquals(setOf(6, 7), days("Sábados y domingos"))
        assertEquals(setOf(3), days("Miércoles"))
        assertEquals(setOf(6), days("sabado"))
    }

    @Test
    fun daysPhrases() {
        assertEquals(all, days("Todos los días"))
        assertEquals(all, days("todos los dias"))
        assertEquals(all, days("Diario"))
        assertEquals(all, days("Every day"))
        assertEquals(all, days("Daily"))
        assertEquals(setOf(6, 7), days("Fines de semana"))
        assertEquals(setOf(6, 7), days("Fin de semana"))
        assertEquals(setOf(6, 7), days("Weekends"))
        assertEquals(weekdays, days("Entre semana"))
        assertEquals(weekdays, days("Weekdays"))
        assertEquals(weekdays, days("Días laborales"))
    }

    @Test
    fun daysItDoesNotUnderstandAreNull() {
        listOf("", "   ", "Hola", "Lunes a", "a Viernes", "Lunes 8-5", "8:00-17:00", "Todos excepto domingo", "Lunes a Viernes a Sábado a", "xyz", "Lunes y quizás").forEach {
            assertNull(days(it), "'$it'")
        }
    }

    @Test
    fun shortWordsEndingInSAreNotDays() {
        // "mas", "dos" y "sus" perderian la s y parecerian "ma", "do" y "su": solo cuenta un plural de 3+ letras.
        listOf("mas", "dos", "sus", "mis", "los", "Lunes y mas", "dos dias").forEach { assertNull(days(it), it) }
        assertEquals(setOf(1, 2), days("Mondays, Tuesdays"))
        assertEquals(setOf(6, 7), days("sabados y domingos"))
        assertEquals(setOf(2), days("Tues"))
    }

    @Test
    fun daysAreSortedAndWithoutRepeats() {
        assertEquals(listOf(1, 3), days("Miércoles, Lunes, Lunes")?.toList()?.sorted())
        assertEquals(setOf(1), days("Lunes, lun, Mon"))
    }

    // ---- horas ----

    @Test
    fun hoursIn24HourFormat() {
        assertEquals(range("08:00", "17:00"), hours("8:00-17:00"))
        assertEquals(range("08:00", "16:00"), hours("08:00 – 16:00"))
        assertEquals(range("08:00", "16:00"), hours("08:00 — 16:00"))
        assertEquals(range("06:00", "14:00"), hours("6:00 a 14:00"))
        assertEquals(range("14:00", "22:00"), hours("14:00-22:00"))
        assertEquals(range("08:30", "17:00"), hours("8:30 - 17:00"))
        assertEquals(range("08:00", "17:00"), hours("de 8:00 a 17:00 hrs"))
        assertEquals(range("08:00", "17:00"), hours("8:00h-17:00h"))
        assertEquals(range("08:30", "17:00"), hours("8h30 - 17h00"))
        assertEquals(range("08:00", "16:00"), hours("08:00 hasta 16:00"))
    }

    @Test
    fun hoursWithAmAndPm() {
        assertEquals(range("08:00", "17:00"), hours("8am - 5pm"))
        assertEquals(range("08:00", "17:00"), hours("8AM-5PM"))
        assertEquals(range("08:00", "17:00"), hours("8:00 AM - 5:00 PM"))
        assertEquals(range("08:00", "17:00"), hours("8:00 a.m. a 5:00 p.m."))
        assertEquals(range("08:00", "17:00"), hours("8 a.m. - 5 p.m."))
        assertEquals(range("07:00", "15:00"), hours("7am to 3pm"))
        assertEquals(range("08:00", "16:00"), hours("from 8am to 4pm"))
        assertEquals(range("00:00", "08:00"), hours("12am-8am"))
        assertEquals(range("12:00", "20:00"), hours("12pm-8pm"))
    }

    @Test
    fun hoursWithOneSuffixInferTheOther() {
        assertEquals(range("09:00", "17:00"), hours("9-5pm"))
        assertEquals(range("10:00", "14:00"), hours("10am-2"))
        assertEquals(range("08:00", "17:00"), hours("8:00 to 5:00 pm"))
        assertEquals(range("11:00", "13:00"), hours("11-1pm"))
        assertEquals(range("12:00", "20:00"), hours("12:00 a 8:00 pm"))
    }

    @Test
    fun hoursWithoutSuffixAndSmallNumbersAreAfternoon() {
        assertEquals(range("08:00", "17:00"), hours("8-5"))
        assertEquals(range("09:00", "18:00"), hours("9 a 6"))
        assertEquals(range("08:00", "17:00"), hours("8:00-5:00"))
    }

    @Test
    fun overnightHours() {
        assertEquals(range("22:00", "06:00"), hours("22:00-06:00"))
        assertEquals(range("22:00", "06:00"), hours("10pm-6am"))
        assertEquals(range("23:00", "07:00"), hours("23:00 a 7:00"))
    }

    @Test
    fun hoursItDoesNotUnderstandAreNull() {
        listOf("", "  ", "abc", "8:00", "8:00-8:00", "25:00-26:00", "8:60-9:00", "mañana", "8-", "-5", "8:00-17:00-19:00", "13pm-5pm", "0am-5am").forEach {
            assertNull(hours(it), "'$it'")
        }
    }

    @Test
    fun hoursKeepMinutes() {
        assertEquals(range("07:45", "16:15"), hours("7:45-16:15"))
        assertEquals(range("07:45", "16:15"), hours("7:45am - 4:15pm"))
    }
}
