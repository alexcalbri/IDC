package com.ideasdeveloper.idc.server.extensions.packages

import java.sql.Connection

/**
 * Autorizaciones del dueno del servidor para instalar un paquete sin firma (EXTENSIONS.md "Unsigned
 * extensions"): una por paquete, identificada por el hash de su arbol.
 */
interface UnsignedAuthorizationStore {
    fun isAuthorized(packageSha256: String): Boolean

    /** Guarda la autorizacion con quien la dio, el origen y el texto de confirmacion escrito. */
    fun authorize(packageSha256: String, extensionId: String, sourceUrl: String, actor: String, acknowledgement: String)
}

/**
 * Implementacion sobre la tabla de control extension_unsigned_authorizations
 * (package_sha256 unico, extension_id, source_url, actor, acknowledgement, authorized_at).
 */
class JdbcUnsignedAuthorizationStore(private val connection: () -> Connection) : UnsignedAuthorizationStore {
    override fun isAuthorized(packageSha256: String): Boolean = connection().use { conn ->
        conn.prepareStatement("SELECT 1 FROM extension_unsigned_authorizations WHERE package_sha256 = ?").use { statement ->
            statement.setString(1, packageSha256.lowercase())
            statement.executeQuery().use { it.next() }
        }
    }

    override fun authorize(packageSha256: String, extensionId: String, sourceUrl: String, actor: String, acknowledgement: String) {
        require(Sha256Pattern.matches(packageSha256)) { "El hash debe tener 64 caracteres hexadecimales." }
        require(acknowledgement.isNotBlank()) { "Falta el texto de confirmacion." }
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO extension_unsigned_authorizations (package_sha256, extension_id, source_url, actor, confirmation_text)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (package_sha256) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, packageSha256.lowercase())
                statement.setString(2, extensionId)
                statement.setString(3, sourceUrl)
                statement.setString(4, actor)
                statement.setString(5, acknowledgement.trim())
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        val Sha256Pattern = Regex("[A-Fa-f0-9]{64}")
    }
}
