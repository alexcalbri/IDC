package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.auth.application.ApplicationUserCodes
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
 * User codes are owned by Core in application_users. This module stores only revocable WiFi-access
 * state in hotspot_user_codes. The server company also keeps grants for its users to join other
 * companies' networks in hotspot_user_network_grants.
 */
object HotspotUserCodes {
    fun generate(): String = ApplicationUserCodes.generate()

    fun isUserCode(code: String): Boolean = ApplicationUserCodes.isValid(code)

    /** The code belongs to Core; Hotspot stores only whether WiFi access is enabled. */
    fun codeFor(connection: Connection, userId: UUID, serverOwner: Boolean, central: Connection? = null): String {
        val codeConnection = if (serverOwner) {
            central ?: throw IllegalArgumentException("La base central es requerida para obtener el codigo del dueno del servidor.")
        } else {
            connection
        }
        val code = ApplicationUserCodes.ensure(codeConnection, userId)
        connection.prepareStatement(
            "INSERT INTO hotspot_user_codes (user_id, server_owner) VALUES (?, ?) ON CONFLICT (user_id) DO NOTHING"
        ).use { insert ->
            insert.setObject(1, userId)
            insert.setBoolean(2, serverOwner)
            insert.executeUpdate()
        }
        return code
    }

    /** Users of this database with their codes; with [central], also the server owners (server company). */
    fun users(tenant: Connection, central: Connection?): List<HotspotUser> {
        val local = userRows(tenant, "", "ORDER BY users.postgres_role") {}
        val owners = central?.let(::serverOwners).orEmpty()
        return local.map {
            HotspotUser(it.id, it.username, codeFor(tenant, it.id, false), false, it.active, it.displayName, accessEnabled(tenant, it.id))
        } + owners.map {
            HotspotUser(it.id, it.username, codeFor(tenant, it.id, true, central), true, true, it.displayName, accessEnabled(tenant, it.id))
        }
    }

    /** The user of [code] in this database, checking that the account is still active. */
    fun findActive(tenant: Connection, central: Connection, code: String): HotspotUser? {
        if (!isUserCode(code)) return null
        val normalizedCode = "us" + code.trim().drop(2).uppercase()
        val local = userRows(tenant, "WHERE users.user_code = ? AND users.is_active", "") { it.setString(1, normalizedCode) }
            .firstOrNull()
        if (local != null) {
            if (!accessEnabled(tenant, local.id)) return null
            return HotspotUser(local.id, local.username, normalizedCode, false, true, local.displayName)
        }
        val owner = userRows(
            central,
            "JOIN server_owners owners ON owners.user_id = users.id WHERE users.is_active AND users.user_code = ?",
            "",
        ) { it.setString(1, normalizedCode) }.firstOrNull() ?: return null
        val ownerAccess = tenant.prepareStatement(
            "SELECT access_enabled FROM hotspot_user_codes WHERE user_id = ? AND server_owner = TRUE"
        ).use { query ->
            query.setObject(1, owner.id)
            query.executeQuery().use { rows -> if (rows.next()) rows.getBoolean(1) else null }
        } ?: return null
        if (!ownerAccess) return null
        return HotspotUser(owner.id, owner.username, normalizedCode, true, true, owner.displayName)
    }

    /** Whether [code] belongs to an active business owner in this tenant. */
    fun isActiveBusinessOwnerCode(tenant: Connection, code: String): Boolean {
        if (!isUserCode(code)) return false
        val normalizedCode = "us" + code.trim().drop(2).uppercase()
        return tenant.prepareStatement(
            """
            SELECT 1
            FROM application_users users
            JOIN business_owner owner ON owner.user_id = users.id
            WHERE users.user_code = ? AND users.is_active
            """.trimIndent()
        ).use { query ->
            query.setString(1, normalizedCode)
            query.executeQuery().use { rows -> rows.next() }
        }
    }

    /** The active business owner's identity in this tenant, used to track/revoke access here. */
    fun activeBusinessOwner(tenant: Connection): HotspotUser? {
        val owner = userRows(
            tenant,
            "JOIN business_owner owner ON owner.user_id = users.id WHERE users.is_active",
            "LIMIT 1",
        ) {}.firstOrNull() ?: return null
        val code = codeFor(tenant, owner.id, false)
        return HotspotUser(
            owner.id,
            owner.username,
            code,
            false,
            true,
            owner.displayName,
            accessEnabled(tenant, owner.id),
        )
    }

    fun accessEnabled(connection: Connection, userId: UUID): Boolean = connection.prepareStatement(
        "SELECT access_enabled FROM hotspot_user_codes WHERE user_id = ?"
    ).use { query ->
        query.setObject(1, userId)
        query.executeQuery().use { rows -> if (rows.next()) rows.getBoolean(1) else true }
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
