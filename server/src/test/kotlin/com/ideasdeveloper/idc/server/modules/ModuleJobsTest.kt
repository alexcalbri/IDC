package com.ideasdeveloper.idc.server.modules

import jakarta.mail.internet.MimeBodyPart
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleJobsTest {
    private val start = Instant.parse("2026-10-28T12:00:00Z")
    private val job = ModuleJob("poll", Duration.ofMinutes(5)) {}

    @Test
    fun jobRunsFirstAndThenEveryInterval() {
        val clock = ModuleJobClock()
        assertTrue(clock.isDue("crm", job, "acme", start))
        clock.ran("crm", job, "acme", start)
        assertFalse(clock.isDue("crm", job, "acme", start.plusSeconds(60)))
        assertTrue(clock.isDue("crm", job, "acme", start.plus(Duration.ofMinutes(5))))
    }

    @Test
    fun companiesAndModulesAreTrackedSeparately() {
        val clock = ModuleJobClock()
        clock.ran("crm", job, "acme", start)
        assertTrue(clock.isDue("crm", job, "other", start))
        assertTrue(clock.isDue("hotel", job, "acme", start))
        assertTrue(clock.isDue("crm", ModuleJob("mail", Duration.ofMinutes(5)) {}, "acme", start))
    }

    @Test
    fun htmlMailCarriesTextAlternativeFirst() {
        val body = MailSender.htmlBody("<p>Hola</p>", "Hola")
        assertEquals(2, body.count)
        assertEquals("Hola", (body.getBodyPart(0) as MimeBodyPart).content)
        val html = body.getBodyPart(1) as MimeBodyPart
        assertEquals("<p>Hola</p>", html.content)
        assertTrue(html.dataHandler.contentType.startsWith("text/html"))
    }
}
