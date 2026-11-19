package com.ideasdeveloper.idc.modules.eventos

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure rules of Eventos (no database): capacity, waiting list, prices, balance, state changes and validation. */
object EventosRules {
    const val MaxTitle = 200
    const val MaxText = 10_000
    const val MaxCategory = 60
    const val MaxSeries = 120
    const val MaxSpace = 120
    const val MaxHost = 160
    const val MaxCapacity = 100_000
    const val MaxGuests = 100_000
    const val MaxSeats = 100
    const val MaxRegistrationNote = 1_000
    const val MaxPaymentNote = 200
    const val MaxPaymentMethod = 40
    const val MaxAbonoNote = 500
    const val MaxCancelReason = 500
    const val MaxListItems = 100
    const val MaxListItemLength = 120
    val MaxAmount: BigDecimal = BigDecimal("99999999.99")

    private val StampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val CurrencyPattern = Regex("^[A-Z]{3}$")

    // ---- capacity ----

    /** Seats taken: the sum of the seats of the registrations with state Registered. */
    fun occupied(registrations: Iterable<Registration>): Int =
        registrations.filter { it.status == RegistrationStatus.Registered }.sumOf { it.seats }

    /** Seats left; null when the event has no limit. Never negative. */
    fun available(capacity: Int?, occupied: Int): Int? = capacity?.let { (it - occupied).coerceAtLeast(0) }

    fun fits(capacity: Int?, occupied: Int, seats: Int): Boolean = capacity == null || occupied + seats <= capacity

    /** Registered when the seats fit and nobody is waiting (the waiting list keeps its order), otherwise on the waiting list. */
    fun decide(capacity: Int?, occupied: Int, seats: Int, waiting: Int = 0): RegistrationStatus =
        if (waiting == 0 && fits(capacity, occupied, seats)) RegistrationStatus.Registered else RegistrationStatus.Waitlist

    fun isFull(capacity: Int?, occupied: Int): Boolean = capacity != null && occupied >= capacity

    /** Published becomes Full when the seats are all taken and Full goes back to Published when one is free (or the limit is lifted). */
    fun statusAfterOccupancy(status: EventStatus, capacity: Int?, occupied: Int): EventStatus = when {
        status == EventStatus.Published && isFull(capacity, occupied) -> EventStatus.Full
        status == EventStatus.Full && !isFull(capacity, occupied) -> EventStatus.Published
        else -> status
    }

    /** The capacity cannot go below the seats already taken. Returns the error or null. */
    fun capacityError(newCapacity: Int?, occupied: Int): String? =
        if (newCapacity != null && newCapacity < occupied) "El cupo no puede ser menor que las plazas ocupadas ($occupied)." else null

    /** Whether somebody should be told that a seat was freed: a registered person left and there are people waiting. */
    fun freedSeatNotice(previous: RegistrationStatus, waiting: Int): Boolean = previous == RegistrationStatus.Registered && waiting > 0

    // ---- registering ----

    /** Why nobody can be registered in this event now, or null. Only public events that are published or full admit registrations. */
    fun registerError(kind: EventKind, status: EventStatus): String? = when {
        kind == EventKind.Private -> "Los eventos privados no tienen inscripciones."
        status == EventStatus.Draft -> "El evento es un borrador: publícalo para inscribir."
        status == EventStatus.Done -> "El evento ya se realizó."
        status == EventStatus.Cancelled -> "El evento está cancelado."
        else -> null
    }

    /** Member price when the event has one and the customer has a current membership; otherwise the general price. */
    fun proposedPriceKind(priceMember: BigDecimal?, hasMembership: Boolean): PriceKind =
        if (priceMember != null && hasMembership) PriceKind.Member else PriceKind.General

    /** Price x seats (a missing price is 0); a courtesy is free. */
    fun proposedAmount(kind: PriceKind, priceMember: BigDecimal?, pricePublic: BigDecimal?, seats: Int): BigDecimal {
        val unit = when (kind) {
            PriceKind.Courtesy -> BigDecimal.ZERO
            PriceKind.Member -> priceMember ?: BigDecimal.ZERO
            PriceKind.General -> pricePublic ?: BigDecimal.ZERO
        }
        return money(unit.multiply(BigDecimal(seats)))
    }

