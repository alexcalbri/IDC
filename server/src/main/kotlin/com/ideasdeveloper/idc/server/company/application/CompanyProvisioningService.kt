package com.ideasdeveloper.idc.server.company.application

import com.ideasdeveloper.idc.server.auth.infrastructure.database.TenantDatabaseConfig
import com.ideasdeveloper.idc.server.company.api.CreateCompanyRequest
import com.ideasdeveloper.idc.server.company.api.CreateCompanyResponse
import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.company.api.CompanyModuleResponse
import com.ideasdeveloper.idc.server.company.api.CompanySummaryResponse
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleRegistry
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.io.path.name

class CompanyProvisioningException(message: String) : Exception(message)

class CompanyProvisioningService(
    private val central: DataSource,
    private val config: CompanyProvisioningConfig,
    private val loginDatabases: LoginDatabases,
    private val moduleRegistry: ModuleRegistry,
) {
    private val companyCodePattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val postgresRolePattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val colorPattern = Regex("#[0-9A-Fa-f]{6}")
    private val modules: List<ModuleDefinition> = moduleRegistry.definitions

    fun createCompany(request: CreateCompanyRequest, provisioningRole: String): CreateCompanyResponse {
        val code = request.code.trim()
        val companyName = request.name.trim()
        val ownerUsername = request.businessOwnerUsername.trim()
        val ownerPassword = request.businessOwnerPassword
        val databaseName = "idc_$code"
        val companyId = UUID.randomUUID()

        validate(code, companyName, ownerUsername, ownerPassword, request)

        central.connection.use { connection ->
            if (connection.exists("companies", "code", code)) {
                throw CompanyProvisioningException("Ya existe una empresa con ese codigo.")
            }
            if (connection.exists("companies", "database_name", databaseName)) {
                throw CompanyProvisioningException("Ya existe una base de datos registrada para ese codigo.")
            }
        }

        adminConnection(provisioningRole).use { admin ->
            admin.autoCommit = true
            if (admin.existsDatabase(databaseName)) {
                throw CompanyProvisioningException("La base de datos de la empresa ya existe.")
            }
            if (admin.existsRole(ownerUsername)) {
                throw CompanyProvisioningException("El usuario PostgreSQL del business owner ya existe.")
            }

            admin.createStatement().use { statement ->
                statement.executeUpdate("CREATE DATABASE ${quoteIdentifier(databaseName)}")
                statement.executeUpdate(
                    "CREATE ROLE ${quoteIdentifier(ownerUsername)} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD ${quoteLiteral(ownerPassword)}"
                )
                statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(config.runtimeUser)}")
                statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(ownerUsername)}")
            }
        }

        val tenantConfig = TenantDatabaseConfig(
            databaseName = databaseName,
            jdbcUrl = config.tenantJdbcUrlPrefix + databaseName,
            user = config.runtimeUser,
            password = config.runtimePassword,
        )

        tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
            applyTenantCoreMigrations(tenant)
            applyBaseModuleMigrations(tenant)
            seedBusinessOwner(tenant, ownerUsername)
            grantTenantPermissions(tenant, ownerUsername)
        }

        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                insertCompany(connection, companyId, request, databaseName)
                insertAudit(
                    connection = connection,
                    actorRole = provisioningRole,
                    action = "company.created",
                    companyCode = code,
                    moduleId = null,
                    details = """{"databaseName":"$databaseName","businessOwnerUsername":"$ownerUsername"}""",
                )
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }

        loginDatabases.registerTenant(tenantConfig)

        return CreateCompanyResponse(
            id = companyId.toString(),
            code = code,
            name = companyName,
            databaseName = databaseName,
            businessOwnerUsername = ownerUsername,
            enabledModules = listOf("clientes"),
        )
    }

    fun listCompanies(): List<CompanySummaryResponse> {
        val companies = central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT code, name, database_name, is_active FROM companies ORDER BY code"
            ).use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                CompanyRow(
                                    code = rows.getString("code"),
                                    name = rows.getString("name"),
                                    databaseName = rows.getString("database_name"),
                                    isActive = rows.getBoolean("is_active"),
                                )
                            )
                        }
                    }
                }
            }
        }

        return companies.map { company ->
            CompanySummaryResponse(
                code = company.code,
                name = company.name,
                databaseName = company.databaseName,
                isActive = company.isActive,
                modules = loadCompanyModules(company.databaseName),
            )
        }
    }

    fun setModuleEnabled(companyCode: String, moduleId: String, enabled: Boolean, provisioningRole: String): CompanySummaryResponse {
        val normalizedCompanyCode = companyCode.trim()
        val normalizedModuleId = moduleId.trim()
        val definition = modules.singleOrNull { it.id == normalizedModuleId }
            ?: throw CompanyProvisioningException("Modulo no reconocido o no instalado en el servidor.")
        if (definition.locked && !enabled) {
            throw CompanyProvisioningException("El modulo ${definition.displayName} es base y no puede deshabilitarse.")
        }

        val company = central.connection.use { connection ->
            findCompany(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException("Empresa no encontrada.")
        }
        val tenantConfig = loginDatabases.tenantConfig(company.databaseName)
            ?: throw CompanyProvisioningException("La empresa no tiene conexion tenant configurada.")

        tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
            applyModuleMigrations(tenant, definition)
            tenant.autoCommit = false
            try {
                tenant.prepareStatement(
                    """
                    INSERT INTO tenant_modules (module_id, display_name, status, enabled_at)
                    VALUES (?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                    ON CONFLICT (module_id)
                    DO UPDATE SET
                        display_name = EXCLUDED.display_name,
                        status = EXCLUDED.status,
                        enabled_at = CASE
                            WHEN EXCLUDED.status = 'enabled' THEN COALESCE(tenant_modules.enabled_at, CURRENT_TIMESTAMP)
                            ELSE NULL
                        END
                    """.trimIndent()
                ).use { upsert ->
                    upsert.setString(1, definition.id)
                    upsert.setString(2, definition.displayName)
                    upsert.setString(3, if (enabled) "enabled" else "disabled")
                    upsert.setBoolean(4, enabled)
                    upsert.executeUpdate()
                }
                tenant.commit()
            } catch (failure: Exception) {
                tenant.rollback()
                throw failure
            } finally {
                tenant.autoCommit = true
            }
        }

        central.connection.use { connection ->
            insertAudit(
                connection = connection,
                actorRole = provisioningRole,
                action = if (enabled) "module.enabled" else "module.disabled",
                companyCode = normalizedCompanyCode,
                moduleId = definition.id,
                details = """{"displayName":"${definition.displayName}"}""",
            )
        }

        return CompanySummaryResponse(
            code = company.code,
            name = company.name,
            databaseName = company.databaseName,
            isActive = company.isActive,
            modules = loadCompanyModules(company.databaseName),
        )
    }

    private fun validate(
        code: String,
        companyName: String,
        ownerUsername: String,
        ownerPassword: String,
        request: CreateCompanyRequest,
    ) {
        if (!companyCodePattern.matches(code)) {
            throw CompanyProvisioningException("El codigo debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo.")
        }
        if (companyName.isBlank()) {
            throw CompanyProvisioningException("El nombre de la empresa es obligatorio.")
        }
        if (!postgresRolePattern.matches(ownerUsername)) {
            throw CompanyProvisioningException("El usuario debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo.")
        }
        if (ownerPassword.length < 12) {
            throw CompanyProvisioningException("La contrasena del business owner debe tener al menos 12 caracteres.")
        }
        if (!colorPattern.matches(request.primaryColor) ||
            !colorPattern.matches(request.secondaryColor) ||
            !colorPattern.matches(request.accentColor)
        ) {
            throw CompanyProvisioningException("Los colores deben usar formato hexadecimal #RRGGBB.")
        }
    }

    private fun adminConnection(provisioningRole: String): Connection {
        val source = PGSimpleDataSource().apply {
            setURL(config.administrationJdbcUrl)
            setUser(config.runtimeUser)
            setPassword(config.runtimePassword)
        }
        return source.connection.apply {
            setProvisioningRole(provisioningRole)
        }
    }

    private fun tenantAdminConnection(jdbcUrl: String, provisioningRole: String): Connection {
        val source = PGSimpleDataSource().apply {
            setURL(jdbcUrl)
            setUser(config.runtimeUser)
            setPassword(config.runtimePassword)
        }
        return source.connection.apply {
            setProvisioningRole(provisioningRole)
        }
    }

    private fun applyTenantCoreMigrations(connection: Connection) {
        listOf(
            "database/core/migrations/V001__create_application_users.sql" to "core",
            "database/core/migrations/V002__create_application_sessions.sql" to "core",
            "database/tenant/migrations/V001__create_business_owner.sql" to "tenant",
            "database/tenant/migrations/V003__create_tenant_modules.sql" to "tenant",
        ).forEach { (relativePath, module) ->
            applyMigration(connection, module, Path.of(config.migrationsRoot).resolve(relativePath))
        }
    }

    private fun applyBaseModuleMigrations(connection: Connection) {
        moduleRegistry.definitions
            .filter { it.locked }
            .forEach { definition -> applyModuleMigrations(connection, definition) }
    }

    private fun applyModuleMigrations(connection: Connection, definition: ModuleDefinition) {
        val module = moduleRegistry.module(definition.id) ?: return
        module.migrationPaths.forEach { relativePath ->
            applyMigration(connection, module.migrationModule, Path.of(config.migrationsRoot).resolve(relativePath))
        }
    }

    private fun applyMigration(connection: Connection, module: String, path: Path) {
        val sql = Files.readString(path).trim()
        require(sql.startsWith("BEGIN;") && sql.endsWith("COMMIT;")) {
            "Migration $path must have outer BEGIN/COMMIT"
        }
        val version = path.name.removeSuffix(".sql")
        val checksum = sha256(sql)
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        module TEXT NOT NULL,
                        version TEXT NOT NULL,
                        checksum TEXT NOT NULL,
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (module, version)
                    )
                    """.trimIndent()
                )
            }
            connection.prepareStatement("SELECT checksum FROM schema_migrations WHERE module = ? AND version = ?").use { query ->
                query.setString(1, module)
                query.setString(2, version)
                query.executeQuery().use { rows ->
                    if (rows.next()) {
                        if (rows.getString(1) != checksum) {
                            throw CompanyProvisioningException("La migracion $version ya fue aplicada con otro checksum.")
                        }
                        connection.commit()
                        return
                    }
                }
            }
            val migrationSql = sql.removePrefix("BEGIN;").removeSuffix("COMMIT;").trim()
            connection.createStatement().use { statement ->
                statement.execute(migrationSql)
            }
            connection.prepareStatement("INSERT INTO schema_migrations (module, version, checksum) VALUES (?, ?, ?)").use { insert ->
                insert.setString(1, module)
                insert.setString(2, version)
                insert.setString(3, checksum)
                insert.executeUpdate()
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun seedBusinessOwner(connection: Connection, ownerUsername: String) {
        val userId = UUID.randomUUID()
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT INTO application_users (id, postgres_role, is_active) VALUES (?, ?, TRUE)").use { insert ->
                insert.setObject(1, userId)
                insert.setString(2, ownerUsername)
                insert.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO business_owner (user_id) VALUES (?)").use { insert ->
                insert.setObject(1, userId)
                insert.executeUpdate()
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun grantTenantPermissions(connection: Connection, ownerUsername: String) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
            statement.executeUpdate("GRANT USAGE ON SCHEMA public TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT USAGE ON SCHEMA public TO ${quoteIdentifier(ownerUsername)}")
            statement.executeUpdate("GRANT SELECT ON application_users, business_owner, tenant_modules TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON application_sessions TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON customers, customer_field_definitions TO ${quoteIdentifier(config.runtimeUser)}")
        }
    }

    private fun loadCompanyModules(databaseName: String): List<CompanyModuleResponse> {
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return modules.map { definition ->
            definition.toCompanyModuleResponse(enabled = definition.locked)
        }

        val states = mutableMapOf<String, Boolean>()
        tenantRuntimeConnection(tenantConfig).use { connection ->
            connection.prepareStatement("SELECT module_id, status FROM tenant_modules").use { query ->
                query.executeQuery().use { rows ->
                    while (rows.next()) {
                        states[rows.getString("module_id")] = rows.getString("status") == "enabled"
                    }
                }
            }
        }
        return modules.map { definition ->
            definition.toCompanyModuleResponse(enabled = if (definition.locked) true else states[definition.id] == true)
        }
    }

    private fun tenantRuntimeConnection(tenant: TenantDatabaseConfig): Connection {
        val source = PGSimpleDataSource().apply {
            setURL(tenant.jdbcUrl)
            setUser(tenant.user)
            setPassword(tenant.password)
        }
        return source.connection
    }

    private fun insertCompany(connection: Connection, companyId: UUID, request: CreateCompanyRequest, databaseName: String) {
        connection.prepareStatement(
            """
            INSERT INTO companies (
                id, code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, companyId)
            insert.setString(2, request.code.trim())
            insert.setString(3, request.name.trim())
            insert.setString(4, request.logoUrl?.trim()?.takeIf { it.isNotBlank() })
            insert.setString(5, request.primaryColor)
            insert.setString(6, request.secondaryColor)
            insert.setString(7, request.accentColor)
            insert.setString(8, databaseName)
            insert.executeUpdate()
        }
    }

    private fun findCompany(connection: Connection, companyCode: String): CompanyRow? {
        connection.prepareStatement(
            "SELECT code, name, database_name, is_active FROM companies WHERE code = ? AND is_active = TRUE"
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows ->
                return if (rows.next()) {
                    CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        databaseName = rows.getString("database_name"),
                        isActive = rows.getBoolean("is_active"),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun insertAudit(
        connection: Connection,
        actorRole: String,
        action: String,
        companyCode: String?,
        moduleId: String?,
        details: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO provisioning_audit_log (id, actor_role, action, company_code, module_id, details)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, actorRole)
            insert.setString(3, action)
            insert.setString(4, companyCode)
            insert.setString(5, moduleId)
            insert.setString(6, details)
            insert.executeUpdate()
        }
    }

    private fun Connection.exists(table: String, column: String, value: String): Boolean {
        prepareStatement("SELECT 1 FROM $table WHERE $column = ?").use { query ->
            query.setString(1, value)
            query.executeQuery().use { rows -> return rows.next() }
        }
    }

    private fun Connection.existsDatabase(name: String): Boolean =
        prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { query ->
            query.setString(1, name)
            query.executeQuery().use { rows -> rows.next() }
        }

    private fun Connection.existsRole(name: String): Boolean =
        prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use { query ->
            query.setString(1, name)
            query.executeQuery().use { rows -> rows.next() }
        }

    private fun quoteIdentifier(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun quoteLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun Connection.setProvisioningRole(role: String) {
        createStatement().use { statement ->
            statement.execute("SET ROLE ${quoteIdentifier(role)}")
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(bytes)
    }

    private data class CompanyRow(
        val code: String,
        val name: String,
        val databaseName: String,
        val isActive: Boolean,
    )

    private fun ModuleDefinition.toCompanyModuleResponse(enabled: Boolean): CompanyModuleResponse =
        CompanyModuleResponse(
            moduleId = id,
            displayName = displayName,
            enabled = enabled,
            locked = locked,
        )
}
