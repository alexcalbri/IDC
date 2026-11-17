package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Menu by permissions, tabs of Configuracion, old routes and the remembered view of Leads. */
class CrmMenuAndTabsTest {
    private fun permissions(vararg granted: String) = ModulePermissions("acme", "token", false, granted.toSet())

    private val everything = permissions(
        CrmScreens.View, CrmScreens.Manage, CrmScreens.Delete, CrmScreens.Reception, CrmScreens.SeeAll, CrmScreens.Settings, CrmScreens.Assign,
    )

    @Test
    fun menuIsNuevoLeadLeadsSinCalificarSeguimientosConfiguracion() {
        assertEquals(listOf("new", "leads", "reception", "followups", "settings"), CrmScreens.menu(everything, interestsEnabled = true))
        assertEquals(listOf("new", "leads", "reception", "followups", "settings"), CrmScreens.menu(everything, interestsEnabled = false))
    }

    @Test
    fun pipelineAndBulkAreNoLongerMenuItems() {
        val menu = CrmScreens.menu(everything, interestsEnabled = true)
        listOf("pipeline", "bulk", "mail", "spam", "templates", "team", "integrations", "import").forEach { assertFalse(it in menu, it) }
    }

    @Test
    fun menuByPermission() {
        assertEquals(listOf("leads", "followups"), CrmScreens.menu(permissions(CrmScreens.View), interestsEnabled = true))
        assertEquals(listOf("new"), CrmScreens.menu(permissions(CrmScreens.Manage), interestsEnabled = true))
        assertEquals(listOf("reception"), CrmScreens.menu(permissions(CrmScreens.Reception), interestsEnabled = true))
        assertTrue(CrmScreens.menu(permissions(), interestsEnabled = true).isEmpty())
    }

    @Test
    fun onlyAssignSeesJustSinCalificar() {
        val assigner = permissions(CrmScreens.Assign)
        assertEquals(listOf("reception"), CrmScreens.menu(assigner, interestsEnabled = true))
        assertEquals("reception", CrmScreens.home(assigner))
        assertFalse(CrmScreens.canOpen("settings", assigner))
        assertFalse(CrmScreens.canOpen("pipeline", assigner))
    }

    @Test
    fun onlySettingsSeesJustConfiguracionAndEveryTab() {
        val admin = permissions(CrmScreens.Settings)
        assertEquals(listOf("settings"), CrmScreens.menu(admin, interestsEnabled = true))
        assertEquals("settings", CrmScreens.home(admin))
        assertEquals(
            listOf("settings", "automations", "team", "mail", "templates", "spam", "integrations", "import"),
            CrmScreens.visibleTabs(admin),
        )
        assertTrue(CrmScreens.canOpen("mail", admin))
        assertFalse(CrmScreens.canOpen("leads", admin))
    }

    @Test
    fun configuracionAndItsTabsNeedSettings() {
        val manager = permissions(CrmScreens.View, CrmScreens.Manage, CrmScreens.Assign)
        assertFalse("settings" in CrmScreens.menu(manager, interestsEnabled = true))
        assertTrue(CrmScreens.visibleTabs(manager).isEmpty())
        listOf("settings", "automations", "team", "mail", "templates", "spam", "integrations", "import", "importjob").forEach { screen ->
            assertEquals(CrmScreens.Settings, CrmScreens.permissionFor(screen), screen)
            assertFalse(CrmScreens.canOpen(screen, manager), screen)
        }
    }

    @Test
    fun homeOpensLeadsFirstEvenWithManage() {
        assertEquals("leads", CrmScreens.home(everything))
        assertEquals("leads", CrmScreens.home(permissions(CrmScreens.View, CrmScreens.Manage)))
        assertEquals("new", CrmScreens.home(permissions(CrmScreens.Manage)))
    }

    @Test
    fun oldSectionRoutesOpenTheirTab() {
        mapOf("mail" to "mail", "spam" to "spam", "templates" to "templates", "team" to "team", "integrations" to "integrations", "import" to "import", "importjob" to "import", "settings" to "settings", "automations" to "automations")
            .forEach { (route, tab) ->
                assertTrue(CrmScreens.isScreen(route), route)
                assertEquals(tab, CrmScreens.settingsTabOf(route), route)
            }
        assertNull(CrmScreens.settingsTabOf("leads"))
        assertNull(CrmScreens.settingsTabOf("pipeline"))
        assertNull(CrmScreens.settingsTabOf("lead"))
    }

    @Test
    fun oldLeadRoutesOpenTheirView() {
        assertTrue(CrmScreens.isScreen("pipeline"))
        assertEquals(CrmScreens.ViewPipeline, CrmScreens.forcedView("pipeline"))
        assertEquals(CrmScreens.View, CrmScreens.permissionFor("bulk"))
        assertEquals("leads", CrmScreens.canonical("bulk"))
        assertEquals(CrmScreens.ViewList, CrmScreens.forcedView("bulk"))
        assertEquals(CrmScreens.View, CrmScreens.permissionFor("pipeline"))
        // The unified item has no forced view: it opens the remembered one.
        assertNull(CrmScreens.forcedView("leads"))
        assertEquals("reception", CrmScreens.canonical("assignments"))
    }

    @Test
    fun bulkRouteNeedsViewToOpenButManageToApply() {
        // Opening "bulk" shows the list (crm.view); the bar itself only draws with crm.leads.manage (see bulkCard).
        assertTrue(CrmScreens.canOpen("bulk", permissions(CrmScreens.View)))
        assertFalse(CrmScreens.canOpen("bulk", permissions()))
    }

    @Test
    fun theViewSentWinsOverTheRememberedOne() {
        assertEquals("list", CrmScreens.resolveLeadsView("list", "pipeline"))
        assertEquals("pipeline", CrmScreens.resolveLeadsView("pipeline", "list"))
    }

    @Test
    fun noViewSentKeepsTheRememberedOne() {
        assertEquals("list", CrmScreens.resolveLeadsView(null, "list"))
        assertEquals("pipeline", CrmScreens.resolveLeadsView(null, "pipeline"))
    }

    @Test
    fun unknownViewsFallBackToThePipeline() {
        assertEquals("pipeline", CrmScreens.resolveLeadsView("kanban", "nonsense"))
        assertEquals("list", CrmScreens.resolveLeadsView("kanban", "list"))
    }
}
