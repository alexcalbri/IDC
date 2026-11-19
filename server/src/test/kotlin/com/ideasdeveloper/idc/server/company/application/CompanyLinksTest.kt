package com.ideasdeveloper.idc.server.company.application

import com.ideasdeveloper.idc.server.modules.CompanyLinkException
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanyLinksTest {
    private val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun row(code: String, active: Boolean = true, server: Boolean = false, group: UUID? = owner) =
        LinkCompanyRow(code, code.uppercase(), active, server, group)

    @Test
    fun `purpose must be a lowercase word`() {
        CompanyLinks.requirePurpose("clientes")
        CompanyLinks.requirePurpose("a_1")
        listOf("", "Clientes", "1abc", "a-b", "a b", "a".repeat(64), "a'; DROP TABLE x;--").forEach {
            assertFailsWith<CompanyLinkException>(it) { CompanyLinks.requirePurpose(it) }
        }
    }

    @Test
    fun `two active companies of the same owner can be linked`() {
        CompanyLinks.validateLink("a", "b", listOf(row("a"), row("b"), row("c")))
    }

    @Test
    fun `a company cannot be linked to itself`() {
        val failure = assertFailsWith<CompanyLinkException> { CompanyLinks.validateLink("a", "a", listOf(row("a"))) }
        assertEquals("Elige otra empresa para vincular.", failure.message)
    }

    @Test
    fun `a company of another owner is not in the group and is refused`() {
        // The group rows only hold the owner's companies, so a foreign target is missing.
        val failure = assertFailsWith<CompanyLinkException> { CompanyLinks.validateLink("a", "x", listOf(row("a"), row("b"))) }
        assertEquals("Solo se pueden vincular empresas del mismo dueno.", failure.message)
    }

    @Test
    fun `inactive companies are refused on either side`() {
        assertFailsWith<CompanyLinkException> { CompanyLinks.validateLink("a", "b", listOf(row("a"), row("b", active = false))) }
        assertFailsWith<CompanyLinkException> { CompanyLinks.validateLink("a", "b", listOf(row("a", active = false), row("b"))) }
    }

    @Test
    fun `the server workspace is never linked`() {
        assertFailsWith<CompanyLinkException> { CompanyLinks.validateLink("a", "b", listOf(row("a"), row("b", server = true))) }
        assertFailsWith<CompanyLinkException> { CompanyLinks.validateLink("a", "b", listOf(row("a", server = true), row("b"))) }
    }

    @Test
    fun `the requesting company must belong to the group`() {
        assertFailsWith<CompanyLinkException> { CompanyLinks.validateLink("z", "b", listOf(row("b"), row("c"))) }
    }

    @Test
    fun `unlink needs both companies in the owner group but allows inactive ones`() {
        CompanyLinks.validateUnlink("a", "b", listOf(row("a"), row("b", active = false)))
        CompanyLinks.validateUnlink("a", "a", listOf(row("a")))
        assertFailsWith<CompanyLinkException> { CompanyLinks.validateUnlink("a", "x", listOf(row("a"))) }
        assertFailsWith<CompanyLinkException> { CompanyLinks.validateUnlink("z", "a", listOf(row("a"))) }
    }

    @Test
    fun `link group is kept inside the same owner and reset otherwise`() {
        val kept = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val other = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        assertEquals(kept, CompanyLinks.resolveLinkGroup(owner, owner, kept))
        assertEquals(owner, CompanyLinks.resolveLinkGroup(owner, null, null))
        assertEquals(owner, CompanyLinks.resolveLinkGroup(owner, other, kept))
    }

    @Test
    fun `reads join companies again by owner group and exclude inactive and server companies`() {
        val sql = CompanyLinks.LinkedSql
        assertTrue("c.owner_group = l.owner_group" in sql)
        assertTrue("mc.owner_group = me.owner_group" in sql)
        assertTrue("c.is_active AND NOT c.is_server_workspace" in sql)
        assertFalse("'" in sql, "values must be parameters")
    }

    @Test
    fun `permissions with prefix cover modules without a timeline`() {
        val user = ModulePermissions("a", "t", businessOwner = false, granted = setOf("crm.leads.view"))
        assertTrue(user.hasAnyWithPrefix("crm."))
        assertFalse(user.hasAnyWithPrefix("hotel."))
        assertTrue(ModulePermissions("a", "t", businessOwner = true, granted = emptySet()).hasAnyWithPrefix("hotel."))
    }

    @Test
    fun `unlink reads group members without filtering inactive companies`() {
        val sql = CompanyLinks.LeftInGroupSql
        assertFalse("is_active" in sql)
        assertTrue("l.owner_group = ?" in sql)
        assertTrue("c.owner_group = l.owner_group" in sql)
    }
}
