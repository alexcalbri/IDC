package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parsing of what the screens send (TareasActions): pure, no database. */
class TareasActionsTest {
    private val ana = TareasUser(UUID.randomUUID(), "Ana Ruiz", "ana@example.com")
    private val beto = TareasUser(UUID.randomUUID(), "Beto", "beto@example.com")
    private val users = listOf(ana, beto)
    private val board = UUID.randomUUID()
    private val semana = BoardGroup(UUID.randomUUID(), board, "Esta semana", 0, archived = false)
    private val vieja = BoardGroup(UUID.randomUUID(), board, "Vieja", 1, archived = true)
    private val groups = listOf(semana, vieja)

    private fun input(values: Map<String, String>, canSeeCustomers: Boolean = true, existing: Task? = null) =
        TareasActions.taskInput(board, mapOf("title" to "Revisar") + values, users, groups, canSeeCustomers, existing)

    @Test
    fun taskInputMapsLabelsToIds() {
        val parsed = input(
            mapOf(
                "group" to "esta semana",
                "priority" to "Alta",
                "due" to "2026-11-20",
                "reviewer" to "BETO",
                "assignees" to UiFieldValues.options(listOf("Ana Ruiz", "Beto", "ana ruiz")),
                "detail" to "Notas",
            ),
        )
        assertEquals(semana.id, parsed.groupId)
        assertEquals(TaskPriority.High, parsed.priority)
        assertEquals(LocalDate.of(2026, 11, 20), parsed.dueDate)
        assertEquals(beto.id, parsed.reviewerId)
        assertEquals(listOf(ana.id, beto.id), parsed.assignees)
        assertEquals("Revisar", parsed.title)
    }

    @Test
    fun taskInputDefaultsAndNoneLabels() {
        val parsed = input(mapOf("group" to TareasActions.NoGroup, "reviewer" to TareasActions.NoReviewer))
        assertNull(parsed.groupId)
        assertNull(parsed.reviewerId)
        assertEquals(TaskPriority.Medium, parsed.priority)
        assertNull(parsed.dueDate)
        assertTrue(parsed.assignees.isEmpty())
    }

    @Test
    fun taskInputRejectsBadValues() {
        assertFailsWith<TareasValidationException> { input(mapOf("due" to "18/11/2026")) }
        assertFailsWith<TareasValidationException> { input(mapOf("due" to "2026-13-45")) }
        assertFailsWith<TareasValidationException> { input(mapOf("priority" to "Altísima")) }
        assertFailsWith<TareasValidationException> { input(mapOf("assignees" to UiFieldValues.options(listOf("Zoe")))) }
        assertFailsWith<TareasValidationException> { input(mapOf("reviewer" to "Zoe")) }
        assertFailsWith<TareasValidationException> { input(mapOf("group" to "Inexistente")) }
        // An archived group cannot be chosen.
        assertFailsWith<TareasValidationException> { input(mapOf("group" to "Vieja")) }
        assertFailsWith<TareasValidationException> { input(mapOf("title" to "  ")) }
    }

    @Test
    fun priorityAndStatusAcceptCodesAndLabels() {
        assertEquals(TaskPriority.Urgent, TareasActions.priority("urgent"))
        assertEquals(TaskPriority.Low, TareasActions.priority("Baja"))
        assertEquals(TaskPriority.Medium, TareasActions.priority(null))
        assertEquals(TaskPriority.Medium, TareasActions.priority("  "))
        assertEquals(TaskStatus.Review, TareasActions.status("review"))
        assertEquals(TaskStatus.InProgress, TareasActions.status("En curso"))
        assertFailsWith<TareasValidationException> { TareasActions.status("otro") }
        assertFailsWith<TareasValidationException> { TareasActions.status(null) }
    }

    @Test
    fun customerNeedsAChosenSuggestion() {
        assertFailsWith<TareasValidationException> { input(mapOf("customer" to "Acme")) }
        assertFailsWith<TareasValidationException> { input(mapOf("customer" to "Acme", "customer_id" to "no-es-uuid")) }
        val id = UUID.randomUUID()
        assertEquals(id, input(mapOf("customer" to "Acme", "customer_id" to id.toString())).customerId)
        // Clearing the visible text clears the customer, even with a stale hidden id.
        assertNull(input(mapOf("customer" to "", "customer_id" to id.toString())).customerId)
    }

    @Test
    fun customerIsKeptWhenTheUserCannotSeeCustomers() {
        val customer = UUID.randomUUID()
        val existing = TareasFixtures.task().copy(customerId = customer)
        assertEquals(customer, input(emptyMap(), canSeeCustomers = false, existing = existing).customerId)
        // What the form sends is ignored when customers are hidden.
        assertEquals(customer, input(mapOf("customer" to "x", "customer_id" to UUID.randomUUID().toString()), canSeeCustomers = false, existing = existing).customerId)
        assertNull(input(emptyMap(), canSeeCustomers = false).customerId)
    }

    @Test
    fun bodyValuesFlattenArraysAndIgnoreInvalidJson() {
        val values = TareasActions.bodyValues("""{"title":"A","assignees":["Ana Ruiz","Beto"],"n":3,"x":null}""")
        assertEquals("A", values["title"])
        assertEquals(listOf("Ana Ruiz", "Beto"), UiFieldValues.options(values.getValue("assignees")))
        assertEquals("3", values["n"])
        assertEquals("", values["x"])
        assertTrue(TareasActions.bodyValues("no json").isEmpty())
    }

