package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiTone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Button fields (change 9.4) and the Cloudbeds check-in request (change 9.2). */
class CrmButtonsTest {
    private val confirmation = CrmTemplate(UUID.randomUUID(), "Confirmacion", "Reserva", "Texto")
    private val reserveRule = manual("Confirmar y reservar")
    private val checkInRule = manual("Check-in en Cloudbeds")
    private val welcomeRule = reserveRule.copy(id = UUID.randomUUID(), name = "Bienvenida", trigger = CrmTrigger(CrmTriggerType.LeadCreated))

    private fun manual(name: String) = CrmAutomation(
        UUID.randomUUID(), name, true, CrmTrigger(CrmTriggerType.Manual),
        actions = listOf(CrmAction(CrmActionKind.Notify)),
    )

    private val templates = listOf(confirmation)
    private val manuals = listOf(reserveRule, checkInRule)

    // ---- Configuration ----

    @Test
    fun `a button config reads a template or a list of automations`() {
        val template = Json.parseToJsonElement("""{"template":"${confirmation.id}"}""").jsonObject
        assertEquals(CrmButtonConfig(templateId = confirmation.id), CrmButtons.read(template))
        val automations = Json.parseToJsonElement("""{"automations":["${reserveRule.id}","${checkInRule.id}","${reserveRule.id}"]}""").jsonObject
        // Order is kept, repeats are dropped.
        assertEquals(CrmButtonConfig(automationIds = listOf(reserveRule.id, checkInRule.id)), CrmButtons.read(automations))
    }

    @Test
    fun `a config that does not read is an empty button`() {
        assertTrue(CrmButtons.read(JsonObject(emptyMap())).isEmpty)
        assertTrue(CrmButtons.read(Json.parseToJsonElement("""{"template":"no-es-un-id"}""").jsonObject).isEmpty)
        assertTrue(CrmButtons.read(Json.parseToJsonElement("""{"automations":"x"}""").jsonObject).isEmpty)
        assertEquals(listOf(reserveRule.id), CrmButtons.read(Json.parseToJsonElement("""{"automations":["basura","${reserveRule.id}"]}""").jsonObject).automationIds)
        // A hand-edited config with both uses the template.
        val both = Json.parseToJsonElement("""{"template":"${confirmation.id}","automations":["${reserveRule.id}"]}""").jsonObject
        assertEquals(CrmButtonConfig(templateId = confirmation.id), CrmButtons.read(both))
    }

    @Test
    fun `the config is written as V021 describes`() {
        assertEquals(JsonObject(mapOf("template" to JsonPrimitive(confirmation.id.toString()))), CrmButtons.toJson(CrmButtonConfig(templateId = confirmation.id)))
        val written = CrmButtons.toJson(CrmButtonConfig(automationIds = listOf(reserveRule.id, checkInRule.id)))
        assertEquals(JsonArray(listOf(JsonPrimitive(reserveRule.id.toString()), JsonPrimitive(checkInRule.id.toString()))), written["automations"])
        assertEquals(JsonObject(emptyMap()), CrmButtons.toJson(CrmButtonConfig()))
    }

    @Test
    fun `the field form picks a template or manual automations`() {
        val byTemplate = CrmButtons.parseForm(mapOf("buttonAction" to CrmButtons.ActionTemplate, "buttonTemplate" to "Confirmacion"), templates, manuals)
        assertEquals(CrmButtonConfig(templateId = confirmation.id), CrmButtons.read(byTemplate))
        val chosen = UiFieldValues.options(listOf("Check-in en Cloudbeds", "Confirmar y reservar"))
        val byAutomations = CrmButtons.parseForm(mapOf("buttonAction" to CrmButtons.ActionAutomations, "buttonAutomations" to chosen), templates, manuals)
        // The order of the choice is the order they run.
        assertEquals(listOf(checkInRule.id, reserveRule.id), CrmButtons.read(byAutomations).automationIds)
    }

