package com.ideasdeveloper.idc.modules.empleados

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Estado de una ficha: la baja conserva la ficha y su historial pero deja de verse en el directorio basico. */
enum class EmployeeStatus(val code: String, val label: String) {
    Active("active", "Activo"),
    Inactive("inactive", "Baja");

    companion object {
        fun fromCode(code: String?): EmployeeStatus? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }

        /** Acepta la etiqueta visible (sin distinguir mayusculas) y, por comodidad, el codigo. */
        fun fromLabel(label: String?): EmployeeStatus? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromCode(text)
        }
    }
}

enum class ShiftKind(val code: String, val label: String) {
    Work("work", "Turno"),
    Off("off", "Libre");

    companion object {
        fun fromCode(code: String?): ShiftKind? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }

        fun fromLabel(label: String?): ShiftKind? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromCode(text)
        }
    }
}

enum class AbsenceKind(val code: String, val label: String) {
    Vacation("vacation", "Vacaciones"),
    Leave("leave", "Permiso"),
    Sick("sick", "Enfermedad"),
    Other("other", "Otro");

    companion object {
        fun fromCode(code: String?): AbsenceKind? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }

        fun fromLabel(label: String?): AbsenceKind? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromCode(text)
        }
    }
}

enum class AbsenceStatus(val code: String, val label: String) {
    Pending("pending", "Pendiente"),
    Approved("approved", "Aprobada"),
    Rejected("rejected", "Rechazada"),
    Cancelled("cancelled", "Cancelada");

    companion object {
        fun fromCode(code: String?): AbsenceStatus? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }

        fun fromLabel(label: String?): AbsenceStatus? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromCode(text)
        }
    }
}

/** Una regla del modulo que no se cumple (limites, referencias desconocidas); el mensaje se muestra al usuario. */
class EmpleadosValidationException(message: String) : Exception(message)

/**
 * Que columnas de una ficha se leen. La consulta SQL solo selecciona las del nivel: los campos que el usuario no puede
 * ver nunca salen de la base de datos (no basta con ocultarlos en la pantalla).
 *
 * - [Basic]: nombre, puesto, departamento, sede y estado.
 * - [Contact]: Basic + telefono, correo y disponibilidad (quien gestiona turnos).
 * - [Own]: Contact + fecha de ingreso (el propio empleado sobre su ficha).
 * - [Full]: todo, tambien fecha de baja, notas y usuario enlazado (quien administra fichas).
 */
enum class FieldLevel(val contact: Boolean, val hireDate: Boolean, val internal: Boolean) {
    Basic(false, false, false),
    Contact(true, false, false),
    Own(true, true, false),
    Full(true, true, true),
}

/** Ficha de empleado. Los campos que su [level] no incluye valen null: no se leyeron. */
data class Employee(
    val id: UUID,
    val fullName: String,
    val jobTitle: String,
    val departmentId: UUID?,
    val department: String?,
    val siteId: UUID?,
    val site: String?,
    val status: EmployeeStatus,
    val level: FieldLevel = FieldLevel.Basic,
    val phone: String? = null,
    val email: String? = null,
    val availability: String? = null,
    val hireDate: LocalDate? = null,
    val endedOn: LocalDate? = null,
    val notes: String? = null,
    val userId: UUID? = null,
)

data class CatalogItem(val id: UUID, val name: String, val position: Int, val archived: Boolean)

/** Un usuario de la empresa que se puede enlazar a una ficha o avisar. */
data class UserRef(val id: UUID, val label: String, val email: String)

/** Franja del horario habitual de un dia de la semana (ISO 1 lunes ... 7 domingo). Fin menor que inicio: pasa la medianoche. */
data class WeeklySlot(
    val id: UUID,
    val employeeId: UUID,
    val weekday: Int,
    val start: LocalTime,
    val end: LocalTime,
    val siteId: UUID?,
    val position: Int = 0,
)

/** Turno concreto de una fecha. Un turno `off` es un dia libre y no tiene horas. */
data class Shift(
    val id: UUID,
    val employeeId: UUID,
    val date: LocalDate,
    val kind: ShiftKind,
    val start: LocalTime?,
    val end: LocalTime?,
    val siteId: UUID?,
    val note: String = "",
)

data class Absence(
    val id: UUID,
    val employeeId: UUID,
    val kind: AbsenceKind,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: AbsenceStatus,
    val reason: String = "",
    val decisionNote: String = "",
    val requestedBy: UUID? = null,
    val decidedBy: UUID? = null,
    val decidedAt: Instant? = null,
    val createdAt: Instant = Instant.EPOCH,
) {
    fun covers(date: LocalDate): Boolean = !date.isBefore(startDate) && !date.isAfter(endDate)
}

