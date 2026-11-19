package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.ui.UiOpenModule
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MembresiaCustomerTimelineTest {
    private val customerId = UUID.randomUUID()

    private fun row(
        startsOn: LocalDate = LocalDate.of(2026, 11, 1),
        endsOn: LocalDate = LocalDate.of(2026, 11, 30),
        status: String = StoredStatus.Activa,
        guestTotal: Int = 0,
        guestUsed: Int = 0,
        renewal: Boolean = false,
        createdAt: Instant = Instant.parse("2026-10-30T12:00:00Z"),
        cancelledAt: Instant? = null,
    ) = MembershipTimelineRow(
        planName = "Mensual", startsOn = startsOn, endsOn = endsOn, amount = BigDecimal("50.00"), currency = "USD", status = status,
        guestPassesTotal = guestTotal, guestPassesUsed = guestUsed, renewal = renewal, createdAt = createdAt, cancelledAt = cancelledAt,
    )

    private fun entries(
        memberships: List<MembershipTimelineRow> = emptyList(),
        visits: List<VisitMonthRow> = emptyList(),
        limit: Int = 50,
    ) = MembresiaCustomerTimeline.entries(memberships, visits, customerId, limit)

    @Test
    fun usesViewPermission() {
        assertEquals(MembresiaModule.View, MembresiaCustomerTimeline.timeline.permission)
    }

    @Test
    fun createdMembershipHasTitleKindDetailAndLink() {
        val entry = entries(listOf(row(guestTotal = 3, guestUsed = 1))).single()
        assertEquals("membresia_alta", entry.kind)
        assertEquals("Membresía Mensual", entry.title)
        assertEquals("2026-11-01 a 2026-11-30 · 50.00 USD\nPases de invitado: 1/3", entry.detail)
        val link = assertIs<UiOpenModule>(entry.link)
        assertEquals("membresia", link.moduleId)
        assertEquals("miembro", link.screen)
        assertEquals(mapOf("customer" to customerId.toString()), link.params)
    }

    @Test
    fun guestPassesAreOmittedWhenThePlanHasNone() {
        assertEquals("2026-11-01 a 2026-11-30 · 50.00 USD", entries(listOf(row())).single().detail)
    }

    @Test
    fun renewalIsDifferentFromCreation() {
        val entry = entries(listOf(row(renewal = true))).single()
        assertEquals("membresia_renovada", entry.kind)
        assertEquals("Membresía Mensual renovada", entry.title)
    }

    @Test
    fun importedMembershipUsesItsRealStartDate() {
        val imported = row(startsOn = LocalDate.of(2024, 3, 10), endsOn = LocalDate.of(2024, 4, 9), createdAt = Instant.parse("2026-11-18T09:00:00Z"))
        assertEquals(Instant.parse("2024-03-10T00:00:00Z"), entries(listOf(imported)).single().at)
    }

    @Test
    fun scheduledMembershipUsesItsCreationDate() {
        val scheduled = row(startsOn = LocalDate.of(2027, 1, 1), endsOn = LocalDate.of(2027, 1, 31), createdAt = Instant.parse("2026-11-18T09:00:00Z"))
        assertEquals(Instant.parse("2026-11-18T09:00:00Z"), entries(listOf(scheduled)).single().at)
    }

    @Test
    fun cancelledMembershipAddsEntryWithoutReasonOrNotes() {
        val cancelledAt = Instant.parse("2026-11-10T15:00:00Z")
        val result = entries(listOf(row(status = StoredStatus.Cancelada, cancelledAt = cancelledAt)))
        assertEquals(listOf("membresia_cancelada", "membresia_alta"), result.map { it.kind })
        val cancelled = result.first()
        assertEquals("Membresía Mensual cancelada", cancelled.title)
        assertEquals(cancelledAt, cancelled.at)
        assertEquals("", cancelled.detail)
    }

    @Test
    fun expiredMembershipDatesTheDayAfterItEnded() {
        val result = entries(listOf(row(status = StoredStatus.Vencida)))
        val expired = result.first { it.kind == "membresia_vencida" }
        assertEquals("Membresía Mensual vencida", expired.title)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), expired.at)
    }

    @Test
    fun activeMembershipHasNoExpiredEntry() {
        assertFalse(entries(listOf(row())).any { it.kind == "membresia_vencida" })
    }

    @Test
    fun visitsAreOneEntryPerMonthWithSpanishMonthAndGuests() {
        val visits = listOf(
            VisitMonthRow(YearMonth.of(2026, 11), memberVisits = 4, guestVisits = 2, lastVisitAt = Instant.parse("2026-11-17T14:00:00Z")),
            VisitMonthRow(YearMonth.of(2026, 10), memberVisits = 3, guestVisits = 0, lastVisitAt = Instant.parse("2026-10-28T09:00:00Z")),
        )
        val result = entries(visits = visits)
        assertEquals(listOf("Visitas de noviembre 2026: 6", "Visitas de octubre 2026: 3"), result.map { it.title })
        assertEquals(listOf("Con invitado: 2", ""), result.map { it.detail })
        assertTrue(result.all { it.kind == "membresia_visitas" })
        assertEquals(Instant.parse("2026-11-17T14:00:00Z"), result.first().at)
    }

    @Test
    fun entriesAreNewestFirstAcrossKinds() {
        val result = entries(
            memberships = listOf(row(startsOn = LocalDate.of(2026, 9, 1), endsOn = LocalDate.of(2026, 9, 30), createdAt = Instant.parse("2026-08-30T10:00:00Z"))),
            visits = listOf(VisitMonthRow(YearMonth.of(2026, 9), 1, 0, Instant.parse("2026-09-15T10:00:00Z"))),
        )
        assertEquals(listOf("membresia_visitas", "membresia_alta"), result.map { it.kind })
        assertEquals(result.sortedByDescending { it.at }.map { it.at }, result.map { it.at })
    }

    @Test
    fun limitKeepsTheNewestEntries() {
        val memberships = (1..5).map { month ->
            row(startsOn = LocalDate.of(2026, month, 1), endsOn = LocalDate.of(2026, month, 28), createdAt = Instant.parse("2027-01-01T00:00:00Z"))
        }
        val result = entries(memberships, limit = 2)
        assertEquals(2, result.size)
        assertEquals(listOf(Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z")), result.map { it.at })
    }

    @Test
    fun noRowsNoEntries() {
        assertTrue(entries().isEmpty())
    }
}
