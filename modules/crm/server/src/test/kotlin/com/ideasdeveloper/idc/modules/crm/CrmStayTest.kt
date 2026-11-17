package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmStayTest {
    private val arrival = CrmField(UUID.randomUUID(), "fecha_ingreso", "Fecha de ingreso", CrmFieldType.Date)
    private val departure = CrmField(UUID.randomUUID(), "fecha_salida", "Fecha de salida", CrmFieldType.Date)
    private val note = CrmField(UUID.randomUUID(), "cf_nota", "Nota", CrmFieldType.Text)
    private val fields = listOf(arrival, departure, note)
    private val mapping = CrmStayMapping("fecha_ingreso", "fecha_salida")

    private val stages = listOf(CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0))
    private val base = mapOf("name" to "Megan", "email" to "megan@example.com")

    private fun input(custom: Map<String, kotlinx.serialization.json.JsonElement?> = emptyMap()) = CrmLeadInput(
        "Megan", "megan@example.com", "", stages[0].id, null, emptyList(), "", "", "", "", LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2), null, null, custom,
    )

    private fun json(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun copiesTheMappedFieldsToTheInternalDates() {
        val saved = CrmStay.withDates(input(json("fecha_ingreso" to "2026-12-25", "fecha_salida" to "2027-01-03")), mapping, fields, JsonObject(emptyMap()))
        assertEquals(LocalDate.of(2026, 12, 25), saved.targetStart)
        assertEquals(LocalDate.of(2027, 1, 3), saved.targetEnd)
    }

    @Test
    fun withoutMappingTheInternalDatesAreEmpty() {
        val sent = input(json("fecha_ingreso" to "2026-12-25"))
        val saved = CrmStay.withDates(sent, CrmStayMapping.None, fields, JsonObject(emptyMap()))
        assertNull(saved.targetStart)
        assertNull(saved.targetEnd)
        // A mapping that points to a deleted (or not date) field counts as none.
        val stale = CrmStay.withDates(sent, CrmStayMapping("cf_nota", "fecha_borrada"), fields, JsonObject(emptyMap()))
        assertNull(stale.targetStart)
        assertNull(stale.targetEnd)
    }

    @Test
    fun keepsTheStoredValuesThatTheFormDoesNotSend() {
        // Editing only the end: the start comes from what the lead already has.
        val saved = CrmStay.withDates(input(json("fecha_salida" to "2027-01-03")), mapping, fields, json("fecha_ingreso" to "2026-12-25"))
        assertEquals(LocalDate.of(2026, 12, 25), saved.targetStart)
        assertEquals(LocalDate.of(2027, 1, 3), saved.targetEnd)
        // Clearing a field (null) clears its copy.
        val cleared = CrmStay.withDates(input(mapOf("fecha_salida" to null)), mapping, fields, json("fecha_ingreso" to "2026-12-25", "fecha_salida" to "2027-01-03"))
        assertNull(cleared.targetEnd)
    }

    @Test
    fun theEndCannotBeBeforeTheStart() {
        val failure = assertFailsWith<CrmValidationException> {
            CrmStay.withDates(input(json("fecha_ingreso" to "2026-12-25", "fecha_salida" to "2026-12-20")), mapping, fields, JsonObject(emptyMap()))
        }
        assertTrue("Fecha de salida" in failure.message.orEmpty() && "Fecha de ingreso" in failure.message.orEmpty())
        // The same day is fine.
        CrmStay.withDates(input(json("fecha_ingreso" to "2026-12-25", "fecha_salida" to "2026-12-25")), mapping, fields, JsonObject(emptyMap()))
    }

    @Test
    fun theSameFieldCannotBeBothDates() {
        assertFailsWith<CrmValidationException> {
            CrmStay.parseSelection(mapOf("start" to "Fecha de ingreso", "end" to "Fecha de ingreso"), fields)
        }
        val chosen = CrmStay.parseSelection(mapOf("start" to "Fecha de ingreso", "end" to "Sin elegir"), fields)
        assertEquals(CrmStayMapping("fecha_ingreso", ""), chosen)
        // Only date fields can be chosen.
        assertFailsWith<CrmValidationException> { CrmStay.parseSelection(mapOf("start" to "Nota", "end" to ""), fields) }
        // A saved mapping that repeats a key keeps only the start.
        assertEquals(CrmStayMapping("fecha_ingreso", ""), CrmStay.effective(CrmStayMapping("fecha_ingreso", "fecha_ingreso"), fields))
    }

    @Test
    fun agentsAliasesWriteInTheMappedFields() {
        val moved = CrmStay.withAliases(mapOf("name" to "Ana", "targetStart" to "2026-12-25", "targetEnd" to "2027-01-03"), mapping)
        assertEquals("2026-12-25", moved["fecha_ingreso"])
        assertEquals("2027-01-03", moved["fecha_salida"])
        assertNull(moved["targetStart"])
        // Through the form parser the lead gets the custom values and the internal copy.
        val parsed = CrmForms.parseLead(base + mapOf("targetStart" to "2026-12-25", "targetEnd" to "2027-01-03"), stages, emptyList(), false, emptyList(), fields, stay = mapping)
        assertEquals(JsonPrimitive("2026-12-25"), parsed.custom["fecha_ingreso"])
        assertEquals(LocalDate.of(2027, 1, 3), parsed.targetEnd)
        // Empty aliases without mapping are ignored; a typed date without mapping is refused.
        assertEquals(mapOf("name" to "Ana"), CrmStay.withAliases(mapOf("name" to "Ana", "targetStart" to ""), CrmStayMapping.None))
        assertFailsWith<CrmValidationException> { CrmStay.withAliases(mapOf("targetStart" to "2026-12-25"), CrmStayMapping.None) }
        // Aliases are checked against the end too.
        assertFailsWith<CrmValidationException> {
            CrmForms.parseLead(base + mapOf("targetStart" to "2026-12-25", "targetEnd" to "2026-12-24"), stages, emptyList(), false, emptyList(), fields, stay = mapping)
        }
    }

    @Test
    fun importWritesTheDatesInTheMappedFields() {
        val (changes, warning) = CrmStay.importChanges(mapping, JsonObject(emptyMap()), LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 3))
        assertEquals(JsonPrimitive("2026-12-25"), changes["fecha_ingreso"])
        assertEquals(JsonPrimitive("2027-01-03"), changes["fecha_salida"])
        assertNull(warning)
        // Without mapping the columns are ignored and the report says so.
        val (none, ignored) = CrmStay.importChanges(CrmStayMapping.None, JsonObject(emptyMap()), LocalDate.of(2026, 12, 25), null)
        assertTrue(none.isEmpty())
        assertNotNull(ignored)
        assertTrue("Fechas de estadia" in ignored)
        // Nothing to import, nothing to say.
        assertNull(CrmStay.importChanges(CrmStayMapping.None, JsonObject(emptyMap()), null, null).second)
        // An end before the lead's stored start is dropped and reported.
        val (dropped, why) = CrmStay.importChanges(mapping, json("fecha_ingreso" to "2026-12-25"), null, LocalDate.of(2026, 12, 20))
        assertTrue(dropped.isEmpty())
        assertNotNull(why)
    }

    @Test
    fun cardsShowTheMappedDatesWithTheirLabels() {
        val lead = CrmLead(
            UUID.randomUUID(), UUID.randomUUID(), "Ana", "", "", stages[0].id, null, emptyList(), "", "", "", "", null, null, null, null,
            java.time.Instant.now(), null, java.time.Instant.now(), customFields = json("fecha_ingreso" to "2026-12-25", "fecha_salida" to "2027-01-03", "cf_nota" to "x"),
        )
        assertEquals(listOf("Fecha de ingreso: 2026-12-25", "Fecha de salida: 2027-01-03"), CrmStay.cardLines(lead, mapping, fields))
        assertEquals(emptyList(), CrmStay.cardLines(lead, CrmStayMapping.None, fields))
        assertEquals("Ingreso: Fecha de ingreso. Salida: Fecha de salida.", CrmStay.summary(mapping, fields))
    }

    @Test
    fun theFieldOfTheFormWinsOverTheAlias() {
        val moved = CrmStay.withAliases(mapOf("fecha_ingreso" to "2026-12-01", "targetStart" to "2026-12-25"), mapping)
        assertEquals("2026-12-01", moved["fecha_ingreso"])
        assertNull(moved["targetStart"])
    }

    @Test
    fun aStoredInvertedPairDoesNotBlockOtherEdits() {
        val inverted = json("fecha_ingreso" to "2026-12-25", "fecha_salida" to "2026-12-20")
        // The form sends the same values again (nothing changed on the dates): saved, the end is dropped from the copy.
        val parsed = CrmForms.parseLead(
            base + mapOf("fecha_ingreso" to "2026-12-25", "fecha_salida" to "2026-12-20", "source" to "Web"),
            stages, emptyList(), false, emptyList(), fields, inverted, mapping,
        )
        assertEquals(LocalDate.of(2026, 12, 25), parsed.targetStart)
        assertNull(parsed.targetEnd)
        // Changing one of the dates validates the pair again.
        assertFailsWith<CrmValidationException> {
            CrmForms.parseLead(base + mapOf("fecha_ingreso" to "2026-12-26", "fecha_salida" to "2026-12-20"), stages, emptyList(), false, emptyList(), fields, inverted, mapping)
        }
        // Fixing it is allowed.
        val fixed = CrmForms.parseLead(base + mapOf("fecha_ingreso" to "2026-12-25", "fecha_salida" to "2026-12-30"), stages, emptyList(), false, emptyList(), fields, inverted, mapping)
        assertEquals(LocalDate.of(2026, 12, 30), fixed.targetEnd)
    }

    @Test
    fun lostDatesAreReportedInNotices() {
        assertEquals("", CrmStay.lostNote(0))
        assertTrue("3 lead(s)" in CrmStay.lostNote(3))
        assertTrue(CrmStay.isMapped(mapping, "fecha_salida"))
        assertEquals(false, CrmStay.isMapped(mapping, "cf_nota"))
        assertEquals(false, CrmStay.isMapped(CrmStayMapping.None, ""))
    }
}
