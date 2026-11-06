package com.ideasdeveloper.idc.modules.hotspot

import java.security.SecureRandom
import java.sql.Connection
import java.util.UUID

/**
 * A user with their WiFi code. [serverOwner] users live in the central database. [displayName] is the
 * user's full name; users created before it was required may not have it until they sign in again.
 */
data class HotspotUser(
    val id: UUID,
    val username: String,
    val code: String,
    val serverOwner: Boolean,
    val active: Boolean,
    val displayName: String? = null,
    val wifiAccessEnabled: Boolean = true,
) {
    // Full name when there is one, otherwise the username.
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: username
}

// A row of application_users as the hotspot reads it.
private data class UserRow(val id: UUID, val username: String, val displayName: String?, val active: Boolean)

/** A network of another company that a user of the server company may join with their code. */
data class HotspotUserGrant(val id: UUID, val companyCode: String, val ssid: String, val userId: UUID)

/**
 * WiFi codes of the users, owned by the hotspot module (hotspot_user_codes): the users table of the
 * core is only read. Codes are created the first time they are needed and never change. The server
 * company also keeps codes for the server owners and the networks of other companies its users
 * were authorized to join (hotspot_user_network_grants).
 */
object HotspotUserCodes {
    private val random = SecureRandom()
    // Same alphabet as customer codes: no 0/O, 1/I.
    private const val Alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val Length = 8
    private const val Prefix = "us"

    fun generate(): String = Prefix + buildString(Length) { repeat(Length) { append(Alphabet[random.nextInt(Alphabet.length)]) } }

    fun isUserCode(code: String): Boolean {
        val normalized = code.trim().uppercase()
        return normalized.length == Prefix.length + Length &&
            normalized.startsWith(Prefix.uppercase()) &&
            normalized.drop(Prefix.length).all { it in Alphabet }
    }

    /** Code of [userId], creating it when the user has none yet. */
    fun codeFor(connection: Connection, userId: UUID, serverOwner: Boolean): String {
        repeat(10) {
            existing(connection, userId)?.let { return it }
            // A code already taken by someone else inserts nothing and the loop tries another one.
            connection.prepareStatement(
                "INSERT INTO hotspot_user_codes (user_id, user_code, server_owner) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { insert ->
                insert.setObject(1, userId)
                insert.setString(2, generate())
                insert.setBoolean(3, serverOwner)
                insert.executeUpdate()
            }
        }
        return existing(connection, userId) ?: throw IllegalStateException("No se pudo generar el codigo de usuario.")
    }

    private fun existing(connection: Connection, userId: UUID): String? = connection.prepareStatement(
        "SELECT user_code FROM hotspot_user_codes WHERE user_id = ?"
    ).use { query ->
        query.setObject(1, userId)
        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

    /** Users of this database with their codes; with [central], also the server owners (server company). */
    fun users(tenant: Connection, central: Connection?): List<HotspotUser> {
        val local = userRows(tenant, "", "ORDER BY users.postgres_role") {}
        val owners = central?.let(::serverOwners).orEmpty()
        return local.map {
            HotspotUser(it.id, it.username, codeFor(tenant, it.id, false), false, it.active, it.displayName, accessEnabled(tenant, it.id))
        } + owners.map {
            HotspotUser(it.id, it.username, codeFor(tenant, it.id, true), true, true, it.displayName, accessEnabled(tenant, it.id))
        }
    }

    /** The user of [code] in this database, checking that the account is still active. */
    fun findActive(tenant: Connection, central: Connection, code: String): HotspotUser? {
        if (!isUserCode(code)) return null
        val normalizedCode = Prefix + code.trim().drop(Prefix.length).uppercase()
        val (userId, serverOwner, enabled) = tenant.prepareStatement(
            "SELECT user_id, server_owner, access_enabled FROM hotspot_user_codes WHERE user_code = ?"
        ).use { query ->
            query.setString(1, normalizedCode)
            query.executeQuery().use { rows ->
                if (rows.next()) Triple(rows.getObject(1, UUID::class.java), rows.getBoolean(2), rows.getBoolean(3)) else null
            }
        } ?: return null
        if (!enabled) return null
        val user = if (serverOwner) {
            serverOwners(central).firstOrNull { it.id == userId }
        } else {
            userRows(tenant, "WHERE users.id = ? AND users.is_active", "") { it.setObject(1, userId) }.firstOrNull()
        } ?: return null
        return HotspotUser(userId, user.username, normalizedCode, serverOwner, true, user.displayName)
    }

    fun accessEnabled(connection: Connection, userId: UUID): Boolean = connection.prepareStatement(
        "SELECT access_enabled FROM hotspot_user_codes WHERE user_id = ?"
    ).use { query ->
        query.setObject(1, userId)
        query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
    }

    fun setAccessEnabled(connection: Connection, userId: UUID, enabled: Boolean): Boolean = connection.prepareStatement(
        "UPDATE hotspot_user_codes SET access_enabled = ? WHERE user_id = ?"
    ).use { update ->
        update.setBoolean(1, enabled)
        update.setObject(2, userId)
        update.executeUpdate() == 1
    }

    // Active server owners from the central database.
    private fun serverOwners(central: Connection): List<UserRow> = userRows(
        central,
        "JOIN server_owners owners ON owners.user_id = users.id WHERE users.is_active",
        "ORDER BY users.postgres_role",
    ) {}

    /**
     * Users with their full name. Before core migration V003 there is no display_name column; the
     * statement then fails and the users are read without names.
     */
    private fun userRows(connection: Connection, filter: String, order: String, bind: (java.sql.PreparedStatement) -> Unit): List<UserRow> {
        fun read(name: String) = connection.prepareStatement(
            "SELECT users.id, users.postgres_role, $name, users.is_active FROM application_users users $filter $order"
        ).use { query ->
            bind(query)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(UserRow(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getBoolean(4))) }
            }
        }
        return try {
            read("users.display_name")
        } catch (_: java.sql.SQLException) {
            read("NULL")
        }
    }

    // ---- Networks of other companies granted to users of the server company ----

    fun grants(connection: Connection): List<HotspotUserGrant> = connection.prepareStatement(
        "SELECT id, company_code, ssid, user_id FROM hotspot_user_network_grants ORDER BY company_code, ssid"
    ).use { query ->
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(HotspotUserGrant(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getObject(4, UUID::class.java)))
                }
            }
        }
    }

    fun hasGrant(connection: Connection, companyCode: String, ssid: String, userId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM hotspot_user_network_grants WHERE company_code = ? AND ssid = ? AND user_id = ?"
    ).use { query ->
        query.setString(1, companyCode)
        query.setString(2, ssid)
        query.setObject(3, userId)
        query.executeQuery().use { it.next() }
    }

    fun addGrant(connection: Connection, companyCode: String, ssid: String, userId: UUID) {
        connection.prepareStatement(
            "INSERT INTO hotspot_user_network_grants (id, company_code, ssid, user_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, companyCode)
            insert.setString(3, ssid)
            insert.setObject(4, userId)
            insert.executeUpdate()
        }
    }

    fun deleteGrant(connection: Connection, id: UUID): Boolean = connection.prepareStatement(
        "DELETE FROM hotspot_user_network_grants WHERE id = ?"
    ).use { delete ->
        delete.setObject(1, id)
        delete.executeUpdate() == 1
    }
}
