package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** One row of crm_automation_runs with the names it refers to. */
data class CrmRunRow(
    val id: UUID,
    val automationId: UUID,
    val automationName: String,
    val leadId: UUID,
    val leadName: String,
    val runKey: String,
    val status: String,
    val detail: String,
    val createdAt: Instant,
)

/** Tenant database access of the automations (crm_automations and crm_automation_runs). */
object CrmAutomationStore {
    val statusLabels = mapOf(
        "pending" to "En cola", "running" to "En curso", "done" to "Hecha", "failed" to "Fallo", "missing_data" to "Faltan datos",
        "warned" to "Aviso de datos", "skipped" to "Omitida",
    )

    private const val Columns = "id, name, enabled, trigger_type, trigger_config::text, conditions::text, actions::text, warn_before_minutes, notify_user_ids::text, position"

    private fun parse(text: String?): JsonObject = text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    private fun parseArray(text: String?): JsonArray = text?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: JsonArray(emptyList())

    /** A rule as saved; null when its JSON no longer reads (it was written by hand in the database). */
    private fun java.sql.ResultSet.automation(): CrmAutomation? = runCatching {
        val type = CrmTriggerType.byId(getString(4)) ?: return@runCatching null
        CrmAutomation(
            id = getObject(1, UUID::class.java),
            name = getString(2),
            enabled = getBoolean(3),
            trigger = CrmAutomationJson.parseTrigger(type, parse(getString(5)), null),
            conditions = CrmAutomationJson.parseConditions(parseArray(getString(6)), null),
            actions = CrmAutomationJson.parseActions(parseArray(getString(7)), null),
            warnBeforeMinutes = getInt(8),
            notifyUserIds = parseArray(getString(9)).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { id -> runCatching { UUID.fromString(id) }.getOrNull() } },
            position = getInt(10),
        )
    }.getOrNull()

    fun all(tenant: Connection): List<CrmAutomation> =
        tenant.prepareStatement("SELECT $Columns FROM crm_automations ORDER BY position, lower(name)").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) rows.automation()?.let { add(it) } } }
        }

    fun find(tenant: Connection, id: UUID): CrmAutomation? =
        tenant.prepareStatement("SELECT $Columns FROM crm_automations WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.automation() else null }
        }

    /** The enabled rules of a trigger type (one cheap read; the event hooks call it on every lead change). */
    fun enabledOf(tenant: Connection, type: CrmTriggerType): List<CrmAutomation> =
        tenant.prepareStatement("SELECT $Columns FROM crm_automations WHERE enabled AND trigger_type = ? ORDER BY position, lower(name)").use { query ->
            query.setString(1, type.id)
            query.executeQuery().use { rows -> buildList { while (rows.next()) rows.automation()?.let { add(it) } } }
        }

    /** Inserts or updates [automation] (already validated). */
    fun save(tenant: Connection, automation: CrmAutomation, exists: Boolean) {
        val sql = if (exists) {
            """
            UPDATE crm_automations SET name = ?, enabled = ?, trigger_type = ?, trigger_config = CAST(? AS jsonb), conditions = CAST(? AS jsonb),
                actions = CAST(? AS jsonb), warn_before_minutes = ?, notify_user_ids = CAST(? AS jsonb), position = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent()
        } else {
            """
            INSERT INTO crm_automations (name, enabled, trigger_type, trigger_config, conditions, actions, warn_before_minutes, notify_user_ids, position, id)
            VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?)
            """.trimIndent()
        }
        try {
            tenant.prepareStatement(sql).use { statement ->
                statement.setString(1, automation.name.trim())
                statement.setBoolean(2, automation.enabled)
                statement.setString(3, automation.trigger.type.id)
                statement.setString(4, CrmAutomationJson.trigger(automation.trigger).toString())
                statement.setString(5, CrmAutomationJson.conditions(automation.conditions).toString())
                statement.setString(6, CrmAutomationJson.actions(automation.actions).toString())
                statement.setInt(7, automation.warnBeforeMinutes)
                statement.setString(8, CrmAutomationJson.ids(automation.notifyUserIds).toString())
                statement.setInt(9, automation.position)
                statement.setObject(10, automation.id)
                if (statement.executeUpdate() != 1) throw CrmValidationException("Esa automatizacion ya no existe.")
            }
        } catch (failure: SQLException) {
            // crm_automations_name_idx: two rules with the same name.
            if (failure.sqlState == "23505") throw CrmValidationException("Ya existe la automatizacion ${automation.name.trim()}.")
            throw failure
        }
    }

    fun setEnabled(tenant: Connection, id: UUID, enabled: Boolean): Boolean =
        tenant.prepareStatement("UPDATE crm_automations SET enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setBoolean(1, enabled)
            update.setObject(2, id)
            update.executeUpdate() == 1
        }

    /** Deletes the rule with its runs (cascade). Buttons that pointed to it stop finding it. */
    fun delete(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("DELETE FROM crm_automations WHERE id = ?").use { delete -> delete.setObject(1, id); delete.executeUpdate() == 1 }

    fun nextPosition(tenant: Connection): Int =
        tenant.prepareStatement("SELECT COALESCE(max(position) + 1, 0) FROM crm_automations").use { query -> query.executeQuery().use { rows -> rows.next(); rows.getInt(1) } }

    // ---- Runs ----

    /**
     * Reserves the execution ([runKey] of that rule and lead) before doing anything: false when it was already reserved, so an
     * execution never happens twice, not even when two passes overlap. The row stays "running" until [finish] sets its result.
     */
    fun claim(tenant: Connection, automationId: UUID, leadId: UUID, runKey: String): Boolean =
        tenant.prepareStatement(
            "INSERT INTO crm_automation_runs (id, automation_id, lead_id, run_key, status, detail, started_at) VALUES (?, ?, ?, ?, 'running', 'En curso', CURRENT_TIMESTAMP) ON CONFLICT (automation_id, lead_id, run_key) DO NOTHING"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, automationId)
            insert.setObject(3, leadId)
            insert.setString(4, runKey.take(200))
            insert.executeUpdate() == 1
        }

    /** Queues an event run ("pending") for the job; false when that key already exists (e.g. the message rule already ran today). */
    fun enqueue(tenant: Connection, automationId: UUID, leadId: UUID, runKey: String): Boolean =
        tenant.prepareStatement(
            "INSERT INTO crm_automation_runs (id, automation_id, lead_id, run_key, status, detail) VALUES (?, ?, ?, ?, 'pending', 'En cola') ON CONFLICT (automation_id, lead_id, run_key) DO NOTHING"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, automationId)
            insert.setObject(3, leadId)
            insert.setString(4, runKey.take(200))
            insert.executeUpdate() == 1
        }

    /** A queued run claimed by the job. */
    data class PendingRun(val automationId: UUID, val leadId: UUID, val runKey: String)

    /** Claims up to [limit] pending runs, oldest first, in one statement (FOR UPDATE SKIP LOCKED: two workers never get the same row); they become "running". */
    fun claimPending(tenant: Connection, limit: Int): List<PendingRun> = tenant.prepareStatement(
        """
        UPDATE crm_automation_runs SET status = 'running', detail = 'En curso', started_at = CURRENT_TIMESTAMP
        WHERE id IN (SELECT id FROM crm_automation_runs WHERE status = 'pending' ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED)
        RETURNING automation_id, lead_id, run_key
        """.trimIndent()
    ).use { update ->
        update.setInt(1, limit)
        update.executeQuery().use { rows ->
            buildList { while (rows.next()) add(PendingRun(rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getString(3))) }
        }
    }

    /** Runs that stayed "running" longer than [CrmRunStates.StaleAfter] (the server stopped midway) are marked failed. */
    fun failStale(tenant: Connection, now: Instant = Instant.now()): Int =
        tenant.prepareStatement("UPDATE crm_automation_runs SET status = 'failed', detail = ? WHERE status = 'running' AND started_at < ?").use { update ->
            update.setString(1, CrmRunStates.Interrupted)
            update.setTimestamp(2, java.sql.Timestamp.from(now.minus(CrmRunStates.StaleAfter)))
            update.executeUpdate()
        }

    /** Runs of [automationId] on [leadId] created in the last [minutes] (loop guard of event rules). */
    fun recentRunCount(tenant: Connection, automationId: UUID, leadId: UUID, minutes: Int): Int =
        tenant.prepareStatement("SELECT count(*) FROM crm_automation_runs WHERE automation_id = ? AND lead_id = ? AND created_at > CURRENT_TIMESTAMP - (? * INTERVAL '1 minute')").use { query ->
            query.setObject(1, automationId)
            query.setObject(2, leadId)
            query.setInt(3, minutes)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    /** Template emails queued for [leadId] in the last hour (any rule or button): the cap of automations counts them. */
    fun templateMailsLastHour(tenant: Connection, leadId: UUID): Int =
        tenant.prepareStatement("SELECT count(*) FROM crm_outbox WHERE lead_id = ? AND kind = ? AND created_at > CURRENT_TIMESTAMP - INTERVAL '1 hour'").use { query ->
            query.setObject(1, leadId)
            query.setString(2, CrmTemplateMailer.OutboxKind)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    /** Old runs go: everything after 90 days except the date and warning keys, which stay up to a year (they stop a date rule from repeating). */
    fun purge(tenant: Connection): Int =
        tenant.prepareStatement(
            """
            DELETE FROM crm_automation_runs
            WHERE status NOT IN ('pending', 'running')
              AND (created_at < CURRENT_TIMESTAMP - INTERVAL '1 year'
                   OR (created_at < CURRENT_TIMESTAMP - INTERVAL '90 days' AND run_key NOT LIKE 'date:%' AND run_key NOT LIKE 'warn:%'))
            """.trimIndent()
        ).use { it.executeUpdate() }

    fun finish(tenant: Connection, automationId: UUID, leadId: UUID, runKey: String, status: String, detail: String) {
        tenant.prepareStatement("UPDATE crm_automation_runs SET status = ?, detail = ? WHERE automation_id = ? AND lead_id = ? AND run_key = ?").use { update ->
            update.setString(1, status)
            update.setString(2, detail.take(2000))
            update.setObject(3, automationId)
            update.setObject(4, leadId)
            update.setString(5, runKey.take(200))
            update.executeUpdate()
        }
    }

    /** (lead, run_key) of the date and warning keys already recorded for [automationId] among [leadIds]. */
    fun recordedKeys(tenant: Connection, automationId: UUID, leadIds: Collection<UUID>): Set<Pair<UUID, String>> {
        if (leadIds.isEmpty()) return emptySet()
        return tenant.prepareStatement("SELECT lead_id, run_key FROM crm_automation_runs WHERE automation_id = ? AND lead_id = ANY(?) AND (run_key LIKE 'date:%' OR run_key LIKE 'warn:%')").use { query ->
            query.setObject(1, automationId)
            query.setArray(2, tenant.createArrayOf("uuid", leadIds.toTypedArray()))
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getString(2)) } }
        }
    }

    /** Latest runs, newest first; of one rule, or of every rule when [automationId] is null. */
    fun recent(tenant: Connection, automationId: UUID?, limit: Int): List<CrmRunRow> = tenant.prepareStatement(
        """
        SELECT r.id, r.automation_id, a.name, r.lead_id, c.display_name, r.run_key, r.status, r.detail, r.created_at
        FROM crm_automation_runs r
        JOIN crm_automations a ON a.id = r.automation_id
        JOIN crm_leads l ON l.id = r.lead_id
        JOIN customers c ON c.id = l.customer_id
        ${if (automationId != null) "WHERE r.automation_id = ?" else ""}
        ORDER BY r.created_at DESC LIMIT ?
        """.trimIndent()
    ).use { query ->
        var index = 1
        if (automationId != null) query.setObject(index++, automationId)
        query.setInt(index, limit)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(
                    CrmRunRow(
                        rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getString(3), rows.getObject(4, UUID::class.java),
                        rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8), rows.getTimestamp(9).toInstant(),
                    )
                )
            }
        }
    }
}
