package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiNode
import kotlinx.serialization.json.JsonNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Punto de enganche [CrmScreenProvider]: ids validos, permisos y pantallas propias que no se pueden pisar (sin base de datos). */
class CrmScreenProviderTest {
    private fun permissions(vararg granted: String) = ModulePermissions("acme", "token", false, granted.toSet())

    private class FakeProvider : ServerExtension, CrmScreenProvider {
        override val manifest = ExtensionManifest(id = "demo", module = "crm", displayName = "Demo", version = "1.0.0")
        override val extensionId = "demo"
        override val sections = listOf(
            CrmExtensionSection("demo_list", "Demo", CrmScreens.View),
            CrmExtensionSection("demo_detail", "Detalle", CrmScreens.Manage, inMenu = false),
            CrmExtensionSection("leads", "Choca", CrmScreens.View),
            CrmExtensionSection("other_screen", "Sin prefijo", CrmScreens.View),
        )

        override val mcpTools = listOf(
            McpTool("demo_list", "Lista", "d", CrmScreens.View, readOnly = true, handler = { JsonNull }),
            McpTool("sin_prefijo", "Sin prefijo", "d", CrmScreens.View, readOnly = true, handler = { JsonNull }),
        )

        override fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> = emptyList()

        override fun action(
            context: CrmScreens.Context,
            actor: CrmActor,
            screen: String,
            op: String,
            params: Map<String, String>,
            values: Map<String, String>,
        ) = CrmExtensionActionResult(screen, null)
    }

    @BeforeTest
    fun link() = CrmModuleExtensions.link(listOf(FakeProvider()))

    @AfterTest
    fun unlink() = CrmModuleExtensions.link(emptyList())

    @Test
    fun findsTheSectionsOfTheProvider() {
        val (provider, section) = CrmScreenProviders.section("demo_list")!!
        assertEquals("demo", provider.extensionId)
        assertEquals("Demo", section.label)
        assertEquals(1, CrmModuleExtensions.screenProviders().size)
        assertEquals(listOf("demo_list", "demo_detail"), CrmScreenProviders.sections(provider).map { it.id })
    }

    @Test
    fun ignoresIdsThatClashWithOwnScreensOrLackThePrefix() {
        assertNull(CrmScreenProviders.section("leads"))
        assertNull(CrmScreenProviders.section("other_screen"))
        assertNull(CrmScreenProviders.section("unknown"))
        assertEquals(CrmScreens.View, CrmScreens.permissionFor("leads"))
    }

    @Test
    fun ignoresMcpToolsWithoutTheExtensionPrefix() {
        assertEquals(listOf("demo_list"), CrmScreenProviders.mcpTools.map { it.name })
    }

    @Test
    fun screensOfTheProviderAreRecognizedWithTheirPermission() {
        assertTrue(CrmScreens.isScreen("demo_list"))
        assertTrue(CrmScreens.isScreen("demo_detail"))
        assertFalse(CrmScreens.isScreen("other_screen"))
        assertEquals(CrmScreens.View, CrmScreens.permissionFor("demo_list"))
        assertEquals(CrmScreens.Manage, CrmScreens.permissionFor("demo_detail"))
        assertTrue(CrmScreens.canOpen("demo_list", permissions(CrmScreens.View)))
        assertFalse(CrmScreens.canOpen("demo_detail", permissions(CrmScreens.View)))
        assertTrue(CrmScreens.canOpen("demo_detail", permissions(CrmScreens.Manage)))
        assertFalse(CrmScreens.canOpen("demo_list", permissions()))
    }

    @Test
    fun ownScreenIdIsNeverTakenByTheProvider() {
        assertTrue(CrmScreens.isScreen("leads"))
        assertNull(CrmScreenProviders.section("leads"))
        val provider = CrmScreenProviders.section("demo_list")!!.first
        assertFalse(CrmScreenProviders.sections(provider).any { it.id == "leads" })
    }

    @Test
    fun invalidPrefixIsNotListedAmongTheSections() {
        val provider = CrmScreenProviders.section("demo_list")!!.first
        assertFalse(CrmScreenProviders.sections(provider).any { it.id == "other_screen" })
        assertNull(CrmScreens.permissionFor("other_screen"))
    }

    @Test
    fun cacheIsRecalculatedAfterLinkingAnotherProvider() {
        assertNotNull(CrmScreenProviders.section("demo_list"))
        assertNull(CrmScreenProviders.section("zeta_home"))
        val other = object : ServerExtension, CrmScreenProvider {
            override val manifest = ExtensionManifest(id = "zeta", module = "crm", displayName = "Zeta", version = "1.0.0")
            override val extensionId = "zeta"
            override val sections = listOf(CrmExtensionSection("zeta_home", "Zeta", CrmScreens.View))
            override val mcpTools = listOf(McpTool("zeta_list", "Lista", "d", CrmScreens.View, readOnly = true, handler = { JsonNull }))
            override fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> = emptyList()
            override fun action(
                context: CrmScreens.Context,
                actor: CrmActor,
                screen: String,
                op: String,
                params: Map<String, String>,
                values: Map<String, String>,
            ) = CrmExtensionActionResult(screen, null)
        }
        CrmModuleExtensions.link(listOf(other))
        assertNull(CrmScreenProviders.section("demo_list"))
        assertEquals("zeta", CrmScreenProviders.section("zeta_home")!!.first.extensionId)
        assertEquals(listOf("zeta_list"), CrmScreenProviders.mcpTools.map { it.name })
        CrmModuleExtensions.link(emptyList())
    }

    @Test
    fun importTargetsAreListedAfterLinkingAndClearedWithoutExtensions() {
        val target = object : ServerExtension, CrmImportTarget {
            override val manifest = ExtensionManifest(id = "destino", module = "crm", displayName = "Destino", version = "1.0.0")
            override val extensionId = "destino"
            override val importKinds = listOf(CrmImportKind("item", "Item", listOf(CrmImportField("title", "Titulo", required = true))))
            override fun importRecords(tenant: java.sql.Connection, actor: CrmActor, kind: String, records: List<CrmImportRecord>) =
                CrmImportOutcome(records.size, 0, 0, emptyList())
        }
        assertTrue(CrmModuleExtensions.importTargets().isEmpty())
        CrmModuleExtensions.link(listOf(target))
        assertEquals(listOf("destino"), CrmModuleExtensions.importTargets().map { it.extensionId })
        assertEquals("item", CrmModuleExtensions.importTargets().single().importKinds.single().id)
        CrmModuleExtensions.link(emptyList())
        assertTrue(CrmModuleExtensions.importTargets().isEmpty())
    }

    @Test
    fun withoutProvidersNothingExtraIsRecognized() {
        CrmModuleExtensions.link(emptyList())
        assertFalse(CrmScreens.isScreen("demo_list"))
        assertNull(CrmScreenProviders.section("demo_list"))
        assertFalse(CrmScreens.canOpen("demo_list", permissions(CrmScreens.View, CrmScreens.Manage)))
        assertTrue(CrmScreenProviders.mcpTools.isEmpty())
    }
}
