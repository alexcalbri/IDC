package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmScope
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reglas puras de la extension (sin base de datos): calculos, validaciones, lectura de formularios y permisos. */
class InmobiliariaModelTest {
    private fun money(text: String) = BigDecimal(text)

    // ---- Precio por m2 y rebajada ----

    @Test
    fun `price per m2 divides and rounds half up to two decimals`() {
        assertEquals(money("500.00"), InmobiliariaMath.pricePerM2(money("250000"), money("500")))
        assertEquals(money("33.33"), InmobiliariaMath.pricePerM2(money("100"), money("3")))
        assertEquals(money("0.13"), InmobiliariaMath.pricePerM2(money("1"), money("8")))
    }

    @Test
    fun `price per m2 is null without price or with area null or zero`() {
        assertNull(InmobiliariaMath.pricePerM2(money("100000"), null))
        assertNull(InmobiliariaMath.pricePerM2(money("100000"), BigDecimal.ZERO))
        assertNull(InmobiliariaMath.pricePerM2(null, money("100")))
    }

    @Test
    fun `reduced only when the previous price is greater than the current one`() {
        assertTrue(InmobiliariaMath.reduced(money("90000"), money("100000")))
        assertFalse(InmobiliariaMath.reduced(money("100000"), money("90000")))
        assertFalse(InmobiliariaMath.reduced(money("100000"), money("100000.00")))
        assertFalse(InmobiliariaMath.reduced(money("100000"), null))
        assertFalse(InmobiliariaMath.reduced(null, money("100000")))
    }

    // ---- Comision y reparto ----

    @Test
    fun `commission is price times percent unless a manual amount is set`() {
        assertEquals(money("4750.00"), InmobiliariaMath.commissionTotal(money("95000"), money("5"), null))
        assertEquals(money("2000.00"), InmobiliariaMath.commissionTotal(money("95000"), money("5"), money("2000")))
        assertEquals(money("2000.00"), InmobiliariaMath.commissionTotal(null, money("5"), money("2000")))
        assertNull(InmobiliariaMath.commissionTotal(null, money("5"), null))
    }

    @Test
    fun `commission rounds half up`() {
        // 333.33 x 2.5 % = 8.33325 -> 8.33 ; 100.10 x 2.5 % = 2.5025 -> 2.50 ; 100.50 x 2.5 % = 2.51250 -> 2.51
        assertEquals(money("8.33"), InmobiliariaMath.commissionTotal(money("333.33"), money("2.5"), null))
        assertEquals(money("2.51"), InmobiliariaMath.commissionTotal(money("100.50"), money("2.5"), null))
    }

    @Test
    fun `party amount is the share of the total commission`() {
        val total = money("4750.00")
        assertEquals(money("2850.00"), InmobiliariaMath.partyAmount(total, money("60")))
        assertEquals(money("1900.00"), InmobiliariaMath.partyAmount(total, money("40")))
        assertNull(InmobiliariaMath.partyAmount(null, money("40")))
    }

    @Test
    fun `shares warn only when there are parties and they do not add up to 100`() {
        assertNull(InmobiliariaMath.shareWarning(emptyList()))
        assertNull(InmobiliariaMath.shareWarning(listOf(money("60"), money("40.000"))))
        val warning = assertNotNull(InmobiliariaMath.shareWarning(listOf(money("60"), money("30"))))
        assertTrue("90" in warning && "100" in warning, warning)
        assertNotNull(InmobiliariaMath.shareWarning(listOf(money("60"), money("50"))))
        assertEquals(money("100.000"), InmobiliariaMath.shareSum(listOf(money("33.333"), money("33.333"), money("33.334"))))
    }

    @Test
    fun `plain numbers drop trailing zeros`() {
        assertEquals("5", InmobiliariaMath.plain(money("5.000")))
        assertEquals("12.5", InmobiliariaMath.plain(money("12.500")))
        assertEquals("0", InmobiliariaMath.plain(money("0.000")))
        assertEquals("1000", InmobiliariaMath.plain(money("1E+3")))
    }

