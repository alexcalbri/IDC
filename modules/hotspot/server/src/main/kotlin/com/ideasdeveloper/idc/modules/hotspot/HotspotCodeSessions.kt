package com.ideasdeveloper.idc.modules.hotspot

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Who a code session belongs to: a customer (customer code) or a user (user code). */
data class CodeOwner(val customerId: UUID? = null, val userId: UUID? = null, val userCompany: String? = null)

data class CodeSession(val id: UUID, val expiresAt: Instant?) {
    // Whole minutes left, rounded up, so a device that joins never outlives the session by more than a minute.
    fun minutesLeft(now: Instant = Instant.now()): Int? =
        expiresAt?.let { ((Duration.between(now, it).toSeconds() + 59) / 60).toInt().coerceAtLeast(1) }
}

/**
 * Shared sessions of access by code (hotspot_code_sessions). The first device that enters with a
 * customer code opens a session with its applicable access window; user-code sessions have no
 * expiry and stay open until an owner disables that user's WiFi access.
 */
object HotspotCodeSessions {
    private val log = LoggerFactory.getLogger(HotspotCodeSessions::class.java)

    fun open(connection: Connection, owner: CodeOwner, now: Instant = Instant.now()): CodeSession? = connection.prepareStatement(
        """
        SELECT id, expires_at FROM hotspot_code_sessions
        WHERE ended_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
          AND ${if (owner.customerId != null) "customer_id = ?" else "user_id = ? AND user_company = ?"}
        ORDER BY expires_at DESC NULLS FIRST
        LIMIT 1
        """.trimIndent()
    ).use { query ->
        query.setTimestamp(1, Timestamp.from(now))
        if (owner.customerId != null) {
            query.setObject(2, owner.customerId)
        } else {
            query.setObject(2, owner.userId)
            query.setString(3, owner.userCompany)
        }
        query.executeQuery().use { rows ->
            if (rows.next()) CodeSession(rows.getObject(1, UUID::class.java), rows.getTimestamp(2)?.toInstant()) else null
        }
    }

    fun start(
        connection: Connection,
        owner: CodeOwner,
        minutes: Int,
        passId: UUID?,
        notAfter: Instant? = null,
        indefinite: Boolean = false,
        now: Instant = Instant.now(),
    ): CodeSession {
        val expiresAt = if (indefinite) null else minOf(now.plus(Duration.ofMinutes(minutes.toLong())), notAfter ?: Instant.MAX)
        val session = CodeSession(UUID.randomUUID(), expiresAt)
        connection.prepareStatement(
            """
            INSERT INTO hotspot_code_sessions (id, customer_id, user_id, user_company, pass_id, started_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, session.id)
            insert.setObject(2, owner.customerId)
            insert.setObject(3, owner.userId)
            insert.setString(4, owner.userCompany)
            insert.setObject(5, passId)
            insert.setTimestamp(6, Timestamp.from(now))
            insert.setTimestamp(7, session.expiresAt?.let(Timestamp::from))
            insert.executeUpdate()
        }
        return session
    }

    fun limit(connection: Connection, sessionId: UUID, notAfter: Instant, now: Instant = Instant.now()): CodeSession? =
        connection.prepareStatement(
            """
            UPDATE hotspot_code_sessions SET expires_at = CASE
                WHEN expires_at IS NULL THEN ?
                ELSE LEAST(expires_at, ?)
            END
            WHERE id = ? AND ended_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
            RETURNING expires_at
            """.trimIndent()
        ).use { update ->
            update.setTimestamp(1, Timestamp.from(notAfter))
            update.setTimestamp(2, Timestamp.from(notAfter))
            update.setObject(3, sessionId)
            update.setTimestamp(4, Timestamp.from(now))
            update.executeQuery().use { rows ->
                if (rows.next()) CodeSession(sessionId, rows.getTimestamp(1)?.toInstant()) else null
            }
        }

    fun hasOpenUserSessions(connection: Connection, userId: UUID, userCompany: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM hotspot_code_sessions WHERE user_id = ? AND user_company = ? AND ended_at IS NULL LIMIT 1"
        ).use { query ->
            query.setObject(1, userId)
            query.setString(2, userCompany)
            query.executeQuery().use { it.next() }
        }

    fun revokeUserSessions(connection: Connection, userId: UUID, userCompany: String, now: Instant = Instant.now()) {
        connection.prepareStatement(
            """
            UPDATE hotspot_code_sessions
            SET expires_at = GREATEST(?, started_at + INTERVAL '1 microsecond')
            WHERE user_id = ? AND user_company = ? AND ended_at IS NULL
            """.trimIndent()
        ).use { update ->
            update.setTimestamp(1, Timestamp.from(now))
            update.setObject(2, userId)
            update.setString(3, userCompany)
            update.executeUpdate()
        }
    }

    /**
     * Disconnects the devices of every expired session and closes it. If the controller cannot
     * disconnect a device, the session stays open for the next scheduled attempt.
     */
    fun expire(connection: Connection, settings: HotspotSettings, controller: NetworkController?, now: Instant = Instant.now()) {
        var after: Pair<Instant, UUID>? = null
        while (true) {
            val expired = connection.prepareStatement(
                """
                SELECT id, expires_at
                FROM hotspot_code_sessions
                WHERE ended_at IS NULL AND expires_at <= ?
                  ${if (after == null) "" else "AND (expires_at, id) > (?, ?)"}
                ORDER BY expires_at, id
                LIMIT 100
                """.trimIndent()
            ).use { query ->
                query.setTimestamp(1, Timestamp.from(now))
                after?.let {
                    query.setTimestamp(2, Timestamp.from(it.first))
                    query.setObject(3, it.second)
                }
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getObject("id", UUID::class.java) to rows.getTimestamp("expires_at").toInstant())
                    }
                }
            }
            if (expired.isEmpty()) break
            expired.forEach { (sessionId, _) ->
                val devices = connection.prepareStatement(
                    "SELECT DISTINCT mac FROM hotspot_accesses WHERE session_id = ? AND authorized AND disconnected_at IS NULL"
                ).use { query ->
                    query.setObject(1, sessionId)
                    query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                }
                var allDisconnected = true
                devices.forEach { mac ->
                    val result = controller?.takeIf { it.isConfigured(settings) }?.disconnectGuest(settings, mac)
                    if (result?.authorized == true) {
                        markDisconnected(connection, sessionId, mac, now)
                    } else {
                        log.warn("Hotspot: no se pudo desconectar {} al vencer su sesion por codigo: {}", mac, result?.message ?: "sin controlador configurado")
                        allDisconnected = false
                    }
                }
                if (allDisconnected) {
                    connection.prepareStatement("UPDATE hotspot_code_sessions SET ended_at = ? WHERE id = ?").use { update ->
                        update.setTimestamp(1, Timestamp.from(now))
                        update.setObject(2, sessionId)
                        update.executeUpdate()
                    }
                }
            }
            val last = expired.last()
            after = last.second to last.first
            if (expired.size < 100) break
        }
    }

    private fun markDisconnected(connection: Connection, sessionId: UUID, mac: String, now: Instant) {
        connection.prepareStatement(
            "UPDATE hotspot_accesses SET disconnected_at = ? WHERE session_id = ? AND mac = ? AND authorized AND disconnected_at IS NULL"
        ).use { update ->
            update.setTimestamp(1, Timestamp.from(now))
            update.setObject(2, sessionId)
            update.setString(3, mac)
            update.executeUpdate()
        }
    }
}
