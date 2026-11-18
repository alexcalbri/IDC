package com.ideasdeveloper.idc.modules.crm

import java.time.Instant

/**
 * Modelos y constantes de la integracion Meta (WhatsApp, Messenger e Instagram) que el CRM necesita para sus pantallas,
 * acciones y canales. La logica (Graph API, webhooks, tablas ext_meta__*) vive en la extension "meta" (extensions/meta).
 */

/** Direcciones publicas de la extension Meta: las sirve la extension; el CRM solo las conoce para mostrarlas en Integraciones. */
object MetaPaths {
    const val ExtensionPath = "/modules/crm/extensions/meta"
    const val WebhookPath = "$ExtensionPath/webhook"
    const val DeauthorizePath = "$ExtensionPath/deauthorize"
    const val DataDeletionPath = "$ExtensionPath/data-deletion"
    const val DeletionStatusPath = "$ExtensionPath/deletion"
    const val ConnectPath = "$ExtensionPath/connect"

    /** Webhook de una empresa que usa su propia app de Meta. */
    fun companyWebhookPath(company: String) = "$ExtensionPath/$company/webhook"
}

data class CrmMetaSettings(
    val enabled: Boolean,
    val appSecret: String?,
    val verifyToken: String?,
    val systemToken: String?,
    val pageId: String?,
    val pageName: String?,
    val pageToken: String?,
    val instagramAccountId: String?,
    val wabaId: String?,
    val phoneNumberId: String?,
    val recordEchoes: Boolean,
    // Messenger and Instagram reply with the page token read from the system token; off, each uses its own token.
    val sharedTokens: Boolean = true,
    val messengerToken: String? = null,
    val instagramToken: String? = null,
    val instagramUsername: String? = null,
    // "own": the company's own Meta app; "server": the server owner's app, connected with Facebook login.
    val connectionMode: String = OwnMode,
    // Server mode: token of the Facebook login (to choose among several pages) and who connected.
    val oauthToken: String? = null,
    val metaUserId: String? = null,
) {
    val serverMode get() = connectionMode == ServerMode

    val whatsAppReady get() = enabled && !systemToken.isNullOrBlank() && !phoneNumberId.isNullOrBlank()

    /** Token that replies on "messenger" or "instagram", or null when there is none. */
    fun channelToken(platform: String): String? =
        (if (sharedTokens) pageToken else if (platform == "instagram") instagramToken else messengerToken)?.takeIf { it.isNotBlank() }

    // Messenger and Instagram reply through the page (Facebook login): the token is all they need.
    fun ready(platform: String) = enabled && channelToken(platform) != null
}

const val OwnMode = "own"
const val ServerMode = "server"

/** Meta app of the server owner (stored in the server workspace). */
data class CrmMetaServerApp(
    val enabled: Boolean = false,
    val appId: String? = null,
    val appSecret: String? = null,
    val verifyToken: String? = null,
    val pagesConfigId: String? = null,
    val whatsAppConfigId: String? = null,
) {
    // Companies can connect when the app is on and has its id, secret and at least one login configuration.
    val ready get() = enabled && appId != null && !appSecret.isNullOrBlank() && (pagesConfigId != null || whatsAppConfigId != null)
}

/** Incoming events of the Meta webhook, already reduced to what the CRM stores. */
sealed interface MetaEvent {
    data class WhatsAppMessage(val phoneNumberId: String, val from: String, val name: String, val id: String, val text: String, val at: Instant?) : MetaEvent
    // Sent by the team from the WhatsApp Business app (coexistence).
    data class WhatsAppEcho(val phoneNumberId: String, val to: String, val id: String, val text: String, val at: Instant?) : MetaEvent
    // Messenger or Instagram direct message; platform is "messenger" or "instagram".
    data class PageMessage(val platform: String, val senderId: String, val id: String, val text: String, val at: Instant?) : MetaEvent
}

class CrmMetaException(message: String) : Exception(message)

/** Una linea del registro de eventos de Meta. */
data class MetaLogEntry(val event: String, val detail: String, val at: Instant)

data class WhatsAppNumber(val id: String, val number: String, val name: String, val status: String, val platform: String = "")

/** What saving and connecting Meta did: the settings as saved, and one line per step. */
data class MetaSetup(val settings: CrmMetaSettings, val done: List<String>, val problems: List<String>, val numbers: List<WhatsAppNumber>) {
    val notice get() = (done + problems).joinToString(". ").let { if (it.isEmpty()) "" else "$it." }
}

data class MetaNameFill(val reviewed: Int, val filled: Int, val missing: Int) {
    override fun toString() = "$reviewed contactos sin nombre revisados, $filled completados, $missing sin respuesta de Meta"
}
