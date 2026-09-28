package com.ideasdeveloper.idc.server.modules

import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

interface ServerModule {
    val definition: ModuleDefinition
    val migrationModule: String
        get() = definition.id
    val migrationPaths: List<String>
        get() = emptyList()

    fun routes(route: Route, access: ModuleAccessService) {
    }
}

interface ServerModuleProvider {
    fun create(): ServerModule
}

@Serializable
// Contract for server-driven modules. New modules must provide enough metadata
// for the generic client module screen to render their views without a client
// release. Module-specific Compose screens are an explicit exception.
data class ModuleDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val version: String = "0.1.0",
    val locked: Boolean = false,
    val views: List<ModuleViewDefinition> = emptyList(),
    val permissions: List<ModulePermissionDefinition> = emptyList(),
)

@Serializable
data class ModuleViewDefinition(
    val id: String,
    val title: String,
    val route: String,
    val kind: String,
    val fields: List<ModuleFieldDefinition> = emptyList(),
    val actions: List<ModuleActionDefinition> = emptyList(),
)

@Serializable
data class ModulePermissionDefinition(
    val id: String,
    val title: String,
    val description: String,
)

@Serializable
data class ModuleFieldDefinition(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean = false,
    val dynamic: Boolean = false,
)

@Serializable
data class ModuleActionDefinition(
    val id: String,
    val title: String,
    val route: String,
    val method: String,
    val permission: String,
)

class ModuleRegistry(
    modules: List<ServerModule>,
    installedModuleIds: Set<String> = modules.filter { it.definition.locked }.map { it.definition.id }.toSet(),
) {
    private val modulesById = modules.associateBy { it.definition.id }.toMutableMap()
    private val externalDefinitionsById = mutableMapOf<String, ModuleDefinition>()
    private val installedIds = installedModuleIds.toMutableSet()

    val definitions: List<ModuleDefinition>
        get() = installedIds.mapNotNull { definitionOrExternal(it) }.sortedBy { it.id }

    val availableDefinitions: List<ModuleDefinition>
        get() = (modulesById.values.map { it.definition } + externalDefinitionsById.values)
            .distinctBy { it.id }
            .sortedBy { it.id }

    fun definition(moduleId: String): ModuleDefinition? =
        definitionOrExternal(moduleId)?.takeIf { installedIds.contains(moduleId) }

    fun availableDefinition(moduleId: String): ModuleDefinition? = definitionOrExternal(moduleId)

    fun module(moduleId: String): ServerModule? = modulesById[moduleId]?.takeIf { installedIds.contains(moduleId) }

    fun registerAvailable(definition: ModuleDefinition) {
        if (!modulesById.containsKey(definition.id)) {
            externalDefinitionsById[definition.id] = definition
        }
    }

    fun registerInstalled(definition: ModuleDefinition) {
        registerAvailable(definition)
        installedIds += definition.id
    }

    fun install(moduleId: String): ModuleDefinition? {
        val definition = modulesById[moduleId]?.definition ?: return null
        installedIds += moduleId
        return definition
    }

    fun remove(moduleId: String): ModuleDefinition? {
        val definition = modulesById[moduleId]?.definition ?: return null
        installedIds -= moduleId
        return definition
    }

    fun isInstalled(moduleId: String): Boolean = installedIds.contains(moduleId)

    fun installedModuleIds(): Set<String> = installedIds.toSet()

    fun installRoutes(route: Route, access: ModuleAccessService) {
        installedIds.mapNotNull { modulesById[it] }.forEach { module ->
            module.routes(route, access)
        }
    }

    private fun definitionOrExternal(moduleId: String): ModuleDefinition? =
        modulesById[moduleId]?.definition ?: externalDefinitionsById[moduleId]
}
