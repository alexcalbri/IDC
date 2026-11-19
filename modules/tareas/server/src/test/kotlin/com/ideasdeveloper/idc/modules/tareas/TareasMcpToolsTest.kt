package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.McpToolRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tools offered to agents: rules of the registry, permissions, argument names and the pure helpers. */
class TareasMcpToolsTest {
    private val tools = TareasMcpTools.mcp.tools
    private val definition = TareasServerModule.definition

    private fun tool(name: String) = tools.first { it.name == name }

    private fun text(element: kotlinx.serialization.json.JsonElement?): String = (element as JsonPrimitive).content

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
        val expected = setOf("list_boards", "list_people", "list_tasks", "get_task", "create_task", "update_task", "complete_task", "add_comment", "set_checklist_item")
        assertEquals(expected, tools.map { it.name }.toSet())
    }

    @Test
    fun readingToolsAreReadOnlyAndWritingToolsAreNot() {
        listOf("list_boards", "list_people", "list_tasks", "get_task").forEach { assertTrue(tool(it).readOnly, it) }
        listOf("create_task", "update_task", "complete_task", "add_comment", "set_checklist_item").forEach { assertFalse(tool(it).readOnly, it) }
        assertTrue(tools.none { it.destructive })
    }

    @Test
    fun permissionsMatchTheDesign() {
        listOf("list_boards", "list_people", "list_tasks", "get_task", "complete_task", "add_comment", "set_checklist_item", "update_task")
            .forEach { assertEquals(TareasModule.View, tool(it).permission, it) }
        assertEquals(TareasModule.Manage, tool("create_task").permission)
        assertTrue(tools.none { it.permission == TareasModule.Boards })
    }

    private fun propertyNames(schema: JsonObject): Set<String> = schema["properties"]?.jsonObject?.keys.orEmpty()

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
        assertTrue(TareasMcpTools.mcp.instructions.isNotBlank())
        listOf("list_boards", "list_people", "create_task", "external_ref", "pending", "in_progress", "review", "done", "cancelled").forEach {
            assertTrue(it in TareasMcpTools.Instructions, it)
        }
    }

    @Test
    fun enumArgumentsListTheFixedCodes() {
        fun enumOf(tool: String, argument: String): List<String> =
            (tool(tool).inputSchema["properties"]?.jsonObject?.get(argument)?.jsonObject?.get("enum") as JsonArray).map { text(it) }
        assertEquals(TaskStatus.entries.map { it.code }, enumOf("update_task", "status"))
        assertEquals(TaskStatus.entries.map { it.code }, enumOf("list_tasks", "status"))
        assertEquals(TaskPriority.entries.map { it.code }, enumOf("create_task", "priority"))
        assertEquals(DueFilter.entries.map { it.code }, enumOf("list_tasks", "due"))
    }

    // ---- helpers ----

    private val ana = TareasUser(UUID.randomUUID(), "Ana Ruiz", "ana@example.com")
    private val beto = TareasUser(UUID.randomUUID(), "Beto", "")
    private val users = listOf(ana, beto)

    @Test
    fun peopleResolveByIdEmailOrNameWithoutRepeats() {
        val ids = TareasMcpTools.resolvePeople(listOf("ANA@example.com", beto.id.toString(), "ana ruiz", " beto "), users)
        assertEquals(listOf(ana.id, beto.id), ids)
    }

    @Test
    fun unknownPersonIsAnErrorThatListsThePeople() {
        val failure = assertFailsWith<McpToolException> { TareasMcpTools.resolvePeople(listOf("Ana Ruiz", "Zoe"), users) }
        assertTrue("Zoe" in failure.message.orEmpty())
        assertTrue("Beto" in failure.message.orEmpty())
    }

    @Test
    fun blankReferencesAreIgnored() {
        assertEquals(emptyList(), TareasMcpTools.resolvePeople(listOf("", "  "), users))
    }

    @Test
    fun textListAcceptsArraysAndPlainText() {
        val arguments = buildJsonObject {
            put("people", JsonArray(listOf(JsonPrimitive(" Ana "), JsonPrimitive(""), JsonNull, JsonPrimitive("Beto"))))
            put("plain", "Ana, Beto;Carla\nDiego")
            put("nothing", JsonNull)
        }
        assertEquals(listOf("Ana", "Beto"), TareasMcpTools.textList(arguments, "people"))
        assertEquals(listOf("Ana", "Beto", "Carla", "Diego"), TareasMcpTools.textList(arguments, "plain"))
        assertEquals(listOf("Ana, Beto;Carla", "Diego"), TareasMcpTools.textList(arguments, "plain", Regex("\\n")))
        assertEquals(emptyList(), TareasMcpTools.textList(arguments, "nothing"))
        assertEquals(emptyList(), TareasMcpTools.textList(arguments, "missing"))
    }

    @Test
    fun taskJsonShowsPeopleStateAndChecklistButNoFileReferences() {
        val task = TareasFixtures.task(
            status = TaskStatus.Review,
            assignees = listOf(ana.id, UUID.randomUUID()),
            reviewerId = beto.id,
            dueDate = LocalDate.of(2026, 11, 1),
        ).copy(checklistDone = 1, checklistTotal = 3, groupName = "Esta semana")
        val json = TareasMcpTools.taskJson(task, users.associate { it.id to it.label }, LocalDate.of(2026, 11, 18))
        val assignees = json["assignees"] as JsonArray
        assertEquals("review", text(json["status"]))
        assertEquals("En revisión", text(json["status_label"]))
        assertEquals("1/3", text(json["checklist"]))
        assertEquals("true", text(json["overdue"]))
        assertEquals("Esta semana", text(json["group"]))
        assertEquals("Ana Ruiz", text(assignees[0].jsonObject["name"]))
        assertEquals("Usuario sin acceso a Tareas", text(assignees[1].jsonObject["name"]))
        assertEquals("Beto", text(json["reviewer"]?.jsonObject?.get("name")))
        assertFalse(json.containsKey("backend"))
        assertFalse(json.containsKey("ref"))
    }

    @Test
    fun taskJsonHidesTheCustomerWhenTheUserCannotSeeCustomers() {
        val customer = UUID.randomUUID()
        val task = TareasFixtures.task().copy(customerId = customer)
        assertEquals(customer.toString(), text(TareasMcpTools.taskJson(task, emptyMap())["customer_id"]))
        assertFalse(TareasMcpTools.taskJson(task, emptyMap(), showCustomer = false).containsKey("customer_id"))
    }
}
