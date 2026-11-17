package com.ideasdeveloper.idc.modules.crm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Archive after check-out (rules) and "Mostrar archivados" (filter). */
class CrmExitTest {
    private val booked = UUID.randomUUID()
    private val converted = UUID.randomUUID()
    private val cold = UUID.randomUUID()
    private val panama = ZoneId.of("America/Panama")
    private val utc = ZoneId.of("UTC")
    private val config = CrmExitAutomation(enabled = true, hour = 14, timeZone = "America/Panama", stageIds = setOf(booked, converted))
    private val day = LocalDate.of(2026, 11, 16)

    // 14:00 in Panama (UTC-5) is 19:00 UTC.
    private fun panamaAt(hour: Int, minute: Int = 0, date: LocalDate = day) = date.atTime(hour, minute).atZone(panama).toInstant()

    private fun due(
        config: CrmExitAutomation = this.config,
        cloudbeds: Boolean = false,
        stage: UUID = booked,
        checkOut: LocalDate? = day,
        archived: Boolean = false,
        now: Instant = panamaAt(14),
    ) = CrmExit.dueByHour(config, cloudbeds, stage, checkOut, archived, now, fallbackZone = utc)

    @Test
    fun withoutCloudbedsArchivesOnceTheHourPassedOnCheckOutDay() {
        assertFalse(due(now = panamaAt(13, 59)))
        assertTrue(due(now = panamaAt(14)))
        assertTrue(due(now = panamaAt(18)))
    }

    @Test
    fun checkOutBeforeTodayIsDueAtOnceAndTodayWaitsForTheHour() {
        // Earlier days do not wait for the hour; today's check-out does.
        assertTrue(due(checkOut = day.minusDays(3), now = panamaAt(9)))
        assertTrue(due(checkOut = day.minusDays(1), now = panamaAt(0, 1)))
        assertFalse(due(checkOut = day, now = panamaAt(9)))
        assertTrue(due(checkOut = day, now = panamaAt(14)))
    }

    @Test
    fun gateGivesTodayAndTheHourInTheCompanyZone() {
        val gate = CrmExit.gate(config, panamaAt(13, 59), fallbackZone = utc)
        assertEquals(CrmExit.Gate(day, hourPassed = false), gate)
        assertEquals(CrmExit.Gate(day, hourPassed = true), CrmExit.gate(config, panamaAt(14), fallbackZone = utc))
        assertTrue(CrmExit.checkOutDue(day.minusDays(1), gate))
        assertFalse(CrmExit.checkOutDue(day, gate))
        assertFalse(CrmExit.checkOutDue(day.plusDays(1), CrmExit.Gate(day, true)))
        assertFalse(CrmExit.checkOutDue(null, CrmExit.Gate(day, true)))
    }

    @Test
    fun checkOutInTheFutureOrWithoutDateIsNeverDue() {
        assertFalse(due(checkOut = day.plusDays(1)))
        assertFalse(due(checkOut = null))
    }

    @Test
    fun onlyChosenStagesAreArchived() {
        assertTrue(due(stage = converted))
        assertFalse(due(stage = cold))
    }

    @Test
    fun disabledAutomationNeverArchives() {
        assertFalse(due(config = config.copy(enabled = false)))
    }

    @Test
    fun withCloudbedsActiveTheHourNeverArchives() {
        assertFalse(due(cloudbeds = true))
        assertFalse(due(cloudbeds = true, checkOut = day.minusDays(10), now = panamaAt(23)))
    }

    @Test
    fun alreadyArchivedLeadsAreLeftAlone() {
        assertFalse(due(archived = true))
    }

