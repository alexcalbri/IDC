package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Fixed task states: filters, kanban columns and the AI agent rely on them. */
enum class TaskStatus(val code: String, val label: String) {
    Pending("pending", "Pendiente"),
    InProgress("in_progress", "En curso"),
    Review("review", "En revisión"),
    Done("done", "Hecha"),
    Cancelled("cancelled", "Cancelada");

    /** A task that still needs work: neither done nor cancelled. */
    val isOpen: Boolean get() = this != Done && this != Cancelled

    companion object {
        fun fromCode(code: String?): TaskStatus? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }

        /** Accepts the visible label (any case) and, for convenience, the code. */
        fun fromLabel(label: String?): TaskStatus? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromCode(text)
        }
    }
}

enum class TaskPriority(val code: String, val label: String) {
    Urgent("urgent", "Urgente"),
    High("high", "Alta"),
    Medium("medium", "Media"),
    Low("low", "Baja");

    companion object {
        fun fromCode(code: String?): TaskPriority? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }

        fun fromLabel(label: String?): TaskPriority? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromCode(text)
        }
    }
}

enum class BoardVisibility(val code: String, val label: String) {
    Company("company", "Toda la empresa"),
    Private("private", "Privado");

    companion object {
        fun fromCode(code: String?): BoardVisibility? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }

        fun fromLabel(label: String?): BoardVisibility? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromCode(text)
        }
    }
}

enum class DueFilter(val code: String, val label: String) {
    Overdue("overdue", "Vencidas"),
    Today("today", "Hoy"),
    Week("week", "Próximos 7 días"),
    None("none", "Sin fecha");

    companion object {
        fun fromCode(code: String?): DueFilter? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }
    }
}

/** Raised for a rule of the module (limits, unknown references); the message is shown to the user. */
class TareasValidationException(message: String) : Exception(message)

data class Board(
    val id: UUID,
    val name: String,
    val description: String,
    val visibility: BoardVisibility,
    val position: Int,
    val archived: Boolean,
    val createdBy: UUID?,
    val members: List<UUID>,
    val openTasks: Int,
    val overdueTasks: Int,
)

data class BoardGroup(val id: UUID, val boardId: UUID, val name: String, val position: Int, val archived: Boolean)

data class Task(
    val id: UUID,
    val boardId: UUID,
    val groupId: UUID?,
    val title: String,
    val detail: String,
    val status: TaskStatus,
    val priority: TaskPriority,
    val dueDate: LocalDate?,
    val reviewerId: UUID?,
    val customerId: UUID?,
    val source: String,
    val externalRef: String?,
    val position: Int,
    val completedAt: Instant?,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val assignees: List<UUID>,
    val groupName: String?,
    val boardName: String,
    val boardVisibility: BoardVisibility,
    val boardArchived: Boolean,
    val checklistDone: Int,
    val checklistTotal: Int,
    val archivedAt: Instant? = null,
) {
    /** Open and past its due date at [today]. */
    fun isOverdue(today: LocalDate): Boolean = status.isOpen && dueDate != null && dueDate.isBefore(today)
}

data class ChecklistItem(
    val id: UUID,
    val taskId: UUID,
    val text: String,
    val done: Boolean,
    val position: Int,
    val doneBy: UUID?,
    val doneAt: Instant?,
)

data class TaskComment(val id: UUID, val taskId: UUID, val authorId: UUID?, val authorName: String, val body: String, val createdAt: Instant)

data class TaskAttachment(
    val id: UUID,
    val taskId: UUID?,
    val name: String,
    val path: String,
    val contentType: String,
    val size: Long,
    val uploadedBy: UUID?,
    val createdAt: Instant,
)

data class TaskActivity(val id: UUID, val taskId: UUID, val kind: String, val detail: String, val authorName: String, val createdAt: Instant)

/** A user that can be assigned: [label] is what screens and agents show (unique among the list). */
data class TareasUser(val id: UUID, val label: String, val email: String)

data class CustomerSuggestion(val id: UUID, val name: String, val email: String)

data class WorkloadRow(val userId: UUID, val open: Int, val overdue: Int, val review: Int)

/** A reminder still to send: [kind] is "due_soon" (due tomorrow) or "overdue". */
data class ReminderCandidate(
    val taskId: UUID,
    val userId: UUID,
    val kind: String,
    val dueDate: LocalDate,
    val title: String,
    val boardName: String,
)

data class TaskFilter(
    val boardId: UUID? = null,
    val assignee: UUID? = null,
    val status: TaskStatus? = null,
    val due: DueFilter? = null,
    val query: String? = null,
    val includeClosed: Boolean = false,
    val limit: Int = 200,
)

