package com.ideasdeveloper.idc.server.modules

import jakarta.mail.internet.MimeBodyPart
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
    fun dailyJobRunsOnlyAtOrAfterItsScheduledLocalTimeOncePerDay() {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.of(2026, 11, 5)
        val dailyJob = ModuleJob("daily", Duration.ofDays(1), dailyAt = LocalTime.of(7, 0)) {}
        val clock = ModuleJobClock()
        val beforeSeven = date.atTime(6, 59).atZone(zone).toInstant()
        val atSeven = date.atTime(7, 0).atZone(zone).toInstant()
        val afterSeven = date.atTime(8, 0).atZone(zone).toInstant()
        val nextDaySeven = date.plusDays(1).atTime(7, 0).atZone(zone).toInstant()

        assertFalse(clock.isDue("hotspot", dailyJob, "acme", beforeSeven))
        assertTrue(clock.isDue("hotspot", dailyJob, "acme", atSeven))
        clock.ran("hotspot", dailyJob, "acme", atSeven)
        assertFalse(clock.isDue("hotspot", dailyJob, "acme", afterSeven))
        assertTrue(clock.isDue("hotspot", dailyJob, "acme", nextDaySeven))
    }

    @Test
    fun companyScheduleOverridesTheJobDefaultInterval() {
        val clock = ModuleJobClock()
        val now = Instant.parse("2026-11-05T12:00:00Z")
        val schedule = ModuleJobSchedule(interval = Duration.ofMinutes(45))
        assertTrue(clock.isDue("hotspot", job, "acme", now, schedule))
        clock.ran("hotspot", job, "acme", now)
        assertFalse(clock.isDue("hotspot", job, "acme", now.plus(Duration.ofMinutes(44)), schedule))
        assertTrue(clock.isDue("hotspot", job, "acme", now.plus(Duration.ofMinutes(45)), schedule))
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
