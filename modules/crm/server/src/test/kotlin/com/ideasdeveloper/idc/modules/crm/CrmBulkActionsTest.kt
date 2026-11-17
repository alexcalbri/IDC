package com.ideasdeveloper.idc.modules.crm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rules of the bulk bar: who gets assigned and which leads a form may touch. */
class CrmBulkActionsTest {
    private val ana = CrmUser(UUID.randomUUID(), "Ana")
    private val users = listOf(ana, CrmUser(UUID.randomUUID(), "Beto"))

    @Test
    fun onlyTheUnassignedOptionLeavesNoOwner() {
        assertNull(CrmBulkActions.ownerFor(users, CrmForms.Unassigned))
    }

    @Test
    fun aKnownNameIsItsUser() {
        assertEquals(ana.id, CrmBulkActions.ownerFor(users, "Ana"))
    }

    @Test
    fun emptyOrUnknownOwnerIsAnErrorNotAnUnassign() {
        listOf("", "  ", "Zoe").forEach { value ->
            val failure = assertFailsWith<CrmActionNotice>(value) { CrmBulkActions.ownerFor(users, value) }
            assertEquals("Elige el responsable.", failure.message)
        }
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
