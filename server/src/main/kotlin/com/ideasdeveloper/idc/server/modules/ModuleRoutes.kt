package com.ideasdeveloper.idc.server.modules

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.moduleRoutes(registry: ModuleRegistry) {
    route("/modules") {
        get {
            call.respond(HttpStatusCode.OK, registry.definitions)
        }

        get("/{moduleId}/metadata") {
            val moduleId = call.parameters["moduleId"].orEmpty()
            val definition = registry.definition(moduleId)
            if (definition == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respond(HttpStatusCode.OK, definition)
        }
    }

    registry.installRoutes(this)
}
