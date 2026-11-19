package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.modules.tareas.TareasFixtures.task
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** State changes: who may leave review, redirection to review and rejections. */
class TaskTransitionsTest {
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val worker = TareasScope(me, admin = false, canManage = false, memberOf = emptySet())
    private val manager = TareasScope(me, admin = false, canManage = true, memberOf = emptySet())

    @Test
    fun sameStatusIsUnchanged() {
        assertEquals(TransitionResult.Unchanged, TaskTransitions.resolve(task(assignees = listOf(me)), TaskStatus.Pending, worker))
    }

    @Test
    fun workerWithoutReviewerCompletesDirectly() {
        val result = TaskTransitions.resolve(task(status = TaskStatus.InProgress, assignees = listOf(me)), TaskStatus.Done, worker)
        assertEquals(TransitionResult.Apply(TaskStatus.Done), result)
    }

    @Test
    fun workerAskingDoneOnATaskWithReviewerGoesToReview() {
        val result = TaskTransitions.resolve(task(status = TaskStatus.InProgress, assignees = listOf(me), reviewerId = other), TaskStatus.Done, worker)
        assertEquals(TransitionResult.Apply(TaskStatus.Review, redirectedToReview = true), result)
    }

    @Test
    fun workerCanSendToReviewExplicitly() {
        val result = TaskTransitions.resolve(task(status = TaskStatus.InProgress, assignees = listOf(me), reviewerId = other), TaskStatus.Review, worker)
        assertEquals(TransitionResult.Apply(TaskStatus.Review), result)
    }

    @Test
    fun reviewerApprovesOrReturns() {
        val inReview = task(status = TaskStatus.Review, assignees = listOf(other), reviewerId = me)
        assertEquals(TransitionResult.Apply(TaskStatus.Done), TaskTransitions.resolve(inReview, TaskStatus.Done, worker))
        assertEquals(TransitionResult.Apply(TaskStatus.InProgress), TaskTransitions.resolve(inReview, TaskStatus.InProgress, worker))
    }

    @Test
    fun managerCanLeaveReviewWithoutBeingTheReviewer() {
        val inReview = task(status = TaskStatus.Review, assignees = listOf(other), reviewerId = other)
        assertEquals(TransitionResult.Apply(TaskStatus.Done), TaskTransitions.resolve(inReview, TaskStatus.Done, manager))
    }

    @Test
    fun workerThatIsNotReviewerCannotLeaveReview() {
        val inReview = task(status = TaskStatus.Review, assignees = listOf(me), reviewerId = other)
        assertIs<TransitionResult.Reject>(TaskTransitions.resolve(inReview, TaskStatus.Done, worker))
        assertIs<TransitionResult.Reject>(TaskTransitions.resolve(inReview, TaskStatus.InProgress, worker))
    }

    @Test
    fun userWhoCannotWorkOnTheTaskIsRejected() {
        val result = TaskTransitions.resolve(task(assignees = listOf(other)), TaskStatus.Done, worker)
        assertIs<TransitionResult.Reject>(result)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun rejectionForNoAccessComesBeforeUnchanged() {
        assertIs<TransitionResult.Reject>(TaskTransitions.resolve(task(assignees = listOf(other)), TaskStatus.Pending, worker))
    }

    @Test
    fun managerCompletesATaskWithReviewerDirectly() {
        val result = TaskTransitions.resolve(task(status = TaskStatus.InProgress, reviewerId = other), TaskStatus.Done, manager)
        assertEquals(TransitionResult.Apply(TaskStatus.Done), result)
    }

    @Test
    fun cancellingAndReopeningAreFree() {
        val mine = task(status = TaskStatus.Done, assignees = listOf(me))
        assertEquals(TransitionResult.Apply(TaskStatus.Pending), TaskTransitions.resolve(mine, TaskStatus.Pending, worker))
        assertEquals(TransitionResult.Apply(TaskStatus.Cancelled), TaskTransitions.resolve(mine, TaskStatus.Cancelled, worker))
    }
}
