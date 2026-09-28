package com.ideasdeveloper.idc.server.app

import com.ideasdeveloper.idc.sayHello
import com.ideasdeveloper.idc.server.auth.api.authRoutes
import com.ideasdeveloper.idc.server.auth.application.SessionService
import com.ideasdeveloper.idc.server.auth.infrastructure.database.ApplicationSessionRepository
import com.ideasdeveloper.idc.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.idc.server.company.api.companyRoutes
import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.idc.server.company.application.CompanySelfService
import com.ideasdeveloper.idc.server.company.application.ServerOwnerAuthorizer
import com.ideasdeveloper.idc.server.infrastructure.database.DatabaseFactory
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleRegistry
import com.ideasdeveloper.idc.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.moduleRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Path
import java.sql.SQLException
import java.util.ServiceLoader
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

private val VersionCatalogJson = Json { ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json { encodeDefaults = true })
    }
    install(RateLimit) {
        register(RateLimitName("login")) {
            rateLimiter(
                limit = 5,
                refillPeriod = 60.seconds,
            )

            requestKey { call ->
                val remoteAddress = call.request.local.remoteAddress

                if (remoteAddress == "127.0.0.1") {
                    call.request.headers["X-Real-IP"]
                        ?.takeIf { it.isNotBlank() }
                        ?: remoteAddress
                } else {
                    remoteAddress
                }
            }
        }
        register(RateLimitName("admin")) {
            rateLimiter(
                limit = 20,
                refillPeriod = 60.seconds,
            )

            requestKey { call ->
                call.request.headers["Authorization"]
                    ?.takeIf { it.isNotBlank() }
                    ?: call.request.local.remoteAddress
            }
        }
    }
    DatabaseFactory.init(environment.config)
    val serverModules = loadServerModules()
    val installedServerModules = loadInstalledServerModuleDefinitions(DatabaseFactory.getDataSource(), serverModules)
    val moduleRegistry = ModuleRegistry(
        modules = serverModules,
        installedModuleIds = installedServerModules.map { it.id }.toSet(),
    ).also { registry ->
        installedServerModules.forEach { definition ->
            registry.registerInstalled(definition)
        }
    }
    val remoteModuleCatalog = RemoteModuleCatalog()
    val loginDatabases = LoginDatabases(
        central = DatabaseFactory.getDataSource(),
        centralDatabase = DatabaseFactory.getDatabase(),
        centralUrl = environment.config.property("database.url").getString(),
        tenantConfiguration = environment.config.property("tenant.databases").getString(),
        runtimeUser = environment.config.property("database.user").getString(),
        runtimePassword = environment.config.property("database.password").getString(),
        tenantJdbcUrlPrefix = environment.config.propertyOrNull("provisioning.tenantJdbcUrlPrefix")?.getString()
            ?: environment.config.property("database.url").getString().substringBeforeLast('/') + "/",
        lifetimeSeconds = environment.config.property("auth.sessionLifetimeSeconds").getString().toLong(),
    )
    val centralSessions = SessionService(
        sessionRepository = ApplicationSessionRepository(DatabaseFactory.getDatabase()),
        userRepository = ApplicationUserRepository(DatabaseFactory.getDatabase()),
        tokenGenerator = SessionTokenGenerator(),
        sessionLifetimeSeconds = environment.config.property("auth.sessionLifetimeSeconds").getString().toLong(),
    )
    val provisioningConfig = companyProvisioningConfig()
    val companyProvisioning = CompanyProvisioningService(
        central = DatabaseFactory.getDataSource(),
        config = provisioningConfig,
        loginDatabases = loginDatabases,
        moduleRegistry = moduleRegistry,
        remoteModuleCatalog = remoteModuleCatalog,
        defaultCatalogUrl = environment.config.propertyOrNull("modules.catalogUrl")?.getString().orEmpty(),
        modulePackagesRoot = Path.of(
            environment.config.propertyOrNull("modules.packagesRoot")?.getString()
                ?: "build/server-modules"
        ),
    )
    // Crea el servicio de autoservicio para perfil y ZIPs de empresas business_owner.
    val moduleAccessService = ModuleAccessService(DatabaseFactory.getDataSource(), loginDatabases)
    val companySelfService = CompanySelfService(
        central = DatabaseFactory.getDataSource(),
        loginDatabases = loginDatabases,
        moduleRegistry = moduleRegistry,
        backupsRoot = Path.of(
            environment.config.propertyOrNull("company.backupsRoot")?.getString()
                ?: "build/company-backups"
        ),
    )
    monitor.subscribe(ApplicationStopped) {
        remoteModuleCatalog.close()
        loginDatabases.close()
        DatabaseFactory.close()
    }
    log.info("PostgreSQL connection verified")
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        get("/version") {
            call.respond(
                versionResponse(
                    catalogUrl = environment.config.propertyOrNull("version.catalogUrl")?.getString().orEmpty(),
                    central = DatabaseFactory.getDataSource(),
                    remoteModuleCatalog = remoteModuleCatalog,
                    defaultModuleCatalogUrl = environment.config.propertyOrNull("modules.catalogUrl")?.getString().orEmpty(),
                )
            )
        }

        rateLimit(RateLimitName("login")) {
            authRoutes(
                loginService = loginDatabases.service,
            )
        }
        rateLimit(RateLimitName("admin")) {
            // Registra rutas de provisioning server_owner y autoservicio business_owner.
            companyRoutes(
                authorizer = ServerOwnerAuthorizer(DatabaseFactory.getDataSource(), centralSessions),
                provisioning = companyProvisioning,
                selfService = companySelfService,
            )
        }
        moduleRoutes(moduleRegistry, moduleAccessService)
    }
}

