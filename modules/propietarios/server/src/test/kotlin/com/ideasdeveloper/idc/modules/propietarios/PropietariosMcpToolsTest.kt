package com.ideasdeveloper.idc.modules.propietarios

import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.McpToolRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Herramientas para agentes: reglas del registro, permisos, nombres de argumentos y el lector de titulares. */
class PropietariosMcpToolsTest {
    private val tools = PropietariosMcpTools.mcp.tools
    private val definition = PropietariosServerModule.definition

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
        val expected = setOf("list_developments", "search_lots", "get_lot", "search_owners", "get_owner", "register_ownership_change", "add_lot")
        assertEquals(expected, tools.map { it.name }.toSet())
    }

    @Test
    fun readingToolsAreReadOnlyAndWritingToolsAreNot() {
        listOf("list_developments", "search_lots", "get_lot", "search_owners", "get_owner").forEach { assertTrue(tool(it).readOnly, it) }
        listOf("register_ownership_change", "add_lot").forEach { assertFalse(tool(it).readOnly, it) }
        assertTrue(tools.none { it.destructive })
    }

    @Test
    fun permissionsMatchTheDesign() {
        listOf("list_developments", "search_lots", "get_lot", "search_owners", "get_owner").forEach { assertEquals(PropietariosModule.View, tool(it).permission, it) }
        listOf("register_ownership_change", "add_lot").forEach { assertEquals(PropietariosModule.Manage, tool(it).permission, it) }
        // Administrar desarrollos e importar no se ofrecen a los agentes desde el nucleo del modulo.
        assertTrue(tools.none { it.permission == PropietariosModule.Admin || it.permission == PropietariosModule.Import })
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
    fun searchLotsLimitIsCappedAtFifty() {
        val limit = tool("search_lots").inputSchema["properties"]!!.jsonObject["limit"]!!.jsonObject
        assertEquals("50", text(limit["maximum"]))
    }

    @Test
    fun statusArgumentsListTheFixedCodes() {
        fun enumOf(tool: String): List<String> =
            (tool(tool).inputSchema["properties"]?.jsonObject?.get("status")?.jsonObject?.get("enum") as JsonArray).map { text(it) }
        assertEquals(LotStatus.entries.map { it.code }, enumOf("search_lots"))
        assertEquals(LotStatus.entries.map { it.code }, enumOf("add_lot"))
    }

    @Test
    fun instructionsExplainTheFlowAndTheStates() {
        assertTrue(PropietariosMcpTools.mcp.instructions.isNotBlank())
        listOf("search_lots", "register_ownership_change", "add_lot", "list_developments", "disponible", "reservado", "vendido", "en_construccion", "construido").forEach {
            assertTrue(it in PropietariosMcpTools.Instructions, it)
        }
    }

    @Test
    fun serverModuleDeclaresTheDesignedPermissionsExtensionsAndHome() {
        assertEquals(listOf("propietarios.view", "propietarios.manage", "propietarios.admin", "propietarios.import"), definition.permissions.map { it.id })
        assertEquals(listOf("propietarios.import.write"), definition.extensionPermissions)
        assertTrue(definition.supportsExtensions)
        assertEquals("desarrollos", definition.ui?.home)
        assertEquals("0.2.0", definition.version)
    }

    // ---- lector de titulares ----

    @Test
    fun ownersAreReadFromAnArrayOfObjects() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val arguments = buildJsonObject {
            put(
                "owners",
                buildJsonArray {
                    add(buildJsonObject { put("customer_id", a.toString()); put("share_percent", 60) })
                    add(buildJsonObject { put("customer_id", b.toString()) })
                },
            )
        }
        val owners = PropietariosMcpTools.parseOwners(arguments)
        assertEquals(listOf(a, b), owners.map { it.customerId })
        assertEquals(0, BigDecimal("60").compareTo(owners[0].sharePercent))
        assertNull(owners[1].sharePercent)
    }

    @Test
    fun anEmptyOwnersListIsAllowedButAMissingOneIsNot() {
        assertEquals(emptyList(), PropietariosMcpTools.parseOwners(buildJsonObject { put("owners", JsonArray(emptyList())) }))
        assertFailsWith<McpToolException> { PropietariosMcpTools.parseOwners(buildJsonObject { }) }
        assertFailsWith<McpToolException> { PropietariosMcpTools.parseOwners(buildJsonObject { put("owners", JsonNull) }) }
    }

    @Test
    fun badOwnersAreErrorsTheAgentCanFix() {
        fun owners(vararg items: JsonObject) = buildJsonObject { put("owners", JsonArray(items.toList())) }
        assertFailsWith<McpToolException> { PropietariosMcpTools.parseOwners(owners(buildJsonObject { put("customer_id", "no-es-un-id") })) }
        assertFailsWith<McpToolException> { PropietariosMcpTools.parseOwners(owners(buildJsonObject { put("share_percent", 10) })) }
        assertFailsWith<McpToolException> {
            PropietariosMcpTools.parseOwners(owners(buildJsonObject { put("customer_id", UUID.randomUUID().toString()); put("share_percent", "mucho") }))
        }
        assertFailsWith<McpToolException> { PropietariosMcpTools.parseOwners(buildJsonObject { put("owners", "texto") }) }
    }

    @Test
    fun lotJsonShowsNumbersStatusAndMapLinkButNoStorageReferences() {
        val lot = PropietariosFixtures.lot(status = LotStatus.Vendido, latitude = BigDecimal("9.9281"), longitude = BigDecimal("-84.0907"))
        val json = PropietariosMcpTools.lotJson(lot)
        assertEquals("vendido", text(json["status"]))
        assertEquals("Vendido", text(json["status_label"]))
        assertEquals("24", text(json["lot_number"]))
        assertEquals("https://www.google.com/maps?q=9.9281,-84.0907", text(json["map_link"]))
        assertFalse(json.containsKey("backend"))
        assertFalse(json.containsKey("ref"))
    }
}
