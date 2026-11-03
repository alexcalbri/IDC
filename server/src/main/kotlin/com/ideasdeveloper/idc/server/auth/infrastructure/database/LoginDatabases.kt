package com.ideasdeveloper.idc.server.auth.infrastructure.database

import com.ideasdeveloper.idc.server.auth.application.AuthenticationService
import com.ideasdeveloper.idc.server.auth.application.SessionService
import com.ideasdeveloper.idc.server.auth.application.ScopedLoginService
import com.ideasdeveloper.idc.server.auth.application.UserProfileRules
import com.ideasdeveloper.idc.server.auth.domain.ApplicationUser
import com.ideasdeveloper.idc.server.auth.domain.LoginCredentials
import com.ideasdeveloper.idc.server.auth.domain.LoginScope
import com.ideasdeveloper.idc.server.auth.domain.LoginSuccessResponse
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

    val service = ScopedLoginService(
        serverScope = scope(central, centralDatabase, centralUrl, true),
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

        // The business owner behind a valid session of this company.
        fun ownerBySession(token: String): ApplicationUser? = sessions.validateSession(token)?.takeIf { !server && isOwner(it) }

        // Opens a session for [user] and describes it as the app expects after signing in.
        fun respond(user: ApplicationUser, companyCode: String?): LoginSuccessResponse? {
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
                profileComplete = profile == null || UserProfileRules.isComplete(profile.first, profile.second))
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
