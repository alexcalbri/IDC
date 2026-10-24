package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.ui.UiAppTheme
import com.ideasdeveloper.idc.ui.UiTheme
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
}
