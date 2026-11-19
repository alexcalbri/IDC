package com.ideasdeveloper.idc.modules.propietarios

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reglas puras: estados, porcentajes, mapa, validacion de entradas y el plan de un cambio de titularidad. */
class PropietariosRulesTest {
    private val date = LocalDate.of(2026, 11, 18)

    private fun owner(customer: UUID = UUID.randomUUID(), share: String? = null) = NewOwner(customer, share?.let { BigDecimal(it) })

    // ---- estados ----

    @Test
    fun lotStatusCodesMatchTheDatabaseAndLabelsAreSpanish() {
        assertEquals(listOf("disponible", "reservado", "vendido", "en_construccion", "construido"), LotStatus.entries.map { it.code })
        assertEquals("En construcción", LotStatus.EnConstruccion.label)
        assertEquals(LotStatus.EnConstruccion, LotStatus.fromLabel("en construcción"))
        assertEquals(LotStatus.Vendido, LotStatus.fromLabel("vendido"))
        assertEquals(LotStatus.Reservado, LotStatus.fromCode(" RESERVADO "))
        assertNull(LotStatus.fromLabel("otro"))
    }

    @Test
    fun onlyAvailableAndReservedLotsBecomeSoldWithAnOwner() {
        assertEquals(LotStatus.Vendido, PropietariosRules.statusAfterOwnership(LotStatus.Disponible))
        assertEquals(LotStatus.Vendido, PropietariosRules.statusAfterOwnership(LotStatus.Reservado))
        assertEquals(LotStatus.Construido, PropietariosRules.statusAfterOwnership(LotStatus.Construido))
        assertEquals(LotStatus.EnConstruccion, PropietariosRules.statusAfterOwnership(LotStatus.EnConstruccion))
        assertEquals(LotStatus.Vendido, PropietariosRules.statusAfterOwnership(LotStatus.Vendido))
    }

    // ---- porcentajes ----

    @Test
    fun sharesMustBePositiveAtMostHundredWithTwoDecimals() {
        assertTrue(PropietariosRules.validShare(BigDecimal("100")))
        assertTrue(PropietariosRules.validShare(BigDecimal("33.33")))
        assertTrue(PropietariosRules.validShare(BigDecimal("0.01")))
        assertFalse(PropietariosRules.validShare(BigDecimal.ZERO))
        assertFalse(PropietariosRules.validShare(BigDecimal("-5")))
        assertFalse(PropietariosRules.validShare(BigDecimal("100.01")))
        assertFalse(PropietariosRules.validShare(BigDecimal("12.345")))
    }

    @Test
    fun sharesSumOverHundredAreRejectedIncludingWhatIsAlreadyAssigned() {
        PropietariosRules.checkShares(listOf(BigDecimal("50"), BigDecimal("50")))
        PropietariosRules.checkShares(listOf(null, null))
        assertFailsWith<PropietariosValidationException> { PropietariosRules.checkShares(listOf(BigDecimal("60"), BigDecimal("50"))) }
        assertFailsWith<PropietariosValidationException> { PropietariosRules.checkShares(listOf(BigDecimal("30")), alreadyAssigned = BigDecimal("80")) }
        PropietariosRules.checkShares(listOf(BigDecimal("20")), alreadyAssigned = BigDecimal("80"))
        assertFailsWith<PropietariosValidationException> { PropietariosRules.checkShares(listOf(BigDecimal("0"))) }
    }

    // ---- mapa ----

    @Test
    fun mapLinkPrefersHttpsUrlThenCoordinatesThenNothing() {
        val lat = BigDecimal("9.928100")
        val lng = BigDecimal("-84.090700")
        assertEquals("https://maps.example.com/lote24", PropietariosRules.mapLink(" https://maps.example.com/lote24 ", lat, lng))
        assertEquals("https://www.google.com/maps?q=9.928100,-84.090700", PropietariosRules.mapLink("", lat, lng))
        // Un enlace que no es https no se usa: se cae a las coordenadas.
        assertEquals("https://www.google.com/maps?q=9.928100,-84.090700", PropietariosRules.mapLink("javascript:alert(1)", lat, lng))
        assertEquals("https://www.google.com/maps?q=9.928100,-84.090700", PropietariosRules.mapLink("http://inseguro.example.com", lat, lng))
        assertNull(PropietariosRules.mapLink("", null, null))
        assertNull(PropietariosRules.mapLink("ftp://x", lat, null))
    }

