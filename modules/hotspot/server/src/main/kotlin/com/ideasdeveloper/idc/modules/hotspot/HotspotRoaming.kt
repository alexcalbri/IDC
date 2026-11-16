package com.ideasdeveloper.idc.modules.hotspot

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Live access a device already has on a shared network, found in one of the linked companies.
 * [expiresAt] null means it does not expire (user codes until an owner revokes them).
 */
data class RoamingAccess(
    val company: String,
    val name: String,
    val email: String?,
    val phone: String?,
    val accessMode: String,
    val userId: UUID?,
    val userCompany: String?,
    val expiresAt: Instant?,
) {
    // Whole minutes left, rounded up like code sessions, so the access never outlives the original by more than a minute.
    fun minutesLeft(now: Instant = Instant.now()): Int? =
        expiresAt?.let { ((Duration.between(now, it).toSeconds() + 59) / 60).toInt().coerceAtLeast(1) }
}

/**
 * Shared networks: the same SSID on the gateways of linked companies (same owner group), for
 * example through a UniFi Fabric. Each gateway counts guest time from its own authorization, so a
 * device that moves would get a fresh period at every gateway. Instead, the portal of the second
 * company looks up the device's live access on that SSID and authorizes it for the time left only.
 *
 * Only original accesses count as a source (not continued ones), so moving back and forth never
 * extends the end of the access and a revoked user code stops carrying over.
 */
object HotspotRoaming {
    /** When an access ends: [Never] for accesses without expiry, [Ended] once over or closed. */
    sealed interface End {
        data object Never : End
        data object Ended : End
        data class At(val instant: Instant) : End
    }

    /**
     * End of one authorized access. Accesses of a code session end with the session (it may be
     * shortened or revoked after the access); others after their minutes, 0 meaning no limit.
     */
    fun endOf(
        createdAt: Instant,
        minutes: Int,
        sessionId: UUID?,
        sessionExpiresAt: Instant?,
        sessionEndedAt: Instant?,
        disconnectedAt: Instant?,
        now: Instant,
    ): End {
        if (disconnectedAt != null) return End.Ended
        val end = when {
            sessionId != null && sessionEndedAt != null -> return End.Ended
            sessionId != null -> sessionExpiresAt?.let { End.At(it) } ?: End.Never
            minutes <= 0 -> End.Never
            else -> End.At(createdAt.plus(Duration.ofMinutes(minutes.toLong())))
        }
        return if (end is End.At && !end.instant.isAfter(now)) End.Ended else end
    }

    /** The access that lasts longest: one without expiry first, then the latest end. */
    fun <T> longest(candidates: List<Pair<T, End>>): Pair<T, End>? =
        candidates.filter { it.second != End.Ended }.maxWithOrNull(
            compareBy<Pair<T, End>> { it.second == End.Never }.thenBy { (it.second as? End.At)?.instant ?: Instant.MAX }
        )

    /** Of the live accesses found in several companies, the one that lasts longest. */
    fun best(found: List<RoamingAccess>): RoamingAccess? =
        longest(found.map { it to (it.expiresAt?.let { end -> End.At(end) } ?: End.Never) })?.first

    /**
     * Live access of [mac] on [ssid] in the company of [tenant], or null. The company must have the
     * same SSID configured as shared; a company without the hotspot tables has nothing to offer.
     */
    fun liveAccess(tenant: Connection, company: String, mac: String, ssid: String, now: Instant = Instant.now()): RoamingAccess? {
        val network = runCatching { HotspotNetworks.findBySsid(tenant, ssid) }.getOrNull()
        if (network?.shared != true) return null
        val rows = tenant.prepareStatement(
            """
            SELECT accesses.created_at, accesses.minutes, accesses.session_id, accesses.disconnected_at,
                   sessions.expires_at AS session_expires_at, sessions.ended_at AS session_ended_at,
                   registrations.name, registrations.email, registrations.phone, registrations.access_mode,
                   registrations.user_id, registrations.user_company
            FROM hotspot_accesses accesses
            JOIN hotspot_registrations registrations ON registrations.id = accesses.registration_id
            LEFT JOIN hotspot_code_sessions sessions ON sessions.id = accesses.session_id
            WHERE accesses.authorized AND accesses.mac = ? AND registrations.ssid = ?
              AND registrations.roamed_from IS NULL
            ORDER BY accesses.created_at DESC
            LIMIT 20
            """.trimIndent()
        ).use { query ->
            query.setString(1, mac)
            query.setString(2, ssid)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val end = endOf(
                            createdAt = rows.getTimestamp("created_at").toInstant(),
                            minutes = rows.getInt("minutes"),
                            sessionId = rows.getObject("session_id", UUID::class.java),
                            sessionExpiresAt = rows.getTimestamp("session_expires_at")?.toInstant(),
                            sessionEndedAt = rows.getTimestamp("session_ended_at")?.toInstant(),
                            disconnectedAt = rows.getTimestamp("disconnected_at")?.toInstant(),
                            now = now,
                        )
                        add(
                            RoamingAccess(
                                company = company,
                                name = rows.getString("name"),
                                email = rows.getString("email"),
                                phone = rows.getString("phone"),
                                accessMode = rows.getString("access_mode"),
                                userId = rows.getObject("user_id", UUID::class.java),
                                userCompany = rows.getString("user_company"),
                                expiresAt = (end as? End.At)?.instant,
                            ) to end
                        )
                    }
                }
            }
        }
        return longest(rows)?.first
    }

    /** Companies whose shared networks count: this one and the active companies of its owner group. */
    fun linkedCompanies(central: Connection, companyCode: String): List<String> {
        val members = central.prepareStatement(
            """
            SELECT member_company.code
            FROM companies target_company
            JOIN companies member_company ON member_company.owner_group = target_company.owner_group
            WHERE target_company.code = ?
              AND target_company.is_active AND NOT target_company.is_server_workspace
              AND member_company.is_active AND NOT member_company.is_server_workspace
            ORDER BY member_company.code
            """.trimIndent()
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        return listOf(companyCode) + (members - companyCode)
    }
}
