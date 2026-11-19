package com.ideasdeveloper.idc.modules.membresia

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembresiaRulesTest {
    private val today = LocalDate.of(2026, 11, 18)

    private fun membership(
        startsOn: LocalDate = today.minusDays(10),
        endsOn: LocalDate = today.plusDays(20),
        status: String = StoredStatus.Activa,
        includedVisits: Int? = null,
        visitsUsed: Int = 0,
        guestTotal: Int = 0,
        guestUsed: Int = 0,
    ) = Membership(
        id = UUID.randomUUID(), customerId = UUID.randomUUID(), customerName = "Ana", planId = UUID.randomUUID(), planName = "Mensual",
        startsOn = startsOn, endsOn = endsOn, amount = BigDecimal("50.00"), currency = "USD", status = status, source = "", hostUserId = null,
        hostName = "", unit = "", notes = "", includedVisits = includedVisits, visitsUsed = visitsUsed, guestPassesTotal = guestTotal,
        guestPassesUsed = guestUsed, renewedFrom = null, cancelledAt = null, cancelReason = "", expiringNotifiedOn = null, createdBy = "",
    )

    private fun row(on: LocalDate, plan: String, amount: String, status: String = StoredStatus.Activa, currency: String = "USD") =
        RevenueRow(on, plan, currency, BigDecimal(amount), 1, status)

    @Test
    fun endsOnIsInclusive() {
        assertEquals(LocalDate.of(2026, 11, 18), MembresiaRules.endsOn(LocalDate.of(2026, 11, 18), 1))
        assertEquals(LocalDate.of(2026, 12, 17), MembresiaRules.endsOn(LocalDate.of(2026, 11, 18), 30))
    }

    @Test
    fun renewalStartsTheDayAfterOrTodayWhenExpired() {
        assertEquals(today.plusDays(6), MembresiaRules.renewalStart(today.plusDays(5), today))
        assertEquals(today.plusDays(1), MembresiaRules.renewalStart(today, today))
        assertEquals(today, MembresiaRules.renewalStart(today.minusDays(1), today))
        assertEquals(today, MembresiaRules.renewalStart(today.minusDays(40), today))
    }

    @Test
    fun initialStatusCoversFutureRetroactiveAndCurrent() {
        assertEquals(StoredStatus.Programada, MembresiaRules.initialStatus(today.plusDays(1), today.plusDays(30), today))
        assertEquals(StoredStatus.Vencida, MembresiaRules.initialStatus(today.minusDays(40), today.minusDays(1), today))
        assertEquals(StoredStatus.Activa, MembresiaRules.initialStatus(today, today.plusDays(30), today))
        assertEquals(StoredStatus.Activa, MembresiaRules.initialStatus(today.minusDays(5), today, today))
    }

    @Test
    fun displayStatusAtEveryBoundary() {
        fun status(stored: String, start: LocalDate, end: LocalDate) = MembresiaRules.displayStatus(stored, start, end, today, 7)
        assertEquals(DisplayStatus.POR_VENCER, status(StoredStatus.Activa, today.minusDays(3), today))
        assertEquals(DisplayStatus.VENCIDA, status(StoredStatus.Activa, today.minusDays(30), today.minusDays(1)))
        assertEquals(DisplayStatus.POR_VENCER, status(StoredStatus.Activa, today.minusDays(3), today.plusDays(7)))
        assertEquals(DisplayStatus.ACTIVA, status(StoredStatus.Activa, today.minusDays(3), today.plusDays(8)))
        assertEquals(DisplayStatus.PROGRAMADA, status(StoredStatus.Programada, today.plusDays(1), today.plusDays(30)))
        assertEquals(DisplayStatus.ACTIVA, status(StoredStatus.Programada, today, today.plusDays(30)))
        assertEquals(DisplayStatus.CANCELADA, status(StoredStatus.Cancelada, today.minusDays(3), today.plusDays(30)))
        assertEquals(DisplayStatus.CANCELADA, status(StoredStatus.Cancelada, today.minusDays(30), today.minusDays(1)))
        // Guardada como activa pero ya terminada: la pantalla es correcta aunque el trabajo no haya corrido.
        assertEquals(DisplayStatus.VENCIDA, status(StoredStatus.Activa, today.minusDays(30), today.minusDays(2)))
    }

    @Test
    fun visitsAndGuestPassesLeft() {
        assertNull(MembresiaRules.visitsLeft(membership(includedVisits = null, visitsUsed = 40)))
        assertEquals(7, MembresiaRules.visitsLeft(membership(includedVisits = 10, visitsUsed = 3)))
        assertEquals(0, MembresiaRules.visitsLeft(membership(includedVisits = 10, visitsUsed = 12)))
        assertEquals(2, MembresiaRules.guestPassesLeft(membership(guestTotal = 3, guestUsed = 1)))
    }

    @Test
    fun checkInAllowsCurrentMembership() {
        assertEquals(CheckInDecision.Allowed(), MembresiaRules.checkIn(membership(), today, 7, override = false))
        assertEquals(CheckInDecision.Allowed(), MembresiaRules.checkIn(membership(endsOn = today.plusDays(2)), today, 7, override = false))
        assertEquals(CheckInDecision.Allowed(), MembresiaRules.checkIn(membership(includedVisits = 10, visitsUsed = 9), today, 7, override = false))
    }

    @Test
    fun checkInWithNoVisitsLeftNeedsOverride() {
        val exhausted = membership(includedVisits = 5, visitsUsed = 5)
        assertIs<CheckInDecision.NeedsOverride>(MembresiaRules.checkIn(exhausted, today, 7, override = false))
        assertEquals(CheckInDecision.Allowed(overridden = true), MembresiaRules.checkIn(exhausted, today, 7, override = true))
    }

    @Test
    fun checkInOfCancelledIsNeverAllowed() {
        val cancelled = membership(status = StoredStatus.Cancelada)
        assertIs<CheckInDecision.Refused>(MembresiaRules.checkIn(cancelled, today, 7, override = false))
        assertIs<CheckInDecision.Refused>(MembresiaRules.checkIn(cancelled, today, 7, override = true))
    }

    @Test
    fun checkInOfExpiredOrScheduledNeedsOverride() {
        val expired = membership(startsOn = today.minusDays(40), endsOn = today.minusDays(1))
        assertIs<CheckInDecision.NeedsOverride>(MembresiaRules.checkIn(expired, today, 7, override = false))
        assertEquals(CheckInDecision.Allowed(overridden = true), MembresiaRules.checkIn(expired, today, 7, override = true))
        val scheduled = membership(startsOn = today.plusDays(3), endsOn = today.plusDays(33), status = StoredStatus.Programada)
        assertIs<CheckInDecision.NeedsOverride>(MembresiaRules.checkIn(scheduled, today, 7, override = false))
    }

    @Test
    fun guestPassNeedsCurrentMembershipAndPassesLeft() {
        assertEquals(CheckInDecision.Allowed(), MembresiaRules.guestPassAllowed(membership(guestTotal = 2, guestUsed = 1), today, 7))
        assertIs<CheckInDecision.Refused>(MembresiaRules.guestPassAllowed(membership(guestTotal = 2, guestUsed = 2), today, 7))
        assertIs<CheckInDecision.Refused>(MembresiaRules.guestPassAllowed(membership(guestTotal = 0), today, 7))
        assertIs<CheckInDecision.Refused>(MembresiaRules.guestPassAllowed(membership(guestTotal = 2, endsOn = today.minusDays(1)), today, 7))
        assertIs<CheckInDecision.Refused>(MembresiaRules.guestPassAllowed(membership(guestTotal = 2, status = StoredStatus.Cancelada), today, 7))
    }

    @Test
    fun currentMembershipPrefersCurrentThenScheduledThenLatest() {
        val old = membership(startsOn = today.minusDays(100), endsOn = today.minusDays(70), status = StoredStatus.Vencida)
        val current = membership()
        val scheduled = membership(startsOn = today.plusDays(21), endsOn = today.plusDays(50), status = StoredStatus.Programada)
        assertEquals(current, MembresiaRules.currentMembership(listOf(old, scheduled, current), today, 7))
        assertEquals(scheduled, MembresiaRules.currentMembership(listOf(old, scheduled), today, 7))
        assertEquals(old, MembresiaRules.currentMembership(listOf(old), today, 7))
        assertNull(MembresiaRules.currentMembership(emptyList(), today, 7))
    }

    @Test
    fun parseAmountAcceptsTwoDecimalsInRange() {
        assertEquals(BigDecimal("25.50"), MembresiaRules.parseAmount("25.5"))
        assertEquals(BigDecimal("25.50"), MembresiaRules.parseAmount(" 25,5 "))
        assertEquals(BigDecimal("0.00"), MembresiaRules.parseAmount("0"))
        assertEquals(BigDecimal("99999999.99"), MembresiaRules.parseAmount("99999999.99"))
        assertNull(MembresiaRules.parseAmount("100000000"))
        assertNull(MembresiaRules.parseAmount("10.999"))
        assertNull(MembresiaRules.parseAmount("-5"))
        assertNull(MembresiaRules.parseAmount("abc"))
        assertNull(MembresiaRules.parseAmount(""))
        assertNull(MembresiaRules.parseAmount(null))
    }

    @Test
    fun escapeLikeEscapesWildcards() {
        assertEquals("100\\% a\\_b \\\\ c", MembresiaRules.escapeLike("100% a_b \\ c"))
        assertEquals("ana", MembresiaRules.escapeLike("ana"))
    }

    @Test
    fun planValidation() {
        val valid = PlanInput("Mensual", "", BigDecimal("50.00"), 30, 12, 2)
        assertEquals(emptyList(), MembresiaRules.validatePlan(valid))
        assertTrue(MembresiaRules.validatePlan(valid.copy(name = "  ")).isNotEmpty())
        assertTrue(MembresiaRules.validatePlan(valid.copy(name = "x".repeat(81))).isNotEmpty())
        assertTrue(MembresiaRules.validatePlan(valid.copy(price = BigDecimal("-1"))).isNotEmpty())
        assertTrue(MembresiaRules.validatePlan(valid.copy(price = BigDecimal("1.005"))).isNotEmpty())
        assertTrue(MembresiaRules.validatePlan(valid.copy(durationDays = 0)).isNotEmpty())
        assertTrue(MembresiaRules.validatePlan(valid.copy(durationDays = 3661)).isNotEmpty())
        assertTrue(MembresiaRules.validatePlan(valid.copy(includedVisits = 0)).isNotEmpty())
        assertEquals(emptyList(), MembresiaRules.validatePlan(valid.copy(includedVisits = null)))
        assertTrue(MembresiaRules.validatePlan(valid.copy(guestPasses = 1001)).isNotEmpty())
    }

    @Test
    fun membershipValidation() {
        val settings = MembresiaSettings.Default
        val input = MembershipInput(UUID.randomUUID(), UUID.randomUUID(), source = "Referido", unit = "A-1", notes = "ok")
        assertEquals(emptyList(), MembresiaRules.validateMembership(input, BigDecimal("10.00"), settings))
        assertEquals(emptyList(), MembresiaRules.validateMembership(input.copy(source = ""), BigDecimal("10.00"), settings))
        assertTrue(MembresiaRules.validateMembership(input.copy(source = "Radio"), BigDecimal("10.00"), settings).isNotEmpty())
        assertTrue(MembresiaRules.validateMembership(input, BigDecimal("100000000"), settings).isNotEmpty())
        assertTrue(MembresiaRules.validateMembership(input.copy(notes = "x".repeat(4001)), BigDecimal("10.00"), settings).isNotEmpty())
        assertTrue(MembresiaRules.validateMembership(input.copy(unit = "x".repeat(81)), BigDecimal("10.00"), settings).isNotEmpty())
    }

    @Test
    fun editValidationRejectsEndBeforeStart() {
        val edit = MembershipEdit(endsOn = today.minusDays(11), amount = BigDecimal("10.00"))
        assertTrue(MembresiaRules.validateEdit(edit, today.minusDays(10), MembresiaSettings.Default, today).isNotEmpty())
        assertEquals(emptyList(), MembresiaRules.validateEdit(edit.copy(endsOn = today), today.minusDays(10), MembresiaSettings.Default, today))
    }

    @Test
    fun settingsValidation() {
        val valid = MembresiaSettings("USD", 7, listOf("Referido", "Otro"))
        assertEquals(emptyList(), MembresiaRules.validateSettings(valid))
        assertTrue(MembresiaRules.validateSettings(valid.copy(currency = "usd")).isNotEmpty())
        assertTrue(MembresiaRules.validateSettings(valid.copy(currency = "US")).isNotEmpty())
        assertTrue(MembresiaRules.validateSettings(valid.copy(expiringDays = 0)).isNotEmpty())
        assertTrue(MembresiaRules.validateSettings(valid.copy(expiringDays = 61)).isNotEmpty())
        assertTrue(MembresiaRules.validateSettings(valid.copy(sources = listOf("A", "a"))).isNotEmpty())
        assertTrue(MembresiaRules.validateSettings(valid.copy(sources = listOf("x".repeat(41)))).isNotEmpty())
    }

    @Test
    fun parseSourcesTrimsAndDeduplicates() {
        assertEquals(listOf("Referido", "Walk-in"), MembresiaRules.parseSources(" Referido \n\nWalk-in\nreferido\nwalk-in"))
    }

    @Test
    fun revenueByMonthExcludesCancelledGroupsAndFillsTwelveMonths() {
        val rows = listOf(
            row(LocalDate.of(2026, 11, 2), "Mensual", "50.00"),
            row(LocalDate.of(2026, 11, 15), "Anual", "400.00"),
            row(LocalDate.of(2026, 11, 20), "Mensual", "50.00", StoredStatus.Cancelada),
            row(LocalDate.of(2026, 9, 1), "Mensual", "50.00"),
        )
        val months = MembresiaRules.revenueByMonth(rows, today, 12, "USD")
        assertEquals(12, months.size)
        assertEquals(YearMonth.of(2025, 12), months.first().month)
        assertEquals(YearMonth.of(2026, 11), months.last().month)
        assertEquals(BigDecimal("450.00"), months.last().amount)
        assertEquals(2, months.last().count)
        assertEquals(BigDecimal("50.00"), months.first { it.month == YearMonth.of(2026, 9) }.amount)
        assertEquals(BigDecimal("0.00"), months.first { it.month == YearMonth.of(2026, 10) }.amount)
        assertEquals(0, months.first { it.month == YearMonth.of(2026, 10) }.count)
    }

    @Test
    fun revenueByPlanSumsAndSortsDescendingIgnoringCancelledAndOldRows() {
        val rows = listOf(
            row(LocalDate.of(2026, 11, 2), "Mensual", "50.00"),
            row(LocalDate.of(2026, 10, 2), "Mensual", "50.00"),
            row(LocalDate.of(2026, 11, 15), "Anual", "400.00"),
            row(LocalDate.of(2026, 11, 16), "Anual", "400.00", StoredStatus.Cancelada),
            row(LocalDate.of(2024, 1, 1), "Antiguo", "999.00"),
        )
        val byPlan = MembresiaRules.revenueByPlan(rows, today, 12, "USD")
        assertEquals(listOf("Anual", "Mensual"), byPlan.map { it.planName })
        assertEquals(BigDecimal("100.00"), byPlan.last().amount)
        assertEquals(2, byPlan.last().count)
    }

    @Test
    fun datesOutsideTheAllowedRangeAreRejected() {
        assertEquals(emptyList(), MembresiaRules.validateDates(today, today.plusDays(29), today))
        assertEquals(emptyList(), MembresiaRules.validateDates(today.minusYears(5), today.minusYears(5).plusDays(30), today))
        assertEquals(emptyList(), MembresiaRules.validateDates(today.plusYears(2), today.plusYears(2).plusDays(30), today))
        assertTrue(MembresiaRules.validateDates(today.minusYears(5).minusDays(1), today, today).isNotEmpty())
        assertTrue(MembresiaRules.validateDates(today.plusYears(2).plusDays(1), today.plusYears(3), today).isNotEmpty())
        assertTrue(MembresiaRules.validateDates(today, today.minusDays(1), today).isNotEmpty())
        assertEquals(emptyList(), MembresiaRules.validateDates(today, today.plusDays(3660), today))
        assertTrue(MembresiaRules.validateDates(today, today.plusDays(3661), today).isNotEmpty())
        // Editar una membresia antigua no exige que el inicio siga dentro del rango.
        assertEquals(emptyList(), MembresiaRules.validateDates(today.minusYears(9), today.minusYears(9).plusDays(30), today, checkStart = false))
    }

    @Test
    fun editRejectsADurationOverTheLimit() {
        val edit = MembershipEdit(endsOn = today.plusDays(4000), amount = BigDecimal("10.00"))
        assertTrue(MembresiaRules.validateEdit(edit, today, MembresiaSettings.Default, today).isNotEmpty())
    }

    @Test
    fun revenueIsSeparatedByCurrency() {
        val rows = listOf(
            row(LocalDate.of(2026, 11, 2), "Mensual", "50.00"),
            row(LocalDate.of(2026, 11, 3), "Mensual", "40.00", currency = "EUR"),
            row(LocalDate.of(2026, 10, 3), "Anual", "400.00", currency = "EUR"),
        )
        assertEquals(BigDecimal("50.00"), MembresiaRules.revenueByMonth(rows, today, 12, "USD").last().amount)
        assertEquals(BigDecimal("40.00"), MembresiaRules.revenueByMonth(rows, today, 12, "EUR").last().amount)
        assertEquals(listOf("Mensual"), MembresiaRules.revenueByPlan(rows, today, 12, "USD").map { it.planName })
        assertEquals(listOf("Anual", "Mensual"), MembresiaRules.revenueByPlan(rows, today, 12, "EUR").map { it.planName })
        assertEquals(BigDecimal("0.00"), MembresiaRules.revenueByMonth(rows, today, 12, "COP").sumOf { it.amount })
    }

    @Test
    fun aggregatedRowsSumTheirCounts() {
        val rows = listOf(RevenueRow(LocalDate.of(2026, 11, 1), "Mensual", "USD", BigDecimal("150.00"), 3))
        val month = MembresiaRules.revenueByMonth(rows, today, 12, "USD").last()
        assertEquals(3, month.count)
        assertEquals(BigDecimal("150.00"), month.amount)
    }
}