    @Test
    fun `the field form refuses what does not exist`() {
        fun fails(values: Map<String, String>) = assertFailsWith<CrmValidationException> { CrmButtons.parseForm(values, templates, manuals) }.message.orEmpty()
        assertTrue(fails(emptyMap()).contains("Elige que hace"))
        assertTrue(fails(mapOf("buttonAction" to CrmButtons.ActionTemplate)).contains("plantilla"))
        assertTrue(fails(mapOf("buttonAction" to CrmButtons.ActionTemplate, "buttonTemplate" to "Otra")).contains("plantilla"))
        assertTrue(fails(mapOf("buttonAction" to CrmButtons.ActionAutomations)).contains("al menos una"))
        // Only manual automations: a rule with another trigger is not offered.
        assertTrue(fails(mapOf("buttonAction" to CrmButtons.ActionAutomations, "buttonAutomations" to UiFieldValues.options(listOf("Bienvenida")))).contains("Bienvenida"))
    }

    @Test
    fun `the field form shows what the button does`() {
        val template = CrmButtons.toJson(CrmButtonConfig(templateId = confirmation.id))
        assertEquals(mapOf("buttonAction" to CrmButtons.ActionTemplate, "buttonTemplate" to "Confirmacion"), CrmButtons.formValues(template, templates, manuals))
        val automations = CrmButtons.toJson(CrmButtonConfig(automationIds = listOf(checkInRule.id, reserveRule.id)))
        val values = CrmButtons.formValues(automations, templates, manuals)
        assertEquals(CrmButtons.ActionAutomations, values["buttonAction"])
        assertEquals(listOf("Check-in en Cloudbeds", "Confirmar y reservar"), UiFieldValues.options(values["buttonAutomations"].orEmpty()))
        assertEquals(emptyMap(), CrmButtons.formValues(JsonObject(emptyMap()), templates, manuals))
        assertEquals("envia Confirmacion", CrmButtons.describe(template, templates, manuals))
        assertEquals("ejecuta Check-in en Cloudbeds, Confirmar y reservar", CrmButtons.describe(automations, templates, manuals))
        assertEquals("sin accion", CrmButtons.describe(JsonObject(emptyMap()), templates, manuals))
    }

    @Test
    fun `pressing asks for confirmation with the label`() {
        val field = CrmField(UUID.randomUUID(), "cf_confirmar", "Enviar confirmacion", CrmFieldType.Button)
        assertEquals("¿Ejecutar Enviar confirmacion?", CrmButtons.confirmText(field))
    }

    @Test
    fun `the notice takes the worst result`() {
        assertEquals(UiTone.Success, CrmButtons.tone(listOf(CrmRunOutcome.Done, CrmRunOutcome.Done)))
        assertEquals(UiTone.Success, CrmButtons.tone(emptyList()))
        assertEquals(UiTone.Warning, CrmButtons.tone(listOf(CrmRunOutcome.Done, CrmRunOutcome.MissingData)))
        assertEquals(UiTone.Warning, CrmButtons.tone(listOf(CrmRunOutcome.Skipped)))
        assertEquals(UiTone.Danger, CrmButtons.tone(listOf(CrmRunOutcome.MissingData, CrmRunOutcome.Failed)))
    }

    // ---- Button fields are not form inputs ----

    private val button = CrmField(UUID.randomUUID(), "cf_confirmar", "Confirmar", CrmFieldType.Button, required = true, config = CrmButtons.toJson(CrmButtonConfig(templateId = confirmation.id)))
    private val note = CrmField(UUID.randomUUID(), "cf_nota", "Nota", CrmFieldType.Text)

