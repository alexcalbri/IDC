package com.ideasdeveloper.idc.modules.hotspot

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A module that gives WiFi access passes to its customers (row of hotspot_pass_sources). */
data class PassSource(val id: String, val name: String, val description: String)

/** WiFi access a source module gave to a customer (row of hotspot_access_passes). */
data class AccessPass(
    val id: UUID,
    val sourceId: String,
    val rule: String,
    val validFrom: Instant?,
    val validUntil: Instant?,
    val minutesBalance: Int?,
)

/**
 * Access passes: the contract through which other modules (hotel, membership, cowork or any future
 * plugin) let their customers connect on customer-code networks. The source module registers in
 * hotspot_pass_sources and keeps its passes up to date; the hotspot only applies the rules:
 *
 * - [Period]: usable while now is in [validFrom, validUntil); no reconnection wait; a session never
 *   goes past validUntil.
 * - [Balance]: every session spends its authorized minutes from minutesBalance; no reconnection
 *   wait; validFrom/validUntil, when present, bound when it can be used.
 *
 * Passes count only while their source is enabled in Integraciones and listed by the network.
 */
object HotspotPasses {
    const val Period = "periodo"
    const val Balance = "saldo"
    // Not a pass source: customers of type hotspot, governed by the hotspot's own times.
    const val HotspotCustomers = "hotspot"

    fun sources(connection: Connection): List<PassSource> = connection.prepareStatement(
        "SELECT id, name, description FROM hotspot_pass_sources ORDER BY lower(name)"
    ).use { query ->
        query.executeQuery().use { rows ->
            buildList { while (rows.next()) add(PassSource(rows.getString("id"), rows.getString("name"), rows.getString("description"))) }
        }
    }

    // Customers of type hotspot, shown next to the pass sources of a customer-code network.
    val hotspotCustomers = PassSource(
        HotspotCustomers, "Clientes tipo hotspot",
        "Los que se registraron en el portal; usan el tiempo de acceso y de reconexion de la red.",
    )

    // What a customer-code network may list: customers of type hotspot and the enabled pass sources.
    fun choosable(connection: Connection): List<PassSource> {
        val enabled = HotspotIntegrations.enabled(connection)
        return listOf(hotspotCustomers) + sources(connection).filter { it.id in enabled }
    }

    /**
     * Minutes a session may last with [pass] at [now], given the network's [accessMinutes]; null when
     * the pass cannot be used now.
     */
    fun sessionMinutes(pass: AccessPass, accessMinutes: Int, now: Instant): Int? {
        if (pass.validFrom != null && now.isBefore(pass.validFrom)) return null
        val untilEnd = pass.validUntil?.let { until ->
            val seconds = Duration.between(now, until).seconds
            if (seconds <= 0) return null
            ((seconds + 59) / 60).toInt()
        }
        val minutes = when (pass.rule) {
            Period -> if (untilEnd == null || pass.validFrom == null) return null else minOf(accessMinutes, untilEnd)
            Balance -> {
                val balance = pass.minutesBalance?.takeIf { it > 0 } ?: return null
                minOf(accessMinutes, balance, untilEnd ?: Int.MAX_VALUE)
            }
            else -> return null
        }
        return minutes.takeIf { it > 0 }
    }

    /** Passes of [customerId] from [sourceIds], best first: periods ending last, then balances expiring first. */
    fun passes(connection: Connection, customerId: UUID, sourceIds: Set<String>): List<AccessPass> {
        if (sourceIds.isEmpty()) return emptyList()
        return connection.prepareStatement(
            """
            SELECT id, source_id, rule, valid_from, valid_until, minutes_balance
            FROM hotspot_access_passes
            WHERE customer_id = ? AND source_id = ANY (?)
            ORDER BY (rule = 'periodo') DESC,
                     CASE WHEN rule = 'periodo' THEN valid_until END DESC,
                     valid_until ASC NULLS LAST, minutes_balance DESC
            """.trimIndent()
        ).use { query ->
            query.setObject(1, customerId)
            query.setArray(2, connection.createArrayOf("text", sourceIds.toTypedArray()))
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toPass()) } }
        }
    }

    /** First of [passes] usable at [now], with the minutes of its session. */
    fun usable(passes: List<AccessPass>, accessMinutes: Int, now: Instant): Pair<AccessPass, Int>? =
        passes.firstNotNullOfOrNull { pass -> sessionMinutes(pass, accessMinutes, now)?.let { pass to it } }

    // Spends [minutes] of a balance pass; false when another session spent them first.
    fun spend(connection: Connection, passId: UUID, minutes: Int): Boolean = connection.prepareStatement(
        """
        UPDATE hotspot_access_passes SET minutes_balance = minutes_balance - ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = ? AND rule = 'saldo' AND minutes_balance >= ?
        """.trimIndent()
    ).use { update ->
        update.setInt(1, minutes)
        update.setObject(2, passId)
        update.setInt(3, minutes)
        update.executeUpdate() == 1
    }

    // Gives back minutes spent by a session the network controller did not authorize.
    fun refund(connection: Connection, passId: UUID, minutes: Int) {
        connection.prepareStatement(
            "UPDATE hotspot_access_passes SET minutes_balance = minutes_balance + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND rule = 'saldo'"
        ).use { update ->
            update.setInt(1, minutes)
            update.setObject(2, passId)
            update.executeUpdate()
        }
    }

    private fun ResultSet.toPass() = AccessPass(
        id = getObject("id", UUID::class.java),
        sourceId = getString("source_id"),
        rule = getString("rule"),
        validFrom = getTimestamp("valid_from")?.let(Timestamp::toInstant),
        validUntil = getTimestamp("valid_until")?.let(Timestamp::toInstant),
        minutesBalance = getObject("minutes_balance") as Int?,
    )
}
