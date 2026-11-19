package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.modules.tareas.TareasFixtures.task
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Who gets each notice: never the author, never twice. */
class TareasNotifyTest {
    private val author = UUID.randomUUID()
    private val ana = UUID.randomUUID()
    private val beto = UUID.randomUUID()

    @Test
    fun assignmentSkipsTheAuthorAndRepeats() {
        assertEquals(listOf(ana, beto), TareasNotify.assignedTargets(listOf(ana, author, beto, ana), author))
    }

    @Test
    fun assignmentWithoutAuthorKeepsEveryone() {
        assertEquals(listOf(ana), TareasNotify.assignedTargets(listOf(ana), null))
        assertTrue(TareasNotify.assignedTargets(emptyList(), author).isEmpty())
    }

    @Test
    fun commentGoesToResponsiblesAndReviewerWithoutTheAuthor() {
        val task = task(assignees = listOf(ana, author), reviewerId = beto)
        assertEquals(listOf(ana, beto), TareasNotify.commentRecipients(task, author))
    }

    @Test
    fun commentCountsAPersonWhoIsResponsibleAndReviewerOnce() {
        val task = task(assignees = listOf(ana), reviewerId = ana)
        assertEquals(listOf(ana), TareasNotify.commentRecipients(task, author))
    }

    @Test
    fun commentByTheOnlyParticipantNotifiesNobody() {
        assertTrue(TareasNotify.commentRecipients(task(assignees = listOf(author), reviewerId = author), author).isEmpty())
        assertTrue(TareasNotify.commentRecipients(task(), author).isEmpty())
    }

    @Test
    fun stateChangeNoticesGoToResponsiblesExceptWhoChangedIt() {
        val task = task(assignees = listOf(ana, author, ana), reviewerId = beto)
        assertEquals(listOf(ana), TareasNotify.assigneeRecipients(task, author))
        assertEquals(listOf(ana, author), TareasNotify.assigneeRecipients(task, null))
    }

    @Test
    fun noticesAreSilentWithoutNotifier() {
        // No notifier: nothing may touch the connection.
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ ->
            error("The connection must not be used")
        } as Connection
        TareasNotify.assigned(connection, null, task(assignees = listOf(ana)), listOf(ana), author)
        TareasNotify.sentToReview(connection, null, task(reviewerId = ana), author)
        TareasNotify.returned(connection, null, task(assignees = listOf(ana)), author)
        TareasNotify.approved(connection, null, task(assignees = listOf(ana)), author)
        TareasNotify.commented(connection, null, task(assignees = listOf(ana)), author, "Ana", "hola")
        assertEquals(0, TareasNotify.dueReminders(connection, null, LocalDate.of(2026, 11, 18)))
    }
}