    @Test
    fun `a button holds no value in the lead form`() {
        val stored = JsonObject(mapOf("cf_nota" to JsonPrimitive("hola"), "cf_confirmar" to JsonPrimitive("basura")))
        assertEquals(mapOf("cf_nota" to "hola"), CrmFields.formValues(listOf(note, button), stored))
        // Not required, not parsed, even when the form sends the key.
        val parsed = CrmFields.parseValues(mapOf("cf_nota" to "chau", "cf_confirmar" to "x"), listOf(note, button), stored)
        assertEquals(setOf("cf_nota"), parsed.keys)
        assertEquals(emptyMap(), CrmFields.parseValues(emptyMap(), listOf(note, button)))
        // It never shows up as a change of the lead.
        val after = CrmFields.merge(stored, mapOf("cf_nota" to JsonPrimitive("chau"), "cf_confirmar" to JsonPrimitive("otra")))
        assertEquals(listOf("Nota: hola -> chau"), CrmFields.changes(listOf(note, button), stored, after))
    }

    @Test
    fun `the definition of a button keeps its config and is never required`() {
        val values = mapOf(
            "label" to "Enviar confirmacion", "type" to "Boton", "required" to "true", "position" to "3",
            "buttonAction" to CrmButtons.ActionTemplate, "buttonTemplate" to "Confirmacion",
        )
        val input = CrmFields.parseDefinition(values, null, templates = templates, manualAutomations = manuals)
        assertEquals(CrmFieldType.Button, input.type)
        assertFalse(input.required)
        assertTrue(input.options.isEmpty())
        assertEquals(CrmButtonConfig(templateId = confirmation.id), CrmButtons.read(input.config))
        // Without an action the field is not created.
        assertFailsWith<CrmValidationException> { CrmFields.parseDefinition(values - "buttonAction", null, templates = templates, manualAutomations = manuals) }
        // The other types carry no config.
        assertEquals(JsonObject(emptyMap()), CrmFields.parseDefinition(mapOf("label" to "Nota", "type" to "Texto"), null).config)
        // The type is offered in the form by its label.
        assertEquals(CrmFieldType.Button, CrmFieldType.byLabel("Boton"))
        assertEquals(CrmFieldType.Button, CrmFieldType.byId("button"))
    }

    @Test
    fun `an existing button keeps its type when edited`() {
        val edited = CrmFields.parseDefinition(
            mapOf("label" to "Confirmar", "type" to "Texto", "buttonAction" to CrmButtons.ActionAutomations, "buttonAutomations" to UiFieldValues.options(listOf("Confirmar y reservar"))),
            editing = button, templates = templates, manualAutomations = manuals,
        )
        assertEquals(CrmFieldType.Button, edited.type)
        assertEquals(listOf(reserveRule.id), CrmButtons.read(edited.config).automationIds)
    }

    // ---- Cloudbeds check-in ----

    private fun settings(property: String?) = CrmCloudbedsSettings(true, "key", property, "cash", false, null, 1, 9, true, "")

    @Test
    fun `the check-in request marks the reservation as checked in`() {
        assertEquals(
            listOf("propertyID" to "123", "reservationID" to "R-77", "status" to "checked_in"),
            CrmCloudbeds.checkInForm(settings("123"), "R-77"),
        )
        // A key of one property needs no propertyID.
        assertEquals(listOf("reservationID" to "R-77", "status" to "checked_in"), CrmCloudbeds.checkInForm(settings(null), "R-77"))
    }

    @Test
    fun `only reservations waiting for the check-in can check in`() {
        fun reservation(status: String) = CrmReservation(UUID.randomUUID(), UUID.randomUUID(), "R-1", status, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), null, "Cuarto 1", "a@b.co", false)
        assertEquals(setOf("confirmed", "not_confirmed"), CrmAutomationRequirements.CheckInStatuses)
        listOf("confirmed", "not_confirmed").forEach { assertTrue(reservation(it).status in CrmAutomationRequirements.CheckInStatuses) }
        listOf("checked_in", "checked_out", "canceled", "no_show").forEach { assertFalse(reservation(it).status in CrmAutomationRequirements.CheckInStatuses) }
    }
}
