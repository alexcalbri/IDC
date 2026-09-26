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
import com.ideasdeveloper.idc.server.modules.ModuleRegistry
import com.ideasdeveloper.idc.server.modules.moduleRoutes
import com.ideasdeveloper.idc.modules.clientes.ClientesServerModule
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Path
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
    val moduleRegistry = ModuleRegistry(
        listOf(
            ClientesServerModule,
        )
    )
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
    val companyProvisioning = provisioningConfig?.let {
        CompanyProvisioningService(
            central = DatabaseFactory.getDataSource(),
            config = it,
            loginDatabases = loginDatabases,
            moduleRegistry = moduleRegistry,
        )
    }
    // Crea el servicio de autoservicio para perfil y ZIPs de empresas business_owner.
    val companySelfService = CompanySelfService(
        central = DatabaseFactory.getDataSource(),
        loginDatabases = loginDatabases,
        backupsRoot = Path.of(
            environment.config.propertyOrNull("company.backupsRoot")?.getString()
                ?: "build/company-backups"
        ),
    )
    monitor.subscribe(ApplicationStopped) {
        loginDatabases.close()
        DatabaseFactory.close()
    }
    log.info("PostgreSQL connection verified")
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
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
        moduleRoutes(moduleRegistry)
    }
}

private fun Application.companyProvisioningConfig(): CompanyProvisioningConfig? {
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
