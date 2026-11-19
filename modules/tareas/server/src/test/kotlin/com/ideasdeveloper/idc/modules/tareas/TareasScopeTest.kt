package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.modules.tareas.TareasFixtures.board
import com.ideasdeveloper.idc.modules.tareas.TareasFixtures.task
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Visibility and permission rules of tasks (`TareasScope`), for every kind of user and board. */
class TareasScopeTest {
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun scope(admin: Boolean = false, canManage: Boolean = false, memberOf: Set<UUID> = emptySet(), userId: UUID? = me) =
        TareasScope(userId, admin, canManage, memberOf)

    @Test
    fun companyBoardIsSeenByAnyoneWithView() {
        assertTrue(scope().seesBoard(board(BoardVisibility.Company)))
    }

    @Test
    fun privateBoardIsSeenOnlyByMembers() {
        val private = board(BoardVisibility.Private)
        assertFalse(scope().seesBoard(private))
        assertTrue(scope(memberOf = setOf(private.id)).seesBoard(private))
        assertFalse(scope(memberOf = setOf(UUID.randomUUID())).seesBoard(private))
    }

    @Test
    fun archivedBoardIsHiddenEvenFromItsMembers() {
        val archivedCompany = board(BoardVisibility.Company, archived = true)
        val archivedPrivate = board(BoardVisibility.Private, archived = true)
        assertFalse(scope().seesBoard(archivedCompany))
        assertFalse(scope(memberOf = setOf(archivedPrivate.id)).seesBoard(archivedPrivate))
    }

    @Test
    fun adminSeesEveryBoardIncludingPrivateAndArchived() {
        val admin = scope(admin = true)
        assertTrue(admin.seesBoard(board(BoardVisibility.Private)))
        assertTrue(admin.seesBoard(board(BoardVisibility.Private, archived = true)))
        assertTrue(admin.seesBoard(board(BoardVisibility.Company, archived = true)))
    }

    @Test
    fun assigneeSeesTaskOfPrivateBoardButNotTheBoard() {
        val private = board(BoardVisibility.Private)
        val task = task(private.id, assignees = listOf(me), visibility = BoardVisibility.Private)
        val scope = scope()
        assertFalse(scope.seesBoard(private))
        assertTrue(scope.seesTask(task))
    }

    @Test
    fun reviewerSeesTaskOfPrivateBoard() {
        val task = task(reviewerId = me, visibility = BoardVisibility.Private)
        assertTrue(scope().seesTask(task))
    }

    @Test
    fun unrelatedUserDoesNotSeeTaskOfPrivateBoard() {
        val task = task(assignees = listOf(other), reviewerId = other, visibility = BoardVisibility.Private)
        assertFalse(scope().seesTask(task))
    }

    @Test
    fun participantSeesTaskOfArchivedBoard() {
        val task = task(assignees = listOf(me), archived = true)
        assertTrue(scope().seesTask(task))
        assertFalse(scope().seesTask(task(archived = true)))
    }

    @Test
    fun userWithoutManageNeverEditsButWorksOnItsOwnTasks() {
        val mine = task(assignees = listOf(me))
        val reviewed = task(reviewerId = me)
        val foreign = task(assignees = listOf(other))
        val scope = scope(canManage = false)
        assertFalse(scope.canEditTask(mine))
        assertTrue(scope.canWorkOnTask(mine))
        assertTrue(scope.canWorkOnTask(reviewed))
        assertFalse(scope.canWorkOnTask(foreign))
    }

    @Test
    fun managerEditsTasksOfBoardsTheySee() {
        val scope = scope(canManage = true)
        assertTrue(scope.canEditTask(task()))
        assertTrue(scope.canWorkOnTask(task()))
        // A private board that the manager is not a member of cannot be edited.
        assertFalse(scope.canEditTask(task(visibility = BoardVisibility.Private)))
        assertFalse(scope.canWorkOnTask(task(visibility = BoardVisibility.Private)))
        assertFalse(scope.canEditTask(task(archived = true)))
    }

    @Test
    fun managerMemberOfPrivateBoardEdits() {
        val private = board(BoardVisibility.Private)
        val scope = scope(canManage = true, memberOf = setOf(private.id))
        assertTrue(scope.canEditTask(task(private.id, visibility = BoardVisibility.Private)))
    }

    @Test
    fun adminWithManageEditsEverywhereAdminWithoutManageDoesNot() {
        val task = task(visibility = BoardVisibility.Private, archived = true)
        assertTrue(scope(admin = true, canManage = true).canEditTask(task))
        assertFalse(scope(admin = true, canManage = false).canEditTask(task))
        assertTrue(scope(admin = true, canManage = false).seesTask(task))
    }

    @Test
    fun reviewerCanReviewEvenWithoutManage() {
        val task = task(assignees = listOf(other), reviewerId = me)
        assertTrue(scope().canReview(task))
        assertFalse(scope().canReview(task(assignees = listOf(me), reviewerId = other)))
        assertTrue(scope(canManage = true).canReview(task(reviewerId = other)))
    }

    @Test
    fun scopeWithoutUserOnlyHasWhatItsFlagsGrant() {
        val anonymous = scope(userId = null)
        assertFalse(anonymous.canWorkOnTask(task(assignees = listOf(me), reviewerId = null)))
        assertFalse(anonymous.canReview(task()))
        assertTrue(anonymous.seesBoard(board()))
    }

    @Test
    fun seesBoardOverloadsAgree() {
        val private = board(BoardVisibility.Private)
        val scope = scope(memberOf = setOf(private.id))
        assertEquals(scope.seesBoard(private), scope.seesBoard(private.id, private.visibility, private.archived))
    }
}