    @Test
    fun mapValidationRequiresHttpsAndCoordinatesInRangeAndTogether() {
        assertTrue(PropietariosRules.validateMap("", null, null).isEmpty())
        assertTrue(PropietariosRules.validateMap("https://a.example.com/x", BigDecimal("9.9"), BigDecimal("-84.1")).isEmpty())
        assertTrue(PropietariosRules.validateMap("http://a.example.com", null, null).isNotEmpty())
        assertTrue(PropietariosRules.validateMap("", BigDecimal("91"), BigDecimal("0")).isNotEmpty())
        assertTrue(PropietariosRules.validateMap("", BigDecimal("0"), BigDecimal("181")).isNotEmpty())
        assertTrue(PropietariosRules.validateMap("", BigDecimal("9"), null).isNotEmpty())
    }

    // ---- entradas ----

    @Test
    fun lotInputValidationChecksNumberLengthsAndArea() {
        val dev = UUID.randomUUID()
        assertTrue(LotInput(dev, "24").validate().isEmpty())
        assertTrue(LotInput(dev, "  ").validate().isNotEmpty())
        assertTrue(LotInput(dev, "x".repeat(61)).validate().isNotEmpty())
        assertTrue(LotInput(dev, "24", registerNumber = "r".repeat(81)).validate().isNotEmpty())
        assertTrue(LotInput(dev, "24", areaM2 = BigDecimal("-1")).validate().isNotEmpty())
        assertTrue(LotInput(dev, "24", areaM2 = BigDecimal("0")).validate().isEmpty())
        assertTrue(LotInput(dev, "24", mapUrl = "http://x.example.com").validate().isNotEmpty())
    }

    @Test
    fun developmentInputNeedsAName() {
        assertTrue(DevelopmentInput("Valle").validate().isEmpty())
        assertTrue(DevelopmentInput(" ").validate().isNotEmpty())
        assertTrue(DevelopmentInput("n".repeat(201)).validate().isNotEmpty())
    }

    // ---- plan del cambio de titularidad ----

    @Test
    fun transferClosesEveryOpenOwnershipOpensTheNewOnesAndSellsTheLot() {
        val a = PropietariosFixtures.ownership("Ana")
        val b = PropietariosFixtures.ownership("Luis", startsOn = date.minusDays(10))
        val buyer = UUID.randomUUID()
        val plan = PropietariosRules.planTransfer(date, listOf(a, b), listOf(owner(buyer, "100")), setOf(buyer), LotStatus.Disponible)
        assertEquals(listOf(a.id, b.id), plan.closeIds)
        assertEquals(LotStatus.Vendido, plan.newStatus)
        assertEquals("traspaso", plan.kind)
    }

    @Test
    fun transferKeepsAConstructionStatus() {
        val buyer = UUID.randomUUID()
        val plan = PropietariosRules.planTransfer(date, emptyList(), listOf(owner(buyer)), setOf(buyer), LotStatus.EnConstruccion)
        assertEquals(LotStatus.EnConstruccion, plan.newStatus)
        assertTrue(plan.closeIds.isEmpty())
    }

    @Test
    fun transferRejectsSharesOverHundred() {
        val one = UUID.randomUUID()
        val two = UUID.randomUUID()
        assertFailsWith<PropietariosValidationException> {
            PropietariosRules.planTransfer(date, emptyList(), listOf(owner(one, "70"), owner(two, "40")), setOf(one, two), LotStatus.Disponible)
        }
    }

    @Test
    fun transferRejectsACustomerTheUserCannotSee() {
        val visible = UUID.randomUUID()
        val hidden = UUID.randomUUID()
        val failure = assertFailsWith<PropietariosValidationException> {
            PropietariosRules.planTransfer(date, emptyList(), listOf(owner(visible), owner(hidden)), setOf(visible), LotStatus.Disponible)
        }
        assertTrue("Clientes" in failure.message.orEmpty())
    }

    @Test
    fun transferRejectsARepeatedOwner() {
        val same = UUID.randomUUID()
        assertFailsWith<PropietariosValidationException> {
            PropietariosRules.planTransfer(date, emptyList(), listOf(owner(same), owner(same)), setOf(same), LotStatus.Disponible)
        }
    }

