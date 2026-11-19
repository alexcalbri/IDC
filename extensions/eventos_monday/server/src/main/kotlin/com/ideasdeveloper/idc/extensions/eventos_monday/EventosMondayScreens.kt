package com.ideasdeveloper.idc.extensions.eventos_monday

import com.ideasdeveloper.idc.modules.eventos.EventosImportContext
import com.ideasdeveloper.idc.modules.eventos.EventosImportReply
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

/** Lo que comparten la pantalla y las herramientas MCP: leer un tablero con el token guardado y revisar el mapeo. */
object EventosMondayFlow {
    const val NoColumn = "No usar"
    const val SpacePrefix = "space:"
    const val ManagePermission = "eventos.manage"
    private const val OptionSeparator = " · "
    const val MaxGroups = 30

    /** Texto de la opcion de una columna en los selectores; el id va al final para recuperarlo aunque haya nombres repetidos. */
    fun columnOption(column: MondayColumn): String = "${column.title}$OptionSeparator${column.id}"

    fun columnIdOf(option: String): String? =
        option.takeIf { it.contains(OptionSeparator) }?.substringAfterLast(OptionSeparator)?.trim()?.takeIf { it.isNotEmpty() }

    /** Grupos del tablero que se muestran para fijar su espacio (los primeros [MaxGroups]). */
    fun shownGroups(groups: List<String>): List<String> = groups.take(MaxGroups)

    /** Quien queda como autor de lo importado: el usuario; un dueno del servidor no es usuario de la empresa. */
    fun actorOf(permissions: ModulePermissions): UUID? = permissions.userId?.takeUnless { permissions.serverOwner }

    /** Aplicar o cancelar una importacion: solo quien la inicio o quien gestiona los eventos. */
    fun requireJobControl(permissions: ModulePermissions, job: MondayJob) {
        val actor = actorOf(permissions)
        if (actor != null && job.createdBy == actor) return
        if (permissions.has(ManagePermission)) return
        throw EventosMondayException("Solo quien inicio la importacion o quien gestiona los eventos puede aplicarla o cancelarla.")
    }

    /** Problema del mapeo que impide importar, o null: solo se revisa que las columnas elegidas sigan existiendo. */
    fun mappingProblem(mapping: MondayMapping, columns: List<MondayColumn>): String? {
        val ids = columns.map { it.id }.toSet()
        return if (mapping.columns.values.any { it !in ids }) "El mapeo usa una columna que ya no existe en el tablero." else null
    }

