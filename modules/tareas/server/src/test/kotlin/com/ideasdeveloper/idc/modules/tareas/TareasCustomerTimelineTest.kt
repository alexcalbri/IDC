package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.ui.UiOpenModule
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TareasCustomerTimelineTest {
    private val user = UUID.randomUUID()
    private val member = TareasScope(userId = user, admin = false, canManage = false, memberOf = emptySet())
    private val admin = TareasScope(userId = user, admin = true, canManage = true, memberOf = emptySet())

    private fun task(
        title: String = "Llamar al cliente",
        status: TaskStatus = TaskStatus.Pending,
        createdAt: Instant = Instant.parse("2026-11-01T10:00:00Z"),
        completedAt: Instant? = null,
        visibility: BoardVisibility = BoardVisibility.Company,
        boardId: UUID = UUID.randomUUID(),
        archivedAt: Instant? = null,
        assignees: List<UUID> = emptyList(),
    ) = TareasFixtures.task(boardId = boardId, status = status, visibility = visibility, assignees = assignees)
        .copy(title = title, createdAt = createdAt, completedAt = completedAt, archivedAt = archivedAt)

    private fun entries(tasks: List<Task>, scope: TareasScope = member, limit: Int = 50) =
        TareasCustomerTimeline.entries(tasks, scope, limit)

    @Test
    fun usesViewPermission() {
        assertEquals(TareasModule.View, TareasCustomerTimeline.timeline.permission)
    }

    @Test
    fun pendingTaskHasOnlyCreatedEntryWithBoardAndStatus() {
        val task = task()
        val entry = entries(listOf(task)).single()
        assertEquals("task_created", entry.kind)
        assertEquals("Tarea: Llamar al cliente", entry.title)
        assertEquals("Tablero · Pendiente", entry.detail)
        assertEquals(task.createdAt, entry.at)
        val link = assertIs<UiOpenModule>(entry.link)
        assertEquals("tareas", link.moduleId)
        assertEquals("task", link.screen)
        assertEquals(mapOf("id" to task.id.toString()), link.params)
    }

    @Test
    fun doneTaskAddsCompletedEntryNewestFirst() {
        val done = task(status = TaskStatus.Done, completedAt = Instant.parse("2026-11-05T10:00:00Z"))
        val result = entries(listOf(done))
        assertEquals(listOf("task_done", "task_created"), result.map { it.kind })
        assertEquals("Tarea completada: Llamar al cliente", result.first().title)
        assertEquals(Instant.parse("2026-11-05T10:00:00Z"), result.first().at)
    }

    @Test
    fun taskOfAPrivateBoardIsHiddenFromNonMembers() {
        val boardId = UUID.randomUUID()
        val private = task(visibility = BoardVisibility.Private, boardId = boardId)
        assertTrue(entries(listOf(private), member).isEmpty())
        assertEquals(1, entries(listOf(private), member.copy(memberOf = setOf(boardId))).size)
        assertEquals(1, entries(listOf(private), admin).size)
    }

    @Test
    fun responsibleSeesTheTaskOfAPrivateBoard() {
        val private = task(visibility = BoardVisibility.Private, assignees = listOf(user))
        assertEquals(1, entries(listOf(private)).size)
    }

    @Test
    fun archivedTasksNeverAppear() {
        val archived = task(archivedAt = Instant.parse("2026-11-02T00:00:00Z"))
        assertTrue(entries(listOf(archived), admin).isEmpty())
    }

    @Test
    fun limitKeepsTheNewestEntries() {
        val tasks = (1..5).map { day -> task(title = "T$day", createdAt = Instant.parse("2026-11-0${day}T10:00:00Z")) }
        val result = entries(tasks, limit = 2)
        assertEquals(listOf("Tarea: T5", "Tarea: T4"), result.map { it.title })
    }

    @Test
    fun noTasksNoEntries() {
        assertTrue(entries(emptyList()).isEmpty())
    }
}
