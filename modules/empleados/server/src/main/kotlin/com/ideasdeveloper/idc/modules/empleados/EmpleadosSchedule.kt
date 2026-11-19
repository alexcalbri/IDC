package com.ideasdeveloper.idc.modules.empleados

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Una franja de trabajo de un dia ya calculada. [source] es "habitual" o "shift"; [shiftId] solo si viene de un turno concreto. */
data class DaySlot(val start: LocalTime, val end: LocalTime, val siteId: UUID?, val source: String, val shiftId: UUID? = null) {
    /** La franja pasa de la medianoche (termina al dia siguiente). */
    val overnight: Boolean get() = end < start
}

/** El horario de un empleado en una fecha. */
sealed class DaySchedule {
    /** Una ausencia aprobada que cubre la fecha: gana a todo lo demas. */
    data class Absent(val kind: AbsenceKind) : DaySchedule()

    data class Working(val slots: List<DaySlot>) : DaySchedule()

    /** Libre; [source] es "shift" si lo dice un turno concreto y null si simplemente no hay horario. */
    data class Off(val source: String?) : DaySchedule()
}

/** Calculo puro del horario: horario habitual, turnos concretos y ausencias aprobadas. Sin base de datos. */
object EmpleadosSchedule {
    const val SourceHabitual = "habitual"
    const val SourceShift = "shift"

    private val TimePattern = Regex("([01]?\\d|2[0-3]):([0-5]\\d)")

    /** El lunes de la semana ISO de [date]. */
    fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    /** "HH:mm" o "H:mm" de 00:00 a 23:59; cualquier otra cosa es null. */
    fun parseTime(text: String?): LocalTime? {
        val match = TimePattern.matchEntire(text?.trim().orEmpty()) ?: return null
        return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    fun formatRange(start: LocalTime, end: LocalTime): String = "${formatTime(start)}–${formatTime(end)}"

    /**
     * El horario de un empleado en [date]. Todo lo recibido es de ese empleado:
     * 1. una ausencia aprobada que cubre la fecha -> [DaySchedule.Absent];
     * 2. si hay turnos en la fecha, sus turnos de trabajo (por hora) o libre si solo hay dias libres;
     * 3. si no, las franjas habituales del dia de la semana (la sede vacia es [defaultSite]);
     * 4. si no hay nada, libre sin fuente.
     */
    fun resolve(date: LocalDate, weekly: List<WeeklySlot>, shifts: List<Shift>, absences: List<Absence>, defaultSite: UUID?): DaySchedule {
        absences.firstOrNull { it.status == AbsenceStatus.Approved && it.covers(date) }?.let { return DaySchedule.Absent(it.kind) }
        val ofDate = shifts.filter { it.date == date }
        if (ofDate.isNotEmpty()) {
            val work = ofDate.filter { it.kind == ShiftKind.Work }.mapNotNull { shift ->
                val start = shift.start ?: return@mapNotNull null
                val end = shift.end ?: return@mapNotNull null
                DaySlot(start, end, shift.siteId ?: defaultSite, SourceShift, shift.id)
            }.sortedBy { it.start }
            return if (work.isEmpty()) DaySchedule.Off(SourceShift) else DaySchedule.Working(work)
        }
        val habitual = weekly.filter { it.weekday == date.dayOfWeek.value }
            .sortedWith(compareBy({ it.start }, { it.position }))
            .map { DaySlot(it.start, it.end, it.siteId ?: defaultSite, SourceHabitual) }
        return if (habitual.isEmpty()) DaySchedule.Off(null) else DaySchedule.Working(habitual)
    }

    /** Minutos desde la medianoche en que la franja termina dentro del mismo dia (una nocturna cuenta hasta las 24:00). */
    private fun endMinutes(start: LocalTime, end: LocalTime): Int = if (end > start) end.toSecondOfDay() / 60 else 24 * 60

    /** Si dos franjas del mismo dia se solapan (la parte nocturna despues de medianoche pertenece al dia siguiente y no cuenta). */
    fun overlaps(aStart: LocalTime, aEnd: LocalTime, bStart: LocalTime, bEnd: LocalTime): Boolean {
        val aS = aStart.toSecondOfDay() / 60
        val bS = bStart.toSecondOfDay() / 60
        return aS < endMinutes(bStart, bEnd) && bS < endMinutes(aStart, aEnd)
    }

    /** Indices (en [times]) de la primera pareja de franjas que se solapan, o null. */
    fun firstOverlap(times: List<Pair<LocalTime, LocalTime>>): Pair<Int, Int>? {
        for (i in times.indices) {
            for (j in i + 1 until times.size) {
                if (overlaps(times[i].first, times[i].second, times[j].first, times[j].second)) return i to j
            }
        }
        return null
    }

    /**
     * Solapes entre dias de una franja nueva [start]-[end]: la cola de madrugada de una franja nocturna del dia anterior
     * ([previousDay], ocupa de 00:00 a su fin) no puede pisar el inicio de la nueva, y la cola de la nueva, si es nocturna,
     * no puede pisar el inicio de una franja del dia siguiente ([nextDay]). Mensaje del problema, o null. Los solapes del mismo
     * dia los revisa [slotsProblem].
     */
    fun crossDayProblem(start: LocalTime, end: LocalTime, previousDay: List<DaySlot>, nextDay: List<DaySlot>): String? = when {
        previousDay.any { it.overnight && start < it.end } -> "Se solapa con la madrugada de un turno nocturno del día anterior."
        end < start && nextDay.any { it.start < end } -> "La madrugada de este turno nocturno se solapa con el inicio del día siguiente."
        else -> null
    }

    /** Si la franja contiene la hora [at]: las nocturnas valen desde su inicio (la parte de la madrugada es del dia siguiente). */
    fun containsFromStart(slot: DaySlot, at: LocalTime): Boolean =
        if (slot.end > slot.start) at >= slot.start && at < slot.end else at >= slot.start

    /** Si una franja nocturna del dia anterior sigue en marcha a la hora [at] de la madrugada. */
    fun overnightTailContains(slot: DaySlot, at: LocalTime): Boolean = slot.overnight && at < slot.end

    /** Mensaje de la primera regla que falla en las franjas de un mismo dia (maximo, horas distintas y sin solapes), o null. */
    fun slotsProblem(times: List<Pair<LocalTime, LocalTime>>, max: Int): String? = when {
        times.size > max -> "Un dia admite hasta $max franjas."
        times.any { it.first == it.second } -> "La hora de inicio y la de fin no pueden ser iguales."
        firstOverlap(times) != null -> "Las franjas de un mismo dia no pueden solaparse."
        else -> null
    }
}
