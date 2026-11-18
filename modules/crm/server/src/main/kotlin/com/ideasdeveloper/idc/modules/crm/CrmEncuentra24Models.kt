package com.ideasdeveloper.idc.modules.crm

import java.net.URI
import java.time.Instant

/** Ajustes de Encuentra24 (la API key llega ya abierta). Los guarda la extension "encuentra24"; el CRM solo los muestra. */
data class CrmEncuentra24Settings(
    val enabled: Boolean,
    val apiKey: String?,
    val slug: String?,
    val lastSync: Instant?,
    val lastResult: String,
    val lastError: String,
) {
    val ready get() = enabled && !apiKey.isNullOrBlank() && !slug.isNullOrBlank()
}

/** Lo que dejo una importacion de Encuentra24. */
data class CrmEncuentra24SyncResult(val imported: Int, val existing: Int, val withoutContact: Int, val spam: Int, val failed: Int, val firstError: String = "") {
    override fun toString() = "$imported importados, $existing ya existentes, $withoutContact sin contacto, $spam spam, $failed con error" +
        if (firstError.isNotEmpty()) " (primer error: $firstError)" else ""
}

class CrmEncuentra24Exception(message: String) : Exception(message)

/** Un anuncio de Encuentra24 enlazado en un mensaje. */
data class CrmEncuentra24Ad(val id: String, val url: String)

/**
 * Lo del CRM que se queda aunque la extension no este instalada: el canal/fuente de los anuncios y el reconocimiento
 * del enlace de un anuncio (el origen "Encuentra24 (WhatsApp)" de un lead nuevo no depende de la extension).
 */
object CrmEncuentra24Ads {
    // Canal de los contactos importados y fuente de los anuncios enlazados a un lead.
    const val Source = "encuentra24"

    private val adLink = Regex("""https?://(?:[a-z0-9-]+\.)*encuentra24\.com/[^\s<>"']*""", RegexOption.IGNORE_CASE)
    private val adId = Regex("""/(\d{5,})(?:[/?#]|$)""")

    /** El anuncio de Encuentra24 enlazado en un mensaje (su boton de WhatsApp agrega el enlace), o null. */
    fun fromText(text: String): CrmEncuentra24Ad? {
        val url = adLink.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']') ?: return null
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return CrmEncuentra24Ad(adId.find("$path/")?.groupValues?.get(1).orEmpty(), url)
    }

    /** Los valores del formulario de Integraciones; la API key sale enmascarada. */
    fun formValues(settings: CrmEncuentra24Settings) = mapOf(
        "enabled" to settings.enabled.toString(),
        "apiKey" to CrmSecrets.mask(settings.apiKey),
        "slug" to settings.slug.orEmpty(),
    )

    // Lo que se ve sin la extension instalada: todo apagado.
    val Disabled = CrmEncuentra24Settings(enabled = false, apiKey = null, slug = null, lastSync = null, lastResult = "", lastError = "")
}