private fun versionResponse(
    catalogUrl: String,
    central: DataSource,
    remoteModuleCatalog: RemoteModuleCatalog,
    defaultModuleCatalogUrl: String,
): VersionResponse {
    val remote = catalogUrl.trim().takeIf { it.isNotBlank() }?.let { url ->
        runCatching {
            VersionCatalogJson.decodeFromString<RemoteVersionCatalog>(
                URI.create(url).toURL().readText()
            )
        }.getOrNull()
    }
    return VersionResponse(
        latestCoreVersion = remote?.latestCoreVersion,
        latestAppVersion = remote?.latestAppVersion,
        moduleUpdates = moduleUpdates(central, remoteModuleCatalog, defaultModuleCatalogUrl),
    )
}

private fun moduleUpdates(
    central: DataSource,
    remoteModuleCatalog: RemoteModuleCatalog,
    defaultModuleCatalogUrl: String,
): List<ModuleVersionUpdate> {
    val installed = installedModuleVersions(central)
    if (installed.isEmpty()) return emptyList()
    val latestById = remoteModuleCatalog.modules(moduleCatalogUrl(central, defaultModuleCatalogUrl))
        .associateBy { it.definition.id }
    return installed.mapNotNull { installedModule ->
        val latest = latestById[installedModule.moduleId]?.definition ?: return@mapNotNull null
        latest.version
            .takeIf { it.isNotBlank() && it != installedModule.version }
            ?.let {
                ModuleVersionUpdate(
                    moduleId = installedModule.moduleId,
                    displayName = latest.displayName,
                    currentVersion = installedModule.version,
                    latestVersion = it,
                )
            }
    }.sortedBy { it.moduleId }
}

private fun installedModuleVersions(central: DataSource): List<InstalledModuleVersion> =
    try {
        central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT module_id, display_name, version FROM server_modules ORDER BY module_id"
            ).use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                InstalledModuleVersion(
                                    moduleId = rows.getString("module_id"),
                                    displayName = rows.getString("display_name"),
                                    version = rows.getString("version"),
                                )
                            )
                        }
                    }
                }
            }
        }
    } catch (_: SQLException) {
        emptyList()
    }

private fun moduleCatalogUrl(central: DataSource, defaultModuleCatalogUrl: String): String =
    try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
                query.setString(1, "module.catalog.url")
                query.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) else defaultModuleCatalogUrl
                }
            }
        }
    } catch (_: SQLException) {
        defaultModuleCatalogUrl
    }.trim()

private data class InstalledModuleVersion(
    val moduleId: String,
    val displayName: String,
    val version: String,
)

private fun loadServerModules() =
    ServiceLoader.load(ServerModuleProvider::class.java)
        .map { it.create() }
        .sortedBy { it.definition.id }

private fun loadInstalledServerModuleDefinitions(central: DataSource, modules: List<ServerModule>): List<ModuleDefinition> {
    val lockedDefinitions = modules.filter { it.definition.locked }.map { it.definition }
    return try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT module_id, display_name, description, version, locked FROM server_modules ORDER BY module_id").use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        addAll(lockedDefinitions)
                        while (rows.next()) {
                            val moduleId = rows.getString("module_id")
                            val localDefinition = modules.firstOrNull { it.definition.id == moduleId }?.definition
                            add(
                                localDefinition ?: ModuleDefinition(
                                    id = moduleId,
                                    displayName = rows.getString("display_name"),
                                    description = rows.getString("description"),
                                    version = rows.getString("version"),
                                    locked = rows.getBoolean("locked"),
                                )
                            )
                        }
                    }.distinctBy { it.id }
                }
            }
        }
    } catch (_: SQLException) {
        lockedDefinitions
    }
}

private fun Application.companyProvisioningConfig(): CompanyProvisioningConfig {
    val databaseUrl = environment.config.property("database.url").getString()
    val runtimeUser = environment.config.property("database.user").getString()
    val runtimePassword = environment.config.property("database.password").getString()
    return CompanyProvisioningConfig(
        administrationJdbcUrl = environment.config.propertyOrNull("provisioning.administrationJdbcUrl")?.getString()
            ?: databaseUrl,
        runtimeUser = runtimeUser,
        runtimePassword = runtimePassword,
        tenantJdbcUrlPrefix = environment.config.propertyOrNull("provisioning.tenantJdbcUrlPrefix")?.getString()
            ?: databaseUrl.substringBeforeLast('/') + "/",
        migrationsRoot = environment.config.propertyOrNull("provisioning.migrationsRoot")?.getString()
            ?: ".",
    )
}
