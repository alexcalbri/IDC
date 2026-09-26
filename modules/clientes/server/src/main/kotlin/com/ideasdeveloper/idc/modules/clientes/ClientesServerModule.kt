package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleViewDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

object ClientesServerModule : ServerModule {
    override val migrationPaths = listOf(
        "modules/clientes/migrations/V001__create_customers.sql",
    )

    override val definition = ModuleDefinition(
        id = ClientesModule.id,
        displayName = ClientesModule.displayName,
        description = "Gestiona la identidad compartida de clientes.",
        locked = true,
        views = listOf(
            ModuleViewDefinition(
                id = "customers",
                title = "Clientes",
                route = "/modules/clientes/customers",
                kind = "list",
            )
        ),
    )

    override fun routes(route: Route) {
        route.route("/modules/clientes") {
            get("/customers") {
                call.respond(HttpStatusCode.OK, emptyList<String>())
            }
        }
    }
}
