package com.ideasdeveloper.idc.modules.hostpot

import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleViewDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

object HostpotServerModule : ServerModule {
    override val migrationPaths = emptyList<String>()

    override val definition = ModuleDefinition(
        id = HostpotModule.id,
        displayName = HostpotModule.displayName,
        description = "Modulo Hostpot pendiente.",
        locked = false,
        views = listOf(
            ModuleViewDefinition(
                id = "dashboard",
                title = "Hostpot",
                route = "/modules/hostpot",
                kind = "placeholder",
            )
        ),
    )

    override fun routes(route: Route) {
        route.route("/modules/hostpot") {
            get {
                call.respond(HttpStatusCode.OK, definition)
            }
        }
    }
}
