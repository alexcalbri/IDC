package com.ideasdeveloper.idc.server.extensions

import com.ideasdeveloper.idc.server.modules.McpToolRules
import com.ideasdeveloper.idc.server.modules.ServerMcpModuleId
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionMcpToolsTest {
    private val tools = ExtensionMcpTools.tools(
        service = { error("Building the tools must not touch the server") },
        packages = { error("Building the tools must not touch the server") },
        role = { error("Building the tools must not touch the server") },
    )

    @Test
    fun offersTheExpectedToolsWithValidNames() {
        assertEquals(
            setOf(
                "list_extensions", "install_extension", "configure_extension", "set_extension_enabled",
                "preview_extension_uninstall", "uninstall_extension", "apply_extension_to_group", "list_extension_packages",
            ),
            tools.map { it.name }.toSet(),
        )
        val namePattern = Regex("^[a-z][a-z0-9_]*$")
        assertTrue(tools.all { namePattern.matches(it.name) && "${ServerMcpModuleId}_${it.name}".length <= McpToolRules.MaxQualifiedNameLength })
    }

    @Test
    fun noToolTakesCredentialsOrManagesPackages() {
        val credential = Regex("(?i)(password|secret|token|api_?key|contrasena|signature|sha256)")
        val arguments = tools.flatMap { tool -> ((tool.inputSchema["properties"] as? JsonObject)?.keys.orEmpty()).map { "${tool.name}/$it" } }
        assertEquals(emptyList(), arguments.filter { credential.containsMatchIn(it) })
        // Installing packages and authorizing unsigned ones stay in the app.
        assertFalse(tools.any { it.name.contains("authorize") || it.name == "install_extension_package" })
    }

    @Test
    fun readingToolsAreReadOnlyAndRemovingOnesAreDestructive() {
        val byName = tools.associateBy { it.name }
        listOf("list_extensions", "preview_extension_uninstall", "list_extension_packages").forEach { assertTrue(byName.getValue(it).readOnly, it) }
        listOf("uninstall_extension", "apply_extension_to_group").forEach { assertTrue(byName.getValue(it).destructive, it) }
        assertFalse(byName.getValue("configure_extension").readOnly)
    }
}
