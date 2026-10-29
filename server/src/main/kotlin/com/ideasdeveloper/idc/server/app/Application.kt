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
import com.ideasdeveloper.idc.server.modules.ModuleJobScheduler
import com.ideasdeveloper.idc.server.modules.ModulePackageBuilder
import com.ideasdeveloper.idc.server.modules.ModulePackageLoader
import com.ideasdeveloper.idc.server.modules.ModuleRegistry
import com.ideasdeveloper.idc.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.SmtpSettings
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
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.ServiceLoader
import javax.sql.DataSource
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val VersionCatalogJson = Json { ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json { encodeDefaults = true })
    }
    val sessionKeyHasher = SessionTokenGenerator()
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

            // A hash of the session, never the token itself: the rate limiter logs its keys.
            requestKey { call ->
                call.request.headers["Authorization"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "session:" + sessionKeyHasher.hash(it) }
                    ?: call.request.local.remoteAddress
            }
        }
    }
    DatabaseFactory.init(environment.config)
    val modulePackagesRoot = Path.of(
        environment.config.propertyOrNull("modules.packagesRoot")?.getString()
            ?: "build/server-modules"
    )
    val classpathModules = loadServerModules()
    val packagedModules = loadPackagedModules(DatabaseFactory.getDataSource(), classpathModules.map { it.definition.id }.toSet())
    val serverModules = (classpathModules + packagedModules).sortedBy { it.definition.id }
    val installedServerModules = loadInstalledServerModuleDefinitions(DatabaseFactory.getDataSource(), serverModules)
    val moduleRegistry = ModuleRegistry(
        modules = serverModules,
        installedModuleIds = installedServerModules.map { it.id }.toSet(),
        packagedModuleIds = packagedModules.map { it.definition.id }.toSet(),
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
        modulePackagesRoot = modulePackagesRoot,
        modulePackageBuilder = ModulePackageBuilder.forRunningServer(modulePackagesRoot),
        requestRestart = ::restartToLoadModules,
    )
    // Applies migrations of newly loaded module versions to the companies that use them, without
    // delaying startup.
    Thread({ companyProvisioning.applyPendingModuleMigrations() }, "module-migrations").apply { isDaemon = true }.start()
    // Crea el servicio de autoservicio para perfil y ZIPs de empresas business_owner.
    val moduleAccessService = ModuleAccessService(DatabaseFactory.getDataSource(), loginDatabases, serverMailSettings(), moduleRegistry::isInstalled)
    // Periodic work declared by modules (ServerModule.jobs), for every company that uses them.
    val moduleJobs = ModuleJobScheduler(moduleRegistry, moduleAccessService).also { it.start() }
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
        moduleJobs.stop()
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

/**
 * Loads the packages built on this server for installed optional modules. A package that fails to
 * load (for example after a core update that broke it) is skipped so the server still starts.
 */
private fun loadPackagedModules(central: DataSource, classpathModuleIds: Set<String>): List<ServerModule> {
    val log = LoggerFactory.getLogger("ModulePackages")
    val packages = try {
        central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT module_id, installed_package_path FROM server_modules WHERE installed_package_path LIKE '%.jar'"
            ).use { query ->
                query.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString("module_id") to rows.getString("installed_package_path")) }
                }
            }
        }
    } catch (_: SQLException) {
        emptyList()
    }
    return packages.flatMap { (moduleId, path) ->
        val jar = Path.of(path)
        when {
            // A module compiled into this server (base module or :server:run) wins over a package.
            moduleId in classpathModuleIds -> emptyList()
            !Files.isRegularFile(jar) -> {
                log.error("Package of module {} not found at {}; reinstall the module.", moduleId, jar)
                emptyList()
            }
            else -> try {
                ModulePackageLoader.load(jar).filter { it.definition.id == moduleId }
            } catch (failure: Throwable) {
                log.error("Package of module {} could not be loaded; reinstall it for this server version.", moduleId, failure)
                emptyList()
            }
        }
    }
}

// systemd (Restart=on-failure) starts the server again within seconds and the new package loads.
private fun restartToLoadModules() {
    Thread({
        Thread.sleep(2_000)
        LoggerFactory.getLogger("ModulePackages").info("Restarting to load the installed module package.")
        exitProcess(RestartExitCode)
    }, "module-restart").start()
}

private const val RestartExitCode = 3

private fun loadInstalledServerModuleDefinitions(central: DataSource, modules: List<ServerModule>): List<ModuleDefinition> {
    val lockedDefinitions = modules.filter { it.definition.locked }.map { it.definition }
    return try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT module_id, display_name, description, version, locked FROM server_modules ORDER BY module_id").use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        addAll(lockedDefinitions)
                        while (rows.next()) {
                            // Only modules whose code is loaded count as installed. Rows left by older
                            // servers or by a package that no longer loads show as available to install.
                            val moduleId = rows.getString("module_id")
                            modules.firstOrNull { it.definition.id == moduleId }?.definition?.let(::add)
                        }
                    }.distinctBy { it.id }
                }
            }
        }
    } catch (_: SQLException) {
        lockedDefinitions
    }
}

private fun Application.serverMailSettings(): SmtpSettings? {
    fun value(key: String) = environment.config.propertyOrNull("mail.$key")?.getString()?.trim().orEmpty()
    return SmtpSettings(
        host = value("host"),
        port = value("port").toIntOrNull() ?: 587,
        username = value("username"),
        password = environment.config.propertyOrNull("mail.password")?.getString().orEmpty(),
        from = value("from"),
        security = value("security").ifBlank { "starttls" },
    ).takeIf { it.isUsable }
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
