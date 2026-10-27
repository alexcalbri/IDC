package com.ideasdeveloper.idc.modules.hotspot

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HotspotPassesTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")

    private fun period(from: Instant, until: Instant) =
        AccessPass(UUID.randomUUID(), "hotel", HotspotPasses.Period, from, until, null)

    private fun balance(minutes: Int, from: Instant? = null, until: Instant? = null) =
        AccessPass(UUID.randomUUID(), "cowork", HotspotPasses.Balance, from, until, minutes)

    @Test
    fun periodIsUsableOnlyInsideItsDatesAndNeverPastTheEnd() {
        val stay = period(now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)))
        assertEquals(60, HotspotPasses.sessionMinutes(stay, 60, now))
        // Ten minutes before checkout the session lasts ten minutes.
        assertEquals(10, HotspotPasses.sessionMinutes(period(now.minus(Duration.ofDays(1)), now.plusSeconds(600)), 60, now))
        // Not yet started, already over, or ending right now.
        assertNull(HotspotPasses.sessionMinutes(period(now.plusSeconds(60), now.plus(Duration.ofDays(1))), 60, now))
        assertNull(HotspotPasses.sessionMinutes(period(now.minus(Duration.ofDays(2)), now.minusSeconds(1)), 60, now))
        assertNull(HotspotPasses.sessionMinutes(period(now.minus(Duration.ofDays(2)), now), 60, now))
        // A partial last minute still counts as one.
        assertEquals(1, HotspotPasses.sessionMinutes(period(now.minus(Duration.ofDays(1)), now.plusSeconds(5)), 60, now))
    }

    @Test
    fun balanceLimitsTheSessionAndRespectsItsOptionalWindow() {
        assertEquals(60, HotspotPasses.sessionMinutes(balance(300), 60, now))
        assertEquals(25, HotspotPasses.sessionMinutes(balance(25), 60, now))
        assertNull(HotspotPasses.sessionMinutes(balance(0), 60, now))
        assertEquals(30, HotspotPasses.sessionMinutes(balance(300, until = now.plusSeconds(1800)), 60, now))
        assertNull(HotspotPasses.sessionMinutes(balance(300, from = now.plusSeconds(60)), 60, now))
        assertNull(HotspotPasses.sessionMinutes(balance(300, until = now.minusSeconds(60)), 60, now))
    }

    @Test
    fun malformedPassesAreNeverUsable() {
        assertNull(HotspotPasses.sessionMinutes(AccessPass(UUID.randomUUID(), "x", HotspotPasses.Period, null, now.plusSeconds(600), null), 60, now))
        assertNull(HotspotPasses.sessionMinutes(AccessPass(UUID.randomUUID(), "x", HotspotPasses.Balance, null, null, null), 60, now))
        assertNull(HotspotPasses.sessionMinutes(AccessPass(UUID.randomUUID(), "x", "voucher", null, null, 60), 60, now))
    }

    @Test
    fun firstUsablePassWins() {
        val expired = period(now.minus(Duration.ofDays(5)), now.minus(Duration.ofDays(1)))
        val hours = balance(45)
        val (pass, minutes) = HotspotPasses.usable(listOf(expired, hours), 60, now)!!
        assertEquals(hours, pass)
        assertEquals(45, minutes)
        assertNull(HotspotPasses.usable(listOf(expired, balance(0)), 60, now))
    }
}
