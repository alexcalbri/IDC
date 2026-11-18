package com.ideasdeveloper.idc.server.extensions

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.security.SecretBox
import java.sql.Connection

/** Where the extension code gets its connections: the central (control) database and a company's. */
interface ExtensionConnections {
    fun central(): Connection

    /** Tenant database of an active company, or null. The caller closes it. */
    fun tenant(companyCode: String): Connection?

    companion object {
        fun from(access: ModuleAccessService): ExtensionConnections = object : ExtensionConnections {
            override fun central(): Connection = access.centralConnection()
            override fun tenant(companyCode: String): Connection? = access.tenantConnectionForCompany(companyCode)
        }
    }
}

data class TenantExtensionRow(val extensionId: String, val moduleId: String, val version: String, val status: String)

data class StoredSetting(val key: String, val value: String, val secret: Boolean)

/**
 * Reads and writes the tenant tables of extensions (tenant_extensions, tenant_extension_settings,
 * tenant_extension_objects). It only needs the SecretBox, so the route gate and the ExtensionAccess
 * handed to extensions can use it before the ExtensionService exists.
 */
class ExtensionStorage(val secrets: SecretBox) {
    fun row(tenant: Connection, extensionId: String): TenantExtensionRow? =
        tenant.prepareStatement("SELECT extension_id, module_id, version, status FROM tenant_extensions WHERE extension_id = ?").use { query ->
            query.setString(1, extensionId)
            query.executeQuery().use { rows -> if (rows.next()) rowOf(rows) else null }
        }

    /** Like [row] but locks it until the end of the transaction (call it with autoCommit off). */
    fun rowForUpdate(tenant: Connection, extensionId: String): TenantExtensionRow? =
        tenant.prepareStatement("SELECT extension_id, module_id, version, status FROM tenant_extensions WHERE extension_id = ? FOR UPDATE").use { query ->
            query.setString(1, extensionId)
            query.executeQuery().use { rows -> if (rows.next()) rowOf(rows) else null }
        }

    fun status(tenant: Connection, extensionId: String): String? = row(tenant, extensionId)?.status

    fun rows(tenant: Connection): Map<String, TenantExtensionRow> =
        tenant.prepareStatement("SELECT extension_id, module_id, version, status FROM tenant_extensions").use { query ->
            query.executeQuery().use { rows -> buildMap { while (rows.next()) rowOf(rows).let { put(it.extensionId, it) } } }
        }

    fun storedSettings(tenant: Connection, extensionId: String): List<StoredSetting> =
        tenant.prepareStatement("SELECT key, value, secret FROM tenant_extension_settings WHERE extension_id = ? ORDER BY key").use { query ->
            query.setString(1, extensionId)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(StoredSetting(rows.getString(1), rows.getString(2), rows.getBoolean(3))) }
            }
        }

    /** Settings with secrets opened; for the extension's own code, never for an API response. */
    fun settings(tenant: Connection, extensionId: String): ExtensionSettings = object : ExtensionSettings {
        private val stored by lazy { storedSettings(tenant, extensionId) }

        override fun get(key: String): String? =
            stored.firstOrNull { it.key == key }?.let { if (it.secret) secrets.open(tenant, it.value) else it.value }

        override fun all(includeSecrets: Boolean): Map<String, String> =
            stored.filter { includeSecrets || !it.secret }
                .associate { it.key to (if (it.secret) secrets.open(tenant, it.value) else it.value) }
    }

    fun recordObject(tenant: Connection, extensionId: String, kind: String, objectRef: String, optionId: String?) {
        tenant.prepareStatement(
            """
            INSERT INTO tenant_extension_objects (extension_id, kind, object_ref, option_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (extension_id, kind, object_ref) DO UPDATE SET option_id = EXCLUDED.option_id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, extensionId)
            statement.setString(2, kind)
            statement.setString(3, objectRef)
            statement.setString(4, optionId)
            statement.executeUpdate()
        }
    }

    /** Writes one setting; a secret is sealed here, so callers always pass the plain value. */
    fun putSetting(tenant: Connection, extensionId: String, key: String, plain: String, secret: Boolean) {
        val stored = if (secret) secrets.seal(tenant, plain) else plain
        tenant.prepareStatement(
            """
            INSERT INTO tenant_extension_settings (extension_id, key, value, secret, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (extension_id, key) DO UPDATE SET value = EXCLUDED.value, secret = EXCLUDED.secret, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, extensionId)
            statement.setString(2, key)
            statement.setString(3, stored)
            statement.setBoolean(4, secret)
            statement.executeUpdate()
        }
    }

    fun deleteSetting(tenant: Connection, extensionId: String, key: String) {
        tenant.prepareStatement("DELETE FROM tenant_extension_settings WHERE extension_id = ? AND key = ?").use { statement ->
            statement.setString(1, extensionId)
            statement.setString(2, key)
            statement.executeUpdate()
        }
    }

    private fun rowOf(rows: java.sql.ResultSet) =
        TenantExtensionRow(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))
}
