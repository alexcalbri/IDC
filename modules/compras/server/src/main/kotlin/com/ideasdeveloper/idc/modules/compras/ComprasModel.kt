package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiTone
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Error de validacion o de regla de negocio: el mensaje (en espanol) se muestra tal cual al usuario. */
class ComprasValidationException(message: String) : RuntimeException(message)

/** Estados de una solicitud; [id] es el valor guardado en `compras_requests.status`. */
enum class RequestStatus(val id: String, val label: String, val tone: String) {
    Draft("draft", "Borrador", UiTone.Neutral),
    Quoting("quoting", "En cotizacion", UiTone.Info),
    PendingApproval("pending_approval", "Pendiente de aprobacion", UiTone.Warning),
    Approved("approved", "Aprobada", UiTone.Success),
    Rejected("rejected", "Rechazada", UiTone.Danger),
    InProgress("in_progress", "Comprada / en progreso", UiTone.Info),
    Completed("completed", "Completada", UiTone.Success),
    Cancelled("cancelled", "Cancelada", UiTone.Neutral);

    /** Completada o cancelada: ya no se edita ni siquiera con manage. */
    val terminal: Boolean get() = this == Completed || this == Cancelled

    /** Cuenta como abierta en el informe de altas y criticas. */
    val open: Boolean get() = this != Completed && this != Cancelled && this != Rejected

    companion object {
        fun fromId(id: String?): RequestStatus? = entries.firstOrNull { it.id == id }

        /** Estados que cuentan como compra aprobada en los totales. */
        val Approvals: Set<RequestStatus> = setOf(Approved, InProgress, Completed)
    }
}

/** Prioridad de una solicitud; [rank] mayor = mas urgente. */
enum class Priority(val id: String, val label: String, val tone: String, val rank: Int) {
    Low("low", "Baja", UiTone.Neutral, 0),
    Medium("medium", "Media", UiTone.Info, 1),
    High("high", "Alta", UiTone.Warning, 2),
    Critical("critical", "Critica", UiTone.Danger, 3);

