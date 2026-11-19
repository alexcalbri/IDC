package com.ideasdeveloper.idc.extensions.tareas_monday

import com.ideasdeveloper.idc.modules.tareas.Board
import com.ideasdeveloper.idc.modules.tareas.TareasImportContext
import com.ideasdeveloper.idc.modules.tareas.TareasImportReply
import com.ideasdeveloper.idc.modules.tareas.TareasModule
import com.ideasdeveloper.idc.modules.tareas.TareasRepository
import com.ideasdeveloper.idc.modules.tareas.TareasService
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.sql.Connection
import java.util.UUID

/** Pasos y operaciones del flujo de la pantalla Importar de esta fuente. */
object MondayOp {
    const val Token = "token"
    const val Forget = "forget"
    const val Boards = "boards"
    const val Map = "map"
    const val Read = "read"
    const val Apply = "apply"
    const val Cancel = "cancel"
}

/** Lo que comparten la pantalla y las herramientas MCP: leer un tablero con el token guardado y revisar el mapeo y el destino. */
object TareasMondayFlow {
    const val NoColumn = "No usar"
    const val NewBoard = "Nuevo tablero"
    private const val OptionSeparator = " · "

    /** Texto de la opcion de una columna o tablero en los selectores; el id va al final para recuperarlo aunque haya nombres repetidos. */
    fun columnOption(column: MondayColumn): String = "${column.title}$OptionSeparator${column.id}"

    fun columnIdOf(option: String): String? =
        option.takeIf { it.contains(OptionSeparator) }?.substringAfterLast(OptionSeparator)?.trim()?.takeIf { it.isNotEmpty() }

    fun boardOption(board: Board): String = "${board.name}$OptionSeparator${board.id}"

    /** Id del tablero de Tareas elegido como destino; null con "Nuevo tablero" o un valor que no es un id. */
    fun targetOf(option: String?): UUID? =
        option?.substringAfterLast(OptionSeparator, "")?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Tableros de Tareas que quien importa ve (los mismos que ve en la app). */
    fun visibleBoards(tenant: Connection, permissions: ModulePermissions): List<Board> =
        TareasRepository.listBoards(tenant, TareasService.scope(tenant, permissions)).filter { !it.archived }

    /** Quien queda como autor de lo importado: el usuario; un dueno del servidor no es usuario de la empresa. */
    fun actorOf(permissions: ModulePermissions): UUID? = permissions.userId?.takeUnless { permissions.serverOwner }

    const val NewBoardDenied = "Crear un tablero nuevo requiere administrar los tableros de Tareas; elige un tablero existente."
    const val TargetDenied = "No tienes permiso para agregar tareas a ese tablero (o no existe)."

    /**
     * Opciones de destino de quien importa: tablero nuevo solo si administra tableros; uno existente solo si puede
     * editar tareas y lo ve (los mismos permisos que tendria al crear las tareas a mano).
     */
    fun destinations(tenant: Connection, permissions: ModulePermissions): Pair<Boolean, List<Board>> {
        val scope = TareasService.scope(tenant, permissions)
        val boards = if (scope.canManage) TareasRepository.listBoards(tenant, scope).filter { !it.archived && scope.seesBoard(it) } else emptyList()
        return scope.admin to boards
    }

    /** Revisa el destino elegido contra los permisos vivos; lanza con un mensaje claro si falta alguno. */
    fun requireDestination(tenant: Connection, permissions: ModulePermissions, targetBoardId: UUID?) {
        val (canCreate, boards) = destinations(tenant, permissions)
        if (targetBoardId == null) {
            if (!canCreate) throw TareasMondayException(NewBoardDenied)
        } else if (boards.none { it.id == targetBoardId }) {
            throw TareasMondayException(TargetDenied)
        }
    }

