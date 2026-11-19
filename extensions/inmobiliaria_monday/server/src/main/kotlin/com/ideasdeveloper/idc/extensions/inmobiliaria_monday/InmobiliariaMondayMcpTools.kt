package com.ideasdeveloper.idc.extensions.inmobiliaria_monday

import com.ideasdeveloper.idc.modules.crm.CrmImportMcpTools
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Herramientas MCP de la importacion (crm.settings, como la pantalla). El token de monday solo se guarda en la app: las
 * herramientas usan el guardado, en el servidor, y nunca lo reciben ni lo devuelven. Los permisos son los del usuario en vivo.
 */
object InmobiliariaMondayMcpTools {
    private val jobArgument = "job" to McpSchema.uuid("Id de la importacion (inmobiliaria_monday_start_import).")

    private fun mappingSchema(description: String) = kotlinx.serialization.json.buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", buildJsonObject { put("type", "string") })
    }

    val tools = listOf(
        McpTool(
            name = "inmobiliaria_monday_list_boards",
            title = "Tableros de monday",
            description = "Lista los tableros de monday.com de la cuenta (id, nombre, espacio y elementos) con el token guardado en la app. Sirve para elegir el tablero de listados o de deals a importar.",
            permission = CrmScreens.Settings,
            readOnly = true,
        ) { call -> boards(call) },
        McpTool(
            name = "inmobiliaria_monday_start_import",
            title = "Leer un tablero de monday",
            description = "Pone en cola la lectura de un tablero de monday para importarlo a Inmobiliaria (propiedades o transacciones). Se lee en segundo plano " +
                "(puede tardar minutos); consulta el avance con inmobiliaria_monday_status. No aplica nada: despues se revisa el mapeo y se aplica con inmobiliaria_monday_apply. " +
                "Confirma con el usuario antes.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    "board" to McpSchema.string("Id del tablero de monday (inmobiliaria_monday_list_boards)."),
                    "kind" to McpSchema.string("Que contiene el tablero: property (listados) o deal (transacciones).", enum = listOf(MondayMapping.KindProperty, MondayMapping.KindDeal)),
                    "new_listings" to McpSchema.boolean("Solo para property: es un tablero de nuevos listados; las propiedades entran como Captacion."),
                    "mapping" to mappingSchema("Opcional: titulo de la columna de monday -> clave del campo. Sin el, se sugiere por el titulo de cada columna."),
                ),
                required = listOf("board", "kind"),
            ),
        ) { call -> start(call) },
        McpTool(
            name = "inmobiliaria_monday_status",
            title = "Estado de la importacion de monday",
            description = "Devuelve una importacion: estado (reading, ready, applying, done, failed), conteos, columnas con su mapeo, campos obligatorios sin mapear, " +
                "una vista previa de las primeras filas mapeadas y los problemas. Sin job, la mas reciente.",
            permission = CrmScreens.Settings,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf(jobArgument)),
        ) { call -> status(call) },
        McpTool(
            name = "inmobiliaria_monday_apply",
            title = "Aplicar la importacion de monday",
            description = "Aplica a Inmobiliaria una importacion ya leida (estado ready), de a 100 filas en segundo plano; consulta el avance con inmobiliaria_monday_status. " +
                "Reimportar actualiza lo ya importado, no lo duplica. Confirma con el usuario antes. Con mapping reemplaza el mapeo antes de aplicar.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(jobArgument, "mapping" to mappingSchema("Opcional: titulo de la columna -> clave del campo; reemplaza el mapeo actual (las columnas no enviadas no se importan).")),
                required = listOf("job"),
            ),
        ) { call -> apply(call) },
    )

    private fun <T> checked(block: () -> T): T = try {
        block()
    } catch (failure: InmobiliariaMondayException) {
        throw McpToolException(failure.message.orEmpty())
    }

    private fun mapping(call: McpToolCall): Map<String, String> {
        val value = call.arguments["mapping"] ?: return emptyMap()
        val obj = value as? JsonObject ?: throw McpToolException("'mapping' debe ser un objeto de textos.")
        return obj.mapValues { (it.value as? JsonPrimitive)?.contentOrNull?.trim() ?: throw McpToolException("'mapping' debe ser un objeto de textos.") }
    }

    private fun boards(call: McpToolCall): JsonElement = checked {
        InmobiliariaMondayService.unavailableReason(call.tenant)?.let { throw McpToolException(it) }
        val boards = InmobiliariaMondayService.boards(call.tenant)
        buildJsonObject {
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

    private fun start(call: McpToolCall): JsonElement = checked {
        val actor = CrmImportMcpTools.actor(call)
        val id = InmobiliariaMondayService.start(
            call.tenant, actor, call.requiredString("board", 20), "", call.requiredString("kind", 20), call.boolean("new_listings"), mapping(call),
        )
        buildJsonObject {
            put("job", id.toString())
            put("result", "Se esta leyendo el tablero de monday; consulta inmobiliaria_monday_status en unos minutos.")
        }
    }

    private fun status(call: McpToolCall): JsonElement = checked {
        val tenant = call.tenant
        val job = call.uuid("job")?.let { InmobiliariaMondayService.job(tenant, it) }
            ?: InmobiliariaMondayStore.recent(tenant, 1).firstOrNull() ?: throw McpToolException("No hay importaciones.")
        val kind = InmobiliariaMondayRunner.target(tenant)?.importKinds?.firstOrNull { it.id == job.kind }
        val byState = InmobiliariaMondayStore.rowCounts(tenant, job.id)
        buildJsonObject {
            put("job", job.id.toString())
            put("state", job.state)
            put("board", job.boardName)
            put("kind", job.kind)
            put("new_listings", job.newListings)
            put("rows_read", job.rowsRead)
            put("created", job.created)
            put("updated", job.updated)
            put("skipped", job.skipped)
            put("failed", job.failed)
            put("pending", byState[MondayRowState.Pending] ?: 0)
            if (job.error.isNotEmpty()) put("error", MondayMapping.clip(job.error, MondayMapping.McpProblemChars))
            putJsonArray("columns") {
                job.columns.forEach { column ->
                    add(buildJsonObject {
                        put("title", column.title)
                        put("type", column.type)
                        job.mapping[column.title]?.let { put("field", it) }
                    })
                }
            }
            if (kind != null) {
                putJsonArray("missing_required") { MondayMapping.missingRequired(kind, job.mapping).forEach { add(JsonPrimitive(it)) } }
                if (job.state == MondayJobState.Ready) {
                    putJsonArray("preview") {
                        InmobiliariaMondayStore.rows(tenant, job.id, null, MondayMapping.McpPreviewRows).forEach { row ->
                            add(buildJsonObject {
                                putJsonObject("values") {
                                    MondayMapping.values(row.data, job.mapping, kind, job.newListings).forEach { (key, value) -> put(key, MondayMapping.clip(value, MondayMapping.McpPreviewChars)) }
                                }
                            })
                        }
                    }
                }
            }
            // Tope de cantidad y de largo: los problemas llevan texto de los datos de monday.
            putJsonArray("problems") { job.problems.take(MondayMapping.McpMaxProblems).forEach { add(JsonPrimitive(MondayMapping.clip(it, MondayMapping.McpProblemChars))) } }
            if (job.problems.size > MondayMapping.McpMaxProblems) put("problems_omitted", job.problems.size - MondayMapping.McpMaxProblems)
        }
    }

    private fun apply(call: McpToolCall): JsonElement = checked {
        val id: UUID = call.requiredUuid("job")
        if (call.has("mapping")) InmobiliariaMondayService.saveMapping(call.tenant, id, mapping(call))
        InmobiliariaMondayService.requireTarget(call.tenant)
        InmobiliariaMondayService.apply(call.tenant, CrmImportMcpTools.actor(call), id)
        buildJsonObject {
            put("job", id.toString())
            put("result", "Se esta aplicando en segundo plano; consulta inmobiliaria_monday_status.")
        }
    }
}
