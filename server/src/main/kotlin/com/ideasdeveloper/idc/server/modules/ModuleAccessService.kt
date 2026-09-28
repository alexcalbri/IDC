package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * Valida acceso server-side a rutas propias de módulos.
 *
 * La visibilidad del dashboard ayuda a la experiencia, pero no autoriza nada por si sola.
 * Toda ruta de un módulo que lea o modifique datos de tenant debe exigir:
 * - Bearer token de una sesión vigente.
 * - X-Company-Code para resolver la base de datos tenant.
 * - Módulo activo para la empresa.
 * - Permiso declarado por el módulo, excepto business_owner que administra su propia empresa.
 */
class ModuleAccessService(
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
) {
    private val tokenGenerator = SessionTokenGenerator()

    fun authorize(call: ApplicationCall, moduleId: String, permissionId: String): ModuleAccessDecision {
        val token = call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return ModuleAccessDecision.Unauthorized("missing_token", "La sesion es requerida.")

        val companyCode = call.request.header("X-Company-Code")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return ModuleAccessDecision.Unauthorized("missing_company", "La empresa es requerida.")

        val databaseName = findActiveDatabase(companyCode)
            ?: return ModuleAccessDecision.Forbidden("company_not_found", "La empresa no esta activa.")
        val tenantConfig = loginDatabases.tenantConfig(databaseName)
            ?: return ModuleAccessDecision.Forbidden("tenant_not_configured", "La base de datos de la empresa no esta configurada.")

        return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password).use { connection ->
            val user = connection.prepareStatement(
                """
                SELECT users.id,
                       EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id) AS business_owner
                FROM application_sessions sessions
                JOIN application_users users ON users.id = sessions.user_id
                WHERE sessions.token_hash = ?
                  AND sessions.revoked_at IS NULL
                  AND sessions.expires_at > CURRENT_TIMESTAMP
                  AND users.is_active = TRUE
                """.trimIndent()
            ).use { query ->
                query.setString(1, tokenGenerator.hash(token))
                query.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        ModuleUser(
                            id = rows.getObject("id", UUID::class.java),
                            businessOwner = rows.getBoolean("business_owner"),
                        )
                    }
                }
            } ?: return ModuleAccessDecision.Unauthorized("invalid_session", "La sesion no es valida.")

            if (!moduleEnabled(connection, moduleId)) {
                return ModuleAccessDecision.Forbidden("module_disabled", "El modulo no esta activo para esta empresa.")
            }

            if (user.businessOwner || hasPermission(connection, user.id, permissionId)) {
                ModuleAccessDecision.Allowed
            } else {
                ModuleAccessDecision.Forbidden("missing_permission", "No tienes permiso para usar esta funcion.")
            }
        }
    }

    fun tenantConnection(call: ApplicationCall): Connection? {
        val companyCode = call.request.header("X-Company-Code")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val databaseName = findActiveDatabase(companyCode) ?: return null
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return null
        return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password)
    }

    private fun findActiveDatabase(companyCode: String): String? = central.connection.use { connection ->
        connection.prepareStatement("SELECT database_name FROM companies WHERE code = ? AND is_active = TRUE").use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    private fun tenantConnection(jdbcUrl: String, username: String, password: String) = PGSimpleDataSource().apply {
        setURL(jdbcUrl)
        user = username
        this.password = password
    }.connection

    private fun moduleEnabled(connection: Connection, moduleId: String): Boolean = connection.prepareStatement(
        "SELECT 1 FROM tenant_modules WHERE module_id = ? AND status = 'enabled'"
    ).use { query ->
        query.setString(1, moduleId)
        query.executeQuery().use { rows -> rows.next() }
    }

    private fun hasPermission(connection: Connection, userId: UUID, permissionId: String): Boolean = connection.prepareStatement(
        "SELECT 1 FROM application_user_permissions WHERE user_id = ? AND permission_id = ?"
    ).use { query ->
        query.setObject(1, userId)
        query.setString(2, permissionId)
        query.executeQuery().use { rows -> rows.next() }
    }
}

sealed class ModuleAccessDecision {
    data object Allowed : ModuleAccessDecision()
    data class Unauthorized(val code: String, val message: String) : ModuleAccessDecision()
    data class Forbidden(val code: String, val message: String) : ModuleAccessDecision()
}

@Serializable
data class ModuleAccessErrorResponse(val code: String, val message: String)

private data class ModuleUser(val id: UUID, val businessOwner: Boolean)

/**
 * Guard reutilizable para nuevas vistas/rutas de módulos.
 *
 * Cada endpoint debe llamar a este helper con el permiso mas especifico posible
 * antes de responder datos o ejecutar acciones del módulo.
 */
suspend fun ApplicationCall.requireModulePermission(
    access: ModuleAccessService,
    moduleId: String,
    permissionId: String,
): Boolean {
    val decision = try {
        withContext(Dispatchers.IO) { access.authorize(this@requireModulePermission, moduleId, permissionId) }
    } catch (_: SQLException) {
        ModuleAccessDecision.Forbidden("tenant_access_error", "No se pudo validar el acceso a la empresa.")
    }

    return when (decision) {
        ModuleAccessDecision.Allowed -> true
        is ModuleAccessDecision.Unauthorized -> {
            respond(HttpStatusCode.Unauthorized, ModuleAccessErrorResponse(decision.code, decision.message))
            false
        }
        is ModuleAccessDecision.Forbidden -> {
            respond(HttpStatusCode.Forbidden, ModuleAccessErrorResponse(decision.code, decision.message))
            false
        }
    }
}