    /** Aplicar o cancelar una importacion: solo quien la inicio o un administrador de tableros. */
    fun requireJobControl(tenant: Connection, permissions: ModulePermissions, job: MondayJob) {
        val actor = actorOf(permissions)
        if (actor != null && job.createdBy == actor) return
        if (TareasService.scope(tenant, permissions).admin) return
        throw TareasMondayException("Solo quien inicio la importacion o un administrador de tableros puede aplicarla o cancelarla.")
    }

    /** Problema del mapeo que impide importar, o null: solo se revisa que las columnas elegidas sigan existiendo. */
    fun mappingProblem(mapping: MondayMapping, columns: List<MondayColumn>): String? {
        val ids = columns.map { it.id }.toSet()
        return if (mapping.columns.values.any { it !in ids }) "El mapeo usa una columna que ya no existe en el tablero." else null
    }

    /** Lee el tablero en segundo plano con el token guardado; el destino, el mapeo y los permisos se comprueban antes de encolar. */
    fun startRead(
        access: ModuleAccessService,
        tenant: Connection,
        permissions: ModulePermissions,
        boardId: String,
        mapping: MondayMapping,
        targetBoardId: UUID?,
    ): UUID {
        requireDestination(tenant, permissions, targetBoardId)
        val token = TareasMondaySettings.loadToken(access.secrets, tenant)
            ?: throw TareasMondayException("Guarda primero el token de la API de monday (pantalla Importar).")
        val board = TareasMondayClient.boardInfo(token, boardId)
        mappingProblem(mapping, board.columns)?.let { throw TareasMondayException(it) }
        return TareasMondayJobs.queueRead(tenant, boardId, board.name, targetBoardId, mapping, actorOf(permissions))
    }

    fun statusLabel(status: String): String = when (status) {
        MondayJobStatus.Reading -> "Leyendo"
        MondayJobStatus.Preview -> "Por revisar"
        MondayJobStatus.Applying -> "Aplicando"
        MondayJobStatus.Done -> "Terminada"
        MondayJobStatus.Failed -> "Con error"
        MondayJobStatus.Cancelled -> "Cancelada"
        else -> status
    }

    fun statusTone(status: String): String = when (status) {
        MondayJobStatus.Done -> UiTone.Success
        MondayJobStatus.Failed -> UiTone.Danger
        MondayJobStatus.Cancelled -> UiTone.Neutral
        MondayJobStatus.Preview -> UiTone.Warning
        else -> UiTone.Info
    }
}

/** Pantalla de la fuente dentro de Importar. Muestra el token solo como "configurado"; nunca su valor. */
object TareasMondayScreens {
    private const val Route = "${TareasModule.UiRoute}/importar"
    private const val MaxListed = 20
    const val ReimportWarning = "Reimportar el mismo tablero actualiza las tareas ya importadas y sobrescribe los cambios hechos en Tareas a esos campos."

    private fun navigate(params: Map<String, String> = emptyMap()) = UiNavigate("importar", mapOf("source" to TareasMondayId) + params)

    private fun post(op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest(Route, "POST", mapOf("source" to TareasMondayId, "op" to op) + params, confirm, formId)

    fun screen(context: TareasImportContext, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(TareasImportPermission)) {
            return listOf(UiText("No tienes permiso para importar datos.", tone = UiTone.Warning))
        }
        return when (params["step"]) {
            "boards" -> boardsStep(context)
            "map" -> mapStep(context, params["board"].orEmpty())
            "job" -> params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { jobStep(context.tenant, it) } ?: home(context)
            else -> home(context)
        }
    }

    // ---- Inicio: token e historial ----

