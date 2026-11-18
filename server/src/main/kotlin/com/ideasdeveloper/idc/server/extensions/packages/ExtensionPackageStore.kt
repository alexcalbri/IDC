package com.ideasdeveloper.idc.server.extensions.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.sql.Connection

/** Fila de server_extensions que el servicio de paquetes necesita. */
data class StoredExtensionPackage(
    val id: String,
    val moduleId: String,
    val version: String,
    val sourceType: String,
    /** installed | pending_restart | removed */
    val status: String,
    val installedPackagePath: String?,
    val packageSha256: String?,
    val signed: Boolean,
    /** SHA-256 del jar compilado (manifest->>'jarSha256'); el arranque lo comprueba antes de cargarlo. */
    val jarSha256: String? = null,
)

/** Acceso a server_extensions (control V011). */
interface ExtensionPackageStore {
    fun find(id: String): StoredExtensionPackage?
    fun all(): List<StoredExtensionPackage>

    /** Guarda el paquete recien compilado con status pending_restart. */
    fun upsert(built: BuiltExtension)

    /** Marca como removed un paquete externo; false si no existe o no es externo. Las empresas no se tocan. */
    fun markRemoved(id: String): Boolean

    /** Paquetes externos que el arranque debe cargar (installed o pending_restart). */
    fun loadable(): List<StoredExtensionPackage>

    /** Pasa de pending_restart a installed los paquetes cuyo jar cargo. */
    fun markInstalled(ids: Collection<String>)
}

class JdbcExtensionPackageStore(private val connection: () -> Connection) : ExtensionPackageStore {
    private val json = Json { encodeDefaults = true }
    private val columns = "id, module_id, version, source_type, status, installed_package_path, package_sha256, signed, manifest->>'jarSha256' AS jar_sha256"

    private fun read(rows: java.sql.ResultSet) = StoredExtensionPackage(
        id = rows.getString("id"),
        moduleId = rows.getString("module_id"),
        version = rows.getString("version"),
        sourceType = rows.getString("source_type"),
        status = rows.getString("status"),
        installedPackagePath = rows.getString("installed_package_path"),
        packageSha256 = rows.getString("package_sha256"),
        signed = rows.getBoolean("signed"),
        jarSha256 = rows.getString("jar_sha256"),
    )

    override fun find(id: String): StoredExtensionPackage? = connection().use { conn ->
        conn.prepareStatement("SELECT $columns FROM server_extensions WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        }
    }

    override fun all(): List<StoredExtensionPackage> = connection().use { conn ->
        conn.prepareStatement("SELECT $columns FROM server_extensions ORDER BY id").use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }
    }

    override fun upsert(built: BuiltExtension) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO server_extensions (id, module_id, version, min_module_version, source_type, source_url, package_sha256,
                                               signed, key_id, installed_package_path, manifest, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'pending_restart')
                ON CONFLICT (id) DO UPDATE SET
                    module_id = EXCLUDED.module_id,
                    version = EXCLUDED.version,
                    min_module_version = EXCLUDED.min_module_version,
                    source_type = EXCLUDED.source_type,
                    source_url = EXCLUDED.source_url,
                    package_sha256 = EXCLUDED.package_sha256,
                    signed = EXCLUDED.signed,
                    key_id = EXCLUDED.key_id,
                    installed_package_path = EXCLUDED.installed_package_path,
                    manifest = EXCLUDED.manifest,
                    status = 'pending_restart',
                    updated_at = CURRENT_TIMESTAMP
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, built.manifest.id)
                statement.setString(2, built.manifest.module)
                statement.setString(3, built.manifest.version)
                statement.setString(4, built.manifest.minModuleVersion)
                statement.setString(5, built.sourceType)
                statement.setString(6, built.sourceUrl)
                statement.setString(7, built.packageSha256)
                statement.setBoolean(8, built.signed)
                statement.setString(9, built.keyId)
                statement.setString(10, built.jar.toString())
                // El manifiesto lleva ademas el SHA-256 del jar (jarSha256) para comprobarlo al cargar.
                val manifest = buildJsonObject {
                    json.encodeToJsonElement(com.ideasdeveloper.idc.server.extensions.ExtensionManifest.serializer(), built.manifest).jsonObject
                        .forEach { (key, value) -> put(key, value) }
                    put("jarSha256", built.jarSha256)
                }
                statement.setString(11, manifest.toString())
                statement.executeUpdate()
            }
        }
    }

    override fun markRemoved(id: String): Boolean = connection().use { conn ->
        conn.prepareStatement(
            "UPDATE server_extensions SET status = 'removed', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND source_type IN ('github', 'zip', 'url') AND status <> 'removed'",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeUpdate() > 0
        }
    }

    override fun loadable(): List<StoredExtensionPackage> = connection().use { conn ->
        conn.prepareStatement(
            "SELECT $columns FROM server_extensions WHERE source_type IN ('github', 'zip', 'url') AND status IN ('installed', 'pending_restart') AND installed_package_path IS NOT NULL ORDER BY id",
        ).use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }
    }

    override fun markInstalled(ids: Collection<String>) {
        if (ids.isEmpty()) return
        connection().use { conn ->
            conn.prepareStatement(
                "UPDATE server_extensions SET status = 'installed', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'pending_restart'",
            ).use { statement ->
                ids.forEach { statement.setString(1, it); statement.addBatch() }
                statement.executeBatch()
            }
        }
    }
}
