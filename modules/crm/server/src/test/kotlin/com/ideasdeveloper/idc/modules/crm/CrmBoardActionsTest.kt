package com.ideasdeveloper.idc.modules.crm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actions of the pipeline board (op=action) and the "@" autocomplete of the notes. */
class CrmBoardActionsTest {
    @Test
    fun cardsAreParsedOnceAndInvalidOnesCounted() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val parsed = CrmBoardActions.parseCards(" $a,$b,$a,no-es-id,")
        assertEquals(listOf(a, b), parsed.ids)
        assertEquals(1, parsed.invalid)
    }

    @Test
    fun noCardsOrTooManyIsAnError() {
        assertFailsWith<CrmActionNotice> { CrmBoardActions.parseCards(null) }
        assertFailsWith<CrmActionNotice> { CrmBoardActions.parseCards(" , ") }
        val many = List(CrmBulkActions.MaxSelected + 1) { UUID.randomUUID() }.joinToString(",")
        assertFailsWith<CrmActionNotice> { CrmBoardActions.parseCards(many) }
        val max = List(CrmBulkActions.MaxSelected) { UUID.randomUUID() }.joinToString(",")
        assertEquals(CrmBulkActions.MaxSelected, CrmBoardActions.parseCards(max).ids.size)
    }

    @Test
    fun valueMustBeAnOptionOnlyForActionsWithOptions() {
        assertEquals("Ganado", CrmBoardActions.checkValue(CrmBoardActions.Move, " Ganado ", listOf("Nuevo", "Ganado")))
        assertFailsWith<CrmActionNotice> { CrmBoardActions.checkValue(CrmBoardActions.Move, "Otra", listOf("Nuevo")) }
        assertFailsWith<CrmActionNotice> { CrmBoardActions.checkValue(CrmBoardActions.Assign, null, listOf("Ana")) }
        assertEquals("", CrmBoardActions.checkValue(CrmBoardActions.Archive, "lo-que-sea", emptyList()))
    }

    @Test
    fun declaredActionsFollowPermissions() {
        fun ids(manage: Boolean = true, assign: Boolean = true, delete: Boolean = true, user: Boolean = true, archived: Boolean = true) =
            CrmBoardActions.available(manage, assign, delete, user, archived)
        assertEquals(
            listOf("move", "assign_me", "assign", "unassign", "archive", "restore", "delete"),
            ids(),
        )
        assertEquals(emptyList(), ids(manage = false))
        // Assigning and deleting are owners-only like in the bulk bar.
        assertEquals(listOf("move", "archive", "restore"), ids(assign = false, delete = false))
        assertFalse("assign_me" in ids(user = false))
        assertFalse("restore" in ids(archived = false))
        assertTrue("delete" in ids(assign = false))
    }

    @Test
    fun boardActionsMapToBulkActions() {
        assertEquals(CrmImportScreens.ActionStage, CrmBoardActions.bulkAction("move"))
        assertEquals(CrmImportScreens.ActionClearOwners, CrmBoardActions.bulkAction("unassign"))
        assertEquals(CrmImportScreens.ActionDelete, CrmBoardActions.bulkAction("delete"))
        assertEquals(null, CrmBoardActions.bulkAction("assign_me"))
    }

    private val ana = CrmUser(UUID.randomUUID(), "Ana", "ana@x.com")
    private val beto = CrmUser(UUID.randomUUID(), "Beto Ruiz", "beto@x.com")
    private val carla = CrmUser(UUID.randomUUID(), "Carla", "carla@y.com")
    private val everybody = listOf(carla, beto, ana)

    @Test
    fun suggestionsExcludeSelfAndFilterByNameOrEmail() {
        assertEquals(listOf("Ana", "Beto Ruiz"), CrmMentions.suggest(everybody, "", carla.id).map { it.name })
        assertEquals(listOf("Beto Ruiz"), CrmMentions.suggest(everybody, "RUIZ", ana.id).map { it.name })
        // The email is never matched: it cannot be enumerated through the autocomplete.
        assertEquals(emptyList(), CrmMentions.suggest(everybody, "y.com", null))
        assertEquals(emptyList(), CrmMentions.suggest(everybody, "ana", ana.id))
    }

    @Test
    fun suggestionsAreCappedAtEightAlphabetically() {
        val many = List(12) { CrmUser(UUID.randomUUID(), "Usuario ${'a' + it}") }
        val shown = CrmMentions.suggest(many, "", null)
        assertEquals(8, shown.size)
        assertEquals("Usuario a", shown.first().name)
    }

    @Test
    fun emailShowsOnlyToTellApartUsersWithTheSameName() {
        val otherAna = CrmUser(UUID.randomUUID(), "ana", "ana2@x.com")
        val items = CrmMentions.suggestions(listOf(ana, otherAna, beto)).items
        assertEquals(listOf("ana@x.com", "ana2@x.com", ""), items.map { it.subtitle })
    }

    @Test
    fun onlyFullAccessCanGrantMentions() {
        assertTrue(CrmMentions.canGrant(CrmMentions.LeadAccess.Full))
        assertFalse(CrmMentions.canGrant(CrmMentions.LeadAccess.ReadNotes))
        assertFalse(CrmMentions.canGrant(CrmMentions.LeadAccess.None))
    }

    @Test
    fun suggestionInsertsTheNameAndTheTextResolvesIt() {
        val item = CrmMentions.suggestions(listOf(beto)).items.single()
        assertEquals("Beto Ruiz", item.title)
        assertEquals("", item.subtitle)
        val inserted = item.values.getValue("insert")
        // What the app writes: "@" + insert + " ".
        val parsed = CrmMentions.parseText("Hola @$inserted revisa esto", everybody)
        assertEquals(listOf(beto.id), parsed.users.map { it.id })
        assertTrue(parsed.unknown.isEmpty())
        assertEquals(listOf(beto.id), CrmMentions.parseText("@$inserted ", everybody).users.map { it.id })
    }
}
