package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiTone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.sql.Connection
import java.util.UUID

/** What a button field does: send one template, or run manual automations in order. */
data class CrmButtonConfig(val templateId: UUID? = null, val automationIds: List<UUID> = emptyList()) {
    val isEmpty get() = templateId == null && automationIds.isEmpty()
}

/** What pressing a button did: one line per step and the tone of the notice (the worst of its steps). */
data class CrmButtonResult(val lines: List<String>, val tone: String) {
    val text get() = lines.joinToString(" ")
}

/** Button fields (type "button"): their configuration and what pressing them does. */
object CrmButtons {
    const val ActionTemplate = "Enviar una plantilla"
    const val ActionAutomations = "Ejecutar automatizaciones"

    fun confirmText(field: CrmField) = "¿Ejecutar ${field.label}?"

    // ---- Configuration (crm_field_definitions.config) ----

    /** Reads the config of a button; anything that does not read is ignored. */
    fun read(config: JsonObject): CrmButtonConfig {
        fun uuid(value: String?) = value?.trim()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val template = uuid((config["template"] as? JsonPrimitive)?.contentOrNull)
        val automations = (config["automations"] as? JsonArray).orEmpty().mapNotNull { uuid((it as? JsonPrimitive)?.contentOrNull) }.distinct()
        // A template wins when a hand-edited config has both.
        return if (template != null) CrmButtonConfig(templateId = template) else CrmButtonConfig(automationIds = automations)
    }

    fun toJson(config: CrmButtonConfig): JsonObject = buildJsonObject {
        when {
            config.templateId != null -> put("template", config.templateId.toString())
            config.automationIds.isNotEmpty() -> put("automations", JsonArray(config.automationIds.map { JsonPrimitive(it.toString()) }))
            else -> Unit
        }
    }

    /** Reads the field form of Configuracion (type Boton): the template, or the manual automations in the order chosen. Throws in Spanish. */
    fun parseForm(values: Map<String, String>, templates: List<CrmTemplate>, manualAutomations: List<CrmAutomation>): JsonObject =
        when (values["buttonAction"].orEmpty().trim()) {
            ActionTemplate -> {
                val name = values["buttonTemplate"].orEmpty().trim()
                val template = templates.firstOrNull { it.name == name } ?: throw CrmValidationException("Elige la plantilla del boton (se crean en Plantillas).")
                toJson(CrmButtonConfig(templateId = template.id))
            }
            ActionAutomations -> {
                val names = UiFieldValues.options(values["buttonAutomations"].orEmpty())
                if (names.isEmpty()) throw CrmValidationException("Elige al menos una automatizacion manual para el boton.")
                val ids = names.map { name ->
                    manualAutomations.firstOrNull { it.name == name }?.id ?: throw CrmValidationException("La automatizacion $name no existe o no es manual.")
                }
                toJson(CrmButtonConfig(automationIds = ids.distinct()))
            }
            else -> throw CrmValidationException("Elige que hace el boton: $ActionTemplate o ${ActionAutomations.lowercase()}.")
        }

    /** Values of the field form for an existing button. */
    fun formValues(config: JsonObject, templates: List<CrmTemplate>, manualAutomations: List<CrmAutomation>): Map<String, String> {
        val read = read(config)
        return when {
            read.templateId != null -> mapOf("buttonAction" to ActionTemplate, "buttonTemplate" to templates.firstOrNull { it.id == read.templateId }?.name.orEmpty())
            read.automationIds.isNotEmpty() -> mapOf(
                "buttonAction" to ActionAutomations,
                "buttonAutomations" to UiFieldValues.options(read.automationIds.mapNotNull { id -> manualAutomations.firstOrNull { it.id == id }?.name }),
            )
            else -> emptyMap()
        }
    }

    /** One line for the list of fields: what the button does. */
    fun describe(config: JsonObject, templates: List<CrmTemplate>, automations: List<CrmAutomation>): String {
        val read = read(config)
        return when {
            read.templateId != null -> "envia ${templates.firstOrNull { it.id == read.templateId }?.name ?: "una plantilla eliminada"}"
            read.automationIds.isNotEmpty() -> "ejecuta ${read.automationIds.joinToString(", ") { id -> automations.firstOrNull { it.id == id }?.name ?: "una automatizacion eliminada" }}"
            else -> "sin accion"
        }
    }

