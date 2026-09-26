package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleViewDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

object CrmServerModule : ServerModule {
    override val migrationPaths = emptyList<String>()

    override val definition = ModuleDefinition(
        id = CrmModule.id,
        displayName = CrmModule.displayName,
        description = "Modulo CRM pendiente.",
        locked = false,
        views = listOf(
            ModuleViewDefinition(
                id = "dashboard",
                title = "CRM",
                route = "/modules/crm",
                kind = "placeholder",
            )
        ),
    )

    override fun routes(route: Route) {
        route.route("/modules/crm") {
            get {
                call.respond(HttpStatusCode.OK, definition)
            }
        }
    }
}
