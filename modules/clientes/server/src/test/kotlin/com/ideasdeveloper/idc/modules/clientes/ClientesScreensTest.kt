package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.GroupCompany
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientesScreensTest {
    private val acme = GroupCompany("acme", "Acme")
    private val beta = GroupCompany("beta", "Beta")
    private val gamma = GroupCompany("gamma", "Gamma")

    private fun permissions(code: String, owner: Boolean, vararg granted: String) =
        ModulePermissions(code, "token", owner, granted.toSet(), userId = UUID.randomUUID())

    private fun buttons(nodes: List<UiNode>): List<UiButton> = nodes.flatMap { node ->
        when (node) {
            is UiButton -> listOf(node)
            is UiCard -> buttons(node.children + node.actions)
            is UiRow -> buttons(node.children)
            else -> emptyList()
        }
    }

    private fun item(status: String) = MergeItem(
        UUID.randomUUID(), beta, UUID.randomUUID(), "Ana", "Acme", UUID.randomUUID(), "Ana M", "ana@x.com", status, "codigo repetido", OffsetDateTime.now(),
    )

    private fun status(linked: List<GroupCompany>, candidates: List<GroupCompany> = emptyList()) =
        LinkStatus(linked, candidates, emptyList(), 0)

    @Test
    fun onlyTheBusinessOwnerManagesLinks() {
        assertTrue(ClientesLinkScreens.canManageLinks(permissions("acme", true)))
        assertFalse(ClientesLinkScreens.canManageLinks(permissions("acme", false, "clientes.fields.manage", "clientes.share", "clientes.delete")))
    }

    @Test
    fun theCompanyLogIsForTheOwnerOrWhoManagesFields() {
        assertTrue(ClientesLinkScreens.canSeeActivity(permissions("acme", true)))
        assertTrue(ClientesLinkScreens.canSeeActivity(permissions("acme", false, "clientes.fields.manage")))
        assertFalse(ClientesLinkScreens.canSeeActivity(permissions("acme", false, "clientes.view", "clientes.edit")))
    }

    @Test
    fun linksScreenOffersLinkingWithAWarningAndUnlinkingWithConfirm() {
        val screen = ClientesLinkScreens.linksScreen("acme", status(listOf(acme, beta), listOf(gamma)), emptyList(), canSeeActivity = true)
        val requests = buttons(screen.body).mapNotNull { it.action as? UiRequest }
        val link = requests.single { it.params["op"] == "link" }
        assertEquals("gamma", link.params["company"])
        assertTrue(link.confirm!!.contains("TODOS"))
        val unlink = requests.single { it.params["op"] == "unlink" }
        assertEquals("beta", unlink.params["company"])
        assertNotNull(unlink.confirm)
        assertTrue(buttons(screen.body).any { it.label == "Registro de Clientes" })
    }

    @Test
    fun linksScreenHidesTheLogButtonFromWhoCannotSeeIt() {
        val screen = ClientesLinkScreens.linksScreen("acme", status(listOf(acme)), emptyList(), canSeeActivity = false)
        assertFalse(buttons(screen.body).any { it.label == "Registro de Clientes" })
    }

    @Test
    fun reviewsCanBeSeparatedAndOnlyPendingOnesRetried() {
        val review = item("review")
        val merged = item("merged")
        val screen = ClientesLinkScreens.linksScreen("acme", LinkStatus(listOf(acme, beta), emptyList(), emptyList(), 1), listOf(review, merged), canSeeActivity = false)
        val requests = buttons(screen.body).mapNotNull { it.action as? UiRequest }
        assertEquals(listOf(review.id.toString()), requests.filter { it.params["op"] == "retry" }.map { it.params["merge"] })
        assertEquals(setOf(review.id.toString(), merged.id.toString()), requests.filter { it.params["op"] == "separate" }.mapNotNull { it.params["merge"] }.toSet())
        assertTrue(requests.filter { it.params["op"] == "separate" }.all { it.confirm != null })
    }

    @Test
    fun mergedCustomersWithDifferentNamesStayVisibleAndSeparable() {
        val merged = item("merged")
        assertTrue(ClientesLinkScreens.reviewTitle(merged).contains("Ana M"))
        assertTrue(ClientesLinkScreens.reviewSubtitle(merged).contains("ana@x.com"))
    }

    @Test
    fun jobLineShowsProgressAndProblems() {
        val job = LinkJob(acme, beta, "error", 10, 2, 1, 50, "sin conexion")
        val line = ClientesLinkScreens.jobLine(job)
        listOf("Acme -> Beta", "10 de 50", "2 fusionados", "1 por revisar", "sin conexion").forEach { assertTrue(line.contains(it), it) }
    }

    @Test
    fun activityScreenListsCompanyEventsWithTheirTitles() {
        val events = listOf(ClientesActivity.CompanyEvent(OffsetDateTime.now(), "field_created", "Campo Edad", "Ana"))
        val screen = ClientesLinkScreens.activityScreen(events, businessOwner = false)
        val list = screen.body.filterIsInstance<UiList>().single()
        assertEquals("Campo personalizado creado", list.items.single().title)
        assertTrue(buttons(screen.body).isEmpty())
    }

    @Test
    fun theOwnerReadsAnotherCompanyWithThatCompanysPermissionsNotItsOwn() {
        val current = permissions("acme", true, "clientes.view")
        val other = ClientesProfile.permissionsIn("beta", current)!!
        assertEquals("beta", other.companyCode)
        assertNull(other.userId)
        assertTrue(other.businessOwner)
        assertFalse(other.serverOwner)
        assertSame(current, ClientesProfile.permissionsIn("acme", current))
    }

    @Test
    fun aUserWhoIsNotTheOwnerNeverGetsAnotherCompanyEvenAskingForItsTab() {
        val current = permissions("acme", false, "clientes.view", "clientes.edit", "clientes.delete")
        assertNull(ClientesProfile.permissionsIn("beta", current))
        assertSame(current, ClientesProfile.permissionsIn("acme", current))
    }

    @Test
    fun profileTextSaysWhetherTheCompanyIsLinkedAndSummariesCountOnce() {
        assertTrue(ClientesProfile.linkText(listOf("Acme", "Beta"), "Acme").contains("Beta"))
        assertTrue(ClientesProfile.linkText(listOf("Acme"), "Acme").contains("no vinculada"))
        val merged = ClientesProfile.mergeUsage(
            listOf(ClientesProfile.ModuleUse("Crm", 2, "2026-01-02T10:00"), ClientesProfile.ModuleUse("Crm", 3, "2026-03-04T10:00"), ClientesProfile.ModuleUse("Hotel", 1, "2026-02-01T00:00")),
        )
        assertEquals(listOf("Crm", "Hotel"), merged.map { it.name })
        assertEquals(3, merged.first().count)
        assertEquals("Crm: 3 registros, ultimo 2026-03-04", ClientesProfile.usageLine(merged.first()))
        assertEquals("Inmobiliaria", ClientesProfile.moduleLabel("inmobiliaria"))
    }
}
