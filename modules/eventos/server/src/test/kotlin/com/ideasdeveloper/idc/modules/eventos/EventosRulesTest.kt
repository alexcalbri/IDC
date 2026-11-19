package com.ideasdeveloper.idc.modules.eventos

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure rules: capacity, waiting list, full, member price, balance, state changes, attendance and validation. */
class EventosRulesTest {
    private val utc = ZoneId.of("UTC")

    private fun registration(status: RegistrationStatus, seats: Int = 1) = Registration(
        id = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        customerName = "Ana",
        status = status,
        seats = seats,
        priceKind = PriceKind.General,
        amount = BigDecimal.ZERO,
        paid = false,
        paidAt = null,
        paymentNote = "",
        attendedAt = null,
        attendedBy = "",
        waitlistedAt = null,
        note = "",
        cancelledAt = null,
        createdBy = "",
        createdAt = Instant.EPOCH,
        eventTitle = "Evento",
        eventStartsAt = Instant.EPOCH,
        eventCurrency = "USD",
    )

    private fun input(kind: EventKind = EventKind.Public, title: String = "Taller", capacity: Int? = null, start: Instant = Instant.parse("2026-12-01T18:00:00Z"), end: Instant? = null) =
        EventInput(kind = kind, title = title, startsAt = start, endsAt = end, capacity = capacity)

    // ---- capacity ----

    @Test
    fun occupiedCountsSeatsOfRegisteredPeopleOnly() {
        val all = listOf(
            registration(RegistrationStatus.Registered, 2),
            registration(RegistrationStatus.Registered, 1),
            registration(RegistrationStatus.Waitlist, 5),
            registration(RegistrationStatus.Cancelled, 3),
        )
        assertEquals(3, EventosRules.occupied(all))
    }

    @Test
    fun availableIsNullWithoutLimitAndNeverNegative() {
        assertNull(EventosRules.available(null, 10))
        assertEquals(4, EventosRules.available(10, 6))
        assertEquals(0, EventosRules.available(5, 9))
    }

    @Test
    fun seatsThatFitAreRegisteredAndTheRestWait() {
        assertEquals(RegistrationStatus.Registered, EventosRules.decide(10, 8, 2))
        assertEquals(RegistrationStatus.Waitlist, EventosRules.decide(10, 8, 3))
        assertEquals(RegistrationStatus.Registered, EventosRules.decide(null, 100_000, 100))
        assertEquals(RegistrationStatus.Waitlist, EventosRules.decide(10, 10, 1))
    }

    @Test
    fun waitingListKeepsItsOrder() {
        assertEquals(RegistrationStatus.Waitlist, EventosRules.decide(10, 2, 1, waiting = 1))
        assertEquals(RegistrationStatus.Registered, EventosRules.decide(10, 2, 1, waiting = 0))
    }

    @Test
    fun fullWhenSeatsAreAllTaken() {
        assertTrue(EventosRules.isFull(10, 10))
        assertFalse(EventosRules.isFull(10, 9))
        assertFalse(EventosRules.isFull(null, 1000))
    }

    @Test
    fun publishedBecomesFullAndGoesBack() {
        assertEquals(EventStatus.Full, EventosRules.statusAfterOccupancy(EventStatus.Published, 10, 10))
        assertEquals(EventStatus.Published, EventosRules.statusAfterOccupancy(EventStatus.Full, 10, 9))
        assertEquals(EventStatus.Published, EventosRules.statusAfterOccupancy(EventStatus.Full, null, 50))
        assertEquals(EventStatus.Draft, EventosRules.statusAfterOccupancy(EventStatus.Draft, 10, 10))
        assertEquals(EventStatus.Cancelled, EventosRules.statusAfterOccupancy(EventStatus.Cancelled, 10, 0))
        assertEquals(EventStatus.Done, EventosRules.statusAfterOccupancy(EventStatus.Done, 10, 10))
    }