    // ---- URLs ----

    @Test
    fun `valid urls are http or https with a host and no spaces`() {
        assertTrue(InmobiliariaInput.isValidUrl("https://ere.example.com/propiedad/123"))
        assertTrue(InmobiliariaInput.isValidUrl("http://example.com"))
        assertTrue(InmobiliariaInput.isValidUrl("HTTPS://Example.com/a?b=c#d"))
        assertTrue(InmobiliariaInput.isValidUrl("https://localhost:8080/x"))
        assertTrue(InmobiliariaInput.isValidUrl("https://drive.google.com/drive/folders/abc"))
    }

    @Test
    fun `invalid urls are rejected`() {
        assertFalse(InmobiliariaInput.isValidUrl(null))
        assertFalse(InmobiliariaInput.isValidUrl(""))
        assertFalse(InmobiliariaInput.isValidUrl("javascript:alert(1)"))
        assertFalse(InmobiliariaInput.isValidUrl("JavaScript://example.com/%0Aalert(1)"))
        assertFalse(InmobiliariaInput.isValidUrl("ftp://example.com/file"))
        assertFalse(InmobiliariaInput.isValidUrl("data:text/html,<b>x</b>"))
        assertFalse(InmobiliariaInput.isValidUrl("example.com"))
        assertFalse(InmobiliariaInput.isValidUrl("https://"))
        assertFalse(InmobiliariaInput.isValidUrl("https:///path"))
        assertFalse(InmobiliariaInput.isValidUrl("https://:8080/path"))
        assertFalse(InmobiliariaInput.isValidUrl("https://exa mple.com"))
        assertFalse(InmobiliariaInput.isValidUrl("https://example.com/a b"))
        assertFalse(InmobiliariaInput.isValidUrl("https://example.com/\u0000"))
        assertFalse(InmobiliariaInput.isValidUrl("https://example.com/\n"))
        assertFalse(InmobiliariaInput.isValidUrl(" https://example.com"))
        assertFalse(InmobiliariaInput.isValidUrl("https://example.com/" + "a".repeat(InmobiliariaInput.MaxUrl)))
    }

    @Test
    fun `a url of exactly the maximum length is valid`() {
        val base = "https://example.com/"
        assertTrue(InmobiliariaInput.isValidUrl(base + "a".repeat(InmobiliariaInput.MaxUrl - base.length)))
    }

    @Test
    fun `requireUrl trims and rejects with a message`() {
        assertEquals("https://example.com/x", InmobiliariaInput.requireUrl("  https://example.com/x  "))
        assertFailsWith<InmobiliariaException> { InmobiliariaInput.requireUrl("javascript:alert(1)") }
    }

    // ---- Dinero, porcentajes y otros campos de formulario ----

    @Test
    fun `money accepts form formats`() {
        assertEquals(money("1250000.50"), InmobiliariaInput.parseMoney("1,250,000.50"))
        assertEquals(money("95000.00"), InmobiliariaInput.parseMoney("$ 95000"))
        assertEquals(money("95000.00"), InmobiliariaInput.parseMoney("\$95,000"))
        assertEquals(money("0.00"), InmobiliariaInput.parseMoney("0"))
        assertNull(InmobiliariaInput.parseMoney("   "))
        assertNull(InmobiliariaInput.parseMoney(null))
    }

    @Test
    fun `money rejects negatives, letters, extra decimals and huge amounts`() {
        listOf("-5", "abc", "12.345", "1e5", "1.2.3", "1000000000000", "12 USD").forEach { raw ->
            assertFailsWith<InmobiliariaException>(raw) { InmobiliariaInput.parseMoney(raw) }
        }
        assertEquals(money("999999999999.99"), InmobiliariaInput.parseMoney("999,999,999,999.99"))
    }

