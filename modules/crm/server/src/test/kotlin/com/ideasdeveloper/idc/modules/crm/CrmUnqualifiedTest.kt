package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Sin calificar": leads without interest or without an assigned user. */
class CrmUnqualifiedTest {
    private fun permissions(vararg granted: String) = ModulePermissions("acme", "token", false, granted.toSet())

    @Test
    fun conditionNeedsNoInterestAndNobodyAssigned() {
        assertEquals("(l.interest_id IS NULL AND ${CrmAssignees.NoneSql})", unqualifiedSql(interestsEnabled = true))
        assertFalse(unqualifiedSql(interestsEnabled = true).contains("owner_user_id"))
    }

    @Test
    fun withInterestsOffOnlyTheAssignedUsersMatter() {
        assertEquals(CrmAssignees.NoneSql, unqualifiedSql(interestsEnabled = false))
    }

    @Test
    fun aLeadLeavesWithAtLeastOneAssignedUserOrAnInterest() {
        val interest = UUID.randomUUID()
        val ana = UUID.randomUUID()
        val beto = UUID.randomUUID()
        // Nothing yet: it is waiting.
        assertTrue(isUnqualified(null, emptyList(), interestsEnabled = true))
        // One assigned user is enough, with or without interest.
        assertFalse(isUnqualified(null, listOf(ana), interestsEnabled = true))
        assertFalse(isUnqualified(null, listOf(ana, beto), interestsEnabled = true))
        // An interest is enough too.
        assertFalse(isUnqualified(interest, emptyList(), interestsEnabled = true))
        // With interests off the interest does not count.
        assertTrue(isUnqualified(interest, emptyList(), interestsEnabled = false))
        assertFalse(isUnqualified(null, listOf(ana), interestsEnabled = false))
        // Removing the last assigned user puts a lead without interest back in the list.
        assertTrue(isUnqualified(null, CrmAssignees.combine(listOf(ana), CrmAssignees.Mode.Remove, listOf(ana)), interestsEnabled = true))
        assertFalse(isUnqualified(null, CrmAssignees.combine(listOf(ana, beto), CrmAssignees.Mode.Remove, listOf(ana)), interestsEnabled = true))
    }

    @Test
    fun filterIsOffByDefault() {
        assertFalse(CrmLeadFilter().unqualified)
        assertTrue(CrmLeadFilter(unqualified = true).unqualified)
    }

    @Test
    fun assignRouteStillOpensSinCalificar() {
        assertTrue(CrmScreens.isScreen("assignments"))
        assertEquals("reception", CrmScreens.canonical("assignments"))
        assertEquals(CrmScreens.Reception, CrmScreens.permissionFor("assignments"))
        assertEquals("leads", CrmScreens.canonical("leads"))
    }

    @Test
    fun menuAndHomeIncludeSinCalificarWithOnlyAssign() {
        val assigner = permissions(CrmScreens.Assign)
        assertTrue("reception" in CrmScreens.menu(assigner, interestsEnabled = false))
        assertTrue("reception" in CrmScreens.menu(assigner, interestsEnabled = true))
        assertEquals("reception", CrmScreens.home(assigner))
        assertTrue(CrmScreens.canOpen("assignments", assigner))
    }

    @Test
    fun menuHidesSinCalificarWithoutReceptionOrAssign() {
        val viewer = permissions(CrmScreens.View)
        assertFalse("reception" in CrmScreens.menu(viewer, interestsEnabled = true))
        assertFalse(CrmScreens.canOpen("reception", viewer))
        assertTrue("reception" in CrmScreens.menu(permissions(CrmScreens.Reception), interestsEnabled = true))
    }
}
