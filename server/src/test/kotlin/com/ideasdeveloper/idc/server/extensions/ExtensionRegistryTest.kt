package com.ideasdeveloper.idc.server.extensions

import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNull

class ExtensionRegistryTest {
    private fun extension(id: String, module: String = "crm") = object : ServerExtension {
        override val manifest = ExtensionManifest(id = id, module = module, displayName = id, version = "0.1.0")
    }

    private fun module(id: String, vararg extensions: ServerExtension) = object : ServerModule {
        override val definition = ModuleDefinition(id = id, displayName = id, description = id)
        override val bundledExtensions = extensions.toList()
    }

    @Test
    fun joinsBundledExtensionsOfModulesWithThoseOfPackages() {
        val registry = ExtensionRegistry.of(listOf(module("crm", extension("meta"), extension("wordpress"))), listOf(extension("zapier")))

        assertEquals(listOf("meta", "wordpress", "zapier"), registry.all.map { it.manifest.id })
        assertNotNull(registry.get("meta"))
        assertNull(registry.get("nope"))
        assertEquals(listOf("meta", "wordpress", "zapier"), registry.forModule("crm").map { it.manifest.id })
        assertEquals(emptyList(), registry.forModule("hotspot"))
    }

    @Test
    fun duplicatedOrInvalidIdsAreSkippedAndTheFirstWins() {
        val first = extension("meta")
        val registry = ExtensionRegistry(listOf(first, extension("meta"), extension("Bad-Id"), extension("wordpress")))

        assertEquals(listOf("meta", "wordpress"), registry.all.map { it.manifest.id })
        assertSame(first, registry.get("meta"))
    }
}
