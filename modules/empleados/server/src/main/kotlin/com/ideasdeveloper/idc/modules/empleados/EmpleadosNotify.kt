package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import com.ideasdeveloper.idc.server.modules.NotificationTarget
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Avisos en el centro de notificaciones del Core. Un aviso nunca rompe el cambio que lo causo: cada uno se crea dentro de
 * runCatching y un fallo solo se registra. El autor del cambio nunca recibe el aviso. La importacion no avisa.
 */
object EmpleadosNotify {
    private val log = LoggerFactory.getLogger(EmpleadosNotify::class.java)

    const val ShiftChangedTitle = "Tu turno cambió"
    const val WeeklyChangedTitle = "Tu horario habitual cambió"
    const val AbsenceRequestedTitle = "Solicitud de ausencia"
    const val AbsenceApprovedTitle = "Ausencia aprobada"
    const val AbsenceRejectedTitle = "Ausencia rechazada"
    private const val MaxText = 1000

    private val WeekdayNames = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
    private val MonthNames = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    /** "lun 24 nov". */
    fun dayLabel(date: LocalDate): String = "${WeekdayNames[date.dayOfWeek.value - 1]} ${date.dayOfMonth} ${MonthNames[date.monthValue - 1]}"

    /** El horario de un dia como lo ve el empleado: "08:00–16:00 · La Jolla", "libre" o "Vacaciones". */
    fun scheduleText(schedule: DaySchedule, siteNames: Map<UUID, String>): String = when (schedule) {
        is DaySchedule.Absent -> schedule.kind.label
        is DaySchedule.Off -> "libre"
        is DaySchedule.Working -> schedule.slots.joinToString(", ") { slot ->
            listOfNotNull(EmpleadosSchedule.formatRange(slot.start, slot.end), slot.siteId?.let { siteNames[it] }).joinToString(" · ")
        }
    }

    /** "lun 24 nov: 08:00–16:00 · La Jolla". */
    fun shiftText(date: LocalDate, schedule: DaySchedule, siteNames: Map<UUID, String>): String = "${dayLabel(date)}: ${scheduleText(schedule, siteNames)}"

    /** Resumen del horario habitual: "lun 08:00–16:00, mar 08:00–16:00". */
    fun weeklyText(slots: List<WeeklySlot>, siteNames: Map<UUID, String>): String {
        if (slots.isEmpty()) return "sin horario habitual"
        return slots.sortedWith(compareBy({ it.weekday }, { it.start })).joinToString(", ") { slot ->
            listOfNotNull("${WeekdayNames[slot.weekday - 1]} ${EmpleadosSchedule.formatRange(slot.start, slot.end)}", slot.siteId?.let { siteNames[it] }).joinToString(" · ")
        }
    }

    /** Solo se avisa de lo que afecta a hoy o al futuro. */
    fun shouldNotify(date: LocalDate, today: LocalDate): Boolean = !date.isBefore(today)

    /** Cuentas a avisar de un cambio: nunca el autor. */
    fun recipients(candidates: Collection<UUID>, byUserId: UUID?): List<UUID> = candidates.distinct().filter { it != byUserId }

    fun mineTarget(date: LocalDate) = NotificationTarget("mine", mapOf("week" to EmpleadosSchedule.weekStart(date).toString()))

    val AbsencesTarget = NotificationTarget("absences", emptyMap())

    /** Un turno de [date] cambio: el empleado recibe el horario vigente de ese dia (despues del cambio). */
    fun shiftChanged(tenant: Connection, notifier: ModuleNotifier?, employeeId: UUID, dates: Collection<LocalDate>, byUserId: UUID?, today: LocalDate) {
        if (notifier == null) return
        runCatching {
            val userId = EmpleadosRepository.userIdOf(tenant, employeeId) ?: return
            if (recipients(listOf(userId), byUserId).isEmpty()) return
            val names = EmpleadosRepository.catalogNames(tenant, Catalog.Sites)
            dates.distinct().filter { shouldNotify(it, today) }.sorted().forEach { date ->
                val (schedule, _) = EmpleadosRepository.daySchedule(tenant, employeeId, date)
                send(notifier, tenant, userId, ShiftChangedTitle, shiftText(date, schedule, names), mineTarget(date))
            }
        }.onFailure { log.warn("No se pudo preparar el aviso de cambio de turno ({}).", it.javaClass.simpleName) }
    }

    /** El horario habitual del empleado cambio. */
    fun weeklyChanged(tenant: Connection, notifier: ModuleNotifier?, employeeId: UUID, byUserId: UUID?, today: LocalDate) {
        if (notifier == null) return
        runCatching {
            val userId = EmpleadosRepository.userIdOf(tenant, employeeId) ?: return
            if (recipients(listOf(userId), byUserId).isEmpty()) return
            val names = EmpleadosRepository.catalogNames(tenant, Catalog.Sites)
            val slots = EmpleadosRepository.weekly(tenant, listOf(employeeId))
            send(notifier, tenant, userId, WeeklyChangedTitle, weeklyText(slots, names), mineTarget(today))
        }.onFailure { log.warn("No se pudo preparar el aviso de cambio del horario habitual ({}).", it.javaClass.simpleName) }
    }

    /** Se pidio una ausencia: la reciben quien gestiona turnos y los duenos (hasta 20), sin el autor. */
    fun absenceRequested(tenant: Connection, notifier: ModuleNotifier?, employeeName: String, absence: AbsenceInput, byUserId: UUID?) {
        if (notifier == null) return
        runCatching {
            val text = "$employeeName pidió ${absence.kind.label.lowercase()} del ${absence.startDate} al ${absence.endDate}"
            recipients(EmpleadosRepository.approvers(tenant), byUserId).forEach { send(notifier, tenant, it, AbsenceRequestedTitle, text, AbsencesTarget) }
        }.onFailure { log.warn("No se pudo preparar el aviso de ausencia pedida ({}).", it.javaClass.simpleName) }
    }

    /** Una ausencia se aprobo o rechazo: la recibe el empleado enlazado. */
    fun absenceDecided(tenant: Connection, notifier: ModuleNotifier?, absence: Absence, approved: Boolean, note: String, byUserId: UUID?) {
        if (notifier == null) return
        runCatching {
            val userId = EmpleadosRepository.userIdOf(tenant, absence.employeeId) ?: return
            if (recipients(listOf(userId), byUserId).isEmpty()) return
            val base = "${absence.kind.label} del ${absence.startDate} al ${absence.endDate}"
            val text = if (note.isBlank()) base else "$base. ${note.trim()}"
            send(notifier, tenant, userId, if (approved) AbsenceApprovedTitle else AbsenceRejectedTitle, text, AbsencesTarget)
        }.onFailure { log.warn("No se pudo preparar el aviso de ausencia decidida ({}).", it.javaClass.simpleName) }
    }

    private fun send(notifier: ModuleNotifier, tenant: Connection, userId: UUID, title: String, text: String, target: NotificationTarget) {
        runCatching { notifier.notify(tenant, userId, title, text.take(MaxText), target) }
            .onFailure { log.warn("No se pudo crear el aviso \"{}\" de empleados ({}).", title, it.javaClass.simpleName) }
    }
}
