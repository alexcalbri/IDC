package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.company.application.ServerWorkspace
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
    // Server-wide SMTP configured by the server administrator; modules may allow a company override.
    val serverMail: SmtpSettings? = null,
    // Whether the server owner installed the module; routes of every compiled module exist from startup.
    private val moduleInstalled: (String) -> Boolean = { true },
) {
    private val tokenGenerator = SessionTokenGenerator()

    // Public module endpoints (e.g. a captive portal) must check this before serving anything.
    fun isInstalled(moduleId: String): Boolean = moduleInstalled(moduleId)

    // Central control-plane connection for module-owned central tables such as public_domains.
    fun centralConnection(): Connection = central.connection

    // Web module pages authenticate with cookies scoped to /modules/{moduleId}/ instead of headers.
    fun companyCode(call: ApplicationCall): String? = (call.request.header("X-Company-Code") ?: call.request.cookies[CompanyCookie])
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun accessToken(call: ApplicationCall): String? = (
        call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?: call.request.cookies[TokenCookie]
        )
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    /**
     * Opens an active company's tenant database without a user session.
     *
     * Only for public module endpoints (for example a captive portal) that resolved the
     * company from server-side data such as a registered domain, never from client input.
     */
    fun tenantConnectionForCompany(companyCode: String): Connection? {
        val databaseName = findActiveDatabase(companyCode) ?: return null
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return null
        return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password)
    }

    /**
     * Tenant database of a company for a public webhook route (/modules/{moduleId}/hooks/{company}/...),
     * or null when the company is not active or does not use the installed module. The company comes
     * from the URL, so the module must authenticate the request (for example a signature checked
     * with a secret stored in this tenant) before trusting or storing anything.
     */
    fun tenantConnectionForWebhook(companyCode: String, moduleId: String): Connection? {
        if (!isInstalled(moduleId) || !CompanyCodePattern.matches(companyCode)) return null
        val connection = tenantConnectionForCompany(companyCode) ?: return null
        val enabled = try {
            moduleEnabled(connection, moduleId)
        } catch (failure: SQLException) {
            connection.close()
            throw failure
        }
        if (!enabled) {
            connection.close()
            return null
        }
        return connection
    }

    // Active companies (the server workspace included) that have [moduleId] enabled; used by module jobs.
    fun companiesWithModule(moduleId: String): List<String> {
        if (!isInstalled(moduleId)) return emptyList()
        val companies = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies WHERE is_active = TRUE ORDER BY code").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
            }
        }
        return companies.filter { (_, databaseName) ->
            val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return@filter false
            runCatching {
                tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password).use { moduleEnabled(it, moduleId) }
            }.getOrDefault(false)
        }.map { it.first }
    }

    fun authorize(call: ApplicationCall, moduleId: String, permissionId: String): ModuleAccessDecision =
        when (val lookup = permissions(call, moduleId)) {
            is ModulePermissionLookup.Granted -> if (lookup.permissions.has(permissionId)) {
                ModuleAccessDecision.Allowed
            } else {
                ModuleAccessDecision.Forbidden("missing_permission", "No tienes permiso para usar esta funcion.")
            }
            is ModulePermissionLookup.Denied -> lookup.decision
        }

    /**
     * Validates session, company and module state once and returns every permission the user holds
     * in the module, so a screen can show only the sections the user may use.
     */
    fun permissions(call: ApplicationCall, moduleId: String): ModulePermissionLookup {
        if (!moduleInstalled(moduleId)) {
            return ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("module_not_installed", "El modulo no esta instalado en el servidor."))
        }
        val token = accessToken(call)
            ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Unauthorized("missing_token", "La sesion es requerida."))
        val companyCode = companyCode(call)
            ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Unauthorized("missing_company", "La empresa es requerida."))

        val databaseName = findActiveDatabase(companyCode)
            ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("company_not_found", "La empresa no esta activa."))
        val tenantConfig = loginDatabases.tenantConfig(databaseName)
            ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("tenant_not_configured", "La base de datos de la empresa no esta configurada."))

        // Server owners sign in centrally and hold every permission in the server workspace; its
        // staff use the tenant sessions and permissions below, like a company's users.
        val serverOwnerId = if (companyCode == ServerWorkspace.Code && isServerWorkspace(companyCode)) serverOwnerSessionUser(token) else null
        if (serverOwnerId != null) {
            return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password).use { connection ->
                if (!moduleEnabled(connection, moduleId)) {
                    ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("module_disabled", "El modulo no esta activo en el espacio del servidor."))
                } else {
                    ModulePermissionLookup.Granted(
                        ModulePermissions(companyCode, token, businessOwner = true, granted = emptySet(), userId = serverOwnerId, serverOwner = true)
                    )
                }
            }
        }

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
            } ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Unauthorized("invalid_session", "La sesion no es valida."))

            if (!moduleEnabled(connection, moduleId)) {
                return ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("module_disabled", "El modulo no esta activo para esta empresa."))
            }

            val granted = if (user.businessOwner) emptySet() else connection.prepareStatement(
                "SELECT permission_id FROM application_user_permissions WHERE user_id = ?"
            ).use { query ->
                query.setObject(1, user.id)
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
            ModulePermissionLookup.Granted(ModulePermissions(companyCode, token, user.businessOwner, granted, userId = user.id))
        }
    }

    fun tenantConnection(call: ApplicationCall): Connection? {
        val companyCode = companyCode(call) ?: return null
        val databaseName = findActiveDatabase(companyCode) ?: return null
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return null
        return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password)
    }

    companion object {
        const val TokenCookie = "idc_module_token"
        const val CompanyCookie = "idc_module_company"
        // Company codes as created by provisioning; anything else in a webhook URL is rejected early.
        private val CompanyCodePattern = Regex("[a-z][a-z0-9_]{0,62}")
    }

    private fun isServerWorkspace(companyCode: String): Boolean = try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT is_server_workspace FROM companies WHERE code = ?").use { query ->
                query.setString(1, companyCode)
                query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
        }
    } catch (_: SQLException) {
        false
    }

    // Central user id of an active server_owner session, or null.
    private fun serverOwnerSessionUser(token: String): UUID? = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT sessions.user_id
            FROM application_sessions sessions
            JOIN server_owners owners ON owners.user_id = sessions.user_id
            JOIN application_users users ON users.id = sessions.user_id
            WHERE sessions.token_hash = ?
              AND sessions.revoked_at IS NULL
              AND sessions.expires_at > CURRENT_TIMESTAMP
              AND users.is_active = TRUE
            """.trimIndent()
        ).use { query ->
            query.setString(1, tokenGenerator.hash(token))
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
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
}

// Permissions of the current user in one module. business_owner holds every permission.
class ModulePermissions(
    val companyCode: String,
    internal val accessToken: String,
    val businessOwner: Boolean,
    private val granted: Set<String>,
    // Signed-in user: a tenant user, or for server owners their central user id.
    val userId: UUID? = null,
    // True only for a server_owner using the server workspace.
    val serverOwner: Boolean = false,
) {
    fun has(permissionId: String): Boolean = businessOwner || permissionId in granted
}

sealed class ModulePermissionLookup {
    data class Granted(val permissions: ModulePermissions) : ModulePermissionLookup()
    data class Denied(val decision: ModuleAccessDecision) : ModulePermissionLookup()
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
 * Like [requireModulePermission] but returns all the user's permissions in the module, or
 * responds 401/403 and returns null. Use it for screens that adapt to what the user may do.
 */
suspend fun ApplicationCall.requireModuleAccess(access: ModuleAccessService, moduleId: String): ModulePermissions? {
    val lookup = try {
        withContext(Dispatchers.IO) { access.permissions(this@requireModuleAccess, moduleId) }
    } catch (_: SQLException) {
        ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("tenant_access_error", "No se pudo validar el acceso a la empresa."))
    }
    return when (lookup) {
        is ModulePermissionLookup.Granted -> lookup.permissions
        is ModulePermissionLookup.Denied -> {
            when (val decision = lookup.decision) {
                is ModuleAccessDecision.Unauthorized -> respond(HttpStatusCode.Unauthorized, ModuleAccessErrorResponse(decision.code, decision.message))
                is ModuleAccessDecision.Forbidden -> respond(HttpStatusCode.Forbidden, ModuleAccessErrorResponse(decision.code, decision.message))
                ModuleAccessDecision.Allowed -> Unit
            }
            null
        }
    }
}

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
