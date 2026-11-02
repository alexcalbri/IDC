package com.ideasdeveloper.idc.modules.agentes

import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.RegisteredMcpTool
import com.ideasdeveloper.idc.server.modules.RegisteredModuleMcp
import com.ideasdeveloper.idc.server.modules.UserModuleAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentesMcpTest {
    private val userId = UUID.randomUUID()

    private fun tool(name: String, permission: String, readOnly: Boolean = true) =
        McpTool(name, name, "Hace $name.", permission, readOnly = readOnly) { JsonPrimitive("ok:$name") }

    private val catalog = listOf(
        RegisteredMcpTool("clientes", "Clientes", tool("search_customers", "clientes.view")),
        RegisteredMcpTool("clientes", "Clientes", tool("create_customer", "clientes.create", readOnly = false)),
        RegisteredMcpTool("crm", "CRM", tool("search_leads", "crm.view")),
        RegisteredMcpTool("hotspot", "Hotspot", tool("reports", "hotspot.reports.view")),
    )

    private fun token(modules: Set<String>? = null, readOnly: Boolean = false) = AgentToken(
        id = UUID.randomUUID(), userId = userId, serverOwner = false, userName = "ana", name = "Claude",
        hint = "abcdef", allowedModules = modules, readOnly = readOnly, createdAt = Instant.now(),
        expiresAt = null, lastUsedAt = null, revokedAt = null, revokedBy = null,
    )

    private fun access(granted: Set<String>, enabled: Set<String> = setOf("clientes", "crm"), owner: Boolean = false) =
        UserModuleAccess("acme", userId, owner, serverOwner = false, granted, enabled) { true }

    private fun names(tools: List<VisibleTool>) = tools.map { it.name }.toSet()

    @Test
    fun agentSeesOnlyToolsItsUserIsAllowedToUse() {
        val visible = AgentesMcp.visibleTools(catalog, token(), access(setOf("clientes.view", "crm.view", "hotspot.reports.view")))
        // No clientes.create permission, and hotspot is not enabled for the company.
        assertEquals(setOf("clientes_search_customers", "crm_search_leads"), names(visible))
    }

    @Test
    fun businessOwnerSeesEveryToolOfEnabledModules() {
        val visible = AgentesMcp.visibleTools(catalog, token(), access(emptySet(), owner = true))
        assertEquals(setOf("clientes_search_customers", "clientes_create_customer", "crm_search_leads"), names(visible))
    }

    @Test
    fun tokenNarrowsModulesAndWrites() {
        val owner = access(emptySet(), owner = true)
        assertEquals(setOf("crm_search_leads"), names(AgentesMcp.visibleTools(catalog, token(modules = setOf("crm")), owner)))
        assertEquals(setOf("clientes_search_customers", "crm_search_leads"), names(AgentesMcp.visibleTools(catalog, token(readOnly = true), owner)))
    }

    // The bridge only relays what each module says about itself, and only for modules the agent can use.
    @Test
    fun instructionsComeFromTheUsableModules() {
        val modules = catalog.groupBy { it.moduleId }.map { (id, tools) -> RegisteredModuleMcp(id, tools.first().moduleName, "Instrucciones de $id.", tools) }
        val visible = AgentesMcp.visibleTools(catalog, token(), access(setOf("clientes.view")))
        val text = AgentesMcp.instructions("Puente.", modules, visible)
        assertEquals("Puente.\n\n## Clientes\nInstrucciones de clientes.", text)
    }

    private fun protocol(granted: Set<String>, denied: MutableList<String> = mutableListOf()): AgentesMcp {
        val visible = AgentesMcp.visibleTools(catalog, token(), access(granted))
        return AgentesMcp(
            instructions = "test",
            tools = { visible },
            invoke = { tool, arguments -> if (arguments.containsKey("fail")) ToolOutcome.Failure("fallo") else ToolOutcome.Success(JsonPrimitive(tool.name)) },
            denied = { denied += it },
        )
    }

    private fun request(id: Int, method: String, params: String = "{}") =
        Json.parseToJsonElement("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")

    @Test
    fun initializeNegotiatesTheProtocolVersion() {
        val known = protocol(emptySet()).handle(request(1, "initialize", """{"protocolVersion":"2025-03-26"}"""))!!.jsonObject
        assertEquals("2025-03-26", known["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        val unknown = protocol(emptySet()).handle(request(1, "initialize", """{"protocolVersion":"1999-01-01"}"""))!!.jsonObject
        assertEquals(AgentesMcp.SupportedVersions.first(), unknown["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun notificationsGetNoAnswer() {
        assertNull(protocol(emptySet()).handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")))
    }

    @Test
    fun toolsListReturnsVisibleToolsWithAnnotations() {
        val answer = protocol(setOf("clientes.view")).handle(request(2, "tools/list"))!!.jsonObject
        val tools = answer["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val tool = tools.first().jsonObject
        assertEquals("clientes_search_customers", tool["name"]!!.jsonPrimitive.content)
        assertTrue(tool["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun callingAToolOutsideThePermissionsIsRejectedAndRecorded() {
        val denied = mutableListOf<String>()
        val answer = protocol(setOf("clientes.view"), denied).handle(request(3, "tools/call", """{"name":"clientes_create_customer","arguments":{}}"""))!!.jsonObject
        assertEquals(AgentesMcp.InvalidParams, answer["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(listOf("clientes_create_customer"), denied)
    }

    @Test
    fun toolResultsAndToolErrors() {
        val ok = protocol(setOf("clientes.view")).handle(request(4, "tools/call", """{"name":"clientes_search_customers"}"""))!!.jsonObject["result"]!!.jsonObject
        assertEquals(false, ok["isError"]!!.jsonPrimitive.boolean)
        val failed = protocol(setOf("clientes.view")).handle(request(5, "tools/call", """{"name":"clientes_search_customers","arguments":{"fail":true}}"""))!!.jsonObject["result"]!!.jsonObject
        assertEquals(true, failed["isError"]!!.jsonPrimitive.boolean)
        assertEquals("fallo", failed["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownMethodsAndBatches() {
        val mcp = protocol(emptySet())
        val unknown = mcp.handle(request(6, "sampling/createMessage"))!!.jsonObject
        assertEquals(AgentesMcp.MethodNotFound, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val batch = mcp.handle(JsonArray(listOf(request(7, "ping"), Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"notifications/x"}"""))))
        assertEquals(1, (batch as JsonArray).size)
        assertEquals(JsonObject(emptyMap()), batch.first().jsonObject["result"])
    }

    @Test
    fun tokenFormat() {
        val secret = "A".repeat(43)
        assertEquals("acme_2", AgentTokens.companyOf("idcmcp.acme_2.$secret"))
        assertNull(AgentTokens.companyOf("idcmcp.Acme.$secret"))
        assertNull(AgentTokens.companyOf("idcmcp.acme.short"))
        assertNull(AgentTokens.companyOf("other.acme.$secret"))
        assertNull(AgentTokens.companyOf(""))
    }

    @Test
    fun newTokenForm() {
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val all = AgentesScreens.parseNewToken(mapOf("name" to " Claude ", "m_crm" to "true", "m_clientes" to "true", "expires" to "30 dias"), listOf("clientes", "crm"), now)
        assertEquals("Claude", all.name)
        // Every module chosen: the agent also gets modules enabled later.
        assertNull(all.allowedModules)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), all.expiresAt)

        val some = AgentesScreens.parseNewToken(mapOf("name" to "x", "m_crm" to "true", "readOnly" to "true", "expires" to "Sin vencimiento"), listOf("clientes", "crm"), now)
        assertEquals(setOf("crm"), some.allowedModules)
        assertTrue(some.readOnly)
        assertNull(some.expiresAt)

        assertFailsWith<AgentesValidationException> { AgentesScreens.parseNewToken(mapOf("name" to "x", "expires" to "30 dias"), listOf("crm"), now) }
        assertFailsWith<AgentesValidationException> { AgentesScreens.parseNewToken(mapOf("name" to "", "m_crm" to "true", "expires" to "30 dias"), listOf("crm"), now) }
    }

    @Test
    fun rateLimiterResetsEveryMinute() {
        var now = 0L
        val limiter = AgentRateLimiter(perMinute = 2) { now }
        assertTrue(limiter.allow("a"))
        assertTrue(limiter.allow("a"))
        assertEquals(false, limiter.allow("a"))
        assertTrue(limiter.allow("b"))
        now = 60_000
        assertTrue(limiter.allow("a"))
    }
}