    @Test
    fun `requireMoney validates already numeric amounts`() {
        assertEquals(money("100.00"), InmobiliariaInput.requireMoney(money("100")))
        assertEquals(money("100.00"), InmobiliariaInput.requireMoney(money("100.0000")))
        assertFailsWith<InmobiliariaException> { InmobiliariaInput.requireMoney(money("-1")) }
        assertFailsWith<InmobiliariaException> { InmobiliariaInput.requireMoney(money("1.005")) }
        assertFailsWith<InmobiliariaException> { InmobiliariaInput.requireMoney(money("1000000000000")) }
    }

    @Test
    fun `percent accepts the percent sign and a decimal comma`() {
        assertEquals(0, money("5").compareTo(InmobiliariaInput.parsePercent("5%")))
        assertEquals(0, money("12.5").compareTo(InmobiliariaInput.parsePercent("12,5 %")))
        assertEquals(0, money("100").compareTo(InmobiliariaInput.parsePercent("100")))
        assertEquals(0, money("0").compareTo(InmobiliariaInput.parsePercent("0")))
        assertNull(InmobiliariaInput.parsePercent(""))
    }

    @Test
    fun `percent rejects values out of range or with too many decimals`() {
        listOf("100.001", "101", "-1", "abc", "1.2345").forEach { raw ->
            assertFailsWith<InmobiliariaException>(raw) { InmobiliariaInput.parsePercent(raw) }
        }
        assertFailsWith<InmobiliariaException> { InmobiliariaInput.requirePercent(money("100.5")) }
        assertFailsWith<InmobiliariaException> { InmobiliariaInput.requirePercent(money("-0.5")) }
    }

    @Test
    fun `area bedrooms bathrooms and dates parse`() {
        assertEquals(money("1250.50"), InmobiliariaInput.parseArea("1,250.5"))
        assertNull(InmobiliariaInput.parseArea(""))
        assertFailsWith<InmobiliariaException> { InmobiliariaInput.parseArea("-3") }
        assertEquals(3, InmobiliariaInput.parseBedrooms("3"))
        assertFailsWith<InmobiliariaException> { InmobiliariaInput.parseBedrooms("101") }
        assertEquals(money("2.5"), InmobiliariaInput.parseBathrooms("2,5"))
        assertEquals(LocalDate.of(2026, 12, 31), InmobiliariaInput.parseDate("2026-12-31"))
        assertFailsWith<InmobiliariaException> { InmobiliariaInput.parseDate("31/12/2026") }
    }

    @Test
    fun `property codes follow the database rule`() {
        listOf("ERE-0123", "P-0001", "a", "A.b_c-9").forEach { assertTrue(InmobiliariaInput.CodePattern.matches(it), it) }
        listOf("", "-abc", ".abc", "a b", "a".repeat(41), "a/b").forEach { assertFalse(InmobiliariaInput.CodePattern.matches(it), it) }
    }

    @Test
    fun `property form is read and checked`() {
        val agent = UUID.randomUUID()
        val fields = InmobiliariaForms.parseProperty(
            mapOf("code" to "ERE-0123", "title" to "Lote frente al mar", "kind" to "Terreno", "agent" to "Ana", "lotArea" to "1,000.5", "bedrooms" to "2"),
        ) { name -> if (name == "Ana") agent else null }
        assertEquals("ERE-0123", fields.code)
        assertEquals(PropertyKind.Land, fields.kind)
        assertEquals(agent, fields.listingAgentId)
        assertEquals(money("1000.50"), fields.lotAreaM2)
        assertEquals(2, fields.bedrooms)
        assertFailsWith<InmobiliariaException> { InmobiliariaForms.parseProperty(mapOf("title" to "")) { null } }
        assertFailsWith<InmobiliariaException> { InmobiliariaForms.parseProperty(mapOf("title" to "x", "code" to "no valido")) { null } }
        assertFailsWith<InmobiliariaException> { InmobiliariaForms.parseProperty(mapOf("title" to "x", "kind" to "nave espacial")) { null } }
    }

