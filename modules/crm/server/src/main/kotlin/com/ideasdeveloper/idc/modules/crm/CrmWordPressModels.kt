package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Datos fijos del plugin IDC Connector y de la extension de WordPress que el CRM necesita para sus pantallas. */
object WordPressPluginInfo {
    /** Fuente de los items del lead que vienen del sitio (crm_lead_items.source). */
    const val Source = "wordpress"

    /** Version del plugin IDC Connector que arma la extension (debe coincidir con la cabecera del PHP). */
    const val Version = "0.3.0"

    /** Descarga del plugin: la sirve la extension de WordPress. */
    const val Path = "/modules/crm/extensions/wordpress/idc-connector.zip"
}

data class CrmWordPressSettings(
    val enabled: Boolean,
    val siteUrl: String?,
    val secret: String?,
    val siteName: String,
    val lastEventAt: Instant?,
    val lastError: String,
) {
    val ready get() = enabled && !siteUrl.isNullOrBlank() && !secret.isNullOrBlank()
}

// A property of the website as the IDC Connector plugin sends it (summary fields).
data class WpListing(
    val id: Long,
    val title: String,
    val url: String,
    val price: String,
    val priceText: String,
    val propertyTypes: List<String>,
    val statuses: List<String>,
    val beds: String,
    val baths: String,
    val city: String,
    val sqft: String,
    val mls: String,
    val agentId: Long?,
    val agentName: String,
    val agentEmail: String,
) {
    // One line for lists: type, status, beds, baths, size, city and MLS.
    val summary: String
        get() = listOfNotNull(
            propertyTypes.joinToString().ifEmpty { null },
            statuses.joinToString().ifEmpty { null },
            beds.ifEmpty { null }?.let { "$it hab." },
            baths.ifEmpty { null }?.let { "$it banos" },
            sqft.ifEmpty { null }?.let { "$it m2" },
            city.ifEmpty { null },
            mls.ifEmpty { null }?.let { "MLS $it" },
        ).joinToString(" - ")

    val priceValue: BigDecimal? get() = price.filter { it.isDigit() || it == '.' }.toBigDecimalOrNull()

    companion object {
        private fun JsonElement?.obj() = this as? JsonObject
        private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
        private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        /** La propiedad tal como la envia el plugin, o null si no es un objeto con id. */
        fun parse(element: JsonElement?): WpListing? {
            val item = element.obj() ?: return null
            val id = item["id"].str().toLongOrNull() ?: return null
            val taxonomies = item["taxonomies"].obj()
            fun terms(name: String) = taxonomies?.get(name).arr().mapNotNull { it.str().ifEmpty { null } }
            val agent = item["agent"].obj()
            val price = item["price"].str()
            val priceText = if (price.isEmpty()) "" else listOf(item["price_prefix"].str(), price, item["price_postfix"].str()).filter { it.isNotEmpty() }.joinToString(" ")
            return WpListing(
                id = id,
                title = item["title"].str().ifEmpty { "Propiedad $id" },
                url = item["url"].str(),
                price = price,
                priceText = priceText,
                propertyTypes = terms("property_type"),
                statuses = terms("ct_status"),
                beds = terms("beds").firstOrNull().orEmpty(),
                baths = terms("baths").firstOrNull().orEmpty(),
                city = terms("city").firstOrNull().orEmpty(),
                sqft = item["sqft"].str(),
                mls = item["mls"].str(),
                agentId = agent?.get("id").str().toLongOrNull(),
                agentName = agent?.get("name").str(),
                agentEmail = agent?.get("email").str(),
            )
        }
    }
}

data class WpAgent(val wpUserId: Long, val name: String, val email: String, val userId: UUID?)

/** Resultado de una busqueda de propiedades en el sitio. */
data class WpSearchResult(val total: Int, val items: List<WpListing>)

class CrmWordPressException(message: String) : Exception(message)
