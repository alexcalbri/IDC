package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiFileRef
import java.sql.Connection
import java.util.UUID

data class CreateResult(val id: UUID, val created: Boolean)

data class StatusResult(val task: Task, val applied: TaskStatus, val redirectedToReview: Boolean)

/**
 * Business operations shared by the screens and the MCP tools, so the rules live in one place. Each one loads the scope,
 * checks permission and visibility, writes inside a transaction (with the activity row) and, after the commit, sends the
 * notices and the customer event. A task the user cannot see is reported as not found.
 *
 * Columns that reference users (author, creator, uploader...) receive [TareasScope.userId], which is null for a server
 * owner (a central user that is not in the company's users).
 */
object TareasService {
    private const val TaskNotFound = "Tarea no encontrada."
    private const val BoardNotFound = "Tablero no encontrado."
    private const val Archived = "La tarea está archivada."

    fun scope(tenant: Connection, permissions: ModulePermissions): TareasScope =
        TareasScope.from(permissions, TareasRepository.memberships(tenant, permissions.userId?.takeUnless { permissions.serverOwner }))

    /** The task when the scope sees it. An archived task is seen only by admins (read only: writes say it is archived). */
    fun visibleTask(tenant: Connection, scope: TareasScope, id: UUID): Task =
        TareasRepository.get(tenant, id)?.takeIf { scope.seesTask(it) && (it.archivedAt == null || scope.admin) } ?: throw TareasValidationException(TaskNotFound)

    /** Whether the user may link customers: holds clientes.view (null: no access to Clientes). */
    fun canSeeCustomers(permissionsForClientes: ModulePermissions?): Boolean = permissionsForClientes?.has("clientes.view") == true

    private fun active(task: Task): Task = if (task.archivedAt != null) throw TareasValidationException(Archived) else task

    private fun actorName(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions): String =
        access.userIdentity(tenant, permissions)?.name ?: "Usuario"

    private fun requireUsers(tenant: Connection, ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        val allowed = TareasRepository.users(tenant).map { it.id }.toSet()
        if (ids.any { it !in allowed }) throw TareasValidationException("Hay personas que no pueden usar Tareas: elige solo usuarios de la lista.")
    }

    // ---- tasks ----

    /** [canSeeCustomers] false: the customer of [input] is ignored. A task with the same external reference in the board is returned untouched. */
    fun createTask(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        input: TaskInput,
        groupName: String? = null,
        source: String = "manual",
        externalRef: String? = null,
        checklist: List<String> = emptyList(),
        canSeeCustomers: Boolean = false,
    ): CreateResult {
        val scope = scope(tenant, permissions)
        if (!scope.canManage) throw TareasValidationException("No tienes permiso para crear tareas.")
        if (!ExternalTaskRequest.SourcePattern.matches(source)) throw TareasValidationException("Origen de tarea no válido.")
        if (externalRef != null && (externalRef.isBlank() || externalRef.length > ExternalTaskRequest.MaxExternalRef)) {
            throw TareasValidationException("La referencia externa debe tener entre 1 y ${ExternalTaskRequest.MaxExternalRef} caracteres.")
        }
        val board = TareasRepository.getBoard(tenant, input.boardId)?.takeIf { scope.seesBoard(it) } ?: throw TareasValidationException(BoardNotFound)
        if (externalRef != null) {
            // The board is visible to the user, so is its task with that reference.
            TareasRepository.findByExternalRef(tenant, board.id, source, externalRef)?.let { return CreateResult(it.id, false) }
        }
        requireUsers(tenant, input.assignees + listOfNotNull(input.reviewerId))
        val name = actorName(tenant, access, permissions)
        val safeInput = if (canSeeCustomers) input else input.copy(customerId = null)
        val result = TareasRepository.transaction(tenant) {
            val groupId = safeInput.groupId ?: groupName?.takeIf { it.isNotBlank() }?.let { wanted ->
                if (scope.admin) {
                    TareasRepository.findOrCreateGroup(tenant, board.id, wanted)
                } else {
                    TareasRepository.listGroups(tenant, board.id).firstOrNull { it.name.equals(wanted.trim(), ignoreCase = true) }?.id
                        ?: throw TareasValidationException("El grupo \"${wanted.trim()}\" no existe en el tablero.")
                }
            }
            val created = TareasRepository.createIfAbsent(tenant, safeInput.copy(groupId = groupId), scope.userId, source, externalRef)
            if (created.created) {
                checklist.map { it.trim() }.filter { it.isNotEmpty() }.take(TareasRepository.MaxChecklistItems).forEach { TareasRepository.addChecklistItem(tenant, created.id, it) }
                TareasRepository.addActivity(tenant, created.id, "created", "Tarea creada", name)
            }
            created
        }
        if (result.created) {
            TareasRepository.get(tenant, result.id)?.let { task ->
                TareasNotify.assigned(tenant, access.notificationsFor(TareasModule.id), task, task.assignees, scope.userId)
            }
        }
        return result
    }