    @Test
    fun `enums are read by id or label`() {
        assertEquals(PropertyStatus.ForSale, PropertyStatus.parse("for_sale"))
        assertEquals(PropertyStatus.Negotiation, PropertyStatus.parse("En negociacion"))
        assertEquals(PartyRole.BuyerLawyer, PartyRole.parse("buyer_lawyer"))
        assertEquals(DealOutcome.Won, DealOutcome.parse("GANADA"))
        assertNull(PropertyKind.parse("nada"))
        assertNull(PropertyKind.parse(null))
        assertEquals(PropertyKind.DevelopmentLot, PropertyKind.fromId("development_lot"))
    }

    @Test
    fun `the template has open stages and a won and a lost one`() {
        val stages = InmobiliariaStore.TemplateStages
        assertEquals(listOf("Oferta", "Esperando CNO", "Due diligence", "Cierre programado", "Cerrada", "Cancelada"), stages.map { it.name })
        assertEquals(DealOutcome.Won, stages[4].outcome)
        assertEquals(DealOutcome.Lost, stages[5].outcome)
        assertTrue(stages.take(4).all { it.outcome == DealOutcome.Open })
    }

    // ---- Cierres por mes ----

    private fun deal(
        close: LocalDate?,
        price: String?,
        pct: String = "5",
        manual: String? = null,
        outcome: DealOutcome = DealOutcome.Open,
        currency: String = "USD",
    ) = Deal(
        id = UUID.randomUUID(), propertyId = UUID.randomUUID(), buyerLeadId = null, buyerCustomerId = null, stageId = UUID.randomUUID(),
        agreedPrice = price?.let(::money), commissionPct = money(pct), commissionAmount = manual?.let(::money), currency = currency,
        estimatedCloseOn = close, closedOn = null, comments = "", createdBy = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        stageOutcome = outcome,
    )

    // ---- Maquina de estados de la venta ----

    private data class Case(val prev: DealOutcome?, val next: DealOutcome, val current: PropertyStatus, val open: Int, val won: Int, val expected: PropertyStatus?)

    @Test
    fun `property status after entering a stage follows the sale state machine`() {
        val o = DealOutcome.Open
        val w = DealOutcome.Won
        val l = DealOutcome.Lost
        val cases = listOf(
            // Entrar en Ganada
            Case(o, w, PropertyStatus.Negotiation, 0, 0, PropertyStatus.Sold),
            Case(null, w, PropertyStatus.ForSale, 0, 0, PropertyStatus.Sold),
            Case(l, w, PropertyStatus.Withdrawn, 0, 0, PropertyStatus.Sold),
            Case(w, w, PropertyStatus.Sold, 0, 0, null),
            // Salir de Ganada
            Case(w, o, PropertyStatus.Sold, 0, 0, PropertyStatus.Negotiation),
            Case(w, l, PropertyStatus.Sold, 0, 0, PropertyStatus.ForSale),
            Case(w, l, PropertyStatus.Sold, 2, 0, PropertyStatus.Negotiation),
            Case(w, o, PropertyStatus.Sold, 2, 0, PropertyStatus.Negotiation),
            Case(w, l, PropertyStatus.Sold, 0, 1, null),
            Case(w, o, PropertyStatus.Sold, 1, 1, null),
            Case(w, l, PropertyStatus.Withdrawn, 0, 0, null),
            // Entrar en Perdida
            Case(o, l, PropertyStatus.Negotiation, 0, 0, PropertyStatus.ForSale),
            Case(o, l, PropertyStatus.Negotiation, 1, 0, null),
            Case(o, l, PropertyStatus.ForSale, 0, 0, null),
            Case(o, l, PropertyStatus.Withdrawn, 0, 0, null),
            Case(null, l, PropertyStatus.Negotiation, 0, 0, PropertyStatus.ForSale),
            // Entrar en Abierta
            Case(null, o, PropertyStatus.ForSale, 0, 0, PropertyStatus.Negotiation),
            Case(l, o, PropertyStatus.ForSale, 0, 0, PropertyStatus.Negotiation),
            Case(o, o, PropertyStatus.Negotiation, 0, 0, null),
            Case(null, o, PropertyStatus.Intake, 0, 0, null),
            Case(l, o, PropertyStatus.Withdrawn, 0, 0, null),
        )
        cases.forEach { c ->
            assertEquals(c.expected, InmobiliariaRules.propertyStatusAfter(c.prev, c.next, c.current, c.open, c.won), c.toString())
        }
    }