    @Test
    fun capacityCannotGoBelowOccupiedSeats() {
        assertNotNull(EventosRules.capacityError(4, 5))
        assertNull(EventosRules.capacityError(5, 5))
        assertNull(EventosRules.capacityError(null, 50))
    }

    @Test
    fun freedSeatNoticeNeedsARegisteredPersonAndPeopleWaiting() {
        assertTrue(EventosRules.freedSeatNotice(RegistrationStatus.Registered, 2))
        assertFalse(EventosRules.freedSeatNotice(RegistrationStatus.Registered, 0))
        assertFalse(EventosRules.freedSeatNotice(RegistrationStatus.Waitlist, 2))
    }

    // ---- registering and prices ----

    @Test
    fun onlyPublishedOrFullPublicEventsAdmitRegistrations() {
        assertNull(EventosRules.registerError(EventKind.Public, EventStatus.Published))
        assertNull(EventosRules.registerError(EventKind.Public, EventStatus.Full))
        assertNotNull(EventosRules.registerError(EventKind.Public, EventStatus.Draft))
        assertNotNull(EventosRules.registerError(EventKind.Public, EventStatus.Done))
        assertNotNull(EventosRules.registerError(EventKind.Public, EventStatus.Cancelled))
        assertNotNull(EventosRules.registerError(EventKind.Private, EventStatus.Published))
    }

    @Test
    fun memberPriceIsProposedOnlyWithMembershipAndAMemberPrice() {
        val price = BigDecimal("10")
        assertEquals(PriceKind.Member, EventosRules.proposedPriceKind(price, true))
        assertEquals(PriceKind.General, EventosRules.proposedPriceKind(price, false))
        assertEquals(PriceKind.General, EventosRules.proposedPriceKind(null, true))
    }

    @Test
    fun amountIsPriceTimesSeatsAndMissingPriceIsZero() {
        val member = BigDecimal("12.50")
        val general = BigDecimal("20")
        assertEquals(BigDecimal("25.00"), EventosRules.proposedAmount(PriceKind.Member, member, general, 2))
        assertEquals(BigDecimal("60.00"), EventosRules.proposedAmount(PriceKind.General, member, general, 3))
        assertEquals(BigDecimal("0.00"), EventosRules.proposedAmount(PriceKind.General, member, null, 3))
        assertEquals(BigDecimal("0.00"), EventosRules.proposedAmount(PriceKind.Courtesy, member, general, 3))
    }

    @Test
    fun courtesyIsAlwaysFreeAndATypedAmountWins() {
        val price = BigDecimal("20")
        assertEquals(BigDecimal("0.00"), EventosRules.resolveAmount(PriceKind.Courtesy, BigDecimal("99"), price, price, 2))
        assertEquals(BigDecimal("15.00"), EventosRules.resolveAmount(PriceKind.General, BigDecimal("15"), price, price, 2))
        assertEquals(BigDecimal("40.00"), EventosRules.resolveAmount(PriceKind.General, null, price, price, 2))
    }

    @Test
    fun balanceIsAgreedMinusPaymentsAndCanBeNegative() {
        val payments = listOf(BigDecimal("100"), BigDecimal("250.50"))
        assertEquals(BigDecimal("649.50"), EventosRules.balance(BigDecimal("1000"), payments))
        assertEquals(BigDecimal("-50.00"), EventosRules.balance(BigDecimal("300"), listOf(BigDecimal("350"))))
        assertEquals(BigDecimal("-10.00"), EventosRules.balance(null, listOf(BigDecimal("10"))))
        assertEquals(BigDecimal("500.00"), EventosRules.balance(BigDecimal("500"), emptyList()))
    }

    @Test
    fun parsesTypedAmounts() {
        assertEquals(BigDecimal("1250"), EventosRules.parseMoney("1250"))
        assertEquals(BigDecimal("1250.50"), EventosRules.parseMoney("1250,50"))
        assertEquals(BigDecimal("1250.50"), EventosRules.parseMoney("1,250.50"))
        assertNull(EventosRules.parseMoney(""))
        assertNull(EventosRules.parseMoney("abc"))
    }

