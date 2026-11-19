package com.ideasdeveloper.idc.extensions.mantenimiento_monday

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
 * Herramientas de agentes de la importacion desde monday (permiso mantenimiento.import, como la pantalla Importar). El
 * token se guarda solo en la app: las herramientas usan el guardado, del lado del servidor, y nunca lo reciben ni lo
 * devuelven. El modulo Mantenimiento las publica como "mantenimiento_<nombre>" (la mas larga queda bajo el tope de 64).
 */
object MantenimientoMondayMcpTools {
    private const val MaxListed = 20

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "monday_list_boards",
            title = "Tableros de monday",
            description = "Lista los tableros de monday.com de la cuenta (id, nombre, espacio y numero de elementos), con el token guardado en la app.",
            permission = MantenimientoImportPermission,
            readOnly = true,
        ) { call -> guarded(call) { listBoards(call) } },
        McpTool(
            name = "monday_read_board",
            title = "Leer un tablero de monday",
            description = "Lee en segundo plano un tablero de monday (puede tardar minutos) con el mapeo de columnas sugerido automaticamente y deja la importacion " +
                "en revision; no crea nada todavia. target = 'plans' (cada elemento es un plan preventivo; la frecuencia sale del grupo) u 'orders' " +
                "(cada elemento es una orden; default_kind = 'corrective' o 'project'). Requiere gestionar mantenimiento. " +
                "Consulta el resumen con monday_import_status y, si el usuario lo confirma, usa monday_apply.",
            permission = MantenimientoImportPermission,
            inputSchema = McpSchema.obj(
                mapOf(
                    "board_id" to McpSchema.string("Id numerico del tablero de monday (de monday_list_boards)."),
                    "target" to McpSchema.string("A que se importa: planes preventivos u ordenes.", enum = listOf(MondayDestination.Plans, MondayDestination.Orders)),
                    "default_kind" to McpSchema.string(
                        "Tipo de las ordenes (solo con target 'orders'); por omision 'corrective'.",
                        enum = listOf(MondayDestination.KindCorrective, MondayDestination.KindProject),
                    ),
                ),
                required = listOf("board_id", "target"),
            ),
        ) { call -> guarded(call) { readBoard(call) } },
        McpTool(
            name = "monday_import_status",
            title = "Estado de la importacion de monday",
            description = "Estado de una importacion (la ultima si no se indica job_id): avance, conteos y, en revision, el resumen con propiedades, personas sin usuario y problemas.",
            permission = MantenimientoImportPermission,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("job_id" to McpSchema.uuid("Id de la importacion; opcional."))),
        ) { call -> guarded(call) { status(call) } },
        McpTool(
            name = "monday_apply",
            title = "Aplicar la importacion de monday",
            description = "Aplica una importacion que esta en revision: crea o actualiza los planes u ordenes con su lista de comprobacion, notas y archivos " +
                "(unas 100 filas por minuto, en segundo plano). Usala solo despues de mostrar el resumen al usuario y que lo confirme.",
            permission = MantenimientoImportPermission,
            inputSchema = McpSchema.obj(
                mapOf("job_id" to McpSchema.uuid("Id de la importacion en revision (de monday_read_board).")),
                required = listOf("job_id"),
            ),
        ) { call -> guarded(call) { apply(call) } },
    )

    // Cada herramienta respeta el permiso vivo del usuario y que la extension siga activa en la empresa.
    private fun guarded(call: McpToolCall, block: () -> JsonElement): JsonElement {
        if (!call.permissions.has(MantenimientoImportPermission)) throw McpToolException("No tienes permiso para importar datos.")
        if (!MantenimientoMondaySettings.extensionEnabled(call.tenant)) throw McpToolException("La extension de monday no esta activa en esta empresa.")
        try {
            return block()
        } catch (failure: MantenimientoMondayException) {
            throw McpToolException(failure.message.orEmpty())
        }
    }

    private fun token(call: McpToolCall): String =
        MantenimientoMondaySettings.loadToken(call.access.secrets, call.tenant)
            ?: throw McpToolException("Guarda primero el token de la API de monday en la app (Importar).")

    private fun listBoards(call: McpToolCall): JsonElement {
        MantenimientoMondayFlow.requireDestination(call.permissions)
        val boards = MantenimientoMondayClient.boards(token(call))
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
        if (!MantenimientoMondayClient.isBoardId(boardId)) throw McpToolException("El argumento 'board_id' debe ser un numero.")
        val target = call.requiredString("target", 10)
        if (!MondayDestination.isValid(target)) throw McpToolException("El argumento 'target' debe ser 'plans' u 'orders'.")
        val kind = call.string("default_kind", 12).ifEmpty { MondayDestination.KindCorrective }
        if (!MondayDestination.isValidKind(kind)) throw McpToolException("El argumento 'default_kind' debe ser 'corrective' o 'project'.")
        val board = MantenimientoMondayClient.boardInfo(token(call), boardId)
        val mapping = MantenimientoMondayMapping.suggest(board.columns)
        val id = MantenimientoMondayFlow.startRead(call.access, call.tenant, call.permissions, boardId, mapping, target, kind)
        val titles = board.columns.associate { it.id to it.title }
        return buildJsonObject {
            put("job_id", id.toString())
            put("board", board.name)
            put("target", target)
            putJsonObject("mapping") { mapping.columns.forEach { (targetName, columnId) -> put(targetName, titles[columnId].orEmpty()) } }
            put("result", "Se esta leyendo el tablero; consulta monday_import_status en unos minutos.")
        }
    }

    private fun status(call: McpToolCall): JsonElement {
        // Solo quien gestiona mantenimiento o quien inicio la importacion la ve.
        val job = (call.uuid("job_id")?.let {
            MantenimientoMondayJobs.find(call.tenant, it)?.takeIf { job -> MantenimientoMondayFlow.canSee(call.permissions, job) }
                ?: throw McpToolException("No se encontro la importacion.")
        } ?: MantenimientoMondayJobs.recent(call.tenant, 20).firstOrNull { MantenimientoMondayFlow.canSee(call.permissions, it) })
            ?: throw McpToolException("Todavia no hay importaciones.")
        return buildJsonObject {
            put("job_id", job.id.toString())
            put("board", job.boardName)
            put("target", job.target)
            put("status", job.status)
            put("message", job.message)
            put("total", job.total)
            put("processed", job.applied)
            put("created", job.created)
            put("updated", job.updated)
            put("skipped", job.skipped)
            put("failed", job.failed)
            put("files_done", job.filesDone)
            put("files_failed", job.filesFailed)
            if (job.status == MondayJobStatus.Preview) {
                val summary = job.summary?.toSummary()
                if (summary != null) {
                    putJsonObject("summary") {
                        put("plans", summary.plans)
                        put("orders", summary.orders)
                        put("checklist_items", summary.checklistItems)
                        put("notes", summary.notes)
                        put("files", summary.files)
                        putJsonArray("properties") { summary.properties.take(MaxListed).forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("assets") { summary.assets.take(MaxListed).forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("matched_people") { summary.matchedPeople.take(MaxListed).forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("unmatched_people") { summary.unmatchedPeople.take(MaxListed).forEach { add(JsonPrimitive(it)) } }
                        put("problem_count", job.summary?.problemCount ?: 0)
                        putJsonArray("problems") { summary.problems.take(MaxListed).forEach { add(JsonPrimitive(it)) } }
                        put("warning", MantenimientoMondayScreens.ReimportWarning)
                    }
                }
            }
        }
    }

    private fun apply(call: McpToolCall): JsonElement {
        val id: UUID = call.requiredUuid("job_id")
        val job = MantenimientoMondayJobs.find(call.tenant, id) ?: throw McpToolException("No se encontro la importacion.")
        MantenimientoMondayFlow.requireJobControl(call.permissions, job, apply = true)
        if (!MantenimientoMondayJobs.requestApply(call.tenant, id)) {
            throw McpToolException("La importacion esta en estado '${job.status}'; solo se aplica una que este en revision (preview).")
        }
        return buildJsonObject {
            put("job_id", id.toString())
            put("result", "Se esta importando en segundo plano; consulta monday_import_status.")
        }
    }
}
