package com.ideasdeveloper.idc.extensions.inmobiliaria

import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Reglas puras de la importacion (sin base de datos): estados, etapas, fechas, nombres, booleanos y enlaces. */
class InmobiliariaImportTest {
    private val rules = InmobiliariaImportRules

    @Test
    fun mapsMondayStatuses() {
        assertEquals(PropertyStatus.ForSale, rules.statusFor("FOR SALE", false))
        assertEquals(PropertyStatus.ForSale, rules.statusFor("REDUCED", false))
        assertEquals(PropertyStatus.Sold, rules.statusFor("Sold", false))
        assertEquals(PropertyStatus.Sold, rules.statusFor("Vendida", false))
        assertEquals(PropertyStatus.ForSale, rules.statusFor("for_sale", false))
        assertEquals(PropertyStatus.Withdrawn, rules.statusFor("Retirada", false))
    }

    @Test
    fun blankStatusDependsOnIntake() {
        assertEquals(PropertyStatus.ForSale, rules.statusFor("", false))
        assertEquals(PropertyStatus.Intake, rules.statusFor("  ", true))
        assertEquals(PropertyStatus.ForSale, rules.statusFor("FOR SALE", true))
    }

    @Test
    fun unknownStatusIsRejected() {
        assertFailsWith<InmobiliariaException> { rules.statusFor("quien sabe", false) }
    }

    @Test
    fun mapsStageNamesToOutcomes() {
        assertEquals(DealOutcome.Won, rules.stageOutcome("Closed"))
        assertEquals(DealOutcome.Won, rules.stageOutcome("Cerrada"))
        assertEquals(DealOutcome.Won, rules.stageOutcome(" won "))
        assertEquals(DealOutcome.Lost, rules.stageOutcome("Canceled"))
        assertEquals(DealOutcome.Lost, rules.stageOutcome("Cancelada"))
        assertEquals(DealOutcome.Lost, rules.stageOutcome("Lost"))
        assertEquals(DealOutcome.Open, rules.stageOutcome("Due diligence"))
        assertEquals(DealOutcome.Open, rules.stageOutcome(""))
    }

    @Test
    fun parsesBothDateFormats() {
        assertEquals(LocalDate.of(2026, 3, 9), rules.parseDate("2026-03-09"))
        assertEquals(LocalDate.of(2026, 3, 9), rules.parseDate("03/09/2026"))
        assertEquals(LocalDate.of(2026, 3, 9), rules.parseDate("3/9/2026"))
        assertEquals(LocalDate.of(2026, 3, 9), rules.parseDate("2026-03-09 14:30"))
        assertEquals(LocalDate.of(2026, 3, 9), rules.parseDate("2026-03-09T14:30:00"))
        assertNull(rules.parseDate("  "))
        assertNull(rules.parseDate(null))
    }

    @Test
    fun rejectsInvalidDates() {
        assertFailsWith<InmobiliariaException> { rules.parseDate("13/45/2026") }
        assertFailsWith<InmobiliariaException> { rules.parseDate("2026-02-30") }
        assertFailsWith<InmobiliariaException> { rules.parseDate("mañana") }
    }

    @Test
    fun splitsPartyNames() {
        assertEquals(listOf("Ana Perez", "Luis"), rules.splitNames(" Ana Perez, Luis ,, ana perez"))
        assertTrue(rules.splitNames("").isEmpty())
        assertTrue(rules.splitNames(null).isEmpty())
    }

    @Test
    fun readsBooleans() {
        listOf("true", "TRUE", "si", "Sí", "yes", "1").forEach { assertTrue(rules.isTrue(it), it) }
        listOf("false", "no", "0", "", "quizas").forEach { assertFalse(rules.isTrue(it), it) }
        assertFalse(rules.isTrue(null))
    }

    @Test
    fun mergesLinksByLabelKeepingTheOthers() {
        val existing = listOf(LinkInput("Sitio web", "https://a.test/1"), LinkInput("Craigslist", "https://c.test/1"))
        val imported = listOf(LinkInput("sitio web", "https://a.test/2"), LinkInput("Google Drive", "https://d.test/1"))
        assertEquals(
            listOf(LinkInput("sitio web", "https://a.test/2"), LinkInput("Craigslist", "https://c.test/1"), LinkInput("Google Drive", "https://d.test/1")),
            rules.mergeLinks(existing, imported),
        )
    }

