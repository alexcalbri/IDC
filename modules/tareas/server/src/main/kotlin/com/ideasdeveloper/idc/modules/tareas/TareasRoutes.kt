package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissionLookup
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Native screens of the module: GET and POST of each screen under /modules/tareas/ui. The literal routes (/files,
 * /files/{id}, /suggest/customers) are registered by TareasServerModule and win over "/{screen}".
 */
object TareasRoutes {
    private val log = LoggerFactory.getLogger(TareasRoutes::class.java)
    private val ScreenIds = setOf("mine", "boards", "board", "task", "new-task", "admin", "board-edit", ImportScreen)

    /** The screen to show next, with the query parameters it keeps. */
    private class Outcome(val screen: String, val params: Map<String, String>, val notice: UiNotice)

    private fun success(screen: String, params: Map<String, String>, text: String, tone: String = UiTone.Success) = Outcome(screen, params, UiNotice(text, tone))

    fun screens(route: Route, access: ModuleAccessService) {
        route.get("/{screen}") {
            val permissions = call.requireModuleAccess(access, TareasModule.id) ?: return@get
            val screen = call.parameters["screen"].orEmpty()
            // "importar" needs tareas.import (checked before the connection opens); every other screen needs tareas.view.
            if (!permissions.has(if (screen == ImportScreen) TareasModule.Import else TareasModule.View)) return@get call.forbidden()
            if (screen !in ScreenIds) return@get call.respond(HttpStatusCode.NotFound)
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            if (screen == ImportScreen) {
                return@get call.respondTareas(access, UiScreen.serializer()) { tenant -> importScreen(tenant, access, permissions, params, null) }
            }
            val customers = if (screen == "task" || screen == "new-task") canSeeCustomers(access, call) else false
            call.respondTareas(access, UiScreen.serializer()) { tenant ->
                open(tenant, permissions, customers, screen, params, null, null)
            }
        }

        route.post("/{screen}") {
            val permissions = call.requireModuleAccess(access, TareasModule.id) ?: return@post
            val screen = call.parameters["screen"].orEmpty()
            if (screen !in ScreenIds) return@post call.respond(HttpStatusCode.NotFound)
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            // The minimum permission is checked before the connection opens; the service applies the fine rules after.
            if (!permissions.has(TareasActions.requiredPermission(screen, params["op"]))) return@post call.forbidden()
            val length = call.request.headers["Content-Length"]?.toLongOrNull()
            val values = try {
                if (length != null && length > 1_000_000L) throw TareasValidationException(TareasActions.InvalidForm)
                TareasActions.formValues(call.receiveText())
            } catch (failure: TareasValidationException) {
                return@post call.respond(HttpStatusCode.BadRequest, TareasErrorResponse("invalid_request", failure.message.orEmpty()))
            }
            if (screen == ImportScreen) {
                return@post call.respondTareas(access, UiScreen.serializer()) { tenant -> importAct(tenant, access, permissions, params, values) }
            }
            val customers = if (screen == "task" || screen == "new-task") canSeeCustomers(access, call) else false
            call.respondTareas(access, UiScreen.serializer()) { tenant ->
                act(tenant, access, permissions, customers, screen, params, values)
            }
        }
    }

    private suspend fun ApplicationCall.forbidden() =
        respond(HttpStatusCode.Forbidden, TareasErrorResponse("missing_permission", "No tienes permiso para esta accion."))

    /** Customers appear in the task form only for who may see them (clientes.view). */
    private suspend fun canSeeCustomers(access: ModuleAccessService, call: ApplicationCall): Boolean {
        val lookup = withContext(Dispatchers.IO) {
            try {
                access.permissions(call, "clientes")
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                log.warn("No se pudo leer el permiso de clientes ({}).", failure.javaClass.simpleName)
                null
            }
        }
        return lookup is ModulePermissionLookup.Granted && lookup.permissions.has("clientes.view")
    }

    // ---- loading screens ----

    private fun context(tenant: Connection, permissions: ModulePermissions, customers: Boolean) = TareasScreens.Context(
        scope = TareasService.scope(tenant, permissions),
        users = TareasRepository.users(tenant),
        today = TareasRepository.today(),
        canSeeCustomers = customers,
        importEnabled = permissions.has(TareasModule.Import) && TareasImportSources.enabled(tenant, permissions.companyCode).isNotEmpty(),
    )

    // ---- import screen (the sources are extensions: see TareasImportSource) ----

    private const val ImportScreen = "importar"

    private fun importContext(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions) = TareasImportContext(
        access, tenant, permissions.companyCode, permissions, access.userIdentity(tenant, permissions)?.name ?: "Usuario", TareasRepository.today(),
    )

    private fun errorBody(text: String) = listOf<UiNode>(UiText(text, UiTextStyle.Body, UiTone.Danger))

