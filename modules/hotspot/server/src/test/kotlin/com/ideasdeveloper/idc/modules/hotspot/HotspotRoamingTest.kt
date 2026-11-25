package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.modules.hotspot.HotspotRoaming.End
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotspotRoamingTest {
    private val now = Instant.parse("2026-11-16T18:00:00Z")
    private val session = UUID.randomUUID()

    private fun end(
        createdAt: Instant = Instant.parse("2026-11-16T10:00:00Z"),
        minutes: Int = 1440,
        sessionId: UUID? = null,
        sessionExpiresAt: Instant? = null,
        sessionEndedAt: Instant? = null,
        disconnectedAt: Instant? = null,
    ) = HotspotRoaming.endOf(createdAt, minutes, sessionId, sessionExpiresAt, sessionEndedAt, disconnectedAt, now)

    @Test
    fun accessesEndWhenTheirOriginalTimeEndsNotAtTheSecondGateway() {
        // Authorized at 10:00 for a day: at 18:00 the second gateway gets until 10:00 tomorrow, not another day.
        assertEquals(End.At(Instant.parse("2026-11-17T10:00:00Z")), end())
        assertEquals(End.Ended, end(createdAt = Instant.parse("2026-11-15T09:00:00Z")))
        assertEquals(End.Never, end(minutes = 0))
    }

    @Test
    fun codeSessionAccessesFollowTheirSession() {
        assertEquals(End.At(Instant.parse("2026-11-16T20:00:00Z")), end(sessionId = session, sessionExpiresAt = Instant.parse("2026-11-16T20:00:00Z")))
        // A user code without expiry, until an owner revokes it.
        assertEquals(End.Never, end(sessionId = session, sessionExpiresAt = null))
        assertEquals(End.Ended, end(sessionId = session, sessionExpiresAt = null, sessionEndedAt = now))
        assertEquals(End.Ended, end(sessionId = session, sessionExpiresAt = now))
        assertEquals(End.Ended, end(disconnectedAt = now))
    }

    @Test
    fun theLongestLiveAccessWins() {
        val soon = access("vistamar", Instant.parse("2026-11-16T19:00:00Z"))
        val later = access("costaazul", Instant.parse("2026-11-17T09:00:00Z"))
        val forever = access("costaazul", null)
        assertEquals(later, HotspotRoaming.best(listOf(soon, later)))
        assertEquals(forever, HotspotRoaming.best(listOf(soon, forever, later)))
        assertNull(HotspotRoaming.best(emptyList()))
    }

    @Test
    fun minutesLeftRoundUpAndAccessesWithoutExpiryHaveNone() {
        assertEquals(960, access("vistamar", Instant.parse("2026-11-17T10:00:00Z")).minutesLeft(now))
        assertEquals(1, access("vistamar", Instant.parse("2026-11-16T18:00:20Z")).minutesLeft(now))
        assertNull(access("vistamar", null).minutesLeft(now))
    }

    @Test
    fun networksCanBeSharedWithLinkedCompanies() {
        val shared = HotspotNetworks.parse(UUID.randomUUID(), mapOf("ssid" to "Bosque", "accessMode" to "Formulario", "shared" to "true"))
        assertTrue(shared.shared)
        assertEquals("true", HotspotNetworks.toFormValues(shared)["shared"])
        assertFalse(HotspotNetworks.parse(UUID.randomUUID(), mapOf("ssid" to "Bosque", "accessMode" to "Formulario")).shared)
    }

    @Test
    fun welcomeBackShowsTheExactTimeLeft() {
        assertEquals("16 h", HotspotPortalPages.timeLeft(960))
        assertEquals("1 h 1 min", HotspotPortalPages.timeLeft(61))
        assertEquals("1 dia 2 h 5 min", HotspotPortalPages.timeLeft(1565))
        assertEquals("1 min", HotspotPortalPages.timeLeft(1))
    }

    private fun access(company: String, expiresAt: Instant?) = RoamingAccess(
        company = company, name = "Ana", email = "ana@example.com", phone = null,
        accessMode = HotspotNetworks.Form, userId = null, userCompany = null, expiresAt = expiresAt,
    )
}
