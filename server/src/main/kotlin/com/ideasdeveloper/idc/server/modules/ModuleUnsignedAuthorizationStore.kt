package com.ideasdeveloper.idc.server.modules

import kotlinx.serialization.Serializable
import java.sql.Connection

/**
 * Autorizaciones del dueno del servidor para instalar un paquete de modulo sin firma: una por
 * paquete, identificada por el hash SHA-256 de su arbol y por el commit de 40 hex que reviso.
 */
interface ModuleUnsignedAuthorizationStore {
    fun isAuthorized(packageSha256: String): Boolean

    /** Guarda la autorizacion de [moduleId] para ese hash y commit, con quien la dio y su confirmacion escrita. */
    fun authorize(packageSha256: String, moduleId: String, commit: String, actor: String, acknowledgement: String)
}

/** Almacen por defecto: no autoriza nada, asi un paquete sin firma nunca se instala por omision. */
object DenyAllModuleAuthorizations : ModuleUnsignedAuthorizationStore {
    override fun isAuthorized(packageSha256: String): Boolean = false

    override fun authorize(packageSha256: String, moduleId: String, commit: String, actor: String, acknowledgement: String) {
        throw UnsupportedOperationException("Este almacen no guarda autorizaciones.")
    }
}

/**
 * Implementacion sobre la misma tabla de control que las extensiones (extension_unsigned_authorizations):
 * el hash es unico, el id del modulo va en extension_id y el commit en source_url con el prefijo
 * [SourcePrefix]. Al consultar solo cuentan los registros con ese prefijo, de modo que una
 * autorizacion dada a una extension nunca sirve para instalar un modulo.
 */
class JdbcModuleUnsignedAuthorizationStore(private val connection: () -> Connection) : ModuleUnsignedAuthorizationStore {
    override fun isAuthorized(packageSha256: String): Boolean = connection().use { conn ->
        conn.prepareStatement(
            "SELECT 1 FROM extension_unsigned_authorizations WHERE package_sha256 = ? AND starts_with(source_url, ?)",
        ).use { statement ->
            statement.setString(1, packageSha256.lowercase())
            statement.setString(2, SourcePrefix)
            statement.executeQuery().use { it.next() }
        }
    }

    override fun authorize(packageSha256: String, moduleId: String, commit: String, actor: String, acknowledgement: String) {
        require(Sha256Pattern.matches(packageSha256)) { "El hash debe tener 64 caracteres hexadecimales." }
        require(CommitPattern.matches(commit.trim().lowercase())) { "El commit debe tener 40 caracteres hexadecimales." }
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
                statement.setString(2, moduleId)
                statement.setString(3, SourcePrefix + commit.trim().lowercase())
                statement.setString(4, actor)
                statement.setString(5, acknowledgement.trim())
                statement.executeUpdate()
            }
        }
    }

    companion object {
        /** Marca los registros de modulos en source_url, para distinguirlos de los de extensiones. */
        const val SourcePrefix = "module-commit:"
        private val Sha256Pattern = Regex("[A-Fa-f0-9]{64}")
        private val CommitPattern = Regex("[a-f0-9]{40}")
    }
}

/** Lo que se descarga hoy de un modulo: commit, URL y hash del arbol calculado. Antes de autorizar sin firma. */
@Serializable
data class ModulePackageInspection(
    val id: String,
    val commit: String,
    val commitUrl: String,
    /** Hash del arbol calculado sobre lo descargado (no el declarado en extension.sig.json). */
    val packageSha256: String,
    /** true solo con una firma verificada sobre ese hash. */
    val signed: Boolean,
    val keyId: String? = null,
    /** Por que una firma presente no vale (hash distinto, llave desconocida...). */
    val signatureProblem: String? = null,
)

@Serializable
data class AuthorizeUnsignedModuleRequest(
    val confirmationText: String = "",
    /** Hash del arbol que el usuario reviso (GET .../inspect). */
    val expectedPackageSha256: String = "",
    /** Commit de 40 hex que el usuario reviso. */
    val expectedCommit: String = "",
)

@Serializable
data class ModuleUnsignedAuthorizationResponse(val id: String, val packageSha256: String, val sourceUrl: String)