    /** "Importar": one source's screen, or the buttons to choose among several. Needs tareas.import (checked by the route). */
    private fun importScreen(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, notice: UiNotice?): UiScreen {
        val scope = TareasService.scope(tenant, permissions)
        val sources = TareasImportSources.enabled(tenant, permissions.companyCode)
        fun screen(body: List<UiNode>) = UiScreen(
            title = "Importar",
            navigation = TareasScreens.navigation(scope, ImportScreen, import = sources.isNotEmpty()),
            notice = notice,
            body = body,
        )
        if (sources.isEmpty()) {
            return screen(listOf(UiText("No hay fuentes de importación activas. Instala y activa una extensión de importación de Tareas (por ejemplo Monday) y vuelve aquí.")))
        }
        val chosen = sources.firstOrNull { it.sourceId == params["source"] } ?: sources.singleOrNull()
        val picker = if (sources.size > 1) {
            listOf<UiNode>(UiRow(sources.map { UiButton(it.title, UiNavigate(ImportScreen, mapOf("source" to it.sourceId)), style = if (it === chosen) "primary" else "secondary") }))
        } else {
            emptyList()
        }
        if (chosen == null) return screen(picker + UiText("Elige desde dónde quieres importar.", UiTextStyle.Caption))
        val body = try {
            chosen.screen(importContext(tenant, access, permissions), params)
        } catch (failure: TareasValidationException) {
            errorBody(failure.message.orEmpty())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("No se pudo mostrar la fuente de importación {} ({}).", chosen.sourceId, failure.javaClass.simpleName)
            errorBody("No se pudo mostrar esta fuente de importación.")
        }
        return screen(picker + body)
    }

    private fun importAct(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, values: Map<String, String>): UiScreen {
        val source = TareasImportSources.enabled(tenant, permissions.companyCode).firstOrNull { it.sourceId == params["source"] }
            ?: return importScreen(tenant, access, permissions, emptyMap(), UiNotice("Esa fuente de importación no está disponible.", UiTone.Danger))
        val reply = try {
            source.action(importContext(tenant, access, permissions), params["op"].orEmpty(), values)
        } catch (failure: TareasValidationException) {
            TareasImportReply(failure.message.orEmpty(), error = true)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("Falló una operación de importación de {} ({}).", source.sourceId, failure.javaClass.simpleName)
            TareasImportReply("No se pudo completar la operación.", error = true)
        }
        return importScreen(tenant, access, permissions, reply.params + ("source" to source.sourceId), UiNotice(reply.notice, if (reply.error) UiTone.Danger else UiTone.Success))
    }

    private fun uuid(params: Map<String, String>, key: String): UUID? = TareasActions.uuid(params[key])

    private fun missing(what: String) = TareasValidationException(what)

    /** Builds [screen]. A rule that fails (a task that no longer exists, a screen the user cannot open) shows "Mis tareas" with the message. */
    private fun open(
        tenant: Connection,
        permissions: ModulePermissions,
        customers: Boolean,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
        loaded: TareasScreens.Context? = null,
    ): UiScreen {
        val context = loaded ?: context(tenant, permissions, customers)
        return try {
            build(tenant, context, screen, params, notice, submitted)
        } catch (failure: TareasValidationException) {
            build(tenant, context, "mine", emptyMap(), UiNotice(failure.message.orEmpty(), UiTone.Danger), null)
        }
    }

