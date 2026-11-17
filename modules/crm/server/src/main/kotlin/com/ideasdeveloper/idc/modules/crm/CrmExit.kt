package com.ideasdeveloper.idc.modules.crm

import java.sql.Connection
import java.sql.PreparedStatement
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Archive after check-out, per company. [timeZone] is an IANA zone; empty uses the server's, like
 * the daily digest (the project has no time zone per company).
 */
data class CrmExitAutomation(
    val enabled: Boolean = false,
    val hour: Int = CrmExit.DefaultHour,
    val timeZone: String = "",
    val stageIds: Set<UUID> = emptySet(),
)

/** What the automation does to one lead: the rules are pure so they can be tested without a database. */
object CrmExit {
    const val DefaultHour = 14
    const val ActivityText = "Archivado automaticamente al hacer check-out."
    private val actor = CrmActor(null, "Automatizacion de salida")

    /** The company's zone; an unknown or empty one falls back to [fallback]. */
    fun zone(config: CrmExitAutomation, fallback: ZoneId = ZoneId.systemDefault()): ZoneId =
        if (config.timeZone.isBlank()) fallback else try {
            ZoneId.of(config.timeZone.trim())
        } catch (failure: DateTimeException) {
            fallback
        }

    /** Today in the company's zone and whether the configured hour has passed there. */
    data class Gate(val today: LocalDate, val hourPassed: Boolean)

    fun gate(config: CrmExitAutomation, now: Instant, fallbackZone: ZoneId = ZoneId.systemDefault()): Gate {
        val local = now.atZone(zone(config, fallbackZone))
        return Gate(local.toLocalDate(), !local.toLocalTime().isBefore(LocalTime.of(config.hour, 0)))
    }

    /** A check-out before today is due; one today is due once the hour passed; a later one is not. */
    fun checkOutDue(checkOut: LocalDate?, gate: Gate): Boolean =
        checkOut != null && (checkOut.isBefore(gate.today) || checkOut == gate.today && gate.hourPassed)

    /**
     * Without Cloudbeds: whether a lead is archived at [now]. It must be open, in one of the chosen stages,
     * and its end date (the check-out) must be before today, or today with the configured hour already
     * passed, in the company's zone. With Cloudbeds active the hour never archives: see [archivesOnCheckout].
     */
    fun dueByHour(
        config: CrmExitAutomation,
        cloudbedsActive: Boolean,
        stageId: UUID,
        checkOut: LocalDate?,
        archived: Boolean,
        now: Instant,
        fallbackZone: ZoneId = ZoneId.systemDefault(),
        restored: Boolean = false,
    ): Boolean {
        if (!config.enabled || cloudbedsActive || archived || restored || stageId !in config.stageIds) return false
        return checkOutDue(checkOut, gate(config, now, fallbackZone))
    }

    /**
     * With Cloudbeds: whether a checked-out reservation archives its lead. The existing option decides;
     * with the automation on only leads in the chosen stages are archived.
     */
    fun archivesOnCheckout(config: CrmExitAutomation, archiveOnCheckout: Boolean, stageId: UUID, archived: Boolean, restored: Boolean = false): Boolean =
        archiveOnCheckout && !archived && !restored && (!config.enabled || stageId in config.stageIds)

    /** A restored lead stays out of the automation until its stage or end date changes. */
    fun restoreSurvives(oldStage: UUID, newStage: UUID, oldEnd: LocalDate?, newEnd: LocalDate?): Boolean = oldStage == newStage && oldEnd == newEnd

    // Open reservations keep the lead (a lead with several reservations is archived when the last one leaves).
    private const val OpenReservations = "('not_confirmed', 'confirmed', 'checked_in')"

    /**
     * SQL over crm_leads "l" for "this lead is due": by date without Cloudbeds (3 parameters: today, today,
     * hour passed), by a checked-out reservation with Cloudbeds and its archive option on (none), never otherwise.
     */
    private fun dueSql(cloudbedsActive: Boolean, archiveOnCheckout: Boolean): String? = when {
        !cloudbedsActive -> "l.target_end IS NOT NULL AND (l.target_end < ? OR (l.target_end = ? AND ?))"
        archiveOnCheckout ->
            "EXISTS (SELECT 1 FROM crm_reservations r WHERE r.lead_id = l.id AND r.status = 'checked_out') " +
                "AND NOT EXISTS (SELECT 1 FROM crm_reservations o WHERE o.lead_id = l.id AND o.status IN $OpenReservations)"
        else -> null
    }

    private fun PreparedStatement.bindDue(from: Int, cloudbedsActive: Boolean, gate: Gate) {
        if (cloudbedsActive) return
        setObject(from, gate.today)
        setObject(from + 1, gate.today)
        setBoolean(from + 2, gate.hourPassed)
    }

    fun load(tenant: Connection): CrmExitAutomation =
        // Stages that no longer exist are left out.
        tenant.prepareStatement(
            """
            SELECT exit_enabled, exit_hour, exit_time_zone, ARRAY(SELECT s.id FROM crm_stages s WHERE s.id = ANY(crm_settings.exit_stage_ids))
            FROM crm_settings WHERE id = 1
            """.trimIndent()
        ).use { query ->
            query.executeQuery().use { rows ->
                if (rows.next()) {
                    val ids = (rows.getArray(4)?.array as? Array<*>).orEmpty().mapNotNull { it as? UUID }.toSet()
                    CrmExitAutomation(rows.getBoolean(1), rows.getInt(2), rows.getString(3).orEmpty(), ids)
                } else {
                    CrmExitAutomation()
                }
            }
        }