    companion object {
        fun fromId(id: String?): Priority? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Quien actua: el usuario con sus permisos vivos en el modulo. El dueno del negocio y el del servidor
 * tienen todos los permisos ([isOwner]).
 */
data class Actor(val userId: UUID?, val name: String, val permissions: ModulePermissions) {
    val isOwner: Boolean get() = permissions.businessOwner || permissions.serverOwner

    fun can(permission: String): Boolean = isOwner || permissions.has(permission)

    /** Es [id] el propio actor (false si alguno es nulo). */
    fun isUser(id: UUID?): Boolean = userId != null && userId == id
}

data class Department(val id: UUID, val name: String, val active: Boolean, val sortOrder: Int)

/** Aprobador designado. [maxAmount] nulo = sin limite; el limite se aplica solo en [limitCurrency]. */
data class Approver(val userId: UUID, val name: String, val maxAmount: BigDecimal?, val limitCurrency: String, val active: Boolean)

/** Usuario activo de la empresa; [canApprove] = dueno del negocio o con permiso compras.approve. */
data class CompanyUser(val id: UUID, val name: String, val email: String, val canApprove: Boolean)

data class ComprasSettings(val defaultCurrency: String)

/** Origen opcional de una solicitud (p. ej. kind "mantenimiento.work_order", ref el id de la orden). */
data class SourceRef(val kind: String, val ref: String)

/** Datos editables de una solicitud. Usa [validated] antes de guardar. */
data class RequestDraft(
    val title: String,
    val description: String = "",
    val departmentId: UUID? = null,
    val estimatedAmount: BigDecimal? = null,
    val currency: String,
    val requiredDate: LocalDate? = null,
    val priority: Priority = Priority.Medium,
) {
    /** Copia normalizada o [ComprasValidationException]. */
    fun validated(): RequestDraft {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) throw ComprasValidationException("El titulo es obligatorio.")
        if (cleanTitle.length > MaxTitle) throw ComprasValidationException("El titulo admite hasta $MaxTitle caracteres.")
        val cleanDescription = description.trim()
        if (cleanDescription.length > MaxDescription) throw ComprasValidationException("La descripcion admite hasta $MaxDescription caracteres.")
        return copy(
            title = cleanTitle,
            description = cleanDescription,
            estimatedAmount = estimatedAmount?.let { ComprasMoney.check(it) },
            currency = ComprasMoney.currency(currency),
        )
    }

    /** Nombres de los campos que cambian respecto a [current], para el historial ("Titulo, Monto"). */
    fun changesFrom(current: PurchaseRequest): String {
        val sameAmount = when {
            estimatedAmount == null || current.estimatedAmount == null -> estimatedAmount == current.estimatedAmount
            else -> estimatedAmount.compareTo(current.estimatedAmount) == 0
        }
        return buildList {
            if (title.trim() != current.title) add("Titulo")
            if (description.trim() != current.description) add("Descripcion")
            if (departmentId != current.departmentId) add("Departamento")
            if (!sameAmount) add("Monto")
            if (currency.trim().uppercase() != current.currency) add("Moneda")
            if (requiredDate != current.requiredDate) add("Fecha requerida")
            if (priority != current.priority) add("Prioridad")
        }.joinToString(", ")
    }

    companion object {
        const val MaxTitle = 200
        const val MaxDescription = 5000
    }
}

data class PurchaseRequest(
    val id: UUID,
    val number: Long,
    val title: String,
    val description: String,
    val departmentId: UUID?,
    val departmentName: String?,
    val estimatedAmount: BigDecimal?,
    val currency: String,
    val requiredDate: LocalDate?,
    val priority: Priority,
    val status: RequestStatus,
    val requesterId: UUID,
    val requesterName: String,
    val decidedBy: UUID?,
    val decidedByName: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val approvedAmount: BigDecimal?,
    val approvedCurrency: String?,
    val sourceKind: String?,
    val sourceRef: String?,
    val submittedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Monto a aprobar: el de la cotizacion elegida si la hay, si no el estimado (null = sin monto). */
    val approvalAmount: BigDecimal?,
    /** Moneda del monto a aprobar. */
    val approvalCurrency: String,
) {
    /** Folio visible, p. ej. "C-00012". */
    val folio: String get() = ComprasFolio.format(number)
}

data class Quote(
    val id: UUID,
    val requestId: UUID,
    val supplier: String,
    val amount: BigDecimal,
    val currency: String,
    val notes: String,
    val selected: Boolean,
    val quotedBy: UUID?,
    val quotedByName: String,
    val createdAt: Instant,
)

/** Una linea del historial; [kind]: created, edited, status, quote, attachment. */
data class RequestEvent(
    val id: Long,
    val requestId: UUID,
    val at: Instant,
    val actorId: UUID?,
    val actorName: String,
    val kind: String,
    val fromStatus: RequestStatus?,
    val toStatus: RequestStatus?,
    val detail: String,
)

/**
 * Filtros de la lista. [mine] = solo las del actor; [highOnly] = prioridad alta o critica y abiertas;
 * [pendingMyApproval] = pendientes que el actor puede aprobar por su limite.
 */
data class RequestFilter(
    val status: RequestStatus? = null,
    val priority: Priority? = null,
    val departmentId: UUID? = null,
    val mine: Boolean = false,
    val text: String? = null,
    val highOnly: Boolean = false,
    val pendingMyApproval: Boolean = false,
)

/** Resultado de [ComprasRepository.transition]: la solicitud ya movida y el estado del que venia. */
data class TransitionResult(val request: PurchaseRequest, val from: RequestStatus)

/** Fila de totales: [label] es departamento, mes (AAAA-MM) o id de estado; siempre por [currency]. */
data class TotalRow(val label: String, val currency: String, val total: BigDecimal, val count: Int)

/** Folio visible "C-00012" y su lectura. */
object ComprasFolio {
    fun format(number: Long): String = "C-" + number.toString().padStart(5, '0')

