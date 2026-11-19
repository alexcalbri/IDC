package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.ui.UiBoard
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiScreen
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Pure builders of the native screens: buckets, status buttons, kanban shape and the UI contract. */
class TareasScreensTest {
    // A Wednesday: its ISO week starts on Monday 2026-11-16.
    private val today = LocalDate.of(2026, 11, 18)
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val users = listOf(TareasUser(me, "Yo", "yo@example.com"), TareasUser(other, "Otro", "otro@example.com"))

    private fun due(date: LocalDate?, status: TaskStatus = TaskStatus.Pending) = TareasFixtures.task(status = status, dueDate = date)

    private fun titles(buckets: List<Pair<String, List<Task>>>) = buckets.map { it.first }

    @Test
    fun mineBucketsSplitByDayAndWeekLimits() {
        val tasks = listOf(
            due(today.minusDays(1)),
            due(today),
            due(today.plusDays(1)),
            due(today.plusDays(7)),
            due(today.plusDays(8)),
            due(null),
            due(today.minusDays(3), TaskStatus.Done),
            due(today, TaskStatus.Cancelled),
        )
        val buckets = TareasScreens.mineBuckets(tasks, today)
        assertEquals(listOf("Vencidas", "Hoy", "Próximos 7 días", "Más adelante", "Sin fecha"), titles(buckets))
        assertEquals(listOf(1, 1, 2, 1, 1), buckets.map { it.second.size })
        assertEquals(today.plusDays(7), buckets[2].second.maxOf { it.dueDate ?: today })
    }

    @Test
    fun mineBucketsLeaveEmptyGroupsOut() {
        assertEquals(listOf("Hoy"), titles(TareasScreens.mineBuckets(listOf(due(today)), today)))
        assertTrue(TareasScreens.mineBuckets(emptyList(), today).isEmpty())
    }

    @Test
    fun agendaBucketsGroupByIsoWeek() {
        val tasks = listOf(
            due(today.minusDays(2)),
            due(LocalDate.of(2026, 11, 10), TaskStatus.Done),
            due(LocalDate.of(2026, 11, 18)),
            due(LocalDate.of(2026, 11, 22)),
            due(LocalDate.of(2026, 11, 23)),
            due(LocalDate.of(2027, 1, 17)),
            due(LocalDate.of(2027, 1, 18)),
            due(null),
        )
        val buckets = TareasScreens.agendaBuckets(tasks, today)
        val names = titles(buckets)
        assertEquals("Vencidas", names[0])
        assertEquals("Anteriores", names[1])
        // Monday to Sunday of this week share a group; Monday 23 starts the next one.
        assertTrue(names[2].startsWith("Semana ") && names[2].endsWith("2026-11-16 a 2026-11-22"), names[2])
        assertEquals(2, buckets[2].second.size)
        assertTrue(names[3].endsWith("2026-11-23 a 2026-11-29"), names[3])
        // Eight weeks ahead is still a week of its own; the ninth is "later".
        assertTrue(names[4].endsWith("2027-01-11 a 2027-01-17"), names[4])
        assertEquals("Más adelante", names[5])
        assertEquals("Sin fecha", names[6])
        assertEquals(7, names.size)
    }

    private fun scope(canManage: Boolean = false, admin: Boolean = false) = TareasScope(me, admin, canManage, emptySet())

    private fun labels(scope: TareasScope, task: Task) = TareasScreens.statusActions(scope, task).map { it.label }

    @Test
    fun statusActionsForAWorkerWithoutReviewer() {
        val worker = scope()
        assertEquals(listOf("Empezar", "Marcar hecha"), labels(worker, TareasFixtures.task(status = TaskStatus.Pending, assignees = listOf(me))))
        assertEquals(listOf("Marcar hecha"), labels(worker, TareasFixtures.task(status = TaskStatus.InProgress, assignees = listOf(me))))
        assertEquals(listOf("Reabrir"), labels(worker, TareasFixtures.task(status = TaskStatus.Done, assignees = listOf(me))))
    }

    @Test
    fun statusActionsSendToReviewWhenThereIsAReviewer() {
        val worker = scope()
        val pending = TareasFixtures.task(status = TaskStatus.Pending, assignees = listOf(me), reviewerId = other)
        assertEquals(listOf("Empezar", "Enviar a revisión"), labels(worker, pending))
        val actions = TareasScreens.statusActions(worker, TareasFixtures.task(status = TaskStatus.InProgress, assignees = listOf(me), reviewerId = other))
        assertEquals(listOf(TaskStatus.Review), actions.map { it.status })
    }

    @Test
    fun statusActionsInReviewDependOnCanReview() {
        val inReview = TareasFixtures.task(status = TaskStatus.Review, assignees = listOf(me), reviewerId = other)
        assertTrue(labels(scope(), inReview).isEmpty())
        val reviewer = TareasFixtures.task(status = TaskStatus.Review, assignees = listOf(other), reviewerId = me)
        val actions = TareasScreens.statusActions(scope(), reviewer)
        assertEquals(listOf("Aprobar", "Devolver"), actions.map { it.label })
        assertEquals(listOf(TaskStatus.Done, TaskStatus.InProgress), actions.map { it.status })
    }