    @Test
    fun statusIsNotRevertedOnReimport() {
        assertFalse(rules.shouldChangeStatus(PropertyStatus.ForSale, PropertyStatus.ForSale, false))
        assertFalse(rules.shouldChangeStatus(PropertyStatus.Negotiation, PropertyStatus.ForSale, false))
        assertFalse(rules.shouldChangeStatus(PropertyStatus.Sold, PropertyStatus.ForSale, true))
        assertTrue(rules.shouldChangeStatus(PropertyStatus.Sold, PropertyStatus.ForSale, false))
        assertTrue(rules.shouldChangeStatus(PropertyStatus.Intake, PropertyStatus.ForSale, false))
    }

    @Test
    fun composesNotesWithWhatCouldNotBeLinked() {
        assertEquals("Nota\nAgente: Pedro", rules.composeNotes(" Nota ", listOf("Agente: Pedro")))
        assertEquals("Vendedor: Ana", rules.composeNotes("", listOf("Vendedor: Ana")))
        assertEquals("", rules.composeNotes("", emptyList()))
    }

    @Test
    fun importKindsDeclareTheDocumentedFields() {
        val kinds = InmobiliariaImport.kinds.associateBy { it.id }
        assertEquals(setOf("property", "deal"), kinds.keys)
        assertEquals(listOf("title"), kinds.getValue("property").fields.filter { it.required }.map { it.key })
        assertEquals(listOf("property"), kinds.getValue("deal").fields.filter { it.required }.map { it.key })
        val propertyKeys = kinds.getValue("property").fields.map { it.key }
        assertTrue(propertyKeys.containsAll(InmobiliariaImportRules.LinkFields.map { it.first }))
        val dealKeys = kinds.getValue("deal").fields.map { it.key }
        assertTrue(dealKeys.containsAll(InmobiliariaImportRules.PartyFields.map { it.first }))
        assertEquals(propertyKeys.size, propertyKeys.toSet().size)
        assertEquals(dealKeys.size, dealKeys.toSet().size)
    }

    private fun candidate(code: String, title: String, zone: String) = InmobiliariaImportRules.DedupeCandidate(UUID.randomUUID(), code, title, zone)

    private fun stage(name: String, outcome: DealOutcome, position: Int = 0) = DealStage(UUID.randomUUID(), name, position, outcome)

    @Test
    fun dedupesByCodeIgnoringCase() {
        val byCode = candidate("ERE-0123", "Casa", "Boquete")
        assertEquals(byCode, rules.pickDuplicate("ere-0123", "Otro titulo", "", byCode, emptyList()))
        assertNull(rules.pickDuplicate("ERE-9999", "Casa", "Boquete", null, listOf(candidate("P-1", "Casa", "Boquete"))))
    }

    @Test
    fun dedupesByTitleAndZoneOnlyWhenThereIsOneMatch() {
        val one = candidate("P-1", "Casa  Vista-Mar", "Boquete")
        val other = candidate("P-2", "Casa Vista Mar", "David")
        assertEquals(one, rules.pickDuplicate(null, "casa vista mar", "BOQUETE", null, listOf(one, other)))
        assertNull(rules.pickDuplicate(null, "Casa Vista Mar", "Boquete", null, listOf(one, candidate("P-3", "Casa vista mar", "boquete"))))
        assertNull(rules.pickDuplicate(null, "Casa Vista Mar", "Panama", null, listOf(one)))
        assertNull(rules.pickDuplicate(null, "  ", "", null, listOf(candidate("P-4", "", ""))))
    }

    @Test
    fun intakeNeverDemotesAnExistingProperty() {
        listOf(PropertyStatus.ForSale, PropertyStatus.Negotiation, PropertyStatus.Sold, PropertyStatus.Withdrawn).forEach {
            assertFalse(rules.shouldChangeStatus(it, PropertyStatus.Intake, false), it.name)
        }
        assertFalse(rules.shouldChangeStatus(PropertyStatus.Intake, PropertyStatus.Intake, false))
    }

    @Test
    fun unknownStatusFallsBackToTheDefault() {
        val unknown = rules.readStatus("COMING SOON", false)
        assertTrue(unknown.unknown)
        assertEquals(PropertyStatus.ForSale, unknown.status)
        assertEquals(PropertyStatus.Intake, rules.readStatus("COMING SOON", true).status)
        val known = rules.readStatus("SOLD", false)
        assertFalse(known.unknown)
        assertEquals(PropertyStatus.Sold, known.status)
    }

