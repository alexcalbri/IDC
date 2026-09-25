package com.ideasdeveloper.idc.server.auth.infrastructure.database

import com.ideasdeveloper.idc.server.auth.application.AuthenticationService
import com.ideasdeveloper.idc.server.auth.application.SessionService
import com.ideasdeveloper.idc.server.auth.application.ScopedLoginService
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
    private val lifetimeSeconds: Long,
) : AutoCloseable {
    private val configurations = Json.decodeFromString<List<TenantDatabaseConfig>>(tenantConfiguration)
        .also { entries -> require(entries.map { it.databaseName }.distinct().size == entries.size) }
        .associateBy { it.databaseName }
        .toMutableMap()
    private val pools = mutableMapOf<String, HikariDataSource>()
    private val scopes = mutableMapOf<String, LoginScope>()

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

    @Synchronized
    fun registerTenant(config: TenantDatabaseConfig) {
        configurations[config.databaseName] = config
        scopes.remove(config.databaseName)
        pools.remove(config.databaseName)?.close()
    }

    @Synchronized
    private fun tenantScope(name: String): LoginScope? {
        scopes[name]?.let { return it }
        val config = configurations[name] ?: return null
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

    private fun scope(source: DataSource, database: Database, url: String, server: Boolean): LoginScope {
        val users = ApplicationUserRepository(database)
        val authentication = AuthenticationService(PostgresCredentialVerifier(url), users)
        val sessions = SessionService(ApplicationSessionRepository(database), users, SessionTokenGenerator(), lifetimeSeconds)
        return LoginScope { credentials ->
            val user = authentication.authenticate(credentials) ?: return@LoginScope null
            val owner = source.connection.use { connection ->
                val table = if (server) "server_owners" else "business_owner"
                connection.prepareStatement("SELECT 1 FROM $table WHERE user_id = ?").use { query ->
                    query.setObject(1, java.util.UUID.fromString(user.id.toString()))
                    query.executeQuery().use { it.next() }
                }
            }
            if (server && !owner) return@LoginScope null
            val session = sessions.createSession(user)
            LoginSuccessResponse(session.userId, session.username, session.accessToken, session.expiresInSeconds,
                scope = if (server) "server" else "company",
                companyCode = if (server) null else credentials.companyCode,
                role = if (server) "server_owner" else if (owner) "business_owner" else "user",
                enabledModules = if (server) emptyList() else enabledModules(source))
        }
    }

    private fun enabledModules(source: DataSource): List<String> {
        return source.connection.use { connection ->
            connection.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled' ORDER BY module_id").use { query ->
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