    /** [canSeeCustomers] false: the customer already on the task is kept whatever [input] says. */
    fun updateTask(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        id: UUID,
        input: TaskInput,
        status: TaskStatus? = null,
        canSeeCustomers: Boolean = false,
    ): Task {
        val scope = scope(tenant, permissions)
        val task = active(visibleTask(tenant, scope, id))
        if (!scope.canEditTask(task)) throw TareasValidationException("No tienes permiso para editar esta tarea.")
        if (input.boardId != task.boardId) {
            TareasRepository.getBoard(tenant, input.boardId)?.takeIf { scope.seesBoard(it) } ?: throw TareasValidationException(BoardNotFound)
        }
        val safeInput = if (canSeeCustomers) input else input.copy(customerId = task.customerId)
        // Only people newly chosen are checked: someone already on the task may have lost access since.
        requireUsers(tenant, safeInput.assignees.filter { it !in task.assignees } + listOfNotNull(safeInput.reviewerId?.takeIf { it != task.reviewerId }))
        val name = actorName(tenant, access, permissions)
        val added = TareasRepository.transaction(tenant) {
            if (!TareasRepository.update(tenant, id, safeInput)) throw TareasValidationException(TaskNotFound)
            val newcomers = TareasRepository.setAssignees(tenant, id, safeInput.assignees)
            TareasRepository.addActivity(tenant, id, "updated", "Tarea editada", name)
            newcomers
        }
        val updated = TareasRepository.get(tenant, id) ?: throw TareasValidationException(TaskNotFound)
        TareasNotify.assigned(tenant, access.notificationsFor(TareasModule.id), updated, added, scope.userId)
        if (status != null && status != updated.status) return changeStatus(tenant, access, permissions, id, status).task
        return updated
    }

    fun changeStatus(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, requested: TaskStatus): StatusResult {
        val scope = scope(tenant, permissions)
        val task = active(visibleTask(tenant, scope, id))
        val applied: TransitionResult.Apply = when (val result = TaskTransitions.resolve(task, requested, scope)) {
            is TransitionResult.Reject -> throw TareasValidationException(result.message)
            TransitionResult.Unchanged -> return StatusResult(task, task.status, false)
            is TransitionResult.Apply -> result
        }
        val name = actorName(tenant, access, permissions)
        val previous = TareasRepository.transaction(tenant) {
            val before = TareasRepository.setStatus(tenant, id, applied.status, scope.userId) ?: throw TareasValidationException(TaskNotFound)
            val note = if (applied.redirectedToReview) " (enviada a revisión)" else ""
            TareasRepository.addActivity(tenant, id, "status", "${before.label} → ${applied.status.label}$note", name)
            before
        }
        val updated = TareasRepository.get(tenant, id) ?: throw TareasValidationException(TaskNotFound)
        val notifier = access.notificationsFor(TareasModule.id)
        when {
            applied.status == TaskStatus.Review -> TareasNotify.sentToReview(tenant, notifier, updated, scope.userId)
            previous == TaskStatus.Review && applied.status == TaskStatus.InProgress -> TareasNotify.returned(tenant, notifier, updated, scope.userId)
            previous == TaskStatus.Review && applied.status == TaskStatus.Done -> TareasNotify.approved(tenant, notifier, updated, scope.userId)
        }
        if (applied.status == TaskStatus.Done && previous != TaskStatus.Done) TareasCustomerEvents.completed(tenant, access, updated, name)
        return StatusResult(updated, applied.status, applied.redirectedToReview)
    }

    fun complete(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID): StatusResult =
        changeStatus(tenant, access, permissions, id, TaskStatus.Done)

    fun archive(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID) {
        val scope = scope(tenant, permissions)
        val task = active(visibleTask(tenant, scope, id))
        if (!scope.canEditTask(task)) throw TareasValidationException("No tienes permiso para archivar esta tarea.")
        val name = actorName(tenant, access, permissions)
        TareasRepository.transaction(tenant) {
            if (TareasRepository.archive(tenant, id)) TareasRepository.addActivity(tenant, id, "archived", "Tarea archivada", name)
        }
    }

    fun comment(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, body: String): UUID {
        val scope = scope(tenant, permissions)
        val task = active(visibleTask(tenant, scope, id))
        if (!scope.canWorkOnTask(task)) throw TareasValidationException("No tienes permiso para comentar esta tarea.")
        val name = actorName(tenant, access, permissions)
        val commentId = TareasRepository.addComment(tenant, id, scope.userId, name, body)
        TareasNotify.commented(tenant, access.notificationsFor(TareasModule.id), task, scope.userId, name, body)
        return commentId
    }

    // ---- checklist ----

    private fun workableTask(tenant: Connection, permissions: ModulePermissions, taskId: UUID): Pair<TareasScope, Task> {
        val scope = scope(tenant, permissions)
        val task = active(visibleTask(tenant, scope, taskId))
        if (!scope.canWorkOnTask(task)) throw TareasValidationException("No tienes permiso para cambiar esta tarea.")
        return scope to task
    }