    private fun build(
        tenant: Connection,
        context: TareasScreens.Context,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
    ): UiScreen {
        val scope = context.scope
        fun visibleBoard(key: String): Board =
            uuid(params, key)?.let { TareasRepository.getBoard(tenant, it) }?.takeIf { scope.seesBoard(it) } ?: throw missing("Tablero no encontrado.")
        return when (screen) {
            "boards" -> TareasScreens.boards(context, TareasRepository.listBoards(tenant, scope), notice)
            "board" -> {
                val board = visibleBoard("id")
                val view = TareasScreens.viewOf(params)
                val filter = TareasActions.filter(params, context.users, board.id, TareasScreens.ListLimit + 1)
                val groups = TareasRepository.listGroups(tenant, board.id)
                val data = when (view) {
                    TareasScreens.ViewKanban -> TareasScreens.BoardData(
                        board, groups,
                        columns = TaskStatus.entries.associateWith { status ->
                            TareasRepository.list(tenant, scope, filter.copy(status = status, limit = TareasScreens.KanbanLimit + 1))
                        },
                    )
                    TareasScreens.ViewWorkload -> TareasScreens.BoardData(board, groups, workload = TareasRepository.workload(tenant, scope, board.id))
                    else -> TareasScreens.BoardData(board, groups, tasks = TareasRepository.list(tenant, scope, filter))
                }
                TareasScreens.board(context, data, params, notice)
            }
            "task" -> {
                val task = TareasService.visibleTask(tenant, scope, uuid(params, "id") ?: throw missing("Tarea no encontrada."))
                val customerName = task.customerId?.takeIf { context.canSeeCustomers }?.let { TareasRepository.customerName(tenant, it) }
                TareasScreens.task(
                    context,
                    TareasScreens.TaskDetail(
                        task = task,
                        groups = TareasRepository.listGroups(tenant, task.boardId),
                        checklist = TareasRepository.listChecklist(tenant, task.id),
                        comments = TareasRepository.listComments(tenant, task.id),
                        activity = TareasRepository.listActivity(tenant, task.id),
                        attachments = TareasRepository.listAttachments(tenant, task.id),
                        customerName = customerName,
                    ),
                    notice, submitted,
                )
            }
            "new-task" -> {
                if (!scope.canManage) throw missing("No tienes permiso para crear tareas.")
                val board = visibleBoard("board")
                val groups = TareasRepository.listGroups(tenant, board.id)
                TareasScreens.newTask(context, board, groups, uuid(params, "group"), notice, submitted)
            }
            "admin" -> {
                if (!scope.admin) throw missing("Solo quien administra tableros puede abrir esa pantalla.")
                TareasScreens.admin(context, TareasRepository.listBoards(tenant, scope, includeArchived = true), notice)
            }
            "board-edit" -> {
                if (!scope.admin) throw missing("Solo quien administra tableros puede abrir esa pantalla.")
                val board = uuid(params, "id")?.let { TareasRepository.getBoard(tenant, it) ?: throw missing("Tablero no encontrado.") }
                TareasScreens.boardEdit(context, board, board?.let { TareasRepository.listGroups(tenant, it.id) }.orEmpty(), notice, submitted)
            }
            else -> TareasScreens.mine(context, mineTasks(tenant, context), notice)
        }
    }

    /** Tasks the user is responsible for plus the ones waiting for their review (one extra row over the limit tells it was cut). */
    private fun mineTasks(tenant: Connection, context: TareasScreens.Context): List<Task> {
        val me = context.scope.userId ?: return emptyList()
        val own = TareasRepository.list(tenant, context.scope, TaskFilter(assignee = me, limit = TareasScreens.ListLimit + 1))
        return own + TareasRepository.reviewing(tenant, me, TareasScreens.ListLimit + 1)
    }

    // ---- actions ----

    /** Query parameters each screen keeps after an action (the rest belongs to the operation that just ran). */
    private fun keep(screen: String, params: Map<String, String>): Map<String, String> {
        val keys = when (screen) {
            "board" -> setOf("id", "view", "q", "assignee", "status", "due")
            "task", "board-edit" -> setOf("id")
            "new-task" -> setOf("board", "group")
            else -> emptySet()
        }
        return params.filterKeys { it in keys }
    }

    private fun act(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        customers: Boolean,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): UiScreen {
        val context = context(tenant, permissions, customers)
        val outcome = try {
            perform(tenant, access, permissions, context, screen, params, values)
        } catch (failure: TareasValidationException) {
            // The same screen again with the message and what the user typed.
            return open(tenant, permissions, customers, screen, keep(screen, params), UiNotice(failure.message.orEmpty(), UiTone.Danger), values, context)
        }
        return open(tenant, permissions, customers, outcome.screen, outcome.params, outcome.notice, null, context)
    }

