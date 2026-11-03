package com.ideasdeveloper.idc.server.company.application

import com.ideasdeveloper.idc.server.modules.McpToolRules
import com.ideasdeveloper.idc.server.modules.ServerMcpModuleId
import com.ideasdeveloper.idc.server.modules.UserModuleAccess
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerMcpToolsTest {
    private val tools = ServerMcpTools.tools { error("Building the tools must not touch the server") }

    @Test
    fun toolsFollowTheModuleRulesAndTakeNoCredentials() {
        val namePattern = Regex("^[a-z][a-z0-9_]*$")
        val credential = Regex("(?i)(password|secret|token|api_?key|contrasena)")
        assertTrue(tools.all { namePattern.matches(it.name) && "${ServerMcpModuleId}_${it.name}".length <= McpToolRules.MaxQualifiedNameLength })
        assertEquals(tools.size, tools.map { it.name }.toSet().size)
        val arguments = tools.flatMap { tool -> ((tool.inputSchema["properties"] as? JsonObject)?.keys.orEmpty()).map { "${tool.name}/$it" } }
        assertEquals(emptyList(), arguments.filter { credential.containsMatchIn(it) })
    }

    // Only server owners get the server's own tools; module tools still need the module enabled.
    @Test
    fun onlyServerOwnersGetTheServerTools() {
        val userId = UUID.randomUUID()
        val owner = UserModuleAccess(ServerWorkspace.Code, userId, true, serverOwner = true, emptySet(), emptySet()) { true }
        val businessOwner = UserModuleAccess("acme", userId, true, serverOwner = false, emptySet(), setOf("crm")) { true }
        assertNotNull(owner.permissions(ServerMcpModuleId))
        assertNull(owner.permissions("crm"))
        assertNull(businessOwner.permissions(ServerMcpModuleId))
        assertNotNull(businessOwner.permissions("crm"))
    }
}
