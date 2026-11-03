package com.ideasdeveloper.idc.server.auth.infrastructure.database

import com.ideasdeveloper.idc.server.auth.application.AuthenticationService
import com.ideasdeveloper.idc.server.auth.application.SessionService
import com.ideasdeveloper.idc.server.auth.application.ScopedLoginService
import com.ideasdeveloper.idc.server.auth.application.UserProfileRules
import com.ideasdeveloper.idc.server.auth.domain.ApplicationUser
import com.ideasdeveloper.idc.server.auth.domain.LoginCredentials
import com.ideasdeveloper.idc.server.auth.domain.LoginScope
import com.ideasdeveloper.idc.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.idc.server.auth.domain.SwitchTarget
import com.ideasdeveloper.idc.server.auth.domain.SwitchTargetsResponse
import com.ideasdeveloper.idc.server.auth.infrastructure.security.PostgresCredentialVerifier
import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/** Credentials come only from server configuration, never from the client. */
@Serializable
class TenantDatabaseConfig(val databaseName: String, val jdbcUrl: String, val user: String, val password: String)

class LoginDatabases(
    private val central: DataSource,
    centralDatabase: Database,
    centralUrl: String,
    tenantConfiguration: String,
    private val runtimeUser: String,
    private val runtimePassword: String,
    private val tenantJdbcUrlPrefix: String,
    private val lifetimeSeconds: Long,
) : AutoCloseable {
    private val configurations = Json.decodeFromString<List<TenantDatabaseConfig>>(tenantConfiguration)
        .also { entries -> require(entries.map { it.databaseName }.distinct().size == entries.size) }
        .associateBy { it.databaseName }
        .toMutableMap()
    private val pools = mutableMapOf<String, HikariDataSource>()
    private val scopes = mutableMapOf<String, Scope>()
    private val tenantDatabaseNamePattern = Regex("[a-z][a-z0-9_]{0,62}")

    private val serverScope = scope(central, centralDatabase, centralUrl, true)
    private val tokens = SessionTokenGenerator()

    val service = ScopedLoginService(
        serverScope = serverScope,
        findActiveDatabase = { code ->
            central.connection.use { connection ->
                connection.prepareStatement("SELECT database_name FROM companies WHERE code = ? AND is_active = TRUE").use { query ->
                    query.setString(1, code)
                    query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }
        },
        companyScope = { name -> tenantScope(name) },
    )

    @Synchronized
    fun tenantConfig(databaseName: String): TenantDatabaseConfig? = configurations[databaseName]
        ?: databaseName.takeIf { it.matches(tenantDatabaseNamePattern) }?.let { inferredTenantConfig(it) }

    @Synchronized
    fun registerTenant(config: TenantDatabaseConfig) {
        configurations[config.databaseName] = config
        scopes.remove(config.databaseName)
        pools.remove(config.databaseName)?.close()
    }

    @Synchronized
    fun unregisterTenant(databaseName: String) {
        configurations.remove(databaseName)
        scopes.remove(databaseName)
        pools.remove(databaseName)?.close()
    }

    /**
     * Signs the business owner of [fromCode] (session [token]) into [toCode] as the business owner
     * of [toCode], without a password, when both companies are active and share the owner group
     * (control V008). Only the server owner puts companies in a group (duplicating with the same
     * owner or linking them), so the group says both owners are the same person even when each
     * company has its own user. Null when any of that does not hold.
     */
    fun switchCompany(fromCode: String, token: String, toCode: String): LoginSuccessResponse? {
        if (fromCode == toCode) return null
        val databases = central.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT target.database_name, origin.database_name
                FROM companies origin
                JOIN companies target ON target.owner_group = origin.owner_group
                WHERE origin.code = ? AND target.code = ?
                  AND origin.is_active AND target.is_active
                  AND NOT origin.is_server_workspace AND NOT target.is_server_workspace
                """.trimIndent()
            ).use { query ->
                query.setString(1, fromCode)
                query.setString(2, toCode)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
            }
        } ?: return null
        val origin = tenantScope(databases.second) ?: return null
        origin.ownerBySession(token) ?: return null
        val target = tenantScope(databases.first) ?: return null
        val targetOwner = target.businessOwner()?.takeIf { it.isActive } ?: return null
        return target.respond(targetOwner, toCode)
    }

    // Who is behind a session for the company switcher.
    private sealed interface Switcher {
        // A server owner: on the server ([fromCode] null) or inside a company they entered.
        class ServerOwner(val user: ApplicationUser) : Switcher
        class BusinessOwner : Switcher
    }

    private fun switcher(fromCode: String?, token: String): Switcher? {
        if (fromCode == null) return serverScope.sessionOwner(token)?.let { Switcher.ServerOwner(it) }
        val scope = activeDatabase(fromCode)?.let { tenantScope(it) } ?: return null
        scope.sessionOwner(token) ?: return null
        val serverOwnerId = central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT server_owner_user_id FROM server_owner_company_sessions WHERE token_hash = ? AND company_code = ? AND expires_at > CURRENT_TIMESTAMP"
            ).use { query ->
                query.setString(1, tokens.hash(token))
                query.setString(2, fromCode)
                query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, java.util.UUID::class.java) else null }
            }
        } ?: return Switcher.BusinessOwner()
        val user = serverScope.users.findById(kotlin.uuid.Uuid.parse(serverOwnerId.toString()))
            ?.takeIf { it.isActive && serverScope.isOwner(it) } ?: return null
        return Switcher.ServerOwner(user)
    }

    /**
     * Where the session can move from [fromCode] (null: the server). A server owner may go to the
     * server ([SwitchTargetsResponse.serverName] set) and to any active company; a business owner
     * to the companies of its owner group. Null when the session cannot switch.
     */
    fun switchTargets(fromCode: String?, token: String): SwitchTargetsResponse? = when (switcher(fromCode, token)) {
        null -> null
        is Switcher.ServerOwner -> SwitchTargetsResponse(serverName(), fromCode, companies(null))
        is Switcher.BusinessOwner -> SwitchTargetsResponse(null, fromCode, companies(fromCode))
    }

    /**
     * Moves the session to [toCode] (null: back to the server, server owners only) and returns the
     * new session. A server owner enters a company as its business owner user: the session is
     * recorded in server_owner_company_sessions and audited, and the session it leaves is closed.
     */
    fun switchTo(fromCode: String?, token: String, toCode: String?): LoginSuccessResponse? {
        if (fromCode == toCode) return null
        return when (val who = switcher(fromCode, token)) {
            null -> null
            is Switcher.BusinessOwner -> toCode?.let { switchCompany(fromCode!!, token, it) }
            is Switcher.ServerOwner -> {
                val response = if (toCode == null) {
                    serverScope.respond(who.user, null)
                } else {
                    val target = activeDatabase(toCode)?.let { tenantScope(it) } ?: return null
                    val owner = target.businessOwner()?.takeIf { it.isActive } ?: return null
                    target.respond(owner, toCode, serverOwnerAccess = true)?.also { opened ->
                        central.connection.use { connection ->
                            connection.prepareStatement(
                                """
                                INSERT INTO server_owner_company_sessions (token_hash, server_owner_user_id, company_code, expires_at)
                                VALUES (?, ?, ?, CURRENT_TIMESTAMP + make_interval(secs => ?))
                                """.trimIndent()
                            ).use { insert ->
                                insert.setString(1, tokens.hash(opened.accessToken))
                                insert.setObject(2, java.util.UUID.fromString(who.user.id.toString()))
                                insert.setString(3, toCode)
                                insert.setLong(4, opened.expiresInSeconds)
                                insert.executeUpdate()
                            }
                            connection.prepareStatement(
                                """
                                INSERT INTO provisioning_audit_log (id, actor_role, action, company_code, module_id, details, actor_name, actor_email)
                                SELECT ?, users.postgres_role, 'server_owner.entered_company', ?, NULL, CAST(? AS jsonb), users.display_name, users.email
                                FROM application_users users WHERE users.id = ?
                                """.trimIndent()
                            ).use { insert ->
                                insert.setObject(1, java.util.UUID.randomUUID())
                                insert.setString(2, toCode)
                                insert.setString(3, """{"businessOwnerUsername":"${owner.postgresRole}"}""")
                                insert.setObject(4, java.util.UUID.fromString(who.user.id.toString()))
                                insert.executeUpdate()
                            }
                            connection.prepareStatement("DELETE FROM server_owner_company_sessions WHERE expires_at <= CURRENT_TIMESTAMP").use { it.executeUpdate() }
                        }
                    }
                } ?: return null
                // Leaving a company: that session is not needed any more.
                if (fromCode != null) {
                    activeDatabase(fromCode)?.let { tenantScope(it) }?.revoke(token)
                    central.connection.use { connection ->
                        connection.prepareStatement("DELETE FROM server_owner_company_sessions WHERE token_hash = ?").use {
                            it.setString(1, tokens.hash(token))
                            it.executeUpdate()
                        }
                    }
                }
                response
            }
        }
    }

    // Active companies (no server workspace): all of them, or those of the owner group of [groupOf].
    private fun companies(groupOf: String?): List<SwitchTarget> = central.connection.use { connection ->
        val filter = if (groupOf == null) "" else "AND owner_group = (SELECT owner_group FROM companies WHERE code = ?)"
        connection.prepareStatement("SELECT code, name FROM companies WHERE is_active AND NOT is_server_workspace $filter ORDER BY name, code").use { query ->
            groupOf?.let { query.setString(1, it) }
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(SwitchTarget(rows.getString(1), rows.getString(2))) } }
        }
    }

    private fun activeDatabase(code: String): String? = central.connection.use { connection ->
        connection.prepareStatement("SELECT database_name FROM companies WHERE code = ? AND is_active AND NOT is_server_workspace").use { query ->
            query.setString(1, code)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    // Name the server owner gave the server (Empresas > Nombre del servidor), or the default.
    fun serverName(): String = try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
                query.setString(1, ServerNameSetting)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }
    } catch (_: java.sql.SQLException) {
        null
    }?.takeIf { it.isNotBlank() } ?: DefaultServerName

    @Synchronized
    private fun tenantScope(name: String): Scope? {
        scopes[name]?.let { return it }
        val config = configurations[name]
            ?: name.takeIf { it.matches(tenantDatabaseNamePattern) }?.let { inferredTenantConfig(it) }
            ?: return null
        configurations[name] = config
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = 3
            minimumIdle = 0
            connectionTimeout = 5000
            connectionInitSql = "SET search_path TO public"
        })
        try {
            val result = scope(pool, Database.connect(pool), config.jdbcUrl, false)
            pools[name] = pool
            scopes[name] = result
            return result
        } catch (failure: Exception) {
            pool.close()
            throw failure
        }
    }

    private fun inferredTenantConfig(databaseName: String): TenantDatabaseConfig = TenantDatabaseConfig(
        databaseName = databaseName,
        jdbcUrl = tenantJdbcUrlPrefix + databaseName,
        user = runtimeUser,
        password = runtimePassword,
    )

    private fun scope(source: DataSource, database: Database, url: String, server: Boolean) = Scope(source, database, url, server)

    /** Sign-in of one database: the server (server owners) or a company. */
    private inner class Scope(private val source: DataSource, database: Database, url: String, private val server: Boolean) : LoginScope {
        val users = ApplicationUserRepository(database)
        private val authentication = AuthenticationService(PostgresCredentialVerifier(url), users)
        private val sessions = SessionService(ApplicationSessionRepository(database), users, SessionTokenGenerator(), lifetimeSeconds)

        override fun login(credentials: LoginCredentials): LoginSuccessResponse? {
            val user = authentication.authenticate(credentials) ?: return null
            return respond(user, credentials.companyCode)
        }

        fun isOwner(user: ApplicationUser): Boolean = source.connection.use { connection ->
            val table = if (server) "server_owners" else "business_owner"
            connection.prepareStatement("SELECT 1 FROM $table WHERE user_id = ?").use { query ->
                query.setObject(1, java.util.UUID.fromString(user.id.toString()))
                query.executeQuery().use { it.next() }
            }
        }

        // The business owner user of this company.
        fun businessOwner(): ApplicationUser? {
            val role = source.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT users.postgres_role FROM business_owner owner JOIN application_users users ON users.id = owner.user_id LIMIT 1"
                ).use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null } }
            } ?: return null
            return users.findByPostgresRole(role)
        }

        // The owner (server owner or business owner) behind a valid session of this database.
        fun sessionOwner(token: String): ApplicationUser? = sessions.validateSession(token)?.takeIf { isOwner(it) }

        fun ownerBySession(token: String): ApplicationUser? = sessionOwner(token)?.takeIf { !server }

        fun revoke(token: String) {
            sessions.revokeSession(token)
        }

        // Opens a session for [user] and describes it as the app expects after signing in.
        fun respond(user: ApplicationUser, companyCode: String?, serverOwnerAccess: Boolean = false): LoginSuccessResponse? {
            val owner = isOwner(user)
            if (server && !owner) return null
            val session = sessions.createSession(user)
            val workspace = if (server) serverWorkspace() else null
            val profile = profile(source, java.util.UUID.fromString(user.id.toString()))
            return LoginSuccessResponse(session.userId, session.username, session.accessToken, session.expiresInSeconds,
                scope = if (server) "server" else "company",
                companyCode = if (server) null else companyCode,
                role = if (server) "server_owner" else if (owner) "business_owner" else "user",
                enabledModules = if (server) workspace?.second.orEmpty() else visibleModules(source, java.util.UUID.fromString(user.id.toString()), owner),
                workspaceCompanyCode = workspace?.first,
                displayName = profile?.first,
                email = profile?.second,
                profileComplete = profile == null || UserProfileRules.isComplete(profile.first, profile.second),
                serverName = if (server) serverName() else null,
                serverOwnerAccess = serverOwnerAccess)
        }
    }

    // Name and email of the user, or null before core migration V003 (sign-in then does not ask for them).
    private fun profile(source: DataSource, userId: java.util.UUID): Pair<String?, String?>? = try {
        source.connection.use { connection ->
            connection.prepareStatement("SELECT display_name, email FROM application_users WHERE id = ?").use { query ->
                query.setObject(1, userId)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
            }
        }
    } catch (_: java.sql.SQLException) {
        null
    }

    // Code and enabled modules of the server workspace, or null when it has not been created.
    private fun serverWorkspace(): Pair<String, List<String>>? = try {
        val (code, databaseName) = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies WHERE is_server_workspace AND is_active").use { query ->
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
            }
        } ?: return null
        val tenant = tenantConfig(databaseName) ?: return null
        val modules = org.postgresql.ds.PGSimpleDataSource().apply {
            setURL(tenant.jdbcUrl)
            user = tenant.user
            password = tenant.password
        }.connection.use { connection ->
            connection.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled' ORDER BY module_id").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        code to modules
    } catch (_: java.sql.SQLException) {
        // Before control migration V006, or if the workspace database is unavailable, sign-in still works.
        null
    }

    private fun visibleModules(source: DataSource, userId: java.util.UUID, owner: Boolean): List<String> {
        return source.connection.use { connection ->
            val sql = if (owner) {
                "SELECT module_id FROM tenant_modules WHERE status = 'enabled' ORDER BY module_id"
            } else {
                """
                SELECT modules.module_id
                FROM tenant_modules modules
                WHERE modules.status = 'enabled'
                  AND EXISTS (
                      SELECT 1
                      FROM application_user_permissions user_permissions
                      WHERE user_permissions.user_id = ?
                        AND user_permissions.permission_id = modules.module_id || '.view'
                  )
                ORDER BY modules.module_id
                """.trimIndent()
            }
            connection.prepareStatement(sql).use { query ->
                if (!owner) query.setObject(1, userId)
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString(1))
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        pools.values.forEach { it.close() }
        pools.clear()
        scopes.clear()
    }
}

const val ServerNameSetting = "server.name"
const val DefaultServerName = "Servidor"
