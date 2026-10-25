package com.ideasdeveloper.idc.server.company.application

import com.ideasdeveloper.idc.server.auth.infrastructure.database.TenantDatabaseConfig
import com.ideasdeveloper.idc.server.company.api.CreateCompanyRequest
import com.ideasdeveloper.idc.server.company.api.CreateCompanyResponse
import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.company.api.CompanyModuleResponse
import com.ideasdeveloper.idc.server.company.api.CompanySummaryResponse
import com.ideasdeveloper.idc.server.company.api.ServerModuleCatalogResponse
import com.ideasdeveloper.idc.server.company.api.ServerModuleResponse
import com.ideasdeveloper.idc.server.company.api.UpdateCompanyRequest
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleRegistry
import com.ideasdeveloper.idc.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.idc.server.modules.RemoteModulePackage
import com.ideasdeveloper.idc.ModuleVersion
import com.ideasdeveloper.idc.server.modules.GitHubLocation
import com.ideasdeveloper.idc.server.modules.ModuleBuildException
import com.ideasdeveloper.idc.server.modules.ModulePackageBuilder
import com.ideasdeveloper.idc.server.modules.ServerModule
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
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
    private val remoteModuleCatalog: RemoteModuleCatalog,
    private val defaultCatalogUrl: String,
    private val modulePackagesRoot: Path,
    // Null outside an installed distribution (e.g. :server:run), where modules cannot be built.
    private val modulePackageBuilder: ModulePackageBuilder?,
    private val requestRestart: () -> Unit,
) {
    private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)
    // Progress of optional modules being built for installation, shown in the module list.
    private val installStates = ConcurrentHashMap<String, ModuleInstallState>()
    // One build at a time: compiling uses about 1 GB of memory.
    private val buildExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "module-build").apply { isDaemon = true } }
    private val companyCodePattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val postgresRolePattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val colorPattern = Regex("#[0-9A-Fa-f]{6}")
    private val moduleCatalogSettingKey = "module.catalog.url"
    private val modules: List<ModuleDefinition>
        get() = moduleRegistry.definitions

    fun createCompany(request: CreateCompanyRequest, provisioningRole: String): CreateCompanyResponse {
        val code = request.code.trim()
        val companyName = request.name.trim()
        val ownerUsername = request.businessOwnerUsername.trim()
        val ownerPassword = request.businessOwnerPassword
        val databaseName = "idc_$code"
        val companyId = UUID.randomUUID()

        if (code == ServerWorkspace.Code) {
            throw CompanyProvisioningException("El codigo ${ServerWorkspace.Code} esta reservado para el espacio del servidor.")
        }
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

        val tenantConfig = tenantConfig(databaseName)

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
                "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE NOT is_server_workspace ORDER BY code"
            ).use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                CompanyRow(
                                    code = rows.getString("code"),
                                    name = rows.getString("name"),
                                    logoUrl = rows.getString("logo_url"),
                                    primaryColor = rows.getString("primary_color"),
                                    secondaryColor = rows.getString("secondary_color"),
                                    accentColor = rows.getString("accent_color"),
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
                logoUrl = company.logoUrl,
                primaryColor = company.primaryColor,
                secondaryColor = company.secondaryColor,
                accentColor = company.accentColor,
            )
        }
    }


    fun updateCompany(companyCode: String, request: UpdateCompanyRequest, provisioningRole: String): CompanySummaryResponse {
        val normalizedCompanyCode = companyCode.trim()
        rejectServerWorkspace(normalizedCompanyCode)
        val companyName = request.name.trim()
        val logoUrl = request.logoUrl?.trim()?.takeIf { it.isNotBlank() }
        if (companyName.isBlank()) {
            throw CompanyProvisioningException("El nombre de la empresa es obligatorio.")
        }
        if (!colorPattern.matches(request.primaryColor) ||
            !colorPattern.matches(request.secondaryColor) ||
            !colorPattern.matches(request.accentColor)
        ) {
            throw CompanyProvisioningException("Los colores deben usar formato hexadecimal #RRGGBB.")
        }

        val company = central.connection.use { connection ->
            findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException("Empresa no encontrada.")
        }

        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    UPDATE companies
                    SET name = ?, logo_url = ?, primary_color = ?, secondary_color = ?, accent_color = ?
                    WHERE code = ?
                    """.trimIndent()
                ).use { update ->
                    update.setString(1, companyName)
                    update.setString(2, logoUrl)
                    update.setString(3, request.primaryColor)
                    update.setString(4, request.secondaryColor)
                    update.setString(5, request.accentColor)
                    update.setString(6, normalizedCompanyCode)
                    if (update.executeUpdate() != 1) {
                        throw CompanyProvisioningException("No se pudo actualizar la empresa.")
                    }
                }
                insertAudit(
                    connection = connection,
                    actorRole = provisioningRole,
                    action = "company.updated",
                    companyCode = normalizedCompanyCode,
                    moduleId = null,
                    details = """{"previousName":"${company.name.escapeJson()}","name":"${companyName.escapeJson()}","databaseName":"${company.databaseName}"}""",
                )
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

        return CompanySummaryResponse(
            code = company.code,
            name = companyName,
            databaseName = company.databaseName,
            isActive = company.isActive,
            modules = loadCompanyModules(company.databaseName),
            logoUrl = logoUrl,
            primaryColor = request.primaryColor,
            secondaryColor = request.secondaryColor,
            accentColor = request.accentColor,
        )
    }


    fun setCompanyActive(companyCode: String, active: Boolean, provisioningRole: String): CompanySummaryResponse {
        val normalizedCompanyCode = companyCode.trim()
        rejectServerWorkspace(normalizedCompanyCode)
        val company = central.connection.use { connection ->
            findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException("Empresa no encontrada.")
        }

        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("UPDATE companies SET is_active = ? WHERE code = ?").use { update ->
                    update.setBoolean(1, active)
                    update.setString(2, normalizedCompanyCode)
                    update.executeUpdate()
                }
                insertAudit(
                    connection = connection,
                    actorRole = provisioningRole,
                    action = if (active) "company.activated" else "company.deactivated",
                    companyCode = normalizedCompanyCode,
                    moduleId = null,
                    details = """{"databaseName":"${company.databaseName}"}""",
                )
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

        if (active) {
            loginDatabases.registerTenant(tenantConfig(company.databaseName))
        } else {
            loginDatabases.unregisterTenant(company.databaseName)
        }

        return CompanySummaryResponse(
            code = company.code,
            name = company.name,
            databaseName = company.databaseName,
            isActive = active,
            modules = loadCompanyModules(company.databaseName),
            logoUrl = company.logoUrl,
            primaryColor = company.primaryColor,
            secondaryColor = company.secondaryColor,
            accentColor = company.accentColor,
        )
    }

    fun deleteInactiveCompany(companyCode: String, provisioningRole: String) {
        val normalizedCompanyCode = companyCode.trim()
        rejectServerWorkspace(normalizedCompanyCode)
        val company = central.connection.use { connection ->
            findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException("Empresa no encontrada.")
        }
        if (company.isActive) {
            throw CompanyProvisioningException("Primero debes desactivar la empresa antes de eliminarla.")
        }

        val tenantConfig = loginDatabases.tenantConfig(company.databaseName)
        var ownerRole: String? = null
        var tenantDatabaseDropped = false
        var ownerRoleDropped = false

        adminConnection(provisioningRole).use { admin ->
            admin.autoCommit = true
            val tenantDatabaseExists = admin.existsDatabase(company.databaseName)
            if (tenantDatabaseExists && tenantConfig != null) {
                ownerRole = tenantRuntimeConnection(tenantConfig).use { tenant ->
                    tenant.prepareStatement(
                        """
                        SELECT users.postgres_role
                        FROM business_owner owner
                        JOIN application_users users ON users.id = owner.user_id
                        LIMIT 1
                        """.trimIndent()
                    ).use { query ->
                        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
                }
            }

            loginDatabases.unregisterTenant(company.databaseName)

            admin.createStatement().use { statement ->
                if (tenantDatabaseExists) {
                    statement.executeUpdate("REVOKE CONNECT ON DATABASE ${quoteIdentifier(company.databaseName)} FROM PUBLIC")
                    statement.executeQuery(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ${quoteLiteral(company.databaseName)}"
                    ).use { }
                    statement.executeUpdate("DROP DATABASE IF EXISTS ${quoteIdentifier(company.databaseName)}")
                    tenantDatabaseDropped = true
                }
                val ownerRoleToDrop = ownerRole
                if (!ownerRoleToDrop.isNullOrBlank() && admin.existsRole(ownerRoleToDrop)) {
                    statement.executeUpdate("DROP ROLE IF EXISTS ${quoteIdentifier(ownerRoleToDrop)}")
                    ownerRoleDropped = true
                }
            }
        }

        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                insertAudit(
                    connection = connection,
                    actorRole = provisioningRole,
                    action = "company.deleted",
                    companyCode = normalizedCompanyCode,
                    moduleId = null,
                    details = """{"databaseName":"${company.databaseName}","ownerRole":"${ownerRole.orEmpty()}","tenantDatabaseDropped":$tenantDatabaseDropped,"ownerRoleDropped":$ownerRoleDropped}""",
                )
                connection.prepareStatement("DELETE FROM companies WHERE code = ? AND is_active = FALSE").use { delete ->
                    delete.setString(1, normalizedCompanyCode)
                    if (delete.executeUpdate() != 1) {
                        throw CompanyProvisioningException("No se pudo eliminar la empresa desactivada.")
                    }
                }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
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
            logoUrl = company.logoUrl,
            primaryColor = company.primaryColor,
            secondaryColor = company.secondaryColor,
            accentColor = company.accentColor,
        )
    }

    /**
     * Base modules, installed modules and the modules offered by the catalog URL configured now.
     * Modules of a previously configured catalog that were never installed are not shown.
     */
    fun listServerModules(): List<ServerModuleResponse> {
        ensureLockedServerModulesPersisted()
        val remotePackages = syncRemoteAvailableModules()
        val shown = moduleRegistry.availableDefinitions
            .filter { moduleRegistry.hasCode(it.id) || moduleRegistry.isInstalled(it.id) || it.id in remotePackages || installStates.containsKey(it.id) }
        val serverUsage = serverWorkspaceModules()
        return shown.map { definition -> moduleResponse(definition, remotePackages[definition.id], serverUsage) }
    }

    private fun moduleResponse(
        definition: ModuleDefinition,
        remotePackage: RemoteModulePackage?,
        serverUsage: Set<String>? = serverWorkspaceModules(),
    ): ServerModuleResponse {
        val installedPackage = installedPackage(definition.id)
        val state = installStates[definition.id]
        val installedVersion = installedPackage?.version ?: definition.version
        val catalogVersion = remotePackage?.definition?.version
        val installed = moduleRegistry.isInstalled(definition.id)
        // Base modules are updated with the server, never from the catalog.
        val comparable = installed && !definition.locked && catalogVersion != null
        return ServerModuleResponse(
            moduleId = definition.id,
            displayName = definition.displayName,
            description = definition.description,
            version = installedVersion,
            latestVersion = remotePackage?.definition?.version,
            locked = definition.locked,
            installed = moduleRegistry.isInstalled(definition.id),
            activeCompanyCount = countCompaniesWithModuleEnabled(definition.id),
            packageUrl = installedPackage?.packageUrl,
            packageSha256 = installedPackage?.packageSha256,
            installedPackagePath = installedPackage?.installedPackagePath,
            status = state?.status,
            statusMessage = state?.message,
            updateAvailable = comparable && ModuleVersion.isNewer(catalogVersion!!, installedVersion),
            newerThanCatalog = comparable && ModuleVersion.isNewer(installedVersion, catalogVersion!!),
            usedByServer = installed && serverUsage != null && (definition.locked || definition.id in serverUsage),
        )
    }

    fun serverModuleCatalog(): ServerModuleCatalogResponse =
        ServerModuleCatalogResponse(catalogUrl = moduleCatalogUrl())

    fun updateServerModuleCatalog(catalogUrl: String): ServerModuleCatalogResponse {
        val normalized = catalogUrl.trim()
        if (normalized.isNotBlank() && !normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            throw CompanyProvisioningException("La URL del catalogo debe iniciar con http:// o https://.")
        }
        central.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO server_settings (setting_key, setting_value, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (setting_key)
                DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { upsert ->
                upsert.setString(1, moduleCatalogSettingKey)
                upsert.setString(2, normalized)
                upsert.executeUpdate()
            }
        }
        return ServerModuleCatalogResponse(catalogUrl = normalized)
    }

    /**
     * Installs a module. Modules whose code is already loaded are activated at once. Optional
     * modules are downloaded from the configured catalog branch and built in the background; when
     * the package is ready the server restarts to load it. The response reports the progress.
     */
    fun installServerModule(moduleId: String): ServerModuleResponse {
        val normalizedModuleId = moduleId.trim()
        val remotePackages = syncRemoteAvailableModules()
        val definition = moduleRegistry.availableDefinition(normalizedModuleId)
            ?: throw CompanyProvisioningException("Modulo no encontrado en el catalogo configurado.")
        val remotePackage = remotePackages[definition.id]
        if (installStates[definition.id]?.status in setOf(InstallStatus.Installing, InstallStatus.Restarting)) {
            return moduleResponse(definition, remotePackage)
        }

        // Only a newer catalog version replaces a loaded package; an older one never downgrades it.
        val loadedVersion = moduleRegistry.loadedVersion(definition.id)
        val update = moduleRegistry.isPackaged(definition.id) && remotePackage != null && loadedVersion != null &&
            ModuleVersion.isNewer(remotePackage.definition.version, loadedVersion)
        if (moduleRegistry.hasCode(definition.id) && !update) {
            activateLoadedModule(definition)
            return moduleResponse(definition, remotePackage)
        }

        val builder = modulePackageBuilder
            ?: throw CompanyProvisioningException("Este servidor no puede compilar modulos: falta la carpeta compiler/ de la instalacion. Actualiza el servidor con update.sh.")
        val source = remotePackage?.source
            ?: throw CompanyProvisioningException("${definition.displayName} no esta en el catalogo configurado, o el catalogo no es una carpeta de GitHub.")
        installStates[definition.id] = ModuleInstallState(InstallStatus.Installing, "En cola para compilar")
        buildExecutor.execute { buildAndInstall(definition, builder, source) }
        return moduleResponse(definition, remotePackage)
    }

    private fun activateLoadedModule(definition: ModuleDefinition) {
        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                upsertServerModule(connection, definition)
                if (!moduleRegistry.isInstalled(definition.id)) {
                    moduleRegistry.install(definition.id)
                }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /**
     * Turns a module on or off in the server workspace. Turning on the first module creates the
     * workspace with its own tenant database. Base modules are always on once it exists.
     */
    fun setServerModuleUsage(moduleId: String, enabled: Boolean, provisioningRole: String): ServerModuleResponse {
        val definition = modules.singleOrNull { it.id == moduleId.trim() }
            ?: throw CompanyProvisioningException("Modulo no reconocido o no instalado en el servidor.")
        if (enabled) {
            setModuleEnabled(ensureServerWorkspace(provisioningRole), definition.id, true, provisioningRole)
        } else {
            if (definition.locked) {
                throw CompanyProvisioningException("${definition.displayName} es un modulo base y siempre esta activo en el espacio del servidor.")
            }
            serverWorkspaceRow()?.let { setModuleEnabled(it.code, definition.id, false, provisioningRole) }
        }
        return moduleResponse(definition, null)
    }

    // Code of the server workspace, creating it with its own tenant database on first use.
    @Synchronized
    fun ensureServerWorkspace(provisioningRole: String): String {
        serverWorkspaceRow()?.let { return it.code }
        val code = ServerWorkspace.Code
        val databaseName = ServerWorkspace.DatabaseName
        central.connection.use { connection ->
            if (connection.exists("companies", "code", code)) {
                throw CompanyProvisioningException("Ya existe una empresa con el codigo $code; cambia su codigo para crear el espacio del servidor.")
            }
        }
        adminConnection(provisioningRole).use { admin ->
            admin.autoCommit = true
            // A database left by an interrupted attempt is reused; its migrations are idempotent.
            if (!admin.existsDatabase(databaseName)) {
                admin.createStatement().use { statement ->
                    statement.executeUpdate("CREATE DATABASE ${quoteIdentifier(databaseName)}")
                    statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(config.runtimeUser)}")
                }
            }
        }
        val tenantConfig = tenantConfig(databaseName)
        tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
            applyTenantCoreMigrations(tenant)
            applyBaseModuleMigrations(tenant)
            grantTenantPermissions(tenant, ownerUsername = null)
        }
        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO companies (
                        id, code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active, is_server_workspace
                    ) VALUES (?, ?, ?, NULL, '#667EEA', '#764BA2', '#FFFFFF', ?, TRUE, TRUE)
                    """.trimIndent()
                ).use { insert ->
                    insert.setObject(1, UUID.randomUUID())
                    insert.setString(2, code)
                    insert.setString(3, ServerWorkspace.Name)
                    insert.setString(4, databaseName)
                    insert.executeUpdate()
                }
                insertAudit(connection, provisioningRole, "server_workspace.created", code, null, """{"databaseName":"$databaseName"}""")
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
        loginDatabases.registerTenant(tenantConfig)
        log.info("Server workspace created in database {}", databaseName)
        return code
    }

    private fun serverWorkspaceRow(): CompanyRow? = try {
        central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE is_server_workspace"
            ).use { query ->
                query.executeQuery().use { rows ->
                    if (!rows.next()) null else CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        logoUrl = rows.getString("logo_url"),
                        primaryColor = rows.getString("primary_color"),
                        secondaryColor = rows.getString("secondary_color"),
                        accentColor = rows.getString("accent_color"),
                        databaseName = rows.getString("database_name"),
                        isActive = rows.getBoolean("is_active"),
                    )
                }
            }
        }
    } catch (_: java.sql.SQLException) {
        // Before control migration V006 there is no workspace column.
        null
    }

    // Modules enabled in the server workspace, or null when it does not exist yet.
    private fun serverWorkspaceModules(): Set<String>? {
        val workspace = serverWorkspaceRow() ?: return null
        val tenant = loginDatabases.tenantConfig(workspace.databaseName) ?: return emptySet()
        return tenantRuntimeConnection(tenant).use { connection ->
            connection.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
        }
    }

    private fun rejectServerWorkspace(companyCode: String) {
        if (companyCode == ServerWorkspace.Code && serverWorkspaceRow()?.code == companyCode) {
            throw CompanyProvisioningException("El espacio del servidor no se administra como una empresa.")
        }
    }

    /**
     * Brings every company up to date with the migrations of the modules it uses: base modules for
     * all companies and optional modules where they are enabled. Runs at startup, so a module
     * version loaded after an installation or update applies its new migrations to the companies
     * that already had the module. Applied migrations are skipped (schema_migrations).
     */
    fun applyPendingModuleMigrations() {
        val provisioningRole = firstServerOwnerRole() ?: run {
            log.warn("No server owner found; pending module migrations were not applied.")
            return
        }
        val companies = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies ORDER BY code").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
            }
        }
        companies.forEach { (companyCode, databaseName) ->
            val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return@forEach
            try {
                tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
                    val enabled = tenant.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
                        query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
                    }
                    moduleRegistry.definitions
                        .filter { (it.locked || it.id in enabled) && moduleRegistry.module(it.id) != null }
                        .forEach { definition ->
                            try {
                                applyModuleMigrations(tenant, definition)
                            } catch (failure: Exception) {
                                log.error("Migrations of module {} failed for company {}", definition.id, companyCode, failure)
                                installStates[definition.id] = ModuleInstallState(
                                    InstallStatus.Attention,
                                    "Una migracion fallo en la empresa $companyCode: ${failure.message}",
                                )
                            }
                        }
                }
            } catch (failure: Exception) {
                log.error("Pending module migrations could not run for company {}", companyCode, failure)
            }
        }
    }

    private fun firstServerOwnerRole(): String? = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT users.postgres_role
            FROM server_owners owners
            JOIN application_users users ON users.id = owners.user_id
            WHERE users.is_active
            ORDER BY users.postgres_role
            LIMIT 1
            """.trimIndent()
        ).use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null } }
    }

    private fun buildAndInstall(definition: ModuleDefinition, builder: ModulePackageBuilder, source: GitHubLocation) {
        val moduleId = definition.id
        try {
            val built = builder.build(moduleId, source) { step ->
                installStates[moduleId] = ModuleInstallState(InstallStatus.Installing, step)
            }
            central.connection.use { connection ->
                upsertServerModule(
                    connection = connection,
                    definition = definition.copy(version = built.version),
                    packageUrl = source.archiveUrl,
                    packageSha256 = built.sha256,
                    installedPackagePath = built.jar.toString(),
                )
            }
            log.info("Module {} {} built from {}@{}; restarting to load it.", moduleId, built.version, source.repo, source.ref)
            installStates[moduleId] = ModuleInstallState(InstallStatus.Restarting, "Reiniciando el servidor para cargar ${definition.displayName}")
            requestRestart()
        } catch (failure: ModuleBuildException) {
            log.warn("Module {} could not be built: {}", moduleId, failure.message)
            installStates[moduleId] = ModuleInstallState(InstallStatus.Failed, failure.message.orEmpty())
        } catch (failure: Exception) {
            log.error("Module {} installation failed", moduleId, failure)
            installStates[moduleId] = ModuleInstallState(InstallStatus.Failed, "Error inesperado: ${failure.message}")
        }
    }

    fun deleteServerModule(moduleId: String) {
        val normalizedModuleId = moduleId.trim()
        val definition = moduleRegistry.definition(normalizedModuleId)
            ?: throw CompanyProvisioningException("Modulo no reconocido o no instalado en el servidor.")
        if (definition.locked) {
            throw CompanyProvisioningException("El modulo ${definition.displayName} es base y no puede eliminarse del servidor.")
        }
        val activeCompanyCount = countCompaniesWithModuleEnabled(definition.id)
        if (activeCompanyCount > 0) {
            throw CompanyProvisioningException("No se puede eliminar ${definition.displayName}; esta activo en $activeCompanyCount empresa(s) o en el espacio del servidor.")
        }

        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM server_modules WHERE module_id = ? AND locked = FALSE").use { delete ->
                    delete.setString(1, definition.id)
                    if (delete.executeUpdate() != 1) {
                        throw CompanyProvisioningException("No se pudo eliminar ${definition.displayName} del catalogo activo.")
                    }
                }
                moduleRegistry.remove(definition.id)
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
        installStates.remove(definition.id)
        // The loaded classes stay in memory until the next restart, but the routes reject the module.
        if (moduleRegistry.isPackaged(definition.id)) {
            modulePackagesRoot.resolve(definition.id).toFile().deleteRecursively()
        }
    }

    private fun ensureLockedServerModulesPersisted() {
        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                moduleRegistry.availableDefinitions
                    .filter { it.locked }
                    .forEach { definition ->
                        upsertServerModule(connection, definition)
                        if (!moduleRegistry.isInstalled(definition.id)) {
                            moduleRegistry.install(definition.id)
                        }
                    }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun syncRemoteAvailableModules(): Map<String, RemoteModulePackage> {
        return remoteModuleCatalog.modules(moduleCatalogUrl()).associateBy { remotePackage ->
            moduleRegistry.registerAvailable(remotePackage.definition)
            remotePackage.definition.id
        }
    }

    private fun moduleCatalogUrl(): String {
        return try {
            central.connection.use { connection ->
                connection.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
                    query.setString(1, moduleCatalogSettingKey)
                    query.executeQuery().use { rows ->
                        if (rows.next()) rows.getString(1) else defaultCatalogUrl
                    }
                }
            }
        } catch (_: Exception) {
            defaultCatalogUrl
        }.trim()
    }

    private fun installedPackage(moduleId: String): InstalledModulePackage? = central.connection.use { connection ->
        connection.prepareStatement(
            "SELECT version, package_url, package_sha256, installed_package_path FROM server_modules WHERE module_id = ?"
        ).use { query ->
            query.setString(1, moduleId)
            query.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    InstalledModulePackage(
                        version = rows.getString("version"),
                        packageUrl = rows.getString("package_url"),
                        packageSha256 = rows.getString("package_sha256"),
                        installedPackagePath = rows.getString("installed_package_path"),
                    )
                }
            }
        }
    }

    private fun upsertServerModule(
        connection: Connection,
        definition: ModuleDefinition,
        packageUrl: String? = null,
        packageSha256: String? = null,
        installedPackagePath: String? = null,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO server_modules (
                module_id, display_name, description, version, locked, package_url, package_sha256, installed_package_path
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (module_id)
            DO UPDATE SET
                display_name = EXCLUDED.display_name,
                description = EXCLUDED.description,
                version = EXCLUDED.version,
                locked = server_modules.locked OR EXCLUDED.locked,
                package_url = COALESCE(EXCLUDED.package_url, server_modules.package_url),
                package_sha256 = COALESCE(EXCLUDED.package_sha256, server_modules.package_sha256),
                installed_package_path = COALESCE(EXCLUDED.installed_package_path, server_modules.installed_package_path)
            """.trimIndent()
        ).use { upsert ->
            upsert.setString(1, definition.id)
            upsert.setString(2, definition.displayName)
            upsert.setString(3, definition.description)
            upsert.setString(4, definition.version)
            upsert.setBoolean(5, definition.locked)
            upsert.setString(6, packageUrl)
            upsert.setString(7, packageSha256)
            upsert.setString(8, installedPackagePath)
            upsert.executeUpdate()
        }
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

    private fun tenantConfig(databaseName: String): TenantDatabaseConfig = TenantDatabaseConfig(
        databaseName = databaseName,
        jdbcUrl = config.tenantJdbcUrlPrefix + databaseName,
        user = config.runtimeUser,
        password = config.runtimePassword,
    )

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
            "database/tenant/migrations/V004__create_application_permissions.sql" to "tenant",
            "database/tenant/migrations/V005__create_application_user_credentials.sql" to "tenant",
        ).forEach { (relativePath, module) ->
            applyMigration(connection, module, relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        }
    }

    private fun applyBaseModuleMigrations(connection: Connection) {
        moduleRegistry.definitions
            .filter { it.locked }
            .forEach { definition -> applyModuleMigrations(connection, definition) }
    }

    private fun applyModuleMigrations(connection: Connection, definition: ModuleDefinition) {
        val module = moduleRegistry.module(definition.id) ?: return
        if (!definition.locked) {
            // Optional modules may build on base modules (e.g. Clientes); bring those schemas up to date first.
            applyBaseModuleMigrations(connection)
        }
        module.migrationPaths.forEach { relativePath ->
            applyMigration(connection, module.migrationModule, relativePath, migrationSql(module, relativePath))
        }
        if (module.runtimeTables.isNotEmpty()) {
            val tables = module.runtimeTables.joinToString(", ") { quoteIdentifier(it) }
            connection.createStatement().use { statement ->
                statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON $tables TO ${quoteIdentifier(config.runtimeUser)}")
            }
        }
    }

    // Packaged modules carry their migrations; modules compiled into the server read the source tree.
    private fun migrationSql(module: ServerModule, relativePath: String): String =
        module.javaClass.classLoader.getResourceAsStream(relativePath)?.use { String(it.readAllBytes(), Charsets.UTF_8) }
            ?: Path.of(config.migrationsRoot).resolve(relativePath).takeIf { Files.isRegularFile(it) }?.let(Files::readString)
            ?: throw CompanyProvisioningException("No se encontro la migracion $relativePath del modulo ${module.definition.id}.")

    private fun applyMigration(connection: Connection, module: String, relativePath: String, content: String) {
        val sql = content.trim()
        require(sql.startsWith("BEGIN;") && sql.endsWith("COMMIT;")) {
            "Migration $relativePath must have outer BEGIN/COMMIT"
        }
        val version = relativePath.substringAfterLast('/').removeSuffix(".sql")
        // scripts/ubuntu/migrations.sh hashes the whole file; earlier servers hashed the trimmed text.
        // Either one identifies an unchanged migration, so both installers can share tenants.
        val checksum = sha256(content)
        val acceptedChecksums = setOf(checksum, sha256(sql))
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
                        if (rows.getString(1) !in acceptedChecksums) {
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

    // The server workspace has no business owner (ownerUsername = null).
    private fun grantTenantPermissions(connection: Connection, ownerUsername: String?) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
            statement.executeUpdate("GRANT USAGE ON SCHEMA public TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE ON application_users TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT ON business_owner, tenant_modules TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON application_sessions TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON application_permissions, application_user_permissions TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON application_user_credentials TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON customers, customer_field_definitions TO ${quoteIdentifier(config.runtimeUser)}")
            if (ownerUsername != null) {
                statement.executeUpdate("GRANT USAGE ON SCHEMA public TO ${quoteIdentifier(ownerUsername)}")
                statement.executeUpdate("GRANT SELECT ON application_users, business_owner, tenant_modules, application_permissions, application_user_permissions TO ${quoteIdentifier(ownerUsername)}")
                statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON application_sessions TO ${quoteIdentifier(ownerUsername)}")
            }
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

    private fun countCompaniesWithModuleEnabled(moduleId: String): Int {
        val companies = central.connection.use { connection ->
            connection.prepareStatement("SELECT database_name FROM companies ORDER BY code").use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString("database_name"))
                        }
                    }
                }
            }
        }
        return companies.count { databaseName ->
            val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: tenantConfig(databaseName)
            tenantRuntimeConnection(tenantConfig).use { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM tenant_modules WHERE module_id = ? AND status = 'enabled'"
                ).use { query ->
                    query.setString(1, moduleId)
                    query.executeQuery().use { rows -> rows.next() }
                }
            }
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
            "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE code = ? AND is_active = TRUE"
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows ->
                return if (rows.next()) {
                    CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        logoUrl = rows.getString("logo_url"),
                        primaryColor = rows.getString("primary_color"),
                        secondaryColor = rows.getString("secondary_color"),
                        accentColor = rows.getString("accent_color"),
                        databaseName = rows.getString("database_name"),
                        isActive = rows.getBoolean("is_active"),
                    )
                } else {
                    null
                }
            }
        }
    }


    private fun findCompanyAnyState(connection: Connection, companyCode: String): CompanyRow? {
        connection.prepareStatement(
            "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE code = ?"
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows ->
                return if (rows.next()) {
                    CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        logoUrl = rows.getString("logo_url"),
                        primaryColor = rows.getString("primary_color"),
                        secondaryColor = rows.getString("secondary_color"),
                        accentColor = rows.getString("accent_color"),
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

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun Connection.setProvisioningRole(role: String) {
        createStatement().use { statement ->
            statement.execute("SET ROLE ${quoteIdentifier(role)}")
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(bytes)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return HexFormat.of().formatHex(digest)
    }

    private data class InstalledModulePackage(
        val version: String,
        val packageUrl: String?,
        val packageSha256: String?,
        val installedPackagePath: String?,
    )

    private data class CompanyRow(
        val code: String,
        val name: String,
        val logoUrl: String?,
        val primaryColor: String,
        val secondaryColor: String,
        val accentColor: String,
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

private object InstallStatus {
    const val Installing = "installing"
    const val Restarting = "restarting"
    const val Failed = "failed"
    // Installed and loaded, but something needs the server owner's attention (e.g. a migration).
    const val Attention = "attention"
}

private data class ModuleInstallState(val status: String, val message: String)