    @Test
    fun transferRejectsADateBeforeTheStartOfAnOpenOwnership() {
        val buyer = UUID.randomUUID()
        val recent = PropietariosFixtures.ownership("Ana", startsOn = date.plusDays(1))
        assertFailsWith<PropietariosValidationException> {
            PropietariosRules.planTransfer(date, listOf(recent), listOf(owner(buyer)), setOf(buyer), LotStatus.Vendido)
        }
        // El mismo dia es valido: la titularidad empieza y termina el mismo dia.
        val sameDay = PropietariosFixtures.ownership("Ana", startsOn = date)
        PropietariosRules.planTransfer(date, listOf(sameDay), listOf(owner(buyer)), setOf(buyer), LotStatus.Vendido)
    }

    @Test
    fun emptyNewOwnersIsABajaThatKeepsTheStatus() {
        val a = PropietariosFixtures.ownership("Ana")
        val plan = PropietariosRules.planTransfer(date, listOf(a), emptyList(), emptySet(), LotStatus.Vendido)
        assertEquals("baja", plan.kind)
        assertEquals(LotStatus.Vendido, plan.newStatus)
        assertEquals(listOf(a.id), plan.closeIds)
    }

    @Test
    fun aBajaOfALotWithoutOwnersIsRejected() {
        assertFailsWith<PropietariosValidationException> {
            PropietariosRules.planTransfer(date, emptyList(), emptyList(), emptySet(), LotStatus.Vendido)
        }
    }

    @Test
    fun transferAcceptsAnOwnerThatContinuesWithAnotherShare() {
        val ana = UUID.randomUUID()
        val current = PropietariosFixtures.ownership("Ana", customerId = ana, share = BigDecimal("100"))
        val other = UUID.randomUUID()
        val plan = PropietariosRules.planTransfer(date, listOf(current), listOf(owner(ana, "50"), owner(other, "50")), setOf(ana, other), LotStatus.Vendido)
        assertEquals(listOf(current.id), plan.closeIds)
        assertEquals("traspaso", plan.kind)
    }

    // ---- historial ----

    @Test
    fun historyDetailListsFromAndToWithNotes() {
        val detail = PropietariosRules.historyDetail("traspaso", listOf("Ana (50 %)", "Luis"), listOf("Marta"), " Escritura 99 ")
        assertEquals("De: Ana (50 %), Luis → A: Marta\nNotas: Escritura 99", detail)
        assertEquals("Baja de: Ana", PropietariosRules.historyDetail("baja", listOf("Ana"), emptyList(), ""))
        assertEquals("Alta de: Marta (25 %)", PropietariosRules.historyDetail("alta", emptyList(), listOf("Marta (25 %)"), ""))
        assertEquals("Ana (12.5 %)", PropietariosRules.ownerLabel("Ana", BigDecimal("12.50")))
        assertEquals("Ana", PropietariosRules.ownerLabel("Ana", null))
    }

    @Test
    fun lotDiffReportsOnlyWhatChanged() {
        val lot = PropietariosFixtures.lot(status = LotStatus.Disponible, area = BigDecimal("250.50"))
        val same = LotInput(lot.developmentId, lot.lotNumber, lot.registerNumber, lot.propertyName, lot.propertyType, lot.status, BigDecimal("250.5"))
        assertEquals("", PropietariosService.lotDiff(lot, same))
        val changed = same.copy(status = LotStatus.Vendido, areaM2 = BigDecimal("300"))
        val diff = PropietariosService.lotDiff(lot, changed)
        assertTrue("Estado: Disponible → Vendido" in diff)
        assertTrue("Área: 250.5 m² → 300 m²" in diff)
    }

    @Test
    fun ownershipDiffReportsShareAndDates() {
        val old = PropietariosFixtures.ownership("Ana", share = BigDecimal("50"), startsOn = date.minusDays(5))
        assertEquals("", PropietariosService.ownershipDiff(old, BigDecimal("50.00"), date.minusDays(5), null))
        val diff = PropietariosService.ownershipDiff(old, BigDecimal("40"), date.minusDays(5), date)
        assertTrue("porcentaje 50 % → 40 %" in diff)
        assertTrue("fin — → $date" in diff)
    }
}