    /** Lee el tablero en segundo plano con el token guardado; el mapeo se comprueba antes de encolar. */
    fun startRead(
        access: ModuleAccessService,
        tenant: Connection,
        permissions: ModulePermissions,
        boardId: String,
        mapping: MondayMapping,
    ): UUID {
        val token = EventosMondaySettings.loadToken(access.secrets, tenant)
            ?: throw EventosMondayException("Guarda primero el token de la API de monday (pantalla Importar).")
        val board = EventosMondayClient.boardInfo(token, boardId)
        mappingProblem(mapping, board.columns)?.let { throw EventosMondayException(it) }
        return EventosMondayJobs.queueRead(tenant, boardId, board.name, mapping, actorOf(permissions))
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
object EventosMondayScreens {
    private const val Route = "/modules/eventos/ui/importar"
    private const val MaxListed = 20
    const val ReimportWarning = "Reimportar el mismo tablero actualiza los eventos ya importados y sobrescribe los cambios hechos en Eventos a esos campos."

    private fun navigate(params: Map<String, String> = emptyMap()) = UiNavigate("importar", mapOf("source" to EventosMondayId) + params)

    private fun post(op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest(Route, "POST", mapOf("source" to EventosMondayId, "op" to op) + params, confirm, formId)

    fun screen(context: EventosImportContext, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(EventosImportPermission)) {
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

    private fun home(context: EventosImportContext): List<UiNode> {
        val tenant = context.tenant
        val configured = EventosMondaySettings.configured(tenant)
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
                    EventosMondayJobs.recent(tenant, 8).map { job ->
                        UiListItem(
                            title = job.boardName.ifBlank { "Tablero ${job.boardId}" },
                            subtitle = job.message.ifBlank { "${job.total} elementos" },
                            trailing = job.createdAt.toString().take(10),
                            badges = listOf(UiBadge(EventosMondayFlow.statusLabel(job.status), EventosMondayFlow.statusTone(job.status))),
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

    private fun boardsStep(context: EventosImportContext): List<UiNode> {
        val back = UiButton("Volver", navigate(), style = "secondary")
        val token = EventosMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText("Guarda primero el token de la API de monday.", tone = UiTone.Danger), back)
        val boards = try {
            EventosMondayClient.boards(token)
        } catch (failure: EventosMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        if (boards.isEmpty()) return listOf(UiText("El token no tiene acceso a ningun tablero activo.", tone = UiTone.Warning), back)
        return listOf(
            UiCard(
                listOf(
                    UiText("Elige el tablero de monday que quieres importar. Cada elemento pasa a ser un evento privado con su cliente."),
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

    // ---- Mapeo ----

    private fun mapStep(context: EventosImportContext, boardId: String): List<UiNode> {
        val back = UiButton("Elegir otro tablero", post(MondayOp.Boards), style = "secondary")
        val token = EventosMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText("Guarda primero el token de la API de monday.", tone = UiTone.Danger))
        val board = try {
            EventosMondayClient.boardInfo(token, boardId)
        } catch (failure: EventosMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        val groups = EventosMondayFlow.shownGroups(board.groups)
        val suggested = EventosMondayMapping.suggest(board.columns, groups)
        val byId = board.columns.associateBy { it.id }
        val columnOptions = listOf(EventosMondayFlow.NoColumn) + board.columns.filter { it.type != "name" && it.type != "subitems" }.map(EventosMondayFlow::columnOption)
        val fields = listOf(UiField("board", "Id del tablero de monday", readOnly = true)) +
            MondayTarget.all.map { definition -> UiField("map:${definition.target}", definition.label, type = "dropdown", options = columnOptions) } +
            groups.map { group ->
                UiField("${EventosMondayFlow.SpacePrefix}$group", "Espacio del grupo \"$group\"", help = "Se guarda como espacio del evento; vacio = sin espacio.")
            }
        val values = mapOf("board" to board.id) + MondayTarget.all.associate { definition ->
            "map:${definition.target}" to (suggested.columns[definition.target]?.let(byId::get)?.let(EventosMondayFlow::columnOption) ?: EventosMondayFlow.NoColumn)
        } + groups.associate { group -> "${EventosMondayFlow.SpacePrefix}$group" to suggested.groups[group].orEmpty() }
        return listOf(
            UiCard(
                listOf(
                    UiText("Tablero: ${board.name}. Revisa a que columna va cada dato; ya van elegidas las que parecen corresponder.", UiTextStyle.Body),
                    UiText(
                        "Cada elemento pasa a ser un evento privado y su contacto un cliente (se reutiliza por correo). El grupo fija el espacio; " +
                            "el grupo \"Booked\" fuerza el estado confirmado. Las columnas sin destino se agregan a las notas del evento.",
                        UiTextStyle.Caption,
                    ),
                    UiForm(
                        "mondayMapping", fields, values,
                        submit = UiButton("Leer tablero", post(MondayOp.Read, mapOf("board" to board.id), formId = "mondayMapping")),
                    ),
                    back,
                ),
                title = "Mapeo de columnas",
            ),
        )
    }

    // ---- Trabajo ----

    private fun jobStep(tenant: Connection, id: UUID): List<UiNode> {
        val back = UiButton("Volver", navigate(), style = "secondary")
        val job = EventosMondayJobs.find(tenant, id) ?: return listOf(UiText("No se encontro la importacion.", tone = UiTone.Danger), back)
        val refresh = UiButton("Actualizar", navigate(mapOf("step" to "job", "job" to id.toString())), style = "secondary")
        val cancel = UiButton("Cancelar importacion", post(MondayOp.Cancel, mapOf("job" to id.toString()), confirm = "Se cancela la importacion. Lo ya importado se conserva. Continuar?"), style = "danger")
        val header = UiText("Tablero: ${job.boardName.ifBlank { job.boardId }} · ${EventosMondayFlow.statusLabel(job.status)}", UiTextStyle.Subtitle)
        return when (job.status) {
            MondayJobStatus.Reading -> listOf(
                header,
                UiText("Se esta leyendo el tablero. Un tablero grande tarda unos minutos; actualiza esta pantalla para seguir."),
                UiText("Elementos leidos: ${job.total}", UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Preview -> previewNodes(job, header, cancel)
            MondayJobStatus.Applying -> listOf(
                header,
                progress(job),
                UiText("Se importan unos 100 eventos por minuto. Puedes salir de esta pantalla; la importacion sigue sola.", UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Done -> listOf(header, UiText(job.message, tone = UiTone.Success), progress(job)) + problemNodes(tenant, job) + back
            MondayJobStatus.Failed -> listOf(header, UiText(job.message.ifBlank { "No se pudo completar la importacion." }, tone = UiTone.Danger), progress(job), back)
            else -> listOf(header, UiText(job.message.ifBlank { "Importacion cancelada." }, tone = UiTone.Warning), progress(job), back)
        }
    }

    private fun progress(job: MondayJob): UiNode = UiGrid(
        listOf(
            UiMetric("Procesados", "${job.applied} de ${job.total}"),
            UiMetric("Creados", job.created.toString()),
            UiMetric("Actualizados", job.updated.toString()),
            UiMetric("Omitidos", job.skipped.toString()),
            UiMetric("Con error", job.failed.toString()),
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
                    UiMetric("Eventos", summary.events.toString()),
                    UiMetric("Clientes nuevos", summary.newCustomers.toString()),
                    UiMetric("Espacios", summary.bySpace.size.toString()),
                    UiMetric("Problemas", saved.problemCount.toString(), captionTone = if (saved.problemCount == 0) UiTone.Success else UiTone.Warning),
                ),
                minItemWidth = 140,
            ),
            UiText(ReimportWarning, UiTextStyle.Caption, tone = UiTone.Warning),
        )
        if (summary.bySpace.isNotEmpty()) {
            nodes += UiText("Por espacio: " + summary.bySpace.entries.take(MaxListed).joinToString { "${it.key.ifBlank { "(sin espacio)" }} ${it.value}" } + ".", UiTextStyle.Caption)
        }
        nodes += UiCard(
            listOf(
                UiText(if (summary.matchedPeople.isEmpty()) "Ningun responsable se empareja con un usuario de Eventos." else "Se emparejan con usuarios: ${summary.matchedPeople.take(MaxListed).joinToString()}."),
                UiText(
                    if (summary.unmatchedPeople.isEmpty()) "Todos los responsables tienen usuario."
                    else "Sin usuario (quedan anotados en las notas del evento): ${summary.unmatchedPeople.take(MaxListed).joinToString()}.",
                    tone = if (summary.unmatchedPeople.isEmpty()) UiTone.Success else UiTone.Warning,
                ),
            ),
            title = "Responsables",
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
                UiButton("Aplicar importacion", post(MondayOp.Apply, mapOf("job" to job.id.toString()), confirm = "Se crean o actualizan ${summary.events} eventos privados con sus clientes. $ReimportWarning Continuar?")),
                cancel,
            ),
        )
        return nodes
    }

    private fun problemNodes(tenant: Connection, job: MondayJob): List<UiNode> {
        val rows = EventosMondayJobs.problems(tenant, job.id, 50)
        if (rows.isEmpty()) return emptyList()
        return listOf(
            UiCard(
                listOf(
                    UiTable(
                        listOf(UiTableColumn("row", "Fila"), UiTableColumn("title", "Evento"), UiTableColumn("result", "Resultado"), UiTableColumn("message", "Motivo")),
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

    // ---- Acciones (POST importar?source=eventos_monday&op=...) ----

    private fun failure(message: String, params: Map<String, String> = emptyMap()) = EventosImportReply(message, error = true, params = params)

    fun action(context: EventosImportContext, op: String, values: Map<String, String>): EventosImportReply {
        if (!context.permissions.has(EventosImportPermission)) return failure("No tienes permiso para importar datos.")
        val tenant = context.tenant
        return try {
            when (op) {
                MondayOp.Token -> saveToken(context, values)
                MondayOp.Forget -> {
                    EventosMondaySettings.saveToken(context.access.secrets, tenant, null)
                    EventosImportReply("Token de monday borrado.")
                }
                MondayOp.Boards -> EventosImportReply("Elige un tablero.", params = mapOf("step" to "boards"))
                MondayOp.Map -> {
                    val board = values["board"].orEmpty().trim()
                    if (!EventosMondayClient.isBoardId(board)) failure("Elige un tablero.", mapOf("step" to "boards"))
                    else EventosImportReply("Revisa el mapeo de columnas.", params = mapOf("step" to "map", "board" to board))
                }
                MondayOp.Read -> read(context, values)
                MondayOp.Apply -> {
                    val id = jobId(values)
                    controlled(context, id)
                    if (EventosMondayJobs.requestApply(tenant, id, EventosMondayFlow.actorOf(context.permissions), context.permissions.serverOwner)) {
                        EventosImportReply("Se esta importando.", params = mapOf("step" to "job", "job" to id.toString()))
                    } else {
                        failure("Esta importacion ya no esta en revision.", mapOf("step" to "job", "job" to id.toString()))
                    }
                }
                MondayOp.Cancel -> {
                    val id = jobId(values)
                    controlled(context, id)
                    val done = EventosMondayJobs.cancel(tenant, id)
                    EventosImportReply(if (done) "Importacion cancelada." else "Esta importacion ya habia terminado.", error = !done, params = mapOf("step" to "job", "job" to id.toString()))
                }
                else -> failure("Operacion desconocida.")
            }
        } catch (problem: EventosMondayException) {
            failure(problem.message.orEmpty())
        }
    }

    // Solo quien inicio la importacion o quien gestiona los eventos la aplica o cancela.
    private fun controlled(context: EventosImportContext, id: UUID) {
        val job = EventosMondayJobs.find(context.tenant, id) ?: throw EventosMondayException("No se encontro la importacion.")
        EventosMondayFlow.requireJobControl(context.permissions, job)
    }

    private fun jobId(values: Map<String, String>): UUID =
        runCatching { UUID.fromString(values["job"].orEmpty().trim()) }.getOrNull() ?: throw EventosMondayException("No se encontro la importacion.")

    private fun saveToken(context: EventosImportContext, values: Map<String, String>): EventosImportReply {
        val tenant = context.tenant
        val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && it != SecretBox.Mask }
        if (typed == null && !EventosMondaySettings.configured(tenant)) return failure("Escribe el token de la API de monday.")
        typed?.let { EventosMondaySettings.saveToken(context.access.secrets, tenant, it) }
        return EventosImportReply("Token de monday guardado.", params = mapOf("step" to "boards"))
    }

    private fun read(context: EventosImportContext, values: Map<String, String>): EventosImportReply {
        val board = values["board"].orEmpty().trim()
        if (!EventosMondayClient.isBoardId(board)) return failure("Elige un tablero.", mapOf("step" to "boards"))
        val chosen = MondayTarget.all.mapNotNull { definition ->
            values["map:${definition.target}"]?.let(EventosMondayFlow::columnIdOf)?.let { definition.target to it }
        }.toMap()
        // El espacio de cada grupo (vacio = sin espacio); los titulos se recortan al largo del espacio del evento.
        val groups = values.filterKeys { it.startsWith(EventosMondayFlow.SpacePrefix) }.entries.take(EventosMondayFlow.MaxGroups)
            .associate { it.key.removePrefix(EventosMondayFlow.SpacePrefix).take(200) to it.value.trim().take(200) }
        val id = try {
            EventosMondayFlow.startRead(context.access, context.tenant, context.permissions, board, MondayMapping(chosen, groups))
        } catch (problem: EventosMondayException) {
            return failure(problem.message.orEmpty(), mapOf("step" to "map", "board" to board))
        }
        return EventosImportReply("Se esta leyendo el tablero de monday.", params = mapOf("step" to "job", "job" to id.toString()))
    }
}
