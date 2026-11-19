package com.ideasdeveloper.idc.modules.tareas

import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Validation and limits of the data a form or an agent sends for a task. */
class TaskInputTest {
    private fun input(
        title: String = "Revisar contrato",
        detail: String = "",
        dueDate: LocalDate? = null,
        assignees: List<UUID> = emptyList(),
    ) = TaskInput(UUID.randomUUID(), title = title, detail = detail, dueDate = dueDate, assignees = assignees)

    @Test
    fun validInputHasNoMessages() {
        assertTrue(input(dueDate = LocalDate.of(2026, 11, 18)).validate().isEmpty())
    }

    @Test
    fun blankTitleIsRejected() {
        assertEquals(1, input(title = "   ").validate().size)
        assertEquals(1, input(title = "").validate().size)
    }

    @Test
    fun titleLimitCountsTrimmedText() {
        assertTrue(input(title = " " + "a".repeat(TaskInput.MaxTitle) + " ").validate().isEmpty())
        assertEquals(1, input(title = "a".repeat(TaskInput.MaxTitle + 1)).validate().size)
    }

    @Test
    fun detailLimit() {
        assertTrue(input(detail = "a".repeat(TaskInput.MaxDetail)).validate().isEmpty())
        assertEquals(1, input(detail = "a".repeat(TaskInput.MaxDetail + 1)).validate().size)
    }

    @Test
    fun dueDateMustBeInTheSupportedRange() {
        assertTrue(input(dueDate = TaskInput.MinDate).validate().isEmpty())
        assertTrue(input(dueDate = TaskInput.MaxDate).validate().isEmpty())
        assertEquals(1, input(dueDate = TaskInput.MinDate.minusDays(1)).validate().size)
        assertEquals(1, input(dueDate = TaskInput.MaxDate.plusDays(1)).validate().size)
    }

    @Test
    fun assigneesLimitIgnoresRepeats() {
        val ids = List(TaskInput.MaxAssignees) { UUID.randomUUID() }
        assertTrue(input(assignees = ids + ids).validate().isEmpty())
        assertEquals(1, input(assignees = ids + UUID.randomUUID()).validate().size)
    }

    @Test
    fun severalProblemsAreAllReported() {
        assertEquals(2, input(title = "", detail = "a".repeat(TaskInput.MaxDetail + 1)).validate().size)
    }

    @Test
    fun statusAndPriorityParseCodesAndLabels() {
        assertEquals(TaskStatus.InProgress, TaskStatus.fromCode(" IN_PROGRESS "))
        assertEquals(TaskStatus.Review, TaskStatus.fromLabel("en revisión"))
        assertEquals(TaskStatus.Done, TaskStatus.fromLabel("done"))
        assertNull(TaskStatus.fromCode("nope"))
        assertEquals(TaskPriority.Urgent, TaskPriority.fromLabel("urgente"))
        assertNull(TaskPriority.fromCode(null))
        assertTrue(TaskStatus.Pending.isOpen && !TaskStatus.Done.isOpen && !TaskStatus.Cancelled.isOpen)
    }

    @Test
    fun overdueNeedsAnOpenTaskPastItsDate() {
        val today = LocalDate.of(2026, 11, 18)
        fun task(status: TaskStatus, due: LocalDate?) = TareasFixtures.task(status = status, dueDate = due)
        assertTrue(task(TaskStatus.Pending, today.minusDays(1)).isOverdue(today))
        assertTrue(!task(TaskStatus.Pending, today).isOverdue(today))
        assertTrue(!task(TaskStatus.Done, today.minusDays(1)).isOverdue(today))
        assertTrue(!task(TaskStatus.Pending, null).isOverdue(today))
    }
}