/** What a form or an agent sends to create or edit a task (state and responsibles are changed apart on edit). */
data class TaskInput(
    val boardId: UUID,
    val groupId: UUID? = null,
    val title: String,
    val detail: String = "",
    val priority: TaskPriority = TaskPriority.Medium,
    val dueDate: LocalDate? = null,
    val reviewerId: UUID? = null,
    val customerId: UUID? = null,
    val assignees: List<UUID> = emptyList(),
) {
    /** Messages for the user; empty when the input is valid. */
    fun validate(): List<String> = buildList {
        if (title.isBlank()) add("El título es obligatorio.")
        else if (title.trim().length > MaxTitle) add("El título admite hasta $MaxTitle caracteres.")
        if (detail.length > MaxDetail) add("El detalle admite hasta $MaxDetail caracteres.")
        if (dueDate != null && (dueDate.isBefore(MinDate) || dueDate.isAfter(MaxDate))) add("La fecha debe estar entre $MinDate y $MaxDate.")
        if (assignees.distinct().size > MaxAssignees) add("Una tarea admite hasta $MaxAssignees responsables.")
    }

    companion object {
        const val MaxTitle = 300
        const val MaxDetail = 20000
        const val MaxAssignees = 50
        val MinDate: LocalDate = LocalDate.of(2000, 1, 1)
        val MaxDate: LocalDate = LocalDate.of(2100, 12, 31)
    }
}

/**
 * What the signed-in user may do, computed once per request. [admin] is tareas.boards, business owner or server owner;
 * [memberOf] are the private boards the user belongs to. The same rules are applied in SQL by [TareasRepository.list].
 */
data class TareasScope(val userId: UUID?, val admin: Boolean, val canManage: Boolean, val memberOf: Set<UUID>) {
    fun seesBoard(board: Board): Boolean = seesBoard(board.id, board.visibility, board.archived)

    fun seesBoard(boardId: UUID, visibility: BoardVisibility, archived: Boolean): Boolean =
        admin || (!archived && (visibility == BoardVisibility.Company || boardId in memberOf))

    fun seesTask(task: Task): Boolean =
        seesBoard(task.boardId, task.boardVisibility, task.boardArchived) || isParticipant(task)

    fun canEditTask(task: Task): Boolean = canManage && seesBoard(task.boardId, task.boardVisibility, task.boardArchived)

    /** Status, checklist, comments and attachments: whoever edits the task, its responsibles and its reviewer. */
    fun canWorkOnTask(task: Task): Boolean = canEditTask(task) || isParticipant(task)

    fun canReview(task: Task): Boolean = (userId != null && userId == task.reviewerId) || canEditTask(task)

    private fun isParticipant(task: Task): Boolean = userId != null && (userId in task.assignees || userId == task.reviewerId)

    companion object {
        fun from(permissions: ModulePermissions, memberOf: Set<UUID>): TareasScope {
            val owner = permissions.serverOwner || permissions.businessOwner
            return TareasScope(
                // A server owner is a central user: never an assignee or reviewer of the company's tasks.
                userId = permissions.userId?.takeUnless { permissions.serverOwner },
                admin = owner || permissions.has(TareasModule.Boards),
                canManage = owner || permissions.has(TareasModule.Manage),
                memberOf = memberOf,
            )
        }
    }
}

sealed class TransitionResult {
    /** The task moves to [status]; [redirectedToReview] when it asked for Done but must be reviewed first. */
    data class Apply(val status: TaskStatus, val redirectedToReview: Boolean = false) : TransitionResult()

    data object Unchanged : TransitionResult()

    data class Reject(val message: String) : TransitionResult()
}

object TaskTransitions {
    fun resolve(task: Task, requested: TaskStatus, scope: TareasScope): TransitionResult {
        if (!scope.canWorkOnTask(task)) return TransitionResult.Reject("No tienes permiso para cambiar el estado de esta tarea.")
        if (requested == task.status) return TransitionResult.Unchanged
        val reviewer = scope.canReview(task)
        if (task.status == TaskStatus.Review && !reviewer) {
            return TransitionResult.Reject("Solo el revisor puede aprobar o devolver una tarea en revisión.")
        }
        if (requested == TaskStatus.Done && task.reviewerId != null && !reviewer) {
            return TransitionResult.Apply(TaskStatus.Review, redirectedToReview = true)
        }
        return TransitionResult.Apply(requested)
    }
}
