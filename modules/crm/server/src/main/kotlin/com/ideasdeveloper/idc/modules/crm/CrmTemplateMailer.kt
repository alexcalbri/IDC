package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.util.UUID

/**
 * ENTRY POINT to send or render a template for a lead (CRM 0.24.0). Keep these signatures stable: the automations use them.
 *
 *   CrmTemplateMailer.render(tenant, templateId, leadId, extra = emptyMap(), http = CrmJavaHttp): RenderedMail
 *       Fills the template with the lead's data ({campo:key}, {cliente:key} included) and, when the template has
 *       "translate" on, translates it to the guest's language first. Never sends anything.
 *       Throws CrmValidationException when the template or the lead no longer exists.
 *
 *   CrmTemplateMailer.send(tenant, templateId, leadId, requestedBy = null, extra = emptyMap(), http = CrmJavaHttp): SentMail
 *       render + enqueue to the lead's email in the outbox (CrmNotify.enqueue, kind "template") + message in the
 *       conversation + activity of the lead (also the translation note when it failed). Throws CrmValidationException
 *       when the lead has no valid email.
 *
 * RenderedMail(subject, html, text, language, translated, note):
 *   language   = language the mail goes in (the guest's when translated, else the template's base language)
 *   translated = true when the text was translated
 *   note       = "No se pudo traducir: <motivo>" when the translation failed (the original is used), else null
 */
data class RenderedMail(val subject: String, val html: String, val text: String, val language: String, val translated: Boolean, val note: String?) {
    fun toEmail() = CrmEmail(subject, html, text)
}

data class SentMail(val to: String, val mail: RenderedMail)

object CrmTemplateMailer {
    const val OutboxKind = "template"

    fun render(tenant: Connection, templateId: UUID, leadId: UUID, extra: Map<String, String> = emptyMap(), http: CrmHttp = CrmJavaHttp): RenderedMail {
        val template = CrmMail.templates(tenant).firstOrNull { it.id == templateId } ?: throw CrmValidationException("Esa plantilla ya no existe.")
        val lead = CrmRepository.lead(tenant, leadId) ?: throw CrmValidationException("Ese lead ya no existe.")
        return renderFor(tenant, template, lead, extra, http)
    }

    /** Same as [render] with the template and the lead already loaded. */
    fun renderFor(tenant: Connection, template: CrmTemplate, lead: CrmLead, extra: Map<String, String> = emptyMap(), http: CrmHttp = CrmJavaHttp): RenderedMail {
        val names = CrmNames(CrmRepository.users(tenant), CrmRepository.interests(tenant))
        val values = CrmInbox.templateValues(tenant, lead, names, CrmRepository.stages(tenant)) + dynamicValues(tenant, lead) + extra
        val settings = CrmTranslate.load(tenant)
        val guestLanguage = settings.languageField.takeIf { it.isNotEmpty() }?.let { guestValue(lead.customFields[it]) }
        return compose(template, values, guestLanguage, settings, CrmTranslate.translator(settings, http), CrmTranslate.cache(tenant, template.id))
    }

    fun send(
        tenant: Connection, templateId: UUID, leadId: UUID, requestedBy: CrmActor? = null,
        extra: Map<String, String> = emptyMap(), http: CrmHttp = CrmJavaHttp,
    ): SentMail {
        val template = CrmMail.templates(tenant).firstOrNull { it.id == templateId } ?: throw CrmValidationException("Esa plantilla ya no existe.")
        val lead = CrmRepository.lead(tenant, leadId) ?: throw CrmValidationException("Ese lead ya no existe.")
        if (!CrmContacts.isEmail(lead.email)) throw CrmValidationException("El lead no tiene un correo valido.")
        val mail = renderFor(tenant, template, lead, extra, http)
        val actor = requestedBy ?: CrmActor(null, "Automatizacion")
        CrmNotify.enqueue(tenant, OutboxKind, lead.email, mail.toEmail(), lead.id)
        CrmInbox.addMessage(tenant, lead.id, "out", "email", mail.subject, mail.text, null, "sent", "", actor.userId, actor.name)
        val language = if (mail.translated) " (traducida al ${mail.language})" else ""
        CrmRepository.addActivity(tenant, lead.id, "item", "Plantilla ${template.name} enviada a ${lead.email}$language.", actor)
        mail.note?.let { CrmRepository.addActivity(tenant, lead.id, "item", "${template.name}: $it", actor) }
        return SentMail(lead.email, mail)
    }