    @Test
    fun picksStagesByNameTruncatedTo60() {
        val long = "A".repeat(60)
        val stages = listOf(stage("Offer", DealOutcome.Open), stage(long, DealOutcome.Open), stage("Closed", DealOutcome.Won), stage("Cancelada", DealOutcome.Lost))
        assertEquals(stages[1], assertIs<InmobiliariaImportRules.StagePick.Use>(rules.pickStage(stages, long + "BBB")).stage)
        assertEquals(stages[0], assertIs<InmobiliariaImportRules.StagePick.Use>(rules.pickStage(stages, " offer ")).stage)
        val create = assertIs<InmobiliariaImportRules.StagePick.Create>(rules.pickStage(emptyList(), "B".repeat(80)))
        assertEquals(60, create.name.length)
    }

    @Test
    fun unknownOpenStageUsesTheFirstOpenOneWithAWarning() {
        val stages = listOf(stage("Offer", DealOutcome.Open), stage("Closed", DealOutcome.Won))
        val use = assertIs<InmobiliariaImportRules.StagePick.Use>(rules.pickStage(stages, "Due diligence"))
        assertEquals(stages[0], use.stage)
        assertTrue(use.warning.orEmpty().contains("Due diligence"))
        assertIs<InmobiliariaImportRules.StagePick.Create>(rules.pickStage(listOf(stages[1]), "Due diligence"))
    }

    @Test
    fun wonAndLostStagesReuseExistingOutcomesAndCreateOnlyIfMissing() {
        val stages = listOf(stage("Offer", DealOutcome.Open), stage("Vendida", DealOutcome.Won))
        val won = assertIs<InmobiliariaImportRules.StagePick.Use>(rules.pickStage(stages, "Closed"))
        assertEquals(stages[1], won.stage)
        assertNull(won.warning)
        val lost = assertIs<InmobiliariaImportRules.StagePick.Create>(rules.pickStage(stages, "Canceled"))
        assertEquals(DealOutcome.Lost, lost.outcome)
    }

    @Test
    fun plansDealsOnSoldOrWithdrawnProperties() {
        assertEquals(InmobiliariaImportRules.DealPlan.Normal, rules.planDeal(PropertyStatus.ForSale, DealOutcome.Won, false))
        assertEquals(InmobiliariaImportRules.DealPlan.RejectWon, rules.planDeal(PropertyStatus.Sold, DealOutcome.Won, true))
        assertEquals(InmobiliariaImportRules.DealPlan.WithoutPropertyEffects, rules.planDeal(PropertyStatus.Sold, DealOutcome.Won, false))
        assertEquals(InmobiliariaImportRules.DealPlan.WithoutPropertyEffects, rules.planDeal(PropertyStatus.Withdrawn, DealOutcome.Lost, false))
        assertEquals(InmobiliariaImportRules.DealPlan.WithoutPropertyEffects, rules.planDeal(PropertyStatus.Sold, DealOutcome.Open, true))
    }

    @Test
    fun closedOnPrefersRequestedThenSoldOnThenToday() {
        val today = LocalDate.of(2026, 11, 18)
        assertEquals(InmobiliariaImportRules.ClosedOn(LocalDate.of(2026, 1, 2), false), rules.closedOnFor(LocalDate.of(2026, 1, 2), LocalDate.of(2025, 5, 5), today))
        assertEquals(InmobiliariaImportRules.ClosedOn(LocalDate.of(2025, 5, 5), false), rules.closedOnFor(null, LocalDate.of(2025, 5, 5), today))
        assertEquals(InmobiliariaImportRules.ClosedOn(today, true), rules.closedOnFor(null, null, today))
    }

    @Test
    fun capsLinksAndValues() {
        val links = (1..25).map { LinkInput("L$it", "https://a.test/$it") }
        assertEquals(20, rules.capLinks(links).size)
        assertEquals("L1", rules.capLinks(links).first().label)
        assertEquals(5000, rules.clip("x".repeat(6000)).length)
        assertEquals("a", rules.clip("  a  "))
        assertEquals(500, InmobiliariaImportRules.MaxBatch)
    }
}