    // ---- Pressing ----

    /** The tone of a notice for the statuses of the steps (the worst wins). */
    fun tone(statuses: List<String>): String = when {
        CrmRunOutcome.Failed in statuses -> UiTone.Danger
        CrmRunOutcome.MissingData in statuses || CrmRunOutcome.Skipped in statuses -> UiTone.Warning
        else -> UiTone.Success
    }

    /**
     * Presses [field] on [lead] for [actor] (who must already be allowed to edit the lead). A template is sent to the lead's email;
     * automations run in order with the same engine and stop at the first one that does not finish. The result lists each step
     * or the data that is missing.
     */
    fun press(
        tenant: Connection, lead: CrmLead, field: CrmField, actor: CrmActor,
        notifier: ModuleNotifier? = CrmLeadEvents.access?.notificationsFor(CrmModule.id), http: CrmHttp = CrmJavaHttp,
    ): CrmButtonResult {
        if (lead.archivedAt != null) return CrmButtonResult(listOf("El lead esta archivado: restauralo antes de usar sus botones."), UiTone.Warning)
        val config = read(field.config)
        return when {
            config.templateId != null -> sendTemplate(tenant, lead, config.templateId, actor, http)
            config.automationIds.isNotEmpty() -> runAutomations(tenant, lead, config.automationIds, actor, notifier, http)
            else -> CrmButtonResult(listOf("El boton ${field.label} no tiene accion: elige una en Configuracion > Campos personalizados."), UiTone.Warning)
        }
    }

    private fun sendTemplate(tenant: Connection, lead: CrmLead, templateId: UUID, actor: CrmActor, http: CrmHttp): CrmButtonResult {
        val template = CrmMail.templates(tenant).firstOrNull { it.id == templateId }
            ?: return CrmButtonResult(listOf("Esa plantilla ya no existe."), UiTone.Danger)
        val missing = CrmAutomationRequirements.missing(CrmAction(CrmActionKind.SendTemplate, templateId = templateId), CrmLeadFacts(email = lead.email))
        if (missing.isNotEmpty()) {
            return CrmButtonResult(listOf("No se envio ${template.name}: falta ${CrmAutomationRequirements.sentence(missing)}."), UiTone.Warning)
        }
        return try {
            val sent = CrmTemplateMailer.send(tenant, templateId, lead.id, actor, http = http)
            val translated = if (sent.mail.translated) " (traducida al ${sent.mail.language})" else ""
            CrmButtonResult(listOfNotNull("Plantilla ${template.name} enviada a ${sent.to}$translated.", sent.mail.note), if (sent.mail.note == null) UiTone.Success else UiTone.Warning)
        } catch (failure: CrmValidationException) {
            CrmButtonResult(listOf(failure.message.orEmpty()), UiTone.Danger)
        }
    }

    private fun runAutomations(
        tenant: Connection, lead: CrmLead, ids: List<UUID>, actor: CrmActor, notifier: ModuleNotifier?, http: CrmHttp,
    ): CrmButtonResult {
        val all = CrmAutomationStore.all(tenant)
        // One key for the whole press: every automation of the button is its own run, and pressing again is a new press.
        val key = CrmAutomationKeys.button(UUID.randomUUID())
        val lines = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        for (id in ids) {
            val automation = all.firstOrNull { it.id == id && it.trigger.type == CrmTriggerType.Manual }
            if (automation == null) {
                lines += "Una automatizacion del boton ya no existe o dejo de ser manual."
                statuses += CrmRunOutcome.Skipped
                break
            }
            if (!automation.enabled) {
                lines += "${automation.name} esta desactivada: activala en Configuracion > Automatizaciones."
                statuses += CrmRunOutcome.Skipped
                break
            }
            // The previous automation may have changed the lead.
            val current = CrmRepository.lead(tenant, lead.id)
            if (current == null) {
                lines += "El lead ya no existe."
                statuses += CrmRunOutcome.Failed
                break
            }
            val outcome = CrmAutomationEngine.run(tenant, automation, current, key, actor, notifier, http)
            lines += "${automation.name}: ${outcome.detail}"
            statuses += outcome.status
            if (outcome.status != CrmRunOutcome.Done) break
        }
        return CrmButtonResult(lines, tone(statuses))
    }
}
