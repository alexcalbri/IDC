package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.ui.ModuleUiEntry
import java.util.concurrent.ConcurrentHashMap
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

interface ServerModule {
    val definition: ModuleDefinition
    val migrationModule: String
        get() = definition.id
    val migrationPaths: List<String>
        get() = emptyList()
    // Tenant tables created by this module's migrations that the runtime role must read and write.
    val runtimeTables: List<String>
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
    // How clients show the module: native pieces (UiScreen) or the module's own web pages.
    val ui: ModuleUiEntry? = null,
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
    // Values allowed by "select" fields.
    val options: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val help: String = "",
    // Consecutive fields with the same section are grouped under one heading.
    val section: String = "",
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
    // Modules loaded from packages built on this server (not compiled into the distribution).
    private val packagedModuleIds: Set<String> = emptySet(),
) {
    private val modulesById = modules.associateBy { it.definition.id }.toMutableMap()
    private val externalDefinitionsById = ConcurrentHashMap<String, ModuleDefinition>()
    // Read on every module request and changed when the server owner installs or removes modules.
    private val installedIds: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(installedModuleIds) }

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

    // Whether the module's code is loaded in this process (compiled in or from a package).
    fun hasCode(moduleId: String): Boolean = modulesById.containsKey(moduleId)

    fun isPackaged(moduleId: String): Boolean = moduleId in packagedModuleIds

    fun loadedVersion(moduleId: String): String? = modulesById[moduleId]?.definition?.version

    fun installedModuleIds(): Set<String> = installedIds.toSet()

    /**
     * Registers the routes of every module compiled into the server, installed or not, because
     * Ktor routes cannot be added after startup. Installing or removing a module therefore takes
     * effect immediately: ModuleAccessService rejects requests to modules that are not installed.
     */
    fun installRoutes(route: Route, access: ModuleAccessService) {
        routedModules().forEach { module ->
            module.routes(route, access)
        }
    }

    internal fun routedModules(): Collection<ServerModule> = modulesById.values

    private fun definitionOrExternal(moduleId: String): ModuleDefinition? =
        modulesById[moduleId]?.definition ?: externalDefinitionsById[moduleId]
}
