package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.server.auth.domain.LoginCredentials
import com.ideasdeveloper.idc.server.auth.domain.LoginSuccessResponse
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
    // The MCP of each installed module (ModuleRegistry.mcp).
    private val moduleMcp: () -> List<RegisteredModuleMcp> = { emptyList() },
    // The app's login (ScopedLoginService.login), for module sign-in pages such as OAuth.
    private val login: (LoginCredentials) -> LoginSuccessResponse? = { null },
    // What each installed module adds to a customer's history (ModuleRegistry.customerTimelines).
    private val customerTimelines: () -> List<RegisteredCustomerTimeline> = { emptyList() },
) {
    private val log = org.slf4j.LoggerFactory.getLogger(ModuleAccessService::class.java)
    private val tokenGenerator = SessionTokenGenerator()

    // Public module endpoints (e.g. a captive portal) must check this before serving anything.
    fun isInstalled(moduleId: String): Boolean = moduleInstalled(moduleId)

    // The MCP of every installed module; the MCP endpoint filters it per company and user.
    fun moduleMcp(): List<RegisteredModuleMcp> = moduleMcp.invoke()

    // Agent tools of every installed module.
    fun mcpTools(): List<RegisteredMcpTool> = moduleMcp().flatMap { it.tools }

    /**
     * Checks a user's password like the app's login and leaves no session behind, for module pages
     * that sign users in themselves (OAuth authorization of AI agents). Without [companyCode] it
     * signs in a server owner, who then acts in the server workspace. Null when rejected or when a
     * server owner has no server workspace. Callers must rate limit attempts.
     */
    fun verifyCredentials(companyCode: String?, username: String, password: String): VerifiedUser? {
        val session = login(LoginCredentials(username, password, companyCode)) ?: return null
        val serverOwner = session.scope == "server"
        val tokenHash = tokenGenerator.hash(session.accessToken)
        val revoke = "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ?"
        if (serverOwner) {
            central.connection.use { connection -> connection.prepareStatement(revoke).use { it.setString(1, tokenHash); it.executeUpdate() } }
        } else {
            tenantConnectionForCompany(companyCode!!)?.use { connection -> connection.prepareStatement(revoke).use { it.setString(1, tokenHash); it.executeUpdate() } }
        }
        val workspace = if (serverOwner) session.workspaceCompanyCode ?: return null else companyCode!!
        return VerifiedUser(UUID.fromString(session.userId), session.username, workspace, serverOwner)
    }

    /**
     * Module access of a user for requests authenticated without a session, such as an AI agent token
     * stored in the tenant. Same rules as [permissions]: an inactive user (or a server owner that no
     * longer is one) gets null, and permissions are read now, so revoking one applies to the next call.
     */
    fun userAccess(tenant: Connection, companyCode: String, userId: UUID, serverOwner: Boolean): UserModuleAccess? {
        val enabled = enabledModules(tenant)
        if (serverOwner) {
            if (companyCode != ServerWorkspace.Code || !isServerWorkspace(companyCode) || !isActiveServerOwner(userId)) return null
            return UserModuleAccess(companyCode, userId, businessOwner = true, serverOwner = true, granted = emptySet(), enabled, moduleInstalled)
        }
        val businessOwner = tenant.prepareStatement(
            """
            SELECT EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
            FROM application_users users
            WHERE users.id = ? AND users.is_active = TRUE
            """.trimIndent()
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getBoolean(1) else null }
        } ?: return null
        val granted = if (businessOwner) emptySet() else tenant.prepareStatement(
            "SELECT permission_id FROM application_user_permissions WHERE user_id = ?"
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        return UserModuleAccess(companyCode, userId, businessOwner, serverOwner = false, granted, enabled, moduleInstalled)
    }

    /**
     * A server owner acting in any active company of the server, with every permission there, like
     * in the server workspace (their AI agent manages the companies of the server). Null when the user
     * is no longer an active server owner or the company is not active. The caller closes the connection.
     */
    fun serverOwnerAccess(companyCode: String, userId: UUID): Pair<Connection, UserModuleAccess>? {
        if (!isActiveServerOwner(userId)) return null
        val tenant = tenantConnectionForCompany(companyCode) ?: return null
        val enabled = try {
            enabledModules(tenant)
        } catch (failure: SQLException) {
            tenant.close()
            throw failure
        }
        return tenant to UserModuleAccess(companyCode, userId, businessOwner = true, serverOwner = true, granted = emptySet(), enabled, moduleInstalled)
    }

    private fun enabledModules(tenant: Connection): Set<String> =
        tenant.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }

    /**
     * Name and email of the user behind [permissions], to record who made a change: a server owner
     * from the central database, anyone else from [tenant]. Users created before names were required
     * may lack them until they sign in again; the name then falls back to the username.
     */
    fun userIdentity(tenant: Connection, permissions: ModulePermissions): UserIdentity? {
        val userId = permissions.userId ?: return null
        fun read(connection: Connection) = connection.prepareStatement(
            "SELECT postgres_role, display_name, email FROM application_users WHERE id = ?"
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows ->
                if (rows.next()) UserIdentity(userId, rows.getString(2)?.takeIf { it.isNotBlank() } ?: rows.getString(1), rows.getString(3), rows.getString(1)) else null
            }
        }
        return try {
            if (permissions.serverOwner) central.connection.use(::read) else read(tenant)
        } catch (_: SQLException) {
            // Before core migration V003 the profile columns do not exist yet.
            null
        }
    }

    /**
     * A customer's history from every installed module that is enabled for the company and whose
     * permission the user holds, newest first. A module that fails is left out (and logged), so one
     * module never hides the others' history.
     */
    fun customerHistory(tenant: Connection, permissions: ModulePermissions, customerId: UUID, limit: Int = 100): List<CustomerHistoryEntry> {
        val enabled = enabledModules(tenant)
        return customerTimelines()
            .filter { it.moduleId in enabled && isInstalled(it.moduleId) && permissions.has(it.timeline.permission) }
            .flatMap { registered ->
                try {
                    registered.timeline.entries(CustomerTimelineQuery(customerId, tenant, permissions, this, limit)).take(limit).map { registered to it }
                } catch (failure: Exception) {
                    log.warn("Customer history of module {} failed: {}", registered.moduleId, failure.message)
                    emptyList()
                }
            }
            .sortedByDescending { it.second.at }
            .take(limit)
            .map { (registered, entry) ->
                CustomerHistoryEntry(entry.at.toString(), registered.moduleId, registered.moduleName, entry.kind, entry.title, entry.detail, entry.author)
            }
    }

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

    private fun isActiveServerOwner(userId: UUID): Boolean = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT 1
            FROM server_owners owners
            JOIN application_users users ON users.id = owners.user_id
            WHERE owners.user_id = ? AND users.is_active = TRUE
            """.trimIndent()
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> rows.next() }
        }
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
    // A server owner (a central user, not in the tenant's users): in the server workspace, or in any
    // company through their AI agent (ModuleAccessService.serverOwnerAccess).
    val serverOwner: Boolean = false,
) {
    fun has(permissionId: String): Boolean = businessOwner || permissionId in granted
}

// Who a user is, for activity logs: [name] is the display name (or the username when missing).
data class UserIdentity(val userId: UUID, val name: String, val email: String?, val username: String)

// A user whose password was checked: a tenant user, or a server owner acting in the server workspace.
data class VerifiedUser(val userId: UUID, val username: String, val companyCode: String, val serverOwner: Boolean)

/** What a user may do in every module of a company, loaded once per request (see ModuleAccessService.userAccess). */
class UserModuleAccess internal constructor(
    val companyCode: String,
    val userId: UUID,
    val businessOwner: Boolean,
    val serverOwner: Boolean,
    private val granted: Set<String>,
    private val enabledModules: Set<String>,
    private val installed: (String) -> Boolean,
) {
    // The user's permissions in the module, or null when the module is not installed or not enabled.
    // The server's own tools (ServerMcpModuleId) belong to server owners only.
    fun permissions(moduleId: String): ModulePermissions? {
        val usable = if (moduleId == ServerMcpModuleId) serverOwner else moduleId in enabledModules && installed(moduleId)
        return if (usable) ModulePermissions(companyCode, accessToken = "", businessOwner, granted, userId, serverOwner) else null
    }
    // Whether the user can use the tool somewhere: a server owner in every company of the server
    // (ModuleAccessService.serverOwnerAccess), anyone else where the module is enabled and permitted.
    fun mayUse(tool: RegisteredMcpTool): Boolean = serverOwner || permissions(tool.moduleId)?.has(tool.tool.permission) == true
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
