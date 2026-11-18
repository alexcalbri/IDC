package com.ideasdeveloper.idc.modules.crm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notices of a new inbound message and of a due follow-up (change 9.3): who gets them and when they are due. */
class CrmLeadNoticesTest {
    private val ana = UUID.randomUUID()

    private fun settings(email: String = "ana@example.com", message: Boolean = true, followUp: Boolean = true) =
        CrmUserSettings(ana, email, false, true, false, notifyNewMessage = message, notifyFollowUp = followUp)

    @Test
    fun `the message email needs the preference and an address`() {
        assertEquals("ana@example.com", CrmNotify.newMessageEmailTarget(settings()))
        assertNull(CrmNotify.newMessageEmailTarget(settings(message = false)))
        assertNull(CrmNotify.newMessageEmailTarget(settings(email = "")))
        assertNull(CrmNotify.newMessageEmailTarget(settings(email = "   ")))
        // Without a settings row there is no address: only the in-app notice.
        assertNull(CrmNotify.newMessageEmailTarget(null))
    }

    @Test
    fun `the follow-up email needs the preference and an address and is independent of the message one`() {
        assertEquals("ana@example.com", CrmNotify.followUpEmailTarget(settings(message = false)))
        assertNull(CrmNotify.followUpEmailTarget(settings(followUp = false)))
        assertNull(CrmNotify.followUpEmailTarget(settings(email = "")))
        assertNull(CrmNotify.followUpEmailTarget(null))
        assertEquals("ana@example.com", CrmNotify.newMessageEmailTarget(settings(followUp = false)))
    }

    @Test
    fun `both preferences are on by default`() {
        val defaults = CrmUserSettings(ana, "", false, true, false)
        assertTrue(defaults.notifyNewMessage)
        assertTrue(defaults.notifyFollowUp)
    }

    private val today = LocalDate.of(2026, 11, 17)
    private val passed = CrmExit.Gate(today, true)
    private val early = CrmExit.Gate(today, false)

    @Test
    fun `a follow-up is due from its date once the hour passed`() {
        assertTrue(CrmNotify.followUpDue(today, null, true, passed))
        assertTrue(CrmNotify.followUpDue(today.minusDays(3), null, true, passed))
        assertFalse(CrmNotify.followUpDue(today.plusDays(1), null, true, passed))
        assertFalse(CrmNotify.followUpDue(null, null, true, passed))
        assertFalse(CrmNotify.followUpDue(today, null, true, early))
    }

    @Test
    fun `a follow-up is noticed once per date and a new date re-arms it`() {
        assertFalse(CrmNotify.followUpDue(today, today, true, passed))
        // The date moved after the notice: due again.
        assertTrue(CrmNotify.followUpDue(today, today.minusDays(2), true, passed))
    }

    @Test
    fun `closed or archived leads are not noticed`() {
        assertFalse(CrmNotify.followUpDue(today, null, false, passed))
    }

    @Test
    fun `the gate uses the company zone`() {
        // 2026-11-17 02:00 UTC is still the 16th at 21:00 in Bogota and already 11:00 in Tokyo.
        val now = Instant.parse("2026-11-17T02:00:00Z")
        val bogota = CrmZone.gate(ZoneId.of("America/Bogota"), 9, now)
        assertEquals(LocalDate.of(2026, 11, 16), bogota.today)
        assertTrue(bogota.hourPassed)
        val tokyo = CrmZone.gate(ZoneId.of("Asia/Tokyo"), 12, now)
        assertEquals(LocalDate.of(2026, 11, 17), tokyo.today)
        assertFalse(tokyo.hourPassed)
    }

    @Test
    fun `an empty or unknown zone falls back`() {
        val fallback = ZoneId.of("UTC")
        assertEquals(fallback, CrmZone.parse("", fallback))
        assertEquals(fallback, CrmZone.parse("No/Existe", fallback))
        assertEquals(ZoneId.of("America/Panama"), CrmZone.parse(" America/Panama ", fallback))
    }

    @Test
    fun `the notice excerpt is one line cut at 140 characters`() {
        val text = CrmNotify.excerpt("Hola\n\n" + "a".repeat(300))
        assertEquals(140, text.length)
        assertTrue(text.startsWith("Hola a"))
        assertFalse(text.contains('\n'))
    }
}