    // ---- attendance ----

    @Test
    fun attendanceOnlyForRegisteredPeopleOnceTheEventStarted() {
        val start = Instant.parse("2026-12-01T18:00:00Z")
        val sameDay = Instant.parse("2026-12-01T08:00:00Z")
        val dayBefore = Instant.parse("2026-11-30T23:00:00Z")
        assertNull(EventosRules.attendanceError(RegistrationStatus.Registered, EventStatus.Published, start, sameDay, utc))
        assertNull(EventosRules.attendanceError(RegistrationStatus.Registered, EventStatus.Done, start, sameDay.plusSeconds(86_400), utc))
        assertNotNull(EventosRules.attendanceError(RegistrationStatus.Registered, EventStatus.Published, start, dayBefore, utc))
        assertNotNull(EventosRules.attendanceError(RegistrationStatus.Waitlist, EventStatus.Published, start, sameDay, utc))
        assertNotNull(EventosRules.attendanceError(RegistrationStatus.Cancelled, EventStatus.Published, start, sameDay, utc))
        assertNotNull(EventosRules.attendanceError(RegistrationStatus.Registered, EventStatus.Cancelled, start, sameDay, utc))
        assertNotNull(EventosRules.attendanceError(RegistrationStatus.Registered, EventStatus.Draft, start, sameDay, utc))
    }

    // ---- event states ----

    @Test
    fun allowedTransitions() {
        val kind = EventKind.Public
        assertNull(EventosRules.transitionError(kind, EventStatus.Draft, EventStatus.Published))
        assertNull(EventosRules.transitionError(kind, EventStatus.Published, EventStatus.Done))
        assertNull(EventosRules.transitionError(kind, EventStatus.Full, EventStatus.Done))
        assertNull(EventosRules.transitionError(kind, EventStatus.Published, EventStatus.Cancelled, "Lluvia"))
        assertNull(EventosRules.transitionError(kind, EventStatus.Full, EventStatus.Cancelled, "Lluvia"))
        assertNull(EventosRules.transitionError(kind, EventStatus.Cancelled, EventStatus.Draft))
    }

    @Test
    fun forbiddenTransitions() {
        val kind = EventKind.Public
        assertNotNull(EventosRules.transitionError(kind, EventStatus.Draft, EventStatus.Done))
        assertNotNull(EventosRules.transitionError(kind, EventStatus.Draft, EventStatus.Cancelled, "x"))
        assertNotNull(EventosRules.transitionError(kind, EventStatus.Done, EventStatus.Draft))
        assertNotNull(EventosRules.transitionError(kind, EventStatus.Done, EventStatus.Cancelled, "x"))
        assertNotNull(EventosRules.transitionError(kind, EventStatus.Cancelled, EventStatus.Published))
        assertNotNull(EventosRules.transitionError(kind, EventStatus.Published, EventStatus.Draft))
    }

    @Test
    fun cancellingNeedsAReason() {
        assertNotNull(EventosRules.transitionError(EventKind.Public, EventStatus.Published, EventStatus.Cancelled, "  "))
        assertNotNull(EventosRules.transitionError(EventKind.Public, EventStatus.Published, EventStatus.Cancelled, "x".repeat(501)))
    }

    @Test
    fun privateEventsShowConfirmedAndTentative() {
        assertEquals("Confirmado", EventosRules.statusLabel(EventKind.Private, EventStatus.Published))
        assertEquals("Tentativo", EventosRules.statusLabel(EventKind.Private, EventStatus.Draft))
        assertEquals("Publicado", EventosRules.statusLabel(EventKind.Public, EventStatus.Published))
        assertEquals("Borrador", EventosRules.statusLabel(EventKind.Public, EventStatus.Draft))
        assertTrue(EventosRules.isBooked(EventKind.Private, EventStatus.Published))
        assertFalse(EventosRules.isBooked(EventKind.Private, EventStatus.Draft))
        assertFalse(EventosRules.isBooked(EventKind.Public, EventStatus.Published))
    }

