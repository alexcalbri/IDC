package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Sin calificar": leads without interest or without an assigned user. */
class CrmUnqualifiedTest {
    private fun permissions(vararg granted: String) = ModulePermissions("acme", "token", false, granted.toSet())

    @Test
    fun conditionMatchesLeadsWithoutInterestOrWithoutOwner() {
        assertEquals("(l.interest_id IS NULL OR l.owner_user_id IS NULL)", unqualifiedSql(interestsEnabled = true))
    }

    @Test
    fun withInterestsOffOnlyTheOwnerMatters() {
        assertEquals("l.owner_user_id IS NULL", unqualifiedSql(interestsEnabled = false))
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
