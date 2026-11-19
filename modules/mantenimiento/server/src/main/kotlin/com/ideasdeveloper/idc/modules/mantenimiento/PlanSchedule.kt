package com.ideasdeveloper.idc.modules.mantenimiento

import java.time.LocalDate

/** Recurrencia de los planes preventivos. Reglas puras, sin base de datos. */
object PlanSchedule {
    const val MaxSteps = 100_000

    /** La fecha siguiente a [date]: +1 mes, +3 meses, +1 año (java.time recorta el fin de mes) o +[intervalDays] días. */
    fun advance(date: LocalDate, frequency: PlanFrequency, intervalDays: Int?): LocalDate = when (frequency) {
        PlanFrequency.Monthly -> date.plusMonths(1)
        PlanFrequency.Quarterly -> date.plusMonths(3)
        PlanFrequency.Yearly -> date.plusYears(1)
        PlanFrequency.EveryNDays -> date.plusDays((intervalDays ?: 1).coerceIn(1, PlanInput.MaxIntervalDays).toLong())
    }

    /**
     * La ocurrencia número [k] contada desde [anchor] (siempre desde el ancla, no encadenada): así el fin de mes no se arrastra
     * (ancla 31-ene: 28-feb, 31-mar, 30-abr...).
     */
    fun occurrence(anchor: LocalDate, frequency: PlanFrequency, intervalDays: Int?, k: Int): LocalDate = when (frequency) {
        PlanFrequency.Monthly -> anchor.plusMonths(k.toLong())
        PlanFrequency.Quarterly -> anchor.plusMonths(3L * k)
        PlanFrequency.Yearly -> anchor.plusYears(k.toLong())
        PlanFrequency.EveryNDays -> anchor.plusDays((intervalDays ?: 1).coerceIn(1, PlanInput.MaxIntervalDays).toLong() * k)
    }

    /** Toca crear la orden: el plan está activo, tiene fecha, la anticipación ya llegó y no hay una orden abierta. */
    fun due(nextDue: LocalDate?, leadDays: Int, today: LocalDate, hasOpenOrder: Boolean): Boolean =
        nextDue != null && !hasOpenOrder && !nextDue.minusDays(leadDays.toLong()).isAfter(today)

    /**
     * La próxima fecha tras generar la orden de [nextDue]. Calendario fijo: la menor ocurrencia contada desde [anchor] (por defecto
     * [nextDue]) que sea posterior a [nextDue] y no esté en el pasado (las fechas perdidas no se crean). Desde el último cierre: null,
     * la fecha sale al cerrar o cancelar la orden.
     */
    fun afterGeneration(
        nextDue: LocalDate,
        frequency: PlanFrequency,
        intervalDays: Int?,
        mode: ScheduleMode,
        today: LocalDate,
        anchor: LocalDate? = null,
    ): LocalDate? {
        if (mode == ScheduleMode.Floating) return null
        val base = anchor ?: nextDue
        var k = 1
        var next = occurrence(base, frequency, intervalDays, k)
        while ((!next.isAfter(nextDue) || next.isBefore(today)) && k < MaxSteps) {
            k++
            next = occurrence(base, frequency, intervalDays, k)
        }
        return next
    }

    /** Plan "desde el último cierre": la próxima fecha cuenta desde el día en que se cerró la orden (y pasa a ser el ancla). */
    fun afterClose(closedOn: LocalDate, frequency: PlanFrequency, intervalDays: Int?): LocalDate = advance(closedOn, frequency, intervalDays)

    /** Plan "desde el último cierre" cuya orden se canceló: la cuenta empieza hoy. */
    fun afterCancel(today: LocalDate, frequency: PlanFrequency, intervalDays: Int?): LocalDate = advance(today, frequency, intervalDays)
}
