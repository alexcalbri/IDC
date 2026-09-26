package com.ideasdeveloper.idc.server.modules

import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

interface ServerModule {
    val definition: ModuleDefinition
    val migrationModule: String
        get() = definition.id
    val migrationPaths: List<String>
        get() = emptyList()

    fun routes(route: Route) {
    }
}

@Serializable
data class ModuleDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val locked: Boolean = false,
    val views: List<ModuleViewDefinition> = emptyList(),
)

@Serializable
data class ModuleViewDefinition(
    val id: String,
    val title: String,
    val route: String,
    val kind: String,
)

class ModuleRegistry(
    modules: List<ServerModule>,
) {
    private val modulesById = modules.associateBy { it.definition.id }

    val definitions: List<ModuleDefinition> =
        modulesById.values.map { it.definition }.sortedBy { it.id }

    fun definition(moduleId: String): ModuleDefinition? = modulesById[moduleId]?.definition

    fun module(moduleId: String): ServerModule? = modulesById[moduleId]

    fun installRoutes(route: Route) {
        modulesById.values.forEach { module ->
            module.routes(route)
        }
    }
}
