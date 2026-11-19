package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmUser
import java.math.BigDecimal
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Formato, filtros y lectura de formularios de las pantallas (sin base de datos). */
class InmobiliariaScreensTest {
    private val ana = CrmUser(UUID.randomUUID(), "Ana")
    private val luis = CrmUser(UUID.randomUUID(), "Luis")
    private val agents = listOf(ana, luis)

    // ---- Formato ----

    @Test
    fun `money groups thousands and hides zero decimals`() {
        assertEquals("USD 125,000", InmobiliariaFormat.money(BigDecimal("125000.00"), "USD"))
        assertEquals("USD 1,250,000.50", InmobiliariaFormat.money(BigDecimal("1250000.5"), "USD"))
        assertEquals("USD 0", InmobiliariaFormat.money(BigDecimal.ZERO, "USD"))
        assertEquals("-", InmobiliariaFormat.money(null, "USD"))
    }

    @Test
    fun `month label uses a short spanish name without accents`() {
        assertEquals("Ene 2027", InmobiliariaFormat.month(YearMonth.of(2027, 1)))
        assertEquals("Dic 2026", InmobiliariaFormat.month(YearMonth.of(2026, 12)))
    }

    @Test
    fun `percent and field drop trailing zeros`() {
        assertEquals("5 %", InmobiliariaFormat.percent(BigDecimal("5.000")))
        assertEquals("12.5 %", InmobiliariaFormat.percent(BigDecimal("12.500")))
        assertEquals("", InmobiliariaFormat.field(null))
        assertEquals("250000", InmobiliariaFormat.field(BigDecimal("250000.00")))
    }

    @Test
    fun `chart labels carry the currency only when months mix currencies`() {
        val one = listOf(ClosingMonth(YearMonth.of(2027, 1), "USD", 2, BigDecimal("200000"), BigDecimal("10000")))
        assertEquals(listOf("Ene 2027"), InmobiliariaDealScreens.chartPoints(one).map { it.label })
        assertEquals(10000.0, InmobiliariaDealScreens.chartPoints(one).first().value)
        val mixed = one + ClosingMonth(YearMonth.of(2027, 2), "EUR", 1, BigDecimal("100000"), BigDecimal("5000"))
        assertEquals(listOf("Ene 2027 (USD)", "Feb 2027 (EUR)"), InmobiliariaDealScreens.chartPoints(mixed).map { it.label })
    }

    // ---- Filtros ----

    @Test
    fun `property filter reads names and ignores values it does not understand`() {
        val filter = InmobiliariaFilters.property(
            mapOf("q" to " lote ", "fZone" to "Todos", "fKind" to "Terreno", "fStatus" to "En venta", "fAgent" to "Luis", "minPrice" to "100,000", "maxPrice" to "abc"),
            agents, ana.id,
        )
        assertEquals("lote", filter.text)
        assertEquals("", filter.zone)
        assertEquals(PropertyKind.Land, filter.kind)
        assertEquals(PropertyStatus.ForSale, filter.status)
        assertEquals(luis.id, filter.agentId)
        assertEquals(BigDecimal("100000.00"), filter.minPrice)
        assertNull(filter.maxPrice)
        assertNull(filter.mineOf)
    }

    @Test
    fun `my listings filters by the user and not by agent name`() {
        val filter = InmobiliariaFilters.property(mapOf("fAgent" to InmobiliariaFilters.MineListings), agents, ana.id)
        assertEquals(ana.id, filter.mineOf)
        assertNull(filter.agentId)
    }

    @Test
    fun `intake filter is always intake and mine only when asked`() {
        assertEquals(PropertyStatus.Intake, InmobiliariaFilters.intake(emptyMap(), ana.id).status)
        assertNull(InmobiliariaFilters.intake(emptyMap(), ana.id).mineOf)
        assertEquals(ana.id, InmobiliariaFilters.intake(mapOf("fMine" to "true"), ana.id).mineOf)
    }

