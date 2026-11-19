package com.ideasdeveloper.idc.modules.tareas

import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pure rules of the import: the preview summary, matching people, trimming and what an old task does to the reminders. */
class TareasImportApplyTest {
    private val ana = TareasUser(UUID.randomUUID(), "Ana Pérez", "Ana@Empresa.com")
    private val luis = TareasUser(UUID.randomUUID(), "Luis", "luis@empresa.com")
    private val users = listOf(ana, luis)

    private fun row(key: String, title: String = "Tarea $key", vararg emails: String, group: String? = null, names: List<String> = emptyList()) =
        ImportedTask(key = key, title = title, groupName = group, assigneeEmails = emails.toList(), assigneeNames = names)

    // A connection that fails on any use: the rules that validate before touching the database must not reach it.
    private val noDatabase: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        throw AssertionError("No debe usar la base de datos: ${method.name}")
    } as Connection

    @Test
    fun matchesPeopleByEmailIgnoringCase() {
        val summary = TareasImportApply.summary(listOf(row("1", "A", "ana@empresa.COM", " LUIS@empresa.com ")), users)
        assertEquals(listOf("Ana Pérez", "Luis"), summary.matchedPeople)
        assertTrue(summary.unmatchedPeople.isEmpty())
    }

    @Test
    fun listsPeopleWithoutUser() {
        val rows = listOf(
            row("1", "A", "nadie@otra.com", "ana@empresa.com", names = listOf("Carlos")),
            row("2", "B", "NADIE@otra.com", names = listOf("carlos")),
        )
        val summary = TareasImportApply.summary(rows, users)
        assertEquals(listOf("Ana Pérez"), summary.matchedPeople)
        assertEquals(listOf("nadie@otra.com", "Carlos"), summary.unmatchedPeople)
    }

    @Test
    fun reviewerNameCountsWhenThereIsNoEmail() {
        val reviewer = ImportedTask("1", "A", reviewerName = "Marta", reviewerEmail = null)
        assertEquals(listOf("Marta"), TareasImportApply.summary(listOf(reviewer), users).unmatchedPeople)
        val matched = ImportedTask("2", "B", reviewerEmail = "luis@empresa.com")
        assertEquals(listOf("Luis"), TareasImportApply.summary(listOf(matched), users).matchedPeople)
    }

    @Test
    fun blankTitleOrKeyAreProblemsAndNotCounted() {
        val rows = listOf(row("1", "Ok"), row("2", "   "), row("", "Sin clave"), row("1", "Repetida"))
        val summary = TareasImportApply.summary(rows, users)
        assertEquals(1, summary.tasks)
        assertEquals(3, summary.problems.size)
        assertTrue(summary.problems.any { "2" in it && "título" in it })
        assertTrue(summary.problems.any { "identificador" in it })
        assertTrue(summary.problems.any { "más de una vez" in it })
    }

    @Test
    fun countsGroupsChecklistCommentsAndFiles() {
        val first = row("1", "A", group = "Esta semana").copy(
            checklist = listOf(ImportedCheck("sub:1", "Paso", false), ImportedCheck("sub:2", "Otro", true)),
            comments = listOf(ImportedComment("upd:1", "Ana", Instant.EPOCH, "Hola")),
        )
        val second = row("2", "B", group = "esta semana")
        val third = row("3", "C", group = "Próxima")
        val summary = TareasImportApply.summary(listOf(first, second, third), users, files = 4)
        assertEquals(3, summary.tasks)
        assertEquals(listOf("Esta semana", "Próxima"), summary.groups)
        assertEquals(2, summary.checklistItems)
        assertEquals(1, summary.comments)
        assertEquals(4, summary.files)
    }

    @Test
    fun sourceIsValidatedBeforeAnyWrite() {
        val board = UUID.randomUUID()
        val today = LocalDate.of(2026, 11, 18)
        listOf("manual", "agent", "Monday", "1monday", "").forEach { source ->
            assertFailsWith<TareasValidationException>(source) { TareasImportApply.apply(noDatabase, source, board, row("1"), users, null, today) }
        }
    }

    @Test
    fun rowsWithoutTitleOrKeyAreSkippedWithoutTouchingTheDatabase() {
        val board = UUID.randomUUID()
        val today = LocalDate.of(2026, 11, 18)
        assertTrue(TareasImportApply.apply(noDatabase, "monday", board, row("1", "  "), users, null, today) is ImportOutcome.Skipped)
        assertTrue(TareasImportApply.apply(noDatabase, "monday", board, row(" ", "Algo"), users, null, today) is ImportOutcome.Skipped)
        assertTrue(TareasImportApply.apply(noDatabase, "monday", board, row("x".repeat(201), "Algo"), users, null, today) is ImportOutcome.Skipped)
    }

    @Test
    fun clipsLongTextWithAnEllipsis() {
        assertEquals("abc", TareasImportApply.clip("abc", 3))
        val clipped = TareasImportApply.clip("a".repeat(400), 300)
        assertEquals(300, clipped.length)
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun matchesUsersIgnoringCaseAndSpaces() {
        assertEquals(ana, TareasImportApply.matchUser("  ANA@empresa.com ", users))
        assertEquals(null, TareasImportApply.matchUser("", users))
        assertEquals(null, TareasImportApply.matchUser(null, users))
        assertEquals(null, TareasImportApply.matchUser("otro@empresa.com", users))
    }

    @Test
    fun commentCarriesAuthorAndOriginalDate() {
        val at = LocalDate.of(2026, 3, 4).atTime(10, 30).toInstant(ZoneOffset.UTC)
        val text = TareasImportApply.commentText("Ana", at, " Hola ", java.time.ZoneId.of("UTC"))
        assertEquals("[Ana · 2026-03-04 10:30] Hola", text)
        assertEquals("Hola", TareasImportApply.commentText("", null, "Hola"))
        assertEquals(TareasRepository.MaxCommentLength, TareasImportApply.commentText("Ana", at, "x".repeat(9000)).length)
    }

    @Test
    fun peopleWithoutUserAreNotedAtTheEndOfTheDetail() {
        val detail = TareasImportApply.detailWithNotes("Detalle", listOf("Carlos", "x@y.com"), listOf("Marta"))
        assertEquals("Detalle\n\nResponsables en Monday sin usuario: Carlos, x@y.com\nRevisor en Monday sin usuario: Marta", detail)
        assertEquals("Detalle", TareasImportApply.detailWithNotes(" Detalle ", emptyList(), emptyList()))
        val long = TareasImportApply.detailWithNotes("d".repeat(30000), listOf("Carlos"), emptyList())
        assertEquals(TaskInput.MaxDetail, long.length)
        assertTrue(long.endsWith("Responsables en Monday sin usuario: Carlos"))
    }

    @Test
    fun oldOpenTasksSuppressTheirReminders() {
        val today = LocalDate.of(2026, 11, 18)
        assertTrue(TareasImportApply.suppressesReminders(TaskStatus.InProgress, today.minusDays(30), today))
        assertTrue(TareasImportApply.suppressesReminders(TaskStatus.Pending, today.plusDays(1), today))
        assertTrue(!TareasImportApply.suppressesReminders(TaskStatus.Pending, today.plusDays(2), today))
        assertTrue(!TareasImportApply.suppressesReminders(TaskStatus.Done, today.minusDays(30), today))
        assertTrue(!TareasImportApply.suppressesReminders(TaskStatus.Pending, null, today))
    }
}
