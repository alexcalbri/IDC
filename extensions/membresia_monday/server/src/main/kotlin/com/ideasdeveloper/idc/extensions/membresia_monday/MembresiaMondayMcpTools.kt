package com.ideasdeveloper.idc.extensions.membresia_monday

import com.ideasdeveloper.idc.modules.membresia.MembresiaModule
import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Herramientas de agentes de la importacion desde monday (permiso membresia.import, como la pantalla Importar). El token
 * se guarda solo en la app: las herramientas usan el guardado, del lado del servidor, y nunca lo reciben ni lo devuelven.
 * Se publican como "membresia_<nombre>".
 */
object MembresiaMondayMcpTools {
    private const val MaxMembers = 1_000_000

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "monday_list_boards",
            title = "Tableros de monday",
            description = "Lista los tableros de monday.com de la cuenta (id, nombre, espacio y numero de elementos), con el token guardado en la app.",
            permission = MembresiaModule.Import,
            readOnly = true,
        ) { call -> guarded(call) { listBoards(call) } },
        McpTool(
            name = "monday_read_board",
            title = "Leer un tablero de monday",
            description = "Lee en segundo plano un tablero de monday (puede tardar minutos) con el mapeo de columnas sugerido automaticamente y deja la importacion " +
                "en revision; no crea nada todavia. Consulta el resumen con monday_import_status y, si el usuario lo confirma, usa monday_apply.",
            permission = MembresiaModule.Import,
            inputSchema = McpSchema.obj(
                mapOf("board_id" to McpSchema.string("Id numerico del tablero (de monday_list_boards).")),
                required = listOf("board_id"),
            ),
        ) { call -> guarded(call) { readBoard(call) } },
        McpTool(
            name = "monday_import_status",
            title = "Estado de la importacion de monday",
            description = "Estado de una importacion (la ultima si no se indica job_id): avance, conteos y, en revision, la cantidad de miembros, visitas y planes nuevos y los codigos de problema. No devuelve nombres ni textos de monday: el detalle se ve en la pantalla Importar.",
            permission = MembresiaModule.Import,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("job_id" to McpSchema.uuid("Id de la importacion; opcional."))),
        ) { call -> guarded(call) { status(call) } },
        McpTool(
            name = "monday_apply",
            title = "Aplicar la importacion de monday",
            description = "Aplica una importacion que esta en revision: crea planes, clientes, membresias y visitas (unas 200 filas por minuto, en segundo plano). " +
                "Usala solo despues de mostrar el resumen al usuario: confirm_members debe ser la cantidad de miembros del resumen y tiene que dictarla el usuario, no la deduzcas tu.",
            permission = MembresiaModule.Import,
            inputSchema = McpSchema.obj(
                mapOf(
                    "job_id" to McpSchema.uuid("Id de la importacion en revision (de monday_read_board)."),
                    "confirm_members" to McpSchema.integer("Cantidad de miembros del resumen, dictada por el usuario para confirmar.", 0, 1_000_000),
                ),
                required = listOf("job_id", "confirm_members"),
            ),
        ) { call -> guarded(call) { apply(call) } },
    )

    private fun guarded(call: McpToolCall, block: () -> JsonElement): JsonElement {
        if (!call.permissions.has(MembresiaModule.Import)) throw McpToolException("No tienes permiso para importar datos.")
        if (!MembresiaMondaySettings.extensionEnabled(call.tenant)) throw McpToolException("La extension de monday no esta activa en esta empresa.")
        try {
            return block()
        } catch (failure: MembresiaMondayException) {
            throw McpToolException(failure.message.orEmpty())
        }
    }

    private fun token(call: McpToolCall): String =
        MembresiaMondaySettings.loadToken(call.access.secrets, call.tenant)
            ?: throw McpToolException("Guarda primero el token de la API de monday en la app (Importar).")

    private fun listBoards(call: McpToolCall): JsonElement {
        val boards = MembresiaMondayClient.boards(token(call))
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

    private fun readBoard(call: McpToolCall): JsonElement {
        val boardId = call.requiredString("board_id", 20)
        if (!MembresiaMondayClient.isBoardId(boardId)) throw McpToolException("El argumento 'board_id' debe ser un numero.")
        val board = MembresiaMondayClient.boardInfo(token(call), boardId)
        val mapping = MembresiaMondayMapping.suggest(board.columns)
        MembresiaMondayFlow.mappingProblem(mapping, board.columns)?.let {
            throw McpToolException("$it Pide al usuario que haga el mapeo en la pantalla Importar de la app.")
        }
        val id = MembresiaMondayFlow.startRead(call.access, call.tenant, boardId, mapping, "agente: ${call.agentName}".take(200))
        return buildJsonObject {
            put("job_id", id.toString())
            putJsonArray("mapped_fields") { mapping.columns.keys.forEach { add(JsonPrimitive(it)) } }
            put("result", "Se esta leyendo el tablero; consulta monday_import_status en unos minutos.")
        }
    }

    private fun status(call: McpToolCall): JsonElement {
        val job = (call.uuid("job_id")?.let { MembresiaMondayJobs.find(call.tenant, it) ?: throw McpToolException("No se encontro la importacion.") }
            ?: MembresiaMondayJobs.recent(call.tenant, 1).firstOrNull())
            ?: throw McpToolException("Todavia no hay importaciones.")
        // Solo estado, conteos y codigos: nunca nombres ni texto libre de monday (tableros, planes, errores, problemas por fila).
        return buildJsonObject {
            put("job_id", job.id.toString())
            put("status", job.status)
            put("read_items", job.itemsRead)
            put("total", job.total)
            put("processed", job.applied)
            put("created", job.created)
            put("updated", job.updated)
            put("skipped", job.skipped)
            put("failed", job.failed)
            if (job.status == MondayJobStatus.Failed) put("result", "La importacion fallo; el motivo se ve en la pantalla Importar de la app.")
            job.summary?.takeIf { job.status == MondayJobStatus.Preview }?.let { summary ->
                putJsonObject("summary") {
                    put("members", summary.members)
                    put("visits", summary.visits)
                    put("new_plan_count", summary.newPlans.size)
                    putJsonObject("problem_codes") { summary.problemCodes.forEach { (code, count) -> put(code, count) } }
                }
            }
        }
    }

    private fun apply(call: McpToolCall): JsonElement {
        val id: UUID = call.requiredUuid("job_id")
        val job = MembresiaMondayJobs.find(call.tenant, id) ?: throw McpToolException("No se encontro la importacion.")
        // El agente no puede aplicar a ciegas: el usuario dicta la cantidad de miembros del resumen.
        val members = job.summary?.members
        if (job.status == MondayJobStatus.Preview && members != null && call.int("confirm_members", -1, -1..MaxMembers) != members) {
            throw McpToolException("'confirm_members' debe ser la cantidad de miembros del resumen ($members), dictada por el usuario.")
        }
        if (!MembresiaMondayJobs.requestApply(call.tenant, id)) {
            throw McpToolException("La importacion esta en estado '${job.status}'; solo se aplica una que este en revision (preview).")
        }
        return buildJsonObject {
            put("job_id", id.toString())
            put("result", "Se esta importando en segundo plano; consulta monday_import_status.")
        }
    }
}