/** Una ausencia con el nombre de quien la pide (para quien gestiona turnos). */
data class AbsenceRow(val absence: Absence, val employeeName: String)

data class EmployeeFilter(
    val query: String? = null,
    val siteId: UUID? = null,
    val departmentId: UUID? = null,
    /** Solo lo respeta quien ve bajas; los demas siempre ven solo activos. */
    val status: EmployeeStatus? = null,
    val limit: Int = 200,
)

/** Lo que un formulario o un agente envia para crear o editar una ficha. */
data class EmployeeInput(
    val fullName: String,
    val jobTitle: String = "",
    val departmentId: UUID? = null,
    val siteId: UUID? = null,
    val phone: String = "",
    val email: String = "",
    val hireDate: LocalDate? = null,
    val availability: String = "",
    val notes: String = "",
    val userId: UUID? = null,
) {
    /** Mensajes para el usuario; vacio cuando la entrada es valida. */
    fun validate(today: LocalDate): List<String> = buildList {
        if (fullName.isBlank()) add("El nombre es obligatorio.")
        else if (fullName.trim().length > MaxName) add("El nombre admite hasta $MaxName caracteres.")
        if (jobTitle.trim().length > MaxJobTitle) add("El puesto admite hasta $MaxJobTitle caracteres.")
        if (phone.isNotBlank() && !PhonePattern.matches(phone.trim())) add("El teléfono solo admite números, espacios y + - ( ) . # (hasta $MaxPhone caracteres).")
        if (email.isNotBlank() && !(email.trim().length <= MaxEmail && EmailPattern.matches(email.trim()))) add("El correo no es válido (hasta $MaxEmail caracteres).")
        if (hireDate != null && (hireDate.isBefore(MinHireDate) || hireDate.isAfter(today.plusYears(1)))) add("La fecha de ingreso debe estar entre $MinHireDate y un año desde hoy.")
        if (availability.trim().length > MaxAvailability) add("La disponibilidad admite hasta $MaxAvailability caracteres.")
        if (notes.length > MaxNotes) add("Las notas admiten hasta $MaxNotes caracteres.")
    }

    companion object {
        const val MaxName = 160
        const val MaxJobTitle = 120
        const val MaxPhone = 40
        const val MaxEmail = 254
        const val MaxAvailability = 500
        const val MaxNotes = 5000
        val MinHireDate: LocalDate = LocalDate.of(1950, 1, 1)
        val PhonePattern = Regex("[0-9+()\\-. xX#*/]{1,$MaxPhone}")
        val EmailPattern = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}

/** Un turno concreto que se quiere crear. */
data class ShiftInput(
    val employeeId: UUID,
    val date: LocalDate,
    val kind: ShiftKind = ShiftKind.Work,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val siteId: UUID? = null,
    val note: String = "",
) {
    fun validate(): List<String> = buildList {
        if (date.isBefore(MinDate) || date.isAfter(MaxDate)) add("La fecha debe estar entre $MinDate y $MaxDate.")
        if (note.trim().length > MaxNote) add("La nota admite hasta $MaxNote caracteres.")
        if (kind == ShiftKind.Work) {
            if (start == null || end == null) add("El turno necesita hora de inicio y de fin (HH:mm).")
            else if (start == end) add("La hora de inicio y la de fin no pueden ser iguales.")
        }
    }

    companion object {
        const val MaxNote = 500
        const val MaxShiftsPerDay = 4
        val MinDate: LocalDate = LocalDate.of(2000, 1, 1)
        val MaxDate: LocalDate = LocalDate.of(2100, 12, 31)
    }
}

/** Cambios de un turno: lo que es null se conserva. [setSite] distingue "quitar la sede" (true con siteId null) de "no tocarla". */
data class ShiftPatch(
    val date: LocalDate? = null,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val setSite: Boolean = false,
    val siteId: UUID? = null,
    val note: String? = null,
)

/** Una ausencia que se pide o se registra. */
data class AbsenceInput(
    val employeeId: UUID,
    val kind: AbsenceKind,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val reason: String = "",
) {
    fun validate(): List<String> = buildList {
        if (startDate.isBefore(ShiftInput.MinDate) || endDate.isAfter(ShiftInput.MaxDate)) add("Las fechas deben estar entre ${ShiftInput.MinDate} y ${ShiftInput.MaxDate}.")
        if (endDate.isBefore(startDate)) add("La fecha final no puede ser anterior a la inicial.")
        else if (java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) > MaxDays) add("Una ausencia admite hasta ${MaxDays + 1} días.")
        if (reason.trim().length > MaxReason) add("El motivo admite hasta $MaxReason caracteres.")
    }

    companion object {
        const val MaxReason = 1000
        const val MaxDays = 366L
    }
}