    @Test
    fun `a deal exposes its commission and share warning`() {
        val dealId = UUID.randomUUID()
        fun party(share: String) = DealParty(UUID.randomUUID(), dealId, PartyRole.Broker, UUID.randomUUID(), null, "", money(share), "")
        val base = deal(LocalDate.of(2026, 12, 1), "100000")
        val balanced = base.copy(parties = listOf(party("70"), party("30")))
        assertEquals(money("5000.00"), balanced.commissionTotal)
        assertNull(balanced.shareWarning)
        assertEquals(money("3500.00"), balanced.partyAmount(balanced.parties[0]))
        assertNotNull(base.copy(parties = listOf(party("70"))).shareWarning)
    }

    @Test
    fun `currency pattern accepts only three capital letters`() {
        assertTrue(InmobiliariaInput.isCurrency("USD"))
        assertFalse(InmobiliariaInput.isCurrency("usd"))
        assertFalse(InmobiliariaInput.isCurrency("US"))
        assertFalse(InmobiliariaInput.isCurrency("USDX"))
    }

    // ---- Permisos ----

    private val agent = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun access(userId: UUID?, vararg granted: String, businessOwner: Boolean = false): InmobiliariaAccess {
        val permissions = ModulePermissions("acme", "token", businessOwner, granted.toSet(), userId)
        val scope = CrmScope(userId, permissions.has(CrmScreens.SeeAll), reception = false, interestsEnabled = true, interests = emptySet())
        return InmobiliariaAccess(permissions, scope)
    }

    @Test
    fun `view create see all and stage permissions come from the crm permissions`() {
        val viewer = access(agent, CrmScreens.View)
        assertTrue(viewer.canView)
        assertFalse(viewer.canCreate)
        assertFalse(viewer.seesAll)
        assertFalse(viewer.canEditStages)
        assertTrue(access(agent, CrmScreens.View, CrmScreens.Manage).canCreate)
        assertTrue(access(agent, CrmScreens.View, CrmScreens.SeeAll).seesAll)
        assertTrue(access(agent, CrmScreens.Settings).canEditStages)
        assertFalse(access(agent).canView)
        val owner = access(agent, businessOwner = true)
        assertTrue(owner.canView && owner.canCreate && owner.seesAll && owner.canEditStages)
    }

    @Test
    fun `an agent edits a property only if listing agent or creator, a manager edits any`() {
        val agentAccess = access(agent, CrmScreens.View, CrmScreens.Manage)
        assertTrue(agentAccess.canEditProperty(listingAgentId = agent, createdBy = other))
        assertTrue(agentAccess.canEditProperty(listingAgentId = other, createdBy = agent))
        assertFalse(agentAccess.canEditProperty(listingAgentId = other, createdBy = other))
        assertFalse(agentAccess.canEditProperty(listingAgentId = null, createdBy = null))
        val manager = access(agent, CrmScreens.View, CrmScreens.Manage, CrmScreens.SeeAll)
        assertTrue(manager.canEditProperty(listingAgentId = other, createdBy = other))
    }

    @Test
    fun `without the manage permission nobody edits a property, not even their own`() {
        val readOnly = access(agent, CrmScreens.View)
        assertFalse(readOnly.canEditProperty(listingAgentId = agent, createdBy = agent))
        val readOnlyAll = access(agent, CrmScreens.View, CrmScreens.SeeAll)
        assertFalse(readOnlyAll.canEditProperty(listingAgentId = agent, createdBy = agent))
    }

    @Test
    fun `a user without identity edits nothing unless they see all`() {
        val serverOwner = access(null, CrmScreens.View, CrmScreens.Manage)
        assertFalse(serverOwner.canEditProperty(listingAgentId = null, createdBy = null))
        assertFalse(serverOwner.canSeeDeal(createdBy = null, partyUserIds = emptyList(), listingAgentId = null))
    }