    /** The amount to store: 0 for a courtesy, the one the user typed, or the proposed one. */
    fun resolveAmount(kind: PriceKind, requested: BigDecimal?, priceMember: BigDecimal?, pricePublic: BigDecimal?, seats: Int): BigDecimal = when {
        kind == PriceKind.Courtesy -> money(BigDecimal.ZERO)
        requested != null -> money(requested)
        else -> proposedAmount(kind, priceMember, pricePublic, seats)
    }

    /** Agreed amount minus the sum of the payments; negative means paid over. */
    fun balance(agreed: BigDecimal?, payments: Iterable<BigDecimal>): BigDecimal =
        money((agreed ?: BigDecimal.ZERO).subtract(payments.fold(BigDecimal.ZERO) { total, payment -> total.add(payment) }))

    fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    fun validMoney(value: BigDecimal): Boolean =
        value.signum() >= 0 && value.stripTrailingZeros().scale() <= 2 && value <= MaxAmount

    /** A typed amount ("1250", "1250.5", "1250,50"); null when it is empty or not a number. */
    fun parseMoney(text: String?): BigDecimal? {
        val clean = text?.trim().orEmpty().replace(" ", "")
        if (clean.isEmpty()) return null
        val normalized = if (clean.contains(',') && !clean.contains('.')) clean.replace(',', '.') else clean.replace(",", "")
        return normalized.toBigDecimalOrNull()
    }

    // ---- attendance ----

