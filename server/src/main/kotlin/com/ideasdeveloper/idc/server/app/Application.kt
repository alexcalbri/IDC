package com.ideasdeveloper.idc.server.app

import com.ideasdeveloper.idc.sayHello
import com.ideasdeveloper.idc.server.auth.api.authRoutes
import com.ideasdeveloper.idc.server.auth.application.SessionService
import com.ideasdeveloper.idc.server.auth.infrastructure.database.ApplicationSessionRepository
import com.ideasdeveloper.idc.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.idc.server.auth.api.accountProfileRoutes
import com.ideasdeveloper.idc.server.auth.api.passwordResetRoutes
import com.ideasdeveloper.idc.server.auth.application.AccountException
import com.ideasdeveloper.idc.server.auth.application.AccountPasswords
import com.ideasdeveloper.idc.server.auth.application.AccountService
import com.ideasdeveloper.idc.server.company.api.companyRoutes
import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.idc.server.company.application.CompanySelfService
import com.ideasdeveloper.idc.server.company.application.CoreUpdateService
import com.ideasdeveloper.idc.server.company.application.JdbcCoreUpdateStore
import com.ideasdeveloper.idc.server.company.application.ServerMcpTools
import com.ideasdeveloper.idc.server.company.application.ServerOwnerAuthorizer
import com.ideasdeveloper.idc.server.infrastructure.database.DatabaseFactory
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.CompanyFiles
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleJobScheduler
import com.ideasdeveloper.idc.server.modules.JdbcModuleUnsignedAuthorizationStore
import com.ideasdeveloper.idc.server.modules.ModulePackageBuilder
import com.ideasdeveloper.idc.server.modules.ModulePackageLoader
import com.ideasdeveloper.idc.server.modules.ModuleRequirements
import com.ideasdeveloper.idc.server.modules.ModuleRegistry
import com.ideasdeveloper.idc.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.idc.server.modules.ServerMcpModuleId
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.SmtpSettings
import com.ideasdeveloper.idc.server.modules.moduleRoutes
import com.ideasdeveloper.idc.server.extensions.ExtensionActors
import com.ideasdeveloper.idc.server.extensions.ExtensionActorLookup
import com.ideasdeveloper.idc.server.extensions.ExtensionMcpTools
import com.ideasdeveloper.idc.server.extensions.packages.ExtensionManifestValidator
import com.ideasdeveloper.idc.server.extensions.packages.ExtensionPackageBuilder
import com.ideasdeveloper.idc.server.extensions.packages.ExtensionPackageService
import com.ideasdeveloper.idc.server.extensions.packages.ExtensionSignatureVerifier
import com.ideasdeveloper.idc.server.extensions.packages.JdbcExtensionPackageStore
import com.ideasdeveloper.idc.server.extensions.packages.JdbcUnsignedAuthorizationStore
import com.ideasdeveloper.idc.server.extensions.packages.RemoteExtensionCatalog
import com.ideasdeveloper.idc.server.extensions.packages.extensionPackageRoutes
import com.ideasdeveloper.idc.server.extensions.ExtensionConnections
import com.ideasdeveloper.idc.server.extensions.ExtensionNotifier
import com.ideasdeveloper.idc.server.extensions.ExtensionRegistry
import com.ideasdeveloper.idc.server.extensions.ExtensionService
import com.ideasdeveloper.idc.server.extensions.ExtensionStorage
import com.ideasdeveloper.idc.server.extensions.extensionRoutes
import com.ideasdeveloper.idc.server.security.MasterKeySecretBox
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.modules.notificationRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import kotlinx.coroutines.sync.withLock
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.ServiceLoader
import javax.sql.DataSource
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

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

            requestKey { call -> clientAddress(call) }
        }
        register(RateLimitName("admin")) {
            rateLimiter(
                limit = 60,
                refillPeriod = 60.seconds,
            )

            // Siempre por IP: una clave derivada de la cabecera Authorization se evade rotando el
            // token (basta con enviar uno distinto en cada peticion) y haria crecer el mapa sin tope.
            requestKey { call -> clientAddress(call) }
        }
        // Lecturas del Core (/version, /modules, /notifications): por IP, holgado para el uso normal
        // de la app pero evita inundaciones que abririan una conexion a la base de datos cada una.
        register(RateLimitName("public")) {
            rateLimiter(
                limit = 300,
                refillPeriod = 60.seconds,
            )
            requestKey { call -> clientAddress(call) }
        }
        // Routes of extensions, including public webhooks (Meta, WordPress): per client IP, generous
        // enough for webhook bursts, but it stops floods that would open a database connection each.
        register(RateLimitName("extensions")) {
            rateLimiter(
                limit = 300,
                refillPeriod = 60.seconds,
            )
            requestKey { call -> clientAddress(call) }
        }
    }
    DatabaseFactory.init(environment.config)
    val modulePackagesRoot = Path.of(
        environment.config.propertyOrNull("modules.packagesRoot")?.getString()
            ?: "build/server-modules"
    )
    // Files modules store for each company (attachments...); back this folder up with the databases.
    val companyFiles = CompanyFiles.local(
        Path.of(environment.config.propertyOrNull("company.filesRoot")?.getString() ?: "build/company-files")
    )
    val classpathModules = loadServerModules()
    val packageRequirements = mutableMapOf<String, ModuleRequirements>()
    val packagedModules = loadPackagedModules(DatabaseFactory.getDataSource(), classpathModules.map { it.definition.id }.toSet(), packageRequirements)
    val serverModules = (classpathModules + packagedModules).sortedBy { it.definition.id }
    val installedServerModules = loadInstalledServerModuleDefinitions(DatabaseFactory.getDataSource(), serverModules)
    val moduleRegistry = ModuleRegistry(
        modules = serverModules,
        installedModuleIds = installedServerModules.map { it.id }.toSet(),
        packagedModuleIds = packagedModules.map { it.definition.id }.toSet(),
        packageRequirements = packageRequirements,
    ).also { registry ->
        installedServerModules.forEach { definition ->
            registry.registerInstalled(definition)
        }
    }
    val githubToken = environment.config.propertyOrNull("modules.githubToken")?.getString()?.trim()?.takeIf { it.isNotEmpty() }
    val remoteModuleCatalog = RemoteModuleCatalog(githubToken)
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
    // Secrets are sealed with IDC_MASTER_KEY (server.env); without it the server runs but cannot store new ones.
    val secretBox: SecretBox = MasterKeySecretBox.fromEnvironment().also { box ->
        if (!box.available) log.warn("IDC_MASTER_KEY is not configured; secrets cannot be stored until it is set in server.env.")
    }
    val provisioningConfig = companyProvisioningConfig()
    val companyProvisioning = CompanyProvisioningService(
        central = DatabaseFactory.getDataSource(),
        config = provisioningConfig,
        loginDatabases = loginDatabases,
        moduleRegistry = moduleRegistry,
        remoteModuleCatalog = remoteModuleCatalog,
        defaultCatalogUrl = environment.config.propertyOrNull("modules.catalogUrl")?.getString().orEmpty(),
        modulePackagesRoot = modulePackagesRoot,
        modulePackageBuilder = ModulePackageBuilder.forRunningServer(
            modulePackagesRoot,
            githubToken,
            JdbcModuleUnsignedAuthorizationStore { DatabaseFactory.getDataSource().connection },
            // Compuerta de firma de modulos: permisiva por defecto (develop/pruebas); produccion la pone en true.
            signatureEnforced = System.getenv("MODULE_SIGNATURE_ENFORCED")?.trim()?.equals("true", ignoreCase = true) ?: false,
        ),
        requestRestart = ::restartToLoadModules,
        companyFiles = companyFiles,
    )
    // Applies migrations of newly loaded module versions to the companies that use them, without
    // delaying startup.
    // Extension compatibility is checked once those migrations (which create tenant_extensions) ran.
    val migrationsDone = java.util.concurrent.CountDownLatch(1)
    Thread({
        try {
            companyProvisioning.applyPendingModuleMigrations()
        } finally {
            migrationsDone.countDown()
        }
    }, "module-migrations").apply { isDaemon = true }.start()
    // Built below; the server MCP reads them only when an extension tool runs.
    lateinit var extensionService: ExtensionService
    lateinit var extensionPackages: ExtensionPackageService
    val serverMcp = ServerMcpTools.mcp(
        companyProvisioning,
        extensions = { role -> ExtensionMcpTools.tools({ extensionService }, { extensionPackages }, role) },
        extensionInstructions = ExtensionMcpTools.Instructions,
    )
    // Crea el servicio de autoservicio para perfil y ZIPs de empresas business_owner.
    val moduleAccessService = ModuleAccessService(
        DatabaseFactory.getDataSource(),
        loginDatabases,
        serverMailSettings(),
        moduleRegistry::isInstalled,
        // The server's own MCP (companies and modules, server owners only) and each module's.
        { listOf(serverMcp) + moduleRegistry.mcp().filterNot { it.moduleId == ServerMcpModuleId } },
        loginDatabases.service::login,
        moduleRegistry::customerTimelines,
        companyFiles,
        moduleRegistry::customerHistoryWriter,
        moduleRegistry::customerChangeListener,
        moduleRegistry::customerNotesReader,
        secrets = secretBox,
    )
    // Extensions installed per company (EXTENSIONS.md): bundled in the modules or loaded from packages.
    val extensionConnections = ExtensionConnections.from(moduleAccessService)
    val extensionStorage = ExtensionStorage(secretBox)
    // Extension packages built on this server from the extension catalog (signed, or authorized by hash).
    val remoteExtensionCatalog = RemoteExtensionCatalog(githubToken)
    val extensionCatalogUrl = RemoteExtensionCatalog.effectiveUrl(
        environment.config.propertyOrNull("extensions.catalogUrl")?.getString().orEmpty(),
        environment.config.propertyOrNull("modules.catalogUrl")?.getString().orEmpty(),
    )
    val extensionPackagesRoot = modulePackagesRoot.toAbsolutePath().resolveSibling("server-extensions")
    val unsignedAuthorizations = JdbcUnsignedAuthorizationStore { DatabaseFactory.getDataSource().connection }
    extensionPackages = ExtensionPackageService(
        store = JdbcExtensionPackageStore { DatabaseFactory.getDataSource().connection },
        packagesRoot = extensionPackagesRoot,
        bundledIds = { serverModules.flatMap { module -> module.bundledExtensions.map { it.manifest.id } }.toSet() },
        catalogSource = { remoteExtensionCatalog.extensions(extensionCatalogUrl) },
        catalogProblem = { remoteExtensionCatalog.problem(extensionCatalogUrl) },
        builder = ExtensionPackageBuilder.forRunningServer(
            packagesRoot = extensionPackagesRoot,
            validator = ExtensionManifestValidator(
                installedModuleVersion = { id -> moduleRegistry.definition(id)?.version },
                modulePermissions = { id -> moduleRegistry.availableDefinition(id)?.extensionPermissions.orEmpty() },
            ),
            verifier = ExtensionSignatureVerifier(),
            authorizations = unsignedAuthorizations,
            moduleJar = { id -> installedModuleJar(DatabaseFactory.getDataSource(), id) },
            githubToken = githubToken,
        ),
        authorizations = unsignedAuthorizations,
        requestRestart = ::restartToLoadModules,
        audit = { role, action, company, module, details -> companyProvisioning.recordAudit(role, action, company, module, details) },
    )
    val packageExtensions = extensionPackages.loadInstalled(
        moduleClassLoader = { id -> moduleRegistry.module(id)?.javaClass?.classLoader },
    )
    val extensionRegistry = ExtensionRegistry.of(serverModules, packageExtensions)
    serverModules.forEach { module -> module.linkExtensions(extensionRegistry.forModule(module.definition.id)) }
    extensionService = ExtensionService(
        connections = extensionConnections,
        storage = extensionStorage,
        registry = extensionRegistry,
        modules = moduleRegistry,
        notifier = ExtensionNotifier.from(moduleAccessService),
        auditor = { role, action, company, module, details -> companyProvisioning.recordAudit(role, action, company, module, details) },
        migrationRunner = { company, extension -> companyProvisioning.applyExtensionMigrations(company, extension) },
    )
    // A module loaded at a version below an extension's minimum leaves that extension incompatible.
    Thread({
        // Group operations cut by a restart end as failed, so their companies can be retried.
        try {
            extensionService.recoverInterrupted()
        } catch (failure: Exception) {
            log.error("Interrupted extension operations were not recovered", failure)
        }
        migrationsDone.await()
        serverModules.map { it.definition }.filter { extensionRegistry.forModule(it.id).isNotEmpty() }.forEach { definition ->
            try {
                extensionService.markIncompatible(definition.id, definition.version)
            } catch (failure: Exception) {
                log.error("Extensions of module {} were not checked", definition.id, failure)
            }
        }
    }, "extension-compatibility").apply { isDaemon = true }.start()
    // Name and email of each user and password recovery by email code.
    // Un solo contador de fallos de contrasena actual para /auth/profile y la edicion del business owner.
    val passwordAttempts = com.ideasdeveloper.idc.server.auth.application.PasswordAttemptLimiter()
    val accountService = AccountService(
        central = DatabaseFactory.getDataSource(),
        loginDatabases = loginDatabases,
        mail = serverMailSettings().let { settings -> { settings } },
        verifyPassword = loginDatabases::verifyPassword,
        passwordAttempts = passwordAttempts,
        passwords = AccountPasswords(
            serverOwner = { userId, password ->
                try {
                    companyProvisioning.setServerOwnerPasswordByEmailCode(userId, password)
                } catch (failure: CompanyProvisioningException) {
                    throw AccountException("PASSWORD_NOT_UPDATED", failure.message.orEmpty())
                }
            },
            businessOwner = { companyCode, password ->
                try {
                    companyProvisioning.setBusinessOwnerPasswordByEmailCode(companyCode, password)
                } catch (failure: CompanyProvisioningException) {
                    throw AccountException("PASSWORD_NOT_UPDATED", failure.message.orEmpty())
                }
            },
        ),
    )
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
        ownerEditor = companyProvisioning::updateBusinessOwnerBySelf,
        verifyOwnerPassword = { companyCode, username, password -> loginDatabases.verifyPassword(companyCode, username, password) },
        passwordAttempts = passwordAttempts,
        secrets = secretBox,
    )
    monitor.subscribe(ApplicationStopped) {
        moduleJobs.stop()
        remoteModuleCatalog.close()
        remoteExtensionCatalog.close()
        loginDatabases.close()
        DatabaseFactory.close()
    }
    log.info("PostgreSQL connection verified")
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        rateLimit(RateLimitName("public")) {
            get("/version") {
                // En cache unos minutos: la consulta hace peticiones HTTP salientes y no debe poder
                // amplificarse desde fuera.
                call.respond(
                    versionCache.get {
                        versionResponse(
                            catalogUrl = environment.config.propertyOrNull("version.catalogUrl")?.getString().orEmpty(),
                            central = DatabaseFactory.getDataSource(),
                            remoteModuleCatalog = remoteModuleCatalog,
                            defaultModuleCatalogUrl = environment.config.propertyOrNull("modules.catalogUrl")?.getString().orEmpty(),
                        )
                    }
                )
            }
        }

        rateLimit(RateLimitName("login")) {
            authRoutes(
                loginService = loginDatabases.service,
                switchTargets = loginDatabases::switchTargets,
                switchTo = loginDatabases::switchTo,
                logout = loginDatabases::logout,
                includeSession = false,
            )
            passwordResetRoutes(accountService)
        }
        rateLimit(RateLimitName("admin")) {
            // Cierre de sesion y cambio de empresa: no deben gastar el cubo estricto del login (5/min).
            authRoutes(
                loginService = loginDatabases.service,
                switchTargets = loginDatabases::switchTargets,
                switchTo = loginDatabases::switchTo,
                logout = loginDatabases::logout,
                includeLogin = false,
            )
            accountProfileRoutes(accountService)
        }
        rateLimit(RateLimitName("admin")) {
            // Registra rutas de provisioning server_owner y autoservicio business_owner.
            companyRoutes(
                authorizer = ServerOwnerAuthorizer(DatabaseFactory.getDataSource(), centralSessions),
                provisioning = companyProvisioning,
                selfService = companySelfService,
                coreUpdates = CoreUpdateService(
                    store = JdbcCoreUpdateStore(DatabaseFactory.getDataSource()),
                    currentVersion = IdeasCoreVersion,
                    latestVersion = {
                        VersionCatalogClient.fetch(
                            environment.config.propertyOrNull("version.catalogUrl")?.getString().orEmpty()
                        )?.latestCoreVersion
                    },
                ),
            )
        }
        // Centro de notificaciones del Core: la campana de la app lee y marca las del usuario.
        rateLimit(RateLimitName("public")) {
            notificationRoutes(moduleRegistry, moduleAccessService)
            moduleRoutes(moduleRegistry, moduleAccessService)
        }
        rateLimit(RateLimitName("admin")) {
            extensionRoutes(
                extensionService,
                ExtensionActors(moduleAccessService, ServerOwnerAuthorizer(DatabaseFactory.getDataSource(), centralSessions), extensionConnections),
            )
        }
        rateLimit(RateLimitName("admin")) {
            val packageActors = ExtensionActors(moduleAccessService, ServerOwnerAuthorizer(DatabaseFactory.getDataSource(), centralSessions), extensionConnections)
            // Instalar por ZIP/URL queda apagado salvo que EXTENSION_UPLOAD_ENABLED=true (mismo patron que MODULE_SIGNATURE_ENFORCED).
            val uploadEnabled = System.getenv("EXTENSION_UPLOAD_ENABLED")?.trim()?.equals("true", ignoreCase = true) ?: false
            extensionPackageRoutes(extensionPackages, uploadEnabled) { call ->
                (packageActors.serverOwner(call) as? ExtensionActorLookup.Found)?.actor?.role
            }
        }
        // Routes of every extension, mounted once behind ExtensionGate (status per company).
        rateLimit(RateLimitName("extensions")) {
            extensionRegistry.installRoutes(this, moduleAccessService, extensionStorage)
        }
    }
}

