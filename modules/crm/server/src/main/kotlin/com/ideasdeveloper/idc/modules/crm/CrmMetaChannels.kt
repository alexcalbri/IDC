package com.ideasdeveloper.idc.modules.crm

/**
 * Canales de respuesta de Meta (WhatsApp, Messenger e Instagram). Quedan en el CRM porque CrmChannels los lista; el envio y los
 * ajustes los hace la extension "meta" por [CrmHost]. Sin la extension activa en la empresa, ningun canal esta disponible.
 */
object CrmWhatsAppChannel : CrmChannel {
    override val id = "whatsapp"
    override val label = "WhatsApp"

    override fun unavailable(context: CrmChannelContext, lead: CrmLead): String? = when {
        CrmExtensions.inactive(context.tenant, CrmExtensionIds.Meta) != null -> CrmExtensions.inactiveMessage(CrmExtensionIds.Meta)
        !CrmHost.metaSettings(context.tenant).whatsAppReady -> "Configura WhatsApp en Integraciones."
        CrmContacts.normalizePhone(lead.phone).isEmpty() -> "El lead no tiene un telefono valido."
        else -> null
    }

    override fun send(context: CrmChannelContext, lead: CrmLead, email: CrmEmail): String? = try {
        CrmExtensions.inactive(context.tenant, CrmExtensionIds.Meta)?.let { throw CrmMetaException(it) }
        CrmHost.metaSendWhatsApp(CrmHost.metaSettings(context.tenant), CrmContacts.normalizePhone(lead.phone).removePrefix("+"), email.text)
    } catch (failure: CrmMetaException) {
        CrmHost.metaLog(context.tenant, "wa_envio_error", failure.message.orEmpty())
        throw CrmChannelException(failure.message.orEmpty())
    }
}

class CrmPageChannel(override val id: String, override val label: String) : CrmChannel {
    override fun unavailable(context: CrmChannelContext, lead: CrmLead): String? {
        val settings = CrmHost.metaSettings(context.tenant)
        return when {
            CrmExtensions.inactive(context.tenant, CrmExtensionIds.Meta) != null -> CrmExtensions.inactiveMessage(CrmExtensionIds.Meta)
            !settings.ready(id) -> if (settings.sharedTokens) "Conecta la pagina de Facebook en Integraciones." else "Configura el token de $label en Integraciones."
            CrmInbox.identity(context.tenant, lead.customerId, id) == null -> "El lead no ha escrito por $label."
            else -> null
        }
    }

    override fun send(context: CrmChannelContext, lead: CrmLead, email: CrmEmail): String? {
        val recipient = CrmInbox.identity(context.tenant, lead.customerId, id) ?: throw CrmChannelException("El lead no ha escrito por $label.")
        return try {
            CrmExtensions.inactive(context.tenant, CrmExtensionIds.Meta)?.let { throw CrmMetaException(it) }
            CrmHost.metaSendPage(CrmHost.metaSettings(context.tenant), recipient, email.text, id).also { CrmHost.metaLog(context.tenant, "enviado", "$label $recipient") }
        } catch (failure: CrmMetaException) {
            CrmHost.metaLog(context.tenant, "envio_error", "$label: ${failure.message}")
            throw CrmChannelException(failure.message.orEmpty())
        }
    }
}