    private fun home(context: TareasImportContext): List<UiNode> {
        val tenant = context.tenant
        val configured = TareasMondaySettings.configured(tenant)
        val tokenCard = UiCard(
            listOfNotNull(
                UiText(if (configured) "El token de monday esta configurado." else "Aun no hay un token de monday guardado.", tone = if (configured) UiTone.Success else UiTone.Warning),
                // El campo nunca se rellena: el token guardado no se vuelve a mostrar.
                UiForm(
                    "mondayToken",
                    listOf(
                        UiField(
                            "token", "Token de la API de monday", type = "password", required = !configured,
                            help = "monday > tu avatar > Developers > Mi token. Se guarda sellado para la empresa y no se vuelve a mostrar.",
                        ),
                    ),
                    submit = UiButton(if (configured) "Cambiar token" else "Guardar token", post(MondayOp.Token, formId = "mondayToken")),
                ),
                if (configured) {
                    UiRow(
                        listOf(
                            UiButton("Cargar tableros", post(MondayOp.Boards)),
                            UiButton("Quitar token", post(MondayOp.Forget, confirm = "Se borra el token de monday guardado. Continuar?"), style = "secondary"),
                        ),
                    )
                } else {
                    null
                },
            ),
            title = "monday",
        )
        val history = UiCard(
            listOf(
                UiList(
                    TareasMondayJobs.recent(tenant, 8).map { job ->
                        UiListItem(
                            title = job.boardName.ifBlank { "Tablero ${job.boardId}" },
                            subtitle = job.message.ifBlank { "${job.total} elementos" },
                            trailing = job.createdAt.toString().take(10),
                            badges = listOf(UiBadge(TareasMondayFlow.statusLabel(job.status), TareasMondayFlow.statusTone(job.status))),
                            action = navigate(mapOf("step" to "job", "job" to job.id.toString())),
                        )
                    },
                    emptyText = "Todavia no hay importaciones.",
                ),
            ),
            title = "Importaciones",
        )
        return listOf(tokenCard, history)
    }

    // ---- Tableros ----

    private fun boardsStep(context: TareasImportContext): List<UiNode> {
        val back = UiButton("Volver", navigate(), style = "secondary")
        val token = TareasMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText("Guarda primero el token de la API de monday.", tone = UiTone.Danger), back)
        val boards = try {
            TareasMondayClient.boards(token)
        } catch (failure: TareasMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        if (boards.isEmpty()) return listOf(UiText("El token no tiene acceso a ningun tablero activo.", tone = UiTone.Warning), back)
        return listOf(
            UiCard(
                listOf(
                    UiText("Elige el tablero de monday que quieres importar. Cada tablero de monday pasa a ser un tablero de Tareas."),
                    UiList(
                        boards.map { board ->
                            UiListItem(
                                title = board.name,
                                subtitle = board.workspace,
                                trailing = board.items?.let { "$it elementos" }.orEmpty(),
                                action = post(MondayOp.Map, mapOf("board" to board.id)),
                            )
                        },
                    ),
                    back,
                ),
                title = "Tableros de monday",
            ),
        )
    }

    // ---- Destino y mapeo ----