    /**
     * Fills [template] with [values] (translated first when the template asks for it and the guest's language is
     * known and differs from the base one). Pure: the database only comes in through [cache].
     */
    fun compose(
        template: CrmTemplate,
        values: Map<String, String>,
        guestLanguage: String?,
        settings: CrmTranslationSettings,
        translator: CrmTranslator?,
        cache: CrmTranslationCache?,
    ): RenderedMail {
        val target = CrmTranslate.resolveLanguage(guestLanguage, settings.languageMap)
        var subject = template.subject
        var body = template.body
        var translated = false
        var note: String? = null
        if (template.translate && target != null && !CrmTranslate.sameLanguage(target, template.baseLanguage)) {
            val hash = CrmTranslate.contentHash(template.subject, template.body, template.baseLanguage)
            val hit = cache?.get(target, hash)
            if (hit != null) {
                subject = hit.first
                body = hit.second
                translated = true
            } else if (translator == null) {
                note = "No se pudo traducir: configura el traductor en Integraciones > Traduccion."
            } else if (CrmTranslate.coolingDown(settings)) {
                // A translator that just failed is not called again for a while: the original goes out at once.
                note = "No se pudo traducir: el traductor fallo hace poco; se envio el original."
            } else {
                try {
                    val result = CrmTranslate.translateTemplate(translator, template.subject, template.body, template.baseLanguage, target)
                    subject = result.first
                    body = result.second
                    translated = true
                    CrmTranslate.markWorking(settings)
                    // A failed cache write must not stop the email.
                    runCatching { cache?.put(target, hash, subject, body) }
                } catch (failure: CrmTranslateException) {
                    CrmTranslate.markFailed(settings)
                    note = "No se pudo traducir: ${failure.message}"
                } catch (failure: Exception) {
                    CrmTranslate.markFailed(settings)
                    note = "No se pudo traducir: error inesperado"
                }
            }
        }
        val email = CrmMail.render(subject, body, values)
        return RenderedMail(email.subject, email.html, email.text, if (translated) target!! else template.baseLanguage, translated, note)
    }

    /** First value of a list; the text of any other value; "" when empty. */
    fun guestValue(value: JsonElement?): String = when (value) {
        null -> ""
        is JsonArray -> value.firstOrNull()?.let { CrmFields.display(it) }.orEmpty()
        else -> CrmFields.display(value)
    }

    /** Values of {campo:key} and {cliente:key} for a lead, shown as in the lead detail (lists with commas, Si / No). */
    fun dynamicValues(tenant: Connection, lead: CrmLead): Map<String, String> {
        val customer = tenant.prepareStatement("SELECT flexible_attributes::text FROM customers WHERE id = ?").use { query ->
            query.setObject(1, lead.customerId)
            query.executeQuery().use { rows -> if (rows.next()) CrmFields.parseStored(rows.getString(1)) else JsonObject(emptyMap()) }
        }
        return dynamicValues(lead.customFields, CrmFields.all(tenant), customer, CrmImportTargets.customerFields(tenant))
    }

    fun dynamicValues(leadFields: JsonObject, definitions: List<CrmField>, customerAttributes: JsonObject, customerDefinitions: List<CustomerField>): Map<String, String> = buildMap {
        // Files hold ids of attachments: not text.
        definitions.filter { it.type != CrmFieldType.Files && it.type != CrmFieldType.Button }.forEach { put("campo:${it.key}", CrmFields.display(leadFields[it.key])) }
        customerDefinitions.forEach { put("cliente:${it.key}", CrmFields.display(customerAttributes[it.key])) }
    }

    /** Lines "{variable} - description" for the editor: the fixed ones, then one per custom field and customer field. */
    fun variableHelp(fields: List<CrmField>, customerFields: List<CustomerField>): String =
        (CrmMail.placeholders.entries.map { (key, label) -> "{$key} - $label" } +
            fields.filter { it.type != CrmFieldType.Files && it.type != CrmFieldType.Button }.map { "{campo:${it.key}} - Campo del lead: ${it.label}" } +
            customerFields.map { "{cliente:${it.key}} - Campo del cliente: ${it.label}" }).joinToString("\n")
}
