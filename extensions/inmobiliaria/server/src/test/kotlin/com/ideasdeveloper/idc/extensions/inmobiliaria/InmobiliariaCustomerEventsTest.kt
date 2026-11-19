package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.ui.UiOpenModule
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Entradas que la extension escribe en el historial del cliente: funciones puras, sin base de datos. */
class InmobiliariaCustomerEventsTest {
    private val buyer = UUID.randomUUID()
    private val seller = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val closed = LocalDate.of(2025, 3, 14)

    private fun property(ownerCustomerId: UUID? = owner, importKey: String? = null) = Property(
        id = UUID.randomUUID(), code = "P-0007", title = "Casa en la playa", kind = PropertyKind.Other, zone = "Coronado", development = "Los Pinos",
        status = PropertyStatus.Sold, listingAgentId = null, ownerCustomerId = ownerCustomerId, lotAreaM2 = null, builtAreaM2 = null, bedrooms = null,
        bathrooms = null, price = BigDecimal("350000"), previousPrice = null, currency = "USD", description = "descripcion privada", notes = "nota privada",
        wordpressListingId = null, publishedOn = null, soldOn = null, createdBy = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, importKey = importKey,
    )

    private fun party(role: PartyRole, customerId: UUID?, name: String = "Otra Persona") = DealParty(
        id = UUID.randomUUID(), dealId = UUID.randomUUID(), role = role, userId = null, customerId = customerId, name = name,
        sharePct = BigDecimal("37.5"), notes = "nota de la parte",
    )

    private fun deal(buyerCustomerId: UUID? = buyer, parties: List<DealParty> = emptyList()) = Deal(
        id = UUID.randomUUID(), propertyId = UUID.randomUUID(), buyerLeadId = null, buyerCustomerId = buyerCustomerId, stageId = UUID.randomUUID(),
        agreedPrice = BigDecimal("345000"), commissionPct = BigDecimal("5"), commissionAmount = BigDecimal("17250"), currency = "USD",
        estimatedCloseOn = null, closedOn = null, comments = "comentario privado", createdBy = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        stageOutcome = DealOutcome.Won, parties = parties,
    )

    @Test
    fun `a closed sale writes a purchase for the buyer and a sale for the owner`() {
        val events = InmobiliariaCustomerEvents.saleEvents(deal(), property(), closed, "Ana")
        val purchase = events.single { it.customerId == buyer }
        val sale = events.single { it.customerId == owner }
        assertEquals(2, events.size)
        assertEquals("inmueble_compra", purchase.kind)
        assertEquals("Compra de propiedad P-0007", purchase.title)
        assertEquals("inmueble_venta", sale.kind)
        assertEquals("Venta de propiedad P-0007", sale.title)
        assertTrue(events.all { it.moduleId == "crm" && it.author == "Ana" })
    }

    @Test
    fun `buyer and seller parties with a customer are included and repeated customers are written once`() {
        val partner = UUID.randomUUID()
        val deal = deal(
            parties = listOf(
                party(PartyRole.Buyer, buyer), party(PartyRole.Buyer, partner), party(PartyRole.Seller, seller), party(PartyRole.Seller, owner),
                party(PartyRole.Seller, null), party(PartyRole.BuyerAgent, UUID.randomUUID()), party(PartyRole.Broker, UUID.randomUUID()),
            ),
        )
        val events = InmobiliariaCustomerEvents.saleEvents(deal, property(), closed)
        assertEquals(setOf(buyer, partner), events.filter { it.kind == "inmueble_compra" }.map { it.customerId }.toSet())
        assertEquals(2, events.count { it.kind == "inmueble_compra" })
        assertEquals(setOf(seller, owner), events.filter { it.kind == "inmueble_venta" }.map { it.customerId }.toSet())
        assertEquals(2, events.count { it.kind == "inmueble_venta" })
        assertEquals(events.size, events.map { it.sourceRef }.distinct().size)
    }

    @Test
    fun `a sale without customers writes nothing`() {
        assertTrue(InmobiliariaCustomerEvents.saleEvents(deal(buyerCustomerId = null), property(ownerCustomerId = null), closed).isEmpty())
    }

    @Test
    fun `the entry is dated at the start of the closing day in UTC`() {
        val events = InmobiliariaCustomerEvents.saleEvents(deal(), property(), closed)
        assertTrue(events.all { it.at == Instant.parse("2025-03-14T00:00:00Z") })
    }

    @Test
    fun `source refs are stable per deal side and customer`() {
        val deal = deal()
        val first = InmobiliariaCustomerEvents.saleEvents(deal, property(), closed)
        val again = InmobiliariaCustomerEvents.saleEvents(deal, property(), closed)
        assertEquals(first.map { it.sourceRef }, again.map { it.sourceRef })
        assertEquals("deal:${deal.id}:won:buyer:$buyer", first.single { it.customerId == buyer }.sourceRef)
        assertEquals("deal:${deal.id}:won:seller:$owner", first.single { it.customerId == owner }.sourceRef)
    }

    @Test
    fun `the entry shows only the closing date and links to the deal screen`() {
        val deal = deal()
        val event = InmobiliariaCustomerEvents.saleEvents(deal, property(), closed).first()
        assertEquals("Cerrada el 2025-03-14", event.detail)
        assertEquals(UiOpenModule("crm", "inmobiliaria_deal", mapOf("id" to deal.id.toString())), event.link)
    }

    @Test
    fun `the entry never carries prices commissions notes or the names of other parties`() {
        val deal = deal(parties = listOf(party(PartyRole.Seller, seller, "Nombre Ajeno"), party(PartyRole.Buyer, null, "Comprador Ajeno")))
        val events = InmobiliariaCustomerEvents.saleEvents(deal, property(), closed, "Ana")
        val text = events.joinToString("\n") { "${it.title}\n${it.detail}\n${it.author}\n${it.kind}\n${it.sourceRef}" }
        listOf("345000", "350000", "17250", "37.5", "5%", "comentario privado", "nota de la parte", "nota privada", "descripcion privada", "Casa en la playa", "Coronado", "Los Pinos", "Nombre Ajeno", "Comprador Ajeno")
            .forEach { assertFalse(text.contains(it), "no debe aparecer '$it'") }
    }

    @Test
    fun `a hand made property with an owner writes an intake entry for the owner`() {
        val property = property()
        val now = Instant.parse("2026-01-02T10:00:00Z")
        val event = InmobiliariaCustomerEvents.intakeEvents(property, "Ana", now).single()
        assertEquals(owner, event.customerId)
        assertEquals("inmueble_captacion", event.kind)
        assertEquals("Propiedad en captación P-0007", event.title)
        assertEquals("property:${property.id}:owner:$owner", event.sourceRef)
        assertEquals(now, event.at)
        assertEquals(UiOpenModule("crm", "inmobiliaria_property", mapOf("id" to property.id.toString())), event.link)
        assertEquals("", event.detail)
    }

    @Test
    fun `intake is skipped for imported properties and for properties without owner`() {
        assertTrue(InmobiliariaCustomerEvents.intakeEvents(property(importKey = "monday:123")).isEmpty())
        assertTrue(InmobiliariaCustomerEvents.intakeEvents(property(ownerCustomerId = null)).isEmpty())
    }

    @Test
    fun `imported sales still write the closing entry with the real closing date`() {
        val events = InmobiliariaCustomerEvents.saleEvents(deal(), property(importKey = "monday:123"), LocalDate.of(2021, 6, 1))
        assertEquals(2, events.size)
        assertTrue(events.all { it.at == Instant.parse("2021-06-01T00:00:00Z") })
    }
}