    private fun mapStep(context: TareasImportContext, boardId: String): List<UiNode> {
        val back = UiButton("Elegir otro tablero", post(MondayOp.Boards), style = "secondary")
        val token = TareasMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText("Guarda primero el token de la API de monday.", tone = UiTone.Danger))
        val board = try {
            TareasMondayClient.boardInfo(token, boardId)
        } catch (failure: TareasMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        val suggested = TareasMondayMapping.suggest(board.columns)
        val byId = board.columns.associateBy { it.id }
        val columnOptions = listOf(TareasMondayFlow.NoColumn) + board.columns.filter { it.type != "name" && it.type != "subitems" }.map(TareasMondayFlow::columnOption)
        val (canCreate, existing) = TareasMondayFlow.destinations(context.tenant, context.permissions)
        val destinations = (if (canCreate) listOf(TareasMondayFlow.NewBoard) else emptyList()) + existing.map(TareasMondayFlow::boardOption)
        if (destinations.isEmpty()) {
            return listOf(UiText("No tienes permiso para crear tableros ni para agregar tareas a uno existente.", tone = UiTone.Warning), back)
        }
        val fields = listOf(
            UiField("board", "Id del tablero de monday", readOnly = true),
            UiField("target", "Destino en Tareas", type = "dropdown", options = destinations, help = "Un tablero nuevo, o uno existente al que se agregan las tareas."),
        ) + MondayTarget.all.map { definition ->
            UiField("map:${definition.target}", definition.label, type = "dropdown", options = columnOptions)
        }
        val values = mapOf("board" to board.id, "target" to destinations.first()) + MondayTarget.all.associate { definition ->
            "map:${definition.target}" to (suggested.columns[definition.target]?.let(byId::get)?.let(TareasMondayFlow::columnOption) ?: TareasMondayFlow.NoColumn)
        }
        return listOf(
            UiCard(
                listOf(
                    UiText("Tablero: ${board.name}. Revisa a que columna va cada dato; ya van elegidas las que parecen corresponder.", UiTextStyle.Body),
                    UiText(
                        "Los grupos de monday son los grupos del tablero. Los subelementos pasan a la lista de pasos, las actualizaciones a comentarios y los archivos a adjuntos. " +
                            "Las columnas sin destino se agregan al detalle de la tarea.",
                        UiTextStyle.Caption,
                    ),
                    UiForm(
                        "mondayMapping", fields, values,
                        submit = UiButton("Leer tablero", post(MondayOp.Read, mapOf("board" to board.id), formId = "mondayMapping")),
                    ),
                    back,
                ),
                title = "Destino y mapeo de columnas",
            ),
        )
    }

    // ---- Trabajo ----

