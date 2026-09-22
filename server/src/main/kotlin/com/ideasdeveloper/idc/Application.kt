package com.ideasdeveloper.idc

import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    DatabaseFactory.init(environment.config)
    monitor.subscribe(ApplicationStopped) {
        DatabaseFactory.close()
    }
    log.info("PostgreSQL connection verified")

    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
    }
}
