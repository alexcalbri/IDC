package com.ideasdeveloper.idc.extensions.membresia_monday

import com.ideasdeveloper.idc.modules.membresia.MembresiaImportContext
import com.ideasdeveloper.idc.modules.membresia.MembresiaImportReply
import com.ideasdeveloper.idc.modules.membresia.MembresiaModule
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

/** Id de la extension (el mismo de extension.json y de MembresiaImportSource.sourceId). */
const val MembresiaMondayId = "membresia_monday"

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
object MembresiaMondayFlow {
    const val NoColumn = "No usar"
    private const val OptionSeparator = " · "

    /** Texto de la opcion de una columna en los selectores; el id va al final para recuperarlo aunque haya titulos repetidos. */
    fun columnOption(column: MondayColumn): String = "${column.title}$OptionSeparator${column.id}"

    fun columnIdOf(option: String): String? =
        option.takeIf { it.contains(OptionSeparator) }?.substringAfterLast(OptionSeparator)?.trim()?.takeIf { it.isNotEmpty() }

    /** Problema del mapeo que impide importar, o null. Hace falta el plan y las fechas (periodo, o inicio y fin). */
    fun mappingProblem(mapping: MondayMapping, columns: List<MondayColumn>): String? {
        val ids = columns.map { it.id }.toSet()
        if (mapping.columns.values.any { it !in ids }) return "El mapeo usa una columna que ya no existe en el tablero."
        val chosen = mapping.columns
        return when {
            MondayTarget.Plan !in chosen -> "Elige la columna del tipo de membresia: sin ella no se sabe a que plan pertenece cada miembro."
            MondayTarget.Period !in chosen && !(MondayTarget.Start in chosen && MondayTarget.End in chosen) ->
                "Elige la columna del periodo, o las de inicio y fin."
            else -> null
        }
    }

    /** Lee el tablero en segundo plano con el token guardado; el mapeo se comprueba contra las columnas reales. */
    fun startRead(access: ModuleAccessService, tenant: Connection, boardId: String, mapping: MondayMapping, author: String): UUID {
        val token = MembresiaMondaySettings.loadToken(access.secrets, tenant)
            ?: throw MembresiaMondayException("Guarda primero el token de la API de monday (pantalla Importar).")
        val board = MembresiaMondayClient.boardInfo(token, boardId)
        mappingProblem(mapping, board.columns)?.let { throw MembresiaMondayException(it) }
        return MembresiaMondayJobs.queueRead(tenant, boardId, board.name, mapping, author)
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

    /** Guardar o quitar el token lo hace el dueno de la empresa o quien administra los planes; leer y aplicar, membresia.import. */
    fun canManageToken(permissions: ModulePermissions): Boolean = permissions.businessOwner || permissions.has(MembresiaModule.Plans)
}

/** Pantalla de la fuente dentro de Importar. Muestra el token solo como "configurado"; nunca su valor. */
object MembresiaMondayScreens {
    private const val Route = "${MembresiaModule.UiRoute}/importar"
    private const val MaxProblems = 20

    private fun navigate(params: Map<String, String> = emptyMap()) = UiNavigate("importar", mapOf("source" to MembresiaMondayId) + params)

    private fun post(op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest(Route, "POST", mapOf("source" to MembresiaMondayId, "op" to op) + params, confirm, formId)

    fun screen(context: MembresiaImportContext, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(MembresiaModule.Import)) {
            return listOf(UiText("No tienes permiso para importar datos.", tone = UiTone.Warning))
        }
        val tenant = context.tenant
        return when (params["step"]) {
            "boards" -> boardsStep(context)
            "map" -> mapStep(context, params["board"].orEmpty())
            "job" -> params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { jobStep(tenant, it) } ?: home(context)
            else -> home(context)
        }
    }

    // ---- Inicio: token e historial ----