    @Test
    fun statusCodesRoundTrip() {
        EventStatus.entries.forEach { assertEquals(it, EventStatus.fromCode(it.code)) }
        assertEquals(EventStatus.Full, EventStatus.fromCode(" LLENO "))
        assertNull(EventStatus.fromCode("otro"))
        assertEquals(EventKind.Private, EventKind.fromCode("privado"))
        assertEquals(RegistrationStatus.Waitlist, RegistrationStatus.fromCode("espera"))
        assertEquals(PriceKind.Courtesy, PriceKind.fromCode("cortesia"))
    }

    // ---- validation ----

    @Test
    fun aValidPublicEventHasNoErrors() {
        assertTrue(EventosRules.validateEvent(input(capacity = 20)).isEmpty())
    }

    @Test
    fun titleIsRequiredAndLimited() {
        assertTrue(EventosRules.validateEvent(input(title = "  ")).isNotEmpty())
        assertTrue(EventosRules.validateEvent(input(title = "x".repeat(201))).isNotEmpty())
        assertTrue(EventosRules.validateEvent(input(title = "x".repeat(200))).isEmpty())
    }

    @Test
    fun endCannotBeBeforeStart() {
        val start = Instant.parse("2026-12-01T18:00:00Z")
        assertTrue(EventosRules.validateEvent(input(start = start, end = start.minusSeconds(60))).isNotEmpty())
        assertTrue(EventosRules.validateEvent(input(start = start, end = start)).isEmpty())
    }

    @Test
    fun capacityAndGuestsHaveLimits() {
        assertTrue(EventosRules.validateEvent(input(capacity = 0)).isNotEmpty())
        assertTrue(EventosRules.validateEvent(input(capacity = 100_001)).isNotEmpty())
        assertTrue(EventosRules.validateEvent(input(capacity = 100_000)).isEmpty())
        val privateEvent = EventInput(EventKind.Private, "Boda", startsAt = Instant.EPOCH, guests = 0)
        assertTrue(EventosRules.validateEvent(privateEvent).isNotEmpty())
    }

    @Test
    fun amountsAreBoundedWithTwoDecimals() {
        val tooMuch = EventInput(EventKind.Public, "Taller", startsAt = Instant.EPOCH, pricePublic = BigDecimal("100000000"))
        val threeDecimals = EventInput(EventKind.Public, "Taller", startsAt = Instant.EPOCH, pricePublic = BigDecimal("1.005"))
        val negative = EventInput(EventKind.Public, "Taller", startsAt = Instant.EPOCH, priceMember = BigDecimal("-1"))
        val limit = EventInput(EventKind.Public, "Taller", startsAt = Instant.EPOCH, pricePublic = BigDecimal("99999999.99"))
        assertTrue(EventosRules.validateEvent(tooMuch).isNotEmpty())
        assertTrue(EventosRules.validateEvent(threeDecimals).isNotEmpty())
        assertTrue(EventosRules.validateEvent(negative).isNotEmpty())
        assertTrue(EventosRules.validateEvent(limit).isEmpty())
    }

    @Test
    fun longTextsAreRejected() {
        val long = "x".repeat(10_001)
        assertTrue(EventosRules.validateEvent(EventInput(EventKind.Public, "T", description = long, startsAt = Instant.EPOCH)).isNotEmpty())
        assertTrue(EventosRules.validateEvent(EventInput(EventKind.Public, "T", notes = long, startsAt = Instant.EPOCH)).isNotEmpty())
        assertTrue(EventosRules.validateEvent(EventInput(EventKind.Public, "T", series = "x".repeat(121), startsAt = Instant.EPOCH)).isNotEmpty())
    }

