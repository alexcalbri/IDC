package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientesMcpToolsTest {
    private val tools = ClientesMcpTools.mcp.tools
    private val declared = ClientesServerModule.definition.permissions.map { it.id }.toSet()

    private fun tool(name: String): McpTool = tools.single { it.name == name }

    private fun required(tool: McpTool): Set<String> =
        (tool.inputSchema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    private fun properties(tool: McpTool): JsonObject = tool.inputSchema["properties"]!!.jsonObject

    @Test
    fun exposesTheLinkingRestoreAndFieldTools() {
        val names = tools.map { it.name }.toSet()
        assertEquals(tools.size, names.size)
        listOf("get_company_links", "link_company", "unlink_company", "list_merge_reviews", "separate_merge", "restore_customer", "delete_customer_field")
            .forEach { assertTrue(it in names, it) }
    }

    @Test
    fun everyToolPassesTheRegistryRules() {
        tools.forEach { assertNull(McpToolRules.problem(ClientesServerModule.definition, it), it.name) }
        tools.forEach { assertTrue(it.permission in declared, it.name) }
    }

    @Test
    fun permissionsOfTheNewTools() {
        assertEquals("clientes.delete", tool("restore_customer").permission)
        assertEquals("clientes.fields.manage", tool("delete_customer_field").permission)
        listOf("get_company_links", "link_company", "unlink_company", "list_merge_reviews", "separate_merge")
            .forEach { assertEquals("clientes.share", tool(it).permission, it) }
    }

    @Test
    fun readingToolsAreReadOnlyAndWritingOnesAreNot() {
        listOf("get_company_links", "list_merge_reviews").forEach { assertTrue(tool(it).readOnly, it) }
        listOf("link_company", "unlink_company", "separate_merge", "restore_customer", "delete_customer_field").forEach { assertFalse(tool(it).readOnly, it) }
    }

    @Test
    fun requiredArgumentsAreInTheSchema() {
        assertEquals(setOf("companyCode"), required(tool("link_company")))
        assertEquals(setOf("companyCode"), required(tool("unlink_company")))
        assertEquals(setOf("companyCode", "mergeId"), required(tool("separate_merge")))
        assertEquals(setOf("id"), required(tool("restore_customer")))
        assertEquals(setOf("id"), required(tool("delete_customer_field")))
        tools.forEach {
            assertTrue(properties(it).keys.containsAll(required(it)), it.name)
            assertEquals(JsonPrimitive(false), it.inputSchema["additionalProperties"], it.name)
        }
    }

    @Test
    fun noToolHandlesCredentials() {
        val forbidden = Regex("password|passwd|secret|token|api_?key|credential", RegexOption.IGNORE_CASE)
        tools.forEach { tool -> properties(tool).keys.forEach { assertFalse(forbidden.containsMatchIn(it), "${tool.name}: $it") } }
    }

    @Test
    fun instructionsExplainLinkingAndRestoring() {
        val text = ClientesMcpTools.mcp.instructions
        assertTrue(text.contains("business owner"))
        assertTrue(text.contains("restore_customer"))
    }

    @Test
    fun theOwnerCheckRejectsWhoIsNotBusinessOwnerEvenWithClientesShare() {
        val user = ModulePermissions("acme", "t", false, setOf("clientes.share", "clientes.view"), userId = UUID.randomUUID())
        assertFailsWith<McpToolException> { ClientesMcpTools.requireBusinessOwner(user) }
        ClientesMcpTools.requireBusinessOwner(ModulePermissions("acme", "t", true, emptySet(), userId = UUID.randomUUID()))
    }

    // Every linking and merging tool refuses a user who only holds clientes.share before touching the database or Core.
    @Test
    fun everyLinkingToolRefusesANonOwnerBeforeDoingAnything() {
        val user = ModulePermissions("acme", "t", false, setOf("clientes.share"), userId = UUID.randomUUID())
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        // Neither the connection nor the access service may be used: they are never initialised.
        val access = unsafe.allocateInstance(ModuleAccessService::class.java) as ModuleAccessService
        val tenant = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> error("la base no debe usarse") } as Connection
        val call = McpToolCall(JsonObject(emptyMap()), user, tenant, access, "agente", "clientes")
        listOf("get_company_links", "link_company", "unlink_company", "list_merge_reviews", "separate_merge").forEach { name ->
            val failure = assertFailsWith<McpToolException>(name) { tool(name).handler(call) }
            assertTrue(failure.message!!.contains("business owner"), name)
        }
    }
}