    fun save(tenant: Connection, config: CrmExitAutomation) {
        tenant.prepareStatement(
            "UPDATE crm_settings SET exit_enabled = ?, exit_hour = ?, exit_time_zone = ?, exit_stage_ids = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1"
        ).use { update ->
            update.setBoolean(1, config.enabled)
            update.setInt(2, config.hour)
            update.setString(3, config.timeZone)
            update.setArray(4, tenant.createArrayOf("uuid", config.stageIds.sortedBy { it.toString() }.toTypedArray()))
            update.executeUpdate()
        }
    }

    /**
     * Validates the settings form or the agent's values. [stages] are the company's stages: unknown ids
     * are dropped, and an active automation needs at least one stage.
     */
    fun parse(enabled: Boolean, hourText: String, timeZone: String, chosenStageIds: Set<UUID>, stages: List<CrmStage>): CrmExitAutomation {
        val hour = hourText.trim().ifEmpty { DefaultHour.toString() }.toIntOrNull()?.takeIf { it in 0..23 }
            ?: throw CrmValidationException("La hora debe ser un numero entre 0 y 23.")
        val zone = timeZone.trim()
        if (zone.length > 64) throw CrmValidationException("La zona horaria es demasiado larga.")
        if (zone.isNotEmpty()) {
            try {
                ZoneId.of(zone)
            } catch (failure: DateTimeException) {
                throw CrmValidationException("La zona horaria no existe. Usa un nombre como America/Panama.")
            }
        }
        val known = stages.map { it.id }.toSet()
        val chosen = chosenStageIds.filter { it in known }.toSet()
        if (enabled && chosen.isEmpty()) throw CrmValidationException("Elige al menos una etapa de origen.")
        return CrmExitAutomation(enabled, hour, zone, chosen)
    }

    /** Open leads per stage that the automation would archive now (to warn before activating it). */
    fun dueByStage(tenant: Connection, config: CrmExitAutomation, stageIds: Collection<UUID>, now: Instant): Map<UUID, Int> {
        if (stageIds.isEmpty()) return emptyMap()
        val cloudbeds = CrmCloudbeds.load(tenant)
        val condition = dueSql(cloudbeds.ready, cloudbeds.archiveOnCheckout) ?: return emptyMap()
        val gate = gate(config, now)
        return tenant.prepareStatement(
            "SELECT l.stage_id, count(*) FROM crm_leads l WHERE l.archived_at IS NULL AND l.restored_at IS NULL AND l.stage_id = ANY(?) AND $condition GROUP BY l.stage_id"
        ).use { query ->
            query.setArray(1, tenant.createArrayOf("uuid", stageIds.toTypedArray()))
            query.bindDue(2, cloudbeds.ready, gate)
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getInt(2)) } }
        }
    }

    /** How many leads turning the automation on (or adding stages) would archive at once; 0 when nothing is activated. */
    fun pendingOnActivation(tenant: Connection, saved: CrmExitAutomation, wanted: CrmExitAutomation, now: Instant): Int {
        if (!wanted.enabled) return 0
        val added = if (saved.enabled) wanted.stageIds - saved.stageIds else wanted.stageIds
        return dueByStage(tenant, wanted, added, now).values.sum()
    }

    /**
     * Job: archives the open leads of the chosen stages that are due, in one atomic UPDATE that repeats the
     * conditions (a lead moved or archived meanwhile is not touched) and leaves the activity of each archived
     * lead in the same transaction. Without Cloudbeds the due ones are those whose check-out arrived (see
     * [checkOutDue]); with Cloudbeds (and its archive option on) those whose reservation is checked out, which
     * also covers leads that reached a chosen stage after the check-out. Archived leads are never selected
     * again. The configuration is read on every run. Returns the number of leads archived.
     */
    fun archiveDue(tenant: Connection, now: Instant): Int {
        val config = load(tenant)
        if (!config.enabled || config.stageIds.isEmpty()) return 0
        val cloudbeds = CrmCloudbeds.load(tenant)
        val condition = dueSql(cloudbeds.ready, cloudbeds.archiveOnCheckout) ?: return 0
        val gate = gate(config, now)
        return CrmRepository.transaction(tenant) {
            val ids = tenant.prepareStatement(
                """
                UPDATE crm_leads l SET archived_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE l.archived_at IS NULL AND l.restored_at IS NULL AND l.stage_id = ANY(?) AND $condition
                RETURNING l.id
                """.trimIndent()
            ).use { update ->
                update.setArray(1, tenant.createArrayOf("uuid", config.stageIds.sortedBy { it.toString() }.toTypedArray()))
                update.bindDue(2, cloudbeds.ready, gate)
                update.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
            }
            ids.forEach { CrmRepository.addActivity(tenant, it, "updated", ActivityText, actor) }
            ids.size
        }
    }
}
