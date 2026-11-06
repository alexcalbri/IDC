package com.ideasdeveloper.idc.modules.hotspot

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HotspotCodeSessionsTest {
    private val now = Instant.parse("2026-11-03T12:00:00Z")

    // A device that joins a code's session gets only the time left, so all devices end together.
    @Test
    fun joiningDevicesGetTheTimeLeft() {
        assertEquals(30, CodeSession(UUID.randomUUID(), now.plusSeconds(30 * 60)).minutesLeft(now))
        // Partial minutes round up; the expiry job disconnects the device right at the end anyway.
        assertEquals(30, CodeSession(UUID.randomUUID(), now.plusSeconds(29 * 60 + 1)).minutesLeft(now))
        // Never zero: UniFi needs at least one minute.
        assertEquals(1, CodeSession(UUID.randomUUID(), now.plusSeconds(5)).minutesLeft(now))
        assertNull(CodeSession(UUID.randomUUID(), null).minutesLeft(now))
    }
}
