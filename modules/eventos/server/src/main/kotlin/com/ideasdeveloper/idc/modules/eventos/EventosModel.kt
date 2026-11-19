package com.ideasdeveloper.idc.modules.eventos

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** State of an event. A private event shows Published as "Confirmado" and Draft as "Tentativo" (see EventosRules.statusLabel). */
enum class EventStatus(val code: String) {
    Draft("borrador"),
    Published("publicado"),
    Full("lleno"),
    Done("realizado"),
    Cancelled("cancelado"),
    ;

    companion object {
        fun fromCode(code: String?): EventStatus? {
            val wanted = code?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.code == wanted }
        }
    }
}

enum class EventKind(val code: String) {
    Public("publico"),
    Private("privado"),
    ;

    companion object {
        fun fromCode(code: String?): EventKind? {
            val wanted = code?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.code == wanted }
        }
    }
}

enum class RegistrationStatus(val code: String) {
    Registered("inscrito"),
    Waitlist("espera"),
    Cancelled("cancelado"),
    ;

    companion object {
        fun fromCode(code: String?): RegistrationStatus? {
            val wanted = code?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.code == wanted }
        }
    }
}

enum class PriceKind(val code: String) {
    Member("miembro"),
    General("general"),
    Courtesy("cortesia"),
    ;

    companion object {
        fun fromCode(code: String?): PriceKind? {
            val wanted = code?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.code == wanted }
        }
    }
}

/** An active user of the company that can use the module (owner or holder of an eventos.* permission). */
data class EventosUser(val id: UUID, val name: String, val email: String)

data class EventosSettings(val currency: String, val spaces: List<String>, val categories: List<String>)

data class Event(
    val id: UUID,
    val kind: EventKind,
    val title: String,
    val description: String,
    val category: String,
    val series: String,
    val space: String,
    val startsAt: Instant,
    val endsAt: Instant?,
    /** Public events only; null = no limit. */
    val capacity: Int?,
    val hostName: String,
    val responsibleUserId: UUID?,
    val status: EventStatus,
    val priceMember: BigDecimal?,
    val pricePublic: BigDecimal?,
    val currency: String,
    /** Private events only: the customer that hires the event. */
    val clientCustomerId: UUID?,
    /** Name of the client when the customer is visible to the company; empty otherwise. */
    val clientName: String,
    val agreedAmount: BigDecimal?,
    val guests: Int?,
    val notes: String,
    val importKey: String?,
    val cancelledAt: Instant?,
    val cancelReason: String,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Numbers of an event computed in SQL. [occupied] counts seats of registered people; [registrations], [waiting] and
 * [attended] count people (rows). [paymentsTotal] is the sum of the payments (abonos) of a private event.
 */
data class EventStats(
    val occupied: Int = 0,
    val registrations: Int = 0,
    val waiting: Int = 0,
    val attended: Int = 0,
    val collected: BigDecimal = BigDecimal.ZERO,
    val pending: BigDecimal = BigDecimal.ZERO,
    val paymentsTotal: BigDecimal = BigDecimal.ZERO,
)

data class EventWithStats(val event: Event, val stats: EventStats)

data class Registration(
    val id: UUID,
    val eventId: UUID,
    val customerId: UUID,
    /** "Cliente no disponible" when the customer is not visible to the company. */
    val customerName: String,
    val status: RegistrationStatus,
    val seats: Int,
    val priceKind: PriceKind,
    val amount: BigDecimal,
    val paid: Boolean,
    val paidAt: Instant?,
    val paymentNote: String,
    val attendedAt: Instant?,
    val attendedBy: String,
    val waitlistedAt: Instant?,
    val note: String,
    val cancelledAt: Instant?,
    val createdBy: String,
    val createdAt: Instant,
    val eventTitle: String,
    val eventStartsAt: Instant,
    val eventCurrency: String,
)

/** A payment (abono) of a private event. */
data class EventPayment(
    val id: UUID,
    val eventId: UUID,
    val paidOn: java.time.LocalDate,
    val amount: BigDecimal,
    val method: String,
    val note: String,
    val recordedBy: String,
    val createdAt: Instant,
)

/** What the screens and the agents send to create or edit an event. The kind and the currency never change after creation. */
data class EventInput(
    val kind: EventKind,
    val title: String,
    val description: String = "",
    val category: String = "",
    val series: String = "",
    val space: String = "",
    val startsAt: Instant,
    val endsAt: Instant? = null,
    val capacity: Int? = null,
    val hostName: String = "",
    val responsibleUserId: UUID? = null,
    val priceMember: BigDecimal? = null,
    val pricePublic: BigDecimal? = null,
    val clientCustomerId: UUID? = null,
    val agreedAmount: BigDecimal? = null,
    val guests: Int? = null,
    val notes: String = "",
)

/**
 * A registration to create. [priceKind] null = the proposed one (member when the customer has a current membership and the
 * event has a member price); [amount] null = price x seats (a courtesy is always 0).
 */
data class RegistrationInput(
    val eventId: UUID,
    val customerId: UUID?,
    val seats: Int = 1,
    val priceKind: PriceKind? = null,
    val amount: BigDecimal? = null,
    val paid: Boolean = false,
    val note: String = "",
)

data class NewCustomerInput(val name: String, val email: String = "", val phone: String = "")

data class CustomerLookup(val customerId: UUID, val created: Boolean)

/** A customer the company can see (see EventosRepository.VisibleCustomer). */
data class CustomerRef(val id: UUID, val name: String, val email: String)

data class RegistrationResult(
    val registration: Registration,
    val waitlisted: Boolean,
    val eventBecameFull: Boolean,
    val membership: ActiveMembership?,
    val currency: String,
)

data class EventFilter(
    val from: Instant? = null,
    val to: Instant? = null,
    val kind: EventKind? = null,
    val query: String = "",
    val series: String = "",
    val category: String = "",
    val space: String = "",
    val statuses: List<EventStatus> = emptyList(),
    val newestFirst: Boolean = false,
    val limit: Int = 100,
)

data class EventosOverview(val upcoming: Int, val registeredThisWeek: Int, val full: Int)

/** A rule of the module that fails: the message is shown to the user as is. */
class EventosValidationException(val errors: List<String>) : Exception(errors.joinToString(" ")) {
    constructor(message: String) : this(listOf(message))
}
