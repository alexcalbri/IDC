package com.ideasdeveloper.idc.modules.crm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rules of the bulk bar: who gets assigned and which leads a form may touch. */
class CrmBulkActionsTest {
    private val ana = CrmUser(UUID.randomUUID(), "Ana")
    private val users = listOf(ana, CrmUser(UUID.randomUUID(), "Beto"))

    @Test
    fun aKnownNameIsItsUser() {
        assertEquals(ana.id, CrmBulkActions.userFor(users, "Ana"))
    }

    @Test
    fun emptyUnknownOrUnassignedValueIsAnErrorNeverASilentChange() {
        listOf("", "  ", "Zoe", CrmForms.Unassigned).forEach { value ->
            val failure = assertFailsWith<CrmActionNotice>(value) { CrmBulkActions.userFor(users, value) }
            assertEquals("Elige el usuario.", failure.message)
        }
    }

    private val beto = users[1]

    @Test
    fun addingAndRemovingAssigneesInBulk() {
        val lead = listOf(ana.id)
        // Add: the new user joins, the ones already there stay; repeating changes nothing.
        val added = CrmAssignees.combine(lead, CrmAssignees.Mode.Add, listOf(beto.id))
        assertEquals(listOf(ana.id, beto.id), added)
        assertTrue(CrmBulkActions.changesAssignees(lead, added))
        assertFalse(CrmBulkActions.changesAssignees(added, CrmAssignees.combine(added, CrmAssignees.Mode.Add, listOf(beto.id))))
        // Remove: only that user goes; removing someone who is not there changes nothing.
        val removed = CrmAssignees.combine(added, CrmAssignees.Mode.Remove, listOf(ana.id))
        assertEquals(listOf(beto.id), removed)
        assertFalse(CrmBulkActions.changesAssignees(lead, CrmAssignees.combine(lead, CrmAssignees.Mode.Remove, listOf(beto.id))))
        // "Sin asignar": replacing with nobody takes everybody out.
        assertEquals(emptyList(), CrmAssignees.combine(added, CrmAssignees.Mode.Replace, emptyList()))
        // The users to notify are only the ones that were not assigned before.
        assertEquals(listOf(beto.id), CrmAssignees.added(lead, added))
    }

    @Test
    fun aReplacementKeepsAssignedUsersThatCannotBeChosenAnyMore() {
        val inactive = UUID.randomUUID()
        val assignable = users.map { it.id }
        assertEquals(
            listOf(beto.id, inactive),
            CrmAssignees.combineKeepingInactive(listOf(ana.id, inactive), CrmAssignees.Mode.Replace, listOf(beto.id), assignable),
        )
        assertEquals(listOf(ana.id, inactive, beto.id), CrmAssignees.combineKeepingInactive(listOf(ana.id, inactive), CrmAssignees.Mode.Add, listOf(beto.id), assignable))
    }

    private val page = List(5) { UUID.randomUUID() }

    @Test
    fun allTakesTheWholePageAndNothingElse() {
        val stranger = UUID.randomUUID()
        val values = mapOf("all" to "true", "lead:$stranger" to "true") + page.associate { "lead:$it" to "false" }
        assertEquals(page, CrmBulkActions.selectedIds(values, page))
    }

    @Test
    fun withoutAllEachCheckboxCounts() {
        val values = page.associate { "lead:$it" to "false" } + ("lead:${page[1]}" to "true") + ("lead:${page[3]}" to "true")
        assertEquals(listOf(page[1], page[3]), CrmBulkActions.selectedIds(values, page))
    }

    @Test
    fun idsOutsideThePageAreIgnored() {
        val stranger = UUID.randomUUID()
        assertTrue(CrmBulkActions.selectedIds(mapOf("lead:$stranger" to "true", "lead:basura" to "true"), page).isEmpty())
    }

    @Test
    fun selectionIsCapped() {
        val big = List(CrmBulkActions.MaxSelected + 20) { UUID.randomUUID() }
        assertEquals(CrmBulkActions.MaxSelected, CrmBulkActions.selectedIds(mapOf("all" to "true"), big).size)
        assertEquals(CrmBulkActions.MaxSelected, CrmBulkActions.selectedIds(big.associate { "lead:$it" to "true" }, big).size)
    }

    @Test
    fun summaryReportsSkippedLeads() {
        assertEquals("3 lead(s) archivados.", CrmBulkActions.summary(3, "lead(s) archivados.", emptyMap()))
        val text = CrmBulkActions.summary(2, "lead(s) movidos.", mapOf("sin reserva" to 1))
        assertTrue("1 omitidos" in text && "sin reserva (1)" in text, text)
        assertFailsWith<CrmActionNotice> { CrmBulkActions.summary(0, "x", mapOf("sin reserva" to 2)) }
    }
}
