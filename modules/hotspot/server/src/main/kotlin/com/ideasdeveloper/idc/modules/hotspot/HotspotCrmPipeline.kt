package com.ideasdeveloper.idc.modules.hotspot

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class HotspotCrmStage(val id: UUID, val name: String)

data class HotspotLeadWindow(val startDate: LocalDate?, val endDate: LocalDate?) {
    fun allowsAccessOn(date: LocalDate): Boolean =
        (startDate == null || !date.isBefore(startDate.minusDays(1))) &&
            (endDate == null || !date.isAfter(endDate))

    fun expiresAt(zone: ZoneId = ZoneId.systemDefault()): Instant? =
        endDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
}

object HotspotCrmPipeline {
    fun isInstalled(connection: Connection): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT to_regclass('crm_stages') IS NOT NULL AND to_regclass('crm_leads') IS NOT NULL").use { rows ->
                rows.next() && rows.getBoolean(1)
            }
        }

    fun stages(connection: Connection): List<HotspotCrmStage> {
        if (!isInstalled(connection)) return emptyList()
        return connection.prepareStatement("SELECT id, name FROM crm_stages ORDER BY position, name").use { query ->
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(HotspotCrmStage(rows.getObject("id", UUID::class.java), rows.getString("name"))) }
            }
        }
    }

    fun stageName(connection: Connection, stageId: UUID?): String {
        if (stageId == null || !isInstalled(connection)) return ""
        return connection.prepareStatement("SELECT name FROM crm_stages WHERE id = ?").use { query ->
            query.setObject(1, stageId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "" }
        }
    }

    fun stageId(connection: Connection, name: String): UUID? {
        if (!isInstalled(connection)) return null
        return connection.prepareStatement("SELECT id FROM crm_stages WHERE name = ?").use { query ->
            query.setString(1, name)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
    }

    fun currentWindow(
        connection: Connection,
        customerId: UUID,
        stageId: UUID,
        today: LocalDate = LocalDate.now(),
    ): HotspotLeadWindow? {
        val windows = connection.prepareStatement(
            """
            SELECT l.target_start, l.target_end
            FROM crm_leads l
            WHERE l.customer_id = ? AND l.stage_id = ?
            ORDER BY l.target_end DESC NULLS FIRST, l.updated_at DESC
            """.trimIndent()
        ).use { query ->
            query.setObject(1, customerId)
            query.setObject(2, stageId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(
                        HotspotLeadWindow(
                            rows.getObject("target_start", LocalDate::class.java),
                            rows.getObject("target_end", LocalDate::class.java),
                        )
                    )
                }
            }
        }
        return windows.firstOrNull { it.allowsAccessOn(today) }
    }

    fun enforceAccess(connection: Connection, settings: HotspotSettings, today: LocalDate, now: Instant) {
        val crmAvailable = settings.crmAccessEnabled && settings.crmStageId != null && isInstalled(connection)
        var after: Pair<Instant, UUID>? = null
        while (true) {
            val sessions = connection.prepareStatement(
                """
                SELECT s.id, s.customer_id, s.started_at
                FROM hotspot_code_sessions s
                WHERE s.ended_at IS NULL AND s.customer_id IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM hotspot_accesses a
                      JOIN hotspot_registrations r ON r.id = a.registration_id
                      WHERE a.session_id = s.id AND r.access_mode = ?
                  )
                  ${if (after == null) "" else "AND (s.started_at, s.id) > (?, ?)"}
                ORDER BY s.started_at, s.id
                LIMIT 100
                """.trimIndent()
            ).use { query ->
                query.setString(1, HotspotNetworks.CustomerCode)
                after?.let {
                    query.setTimestamp(2, Timestamp.from(it.first))
                    query.setObject(3, it.second)
                }
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                Triple(
                                    rows.getObject("id", UUID::class.java),
                                    rows.getObject("customer_id", UUID::class.java),
                                    rows.getTimestamp("started_at").toInstant(),
                                )
                            )
                        }
                    }
                }
            }
            if (sessions.isEmpty()) break
            sessions.forEach { (sessionId, customerId, _) ->
                val window = if (crmAvailable) currentWindow(connection, customerId, settings.crmStageId, today) else null
                if (window == null) {
                    setExpiry(connection, sessionId, now)
                } else {
                    window.expiresAt()?.let { setExpiry(connection, sessionId, it) }
                }
            }
            val last = sessions.last()
            after = last.third to last.first
            if (sessions.size < 100) break
        }
    }

    private fun setExpiry(connection: Connection, sessionId: UUID, expiresAt: Instant) {
        connection.prepareStatement("UPDATE hotspot_code_sessions SET expires_at = ? WHERE id = ? AND ended_at IS NULL").use { update ->
            update.setTimestamp(1, Timestamp.from(expiresAt))
            update.setObject(2, sessionId)
            update.executeUpdate()
        }
    }
}