    private fun itemOf(tenant: Connection, itemId: UUID): ChecklistItem =
        TareasRepository.getChecklistItem(tenant, itemId) ?: throw TareasValidationException("Elemento de la lista no encontrado.")

    fun addChecklistItem(tenant: Connection, permissions: ModulePermissions, taskId: UUID, text: String): UUID {
        workableTask(tenant, permissions, taskId)
        return TareasRepository.addChecklistItem(tenant, taskId, text)
    }

    fun toggleChecklistItem(tenant: Connection, permissions: ModulePermissions, itemId: UUID, done: Boolean) {
        val item = itemOf(tenant, itemId)
        val (scope, _) = workableTask(tenant, permissions, item.taskId)
        TareasRepository.toggleChecklistItem(tenant, itemId, done, scope.userId)
    }

    fun removeChecklistItem(tenant: Connection, permissions: ModulePermissions, itemId: UUID) {
        val item = itemOf(tenant, itemId)
        val scope = scope(tenant, permissions)
        val task = active(visibleTask(tenant, scope, item.taskId))
        if (!scope.canEditTask(task)) throw TareasValidationException("No tienes permiso para editar esta tarea.")
        TareasRepository.removeChecklistItem(tenant, itemId)
    }

    // ---- files ----

    /** Links the uploads of this user to the task and returns their names (nothing for a server owner: no user to own the files). */
    fun attachFiles(tenant: Connection, permissions: ModulePermissions, taskId: UUID, refs: List<UiFileRef>): List<String> {
        val (scope, _) = workableTask(tenant, permissions, taskId)
        val ids = refs.mapNotNull { runCatching { UUID.fromString(it.id) }.getOrNull() }
        return TareasRepository.attach(tenant, taskId, ids, scope.userId)
    }

    fun removeFiles(tenant: Connection, permissions: ModulePermissions, taskId: UUID, ids: Collection<UUID>): Int {
        workableTask(tenant, permissions, taskId)
        return TareasRepository.markDeleted(tenant, taskId, ids)
    }

    // ---- boards (tareas.boards, business owner or server owner) ----

    private fun adminScope(tenant: Connection, permissions: ModulePermissions): TareasScope {
        val scope = scope(tenant, permissions)
        if (!scope.admin) throw TareasValidationException("Solo quien administra tableros puede hacer esto.")
        return scope
    }

    fun createBoard(tenant: Connection, permissions: ModulePermissions, name: String, description: String, visibility: BoardVisibility, members: Collection<UUID>): UUID {
        val scope = adminScope(tenant, permissions)
        requireUsers(tenant, members)
        return TareasRepository.createBoard(tenant, name, description, visibility, members, scope.userId)
    }

    fun updateBoard(tenant: Connection, permissions: ModulePermissions, id: UUID, name: String, description: String, visibility: BoardVisibility, members: Collection<UUID>) {
        adminScope(tenant, permissions)
        val current = TareasRepository.getBoard(tenant, id) ?: throw TareasValidationException(BoardNotFound)
        requireUsers(tenant, members.filter { it !in current.members })
        TareasRepository.updateBoard(tenant, id, name, description, visibility, members)
    }

    fun archiveBoard(tenant: Connection, permissions: ModulePermissions, id: UUID) {
        adminScope(tenant, permissions)
        if (!TareasRepository.archiveBoard(tenant, id)) throw TareasValidationException(BoardNotFound)
    }

    fun addGroup(tenant: Connection, permissions: ModulePermissions, boardId: UUID, name: String): UUID {
        adminScope(tenant, permissions)
        TareasRepository.getBoard(tenant, boardId) ?: throw TareasValidationException(BoardNotFound)
        return TareasRepository.addGroup(tenant, boardId, name)
    }

    private fun groupOf(tenant: Connection, boardId: UUID, groupId: UUID): BoardGroup =
        TareasRepository.getGroup(tenant, groupId)?.takeIf { it.boardId == boardId } ?: throw TareasValidationException("Grupo no encontrado.")

    fun renameGroup(tenant: Connection, permissions: ModulePermissions, boardId: UUID, groupId: UUID, name: String) {
        adminScope(tenant, permissions)
        groupOf(tenant, boardId, groupId)
        TareasRepository.renameGroup(tenant, groupId, name)
    }

    fun moveGroup(tenant: Connection, permissions: ModulePermissions, boardId: UUID, groupId: UUID, up: Boolean) {
        adminScope(tenant, permissions)
        groupOf(tenant, boardId, groupId)
        TareasRepository.moveGroup(tenant, groupId, up)
    }

    fun archiveGroup(tenant: Connection, permissions: ModulePermissions, boardId: UUID, groupId: UUID) {
        adminScope(tenant, permissions)
        groupOf(tenant, boardId, groupId)
        TareasRepository.archiveGroup(tenant, groupId)
    }
}