    @Test
    fun theHourIsReadInTheCompanyTimeZone() {
        // 19:00 UTC is 14:00 in Panama, but only 19:00 in UTC: a UTC company with hour 14 archived hours earlier.
        val atUtc19 = day.atTime(19, 0).atZone(utc).toInstant()
        assertTrue(due(now = atUtc19))
        val inUtc = config.copy(timeZone = "UTC")
        assertTrue(due(config = inUtc, now = day.atTime(14, 0).atZone(utc).toInstant()))
        assertFalse(due(config = inUtc, now = day.atTime(13, 0).atZone(utc).toInstant()))
        // Late evening in Panama is already the next day in UTC; the check-out day is still Panama's.
        val lateInPanama = panamaAt(21)
        assertTrue(due(checkOut = day, now = lateInPanama))
        assertFalse(due(checkOut = day.plusDays(1), now = lateInPanama))
    }

    @Test
    fun withCloudbedsActiveItsCheckoutIsWhatArchives() {
        assertTrue(CrmExit.archivesOnCheckout(config, archiveOnCheckout = true, stageId = converted, archived = false))
        assertFalse(CrmExit.dueByHour(config, cloudbedsActive = true, stageId = converted, checkOut = day.minusDays(1), archived = false, now = panamaAt(20), fallbackZone = utc))
    }

    @Test
    fun emptyOrUnknownZoneFallsBackToTheServerZone() {
        assertEquals(utc, CrmExit.zone(config.copy(timeZone = ""), utc))
        assertEquals(utc, CrmExit.zone(config.copy(timeZone = "Nowhere/Land"), utc))
        assertEquals(panama, CrmExit.zone(config, utc))
        // Fallback in the rule: with an empty zone the server's (here UTC) decides.
        assertTrue(due(config = config.copy(timeZone = ""), now = day.atTime(14, 0).atZone(utc).toInstant()))
    }

    @Test
    fun runningTwiceChangesNothingBecauseArchivedLeadsAreSkipped() {
        val now = panamaAt(15)
        val first = due(now = now)
        val second = due(now = now, archived = first)
        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun withCloudbedsCheckoutArchivesOnlyWithTheOptionOn() {
        assertTrue(CrmExit.archivesOnCheckout(CrmExitAutomation(), archiveOnCheckout = true, stageId = cold, archived = false))
        assertFalse(CrmExit.archivesOnCheckout(CrmExitAutomation(), archiveOnCheckout = false, stageId = cold, archived = false))
        assertFalse(CrmExit.archivesOnCheckout(config, archiveOnCheckout = false, stageId = booked, archived = false))
    }

    @Test
    fun withCloudbedsAndTheAutomationOnOnlyChosenStagesAreArchived() {
        assertTrue(CrmExit.archivesOnCheckout(config, archiveOnCheckout = true, stageId = booked, archived = false))
        assertFalse(CrmExit.archivesOnCheckout(config, archiveOnCheckout = true, stageId = cold, archived = false))
        assertFalse(CrmExit.archivesOnCheckout(config, archiveOnCheckout = true, stageId = booked, archived = true))
    }

    private val stages = listOf(booked, converted, cold).mapIndexed { index, id -> CrmStage(id, "Etapa $index", StageKind.Open, StageTone.Neutral, index) }

    @Test
    fun settingsAreValidated() {
        val saved = CrmExit.parse(true, " 9 ", " America/Panama ", setOf(booked, UUID.randomUUID()), stages)
        assertEquals(CrmExitAutomation(true, 9, "America/Panama", setOf(booked)), saved)
        assertEquals(CrmExit.DefaultHour, CrmExit.parse(false, "", "", emptySet(), stages).hour)
        assertFailsWith<CrmValidationException> { CrmExit.parse(true, "24", "", setOf(booked), stages) }
        assertFailsWith<CrmValidationException> { CrmExit.parse(true, "x", "", setOf(booked), stages) }
        assertFailsWith<CrmValidationException> { CrmExit.parse(true, "14", "Nowhere/Land", setOf(booked), stages) }
        assertFailsWith<CrmValidationException> { CrmExit.parse(true, "14", "", emptySet(), stages) }
        // Off needs no stage.
        assertTrue(CrmExit.parse(false, "14", "", emptySet(), stages).stageIds.isEmpty())
    }

    @Test
    fun archivedLeadsCannotBeMoved() {
        // The guard runs before the database is touched, so a connection that fails on any use is enough.
        val tenant = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, _, _ ->
            error("moveLead must not reach the database")
        } as java.sql.Connection
        val archived = CrmLead(
            UUID.randomUUID(), UUID.randomUUID(), "Ana", "", "", booked, null, emptyList(), "Web", "", "", "",
            null, day, null, null, Instant.now(), null, Instant.now(), archivedAt = Instant.now(),
        )
        val failure = assertFailsWith<CrmValidationException> { CrmRepository.moveLead(tenant, archived, stages[1], stages, CrmActor(null, "Prueba")) }
        assertEquals("Restaura el lead antes de moverlo.", failure.message)
    }

