package com.ideasdeveloper.idc.extensions.compras_monday

import com.ideasdeveloper.idc.modules.compras.Actor
import com.ideasdeveloper.idc.modules.compras.ComprasImportContext
import com.ideasdeveloper.idc.modules.compras.ComprasModule
import com.ideasdeveloper.idc.modules.compras.ComprasValidationException
import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Herramientas de agentes de la importacion de monday (permiso compras.import; el registro les antepone "compras_"). El token de monday
 * se guarda solo en la app: las herramientas usan el guardado, en el servidor, y nunca lo reciben ni lo devuelven. Las solicitudes que
 * se importan las crea el usuario del agente, con las mismas reglas que la pantalla Importar.
 */
object ComprasMondayMcpTools {
    val tools = listOf(
        McpTool(
            name = "monday_list_boards",
            title = "Tableros de monday",
            description = "Lista los tableros de monday.com de la cuenta (con el token guardado en la app). Con board_id devuelve ademas los grupos (propiedades) " +
                "y las columnas de ese tablero, para elegir los grupos a importar.",
            permission = ComprasMondayImport.ImportPermission,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("board_id" to McpSchema.string("Id de un tablero, para ver sus grupos y columnas; opcional."))),
        ) { call -> guarded { listBoards(call) } },
        McpTool(
            name = "monday_start_import",
            title = "Importar un tablero de monday",
            description = "Lee en segundo plano los grupos elegidos de un tablero de monday.com (puede tardar unos minutos) con el mapeo de columnas sugerido. " +
                "No crea solicitudes todavia: consulta el avance con monday_import_status y confirma con monday_apply_import. Muestra al usuario lo que vas a importar y espera su confirmacion.",
            permission = ComprasMondayImport.ImportPermission,
            inputSchema = McpSchema.obj(
                mapOf(
                    "board_id" to McpSchema.string("Id del tablero (monday_list_boards)."),
                    "group_ids" to buildJsonObject {
                        put("type", "array")
                        put("description", "Ids de los grupos a importar (monday_list_boards con board_id); cada grupo es una propiedad.")
                        put("items", buildJsonObject { put("type", "string") })
                    },
                    "currency" to McpSchema.string("Moneda de 3 letras, por ejemplo USD; por defecto la de Compras."),
                ),
                required = listOf("board_id", "group_ids"),
            ),
        ) { call -> guarded { startImport(call) } },
        McpTool(
            name = "monday_import_status",
            title = "Estado de la importacion de monday",
            description = "Muestra el estado de una importacion de monday (leyendo, lista, importando, terminada o con error), sus conteos y los primeros problemas. Sin job_id usa la mas reciente.",
            permission = ComprasMondayImport.ImportPermission,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("job_id" to McpSchema.uuid("Id de la importacion; opcional."))),
        ) { call -> guarded { status(call) } },
        McpTool(
            name = "monday_apply_import",
            title = "Aplicar la importacion de monday",
            description = "Crea en Compras las solicitudes de una importacion ya leida (estado lista). Se hace en segundo plano; consulta monday_import_status. " +
                "Es idempotente: lo ya importado no se duplica. Pide confirmacion al usuario antes de usarla.",
            permission = ComprasMondayImport.ImportPermission,
            inputSchema = McpSchema.obj(mapOf("job_id" to McpSchema.uuid("Id de la importacion (monday_start_import).")), required = listOf("job_id")),
        ) { call -> guarded { apply(call) } },
    )

    private fun guarded(block: () -> JsonElement): JsonElement = try {
        block()
    } catch (failure: ComprasValidationException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: ComprasMondayException) {
        throw McpToolException(failure.message.orEmpty())
    }

    /** El mismo contexto que la pantalla, con el usuario del agente (sus permisos vivos ya los comprobo el registro). */
    private fun contextOf(call: McpToolCall): ComprasImportContext {
        val companyCode = call.permissions.companyCode
        val actor = Actor(call.permissions.userId, call.access.userIdentity(call.tenant, call.permissions)?.name ?: "Agente", call.permissions)
        return ComprasImportContext(call.tenant, companyCode, actor, call.access.files(companyCode, ComprasModule.id), call.notifier, call.access.secrets)
    }

    private fun token(ctx: ComprasImportContext): String {
        if (!ComprasMondayImport.enabled(ctx.tenant)) throw McpToolException("La extension de monday no esta activa en esta empresa.")
        return ComprasMondayStore.loadToken(ctx) ?: throw McpToolException("Configura el token en la app (Compras > Importar).")
    }

    private fun listBoards(call: McpToolCall): JsonElement {
        val ctx = contextOf(call)
        val token = token(ctx)
        val boardId = call.string("board_id", 30)
        if (boardId.isNotEmpty()) {
            val info = ComprasMondayApi.boardInfo(token, boardId)
            val suggestion = ComprasMondayMapping.suggest(info.columns)
            return buildJsonObject {
                put("id", info.id)
                put("name", info.name)
                putJsonArray("groups") { info.groups.forEach { group -> add(buildJsonObject { put("id", group.id); put("title", group.title) }) } }
                putJsonArray("columns") {
                    info.columns.forEach { column ->
                        add(buildJsonObject { put("id", column.id); put("title", column.title); put("type", column.type) })
                    }
                }
                putJsonArray("suggested_mapping") {
                    suggestion.forEach { (field, columnId) ->
                        add(buildJsonObject { put("field", field); put("column_id", columnId); put("column", info.columns.firstOrNull { it.id == columnId }?.title.orEmpty()) })
                    }
                }
            }
        }
        val boards = ComprasMondayApi.boards(token)
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

    private fun startImport(call: McpToolCall): JsonElement {
        val ctx = contextOf(call)
        val token = token(ctx)
        val boardId = call.requiredString("board_id", 30)
        val requested = (call.arguments["group_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        if (requested.isEmpty()) throw McpToolException("El argumento 'group_ids' debe tener al menos un grupo.")
        val info = ComprasMondayApi.boardInfo(token, boardId)
        // Cada grupo se acepta por id o por titulo exacto; uno que no existe se informa en vez de importar en silencio nada.
        val resolved = requested.map { wanted -> info.groups.firstOrNull { it.id == wanted } ?: info.groups.firstOrNull { it.title.equals(wanted, ignoreCase = true) } }
        val unknown = requested.filterIndexed { index, _ -> resolved[index] == null }
        if (unknown.isNotEmpty()) throw McpToolException("Grupos que no existen en el tablero: ${unknown.joinToString()}. Usa monday_list_boards con board_id para verlos.")
        val groups = resolved.filterNotNull()
        val suggestion = ComprasMondayMapping.suggest(info.columns)
        val jobId = ComprasMondayImport.startImport(ctx, info, groups.map { it.id }, suggestion, call.string("currency", 3))
        return buildJsonObject {
            put("job", jobId.toString())
            put("board", info.name)
            putJsonArray("groups") { groups.forEach { add(JsonPrimitive(it.title)) } }
            put("result", "Se esta leyendo el tablero de monday; consulta monday_import_status en unos minutos.")
        }
    }

    private fun status(call: McpToolCall): JsonElement {
        val ctx = contextOf(call)
        if (!ComprasMondayImport.enabled(ctx.tenant)) throw McpToolException("La extension de monday no esta activa en esta empresa.")
        val jobId = call.uuid("job_id")
        val job = (if (jobId != null) ComprasMondayStore.job(ctx.tenant, jobId) else ComprasMondayStore.latestJob(ctx.tenant))
            ?: throw McpToolException("No hay una importacion de monday.")
        return buildJsonObject {
            put("job", job.id.toString())
            put("board", job.boardName)
            put("status", job.status)
            put("status_label", ComprasMondayImport.statusLabel(job.status))
            put("read", job.readCount)
            put("created", job.createdCount)
            put("existing", job.existingCount)
            put("failed", job.failedCount)
            if (job.error.isNotEmpty()) put("error", job.error)
            put("problems", buildJsonArray {
                ComprasMondayStore.problemRows(ctx.tenant, job.id, 10).forEach { row ->
                    add(buildJsonObject {
                        put("item", ComprasMondayParse.decode(row.payload)?.name ?: row.itemId)
                        put("problem", row.problem.ifEmpty { "con error" })
                    })
                }
            })
        }
    }

    private fun apply(call: McpToolCall): JsonElement {
        val ctx = contextOf(call)
        val job = ComprasMondayImport.applyJob(ctx, call.requiredUuid("job_id"))
        return buildJsonObject {
            put("job", job.id.toString())
            put("result", "Se importaran ${job.readCount} elementos en segundo plano; consulta monday_import_status.")
        }
    }
}
