package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmFieldsTest {
    private val colors = CrmField(UUID.randomUUID(), "cf_colores", "Colores", CrmFieldType.Dropdown, listOf("Rojo", "Azul", "Verde"), multiple = true)
    private val plan = CrmField(UUID.randomUUID(), "cf_plan", "Plan", CrmFieldType.Dropdown, listOf("Basico", "Pro"), required = true)
    private val budget = CrmField(UUID.randomUUID(), "cf_presupuesto", "Presupuesto", CrmFieldType.Number)
    private val signed = CrmField(UUID.randomUUID(), "cf_firmado", "Firmado", CrmFieldType.Boolean)
    private val contract = CrmField(UUID.randomUUID(), "cf_contrato", "Contrato", CrmFieldType.Files, required = true)

    @Test
    fun keysComeFromTheLabelWithoutAccentsAndNeverRepeat() {
        assertEquals("cf_fecha_de_firma", CrmFields.uniqueKey("Fecha de firma", emptySet()))
        assertEquals("cf_telefono_oficina", CrmFields.uniqueKey("Teléfono  (oficina)", emptySet()))
        assertEquals("cf_plan_2", CrmFields.uniqueKey("Plan", setOf("cf_plan")))
        assertEquals("cf_campo", CrmFields.uniqueKey("¿?", emptySet()))
    }

    @Test
    fun dropdownDefinitionsNeedOptionsAndKeepMultipleOnlyForDropdowns() {
        val input = CrmFields.parseDefinition(
            mapOf("label" to "Colores", "type" to "Lista desplegable", "options" to "Rojo\n\nAzul\nrojo\n Verde ", "multiple" to "true", "position" to "2"),
            editing = null,
        )
        assertEquals(listOf("Rojo", "Azul", "Verde"), input.options)
        assertTrue(input.multiple)
        assertEquals(2, input.position)

        assertFailsWith<CrmValidationException> { CrmFields.parseDefinition(mapOf("label" to "Colores", "type" to "Lista desplegable", "options" to ""), null) }
        val text = CrmFields.parseDefinition(mapOf("label" to "Nota", "type" to "Texto", "multiple" to "true", "options" to "a"), null)
        assertFalse(text.multiple)
        assertEquals(emptyList(), text.options)
    }

    @Test
    fun editingKeepsTheOriginalType() {
        val input = CrmFields.parseDefinition(mapOf("label" to "Plan anual", "type" to "Texto", "options" to "Basico\nPro\nPremium"), editing = plan)
        assertEquals(CrmFieldType.Dropdown, input.type)
        assertEquals(listOf("Basico", "Pro", "Premium"), input.options)
    }

    @Test
    fun valuesAreValidatedByType() {
        val values = mapOf(
            "cf_colores" to UiFieldValues.options(listOf("Rojo", "Verde")),
            "cf_plan" to "Pro",
            "cf_presupuesto" to "1500,50",
            "cf_firmado" to "true",
        )
        val parsed = CrmFields.parseValues(values, listOf(colors, plan, budget, signed))
        assertEquals(JsonArray(listOf(JsonPrimitive("Rojo"), JsonPrimitive("Verde"))), parsed["cf_colores"])
        assertEquals(JsonPrimitive("Pro"), parsed["cf_plan"])
        assertEquals(JsonPrimitive("1500.5"), parsed["cf_presupuesto"])
        assertEquals(JsonPrimitive(true), parsed["cf_firmado"])

        assertFailsWith<CrmValidationException> { CrmFields.parseValues(mapOf("cf_plan" to "Gratis"), listOf(plan)) }
        assertFailsWith<CrmValidationException> { CrmFields.parseValues(mapOf("cf_plan" to ""), listOf(plan)) }
        assertFailsWith<CrmValidationException> { CrmFields.parseValues(mapOf("cf_presupuesto" to "mucho"), listOf(budget)) }
        val single = plan.copy(required = false)
        assertFailsWith<CrmValidationException> { CrmFields.parseValues(mapOf("cf_plan" to UiFieldValues.options(listOf("Basico", "Pro"))), listOf(single)) }
    }

    @Test
    fun onlyTheSentKeysChangeAndEmptyClears() {
        val parsed = CrmFields.parseValues(mapOf("cf_presupuesto" to ""), listOf(colors, budget))
        assertEquals(setOf("cf_presupuesto"), parsed.keys)
        assertNull(parsed["cf_presupuesto"])

        val stored = JsonObject(mapOf("cf_presupuesto" to JsonPrimitive("10"), "cf_plan" to JsonPrimitive("Pro")))
        assertEquals(JsonObject(mapOf("cf_plan" to JsonPrimitive("Pro"))), CrmFields.merge(stored, parsed))
    }

    @Test
    fun anOptionRemovedFromTheListStaysValidWhereItWasChosen() {
        val stored = JsonObject(mapOf("cf_colores" to JsonArray(listOf(JsonPrimitive("Morado")))))
        val values = mapOf("cf_colores" to UiFieldValues.options(listOf("Morado", "Rojo")))
        assertEquals(2, (CrmFields.parseValues(values, listOf(colors), stored)["cf_colores"] as JsonArray).size)
        assertFailsWith<CrmValidationException> { CrmFields.parseValues(values, listOf(colors)) }
    }

    @Test
    fun filesFieldsKeepTheIdsOfTheirRefs() {
        val id = UUID.randomUUID()
        val values = mapOf(
            CrmFields.Documents to UiFieldValues.files(listOf(UiFileRef(id.toString(), "a.pdf", "Carpeta"), UiFileRef("no-es-uuid", "b.pdf"))),
            "cf_contrato" to UiFieldValues.files(listOf(UiFileRef(id.toString(), "c.pdf"))),
        )
        val files = CrmFields.parseFiles(values, listOf(contract))
        assertEquals(listOf(id), files[CrmFields.Documents])
        assertEquals(listOf(id), files["cf_contrato"])
        assertFailsWith<CrmValidationException> { CrmFields.parseFiles(mapOf("cf_contrato" to ""), listOf(contract)) }
        // Not sent: not touched.
        assertEquals(emptyMap(), CrmFields.parseFiles(emptyMap(), listOf(contract)))
    }

    @Test
    fun historyShowsEachChangedField() {
        val before = JsonObject(mapOf("cf_colores" to JsonArray(listOf(JsonPrimitive("Rojo")))))
        val after = JsonObject(mapOf("cf_colores" to JsonArray(listOf(JsonPrimitive("Rojo"), JsonPrimitive("Azul"))), "cf_firmado" to JsonPrimitive(true)))
        assertEquals(listOf("Colores: Rojo -> Rojo, Azul", "Firmado: (vacio) -> Si"), CrmFields.changes(listOf(colors, signed), before, after))
    }

    @Test
    fun attachmentNamesAndFoldersAreCleaned() {
        assertEquals("informe.pdf", CrmAttachments.cleanName("C:\\Users\\ana\\informe.pdf"))
        assertEquals("Contrato/Anexos", CrmAttachments.cleanPath("\\Contrato\\..\\./Anexos/"))
        assertEquals("+a.pdf, -b.png", CrmAttachments.describe("Documentos", CrmAttachments.Change(listOf("a.pdf"), listOf("b.png")))?.substringAfter(": "))
        assertNull(CrmAttachments.describe("Documentos", CrmAttachments.Change(emptyList(), emptyList())))
    }
}