    @Test
    fun `deal filter maps stage and my closings`() {
        val stage = DealStage(UUID.randomUUID(), "Oferta", 0, DealOutcome.Open)
        val filter = InmobiliariaFilters.deal(mapOf("fStage" to "Oferta", "fAgent" to InmobiliariaFilters.MineDeals, "fOpen" to "true"), listOf(stage), agents, luis.id)
        assertEquals(stage.id, filter.stageId)
        assertEquals(luis.id, filter.agentId)
        assertTrue(filter.openOnly)
        val none = InmobiliariaFilters.deal(mapOf("fStage" to "Todos", "fAgent" to "Todos"), listOf(stage), agents, luis.id)
        assertNull(none.stageId)
        assertNull(none.agentId)
    }

    @Test
    fun `page is clamped to the existing pages`() {
        assertEquals(1, InmobiliariaFilters.page(null, 0))
        assertEquals(1, InmobiliariaFilters.page("-3", 120))
        assertEquals(3, InmobiliariaFilters.page("9", 120))
        assertEquals(2, InmobiliariaFilters.page("2", 51))
        assertEquals(2, InmobiliariaFilters.pages(51))
    }

    @Test
    fun `keep only passes filters that have a value`() {
        assertEquals(mapOf("q" to "casa"), InmobiliariaFilters.keep(mapOf("q" to "casa", "fZone" to " ", "page" to "2", "id" to "x")))
    }

    // ---- Enlaces ----

    @Test
    fun `links skip empty rows and keep label and url as written`() {
        val links = InmobiliariaLinkForms.parse(mapOf("l1_label" to "Sitio web", "l1_url" to " https://a.com/x ", "l2_label" to "", "l2_url" to "", "l3_label" to "Drive"))
        assertEquals(listOf(LinkInput("Sitio web", "https://a.com/x"), LinkInput("Drive", "")), links)
    }

    @Test
    fun `link rows leave three empty ones and follow a form sent back`() {
        assertEquals(3, InmobiliariaLinkForms.rows(0, null))
        assertEquals(5, InmobiliariaLinkForms.rows(2, null))
        assertEquals(8, InmobiliariaLinkForms.rows(0, mapOf("l8_url" to "x")))
        assertEquals(InmobiliariaService.MaxLinks, InmobiliariaLinkForms.rows(50, null))
    }

    // ---- Partes ----

    @Test
    fun `parties read agent customer and external rows and skip empty ones`() {
        val customer = UUID.randomUUID()
        val parties = InmobiliariaDealForms.parseParties(
            mapOf(
                "p1_role" to "Agente del listado", "p1_agent" to "Ana", "p1_share" to "60 %",
                "p2_role" to "Otro", "p2_agent" to InmobiliariaDealForms.NoAgent, "p2_share" to "", "p2_name" to "",
                "p3_role" to "Abogado del comprador", "p3_name" to "Lic. Perez", "p3_share" to "10", "p3_notes" to "externo",
                "p4_role" to "Referido", "p4_customer" to customer.toString(), "p4_share" to "30",
            ),
            agents,
        )
        assertEquals(3, parties.size)
        assertEquals(PartyInput(PartyRole.ListingAgent, ana.id, null, "", BigDecimal("60.000"), ""), parties[0])
        assertEquals(PartyInput(PartyRole.BuyerLawyer, null, null, "Lic. Perez", BigDecimal("10.000"), "externo"), parties[1])
        assertEquals(customer, parties[2].customerId)
    }

    @Test
    fun `a filled party row needs a known role and agent`() {
        assertFailsWith<InmobiliariaException> { InmobiliariaDealForms.parseParties(mapOf("p1_role" to "Nada", "p1_name" to "X"), agents) }
        assertFailsWith<InmobiliariaException> { InmobiliariaDealForms.parseParties(mapOf("p1_role" to "Otro", "p1_agent" to "Pedro"), agents) }
        assertFailsWith<InmobiliariaException> { InmobiliariaDealForms.parseParties(mapOf("p1_role" to "Otro", "p1_name" to "X", "p1_share" to "150"), agents) }
        assertFailsWith<InmobiliariaException> { InmobiliariaDealForms.parseParties(mapOf("p1_role" to "Otro", "p1_customer" to "no-es-id"), agents) }
    }

