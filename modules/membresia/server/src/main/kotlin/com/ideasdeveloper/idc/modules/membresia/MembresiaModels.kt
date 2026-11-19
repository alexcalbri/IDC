package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.ui.UiTone
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Estados guardados en la base de datos (columna status). */
object StoredStatus {
    const val Programada = "programada"
    const val Activa = "activa"
    const val Vencida = "vencida"
    const val Cancelada = "cancelada"
    val all = listOf(Programada, Activa, Vencida, Cancelada)
}

/** Estado que ve el equipo: se calcula con las fechas, asi es correcto aunque el trabajo diario no haya corrido. */
enum class DisplayStatus(val label: String, val tone: String, val key: String) {
    PROGRAMADA("Programada", UiTone.Info, "programada"),
    ACTIVA("Activa", UiTone.Success, "activa"),
    POR_VENCER("Por vencer", UiTone.Warning, "por_vencer"),
    VENCIDA("Vencida", UiTone.Danger, "vencida"),
    CANCELADA("Cancelada", UiTone.Neutral, "cancelada");

    /** La membresia permite entrar hoy. */
    val current: Boolean get() = this == ACTIVA || this == POR_VENCER

    companion object {
        fun fromKey(key: String?): DisplayStatus? = entries.firstOrNull { it.key == key }
    }
}

data class Plan(
    val id: UUID,
    val name: String,
    val description: String,
    val price: BigDecimal,
    val durationDays: Int,
    /** Visitas incluidas por periodo; null = ilimitadas. */
    val includedVisits: Int?,
    val guestPasses: Int,
    val wifiAccess: Boolean,
    val active: Boolean,
    val displayOrder: Int,
)

data class Membership(
    val id: UUID,
    val customerId: UUID,
    val customerName: String,
    val planId: UUID,
    val planName: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    /** Estado guardado (StoredStatus); para mostrar usa MembresiaRules.displayStatus. */
    val status: String,
    val source: String,
    val hostUserId: UUID?,
    val hostName: String,
    val unit: String,
    val notes: String,
    val includedVisits: Int?,
    val visitsUsed: Int,
    val guestPassesTotal: Int,
    val guestPassesUsed: Int,
    val renewedFrom: UUID?,
    val cancelledAt: Instant?,
    val cancelReason: String,
    val expiringNotifiedOn: LocalDate?,
    val createdBy: String,
)

data class Visit(
    val id: UUID,
    val membershipId: UUID,
    val customerId: UUID,
    val customerName: String,
    val kind: String,
    val guestName: String?,
    val visitOn: LocalDate,
    val visitedAt: Instant,
    val override: Boolean,
    val recordedBy: String,
    val note: String,
)

data class MembresiaSettings(
    val currency: String,
    val expiringDays: Int,
    val sources: List<String>,
) {
    companion object {
        val Default = MembresiaSettings("USD", 7, listOf("Referido", "Walk-in", "Sitio web", "Otro"))
    }
}

/** Cliente visible con su membresia actual (la vigente, o si no la mas reciente). Nunca lleva customer_code ni campos personalizados. */
data class MemberSummary(
    val customerId: UUID,
    val customerName: String,
    val email: String,
    val phone: String,
    val current: Membership?,
)

/** Ficha de un miembro: cliente visible, sus membresias (la mas reciente primero) y ultimas visitas. */
data class MemberDetail(
    val summary: MemberSummary,
    val memberships: List<Membership>,
    val visits: List<Visit>,
)

data class RevenueMonth(val month: YearMonth, val amount: BigDecimal, val count: Int)

data class RevenueByPlan(val planName: String, val amount: BigDecimal, val count: Int)

/**
 * Ingresos agregados en SQL: [startsOn] es el primer dia del mes, [amount] la suma y [count] cuantas membresias
 * (no canceladas) de ese plan y moneda. [status] se conserva para las pruebas puras (el SQL ya excluye canceladas).
 */
data class RevenueRow(
    val startsOn: LocalDate,
    val planName: String,
    val currency: String,
    val amount: BigDecimal,
    val count: Int = 1,
    val status: String = StoredStatus.Activa,
)

data class HostCandidate(val userId: UUID, val name: String)

data class PlanInput(
    val name: String,
    val description: String = "",
    val price: BigDecimal,
    val durationDays: Int,
    val includedVisits: Int? = null,
    val guestPasses: Int = 0,
    val wifiAccess: Boolean = true,
    val displayOrder: Int = 0,
)

/** Alta de membresia. [amount] vacio = precio del plan; [startsOn] vacio = hoy. */
data class MembershipInput(
    val customerId: UUID,
    val planId: UUID,
    val startsOn: LocalDate? = null,
    val amount: BigDecimal? = null,
    val source: String = "",
    val hostUserId: UUID? = null,
    val hostName: String = "",
    val unit: String = "",
    val notes: String = "",
)

/** Renovacion: [planId] vacio = mismo plan; [startsOn] vacio = MembresiaRules.renewalStart. */
data class RenewalInput(
    val planId: UUID? = null,
    val startsOn: LocalDate? = null,
    val amount: BigDecimal? = null,
)

/** Cambios permitidos de una membresia existente. */
data class MembershipEdit(
    val endsOn: LocalDate,
    val amount: BigDecimal,
    val source: String = "",
    val hostUserId: UUID? = null,
    val hostName: String = "",
    val unit: String = "",
    val notes: String = "",
)

data class NewCustomerInput(
    val name: String,
    val email: String = "",
    val phone: String = "",
)

sealed interface VisitResult {
    /** Visita de hoy registrada; [overridden] = recepcion la registro igual pese al aviso. */
    data class Recorded(val overridden: Boolean) : VisitResult

    /** Ya habia una visita de este miembro hoy (doble toque): no se cuenta dos veces. */
    data object AlreadyToday : VisitResult

    /** Hay un aviso (vencida, programada, visitas agotadas): se registra solo con "registrar igual". */
    data class NeedsOverride(val reason: String) : VisitResult

    /** No se registra nunca (cancelada). */
    data class Refused(val reason: String) : VisitResult
}

sealed interface GuestPassResult {
    data object Recorded : GuestPassResult
    data class Refused(val reason: String) : GuestPassResult
}

/** Cliente encontrado o creado por [MembresiaRepository.findOrCreateCustomer]. */
data class CustomerLookup(val customerId: UUID, val created: Boolean)

class MembresiaValidationException(val errors: List<String>) : RuntimeException(errors.joinToString(" "))