    @Test
    fun publicAndPrivateEventsKeepTheirOwnFields() {
        val publicWithClient = EventInput(EventKind.Public, "T", startsAt = Instant.EPOCH, clientCustomerId = UUID.randomUUID())
        val publicWithAgreed = EventInput(EventKind.Public, "T", startsAt = Instant.EPOCH, agreedAmount = BigDecimal.TEN)
        val privateWithCapacity = EventInput(EventKind.Private, "T", startsAt = Instant.EPOCH, capacity = 10)
        val privateWithPrice = EventInput(EventKind.Private, "T", startsAt = Instant.EPOCH, pricePublic = BigDecimal.TEN)
        assertTrue(EventosRules.validateEvent(publicWithClient).isNotEmpty())
        assertTrue(EventosRules.validateEvent(publicWithAgreed).isNotEmpty())
        assertTrue(EventosRules.validateEvent(privateWithCapacity).isNotEmpty())
        assertTrue(EventosRules.validateEvent(privateWithPrice).isNotEmpty())
    }

    @Test
    fun privateEventNeedsItsClientOnlyWhenAsked() {
        val withoutClient = EventInput(EventKind.Private, "Boda", startsAt = Instant.EPOCH)
        assertTrue(EventosRules.validateEvent(withoutClient).isEmpty())
        assertTrue(EventosRules.validateEvent(withoutClient, requireClient = true).isNotEmpty())
        assertTrue(EventosRules.validateEvent(withoutClient.copy(clientCustomerId = UUID.randomUUID()), requireClient = true).isEmpty())
    }

    @Test
    fun registrationValidation() {
        assertTrue(EventosRules.validateRegistration(1, null, "").isEmpty())
        assertTrue(EventosRules.validateRegistration(100, BigDecimal("10.50"), "").isEmpty())
        assertTrue(EventosRules.validateRegistration(0, null, "").isNotEmpty())
        assertTrue(EventosRules.validateRegistration(101, null, "").isNotEmpty())
        assertTrue(EventosRules.validateRegistration(1, BigDecimal("-1"), "").isNotEmpty())
        assertTrue(EventosRules.validateRegistration(1, null, "x".repeat(1001)).isNotEmpty())
    }

    @Test
    fun paymentValidation() {
        assertTrue(EventosRules.validatePayment(BigDecimal("100"), "Transferencia", "").isEmpty())
        assertTrue(EventosRules.validatePayment(BigDecimal.ZERO, "", "").isNotEmpty())
        assertTrue(EventosRules.validatePayment(BigDecimal("100"), "x".repeat(41), "").isNotEmpty())
        assertTrue(EventosRules.validatePayment(BigDecimal("100"), "", "x".repeat(501)).isNotEmpty())
    }

    @Test
    fun settingsValidation() {
        assertTrue(EventosRules.validateSettings("USD", listOf("Sala"), listOf("Taller")).isEmpty())
        assertTrue(EventosRules.validateSettings("usd", emptyList(), emptyList()).isNotEmpty())
        assertTrue(EventosRules.validateSettings("US", emptyList(), emptyList()).isNotEmpty())
        assertTrue(EventosRules.validateSettings("USD", List(101) { "e$it" }, emptyList()).isNotEmpty())
        assertTrue(EventosRules.validateSettings("USD", listOf("x".repeat(121)), emptyList()).isNotEmpty())
    }

    // ---- text helpers ----

    @Test
    fun clipEndsWithAnEllipsis() {
        assertEquals("abc", EventosRules.clip("abc", 5))
        assertEquals("abcd…", EventosRules.clip("abcdefgh", 5))
    }

    @Test
    fun escapeLikeEscapesWildcards() {
        assertEquals("100\\% a\\_b \\\\ c", EventosRules.escapeLike("100% a_b \\ c"))
    }

    @Test
    fun linesAreTrimmedWithoutEmptiesOrRepeats() {
        assertEquals(listOf("Sala A", "patio"), EventosRules.lines(" Sala A \r\n\n patio\nPatio\n sala a "))
    }

    @Test
    fun seatsLabelUsesTheRightNumber() {
        assertEquals("1 plaza", EventosRules.seatsLabel(1))
        assertEquals("3 plazas", EventosRules.seatsLabel(3))
    }
}
