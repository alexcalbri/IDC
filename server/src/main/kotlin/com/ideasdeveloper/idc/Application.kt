package com.ideasdeveloper.idc

import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import com.ideasdeveloper.idc.auth.LoginDatabases
import com.ideasdeveloper.idc.auth.authRoutes
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
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
    }
    DatabaseFactory.init(environment.config)
    val loginDatabases = LoginDatabases(
        central = DatabaseFactory.getDataSource(),
        centralDatabase = DatabaseFactory.getDatabase(),
        centralUrl = environment.config.property("database.url").getString(),
        tenantConfiguration = environment.config.property("tenant.databases").getString(),
        lifetimeSeconds = environment.config.property("auth.sessionLifetimeSeconds").getString().toLong(),
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
    }
}