    /** Why attendance cannot be marked, or null: only registered people, in a published, full or finished event that already started. */
    fun attendanceError(registration: RegistrationStatus, event: EventStatus, startsAt: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String? = when {
        registration != RegistrationStatus.Registered -> "Solo se registra la asistencia de personas inscritas."
        event == EventStatus.Cancelled -> "El evento está cancelado."
        event == EventStatus.Draft -> "El evento es un borrador."
        !startedBy(startsAt, now, zone) -> "El evento todavía no empieza: la asistencia se registra desde el día del evento."
        else -> null
    }

    /** The start date is today or earlier in the time zone. */
    fun startedBy(startsAt: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        !startsAt.atZone(zone).toLocalDate().isAfter(now.atZone(zone).toLocalDate())

    // ---- event states ----

    /** Draft to Published; Published or Full to Cancelled (with reason) or Done; Cancelled back to Draft. Returns the error or null. */
    fun transitionError(kind: EventKind, from: EventStatus, to: EventStatus, reason: String = ""): String? {
        val allowed = when (from) {
            EventStatus.Draft -> to == EventStatus.Published
            EventStatus.Published, EventStatus.Full -> to == EventStatus.Cancelled || to == EventStatus.Done
            EventStatus.Cancelled -> to == EventStatus.Draft
            EventStatus.Done -> false
        }
        return when {
            !allowed -> "No se puede pasar el evento de ${statusLabel(kind, from)} a ${statusLabel(kind, to)}."
            to == EventStatus.Cancelled && reason.isBlank() -> "Escribe el motivo de la cancelación."
            to == EventStatus.Cancelled && reason.length > MaxCancelReason -> "El motivo admite hasta $MaxCancelReason caracteres."
            else -> null
        }
    }

    /** The label of a state: a private event shows Published as "Confirmado" and Draft as "Tentativo". */
    fun statusLabel(kind: EventKind, status: EventStatus): String = when (status) {
        EventStatus.Draft -> if (kind == EventKind.Private) "Tentativo" else "Borrador"
        EventStatus.Published -> if (kind == EventKind.Private) "Confirmado" else "Publicado"
        EventStatus.Full -> "Lleno"
        EventStatus.Done -> "Realizado"
        EventStatus.Cancelled -> "Cancelado"
    }

    /** A private event that is confirmed: what writes "Evento privado" in the customer's history. */
    fun isBooked(kind: EventKind, status: EventStatus): Boolean = kind == EventKind.Private && status == EventStatus.Published

    // ---- validation ----

    private fun moneyError(label: String, value: BigDecimal?): String? =
        if (value != null && !validMoney(value)) "$label debe estar entre 0 y $MaxAmount, con 2 decimales como máximo." else null

    fun validateEvent(input: EventInput, requireClient: Boolean = false): List<String> = buildList {
        if (input.title.isBlank()) add("Escribe el título del evento.")
        if (input.title.trim().length > MaxTitle) add("El título admite hasta $MaxTitle caracteres.")
        if (input.description.length > MaxText) add("La descripción admite hasta $MaxText caracteres.")
        if (input.notes.length > MaxText) add("Las notas admiten hasta $MaxText caracteres.")
        if (input.category.trim().length > MaxCategory) add("La categoría admite hasta $MaxCategory caracteres.")
        if (input.series.trim().length > MaxSeries) add("La serie admite hasta $MaxSeries caracteres.")
        if (input.space.trim().length > MaxSpace) add("El espacio admite hasta $MaxSpace caracteres.")
        if (input.hostName.trim().length > MaxHost) add("El nombre del anfitrión admite hasta $MaxHost caracteres.")
        if (input.endsAt != null && input.endsAt.isBefore(input.startsAt)) add("La hora de fin no puede ser anterior al inicio.")
        if (input.capacity != null && input.capacity !in 1..MaxCapacity) add("El cupo debe estar entre 1 y $MaxCapacity.")
        if (input.guests != null && input.guests !in 1..MaxGuests) add("Los invitados deben estar entre 1 y $MaxGuests.")
        moneyError("El precio de miembro", input.priceMember)?.let { add(it) }
        moneyError("El precio general", input.pricePublic)?.let { add(it) }
        moneyError("El monto acordado", input.agreedAmount)?.let { add(it) }
        when (input.kind) {
            EventKind.Public -> {
                if (input.clientCustomerId != null || input.agreedAmount != null || input.guests != null) {
                    add("Un evento público no tiene contratante, monto acordado ni invitados.")
                }
            }
            EventKind.Private -> {
                if (input.capacity != null || input.priceMember != null || input.pricePublic != null) {
                    add("Un evento privado no tiene cupo ni precios por persona: usa el monto acordado.")
                }
                if (requireClient && input.clientCustomerId == null) add("Elige el cliente que contrata el evento.")
            }
        }
    }

    /** [seats] 1..100, [amount] valid, note up to 1000 characters. */
    fun validateRegistration(seats: Int, amount: BigDecimal?, note: String): List<String> = buildList {
        if (seats !in 1..MaxSeats) add("Las plazas deben estar entre 1 y $MaxSeats.")
        moneyError("El monto", amount)?.let { add(it) }
        if (note.length > MaxRegistrationNote) add("La nota admite hasta $MaxRegistrationNote caracteres.")
    }

    fun validatePayment(amount: BigDecimal, method: String, note: String): List<String> = buildList {
        if (amount.signum() <= 0) add("El abono debe ser mayor que 0.")
        moneyError("El abono", amount)?.let { add(it) }
        if (method.trim().length > MaxPaymentMethod) add("El método admite hasta $MaxPaymentMethod caracteres.")
        if (note.length > MaxAbonoNote) add("La nota admite hasta $MaxAbonoNote caracteres.")
    }

    fun validateSettings(currency: String, spaces: List<String>, categories: List<String>): List<String> = buildList {
        if (!CurrencyPattern.matches(currency.trim())) add("La moneda debe ser un código de 3 letras mayúsculas, por ejemplo USD.")
        if (spaces.size > MaxListItems) add("Hay demasiados espacios (máximo $MaxListItems).")
        if (categories.size > MaxListItems) add("Hay demasiadas categorías (máximo $MaxListItems).")
        if ((spaces + categories).any { it.length > MaxListItemLength }) add("Cada espacio o categoría admite hasta $MaxListItemLength caracteres.")
    }

    fun validCurrency(currency: String): Boolean = CurrencyPattern.matches(currency)

    // ---- text helpers ----

    /** Cuts [text] to [max] characters ending with "…" instead of failing. */
    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    /** Escapes LIKE wildcards so what the user types is matched literally. */
    fun escapeLike(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** One item per line (or comma-free text split by newlines): trimmed, without empties or repeats (ignoring case). */
    fun lines(text: String): List<String> = text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    fun stamp(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = instant.atZone(zone).format(StampFormat)

    fun day(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate = instant.atZone(zone).toLocalDate()

    /** "{n} plaza(s)" with the right number. */
    fun seatsLabel(seats: Int): String = if (seats == 1) "1 plaza" else "$seats plazas"
}
