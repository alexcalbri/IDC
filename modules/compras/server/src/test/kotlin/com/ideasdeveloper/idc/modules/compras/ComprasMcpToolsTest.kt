package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComprasMcpToolsTest {
    private val tools = ComprasMcpTools.mcp.tools
    private val declared = setOf(ComprasModule.View, ComprasModule.Create, ComprasModule.ViewAll, ComprasModule.Approve, ComprasModule.Manage)
    private val namePattern = Regex("^[a-z][a-z0-9_]*$")

    private fun tool(name: String): McpTool = tools.single { it.name == name }

    private fun required(tool: McpTool): Set<String> =
        (tool.inputSchema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    private fun properties(tool: McpTool): JsonObject = tool.inputSchema["properties"]!!.jsonObject

    @Test
    fun exposesTheSevenTools() {
        assertEquals(
            setOf("create_request", "list_requests", "get_request", "submit_request", "approve_request", "reject_request", "purchase_summary"),
            tools.map { it.name }.toSet(),
        )
        assertEquals(tools.size, tools.map { it.name }.toSet().size)
    }

    @Test
    fun namesAreValidAndQualifiedNamesFitMcpClients() {
        tools.forEach {
            assertTrue(namePattern.matches(it.name), it.name)
            assertTrue("${ComprasModule.id}_${it.name}".length <= McpToolRules.MaxQualifiedNameLength, it.name)
        }
    }

    @Test
    fun everyToolUsesADeclaredPermission() {
        tools.forEach { assertTrue(it.permission in declared, "${it.name}: ${it.permission}") }
    }

    @Test
    fun permissionsMatchTheDesign() {
        assertEquals(ComprasModule.Create, tool("create_request").permission)
        assertEquals(ComprasModule.Create, tool("submit_request").permission)
        assertEquals(ComprasModule.View, tool("list_requests").permission)
        assertEquals(ComprasModule.View, tool("get_request").permission)
        assertEquals(ComprasModule.Approve, tool("approve_request").permission)
        assertEquals(ComprasModule.Approve, tool("reject_request").permission)
        assertEquals(ComprasModule.ViewAll, tool("purchase_summary").permission)
    }

    @Test
    fun onlyReadingToolsAreReadOnlyAndNoneIsDestructive() {
        val reading = setOf("list_requests", "get_request", "purchase_summary")
        tools.forEach {
            assertEquals(it.name in reading, it.readOnly, it.name)
            assertFalse(it.destructive, it.name)
        }
    }

    @Test
    fun requiredArgumentsAreDeclaredInTheSchema() {
        assertEquals(setOf("title"), required(tool("create_request")))
        assertEquals(setOf("id"), required(tool("get_request")))
        assertEquals(setOf("id"), required(tool("submit_request")))
        assertEquals(setOf("id"), required(tool("approve_request")))
        assertEquals(setOf("id", "reason"), required(tool("reject_request")))
        assertTrue(required(tool("list_requests")).isEmpty())
        tools.forEach { tool ->
            assertTrue(properties(tool).keys.containsAll(required(tool)), tool.name)
            assertEquals(JsonPrimitive(false), tool.inputSchema["additionalProperties"], tool.name)
        }
    }

    @Test
    fun amountsTravelAsDecimalTextNeverAsNumbers() {
        val amount = properties(tool("create_request"))["estimated_amount"]!!.jsonObject
        assertEquals("string", amount["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun noArgumentLooksLikeACredentialOrChoosesTheCompany() {
        val forbidden = Regex("password|passwd|secret|token|api_?key|credential|acting_company", RegexOption.IGNORE_CASE)
        tools.forEach { tool -> properties(tool).keys.forEach { assertFalse(forbidden.containsMatchIn(it), "${tool.name}: $it") } }
    }

    @Test
    fun everyToolHasATitleAndADescriptionAndTheModuleHasInstructions() {
        tools.forEach {
            assertTrue(it.title.isNotBlank(), it.name)
            assertTrue(it.description.isNotBlank(), it.name)
        }
        assertTrue(ComprasMcpTools.mcp.instructions.isNotBlank())
    }
}
