package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * Agent tools of Tareas. Every write goes through [TareasService] (the same rules as the screens) and every read through
 * [TareasRepository] with the user's [TareasScope], so an agent sees and changes only what its user can. The handlers run
 * on the bridge's tool thread, which is where the JDBC calls belong.
 */
object TareasMcpTools {
    const val Instructions =
        "Tareas organiza el trabajo de la empresa en tableros con grupos. Cada tarea tiene un estado fijo, una prioridad, una fecha límite, " +
            "varios responsables y un revisor opcional. Estados (códigos): pending Pendiente, in_progress En curso, review En revisión, done Hecha, " +
            "cancelled Cancelada. Prioridades: urgent, high, medium (por defecto), low. Los grupos de un tablero son listas libres que la empresa " +
            "nombra como quiere: semanas (\"Esta semana\", \"Próxima semana\") o departamentos; se indican por su nombre. " +
            "Solo ves los tableros y tareas que tu usuario puede ver (un tablero privado solo lo ven sus miembros y los administradores; " +
            "el responsable o el revisor de una tarea siempre ve esa tarea) y solo cambias lo que sus permisos permiten. " +
            "Para volcar un acta o una lista de puntos: 1) list_boards para elegir el tablero y sus grupos; 2) list_people para conocer a los " +
            "responsables (se nombran como los devuelve list_people o por su correo); 3) create_task una vez por punto, con un external_ref " +
            "estable por punto (por ejemplo \"acta-2026-11-18-punto-3\"): si repites la carga, la tarea existente no se duplica (created=false). " +
            "Antes de crear muchas tareas muestra al usuario el resumen y espera su confirmación. " +
            "Revisión: si una tarea tiene revisor, pedir complete_task (o status=done) por alguien que no es el revisor la deja En revisión y avisa al revisor; " +
            "solo el revisor (o quien edita la tarea) la aprueba (done) o la devuelve (in_progress). " +
            "update_task solo cambia lo que envías; con due_date vacío se quita la fecha. Quien es responsable o revisor puede cambiar el estado, " +
            "marcar la lista de comprobación y comentar su tarea aunque no pueda editarla. Los archivos adjuntos se gestionan solo desde la app."

    private val priorityCodes = TaskPriority.entries.map { it.code }
    private val statusCodes = TaskStatus.entries.map { it.code }

    private fun stringArray(description: String, itemDescription: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", McpSchema.string(itemDescription))
    }

    private val assigneesSchema = stringArray(
        "Responsables, por nombre o correo tal como los devuelve list_people. En update_task reemplaza a los actuales; si no lo envías se conservan.",
        "Nombre o correo del usuario.",
    )