    @Test
    fun boardFormParsesAndValidates() {
        val form = TareasActions.boardForm(
            mapOf("name" to " Operaciones ", "description" to " d ", "visibility" to "Privado", "members" to UiFieldValues.options(listOf("Ana Ruiz"))),
            users,
        )
        assertEquals(BoardForm("Operaciones", "d", BoardVisibility.Private, listOf(ana.id)), form)
        assertEquals(BoardVisibility.Company, TareasActions.boardForm(mapOf("name" to "A"), users).visibility)
        assertEquals(BoardVisibility.Company, TareasActions.boardForm(mapOf("name" to "A", "visibility" to "Toda la empresa"), users).visibility)
        assertFailsWith<TareasValidationException> { TareasActions.boardForm(mapOf("name" to " "), users) }
        assertFailsWith<TareasValidationException> { TareasActions.boardForm(mapOf("name" to "a".repeat(121)), users) }
        assertFailsWith<TareasValidationException> { TareasActions.boardForm(mapOf("name" to "A", "description" to "d".repeat(2001)), users) }
        assertFailsWith<TareasValidationException> { TareasActions.boardForm(mapOf("name" to "A", "visibility" to "Secreto"), users) }
        assertFailsWith<TareasValidationException> { TareasActions.boardForm(mapOf("name" to "A", "members" to UiFieldValues.options(listOf("Zoe"))), users) }
    }

    @Test
    fun filterReadsLabelsAndTreatsTodosAsNoFilter() {
        val boardId = UUID.randomUUID()
        val filter = TareasActions.filter(
            mapOf("assignee" to "ana ruiz", "status" to "En curso", "due" to "Vencidas", "q" to "  contrato "),
            users,
            boardId,
            200,
        )
        assertEquals(TaskFilter(boardId, ana.id, TaskStatus.InProgress, DueFilter.Overdue, "contrato", false, 200), filter)
        val open = TareasActions.filter(mapOf("assignee" to "Todos", "status" to "Todos", "due" to "Todos", "q" to " "), users, null, 10)
        assertEquals(TaskFilter(null, null, null, null, null, false, 10), open)
        assertEquals(DueFilter.Week, TareasActions.filter(mapOf("due" to "week"), users, null, 10).due)
        assertNull(TareasActions.filter(mapOf("assignee" to "Zoe"), users, null, 10).assignee)
    }

    @Test
    fun filterParamsKeepsOnlyUsefulFilters() {
        val kept = TareasActions.filterParams(
            mapOf("q" to " x ", "assignee" to "Todos", "status" to "En curso", "due" to "", "view" to "kanban", "id" to "1"),
        )
        assertEquals(mapOf("q" to "x", "status" to "En curso"), kept)
    }

    private fun attachment(id: UUID) = TaskAttachment(id, UUID.randomUUID(), "f.txt", "", "text/plain", 1, null, Instant.EPOCH)

    @Test
    fun fileChangesLinkNewAndRemoveMissing() {
        val kept = attachment(UUID.randomUUID())
        val gone = attachment(UUID.randomUUID())
        val fresh = UiFileRef(UUID.randomUUID().toString(), "nuevo.pdf")
        val values = mapOf("files" to UiFieldValues.files(listOf(UiFileRef(kept.id.toString(), "f.txt"), fresh)))
        val changes = TareasActions.fileChanges(values, listOf(kept, gone))
        assertEquals(listOf(fresh), changes.added)
        assertEquals(listOf(gone.id), changes.removed)
    }

    @Test
    fun fileChangesIgnoreAFormWithoutFilesKeyButEmptyListRemovesAll() {
        val current = listOf(attachment(UUID.randomUUID()))
        assertEquals(FileChanges(emptyList(), emptyList()), TareasActions.fileChanges(emptyMap(), current))
        assertEquals(FileChanges(emptyList(), listOf(current.first().id)), TareasActions.fileChanges(mapOf("files" to ""), current))
    }

    @Test
    fun requiredPermissionIsTheLeastThatWorks() {
        listOf("save", "archive", "check-remove").forEach { assertEquals(TareasModule.Manage, TareasActions.requiredPermission("task", it), it) }
        assertEquals(TareasModule.Manage, TareasActions.requiredPermission("new-task", null))
        assertEquals(TareasModule.Manage, TareasActions.requiredPermission("new-task", "save"))
        assertEquals(TareasModule.Boards, TareasActions.requiredPermission("admin", null))
        assertEquals(TareasModule.Boards, TareasActions.requiredPermission("board-edit", "save"))
        assertEquals(TareasModule.Boards, TareasActions.requiredPermission("admin", "archive"))
        listOf("status", "comment", "check-add", "check-toggle", "files", null).forEach {
            assertEquals(TareasModule.View, TareasActions.requiredPermission("task", it), "task/$it")
        }
        assertEquals(TareasModule.View, TareasActions.requiredPermission("board", "move"))
        assertEquals(TareasModule.View, TareasActions.requiredPermission("mine", null))
        assertEquals(TareasModule.View, TareasActions.requiredPermission("boards", null))
    }
}
