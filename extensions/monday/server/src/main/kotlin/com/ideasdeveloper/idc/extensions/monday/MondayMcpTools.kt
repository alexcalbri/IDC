package com.ideasdeveloper.idc.extensions.monday

import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmImportMcpTools
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Agent tools of the monday import (crm.settings, like the Importar screens). The monday token is saved only in the app;
 * the tools use the stored one, server side, and never receive or return it.
 */
object MondayMcpTools {
    val tools = listOf(
        McpTool(
            name = "list_monday_boards",
            title = "Tableros de monday",
            description = "Lista los tableros de monday.com de la cuenta, con el token guardado en la app.",
            permission = CrmScreens.Settings,
            readOnly = true,
        ) { call -> mondayBoards(call) },
        McpTool(
            name = "import_monday",
            title = "Importar tableros de monday",
            description = "Lee en segundo plano los tableros elegidos de monday.com (puede tardar minutos) y crea una importacion; consulta su avance con get_import.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf("boards" to buildJsonObject {
                    put("type", "array")
                    put("description", "Ids de los tableros (list_monday_boards).")
                    put("items", buildJsonObject { put("type", "string") })
                }),
                required = listOf("boards"),
            ),
        ) { call -> importMonday(call) },
    )

    private fun mondayBoards(call: McpToolCall): JsonElement {
        CrmHost.extensionInactiveReason(call.tenant, CrmExtensionIds.MondayImport)?.let { throw McpToolException(it) }
        val token = MondayImport.mondayToken(call.tenant) ?: throw McpToolException("Guarda primero el token de la API de monday en la app (Importar).")
        val boards = CrmImportMcpTools.checked { MondayImport.mondayBoards(token) }
        return buildJsonObject {
            putJsonArray("boards") {
                boards.forEach { board ->
                    add(buildJsonObject {
                        put("id", board.id)
                        put("name", board.name)
                        put("workspace", board.workspace)
                        board.items?.let { put("items", it) }
                    })
                }
            }
        }
    }

    private fun importMonday(call: McpToolCall): JsonElement {
        CrmHost.extensionInactiveReason(call.tenant, CrmExtensionIds.MondayImport)?.let { throw McpToolException(it) }
        if (MondayImport.mondayToken(call.tenant) == null) throw McpToolException("Guarda primero el token de la API de monday en la app (Importar).")
        val id = CrmImportMcpTools.checked { MondayImport.queueMonday(call.tenant, CrmImportMcpTools.strings(call, "boards"), CrmImportMcpTools.actor(call).userId) }
        return buildJsonObject {
            put("job", id.toString())
            put("result", "Se estan leyendo los tableros de monday; consulta get_import en unos minutos.")
        }
    }
}