// IP del cliente; detras del proxy local se toma X-Real-IP.
private fun clientAddress(call: ApplicationCall): String {
    val remoteAddress = call.request.local.remoteAddress
    // Solo se confia en X-Real-IP cuando la peticion llega del proxy local (IPv4 o IPv6).
    val fromLocalProxy = remoteAddress == "127.0.0.1" || remoteAddress == "::1" || remoteAddress == "0:0:0:0:0:0:0:1"
    if (!fromLocalProxy) return remoteAddress
    // Tope de longitud y solo caracteres de una IP: la cabecera es del cliente y es clave del limitador.
    return call.request.headers["X-Real-IP"]
        ?.trim()
        ?.take(64)
        ?.takeIf { it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char == '.' || char == ':' } }
        ?: remoteAddress
}

// Valor en memoria con expiracion: evita recalcular (y repetir peticiones salientes) en cada llamada.
// Un fallo tambien se guarda, con un TTL corto, para no reintentar la carga en cada peticion.
private class ExpiringCache<T : Any>(private val ttlMillis: Long, private val failureTtlMillis: Long) {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var value: T? = null
    private var failure: Throwable? = null
    private var expiresAt = 0L

    suspend fun get(load: () -> T): T = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now < expiresAt) {
            value?.let { return@withLock it }
            failure?.let { throw it }
        }
        try {
            // La carga hace HTTP bloqueante: fuera de los hilos del dispatcher de peticiones.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load() }.also {
                value = it
                failure = null
                expiresAt = now + ttlMillis
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            value = null
            failure = error
            expiresAt = now + failureTtlMillis
            throw error
        }
    }
}

