package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.McpToolRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Las herramientas para agentes: reglas del registro, permisos, nombres de argumentos y las ayudas puras. */
class MantenimientoMcpToolsTest {
    private val tools = MantenimientoMcpTools.tools
    private val definition = MantenimientoServerModule.definition

    private fun tool(name: String) = tools.first { it.name == name }

    private fun text(element: kotlinx.serialization.json.JsonElement?): String = (element as JsonPrimitive).content

    private fun propertyNames(schema: JsonObject): Set<String> = schema["properties"]?.jsonObject?.keys.orEmpty()

    @Test
    fun everyToolPassesTheRegistryRules() {
        tools.forEach { assertNull(McpToolRules.problem(definition, it), "tool ${it.name}") }
    }

    @Test
    fun toolNamesAreUniqueAndQualifiedNamesFit() {
        assertEquals(tools.size, tools.map { it.name }.toSet().size)
        tools.forEach { assertTrue("${definition.id}_${it.name}".length <= McpToolRules.MaxQualifiedNameLength) }
    }

    @Test
    fun expectedToolsExist() {
        val expected = setOf("list_orders", "get_order", "list_plans", "get_plan", "list_assets", "list_people", "upcoming", "create_order", "update_order", "close_order", "add_note")
        assertEquals(expected, tools.map { it.name }.toSet())
    }

    @Test
    fun readingToolsAreReadOnlyAndWritingToolsAreNot() {
        listOf("list_orders", "get_order", "list_plans", "get_plan", "list_assets", "list_people", "upcoming").forEach { assertTrue(tool(it).readOnly, it) }
        listOf("create_order", "update_order", "close_order", "add_note").forEach { assertFalse(tool(it).readOnly, it) }
        assertTrue(tools.none { it.destructive })
    }

    @Test
    fun everyToolDeclaresViewBecauseTheScopeIsCheckedPerOrder() {
        tools.forEach { assertEquals(MantenimientoModule.View, it.permission, it.name) }
    }

    @Test
    fun noArgumentIsACredentialOrTheActingCompany() {
        val forbidden = listOf("password", "token", "secret", "key", "acting_company")
        tools.forEach { tool ->
            propertyNames(tool.inputSchema).forEach { name ->
                forbidden.forEach { assertFalse(name.contains(it, ignoreCase = true), "${tool.name}.$name") }
            }
        }
    }

    @Test
    fun requiredArgumentsAreDeclaredProperties() {
        tools.forEach { tool ->
            val required = (tool.inputSchema["required"] as? JsonArray).orEmpty().map { text(it) }
            assertTrue(propertyNames(tool.inputSchema).containsAll(required), tool.name)
        }
    }

    @Test
    fun instructionsMentionTheFixedStatesAndTheFlow() {
        assertTrue(MantenimientoMcpTools.mcp.instructions.isNotBlank())
        listOf("list_assets", "create_order", "external_ref", "requested", "quoting", "open", "in_progress", "on_hold", "done", "rejected", "cancelled").forEach {
            assertTrue(it in MantenimientoMcpTools.Instructions, it)
        }
    }

    @Test
    fun enumArgumentsListTheFixedCodes() {
        fun enumOf(tool: String, argument: String): List<String> =
            (tool(tool).inputSchema["properties"]?.jsonObject?.get(argument)?.jsonObject?.get("enum") as JsonArray).map { text(it) }
        assertEquals(OrderStatus.entries.map { it.id }, enumOf("update_order", "status"))
        assertEquals(OrderStatus.entries.map { it.id }, enumOf("list_orders", "status"))
        assertEquals(OrderPriority.entries.map { it.id }, enumOf("create_order", "priority"))
        assertEquals(OrderKind.entries.map { it.id }, enumOf("list_orders", "kind"))
        // Las preventivas las genera un plan: un agente no las crea.
        assertEquals(listOf("corrective", "project"), enumOf("create_order", "kind"))
    }

    @Test
    fun createOrderOnlyRequiresTheTitleAndUpdateAndCloseAcceptIdOrNumber() {
        assertEquals(listOf("title"), (tool("create_order").inputSchema["required"] as JsonArray).map { text(it) })
        listOf("get_order", "update_order", "close_order", "add_note").forEach {
            assertTrue(propertyNames(tool(it).inputSchema).containsAll(listOf("id", "number")), it)
        }
        assertEquals(listOf("body"), (tool("add_note").inputSchema["required"] as JsonArray).map { text(it) })
        assertTrue("external_ref" in propertyNames(tool("create_order").inputSchema))
    }

    @Test
    fun theLimitIsCappedAtOneHundred() {
        val limit = tool("list_orders").inputSchema["properties"]!!.jsonObject["limit"]!!.jsonObject
        assertEquals("100", text(limit["maximum"]))
        val days = tool("upcoming").inputSchema["properties"]!!.jsonObject["days"]!!.jsonObject
        assertEquals("90", text(days["maximum"]))
    }

