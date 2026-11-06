package com.ideasdeveloper.idc.modules.hotspot

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotspotCrmPipelineTest {
    private val stay = HotspotLeadWindow(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-14"))

    @Test
    fun selectedPipelineStageWindowAllowsFromDayBeforeStartThroughEndDate() {
        assertTrue(stay.allowsAccessOn(LocalDate.parse("2026-11-09")))
        assertTrue(stay.allowsAccessOn(LocalDate.parse("2026-11-10")))
        assertTrue(stay.allowsAccessOn(LocalDate.parse("2026-11-14")))
        assertFalse(stay.allowsAccessOn(LocalDate.parse("2026-11-08")))
        assertFalse(stay.allowsAccessOn(LocalDate.parse("2026-11-15")))
    }

    @Test
    fun sessionExpiresAtMidnightAfterTheLeadEndDate() {
        assertEquals(
            Instant.parse("2026-11-15T05:00:00Z"),
            stay.expiresAt(ZoneId.of("America/Panama")),
        )
    }

    @Test
    fun missingLeadDatesLeaveTheSelectedStageAsTheOnlyExpiryCondition() {
        val noDates = HotspotLeadWindow(null, null)
        assertTrue(noDates.allowsAccessOn(LocalDate.parse("2026-11-05")))
        assertEquals(null, noDates.expiresAt())
    }
}
