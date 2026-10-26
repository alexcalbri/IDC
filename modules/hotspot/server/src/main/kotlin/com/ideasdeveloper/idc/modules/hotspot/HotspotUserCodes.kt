package com.ideasdeveloper.idc.modules.hotspot

import java.security.SecureRandom
import java.sql.Connection
import java.util.UUID

/** A user with their WiFi code. [serverOwner] users live in the central database. */
data class HotspotUser(val id: UUID, val username: String, val code: String, val serverOwner: Boolean, val active: Boolean)

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

    fun generate(): String = buildString(Length) { repeat(Length) { append(Alphabet[random.nextInt(Alphabet.length)]) } }

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
        val local = tenant.prepareStatement("SELECT id, postgres_role, is_active FROM application_users ORDER BY postgres_role").use { query ->
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(Triple(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getBoolean(3))) }
            }
        }
        val owners = central?.let(::serverOwners).orEmpty()
        return local.map { (id, name, active) -> HotspotUser(id, name, codeFor(tenant, id, false), false, active) } +
            owners.map { (id, name) -> HotspotUser(id, name, codeFor(tenant, id, true), true, true) }
    }

    /** The user of [code] in this database, checking that the account is still active. */
    fun findActive(tenant: Connection, central: Connection, code: String): HotspotUser? {
        val (userId, serverOwner) = tenant.prepareStatement(
            "SELECT user_id, server_owner FROM hotspot_user_codes WHERE user_code = ?"
        ).use { query ->
            query.setString(1, code)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) to rows.getBoolean(2) else null }
        } ?: return null
        val username = if (serverOwner) {
            serverOwners(central).firstOrNull { it.first == userId }?.second
        } else {
            tenant.prepareStatement("SELECT postgres_role FROM application_users WHERE id = ? AND is_active").use { query ->
                query.setObject(1, userId)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        } ?: return null
        return HotspotUser(userId, username, code, serverOwner, true)
    }

    // Active server owners from the central database.
    private fun serverOwners(central: Connection): List<Pair<UUID, String>> = central.prepareStatement(
        """
        SELECT users.id, users.postgres_role
        FROM server_owners owners
        JOIN application_users users ON users.id = owners.user_id
        WHERE users.is_active
        ORDER BY users.postgres_role
        """.trimIndent()
    ).use { query ->
        query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getString(2)) } }
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