    private fun home(context: MembresiaImportContext): List<UiNode> {
        val tenant = context.tenant
        val configured = MembresiaMondaySettings.configured(tenant)
        val canManage = MembresiaMondayFlow.canManageToken(context.permissions)
        val tokenCard = UiCard(
            listOfNotNull(
                UiText(if (configured) "El token de monday esta configurado." else "Aun no hay un token de monday guardado.", tone = if (configured) UiTone.Success else UiTone.Warning),
                if (!canManage) null else UiForm(
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
                            listOfNotNull(
                                UiButton("Cargar tableros", post(MondayOp.Boards)),
                                if (canManage) UiButton("Quitar token", post(MondayOp.Forget, confirm = "Se borra el token de monday guardado. Continuar?"), style = "secondary") else null,
                            ),
                        ).flatten(),
                    )
                } else {
                    null
                },
            ),
            title = "monday",
        )
        val recent = MembresiaMondayJobs.recent(tenant, 8)
        val history = UiCard(
            listOf(
                UiList(
                    recent.map { job ->
                        UiListItem(
                            title = job.boardName.ifBlank { "Tablero ${job.boardId}" },
                            subtitle = job.message.ifBlank { "${job.total} elementos" },
                            trailing = job.createdAt.toString().take(10),
                            badges = listOf(UiBadge(MembresiaMondayFlow.statusLabel(job.status), MembresiaMondayFlow.statusTone(job.status))),
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

    private fun boardsStep(context: MembresiaImportContext): List<UiNode> {
        val token = MembresiaMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText("Guarda primero el token de la API de monday.", tone = UiTone.Danger), UiButton("Volver", navigate(), style = "secondary"))
        val boards = try {
            MembresiaMondayClient.boards(token)
        } catch (failure: MembresiaMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), UiButton("Volver", navigate(), style = "secondary"))
        }
        if (boards.isEmpty()) return listOf(UiText("El token no tiene acceso a ningun tablero activo.", tone = UiTone.Warning), UiButton("Volver", navigate(), style = "secondary"))
        return listOf(
            UiCard(
                listOf(
                    UiText("Elige el tablero de membresias que quieres importar."),
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
                    UiButton("Volver", navigate(), style = "secondary"),
                ),
                title = "Tableros de monday",
            ),
        )
    }

    // ---- Mapeo ----

    private fun mapStep(context: MembresiaImportContext, boardId: String): List<UiNode> {
        val back = UiButton("Elegir otro tablero", post(MondayOp.Boards), style = "secondary")
        val token = MembresiaMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText("Guarda primero el token de la API de monday.", tone = UiTone.Danger))
        val board = try {
            MembresiaMondayClient.boardInfo(token, boardId)
        } catch (failure: MembresiaMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        val suggested = MembresiaMondayMapping.suggest(board.columns)
        val byId = board.columns.associateBy { it.id }
        val options = listOf(MembresiaMondayFlow.NoColumn) + board.columns.filter { it.type != "name" && it.type != "subitems" }.map(MembresiaMondayFlow::columnOption)
        val fields = listOf(UiField("board", "Id del tablero", readOnly = true)) + MondayTarget.all.map { definition ->
            UiField("map:${definition.target}", definition.label, type = "dropdown", options = options)
        }
        val values = mapOf("board" to board.id) + MondayTarget.all.associate { definition ->
            "map:${definition.target}" to (suggested.columns[definition.target]?.let(byId::get)?.let(MembresiaMondayFlow::columnOption) ?: MembresiaMondayFlow.NoColumn)
        }
        return listOf(
            UiCard(
                listOf(
                    UiText("Tablero: ${board.name}. Revisa a que columna va cada dato; ya van elegidas las que parecen corresponder.", UiTextStyle.Body),
                    UiText("El nombre del miembro es el del elemento. Los subelementos con fecha se importan como visitas, y los que dicen invitado o guest, como visitas de invitado.", UiTextStyle.Caption),
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
        val job = MembresiaMondayJobs.find(tenant, id) ?: return listOf(UiText("No se encontro la importacion.", tone = UiTone.Danger), UiButton("Volver", navigate(), style = "secondary"))
        val refresh = UiButton("Actualizar", navigate(mapOf("step" to "job", "job" to id.toString())), style = "secondary")
        val cancel = UiButton("Cancelar importacion", post(MondayOp.Cancel, mapOf("job" to id.toString()), confirm = "Se cancela la importacion. Lo ya importado se conserva. Continuar?"), style = "danger")
        val back = UiButton("Volver", navigate(), style = "secondary")
        val header = UiText("Tablero: ${job.boardName.ifBlank { job.boardId }} · ${MembresiaMondayFlow.statusLabel(job.status)}", UiTextStyle.Subtitle)
        return when (job.status) {
            MondayJobStatus.Reading -> listOf(
                header,
                UiText("Se esta leyendo el tablero con sus subelementos: ${job.itemsRead} elementos leidos (${job.pagesRead} paginas). Un tablero grande tarda unos minutos; actualiza esta pantalla para seguir."),
                *listOfNotNull(job.message.takeIf { it.isNotBlank() }?.let { UiText(it, UiTextStyle.Caption) }).toTypedArray(),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Preview -> previewNodes(tenant, job, header, cancel)
            MondayJobStatus.Applying -> listOf(
                header,
                progress(job),
                UiText("Se importan unas 200 filas por minuto. Puedes salir de esta pantalla; la importacion sigue sola.", UiTextStyle.Caption),
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
        ),
        minItemWidth = 140,
    )

    private fun previewNodes(tenant: Connection, job: MondayJob, header: UiNode, cancel: UiNode): List<UiNode> {
        // El resumen se calculo una sola vez al terminar la lectura: aqui solo se lee.
        val summary = job.summary ?: return listOf(header, UiText("Esta importacion no tiene resumen; vuelve a leer el tablero.", tone = UiTone.Danger), cancel)
        val nodes = mutableListOf<UiNode>(
            header,
            UiGrid(
                listOf(
                    UiMetric("Miembros", summary.members.toString()),
                    UiMetric("Visitas", summary.visits.toString()),
                    UiMetric("Planes nuevos", summary.newPlans.size.toString()),
                    UiMetric("Problemas", summary.problemCodes.values.sum().toString(), captionTone = if (summary.problemCodes.isEmpty()) UiTone.Success else UiTone.Warning),
                ),
                minItemWidth = 140,
            ),
        )
        if (summary.newPlans.isNotEmpty()) {
            nodes += UiCard(
                listOf(
                    UiText("Estos tipos de membresia no existen como plan y se crean con el monto mas comun y la duracion tipica de sus periodos.", UiTextStyle.Caption),
                    UiTable(
                        listOf(UiTableColumn("name", "Plan"), UiTableColumn("price", "Precio"), UiTableColumn("days", "Dias"), UiTableColumn("guests", "Pases de invitado")),
                        summary.newPlans.map { mapOf("name" to it.name, "price" to it.price, "days" to it.durationDays.toString(), "guests" to it.guestPasses.toString()) },
                    ),
                ),
                title = "Planes que se crean",
            )
        }
        if (summary.customerFields.isNotEmpty()) {
            nodes += UiText("Campos que se crean en Clientes si faltan: ${summary.customerFields.joinToString()}.", UiTextStyle.Caption)
        }
        if (summary.problems.isNotEmpty()) {
            nodes += UiCard(
                summary.problems.take(MaxProblems).map { UiText(it) } +
                    listOfNotNull(summary.problems.size.takeIf { it > MaxProblems }?.let { UiText("Y ${it - MaxProblems} problemas mas.", UiTextStyle.Caption) }) +
                    UiText("Las filas con problemas se omiten al aplicar.", UiTextStyle.Caption),
                title = "Problemas encontrados",
            )
        }
        nodes += UiRow(
            listOf(
                UiButton("Aplicar importacion", post(MondayOp.Apply, mapOf("job" to job.id.toString()), confirm = "Se crean o actualizan ${summary.members} miembros con sus membresias y visitas. Continuar?")),
                cancel,
            ),
        )
        return nodes
    }

    private fun problemNodes(tenant: Connection, job: MondayJob): List<UiNode> {
        if (job.skipped + job.failed == 0) return emptyList()
        val rows = MembresiaMondayJobs.problems(tenant, job.id, 50)
        return listOf(
            UiCard(
                listOf(
                    UiTable(
                        listOf(UiTableColumn("row", "Fila"), UiTableColumn("result", "Resultado"), UiTableColumn("message", "Motivo")),
                        rows.map { mapOf("row" to it.rowNo.toString(), "result" to if (it.outcome == "failed") "Error" else "Omitida", "message" to it.message) },
                    ),
                ),
                title = "Filas sin importar",
            ),
        )
    }

    // ---- Acciones (POST importar?source=membresia_monday&op=...) ----

    private fun failure(message: String, params: Map<String, String> = emptyMap()) = MembresiaImportReply(message, error = true, params = params)

    fun action(context: MembresiaImportContext, op: String, values: Map<String, String>): MembresiaImportReply {
        // Guardar o quitar el token: dueno o membresia.plans; todo lo demas: membresia.import.
        val allowed = if (op == MondayOp.Token || op == MondayOp.Forget) MembresiaMondayFlow.canManageToken(context.permissions) else context.permissions.has(MembresiaModule.Import)
        if (!allowed) return failure("No tienes permiso para esta operacion.")
        val tenant = context.tenant
        return try {
            when (op) {
                MondayOp.Token -> saveToken(context, values)
                MondayOp.Forget -> {
                    MembresiaMondaySettings.saveToken(context.access.secrets, tenant, null)
                    MembresiaImportReply("Token de monday borrado.")
                }
                MondayOp.Boards -> MembresiaImportReply("Elige un tablero.", params = mapOf("step" to "boards"))
                MondayOp.Map -> {
                    val board = values["board"].orEmpty().trim()
                    if (!MembresiaMondayClient.isBoardId(board)) failure("Elige un tablero.", mapOf("step" to "boards"))
                    else MembresiaImportReply("Revisa el mapeo de columnas.", params = mapOf("step" to "map", "board" to board))
                }
                MondayOp.Read -> read(context, values)
                MondayOp.Apply -> {
                    val id = jobId(values)
                    if (MembresiaMondayJobs.requestApply(tenant, id)) {
                        MembresiaImportReply("Se esta importando.", params = mapOf("step" to "job", "job" to id.toString()))
                    } else {
                        failure("Esta importacion ya no esta en revision.", mapOf("step" to "job", "job" to id.toString()))
                    }
                }
                MondayOp.Cancel -> {
                    val id = jobId(values)
                    val done = MembresiaMondayJobs.cancel(tenant, id)
                    MembresiaImportReply(if (done) "Importacion cancelada." else "Esta importacion ya habia terminado.", error = !done, params = mapOf("step" to "job", "job" to id.toString()))
                }
                else -> failure("Operacion desconocida.")
            }
        } catch (problem: MembresiaMondayException) {
            failure(problem.message.orEmpty())
        }
    }

    private fun jobId(values: Map<String, String>): UUID =
        runCatching { UUID.fromString(values["job"].orEmpty().trim()) }.getOrNull() ?: throw MembresiaMondayException("No se encontro la importacion.")

    private fun saveToken(context: MembresiaImportContext, values: Map<String, String>): MembresiaImportReply {
        val tenant = context.tenant
        val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && it != SecretBox.Mask }
        if (typed == null && !MembresiaMondaySettings.configured(tenant)) return failure("Escribe el token de la API de monday.")
        typed?.let { MembresiaMondaySettings.saveToken(context.access.secrets, tenant, it) }
        return MembresiaImportReply("Token de monday guardado.", params = mapOf("step" to "boards"))
    }

    private fun read(context: MembresiaImportContext, values: Map<String, String>): MembresiaImportReply {
        val board = values["board"].orEmpty().trim()
        if (!MembresiaMondayClient.isBoardId(board)) return failure("Elige un tablero.", mapOf("step" to "boards"))
        val chosen = MondayTarget.all.mapNotNull { definition ->
            values["map:${definition.target}"]?.let(MembresiaMondayFlow::columnIdOf)?.let { definition.target to it }
        }.toMap()
        val id = try {
            MembresiaMondayFlow.startRead(context.access, context.tenant, board, MondayMapping(chosen), context.author)
        } catch (problem: MembresiaMondayException) {
            return failure(problem.message.orEmpty(), mapOf("step" to "map", "board" to board))
        }
        return MembresiaImportReply("Se esta leyendo el tablero de monday.", params = mapOf("step" to "job", "job" to id.toString()))
    }
}
