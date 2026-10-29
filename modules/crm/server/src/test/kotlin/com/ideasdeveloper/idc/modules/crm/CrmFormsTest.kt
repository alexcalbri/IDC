package com.ideasdeveloper.idc.modules.crm

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CrmFormsTest {
    private val stages = listOf(
        CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0),
        CrmStage(UUID.randomUUID(), "Reservado", StageKind.Won, StageTone.Success, 1),
    )
    private val surf = CrmInterest(UUID.randomUUID(), "Surf", 0)
    private val ana = CrmUser(UUID.randomUUID(), "ana")
    private val base = mapOf("name" to " Megan Dravet ", "email" to "megan@example.com", "phone" to "+1 630 460 2072")

    private fun parse(values: Map<String, String>, interestsEnabled: Boolean = true) =
        CrmForms.parseLead(base + values, stages, listOf(surf), interestsEnabled, listOf(ana))

    @Test
    fun mapsDropdownNamesToIds() {
        val input = parse(
            mapOf(
                "stage" to "Reservado", "interest" to "Surf", "owner" to "ana",
                "targetStart" to "2026-12-25", "targetEnd" to "2027-01-03", "estimatedValue" to "1,250.50", "followUpOn" to "2026-11-02",
            ),
        )
        assertEquals("Megan Dravet", input.name)
        assertEquals(stages[1].id, input.stageId)
        assertEquals(surf.id, input.interestId)
        assertEquals(ana.id, input.ownerUserId)
        assertEquals(LocalDate.of(2027, 1, 3), input.targetEnd)
        assertEquals(BigDecimal("1250.50"), input.estimatedValue)
    }

    @Test
    fun defaultsToFirstStageWithoutInterestOrOwner() {
        val input = parse(mapOf("interest" to CrmForms.NoInterest, "owner" to CrmForms.Unassigned))
        assertEquals(stages[0].id, input.stageId)
        assertNull(input.interestId)
        assertNull(input.ownerUserId)
        // With interests off the interest is ignored.
        assertNull(parse(mapOf("interest" to "Surf"), interestsEnabled = false).interestId)
    }

    @Test
    fun rejectsInvalidLeads() {
        assertFailsWith<CrmValidationException> { parse(mapOf("name" to " ")) }
        assertFailsWith<CrmValidationException> { parse(mapOf("email" to "megan")) }
        assertFailsWith<CrmValidationException> { parse(mapOf("phone" to "")) }
        assertFailsWith<CrmValidationException> { parse(mapOf("interest" to "Yoga")) }
        assertFailsWith<CrmValidationException> { parse(mapOf("owner" to "luis")) }
        assertFailsWith<CrmValidationException> { parse(mapOf("followUpOn" to "02/11/2026")) }
        assertFailsWith<CrmValidationException> { parse(mapOf("targetStart" to "2026-12-25", "targetEnd" to "2026-12-20")) }
        assertFailsWith<CrmValidationException> { parse(mapOf("estimatedValue" to "-5")) }
        assertFailsWith<CrmValidationException> { parse(mapOf("estimatedValue" to "1.234")) }
    }

    @Test
    fun parsesStagesAndTemplates() {
        val stage = CrmForms.parseStage(mapOf("name" to " Frio ", "kind" to "Perdida", "tone" to "Gris", "position" to "4"))
        assertEquals(CrmStageInput("Frio", StageKind.Lost, StageTone.Neutral, 4), stage)
        assertFailsWith<CrmValidationException> { CrmForms.parseStage(mapOf("name" to "", "position" to "1")) }
        assertFailsWith<CrmValidationException> { CrmForms.parseStage(mapOf("name" to "X", "position" to "-1")) }
        CrmTemplates.all.forEach { template ->
            assertEquals(template.stages.size, template.stages.map { it.name }.toSet().size, "${template.id} repeats a stage")
            assert(template.stages.any { it.kind == StageKind.Won }) { "${template.id} has no won stage" }
        }
    }

    @Test
    fun followUpBadges() {
        val today = LocalDate.of(2026, 10, 28)
        assertEquals("danger", followUpState(today.minusDays(1), today)?.second)
        assertEquals("warning", followUpState(today, today)?.second)
        assertEquals("info", followUpState(today.plusDays(3), today)?.second)
        assertNull(followUpState(null, today))
    }
}