    @Test
    fun `an agent sees the deals they created, are part of or list the property of`() {
        val agentAccess = access(agent, CrmScreens.View)
        assertTrue(agentAccess.canSeeDeal(createdBy = agent, partyUserIds = emptyList(), listingAgentId = null))
        assertTrue(agentAccess.canSeeDeal(createdBy = other, partyUserIds = listOf(other, agent), listingAgentId = null))
        assertTrue(agentAccess.canSeeDeal(createdBy = other, partyUserIds = emptyList(), listingAgentId = agent))
        assertFalse(agentAccess.canSeeDeal(createdBy = other, partyUserIds = listOf(other), listingAgentId = other))
        assertFalse(agentAccess.canSeeDeal(createdBy = null, partyUserIds = emptyList(), listingAgentId = null))
    }

    @Test
    fun `see all sees every deal and editing a deal needs manage and visibility`() {
        val foreign = deal(null, "100000").copy(createdBy = other)
        val manager = access(agent, CrmScreens.View, CrmScreens.SeeAll)
        assertTrue(manager.canSeeDeal(foreign))
        assertFalse(manager.canEditDeal(foreign))
        assertTrue(access(agent, CrmScreens.View, CrmScreens.SeeAll, CrmScreens.Manage).canEditDeal(foreign))
        val agentWithManage = access(agent, CrmScreens.View, CrmScreens.Manage)
        assertFalse(agentWithManage.canEditDeal(foreign))
        assertTrue(agentWithManage.canEditDeal(foreign.copy(createdBy = agent)))
    }

    @Test
    fun `without the view permission no deal is visible`() {
        val mine = deal(null, "100000").copy(createdBy = agent)
        assertFalse(access(agent, CrmScreens.Manage).canSeeDeal(mine))
    }

    @Test
    fun `the migration passes the validator of extension migrations`() {
        val file = listOf("../migrations/V001__inmobiliaria.sql", "extensions/inmobiliaria/migrations/V001__inmobiliaria.sql")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val problems = com.ideasdeveloper.idc.server.extensions.packages.MigrationTableValidator.validate("inmobiliaria", file.readText())
        assertTrue(problems.isEmpty(), problems.joinToString("; "))
        // El codigo es unico sin distinguir mayusculas y el listado de WordPress solo se vincula una vez.
        val sql = file.readText()
        assertTrue("(lower(code))" in sql && "UNIQUE INDEX ext_inmobiliaria__properties_code_uq" in sql)
        assertTrue("WHERE wordpress_listing_id IS NOT NULL" in sql)
    }

    // ---- Importacion de WordPress ----

    @Test
    fun `wordpress kinds match whole words only`() {
        assertEquals(PropertyKind.Other, InmobiliariaService.kindFromWordPress(listOf("Highland")))
        assertEquals(PropertyKind.Other, InmobiliariaService.kindFromWordPress(listOf("Island")))
        assertEquals(PropertyKind.Other, InmobiliariaService.kindFromWordPress(listOf("Casablanca")))
        assertEquals(PropertyKind.Land, InmobiliariaService.kindFromWordPress(listOf("Lot")))
        assertEquals(PropertyKind.Land, InmobiliariaService.kindFromWordPress(listOf("Land")))
        assertEquals(PropertyKind.House, InmobiliariaService.kindFromWordPress(listOf("Casa de playa")))
        assertEquals(PropertyKind.DevelopmentLot, InmobiliariaService.kindFromWordPress(listOf("Lote en desarrollo")))
        assertEquals(PropertyKind.Condo, InmobiliariaService.kindFromWordPress(listOf("Condo", "Beachfront")))
    }

    @Test
    fun `wordpress area is parsed strictly`() {
        assertNull(InmobiliariaService.listingArea("1.234.5"))
        assertEquals(money("1234.50"), InmobiliariaService.listingArea("1,234.5"))
        assertEquals(money("1200.00"), InmobiliariaService.listingArea("1,200 sqft"))
        assertNull(InmobiliariaService.listingArea(""))
    }
}
