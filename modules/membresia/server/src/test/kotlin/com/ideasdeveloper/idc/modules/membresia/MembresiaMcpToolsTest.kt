package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.modules.McpToolRules
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MembresiaMcpToolsTest {
    // Solo las herramientas base: las de importacion dependen de las extensiones instaladas.
    private val tools = MembresiaMcpTools.baseTools
    private val definition = MembresiaServerModule.definition

    @Test
    fun namesAreValidQualifiedAndUnique() {
        assertEquals(tools.size, tools.map { it.name }.toSet().size, "hay nombres repetidos")
        tools.forEach { tool ->
            assertTrue(Regex("^[a-z][a-z0-9_]*$").matches(tool.name), "nombre invalido: ${tool.name}")
            assertTrue("${MembresiaModule.Id}_${tool.name}".length <= 64, "nombre calificado muy largo: ${tool.name}")
        }
    }

    @Test
    fun registryAcceptsEveryTool() {
        tools.forEach { tool -> assertEquals(null, McpToolRules.problem(definition, tool), tool.name) }
    }

    @Test
    fun everyPermissionIsDeclaredByTheModule() {
        val declared = definition.permissions.map { it.id }.toSet()
        tools.forEach { assertTrue(it.permission in declared, "${it.name} usa un permiso no declarado: ${it.permission}") }
    }

    @Test
    fun onlyReadingToolsAreReadOnly() {
        val readOnly = tools.filter { it.readOnly }.map { it.name }.toSet()
        assertEquals(setOf("search_members", "get_member_status", "list_plans", "list_expiring", "revenue_summary"), readOnly)
        val changing = tools.filter { it.name in setOf("record_visit", "create_membership", "renew_membership") }
        assertEquals(3, changing.size)
        changing.forEach { assertTrue(!it.readOnly, "${it.name} cambia datos y no puede ser de solo lectura") }
    }

    @Test
    fun permissionsMatchTheDesign() {
        val byName = tools.associate { it.name to it.permission }
        assertEquals(MembresiaModule.Visits, byName["record_visit"])
        assertEquals(MembresiaModule.Manage, byName["create_membership"])
        assertEquals(MembresiaModule.Manage, byName["renew_membership"])
        assertEquals(MembresiaModule.Manage, byName["revenue_summary"])
        listOf("search_members", "get_member_status", "list_plans", "list_expiring").forEach { assertEquals(MembresiaModule.View, byName[it], it) }
    }

    @Test
    fun noToolTakesCredentialsOrSensitiveData() {
        val forbidden = Regex("key|token|secret|password|credential|code|document|passport|birth", RegexOption.IGNORE_CASE)
        tools.forEach { tool ->
            val properties = tool.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            properties.keys.forEach { assertTrue(!forbidden.containsMatchIn(it), "${tool.name} recibe un argumento sospechoso: $it") }
        }
    }

    @Test
    fun importSourceToolsRequireTheImportPermission() {
        MembresiaImportSources.all.flatMap { it.mcpTools }.forEach {
            assertEquals(MembresiaModule.Import, it.permission, "${it.name} debe exigir ${MembresiaModule.Import}")
        }
    }

    @Test
    fun instructionsAreProvidedAndMcpHoldsTheTools() {
        assertTrue(MembresiaMcpTools.Instructions.isNotBlank())
        assertTrue("override=true" in MembresiaMcpTools.Instructions)
        assertTrue(MembresiaMcpTools.mcp.tools.size >= tools.size)
        assertEquals(MembresiaMcpTools.Instructions, MembresiaMcpTools.mcp.instructions)
        assertEquals(8, tools.size)
    }
}