    /** Lee "C-00012", "c-12" o "12"; null si no es un folio. */
    fun parse(text: String): Long? {
        val digits = text.trim().removePrefix("C-").removePrefix("c-")
        return if (digits.isNotEmpty() && digits.length <= 18 && digits.all { it in '0'..'9' }) digits.toLongOrNull()?.takeIf { it > 0 } else null
    }
}

/** Dinero: siempre [BigDecimal] con 2 decimales (numeric(14,2)), nunca Double. */
object ComprasMoney {
    val Max = BigDecimal("999999999999.99")
    private val AmountPattern = Regex("^\\d+(\\.\\d{1,2})?$")
    private val CurrencyPattern = Regex("^[A-Z]{3}$")

    /**
     * Lee un monto escrito por una persona: acepta punto o coma decimal y separadores de miles
     * ("1,234.50", "1.234,50", "12,5"). Un solo punto es decimal; varios puntos son miles; una sola coma
     * seguida de 3 digitos es miles ("1,234" = 1234), con 1 o 2 digitos es decimal. Rechaza negativos,
     * mas de 2 decimales y mas de [Max]. Devuelve null si [text] esta en blanco.
     */
    fun parseOptional(text: String?): BigDecimal? {
        val raw = text?.trim().orEmpty().replace(" ", "")
        if (raw.isEmpty()) return null
        if (raw.startsWith("-")) throw ComprasValidationException("El monto no puede ser negativo.")
        val normalized = normalize(raw)
        if (!AmountPattern.matches(normalized)) throw ComprasValidationException("El monto '$text' no es valido; usa un numero con hasta 2 decimales.")
        return check(BigDecimal(normalized))
    }

    /** Como [parseOptional] pero el monto es obligatorio. */
    fun parse(text: String?): BigDecimal = parseOptional(text) ?: throw ComprasValidationException("El monto es obligatorio.")

    private fun normalize(raw: String): String {
        val lastDot = raw.lastIndexOf('.')
        val lastComma = raw.lastIndexOf(',')
        return when {
            lastDot >= 0 && lastComma >= 0 -> {
                val decimal = if (lastDot > lastComma) '.' else ','
                val thousands = if (decimal == '.') ',' else '.'
                raw.replace(thousands.toString(), "").replace(decimal, '.')
            }
            lastComma >= 0 -> {
                val single = raw.indexOf(',') == lastComma
                val after = raw.length - lastComma - 1
                if (single && after != 3) raw.replace(',', '.') else raw.replace(",", "")
            }
            raw.indexOf('.') != lastDot -> raw.replace(".", "")
            else -> raw
        }
    }

    /** Verifica el rango (0..[Max], hasta 2 decimales) y devuelve el monto con escala 2. */
    fun check(amount: BigDecimal): BigDecimal {
        if (amount.signum() < 0) throw ComprasValidationException("El monto no puede ser negativo.")
        if (amount.signum() != 0 && amount.stripTrailingZeros().scale() > 2) throw ComprasValidationException("El monto admite hasta 2 decimales.")
        val scaled = amount.setScale(2, RoundingMode.HALF_UP)
        if (scaled > Max) throw ComprasValidationException("El monto supera el maximo de 999,999,999,999.99.")
        return scaled
    }

    /** Moneda de 3 letras en mayusculas ("usd" -> "USD") o [ComprasValidationException]. */
    fun currency(text: String?): String {
        val code = text?.trim()?.uppercase().orEmpty()
        if (!CurrencyPattern.matches(code)) throw ComprasValidationException("La moneda debe ser un codigo de 3 letras, por ejemplo USD.")
        return code
    }

    /** "USD 1,234.50". */
    fun format(amount: BigDecimal, currency: String): String {
        val plain = amount.setScale(2, RoundingMode.HALF_UP).abs().toPlainString()
        val whole = plain.substringBefore('.')
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        val sign = if (amount.signum() < 0) "-" else ""
        return "$currency $sign$grouped.${plain.substringAfter('.')}"
    }
}
