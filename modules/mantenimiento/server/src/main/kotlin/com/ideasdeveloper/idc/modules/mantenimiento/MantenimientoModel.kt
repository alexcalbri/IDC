package com.ideasdeveloper.idc.modules.mantenimiento

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Tipo de orden: preventiva (por plan), correctiva (avería o a mano) o proyecto (con aprobación). */
enum class OrderKind(val id: String, val label: String) {
    Preventive("preventive", "Preventiva"),
    Corrective("corrective", "Correctiva"),
    Project("project", "Proyecto");

    companion object {
        fun fromId(id: String?): OrderKind? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        /** Acepta la etiqueta visible (sin importar mayúsculas) y, por comodidad, el código. */
        fun fromLabel(label: String?): OrderKind? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

/** Estados fijos de una orden: filtros, tablero y agentes dependen de ellos. */
enum class OrderStatus(val id: String, val label: String) {
    Requested("requested", "Pendiente de aprobación"),
    Quoting("quoting", "En cotización"),
    Open("open", "Abierta"),
    InProgress("in_progress", "En curso"),
    OnHold("on_hold", "En espera"),
    Done("done", "Completada"),
    Rejected("rejected", "Rechazada"),
    Cancelled("cancelled", "Cancelada");

    /** Sigue necesitando trabajo o decisión: ni completada, ni rechazada, ni cancelada. */
    val isActive: Boolean get() = this != Done && this != Rejected && this != Cancelled

    /** Estados de trabajo: la orden ya fue aprobada (o no lo necesita) y no está cerrada. */
    val isWorking: Boolean get() = this == Open || this == InProgress || this == OnHold

    companion object {
        fun fromId(id: String?): OrderStatus? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun fromLabel(label: String?): OrderStatus? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

enum class OrderPriority(val id: String, val label: String) {
    Urgent("urgent", "Urgente"),
    High("high", "Alta"),
    Medium("medium", "Media"),
    Low("low", "Baja");

    companion object {
        fun fromId(id: String?): OrderPriority? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun fromLabel(label: String?): OrderPriority? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

enum class PlanFrequency(val id: String, val label: String) {
    Monthly("monthly", "Mensual"),
    Quarterly("quarterly", "Trimestral"),
    Yearly("yearly", "Anual"),
    EveryNDays("every_n_days", "Cada cierto número de días");

    companion object {
        fun fromId(id: String?): PlanFrequency? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun fromLabel(label: String?): PlanFrequency? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

/** Calendario fijo (la fecha avanza sola) o desde el último cierre (la próxima fecha sale de cerrar la orden). */
enum class ScheduleMode(val id: String, val label: String) {
    Fixed("fixed", "Calendario fijo"),
    Floating("floating", "Desde el último cierre");

    companion object {
        fun fromId(id: String?): ScheduleMode? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun fromLabel(label: String?): ScheduleMode? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

/** Orden en que se listan las órdenes. */
enum class OrderSort { Due, Newest }

/** Se lanza cuando se rompe una regla del módulo (límites, referencias que no existen); el mensaje se muestra al usuario. */
class MantenimientoValidationException(message: String) : Exception(message)

data class Property(val id: UUID, val name: String, val position: Int, val archived: Boolean)

data class Asset(
    val id: UUID,
    val propertyId: UUID?,
    val propertyName: String?,
    val name: String,
    val category: String,
    val location: String,
    val notes: String,
    val archived: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Plan(
    val id: UUID,
    val title: String,
    val description: String,
    val propertyId: UUID?,
    val propertyName: String?,
    val assetId: UUID?,
    val assetName: String?,
    val frequency: PlanFrequency,
    val intervalDays: Int?,
    val scheduleMode: ScheduleMode,
    val nextDue: LocalDate?,
    val leadDays: Int,
    val defaultAssignee: UUID?,
    val priority: OrderPriority,
    val active: Boolean,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** La fecha fija desde la que se cuentan las ocurrencias del calendario fijo (evita que el fin de mes se arrastre). */
    val anchorDate: LocalDate? = null,
    /** Último intento fallido del generador: el plan se excluye del lote unas horas. */
    val lastErrorAt: Instant? = null,
)

data class PlanCheck(val id: UUID, val planId: UUID, val text: String, val position: Int)

data class Order(
    val id: UUID,
    val number: Long,
    val kind: OrderKind,
    val status: OrderStatus,
    val priority: OrderPriority,
    val title: String,
    val description: String,
    val propertyId: UUID?,
    val propertyName: String?,
    val assetId: UUID?,
    val assetName: String?,
    val planId: UUID?,
    val planDue: LocalDate?,
    val dueDate: LocalDate?,
    val assigneeId: UUID?,
    val requestedBy: UUID?,
    val requestedByName: String,
    val department: String,
    val quoteAmount: BigDecimal?,
    val quoteCurrency: String,
    val quotedBy: String,
    val costAmount: BigDecimal?,
    val costCurrency: String,
    val resolution: String,
    val rejectedReason: String,
    val approvedAt: Instant?,
    val approvedBy: UUID?,
    val completedAt: Instant?,
    val completedBy: UUID?,
    val tareasTaskId: UUID?,
    val purchaseRequestId: UUID?,
    val purchaseFolio: String,
    val purchaseLinkingAt: Instant?,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant? = null,
    val checklistDone: Int = 0,
    val checklistTotal: Int = 0,
) {
    /** El folio visible, por ejemplo "OT-0007". */
    val displayNumber: String get() = MantenimientoFolio.format(number)

    /** Abierta de verdad y con la fecha límite ya pasada. */
    fun isOverdue(today: LocalDate): Boolean = status.isActive && dueDate != null && dueDate.isBefore(today)
}

object MantenimientoFolio {
    fun format(number: Long): String = "OT-" + number.toString().padStart(4, '0')

    /** Lee "OT-0007", "ot-7" o "7"; null si no es un folio. */
    fun parse(text: String?): Long? {
        val clean = text?.trim()?.removePrefix("OT-")?.removePrefix("ot-")?.trim().orEmpty()
        return clean.takeIf { it.isNotEmpty() && it.length <= 12 && it.all { char -> char.isDigit() } }?.toLongOrNull()?.takeIf { it > 0 }
    }
}

data class OrderCheck(
    val id: UUID,
    val orderId: UUID,
    val text: String,
    val done: Boolean,
    val position: Int,
    val doneBy: UUID?,
    val doneAt: Instant?,
)

/** [kind] es note, status, assign o system. */
data class OrderNote(val id: UUID, val orderId: UUID, val kind: String, val body: String, val authorId: UUID?, val authorName: String, val createdAt: Instant)

data class Attachment(
    val id: UUID,
    val orderId: UUID?,
    val name: String,
    val path: String,
    val contentType: String,
    val size: Long,
    val uploadedBy: UUID?,
    val createdAt: Instant,
)

/** Un usuario asignable: [label] es lo que ven pantallas y agentes (único dentro de la lista). */
data class MantenimientoUser(val id: UUID, val label: String, val email: String?)

/** Quién hace un cambio: su id (null para el dueño del servidor, que no es usuario de la empresa) y su nombre para las notas. */
data class MantenimientoActor(val userId: UUID?, val name: String)

data class MantenimientoSettings(val tareasBoardId: UUID?, val tareasPublisherId: UUID?, val dueSoonDays: Int)

data class OrderMetrics(val open: Int, val overdue: Int, val pendingApproval: Int, val completedThisMonth: Int)

/** Aviso de vencimiento pendiente: [kind] es "due_soon" o "overdue". */
data class ReminderCandidate(val orderId: UUID, val userId: UUID, val kind: String, val dueDate: LocalDate, val number: Long, val title: String)

data class OrderFilter(
    val status: OrderStatus? = null,
    /** Solo las que siguen abiertas (ni completadas, ni rechazadas, ni canceladas). */
    val activeOnly: Boolean = false,
    val kind: OrderKind? = null,
    val propertyId: UUID? = null,
    val assetId: UUID? = null,
    val assigneeId: UUID? = null,
    val planId: UUID? = null,
    /** Las asignadas a este usuario y las que reportó, abiertas ("Mis órdenes"). */
    val mineOf: UUID? = null,
    /** Con fecha límite igual o anterior a ésta. */
    val dueOnOrBefore: LocalDate? = null,
    val query: String? = null,
    val sort: OrderSort = OrderSort.Due,
    val limit: Int = 200,
)

private val MinDate: LocalDate = LocalDate.of(2000, 1, 1)
private val MaxDate: LocalDate = LocalDate.of(2100, 12, 31)
private val MaxAmount = BigDecimal("999999999999.99")
private val CurrencyPattern = Regex("^[A-Z]{3}$")

private fun MutableList<String>.checkDate(label: String, date: LocalDate?) {
    if (date != null && (date.isBefore(MinDate) || date.isAfter(MaxDate))) add("$label debe estar entre $MinDate y $MaxDate.")
}

private fun MutableList<String>.checkAmount(label: String, amount: BigDecimal?) {
    if (amount == null) return
    if (amount.signum() < 0) add("$label no puede ser negativo.")
    else if (amount > MaxAmount) add("$label es demasiado grande.")
    else if (amount.stripTrailingZeros().scale() > 2) add("$label admite hasta 2 decimales.")
}

/** Lo que un formulario o un agente envía para crear o editar una orden (estado y responsable se cambian aparte al editar). */
data class OrderInput(
    val kind: OrderKind = OrderKind.Corrective,
    val title: String,
    val description: String = "",
    val propertyId: UUID? = null,
    val assetId: UUID? = null,
    val priority: OrderPriority = OrderPriority.Medium,
    val dueDate: LocalDate? = null,
    val assigneeId: UUID? = null,
    val department: String = "",
    val quoteAmount: BigDecimal? = null,
    val quoteCurrency: String = "USD",
    val quotedBy: String = "",
    val costAmount: BigDecimal? = null,
    val costCurrency: String = "USD",
    val resolution: String = "",
) {
    /** Mensajes para el usuario; vacío si la entrada es válida. */
    fun validate(): List<String> = buildList {
        if (title.isBlank()) add("El título es obligatorio.")
        else if (title.trim().length > MaxTitle) add("El título admite hasta $MaxTitle caracteres.")
        if (description.length > MaxDescription) add("La descripción admite hasta $MaxDescription caracteres.")
        if (department.trim().length > MaxDepartment) add("El departamento admite hasta $MaxDepartment caracteres.")
        if (quotedBy.trim().length > MaxPerson) add("El nombre de quien cotizó admite hasta $MaxPerson caracteres.")
        if (resolution.length > MaxResolution) add("La resolución admite hasta $MaxResolution caracteres.")
        if (!CurrencyPattern.matches(quoteCurrency.trim())) add("La moneda de la cotización debe tener 3 letras mayúsculas, por ejemplo USD.")
        if (!CurrencyPattern.matches(costCurrency.trim())) add("La moneda del costo debe tener 3 letras mayúsculas, por ejemplo USD.")
        checkAmount("La cotización", quoteAmount)
        checkAmount("El costo", costAmount)
        checkDate("La fecha límite", dueDate)
    }

    companion object {
        const val MaxTitle = 200
        const val MaxDescription = 20000
        const val MaxDepartment = 80
        const val MaxPerson = 120
        const val MaxResolution = 5000
    }
}

/** Lo que se envía para crear o editar un plan preventivo; [checklist] reemplaza la lista de comprobación del plan. */
data class PlanInput(
    val title: String,
    val description: String = "",
    val propertyId: UUID? = null,
    val assetId: UUID? = null,
    val frequency: PlanFrequency = PlanFrequency.Monthly,
    val intervalDays: Int? = null,
    val scheduleMode: ScheduleMode = ScheduleMode.Fixed,
    val nextDue: LocalDate? = null,
    val leadDays: Int = 7,
    val defaultAssignee: UUID? = null,
    val priority: OrderPriority = OrderPriority.Medium,
    val checklist: List<String> = emptyList(),
) {
    /** [requireNextDue] es false al editar un plan "desde el último cierre" que espera su próxima fecha. */
    fun validate(requireNextDue: Boolean = true): List<String> = buildList {
        if (title.isBlank()) add("El título es obligatorio.")
        else if (title.trim().length > MaxTitle) add("El título admite hasta $MaxTitle caracteres.")
        if (description.length > MaxDescription) add("La descripción admite hasta $MaxDescription caracteres.")
        if (frequency == PlanFrequency.EveryNDays) {
            if (intervalDays == null || intervalDays !in 1..MaxIntervalDays) add("Indica cada cuántos días se repite (de 1 a $MaxIntervalDays).")
        } else if (intervalDays != null) {
            add("Los días solo se indican con la frecuencia \"${PlanFrequency.EveryNDays.label}\".")
        }
        if (leadDays !in 0..MaxLeadDays) add("Los días de anticipación van de 0 a $MaxLeadDays.")
        if (requireNextDue && nextDue == null) add("La próxima fecha es obligatoria.")
        checkDate("La próxima fecha", nextDue)
        if (checklist.size > MaxChecklistItems) add("La lista de comprobación admite hasta $MaxChecklistItems elementos.")
        if (checklist.any { it.isBlank() }) add("Un elemento de la lista de comprobación está vacío.")
        if (checklist.any { it.trim().length > MaxChecklistText }) add("Cada elemento de la lista admite hasta $MaxChecklistText caracteres.")
    }

    companion object {
        const val MaxTitle = 200
        const val MaxDescription = 5000
        const val MaxIntervalDays = 3650
        const val MaxLeadDays = 90
        const val MaxChecklistItems = 100
        const val MaxChecklistText = 300
    }
}

data class AssetInput(
    val propertyId: UUID? = null,
    val name: String,
    val category: String = "",
    val location: String = "",
    val notes: String = "",
) {
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("El nombre del activo es obligatorio.")
        else if (name.trim().length > MaxName) add("El nombre del activo admite hasta $MaxName caracteres.")
        if (category.trim().length > MaxCategory) add("La categoría admite hasta $MaxCategory caracteres.")
        if (location.trim().length > MaxLocation) add("La ubicación admite hasta $MaxLocation caracteres.")
        if (notes.length > MaxNotes) add("Las notas admiten hasta $MaxNotes caracteres.")
    }

    companion object {
        const val MaxName = 160
        const val MaxCategory = 60
        const val MaxLocation = 160
        const val MaxNotes = 5000
    }
}