    private fun perform(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        context: TareasScreens.Context,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): Outcome {
        val customers = context.canSeeCustomers
        val op = params["op"].orEmpty()
        val users = context.users
        fun requireId(key: String): UUID = uuid(params, key) ?: throw missing("Falta el elemento sobre el que actuar.")
        val same = keep(screen, params)
        fun taskOutcome(id: UUID, text: String, tone: String = UiTone.Success) = success("task", mapOf("id" to id.toString()), text, tone)
        fun statusOutcome(result: StatusResult, id: UUID): Outcome =
            if (result.redirectedToReview) {
                taskOutcome(id, "La tarea necesita revisión: pasó a En revisión.", UiTone.Info)
            } else {
                taskOutcome(id, "Estado: ${result.applied.label}.")
            }

        return when (screen) {
            "board" -> when (op) {
                "move" -> {
                    val id = requireId("card")
                    val target = TaskStatus.fromCode(params["column"]) ?: throw missing("Estado no válido.")
                    val result = TareasService.changeStatus(tenant, access, permissions, id, target)
                    val text = if (result.redirectedToReview) "La tarea necesita revisión: pasó a En revisión." else "Tarea movida a ${result.applied.label}."
                    success("board", same, text, if (result.redirectedToReview) UiTone.Info else UiTone.Success)
                }
                else -> throw missing("Acción no disponible.")
            }

            "task" -> {
                val id = requireId("id")
                when (op) {
                    "save" -> {
                        val scope = TareasService.scope(tenant, permissions)
                        val task = TareasService.visibleTask(tenant, scope, id)
                        val groups = TareasRepository.listGroups(tenant, task.boardId)
                        val input = TareasActions.taskInput(task.boardId, values, users, groups, customers, task)
                        TareasRepository.transaction(tenant) {
                            TareasService.updateTask(tenant, access, permissions, id, input, canSeeCustomers = customers)
                            applyFiles(tenant, permissions, id, values)
                        }
                        taskOutcome(id, "Cambios guardados.")
                    }
                    "files" -> {
                        applyFiles(tenant, permissions, id, values)
                        taskOutcome(id, "Adjuntos actualizados.")
                    }
                    "status" -> statusOutcome(TareasService.changeStatus(tenant, access, permissions, id, TareasActions.status(params["status"])), id)
                    "archive" -> {
                        val boardId = TareasService.visibleTask(tenant, TareasService.scope(tenant, permissions), id).boardId
                        TareasService.archive(tenant, access, permissions, id)
                        success("board", mapOf("id" to boardId.toString(), "view" to TareasScreens.ViewList), "Tarea archivada.")
                    }
                    "comment" -> {
                        TareasService.comment(tenant, access, permissions, id, values["body"].orEmpty())
                        taskOutcome(id, "Comentario añadido.")
                    }
                    "check-add" -> {
                        TareasService.addChecklistItem(tenant, permissions, id, values["text"].orEmpty())
                        taskOutcome(id, "Elemento añadido.")
                    }
                    "check-toggle" -> {
                        TareasService.toggleChecklistItem(tenant, permissions, requireId("item"), params["done"].toBoolean())
                        taskOutcome(id, "Lista actualizada.")
                    }
                    "check-remove" -> {
                        TareasService.removeChecklistItem(tenant, permissions, requireId("item"))
                        taskOutcome(id, "Elemento quitado.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            "new-task" -> when (op) {
                "create" -> {
                    val boardId = requireId("board")
                    val scope = TareasService.scope(tenant, permissions)
                    val board = TareasRepository.getBoard(tenant, boardId)?.takeIf { scope.seesBoard(it) } ?: throw missing("Tablero no encontrado.")
                    val input = TareasActions.taskInput(board.id, values, users, TareasRepository.listGroups(tenant, board.id), customers)
                    val created = TareasRepository.transaction(tenant) {
                        TareasService.createTask(tenant, access, permissions, input, canSeeCustomers = customers).also { applyFiles(tenant, permissions, it.id, values) }
                    }
                    taskOutcome(created.id, "Tarea creada.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "board-edit" -> {
                fun edited(id: UUID, text: String) = success("board-edit", mapOf("id" to id.toString()), text)
                when (op) {
                    "board-save" -> {
                        val form = TareasActions.boardForm(values, users)
                        val id = uuid(params, "id")
                        if (id == null) {
                            edited(TareasService.createBoard(tenant, permissions, form.name, form.description, form.visibility, form.members), "Tablero creado.")
                        } else {
                            TareasService.updateBoard(tenant, permissions, id, form.name, form.description, form.visibility, form.members)
                            edited(id, "Tablero guardado.")
                        }
                    }
                    "board-archive" -> {
                        TareasService.archiveBoard(tenant, permissions, requireId("id"))
                        success("admin", emptyMap(), "Tablero archivado.")
                    }
                    "group-add" -> {
                        val id = requireId("id")
                        TareasService.addGroup(tenant, permissions, id, values["name"].orEmpty())
                        edited(id, "Grupo añadido.")
                    }
                    "group-rename" -> {
                        val id = requireId("id")
                        TareasService.renameGroup(tenant, permissions, id, requireId("group"), values["name"].orEmpty())
                        edited(id, "Grupo renombrado.")
                    }
                    "group-up", "group-down" -> {
                        val id = requireId("id")
                        TareasService.moveGroup(tenant, permissions, id, requireId("group"), up = op == "group-up")
                        edited(id, "Grupo movido.")
                    }
                    "group-archive" -> {
                        val id = requireId("id")
                        TareasService.archiveGroup(tenant, permissions, id, requireId("group"))
                        edited(id, "Grupo archivado.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            else -> throw missing("Acción no disponible.")
        }
    }

    /** Links the new files of the form to the task and removes the ones the user took out. */
    private fun applyFiles(tenant: Connection, permissions: ModulePermissions, taskId: UUID, values: Map<String, String>) {
        val changes = TareasActions.fileChanges(values, TareasRepository.listAttachments(tenant, taskId))
        if (changes.added.isNotEmpty()) TareasService.attachFiles(tenant, permissions, taskId, changes.added)
        if (changes.removed.isNotEmpty()) TareasService.removeFiles(tenant, permissions, taskId, changes.removed)
    }
}
