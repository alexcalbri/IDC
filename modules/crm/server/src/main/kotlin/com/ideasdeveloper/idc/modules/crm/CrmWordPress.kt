package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
}

data class WpAgent(val wpUserId: Long, val name: String, val email: String, val userId: UUID?)

class CrmWordPressException(message: String) : Exception(message)

/** Real Estate 7 websites through the IDC Connector plugin. */
object CrmWordPress {
    const val Source = "wordpress"
    const val PluginVersion = "0.2.0"
    private const val MaxSkewSeconds = 300L
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val random = SecureRandom()

    // ---- Settings ----

    fun load(tenant: Connection): CrmWordPressSettings = tenant.prepareStatement(
        "SELECT enabled, site_url, secret, site_name, last_event_at, last_error FROM crm_wordpress_settings WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            rows.next()
            CrmWordPressSettings(rows.getBoolean(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getTimestamp(5)?.toInstant(), rows.getString(6))
        }
    }

    fun saveForm(tenant: Connection, values: Map<String, String>) {
        val url = values["siteUrl"].orEmpty().trim().trimEnd('/').ifEmpty { null }
        if (url != null && !url.matches(Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\\s]*)?$"))) {
            throw CrmValidationException("Escribe la direccion del sitio con https://, por ejemplo https://mi-inmobiliaria.com.")
        }
        val enabled = values["enabled"] == "true"
        val secret = load(tenant).secret
        if (enabled && (url == null || secret == null)) throw CrmValidationException("Para activar WordPress escribe la direccion del sitio y genera el secreto.")
        tenant.prepareStatement("UPDATE crm_wordpress_settings SET enabled = ?, site_url = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1").use { update ->
            update.setBoolean(1, enabled)
            update.setString(2, url)
            update.executeUpdate()
        }
    }

    /** Creates a new secret (48 letters and digits) and returns it so it can be pasted in the plugin. */
    fun newSecret(tenant: Connection): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val secret = (1..48).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        tenant.prepareStatement("UPDATE crm_wordpress_settings SET secret = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1").use { it.setString(1, secret); it.executeUpdate() }
        return secret
    }

    private fun record(tenant: Connection, siteName: String?, error: String) {
        tenant.prepareStatement(
            "UPDATE crm_wordpress_settings SET last_event_at = CURRENT_TIMESTAMP, site_name = COALESCE(?, site_name), last_error = ? WHERE id = 1"
        ).use { update ->
            update.setString(1, siteName)
            update.setString(2, error.take(500))
            update.executeUpdate()
        }
    }

    // ---- Signatures ----

    fun sign(secret: String, timestamp: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        mac.update("$timestamp.".toByteArray())
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    /** Valid when the timestamp is within 5 minutes and the signature matches, compared in constant time. */
    fun validSignature(secret: String?, timestamp: String?, signature: String?, body: ByteArray, now: Instant = Instant.now()): Boolean {
        if (secret.isNullOrBlank() || timestamp == null || signature == null) return false
        val seconds = timestamp.toLongOrNull() ?: return false
        if (kotlin.math.abs(now.epochSecond - seconds) > MaxSkewSeconds) return false
        return MessageDigest.isEqual(sign(secret, timestamp, body).toByteArray(), signature.lowercase().toByteArray())
    }

    // ---- Plugin API (IDC -> website) ----

    internal fun rpc(settings: CrmWordPressSettings, action: String, extra: JsonObject = JsonObject(emptyMap())): JsonElement {
        val site = settings.siteUrl ?: throw CrmWordPressException("Escribe la direccion del sitio en Integraciones.")
        val secret = settings.secret ?: throw CrmWordPressException("Genera el secreto y pegalo en el plugin.")
        val body = JsonObject(mapOf("action" to JsonPrimitive(action)) + extra).toString().toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val request = HttpRequest.newBuilder(URI.create("$site/wp-json/idc/v1/rpc"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-IDC-Timestamp", timestamp)
            .header("X-IDC-Signature", sign(secret, timestamp, body))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: Exception) {
            throw CrmWordPressException("No se pudo contactar el sitio $site.")
        }
        val parsed = runCatching { json.parseToJsonElement(response.body()) }.getOrNull()
        return when (response.statusCode()) {
            in 200..299 -> parsed ?: throw CrmWordPressException("El sitio respondio algo que no es JSON.")
            401, 403 -> throw CrmWordPressException("El sitio rechazo la firma: revisa que el plugin IDC Connector este activo y tenga el mismo secreto.")
            404 -> throw CrmWordPressException((parsed as? JsonObject)?.get("error").str().ifEmpty { "El sitio no tiene el plugin IDC Connector (o ese recurso no existe)." })
            else -> throw CrmWordPressException("El sitio respondio HTTP ${response.statusCode()}.")
        }
    }

    fun ping(tenant: Connection): String {
        val result = rpc(load(tenant), "ping").obj()
        val name = result?.get("site").str()
        record(tenant, name, "")
        return "${name.ifEmpty { "Sitio" }} (plugin ${result?.get("version").str()}, tema ${result?.get("theme").str()})"
    }

    data class SearchResult(val total: Int, val items: List<WpListing>)

    fun search(tenant: Connection, text: String, filters: Map<String, String>, minPrice: String, maxPrice: String, page: Int): SearchResult {
        val payload = buildJsonObject {
            put("search", text.take(100))
            put("page", page)
            put("per_page", 20)
            put("min_price", minPrice)
            put("max_price", maxPrice)
            putJsonObject("filters") { filters.forEach { (key, value) -> put(key, value) } }
        }
        val result = rpc(load(tenant), "search", payload).obj() ?: throw CrmWordPressException("Respuesta invalida del sitio.")
        return SearchResult(result["total"].str().toIntOrNull() ?: 0, result["items"].arr().mapNotNull { listing(it) })
    }

    fun listing(tenant: Connection, id: Long): WpListing =
        listing(rpc(load(tenant), "listing", buildJsonObject { put("id", id) })) ?: throw CrmWordPressException("Respuesta invalida del sitio.")

    fun taxonomies(tenant: Connection): Map<String, List<String>> =
        rpc(load(tenant), "taxonomies").obj()?.mapValues { (_, terms) -> terms.arr().mapNotNull { it.obj()?.get("name").str().ifEmpty { null } } }.orEmpty()

    fun markSold(tenant: Connection, id: Long, price: BigDecimal?, date: LocalDate) {
        rpc(load(tenant), "sold", buildJsonObject {
            put("id", id)
            put("price", price?.toPlainString().orEmpty())
            put("date", date.toString())
        })
    }

    /** Reads the agents of the website and keeps their IDC user (an email match links new ones). */
    fun syncAgents(tenant: Connection, users: List<CrmUser>, userEmails: Map<UUID, String>): Int {
        val agents = rpc(load(tenant), "agents").arr().mapNotNull { it.obj() }
        agents.forEach { agent ->
            val id = agent["id"].str().toLongOrNull()?.takeIf { it > 0 } ?: return@forEach
            val email = agent["email"].str()
            val match = users.firstOrNull { user -> userEmails[user.id]?.equals(email, ignoreCase = true) == true }?.id
            tenant.prepareStatement(
                """
                INSERT INTO crm_wp_agents (wp_user_id, name, email, user_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (wp_user_id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email,
                    user_id = COALESCE(crm_wp_agents.user_id, EXCLUDED.user_id), updated_at = CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { upsert ->
                upsert.setLong(1, id)
                upsert.setString(2, agent["name"].str().take(200))
                upsert.setString(3, email.take(254))
                upsert.setObject(4, match)
                upsert.executeUpdate()
            }
        }
        return agents.size
    }

    fun agents(tenant: Connection): List<WpAgent> =
        tenant.prepareStatement("SELECT wp_user_id, name, email, user_id FROM crm_wp_agents ORDER BY name").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(WpAgent(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getObject(4, UUID::class.java))) } }
        }

    fun mapAgent(tenant: Connection, wpUserId: Long, userId: UUID?) {
        tenant.prepareStatement("UPDATE crm_wp_agents SET user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE wp_user_id = ?").use { update ->
            update.setObject(1, userId)
            update.setLong(2, wpUserId)
            update.executeUpdate()
        }
    }

    private fun userForAgent(tenant: Connection, wpUserId: Long?, name: String, email: String): UUID? {
        if (wpUserId == null || wpUserId <= 0) return null
        // Agents seen for the first time are recorded so they can be mapped in Integraciones.
        tenant.prepareStatement(
            """
            INSERT INTO crm_wp_agents (wp_user_id, name, email) VALUES (?, ?, ?)
            ON CONFLICT (wp_user_id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email
            RETURNING user_id
            """.trimIndent()
        ).use { upsert ->
            upsert.setLong(1, wpUserId)
            upsert.setString(2, name.take(200))
            upsert.setString(3, email.take(254))
            upsert.executeQuery().use { rows -> return if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
    }

    // ---- Events (website -> IDC) ----

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    fun listing(element: JsonElement?): WpListing? {
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

    private val formLabels = mapOf("form" to "Formulario", "inquiry" to "Consulta de propiedad", "showing" to "Solicitud de visita")

    /**
     * Stores one event of the website. Forms, listing inquiries and showing requests go to the inbox
     * (channel "form") with the property linked and its agent as suggested responsible; favorites,
     * saved searches and views are added to the activity of the customer's open lead and never
     * create leads. Returns what happened, for the log.
     */
    fun handle(tenant: Connection, body: String, onNewLead: (UUID) -> Unit): String {
        val event = runCatching { json.parseToJsonElement(body) }.getOrNull().obj() ?: return "JSON invalido"
        val kind = event["event"].str()
        val listing = listing(event["listing"])
        val name = event["name"].str()
        val email = event["email"].str()
        return when (kind) {
            "ping" -> {
                record(tenant, null, "")
                "ping"
            }
            "form", "inquiry", "showing" -> {
                val label = formLabels.getValue(kind)
                val source = when {
                    listing != null -> "Formulario - ${listing.title}"
                    event["form"].str().isNotEmpty() -> "Formulario - ${event["form"].str()}"
                    else -> "Formulario del sitio"
                }
                val header = "[Sitio web - $label]" + (listing?.let { " ${it.title}" } ?: event["form"].str().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty())
                val result = CrmInbox.receive(
                    tenant,
                    CrmInbound(
                        channel = "form",
                        name = name,
                        email = email,
                        phone = event["phone"].str(),
                        message = listOf(header, event["message"].str()).filter { it.isNotEmpty() }.joinToString("\n"),
                        messageId = event["id"].str().takeIf { it.isNotEmpty() }?.let { "wp:$it" },
                        source = source,
                        suggestedOwnerUserId = listing?.let { userForAgent(tenant, it.agentId, it.agentName, it.agentEmail) },
                    ),
                    onNewLead,
                )
                val leadId = when (result) {
                    is CrmIntakeResult.Created -> result.leadId
                    is CrmIntakeResult.Updated -> result.leadId
                    else -> null
                }
                if (leadId != null && listing != null) linkListing(tenant, leadId, listing, "Consulto la propiedad ${listing.title} en el sitio.")
                record(tenant, null, "")
                "$kind: ${result::class.simpleName}"
            }
            "favorite", "saved_search", "view" -> {
                val lead = openLeadByEmail(tenant, email) ?: return "$kind sin lead"
                val text = when (kind) {
                    "favorite" -> "Agrego a favoritos: ${listing?.title.orEmpty()}"
                    "saved_search" -> "Guardo una busqueda con alertas:\n${event["message"].str()}"
                    else -> "Vio la propiedad ${listing?.title.orEmpty()}"
                }
                CrmRepository.addActivity(tenant, lead, "web", text, CrmActor(null, "Sitio web"))
                if (kind == "favorite" && listing != null) linkListing(tenant, lead, listing, null)
                "$kind registrado"
            }
            else -> "evento desconocido"
        }
    }

    fun linkListing(tenant: Connection, leadId: UUID, listing: WpListing, activity: String?) {
        val added = CrmItems.link(tenant, leadId, Source, listing.id.toString(), listing.title, listOf(listing.priceText, listing.summary).filter { it.isNotEmpty() }.joinToString(" - "), listing.url, listing.priceValue)
        if (added) CrmRepository.addActivity(tenant, leadId, "item", activity ?: "Propiedad de interes: ${listing.title}.", CrmActor(null, "Sitio web"))
    }

    private fun openLeadByEmail(tenant: Connection, email: String): UUID? {
        if (email.isBlank()) return null
        return tenant.prepareStatement(
            """
            SELECT l.id FROM crm_leads l JOIN customers c ON c.id = l.customer_id
            WHERE lower(c.primary_email) = lower(?) AND l.closed_at IS NULL ORDER BY l.updated_at DESC LIMIT 1
            """.trimIndent()
        ).use { query ->
            query.setString(1, email)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
    }

    fun recordError(tenant: Connection, error: String) = record(tenant, null, error)
}