    private fun jobStep(tenant: Connection, id: UUID): List<UiNode> {
        val back = UiButton("Volver", navigate(), style = "secondary")
        val job = TareasMondayJobs.find(tenant, id) ?: return listOf(UiText("No se encontro la importacion.", tone = UiTone.Danger), back)
        val refresh = UiButton("Actualizar", navigate(mapOf("step" to "job", "job" to id.toString())), style = "secondary")
        val cancel = UiButton("Cancelar importacion", post(MondayOp.Cancel, mapOf("job" to id.toString()), confirm = "Se cancela la importacion. Lo ya importado se conserva. Continuar?"), style = "danger")
        val header = UiText("Tablero: ${job.boardName.ifBlank { job.boardId }} · ${TareasMondayFlow.statusLabel(job.status)}", UiTextStyle.Subtitle)
        return when (job.status) {
            MondayJobStatus.Reading -> listOf(
                header,
                UiText("Se esta leyendo el tablero con sus subelementos, actualizaciones y archivos. Un tablero grande tarda unos minutos; actualiza esta pantalla para seguir."),
                UiText("Elementos leidos: ${job.total}", UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Preview -> previewNodes(job, header, cancel)
            MondayJobStatus.Applying -> listOf(
                header,
                progress(job),
                UiText("Se importan unas 100 tareas por minuto. Puedes salir de esta pantalla; la importacion sigue sola.", UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Done -> listOf(header, UiText(job.message, tone = UiTone.Success), progress(job)) + problemNodes(tenant, job) + back
            MondayJobStatus.Failed -> listOf(header, UiText(job.message.ifBlank { "No se pudo completar la importacion." }, tone = UiTone.Danger), progress(job), back)
            else -> listOf(header, UiText(job.message.ifBlank { "Importacion cancelada." }, tone = UiTone.Warning), progress(job), back)
        }
    }

    private fun progress(job: MondayJob): UiNode = UiGrid(
        listOf(
            UiMetric("Procesadas", "${job.applied} de ${job.total}"),
            UiMetric("Creadas", job.created.toString()),
            UiMetric("Actualizadas", job.updated.toString()),
            UiMetric("Omitidas", job.skipped.toString()),
            UiMetric("Con error", job.failed.toString()),
            UiMetric("Archivos", job.filesDone.toString(), caption = if (job.filesFailed > 0) "${job.filesFailed} con error" else "", captionTone = if (job.filesFailed > 0) UiTone.Warning else UiTone.Neutral),
        ),
        minItemWidth = 140,
    )

    private fun previewNodes(job: MondayJob, header: UiNode, cancel: UiNode): List<UiNode> {
        // El resumen se calculo y guardo una vez al terminar de leer: la pantalla solo lo muestra.
        val saved = job.summary ?: return listOf(header, UiText("La vista previa de esta importacion no esta disponible; cancelala y vuelve a leer el tablero.", tone = UiTone.Danger), cancel)
        val summary = saved.toSummary()
        val nodes = mutableListOf<UiNode>(
            header,
            UiGrid(
                listOf(
                    UiMetric("Tareas", summary.tasks.toString()),
                    UiMetric("Grupos", summary.groups.size.toString()),
                    UiMetric("Pasos de lista", summary.checklistItems.toString()),
                    UiMetric("Comentarios", summary.comments.toString()),
                    UiMetric("Archivos", summary.files.toString()),
                    UiMetric("Problemas", saved.problemCount.toString(), captionTone = if (saved.problemCount == 0) UiTone.Success else UiTone.Warning),
                ),
                minItemWidth = 140,
            ),
            UiText(ReimportWarning, UiTextStyle.Caption, tone = UiTone.Warning),
        )
        if (summary.groups.isNotEmpty()) {
            nodes += UiText("Grupos: ${summary.groups.take(MaxListed).joinToString()}${if (summary.groups.size > MaxListed) " y ${summary.groups.size - MaxListed} mas" else ""}.", UiTextStyle.Caption)
        }
        nodes += UiCard(
            listOf(
                UiText(if (summary.matchedPeople.isEmpty()) "Ninguna persona se empareja con un usuario de Tareas." else "Se emparejan con usuarios: ${summary.matchedPeople.take(MaxListed).joinToString()}."),
                UiText(
                    if (summary.unmatchedPeople.isEmpty()) "Todas las personas tienen usuario."
                    else "Sin usuario (quedan anotadas en el detalle de la tarea): ${summary.unmatchedPeople.take(MaxListed).joinToString()}.",
                    tone = if (summary.unmatchedPeople.isEmpty()) UiTone.Success else UiTone.Warning,
                ),
            ),
            title = "Personas",
        )
        if (summary.problems.isNotEmpty()) {
            nodes += UiCard(
                summary.problems.take(MaxListed).map { UiText(it) } +
                    listOfNotNull(saved.problemCount.takeIf { it > MaxListed }?.let { UiText("Y ${it - MaxListed} problemas mas.", UiTextStyle.Caption) }) +
                    UiText("Las filas con problemas se omiten al aplicar.", UiTextStyle.Caption),
                title = "Problemas encontrados",
            )
        }
        nodes += UiRow(
            listOf(
                UiButton("Aplicar importacion", post(MondayOp.Apply, mapOf("job" to job.id.toString()), confirm = "Se crean o actualizan ${summary.tasks} tareas con sus pasos, comentarios y archivos. $ReimportWarning Continuar?")),
                cancel,
            ),
        )
        return nodes
    }

    private fun problemNodes(tenant: Connection, job: MondayJob): List<UiNode> {
        val rows = TareasMondayJobs.problems(tenant, job.id, 50)
        if (rows.isEmpty()) return emptyList()
        return listOf(
            UiCard(
                listOf(
                    UiTable(
                        listOf(UiTableColumn("row", "Fila"), UiTableColumn("title", "Tarea"), UiTableColumn("result", "Resultado"), UiTableColumn("message", "Motivo")),
                        rows.map { mapOf("row" to it.rowNo.toString(), "title" to it.title, "result" to resultLabel(it.outcome), "message" to it.message) },
                    ),
                ),
                title = "Filas con problemas",
            ),
        )
    }

    private fun resultLabel(outcome: String) = when (outcome) {
        "failed" -> "Error"
        "skipped" -> "Omitida"
        else -> "Con avisos"
    }

    // ---- Acciones (POST importar?source=tareas_monday&op=...) ----

    private fun failure(message: String, params: Map<String, String> = emptyMap()) = TareasImportReply(message, error = true, params = params)

    fun action(context: TareasImportContext, op: String, values: Map<String, String>): TareasImportReply {
        if (!context.permissions.has(TareasImportPermission)) return failure("No tienes permiso para importar datos.")
        val tenant = context.tenant
        return try {
            when (op) {
                MondayOp.Token -> saveToken(context, values)
                MondayOp.Forget -> {
                    TareasMondaySettings.saveToken(context.access.secrets, tenant, null)
                    TareasImportReply("Token de monday borrado.")
                }
                MondayOp.Boards -> TareasImportReply("Elige un tablero.", params = mapOf("step" to "boards"))
                MondayOp.Map -> {
                    val board = values["board"].orEmpty().trim()
                    if (!TareasMondayClient.isBoardId(board)) failure("Elige un tablero.", mapOf("step" to "boards"))
                    else TareasImportReply("Revisa el destino y el mapeo de columnas.", params = mapOf("step" to "map", "board" to board))
                }
                MondayOp.Read -> read(context, values)
                MondayOp.Apply -> {
                    val id = jobId(values)
                    controlled(context, id)
                    if (TareasMondayJobs.requestApply(tenant, id)) {
                        TareasImportReply("Se esta importando.", params = mapOf("step" to "job", "job" to id.toString()))
                    } else {
                        failure("Esta importacion ya no esta en revision.", mapOf("step" to "job", "job" to id.toString()))
                    }
                }
                MondayOp.Cancel -> {
                    val id = jobId(values)
                    controlled(context, id)
                    val done = TareasMondayJobs.cancel(tenant, id)
                    TareasImportReply(if (done) "Importacion cancelada." else "Esta importacion ya habia terminado.", error = !done, params = mapOf("step" to "job", "job" to id.toString()))
                }
                else -> failure("Operacion desconocida.")
            }
        } catch (problem: TareasMondayException) {
            failure(problem.message.orEmpty())
        }
    }

    // Solo quien inicio la importacion o un administrador de tableros la aplica o cancela.
    private fun controlled(context: TareasImportContext, id: UUID) {
        val job = TareasMondayJobs.find(context.tenant, id) ?: throw TareasMondayException("No se encontro la importacion.")
        TareasMondayFlow.requireJobControl(context.tenant, context.permissions, job)
    }

    private fun jobId(values: Map<String, String>): UUID =
        runCatching { UUID.fromString(values["job"].orEmpty().trim()) }.getOrNull() ?: throw TareasMondayException("No se encontro la importacion.")

    private fun saveToken(context: TareasImportContext, values: Map<String, String>): TareasImportReply {
        val tenant = context.tenant
        val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && it != SecretBox.Mask }
        if (typed == null && !TareasMondaySettings.configured(tenant)) return failure("Escribe el token de la API de monday.")
        typed?.let { TareasMondaySettings.saveToken(context.access.secrets, tenant, it) }
        return TareasImportReply("Token de monday guardado.", params = mapOf("step" to "boards"))
    }

    private fun read(context: TareasImportContext, values: Map<String, String>): TareasImportReply {
        val board = values["board"].orEmpty().trim()
        if (!TareasMondayClient.isBoardId(board)) return failure("Elige un tablero.", mapOf("step" to "boards"))
        val chosen = MondayTarget.all.mapNotNull { definition ->
            values["map:${definition.target}"]?.let(TareasMondayFlow::columnIdOf)?.let { definition.target to it }
        }.toMap()
        val target = TareasMondayFlow.targetOf(values["target"])
        val id = try {
            TareasMondayFlow.startRead(context.access, context.tenant, context.permissions, board, MondayMapping(chosen), target)
        } catch (problem: TareasMondayException) {
            return failure(problem.message.orEmpty(), mapOf("step" to "map", "board" to board))
        }
        return TareasImportReply("Se esta leyendo el tablero de monday.", params = mapOf("step" to "job", "job" to id.toString()))
    }
}