    @Test
    fun `an unfilled share counts as zero percent`() {
        val parties = InmobiliariaDealForms.parseParties(mapOf("p1_role" to "Corredor", "p1_name" to "Bruno"), agents)
        assertEquals(BigDecimal.ZERO, parties.single().sharePct)
    }

    @Test
    fun `party rows leave room to add and follow a form sent back`() {
        assertEquals(4, InmobiliariaDealForms.partyRows(0, null))
        assertEquals(6, InmobiliariaDealForms.partyRows(3, null))
        assertEquals(9, InmobiliariaDealForms.partyRows(0, mapOf("p9_name" to "x")))
        assertEquals(InmobiliariaService.MaxParties, InmobiliariaDealForms.partyRows(40, null))
    }

    // ---- Etapas ----

    @Test
    fun `stages keep their ids by row and sort by the order field`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val stages = InmobiliariaDealForms.parseStages(
            mapOf(
                "s1_name" to "Oferta", "s1_outcome" to "Abierta", "s1_order" to "20",
                "s2_name" to "Cerrada", "s2_outcome" to "Ganada", "s2_order" to "30",
                "s3_name" to "Nueva", "s3_outcome" to "Abierta", "s3_order" to "10",
            ),
            listOf(first, second), 4,
        )
        assertEquals(listOf("Nueva", "Oferta", "Cerrada"), stages.map { it.name })
        assertEquals(listOf(null, first, second), stages.map { it.id })
        assertEquals(DealOutcome.Won, stages.last().outcome)
    }

    @Test
    fun `a stage row without a name is removed and an empty order follows the row`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val stages = InmobiliariaDealForms.parseStages(
            mapOf("s1_name" to "", "s1_outcome" to "Abierta", "s2_name" to "Oferta", "s2_outcome" to "Abierta", "s3_name" to "Cerrada", "s3_outcome" to "Ganada"),
            listOf(first, second), 3,
        )
        assertEquals(listOf("Oferta", "Cerrada"), stages.map { it.name })
        assertEquals(listOf(second, null), stages.map { it.id })
    }

    @Test
    fun `stage outcome and order must be valid`() {
        assertFailsWith<InmobiliariaException> { InmobiliariaDealForms.parseStages(mapOf("s1_name" to "X", "s1_outcome" to "Quien sabe"), emptyList(), 1) }
        assertFailsWith<InmobiliariaException> { InmobiliariaDealForms.parseStages(mapOf("s1_name" to "X", "s1_outcome" to "Abierta", "s1_order" to "uno"), emptyList(), 1) }
    }

    @Test
    fun `party rows keep the part reference only for the listed rows`() {
        val keepId = UUID.randomUUID()
        val parties = InmobiliariaDealForms.parseParties(
            mapOf("p1_role" to "Corredor", "p1_name" to "Bruno", "p2_role" to "Otro", "p2_name" to "Ana"),
            emptyList(), keep = mapOf(1 to keepId),
        )
        assertEquals(keepId, parties[0].keepPartyId)
        assertNull(parties[1].keepPartyId)
        val keep = mapOf(1 to keepId)
        assertEquals(keep, InmobiliariaDealForms.parseKeep(InmobiliariaDealForms.keepParam(keep)))
        assertTrue(InmobiliariaDealForms.parseKeep("x:y,3:no-uuid,").isEmpty())
    }

    @Test
    fun `a customer typed without picking one from the list is rejected`() {
        val current = UUID.randomUUID()
        val other = UUID.randomUUID()
        assertNull(InmobiliariaDealForms.pickCustomer("", current.toString(), current to "Ana", "El cliente"))
        assertEquals(current, InmobiliariaDealForms.pickCustomer("Ana", "", current to "Ana", "El cliente"))
        assertEquals(other, InmobiliariaDealForms.pickCustomer("Beto", other.toString(), current to "Ana", "El cliente"))
        assertFailsWith<InmobiliariaException> { InmobiliariaDealForms.pickCustomer("Beto", current.toString(), current to "Ana", "El cliente") }
        assertFailsWith<InmobiliariaException> { InmobiliariaDealForms.pickCustomer("Beto", "", null, "El cliente") }
    }
}
