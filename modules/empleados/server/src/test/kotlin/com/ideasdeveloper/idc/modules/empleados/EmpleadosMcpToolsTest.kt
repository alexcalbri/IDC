package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.employee
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.McpToolRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Herramientas para agentes: reglas del registro, permisos declarados, argumentos y lo que cada nivel de ficha deja pasar. */
class EmpleadosMcpToolsTest {
    private val tools = EmpleadosMcpTools.tools
    private val definition = EmpleadosServerModule.definition

    private fun tool(name: String) = tools.first { it.name == name }

    private fun text(element: kotlinx.serialization.json.JsonElement?): String = (element as JsonPrimitive).content

    @Test
    fun everyToolPassesTheRegistryRules() {
        tools.forEach { assertNull(McpToolRules.problem(definition, it), "tool ${it.name}") }
    }

    @Test
    fun toolNamesAreUniqueAndQualifiedNamesFit() {
        assertEquals(tools.size, tools.map { it.name }.toSet().size)
        tools.forEach { assertTrue("${definition.id}_${it.name}".length <= McpToolRules.MaxQualifiedNameLength, it.name) }
    }

    @Test
    fun expectedToolsExist() {
        val expected = setOf(
            "list_employees", "get_employee", "list_sites", "who_is_on_shift", "get_schedule", "list_absences",
            "create_shift", "move_shift", "delete_shift", "set_day_off", "request_absence", "decide_absence",
        )
        assertEquals(expected, tools.map { it.name }.toSet())
    }

    @Test
    fun readingToolsAreReadOnlyAndWritingToolsAreNot() {
        listOf("list_employees", "get_employee", "list_sites", "who_is_on_shift", "get_schedule", "list_absences").forEach { assertTrue(tool(it).readOnly, it) }
        listOf("create_shift", "move_shift", "delete_shift", "set_day_off", "request_absence", "decide_absence").forEach { assertFalse(tool(it).readOnly, it) }
        assertEquals(listOf("delete_shift"), tools.filter { it.destructive }.map { it.name })
    }

    @Test
    fun permissionsMatchTheDesign() {
        listOf("list_employees", "get_employee", "list_sites", "get_schedule", "list_absences", "request_absence")
            .forEach { assertEquals(EmpleadosModule.View, tool(it).permission, it) }
        listOf("who_is_on_shift", "create_shift", "move_shift", "delete_shift", "set_day_off", "decide_absence")
            .forEach { assertEquals(EmpleadosModule.Shifts, tool(it).permission, it) }
        // Ninguna herramienta exige administrar fichas: las fichas se editan solo desde la app.
        assertTrue(tools.none { it.permission == EmpleadosModule.Manage })
    }

    private fun propertyNames(schema: JsonObject): Set<String> = schema["properties"]?.jsonObject?.keys.orEmpty()