    @Test
    fun restoreOnlyActsOnArchivedLeadsOfAUserWhoManages() {
        assertEquals(CrmRestoreCheck.Restore, restoreCheck(archived = true, canManage = true))
        // Idempotent: an open lead is left alone.
        assertEquals(CrmRestoreCheck.AlreadyOpen, restoreCheck(archived = false, canManage = true))
        assertEquals(CrmRestoreCheck.NoPermission, restoreCheck(archived = true, canManage = false))
        assertEquals(CrmRestoreCheck.NoPermission, restoreCheck(archived = false, canManage = false))
    }

    @Test
    fun aRestoredLeadIsNotArchivedAgainUntilItsDateOrStageChanges() {
        // Check-out long gone and stage chosen, but restored: the hour and Cloudbeds' check-out both leave it alone.
        assertFalse(CrmExit.dueByHour(config, false, booked, day.minusDays(2), archived = false, now = panamaAt(20), fallbackZone = utc, restored = true))
        assertFalse(CrmExit.archivesOnCheckout(config, archiveOnCheckout = true, stageId = booked, archived = false, restored = true))
        // Without the mark (after a change) the same lead is due again.
        assertTrue(CrmExit.dueByHour(config, false, booked, day.minusDays(2), archived = false, now = panamaAt(20), fallbackZone = utc, restored = false))
        assertTrue(CrmExit.archivesOnCheckout(config, archiveOnCheckout = true, stageId = booked, archived = false, restored = false))
        // The mark survives only while the stage and the end date stay the same.
        assertTrue(CrmExit.restoreSurvives(booked, booked, day, day))
        assertTrue(CrmExit.restoreSurvives(booked, booked, null, null))
        assertFalse(CrmExit.restoreSurvives(booked, converted, day, day))
        assertFalse(CrmExit.restoreSurvives(booked, booked, day, day.plusDays(2)))
        assertFalse(CrmExit.restoreSurvives(booked, booked, null, day))
    }

    @Test
    fun archivedFilterHidesShowsOrMixesArchivedLeads() {
        assertEquals("l.archived_at IS NULL", archivedSql(CrmLeadFilter()))
        assertEquals("l.archived_at IS NOT NULL", archivedSql(CrmLeadFilter(archived = true)))
        assertNull(archivedSql(CrmLeadFilter(includeArchived = true)))
        // The pipeline column asks for archived only, even when the list mixes them.
        assertEquals("l.archived_at IS NOT NULL", archivedSql(CrmLeadFilter(archived = true, includeArchived = true)))
    }

    @Test
    fun showArchivedCheckboxWinsOverTheRememberedValue() {
        assertTrue(CrmScreens.resolveShowArchived("true", stored = false))
        assertFalse(CrmScreens.resolveShowArchived("false", stored = true))
        assertTrue(CrmScreens.resolveShowArchived(null, stored = true))
        assertFalse(CrmScreens.resolveShowArchived(null, stored = false))
    }
}
