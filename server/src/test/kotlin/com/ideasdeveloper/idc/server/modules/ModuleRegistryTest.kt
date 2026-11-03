package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.ui.UiAppTheme
import com.ideasdeveloper.idc.ui.UiTheme
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleRegistryTest {
    private fun module(id: String, locked: Boolean = false) = object : ServerModule {
        override val definition = ModuleDefinition(id = id, displayName = id, description = id, locked = locked)
    }

    @Test
    fun routesOfEveryCompiledModuleAreRegisteredAtStartup() {
        val registry = ModuleRegistry(listOf(module("clientes", locked = true), module("hotspot")))

        assertFalse(registry.isInstalled("hotspot"))
        // Ktor routes cannot be added later, so optional modules get routes before they are installed.
        assertEquals(setOf("clientes", "hotspot"), registry.routedModules().map { it.definition.id }.toSet())
    }

    @Test
    fun installAndRemoveTakeEffectWithoutRestart() {
        val registry = ModuleRegistry(listOf(module("clientes", locked = true), module("hotspot")))

        registry.install("hotspot")
        assertTrue(registry.isInstalled("hotspot"))
        assertEquals(listOf("clientes", "hotspot"), registry.definitions.map { it.id })

        registry.remove("hotspot")
        assertFalse(registry.isInstalled("hotspot"))
        assertTrue(registry.isInstalled("clientes"))
        assertEquals(listOf("clientes"), registry.definitions.map { it.id })
    }

    @Test
    fun onlyInstalledModulesPublishTheirValidThemes() {
        val themed = object : ServerModule {
            override val definition = ModuleDefinition(id = "marca", displayName = "Marca", description = "Marca")
            override val themes = listOf(
                UiAppTheme("oceano", "Oceano", dark = true, colors = UiTheme(background = "#04131F")),
                UiAppTheme("roto", "Roto", dark = false, colors = UiTheme(surface = "white")),
            )
        }
        val registry = ModuleRegistry(listOf(module("clientes", locked = true), themed))
        assertEquals(emptyList(), registry.themes())

        registry.install("marca")
        assertEquals(listOf("marca.oceano"), registry.themes().map { it.id })
    }

    private fun tool(name: String, permission: String) = McpTool(name, name, name, permission) { JsonNull }

    @Test
    fun onlyInstalledModulesOfferTheirValidAgentTools() {
        val agentic = object : ServerModule {
            override val definition = ModuleDefinition(
                id = "ventas", displayName = "Ventas", description = "Ventas",
                permissions = listOf(ModulePermissionDefinition("ventas.view", "Ver", "Ver")),
            )
            override val mcp: ModuleMcp
                get() = ModuleMcp(
                    "Ventas registra pedidos.",
                    listOf(
                        tool("search_orders", "ventas.view"),
                        tool("search_orders", "ventas.view"),
                        tool("Bad-Name", "ventas.view"),
                        tool("delete_everything", "clientes.delete"),
                        tool("x".repeat(60), "ventas.view"),
                    ),
                )
        }
        val registry = ModuleRegistry(listOf(agentic))
        assertEquals(emptyList(), registry.mcpTools())

        registry.install("ventas")
        assertEquals(listOf("ventas_search_orders"), registry.mcpTools().map { it.qualifiedName })
        assertEquals("Ventas registra pedidos.", registry.mcp().single().instructions)
    }

    // Credentials are typed only in the app: no tool of any module may take one as an argument.
    @Test
    fun noModuleToolTakesCredentials() {
        val credential = Regex("(?i)(password|secret|token|api_?key|contrasena)")
        fun names(schema: JsonObject): List<String> {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            return properties.keys.toList() + properties.values.flatMap { property ->
                val nested = property as? JsonObject ?: return@flatMap emptyList()
                names(nested) + ((nested["items"] as? JsonObject)?.let(::names).orEmpty())
            }
        }
        val offending = ServiceLoader.load(ServerModuleProvider::class.java).map { it.create() }.flatMap { module ->
            module.mcp?.tools.orEmpty().flatMap { tool -> names(tool.inputSchema).filter { credential.containsMatchIn(it) }.map { "${module.definition.id}/${tool.name}: $it" } }
        }
        assertEquals(emptyList(), offending)
    }

    // A customer history behind a permission the module does not declare would never be shown.
    @Test
    fun everyCustomerTimelineUsesADeclaredPermission() {
        val modules = ServiceLoader.load(ServerModuleProvider::class.java).map { it.create() }
        val problems = modules.mapNotNull { module ->
            val timeline = module.customerTimeline ?: return@mapNotNull null
            "${module.definition.id}: ${timeline.permission}".takeIf { module.definition.permissions.none { it.id == timeline.permission } }
        }
        assertEquals(emptyList(), problems)
        // Clientes, CRM and hotspot report customer history.
        assertTrue(modules.filter { it.customerTimeline != null }.map { it.definition.id }.containsAll(listOf("clientes", "crm", "hotspot")))
    }

    // Every module compiled with the server: a tool the registry would drop is a bug of that module.
    @Test
    fun everyModuleToolFollowsTheRules() {
        val modules = ServiceLoader.load(ServerModuleProvider::class.java).map { it.create() }
        assertTrue(modules.isNotEmpty())
        val problems = modules.flatMap { module ->
            val mcp = module.mcp ?: return@flatMap emptyList()
            val tools = mcp.tools
            tools.mapNotNull { tool -> McpToolRules.problem(module.definition, tool)?.let { "${module.definition.id}/${tool.name}: $it" } } +
                tools.groupBy { it.name }.filterValues { it.size > 1 }.keys.map { "${module.definition.id}/$it: nombre repetido" } +
                listOfNotNull("${module.definition.id}: instrucciones vacias".takeIf { mcp.instructions.isBlank() })
        }
        assertEquals(emptyList(), problems)
    }
}
