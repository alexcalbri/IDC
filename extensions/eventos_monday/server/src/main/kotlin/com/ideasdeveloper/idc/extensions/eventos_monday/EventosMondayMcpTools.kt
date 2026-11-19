package com.ideasdeveloper.idc.extensions.eventos_monday

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
 * Herramientas de agentes de la importacion desde monday (permiso eventos.import, como la pantalla Importar). El token
 * se guarda solo en la app: las herramientas usan el guardado, del lado del servidor, y nunca lo reciben ni lo devuelven.
 * El modulo Eventos las publica como "eventos_<nombre>" (la mas larga queda en 29 caracteres, bajo el tope de 64).
 */
object EventosMondayMcpTools {
    private const val MaxListed = 20

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "monday_list_boards",
            title = "Tableros de monday",
            description = "Lista los tableros de monday.com de la cuenta (id, nombre, espacio y numero de elementos), con el token guardado en la app.",
            permission = EventosImportPermission,
            readOnly = true,
        ) { call -> guarded(call) { listBoards(call) } },
        McpTool(
            name = "monday_read_board",
            title = "Leer un tablero de monday",
            description = "Lee en segundo plano un tablero de monday (puede tardar minutos) con el mapeo de columnas y de grupos a espacios sugerido automaticamente " +
                "y deja la importacion en revision; no crea nada todavia. Cada elemento sera un evento privado con su cliente. " +
                "Consulta el resumen con monday_import_status y, si el usuario lo confirma, usa monday_apply.",
            permission = EventosImportPermission,
            inputSchema = McpSchema.obj(
                mapOf("board_id" to McpSchema.string("Id numerico del tablero de monday (de monday_list_boards).")),
                required = listOf("board_id"),
            ),
        ) { call -> guarded(call) { readBoard(call) } },
        McpTool(
            name = "monday_import_status",
            title = "Estado de la importacion de monday",
            description = "Estado de una importacion (la ultima si no se indica job_id): avance, conteos y, en revision, el resumen con espacios, responsables sin usuario y problemas.",
            permission = EventosImportPermission,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("job_id" to McpSchema.uuid("Id de la importacion; opcional."))),
        ) { call -> guarded(call) { status(call) } },
        McpTool(
            name = "monday_apply",
            title = "Aplicar la importacion de monday",
            description = "Aplica una importacion que esta en revision: crea o actualiza los eventos privados y sus clientes (unos 100 eventos por minuto, en segundo plano). " +
                "Usala solo despues de mostrar el resumen al usuario y que lo confirme.",
            permission = EventosImportPermission,
            inputSchema = McpSchema.obj(
                mapOf("job_id" to McpSchema.uuid("Id de la importacion en revision (de monday_read_board).")),
                required = listOf("job_id"),
            ),
        ) { call -> guarded(call) { apply(call) } },
    )

    // Cada herramienta respeta el permiso vivo del usuario y que la extension siga activa en la empresa.
    private fun guarded(call: McpToolCall, block: () -> JsonElement): JsonElement {
        if (!call.permissions.has(EventosImportPermission)) throw McpToolException("No tienes permiso para importar datos.")
        if (!EventosMondaySettings.extensionEnabled(call.tenant)) throw McpToolException("La extension de monday no esta activa en esta empresa.")
        try {
            return block()
        } catch (failure: EventosMondayException) {
            throw McpToolException(failure.message.orEmpty())
        }
    }

    private fun token(call: McpToolCall): String =
        EventosMondaySettings.loadToken(call.access.secrets, call.tenant)
            ?: throw McpToolException("Guarda primero el token de la API de monday en la app (Importar).")

    private fun listBoards(call: McpToolCall): JsonElement {
        val boards = EventosMondayClient.boards(token(call))
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
        if (!EventosMondayClient.isBoardId(boardId)) throw McpToolException("El argumento 'board_id' debe ser un numero.")
        val board = EventosMondayClient.boardInfo(token(call), boardId)
        val mapping = EventosMondayMapping.suggest(board.columns, EventosMondayFlow.shownGroups(board.groups))
        val id = EventosMondayFlow.startRead(call.access, call.tenant, call.permissions, boardId, mapping)
        val titles = board.columns.associate { it.id to it.title }
        return buildJsonObject {
            put("job_id", id.toString())
            put("board", board.name)
            putJsonObject("mapping") { mapping.columns.forEach { (targetName, columnId) -> put(targetName, titles[columnId].orEmpty()) } }
            putJsonObject("group_spaces") { mapping.groups.forEach { (group, space) -> put(group, space) } }
            put("result", "Se esta leyendo el tablero; consulta monday_import_status en unos minutos.")
        }
    }

    private fun status(call: McpToolCall): JsonElement {
        val job = (call.uuid("job_id")?.let { EventosMondayJobs.find(call.tenant, it) ?: throw McpToolException("No se encontro la importacion.") }
            ?: EventosMondayJobs.recent(call.tenant, 1).firstOrNull())
            ?: throw McpToolException("Todavia no hay importaciones.")
        return buildJsonObject {
            put("job_id", job.id.toString())
            put("board", job.boardName)
            put("status", job.status)
            put("message", job.message)
            put("total", job.total)
            put("processed", job.applied)
            put("created", job.created)
            put("updated", job.updated)
            put("skipped", job.skipped)
            put("failed", job.failed)
            if (job.status == MondayJobStatus.Preview) {
                val summary = job.summary?.toSummary()
                if (summary != null) {
                    putJsonObject("summary") {
                        put("events", summary.events)
                        put("new_customers", summary.newCustomers)
                        putJsonObject("by_space") { summary.bySpace.entries.take(MaxListed).forEach { put(it.key, it.value) } }
                        putJsonArray("matched_people") { summary.matchedPeople.take(MaxListed).forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("unmatched_people") { summary.unmatchedPeople.take(MaxListed).forEach { add(JsonPrimitive(it)) } }
                        put("problem_count", job.summary?.problemCount ?: 0)
                        putJsonArray("problems") { summary.problems.take(MaxListed).forEach { add(JsonPrimitive(it)) } }
                        put("warning", EventosMondayScreens.ReimportWarning)
                    }
                }
            }
        }
    }

    private fun apply(call: McpToolCall): JsonElement {
        val id: UUID = call.requiredUuid("job_id")
        val job = EventosMondayJobs.find(call.tenant, id) ?: throw McpToolException("No se encontro la importacion.")
        EventosMondayFlow.requireJobControl(call.permissions, job)
        if (!EventosMondayJobs.requestApply(call.tenant, id, EventosMondayFlow.actorOf(call.permissions), call.permissions.serverOwner)) {
            throw McpToolException("La importacion esta en estado '${job.status}'; solo se aplica una que este en revision (preview).")
        }
        return buildJsonObject {
            put("job_id", id.toString())
            put("result", "Se esta importando en segundo plano; consulta monday_import_status.")
        }
    }
}