    // ---- ayudas ----

    private val ana = MantenimientoUser(UUID.randomUUID(), "Ana Ruiz", "ana@example.com")
    private val beto = MantenimientoUser(UUID.randomUUID(), "Beto", null)
    private val users = listOf(ana, beto)

    @Test
    fun peopleResolveByIdEmailOrName() {
        assertEquals(ana.id, MantenimientoMcpTools.resolveUser("ANA@example.com", users))
        assertEquals(beto.id, MantenimientoMcpTools.resolveUser(beto.id.toString(), users))
        assertEquals(ana.id, MantenimientoMcpTools.resolveUser(" ana ruiz ", users))
    }

    @Test
    fun unknownPersonIsAnErrorThatListsThePeople() {
        val failure = assertFailsWith<McpToolException> { MantenimientoMcpTools.resolveUser("Zoe", users) }
        assertTrue("Zoe" in failure.message.orEmpty())
        assertTrue("Beto" in failure.message.orEmpty())
    }

    @Test
    fun propertiesResolveByNameOrIdAndSkipArchived() {
        val casa = Property(UUID.randomUUID(), "Casa Mar", 0, false)
        val vieja = Property(UUID.randomUUID(), "Vieja", 1, true)
        val all = listOf(casa, vieja)
        assertEquals(casa.id, MantenimientoMcpTools.resolveProperty("casa mar", all))
        assertEquals(casa.id, MantenimientoMcpTools.resolveProperty(casa.id.toString(), all))
        val failure = assertFailsWith<McpToolException> { MantenimientoMcpTools.resolveProperty("Vieja", all) }
        assertTrue("Casa Mar" in failure.message.orEmpty())
        assertFalse("Vieja." in failure.message.orEmpty().substringAfter("Propiedades:"))
    }

    @Test
    fun enumValuesAreValidated() {
        assertEquals(OrderStatus.OnHold, MantenimientoMcpTools.statusOf("on_hold"))
        assertEquals(OrderPriority.Urgent, MantenimientoMcpTools.priorityOf("URGENT"))
        assertEquals(OrderKind.Project, MantenimientoMcpTools.kindOf("project"))
        assertFailsWith<McpToolException> { MantenimientoMcpTools.statusOf("hecha") }
        assertFailsWith<McpToolException> { MantenimientoMcpTools.priorityOf("critica") }
        // Un agente no crea órdenes preventivas.
        assertFailsWith<McpToolException> { MantenimientoMcpTools.kindOf("preventive") }
    }

    @Test
    fun externalRefBecomesAnAgentImportKeyWithinTheColumnLimit() {
        assertEquals("agent:avería-12", MantenimientoMcpTools.importKeyOf(" avería-12 "))
        assertTrue(MantenimientoMcpTools.importKeyOf("x".repeat(500)).length <= 200)
    }

    @Test
    fun orderJsonShowsFolioStatePeopleAndNoFileReferences() {
        val order = MantenimientoFixtures.order(
            status = OrderStatus.OnHold,
            assigneeId = ana.id,
            dueDate = LocalDate.of(2026, 11, 1),
        ).copy(checklistDone = 1, checklistTotal = 3, propertyName = "Casa Mar", assetName = "Bomba")
        val json = MantenimientoMcpTools.orderJson(order, users.associate { it.id to it.label }, LocalDate.of(2026, 11, 18))
        assertEquals("OT-0007", text(json["number"]))
        assertEquals("on_hold", text(json["status"]))
        assertEquals("En espera", text(json["status_label"]))
        assertEquals("1/3", text(json["checklist"]))
        assertEquals("true", text(json["overdue"]))
        assertEquals("Casa Mar", text(json["property"]))
        assertEquals("Ana Ruiz", text(json["assignee"]?.jsonObject?.get("name")))
        assertFalse(json.containsKey("path"))
        assertFalse(json.containsKey("backend"))
    }

    @Test
    fun orderJsonNamesAnAssigneeWhoLostAccess() {
        val order = MantenimientoFixtures.order(assigneeId = UUID.randomUUID())
        val json = MantenimientoMcpTools.orderJson(order, emptyMap(), LocalDate.of(2026, 11, 18))
        assertEquals("Usuario sin acceso a Mantenimiento", text(json["assignee"]?.jsonObject?.get("name")))
    }

    @Test
    fun aClosedOrderIsNeverOverdue() {
        val order = MantenimientoFixtures.order(status = OrderStatus.Done, dueDate = LocalDate.of(2020, 1, 1))
        assertEquals("false", text(MantenimientoMcpTools.orderJson(order, emptyMap(), LocalDate.of(2026, 11, 18))["overdue"]))
    }
}