    @Test
    fun noToolHandlesCredentialsOrTheActingCompany() {
        val forbidden = listOf("password", "token", "secret", "key", "acting_company", "user_id")
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
    fun noToolReturnsOrAcceptsPasswordsOrTokensInItsDescription() {
        tools.forEach { assertFalse(it.description.contains("contraseña", ignoreCase = true), it.name) }
    }

    @Test
    fun instructionsExplainTheScheduleRulesAndTheFlow() {
        assertTrue(EmpleadosMcpTools.mcp.instructions.isNotBlank())
        listOf("list_employees", "get_schedule", "create_shift", "request_absence", "decide_absence", "who_is_on_shift", "habitual", "ausencia").forEach {
            assertTrue(it in EmpleadosMcpTools.Instructions, it)
        }
    }

    @Test
    fun enumArgumentsListTheFixedCodes() {
        fun enumOf(tool: String, argument: String): List<String> =
            (tool(tool).inputSchema["properties"]?.jsonObject?.get(argument)?.jsonObject?.get("enum") as JsonArray).map { text(it) }
        assertEquals(AbsenceKind.entries.map { it.code }, enumOf("request_absence", "kind"))
        assertEquals(AbsenceStatus.entries.map { it.code }, enumOf("list_absences", "status"))
    }

    // ---- ayudas ----

    @Test
    fun timeArgumentsAreHoursOrErrors() {
        assertEquals(LocalTime.of(8, 0), EmpleadosMcpTools.timeArgument("08:00", "start"))
        assertNull(EmpleadosMcpTools.timeArgument("  ", "start"))
        val failure = assertFailsWith<McpToolException> { EmpleadosMcpTools.timeArgument("8am", "start") }
        assertTrue("start" in failure.message.orEmpty())
    }

    @Test
    fun rangesAreAtMost31DaysAndOrdered() {
        val from = LocalDate.of(2026, 11, 1)
        EmpleadosMcpTools.checkRange(from, from)
        EmpleadosMcpTools.checkRange(from, from.plusDays(30))
        assertFailsWith<McpToolException> { EmpleadosMcpTools.checkRange(from, from.plusDays(31)) }
        assertFailsWith<McpToolException> { EmpleadosMcpTools.checkRange(from, from.minusDays(1)) }
    }

    @Test
    fun basicLevelNeverShowsContactDataNotesOrHireDate() {
        val json = EmpleadosMcpTools.employeeJson(employee(level = FieldLevel.Basic))
        listOf("phone", "email", "availability", "hire_date", "notes", "ended_on", "has_user_account", "user_id").forEach {
            assertFalse(json.containsKey(it), it)
        }
        assertEquals("Ana Ruiz", text(json["name"]))
        assertEquals("active", text(json["status"]))
    }

    @Test
    fun contactLevelAddsPhoneEmailAndAvailabilityOnly() {
        val json = EmpleadosMcpTools.employeeJson(employee(level = FieldLevel.Contact))
        assertEquals("555-0100", text(json["phone"]))
        assertEquals("ana@example.com", text(json["email"]))
        assertEquals("No puede los domingos", text(json["availability"]))
        listOf("hire_date", "notes", "ended_on", "has_user_account").forEach { assertFalse(json.containsKey(it), it) }
    }

    @Test
    fun ownLevelAddsHireDateButNeverNotes() {
        val json = EmpleadosMcpTools.employeeJson(employee(level = FieldLevel.Own))
        assertEquals("2024-03-01", text(json["hire_date"]))
        assertFalse(json.containsKey("notes"))
    }

    @Test
    fun fullLevelShowsEverythingButNeverTheUserId() {
        val linked = employee(level = FieldLevel.Full).copy(userId = UUID.randomUUID())
        val json = EmpleadosMcpTools.employeeJson(linked)
        assertEquals("Nota privada", text(json["notes"]))
        assertEquals("true", text(json["has_user_account"]))
        assertFalse(json.containsKey("user_id"))
        assertFalse(json.toString().contains(linked.userId.toString()))
    }

    @Test
    fun dayJsonDescribesWorkOffAndAbsence() {
        val site = UUID.randomUUID()
        val shiftId = UUID.randomUUID()
        val date = LocalDate.of(2026, 11, 18)
        val work = EmpleadosMcpTools.dayJson(
            date,
            DaySchedule.Working(listOf(DaySlot(LocalTime.of(8, 0), LocalTime.of(16, 0), site, EmpleadosSchedule.SourceShift, shiftId))),
            mapOf(site to "La Jolla"),
        )
        assertEquals("work", text(work["status"]))
        val slot = work["slots"]!!.jsonArray[0].jsonObject
        assertEquals("08:00", text(slot["start"]))
        assertEquals("16:00", text(slot["end"]))
        assertEquals("La Jolla", text(slot["site"]))
        assertEquals("shift", text(slot["source"]))
        assertEquals(shiftId.toString(), text(slot["shift_id"]))
        assertEquals("off", text(EmpleadosMcpTools.dayJson(date, DaySchedule.Off(null), emptyMap())["status"]))
        val absent = EmpleadosMcpTools.dayJson(date, DaySchedule.Absent(AbsenceKind.Sick), emptyMap())
        assertEquals("absent", text(absent["status"]))
        assertEquals("sick", text(absent["absence_kind"]))
    }
}
