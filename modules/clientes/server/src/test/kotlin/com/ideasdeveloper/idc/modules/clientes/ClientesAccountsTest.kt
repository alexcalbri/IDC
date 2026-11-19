package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientesAccountsTest {
    private fun permissions(owner: Boolean, userId: UUID = UUID.randomUUID(), granted: Set<String> = emptySet()) =
        ModulePermissions("acme", "t", owner, granted, userId = userId)

    private fun link(name: String?) = ClientesAccounts.Link(
        UUID.randomUUID(), name?.let { ClientesAccounts.Account(UUID.randomUUID(), it, "ana@x.com") }, "Owner", OffsetDateTime.now(),
    )

    @Test
    fun emailRuleIgnoresCaseAndSpacesAndRejectsEmpty() {
        assertTrue(ClientesAccounts.emailMatches("Ana@X.com", " ana@x.COM "))
        assertFalse(ClientesAccounts.emailMatches("ana@x.com", "otra@x.com"))
        assertFalse(ClientesAccounts.emailMatches("", ""))
        assertFalse(ClientesAccounts.emailMatches("ana@x.com", ""))
        assertFalse(ClientesAccounts.emailMatches("  ", "  "))
    }

    @Test
    fun onlyTheOwnerOrTheLinkPermissionCanLink() {
        assertTrue(ClientesAccounts.canLink(permissions(true)))
        assertTrue(ClientesAccounts.canLink(permissions(false, granted = setOf(ClientesAccounts.LinkPermission))))
        assertFalse(ClientesAccounts.canLink(permissions(false, granted = setOf("clientes.edit", "clientes.share", "clientes.fields.manage"))))
    }

    @Test
    fun aUserWithoutThePermissionCannotLinkTheirOwnAccount() {
        val me = UUID.randomUUID()
        assertFalse(ClientesAccounts.canLink(permissions(false, me, setOf("clientes.view", "clientes.edit"))))
    }

    @Test
    fun theAccountNameIsHiddenFromWhoCannotLink() {
        val withName = link("Ana Perez")
        assertEquals(listOf("Cliente", "Usuario: Ana Perez"), ClientesAccounts.roles(withName, showAccount = true))
        assertEquals(listOf("Cliente", "Usuario"), ClientesAccounts.roles(withName, showAccount = false))
        assertEquals(listOf("Cliente"), ClientesAccounts.roles(null, showAccount = true))
        assertEquals(listOf("Cliente", "Usuario"), ClientesAccounts.roles(link(null), showAccount = true))
        assertTrue(ClientesAccounts.canSeeAccount(permissions(true)))
        assertFalse(ClientesAccounts.canSeeAccount(permissions(false, granted = setOf("clientes.view"))))
    }

    @Test
    fun theLinkToolsDeclareThePermissionAndItIsDefined() {
        val tools = ClientesMcpTools.mcp.tools.filter { it.name in setOf("link_user_account", "unlink_user_account") }
        assertEquals(2, tools.size)
        tools.forEach { assertEquals(ClientesAccounts.LinkPermission, it.permission) }
        assertTrue(ClientesServerModule.definition.permissions.any { it.id == ClientesAccounts.LinkPermission })
    }

    @Test
    fun theNewActivityKindsHaveTitles() {
        assertEquals("Cuenta de usuario enlazada", ClientesActivity.title("user_linked"))
        assertEquals("Cuenta de usuario desenlazada", ClientesActivity.title("user_unlinked"))
    }
}