    private val taskFields = mapOf(
        "title" to McpSchema.string("Título de la tarea (hasta ${TaskInput.MaxTitle} caracteres)."),
        "detail" to McpSchema.string("Descripción o notas de la tarea (hasta ${TaskInput.MaxDetail} caracteres)."),
        "group" to McpSchema.string("Nombre del grupo del tablero (ver list_boards), por ejemplo una semana o un departamento."),
        "priority" to McpSchema.string("Prioridad; por defecto medium.", priorityCodes),
        "due_date" to McpSchema.date("Fecha límite."),
        "assignees" to assigneesSchema,
        "reviewer" to McpSchema.string("Revisor, por nombre o correo (ver list_people). En update_task, vacío lo quita."),
        "customer_id" to McpSchema.uuid("Id de un cliente de Clientes ligado a la tarea. En update_task, vacío lo quita."),
    )

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "list_boards",
            title = "Tableros y grupos",
            description = "Lista los tableros que el usuario puede ver, con sus grupos (nombres), su visibilidad y cuántas tareas abiertas y vencidas tienen. Úsalo antes de crear tareas.",
            permission = TareasModule.View,
            readOnly = true,
        ) { call -> guarded { listBoards(call) } },
        McpTool(
            name = "list_people",
            title = "Personas asignables",
            description = "Lista los usuarios que pueden ser responsables o revisores (nombre y correo). Usa estos nombres o correos en assignees y reviewer.",
            permission = TareasModule.View,
            readOnly = true,
        ) { call -> guarded { listPeople(call) } },
        McpTool(
            name = "list_tasks",
            title = "Buscar tareas",
            description = "Lista las tareas que el usuario puede ver, ordenadas por fecha límite y prioridad. Por defecto solo las abiertas (ni hechas ni canceladas). " +
                "Filtra por tablero, responsable (\"me\" para las del usuario), estado, vencimiento o texto en título y detalle.",
            permission = TareasModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "board_id" to McpSchema.uuid("Solo tareas de este tablero."),
                    "assignee" to McpSchema.string("Solo tareas de este responsable: \"me\" para el usuario, o un id, nombre o correo."),
                    "status" to McpSchema.string("Solo tareas en este estado (incluye hechas y canceladas si se pide).", statusCodes),
                    "due" to McpSchema.string("Vencimiento: overdue vencidas, today hoy, week próximos 7 días, none sin fecha.", DueFilter.entries.map { it.code }),
                    "query" to McpSchema.string("Texto a buscar en título y detalle."),
                    "include_done" to McpSchema.boolean("Incluir tareas hechas y canceladas (por defecto no)."),
                    "limit" to McpSchema.integer("Cantidad máxima de tareas; por defecto 50.", 1, 200),
                ),
            ),
        ) { call -> guarded { listTasks(call) } },
        McpTool(
            name = "get_task",
            title = "Ver tarea",
            description = "Devuelve una tarea visible con su detalle, lista de comprobación (con ids de cada elemento), comentarios y actividad recientes, nombres de los adjuntos y lo que el usuario puede hacer con ella.",
            permission = TareasModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id de la tarea.")), required = listOf("id")),
        ) { call -> guarded { getTask(call) } },
        McpTool(
            name = "create_task",
            title = "Crear tarea",
            description = "Crea una tarea en un tablero visible. Con external_ref la operación es idempotente: si ya existe una tarea creada por agentes con esa referencia no se duplica y se devuelve created=false. " +
                "Cada responsable nuevo recibe el aviso Te asignaron una tarea. Si el grupo no existe, quien administra tableros lo crea; los demás reciben un error.",
            permission = TareasModule.Manage,
            inputSchema = McpSchema.obj(
                taskFields + mapOf(
                    "board_id" to McpSchema.uuid("Id del tablero (ver list_boards)."),
                    "checklist" to stringArray("Elementos de la lista de comprobación, en orden.", "Texto del elemento."),
                    "external_ref" to McpSchema.string("Referencia estable de este punto (hasta ${ExternalTaskRequest.MaxExternalRef} caracteres) para no duplicarlo al repetir la carga."),
                ),
                required = listOf("board_id", "title"),
            ),
        ) { call -> guarded { createTask(call) } },
        McpTool(
            name = "update_task",
            title = "Editar tarea",
            description = "Modifica una tarea visible: solo cambia lo que envías (due_date vacío quita la fecha; reviewer o customer_id vacíos los quitan; assignees reemplaza a los responsables). " +
                "Cambiar el estado (status) lo puede hacer también un responsable o el revisor de la tarea; cualquier otro cambio exige el permiso de gestionar tareas. " +
                "El estado sigue las reglas de revisión: con revisor, pedir done por quien no es el revisor deja la tarea En revisión.",
            // View: a responsible or the reviewer can change the state; the other fields still ask for tareas.manage inside.
            permission = TareasModule.View,
            inputSchema = McpSchema.obj(
                taskFields + mapOf(
                    "id" to McpSchema.uuid("Id de la tarea."),
                    "board_id" to McpSchema.uuid("Mover la tarea a otro tablero visible (el grupo se quita si no envías group)."),
                    "status" to McpSchema.string("Nuevo estado.", statusCodes),
                ),
                required = listOf("id"),
            ),
        ) { call -> guarded { updateTask(call) } },
        McpTool(
            name = "complete_task",
            title = "Completar tarea",
            description = "Marca una tarea como hecha. Si la tarea tiene revisor y el usuario no es el revisor ni puede editarla, pasa a En revisión y se avisa al revisor.",
            permission = TareasModule.View,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id de la tarea.")), required = listOf("id")),
        ) { call -> guarded { completeTask(call) } },
        McpTool(
            name = "add_comment",
            title = "Comentar tarea",
            description = "Agrega un comentario a una tarea que el usuario puede trabajar (editarla, o ser su responsable o revisor). Avisa a los responsables y al revisor.",
            permission = TareasModule.View,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.uuid("Id de la tarea."),
                    "body" to McpSchema.string("Texto del comentario (hasta ${TareasRepository.MaxCommentLength} caracteres)."),
                ),
                required = listOf("id", "body"),
            ),
        ) { call -> guarded { addComment(call) } },
        McpTool(
            name = "set_checklist_item",
            title = "Marcar la lista de comprobación",
            description = "Marca o desmarca un elemento de la lista de comprobación de una tarea (done por defecto true). Indica el elemento con item_id (ver get_task) o con su texto; " +
                "si envías un texto que no existe en la lista, se añade como elemento nuevo.",
            permission = TareasModule.View,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.uuid("Id de la tarea."),
                    "item_id" to McpSchema.uuid("Id del elemento (ver get_task)."),
                    "text" to McpSchema.string("Texto del elemento: lo busca en la lista (sin distinguir mayúsculas) o lo añade si no existe."),
                    "done" to McpSchema.boolean("true para marcarlo, false para desmarcarlo; por defecto true."),
                ),
                required = listOf("id"),
            ),
        ) { call -> guarded { setChecklistItem(call) } },
    )

    // A getter: the tools of the import extensions are read on every request of the bridge (extensions are linked after this object loads).
    val mcp: ModuleMcp get() = ModuleMcp(Instructions, tools + TareasImportSources.all.flatMap { it.mcpTools })

    // ---- helpers (public and free of the database so they can be tested) ----

    /** Runs a handler turning the module's validation errors into the agent's tool errors. */
    private fun guarded(block: () -> JsonElement): JsonElement = try {
        block()
    } catch (failure: TareasValidationException) {
        throw McpToolException(failure.message.orEmpty())
    }

    /** A list argument: a JSON array of texts, or a plain text split by [separators]; null or missing means empty. */
    fun textList(arguments: JsonObject, key: String, separators: Regex = Regex("[,;\\n]")): List<String> = when (val sent = arguments[key]) {
        is JsonArray -> sent.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive !is JsonNull }?.contentOrNull }
        is JsonPrimitive -> if (sent is JsonNull) emptyList() else sent.content.split(separators)
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }

    /** Users by id, e-mail or name (any case), each once. An unknown one is an error that lists the valid people. */
    fun resolvePeople(refs: List<String>, users: List<TareasUser>): List<UUID> {
        val unknown = mutableListOf<String>()
        val found = refs.map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { ref ->
            val user = users.firstOrNull { it.id.toString().equals(ref, ignoreCase = true) }
                ?: users.firstOrNull { it.email.isNotEmpty() && it.email.equals(ref, ignoreCase = true) }
                ?: users.firstOrNull { it.label.equals(ref, ignoreCase = true) }
            if (user == null) unknown += ref
            user?.id
        }
        if (unknown.isNotEmpty()) {
            throw McpToolException("No son usuarios de Tareas: ${unknown.joinToString()}. Personas: ${users.joinToString { it.label }}.")
        }
        return found.distinct()
    }

    private fun priorityOf(text: String): TaskPriority =
        TaskPriority.fromLabel(text) ?: throw McpToolException("'priority' debe ser ${priorityCodes.joinToString(", ")} (recibido '$text').")

    private fun statusOf(text: String): TaskStatus =
        TaskStatus.fromLabel(text) ?: throw McpToolException("'status' debe ser ${statusCodes.joinToString(", ")} (recibido '$text').")

    /** The group of a board the user already sees: it is created for who administers boards (as create_task does), otherwise it must exist. */
    private fun groupOf(call: McpToolCall, scope: TareasScope, boardId: UUID, name: String): UUID? {
        if (name.isBlank()) return null
        val groups = TareasRepository.listGroups(call.tenant, boardId)
        groups.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.let { return it.id }
        if (scope.admin) return TareasRepository.findOrCreateGroup(call.tenant, boardId, name)
        throw McpToolException("El grupo \"${name.trim()}\" no existe en el tablero. Grupos: ${groups.joinToString { it.name }.ifEmpty { "ninguno" }}.")
    }

    /** Whether the user may link and see customers: clientes.view, which owners always hold. Read live on every call. */
    private fun canSeeCustomers(call: McpToolCall): Boolean {
        val permissions = call.permissions
        if (permissions.serverOwner || permissions.businessOwner) return true
        val userId = permissions.userId ?: return false
        val clientes = call.access.userAccess(call.tenant, permissions.companyCode, userId, serverOwner = false)?.permissions("clientes")
        return TareasService.canSeeCustomers(clientes)
    }

    private fun peopleOf(call: McpToolCall, key: String, users: List<TareasUser>): List<UUID> = resolvePeople(textList(call.arguments, key), users)

    private fun reviewerOf(call: McpToolCall, users: List<TareasUser>): UUID? =
        call.string("reviewer", 200).takeIf { it.isNotEmpty() }?.let { resolvePeople(listOf(it), users).firstOrNull() }

    // ---- JSON ----

    private fun names(users: List<TareasUser>): Map<UUID, String> = users.associate { it.id to it.label }

    private fun person(id: UUID, names: Map<UUID, String>): JsonObject = buildJsonObject {
        put("id", id.toString())
        put("name", names[id] ?: "Usuario sin acceso a Tareas")
    }

    /** The task as the agent sees it: ids, state, dates, board, group, people and checklist counts. Never a file reference. */
    fun taskJson(task: Task, names: Map<UUID, String>, today: java.time.LocalDate = TareasRepository.today(), showCustomer: Boolean = true): JsonObject = buildJsonObject {
        put("id", task.id.toString())
        put("title", task.title)
        put("status", task.status.code)
        put("status_label", task.status.label)
        put("priority", task.priority.code)
        put("due_date", task.dueDate?.toString())
        put("overdue", task.isOverdue(today))
        put("board_id", task.boardId.toString())
        put("board", task.boardName)
        put("group", task.groupName)
        put("assignees", buildJsonArray { task.assignees.forEach { add(person(it, names)) } })
        task.reviewerId?.let { put("reviewer", person(it, names)) }
        if (showCustomer) task.customerId?.let { put("customer_id", it.toString()) }
        task.externalRef?.let { put("external_ref", it) }
        put("checklist", "${task.checklistDone}/${task.checklistTotal}")
    }

    private fun taskList(tasks: List<Task>, users: List<TareasUser>, showCustomer: Boolean): JsonArray {
        val labels = names(users)
        val today = TareasRepository.today()
        return buildJsonArray { tasks.forEach { add(taskJson(it, labels, today, showCustomer)) } }
    }

    // ---- reads ----

    private fun listBoards(call: McpToolCall): JsonElement {
        val scope = TareasService.scope(call.tenant, call.permissions)
        val boards = TareasRepository.listBoards(call.tenant, scope)
        return buildJsonObject {
            putJsonArray("boards") {
                boards.forEach { board ->
                    add(
                        buildJsonObject {
                            put("id", board.id.toString())
                            put("name", board.name)
                            put("description", board.description)
                            put("visibility", board.visibility.code)
                            put("open_tasks", board.openTasks)
                            put("overdue_tasks", board.overdueTasks)
                            put("groups", buildJsonArray { TareasRepository.listGroups(call.tenant, board.id).forEach { add(JsonPrimitive(it.name)) } })
                        },
                    )
                }
            }
            put("can_create_tasks", scope.canManage)
        }
    }

    private fun listPeople(call: McpToolCall): JsonElement {
        val users = TareasRepository.users(call.tenant)
        // E-mails only for who manages tasks (they assign people by e-mail); the rest see ids and names.
        val withEmail = TareasService.scope(call.tenant, call.permissions).canManage
        return buildJsonObject {
            putJsonArray("people") {
                users.forEach { user ->
                    add(buildJsonObject { put("id", user.id.toString()); put("name", user.label); if (withEmail) put("email", user.email) })
                }
            }
        }
    }

    private fun listTasks(call: McpToolCall): JsonElement {
        val scope = TareasService.scope(call.tenant, call.permissions)
        val users = TareasRepository.users(call.tenant)
        val assignee = call.string("assignee", 200).takeIf { it.isNotEmpty() }?.let { text ->
            if (text.equals("me", ignoreCase = true)) {
                scope.userId ?: throw McpToolException("Este usuario no puede tener tareas asignadas.")
            } else {
                resolvePeople(listOf(text), users).firstOrNull()
            }
        }
        val due = call.string("due", 20).takeIf { it.isNotEmpty() }?.let {
            DueFilter.fromCode(it) ?: throw McpToolException("'due' debe ser ${DueFilter.entries.joinToString(", ") { filter -> filter.code }} (recibido '$it').")
        }
        val limit = call.int("limit", 50, 1..200)
        val filter = TaskFilter(
            boardId = call.uuid("board_id"),
            assignee = assignee,
            status = call.string("status", 20).takeIf { it.isNotEmpty() }?.let { statusOf(it) },
            due = due,
            query = call.string("query", 200).takeIf { it.isNotEmpty() },
            includeClosed = call.boolean("include_done", false),
            limit = limit,
        )
        val tasks = TareasRepository.list(call.tenant, scope, filter)
        return buildJsonObject {
            put("count", tasks.size)
            if (tasks.size >= limit) put("note", "Puede haber más tareas: afina los filtros o sube limit.")
            put("tasks", taskList(tasks, users, canSeeCustomers(call)))
        }
    }

    private fun getTask(call: McpToolCall): JsonElement {
        val scope = TareasService.scope(call.tenant, call.permissions)
        val task = TareasService.visibleTask(call.tenant, scope, call.requiredUuid("id"))
        val users = TareasRepository.users(call.tenant)
        val labels = names(users)
        return buildJsonObject {
            put("task", taskJson(task, labels, showCustomer = canSeeCustomers(call)))
            put("detail", task.detail)
            put("created_at", task.createdAt.toString())
            task.completedAt?.let { put("completed_at", it.toString()) }
            put(
                "can",
                buildJsonObject {
                    put("edit", scope.canEditTask(task))
                    put("work", scope.canWorkOnTask(task))
                    put("review", scope.canReview(task))
                },
            )
            putJsonArray("checklist_items") {
                TareasRepository.listChecklist(call.tenant, task.id).forEach { item ->
                    add(buildJsonObject { put("item_id", item.id.toString()); put("text", item.text); put("done", item.done) })
                }
            }
            putJsonArray("comments") {
                TareasRepository.listComments(call.tenant, task.id, 20).forEach { comment ->
                    add(buildJsonObject { put("author", comment.authorName); put("body", comment.body); put("at", comment.createdAt.toString()) })
                }
            }
            putJsonArray("activity") {
                TareasRepository.listActivity(call.tenant, task.id, 10).forEach { entry ->
                    add(buildJsonObject { put("kind", entry.kind); put("detail", entry.detail); put("author", entry.authorName); put("at", entry.createdAt.toString()) })
                }
            }
            // Only names and sizes: the stored location of a file is never shown.
            putJsonArray("attachments") {
                TareasRepository.listAttachments(call.tenant, task.id).forEach { file ->
                    add(buildJsonObject { put("name", file.name); put("size", file.size); put("content_type", file.contentType) })
                }
            }
        }
    }

    // ---- writes ----

    private fun createTask(call: McpToolCall): JsonElement {
        val scope = TareasService.scope(call.tenant, call.permissions)
        if (!scope.canManage) throw McpToolException("El usuario no puede crear tareas.")
        val users = TareasRepository.users(call.tenant)
        val seesCustomers = canSeeCustomers(call)
        val input = TaskInput(
            boardId = call.requiredUuid("board_id"),
            title = call.requiredString("title", TaskInput.MaxTitle + 1),
            detail = call.string("detail", TaskInput.MaxDetail + 1),
            priority = call.string("priority", 20).takeIf { it.isNotEmpty() }?.let { priorityOf(it) } ?: TaskPriority.Medium,
            dueDate = call.date("due_date"),
            reviewerId = reviewerOf(call, users),
            customerId = call.uuid("customer_id"),
            assignees = peopleOf(call, "assignees", users),
        )
        val externalRef = call.string("external_ref", ExternalTaskRequest.MaxExternalRef + 1).takeIf { it.isNotEmpty() }
        val result = TareasService.createTask(
            call.tenant,
            call.access,
            call.permissions,
            input,
            groupName = call.string("group", 81).takeIf { it.isNotEmpty() },
            source = "agent",
            externalRef = externalRef,
            checklist = textList(call.arguments, "checklist", Regex("\\n")),
            canSeeCustomers = seesCustomers,
        )
        val task = TareasRepository.get(call.tenant, result.id)?.takeIf { scope.seesTask(it) }
            ?: throw McpToolException("Tarea no encontrada.")
        return buildJsonObject {
            put("created", result.created)
            if (!seesCustomers && call.has("customer_id")) put("customer_note", "customer_id se ignoró: el usuario no puede ver Clientes.")
            if (!result.created) put("note", "Ya existía una tarea con esa external_ref: no se creó otra ni se cambió.")
            put("task", taskJson(task, names(users), showCustomer = seesCustomers))
        }
    }

    /** True when the call asks for a change other than the state: those need the permission to edit the task. */
    private fun changesFields(call: McpToolCall): Boolean =
        listOf("title", "detail", "group", "priority", "due_date", "assignees", "reviewer", "customer_id", "board_id").any { call.has(it) }

    private fun updateTask(call: McpToolCall): JsonElement {
        val id = call.requiredUuid("id")
        val statusText = call.string("status", 20)
        val status = statusText.takeIf { call.has("status") && it.isNotEmpty() }?.let { statusOf(it) }
        if (call.has("status") && status == null) throw McpToolException("'status' no puede estar vacío.")
        val fields = changesFields(call)
        if (!fields && status == null) throw McpToolException("Indica qué cambiar: status, title, detail, group, priority, due_date, assignees, reviewer o customer_id.")
        val scope = TareasService.scope(call.tenant, call.permissions)
        val task = TareasService.visibleTask(call.tenant, scope, id)
        val users = TareasRepository.users(call.tenant)
        val seesCustomers = canSeeCustomers(call)
        val updated: Task
        var note: String? = null
        if (!fields) {
            // Only the state: whoever works on the task may (TareasService applies the review rules).
            val result = TareasService.changeStatus(call.tenant, call.access, call.permissions, id, status ?: task.status)
            updated = result.task
            if (result.redirectedToReview) note = "La tarea tiene revisor: quedó En revisión y se avisó al revisor."
        } else {
            if (!scope.canEditTask(task)) throw McpToolException("El usuario no puede editar esta tarea; solo puede cambiar su estado, marcar su lista y comentar.")
            val boardId = call.uuid("board_id") ?: task.boardId
            // The destination board is checked before anything about its groups is said.
            if (boardId != task.boardId) {
                TareasRepository.getBoard(call.tenant, boardId)?.takeIf { scope.seesBoard(it) } ?: throw McpToolException("Tablero no encontrado.")
            }
            val groupId = when {
                call.has("group") -> groupOf(call, scope, boardId, call.string("group", 81))
                boardId == task.boardId -> task.groupId
                else -> null
            }
            val input = TaskInput(
                boardId = boardId,
                groupId = groupId,
                title = if (call.has("title")) call.requiredString("title", TaskInput.MaxTitle + 1) else task.title,
                detail = if (call.has("detail")) call.string("detail", TaskInput.MaxDetail + 1) else task.detail,
                priority = if (call.has("priority")) priorityOf(call.requiredString("priority", 20)) else task.priority,
                dueDate = if (call.has("due_date")) call.date("due_date") else task.dueDate,
                reviewerId = if (call.has("reviewer")) reviewerOf(call, users) else task.reviewerId,
                customerId = if (call.has("customer_id") && seesCustomers) call.uuid("customer_id") else task.customerId,
                // TareasService.updateTask replaces the responsibles every time: keep the current ones unless the agent sends them.
                assignees = if (call.has("assignees")) peopleOf(call, "assignees", users) else task.assignees,
            )
            updated = TareasService.updateTask(call.tenant, call.access, call.permissions, id, input, status, canSeeCustomers = seesCustomers)
            if (!seesCustomers && call.has("customer_id")) note = "customer_id se ignoró: el usuario no puede ver Clientes."
            if (status != null && updated.status != status) note = (note?.plus(" ") ?: "") + "El estado quedó en ${updated.status.label}: ${statusNoteFor(updated, status)}"
        }
        return buildJsonObject {
            note?.let { put("note", it) }
            put("task", taskJson(updated, names(TareasRepository.users(call.tenant)), showCustomer = seesCustomers))
        }
    }

    private fun statusNoteFor(task: Task, requested: TaskStatus): String =
        if (requested == TaskStatus.Done && task.status == TaskStatus.Review) "la tarea tiene revisor y debe aprobarla él." else "se aplicaron las reglas de revisión."

    private fun completeTask(call: McpToolCall): JsonElement {
        val result = TareasService.complete(call.tenant, call.access, call.permissions, call.requiredUuid("id"))
        return buildJsonObject {
            if (result.redirectedToReview) put("note", "La tarea tiene revisor: quedó En revisión y se avisó al revisor.")
            put("task", taskJson(result.task, names(TareasRepository.users(call.tenant)), showCustomer = canSeeCustomers(call)))
        }
    }

    private fun addComment(call: McpToolCall): JsonElement {
        val id = call.requiredUuid("id")
        val commentId = TareasService.comment(call.tenant, call.access, call.permissions, id, call.requiredString("body", TareasRepository.MaxCommentLength + 1))
        return buildJsonObject {
            put("task_id", id.toString())
            put("comment_id", commentId.toString())
            put("result", "Comentario agregado.")
        }
    }

    private fun setChecklistItem(call: McpToolCall): JsonElement {
        val taskId = call.requiredUuid("id")
        val done = call.boolean("done", true)
        val itemId = call.uuid("item_id")
        val text = call.string("text", 300)
        if (itemId == null && text.isEmpty()) throw McpToolException("Indica item_id o text.")
        val scope = TareasService.scope(call.tenant, call.permissions)
        TareasService.visibleTask(call.tenant, scope, taskId)
        val items = TareasRepository.listChecklist(call.tenant, taskId)
        val existing = if (itemId != null) {
            items.firstOrNull { it.id == itemId } ?: throw McpToolException("El elemento no pertenece a esa tarea (ver get_task).")
        } else {
            items.firstOrNull { it.text.equals(text, ignoreCase = true) }
        }
        val added = existing == null
        // A new item starts undone: adding and ticking it is one change, so both happen or none does.
        val target = TareasRepository.transaction(call.tenant) {
            val id = existing?.id ?: TareasService.addChecklistItem(call.tenant, call.permissions, taskId, text)
            if (!added || done) TareasService.toggleChecklistItem(call.tenant, call.permissions, id, done)
            id
        }
        val task = TareasRepository.get(call.tenant, taskId)
        return buildJsonObject {
            put("task_id", taskId.toString())
            put("item_id", target.toString())
            put("done", done)
            put("added", added)
            task?.let { put("checklist", "${it.checklistDone}/${it.checklistTotal}") }
        }
    }
}