private val versionCache = ExpiringCache<VersionResponse>(ttlMillis = 5 * 60_000L, failureTtlMillis = 45_000L)

private fun versionResponse(
    catalogUrl: String,
    central: DataSource,
    remoteModuleCatalog: RemoteModuleCatalog,
    defaultModuleCatalogUrl: String,
): VersionResponse {
    val remote = VersionCatalogClient.fetch(catalogUrl)
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
private fun loadPackagedModules(
    central: DataSource,
    classpathModuleIds: Set<String>,
    requirements: MutableMap<String, ModuleRequirements>,
): List<ServerModule> {
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
                    .also { loaded -> if (loaded.isNotEmpty()) requirements[moduleId] = ModulePackageLoader.readRequirements(jar, moduleId) }
            } catch (failure: Throwable) {
                log.error("Package of module {} could not be loaded; reinstall it for this server version.", moduleId, failure)
                emptyList()
            }
        }
    }
}

// systemd (Restart=on-failure) starts the server again within seconds and the new package loads.
// Jar of an optional module built on this server, which extension packages compile against; null for base modules.
private fun installedModuleJar(central: DataSource, moduleId: String): Path? = try {
    central.connection.use { connection ->
        connection.prepareStatement("SELECT installed_package_path FROM server_modules WHERE module_id = ? AND installed_package_path LIKE '%.jar'").use { query ->
            query.setString(1, moduleId)
            query.executeQuery().use { rows -> if (rows.next()) Path.of(rows.getString(1)).takeIf { Files.isRegularFile(it) } else null }
        }
    }
} catch (_: SQLException) {
    null
}

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
        // SMTP del servidor (lo configura el dueno del servidor): se permite un relay local o interno,
        // a diferencia del SMTP por empresa que valida el host contra redes privadas (anti-SSRF).
        trusted = true,
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
