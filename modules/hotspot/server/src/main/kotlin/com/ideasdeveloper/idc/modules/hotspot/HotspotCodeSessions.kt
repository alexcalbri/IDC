package com.ideasdeveloper.idc.modules.hotspot

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Who a code session belongs to: a customer (customer code) or a user (user code). */
data class CodeOwner(val customerId: UUID? = null, val userId: UUID? = null, val userCompany: String? = null)

data class CodeSession(val id: UUID, val expiresAt: Instant) {
    // Whole minutes left, rounded up, so a device that joins never outlives the session by more than a minute.
    fun minutesLeft(now: Instant = Instant.now()): Int = ((Duration.between(now, expiresAt).toSeconds() + 59) / 60).toInt().coerceAtLeast(1)
}

/**
 * Shared sessions of access by code (hotspot_code_sessions). The first device that enters with a
 * customer or user code opens a session with the network's access time; other devices that use the
 * same code while it is open join it for the time left. When it expires, [expire] disconnects every
 * device that used the code, so sharing a code never gives more time than one access.
 */
object HotspotCodeSessions {
    private val log = LoggerFactory.getLogger(HotspotCodeSessions::class.java)
    // A session whose devices could not be disconnected is retried for this long, then closed anyway.
    private val giveUpAfter = Duration.ofDays(1)

    fun open(connection: Connection, owner: CodeOwner, now: Instant = Instant.now()): CodeSession? = connection.prepareStatement(
        """
        SELECT id, expires_at FROM hotspot_code_sessions
        WHERE ended_at IS NULL AND expires_at > ?
          AND ${if (owner.customerId != null) "customer_id = ?" else "user_id = ? AND user_company = ?"}
        ORDER BY expires_at DESC
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
        query.executeQuery().use { rows -> if (rows.next()) CodeSession(rows.getObject(1, UUID::class.java), rows.getTimestamp(2).toInstant()) else null }
    }

    fun start(connection: Connection, owner: CodeOwner, minutes: Int, passId: UUID?, now: Instant = Instant.now()): CodeSession {
        val session = CodeSession(UUID.randomUUID(), now.plus(Duration.ofMinutes(minutes.toLong())))
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
            insert.setTimestamp(7, Timestamp.from(session.expiresAt))
            insert.executeUpdate()
        }
        return session
    }

    /**
     * Disconnects the devices of every expired session and closes it. A device the controller
     * could not disconnect is retried on the next run; after [giveUpAfter] the session is closed anyway.
     */
    fun expire(connection: Connection, settings: HotspotSettings, controller: NetworkController?, now: Instant = Instant.now()) {
        val expired = connection.prepareStatement(
            "SELECT id, expires_at FROM hotspot_code_sessions WHERE ended_at IS NULL AND expires_at <= ? ORDER BY expires_at LIMIT 100"
        ).use { query ->
            query.setTimestamp(1, Timestamp.from(now))
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getTimestamp(2).toInstant()) } }
        }
        expired.forEach { (sessionId, expiresAt) ->
            val devices = connection.prepareStatement(
                "SELECT DISTINCT mac FROM hotspot_accesses WHERE session_id = ? AND authorized AND disconnected_at IS NULL"
            ).use { query ->
                query.setObject(1, sessionId)
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            val pending = devices.filterNot { mac ->
                val result = controller?.takeIf { it.isConfigured(settings) }?.disconnectGuest(settings, mac)
                if (result?.authorized == true) {
                    markDisconnected(connection, sessionId, mac, now)
                    true
                } else {
                    log.warn("Hotspot: no se pudo desconectar {} al vencer su sesion por codigo: {}", mac, result?.message ?: "sin controlador configurado")
                    false
                }
            }
            if (pending.isEmpty() || Duration.between(expiresAt, now) > giveUpAfter) {
                connection.prepareStatement("UPDATE hotspot_code_sessions SET ended_at = ? WHERE id = ?").use { update ->
                    update.setTimestamp(1, Timestamp.from(now))
                    update.setObject(2, sessionId)
                    update.executeUpdate()
                }
            }
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