    @Test
    fun statusActionsOfAManagerWhoIsNotTheReviewerCanReviewAndCancel() {
        val inReview = TareasFixtures.task(status = TaskStatus.Review, assignees = listOf(other), reviewerId = other)
        val manager = scope(canManage = true)
        assertEquals(listOf("Aprobar", "Devolver", "Cancelar tarea"), labels(manager, inReview))
        val cancel = TareasScreens.statusActions(manager, inReview).last()
        assertEquals(TaskStatus.Cancelled, cancel.status)
        assertTrue(cancel.confirm != null)
        // Closed tasks cannot be cancelled again.
        assertEquals(listOf("Reabrir"), labels(manager, TareasFixtures.task(status = TaskStatus.Done)))
    }

    @Test
    fun statusActionsAreEmptyWithoutAccess() {
        assertTrue(labels(scope(), TareasFixtures.task(status = TaskStatus.Pending, assignees = listOf(other))).isEmpty())
    }

    // ---- screens ----

    private fun context(scope: TareasScope = scope(canManage = true, admin = true)) =
        TareasScreens.Context(scope, users, today, canSeeCustomers = true, zone = java.time.ZoneOffset.UTC)

    private val boardModel = TareasFixtures.board().let { it.copy(name = "Operaciones") }
    private val group = BoardGroup(UUID.randomUUID(), boardModel.id, "Esta semana", 0, false)

    private fun taskIn(status: TaskStatus, index: Int) =
        TareasFixtures.task(boardModel.id, status, listOf(me), dueDate = today.plusDays(index.toLong())).copy(title = "T$index")

    private fun data(perColumn: Int) = TareasScreens.BoardData(
        boardModel,
        listOf(group),
        tasks = TaskStatus.entries.flatMap { status -> (1..perColumn).map { taskIn(status, it) } },
        columns = TaskStatus.entries.associateWith { status -> (1..perColumn).map { taskIn(status, it) } },
        workload = listOf(WorkloadRow(me, 3, 1, 1)),
    )

    private fun roundTrip(screen: UiScreen) {
        val json = UiJson.encodeToString(UiScreen.serializer(), screen)
        assertEquals(screen, UiJson.decodeFromString(UiScreen.serializer(), json))
    }

    @Test
    fun kanbanHasFiveColumnsMoveRouteAndAtMostFiftyCardsEach() {
        val screen = TareasScreens.board(context(), data(KanbanLimitPlusExtra), mapOf("view" to "kanban", "q" to "x", "status" to "Hecha"))
        val board = screen.body.filterIsInstance<UiBoard>().single()
        assertEquals(TaskStatus.entries.map { it.code }, board.columns.map { it.id })
        assertTrue(board.columns.all { it.cards.size == TareasScreens.KanbanLimit })
        assertTrue(board.columns.all { it.caption.isNotEmpty() })
        val move = board.move
        assertIs<com.ideasdeveloper.idc.ui.UiRequest>(move)
        assertEquals("/modules/tareas/ui/board", move.route)
        assertEquals("move", move.params["op"])
        assertEquals(boardModel.id.toString(), move.params["id"])
        assertEquals("kanban", move.params["view"])
        assertEquals("x", move.params["q"])
        // The state filter would hide the destination column: it is not carried.
        assertTrue("status" !in move.params)
        roundTrip(screen)
    }

    @Test
    fun kanbanWithFewTasksShowsAllWithoutCutNotice() {
        val screen = TareasScreens.board(context(), data(2), mapOf("view" to "kanban"))
        val board = screen.body.filterIsInstance<UiBoard>().single()
        assertTrue(board.columns.all { it.cards.size == 2 && it.caption.isEmpty() })
    }

    @Test
    fun everyBoardViewAndScreenSerializesWithTheContract() {
        val context = context()
        val data = data(3)
        listOf("list", "kanban", "agenda", "workload", "desconocida").forEach {
            roundTrip(TareasScreens.board(context, data, mapOf("view" to it)))
        }
        roundTrip(TareasScreens.mine(context, data.tasks))
        roundTrip(TareasScreens.boards(context, listOf(boardModel)))
        roundTrip(TareasScreens.admin(context, listOf(boardModel)))
        roundTrip(TareasScreens.newTask(context, boardModel, listOf(group), group.id))
        roundTrip(TareasScreens.boardEdit(context, boardModel, listOf(group)))
        roundTrip(TareasScreens.boardEdit(context, null, emptyList()))
        val task = data.tasks.first()
        roundTrip(TareasScreens.task(context, TareasScreens.TaskDetail(task, listOf(group), emptyList(), emptyList(), emptyList(), emptyList())))
        roundTrip(TareasScreens.task(context(scope()), TareasScreens.TaskDetail(task, listOf(group), emptyList(), emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun unknownViewFallsBackToList() {
        assertEquals("list", TareasScreens.viewOf(mapOf("view" to "xyz")))
        assertEquals("kanban", TareasScreens.viewOf(mapOf("view" to "kanban")))
        assertEquals("list", TareasScreens.viewOf(emptyMap()))
    }

    @Test
    fun navigationShowsAdminOnlyToAdmins() {
        assertEquals(listOf("mine", "boards"), TareasScreens.navigation(scope(), "mine").items.map { it.id })
        assertEquals(listOf("mine", "boards", "admin"), TareasScreens.navigation(scope(admin = true), "mine").items.map { it.id })
    }

    private companion object {
        const val KanbanLimitPlusExtra = TareasScreens.KanbanLimit + 5
    }
}
