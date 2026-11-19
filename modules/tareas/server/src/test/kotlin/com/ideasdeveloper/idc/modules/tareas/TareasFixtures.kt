package com.ideasdeveloper.idc.modules.tareas

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Builders shared by the pure tests of Tareas (no database). */
object TareasFixtures {
    fun task(
        boardId: UUID = UUID.randomUUID(),
        status: TaskStatus = TaskStatus.Pending,
        assignees: List<UUID> = emptyList(),
        reviewerId: UUID? = null,
        visibility: BoardVisibility = BoardVisibility.Company,
        archived: Boolean = false,
        dueDate: LocalDate? = null,
    ) = Task(
        id = UUID.randomUUID(),
        boardId = boardId,
        groupId = null,
        title = "Tarea",
        detail = "",
        status = status,
        priority = TaskPriority.Medium,
        dueDate = dueDate,
        reviewerId = reviewerId,
        customerId = null,
        source = "manual",
        externalRef = null,
        position = 0,
        completedAt = null,
        createdBy = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        assignees = assignees,
        groupName = null,
        boardName = "Tablero",
        boardVisibility = visibility,
        boardArchived = archived,
        checklistDone = 0,
        checklistTotal = 0,
    )

    fun board(
        visibility: BoardVisibility = BoardVisibility.Company,
        archived: Boolean = false,
        id: UUID = UUID.randomUUID(),
    ) = Board(id, "Tablero", "", visibility, 0, archived, null, emptyList(), 0, 0)
}
