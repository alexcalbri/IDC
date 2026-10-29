package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

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

// A contact of a listing as the Encuentra24 Leads API returns it.
data class E24Lead(
    val id: String,
    val origin: String,
    val title: String,
    val adId: String,
    val message: String,
    val chat: String,
    val name: String,
    val email: String,
    val phone: String,
    val createdAt: Instant?,
)

class CrmEncuentra24Exception(message: String) : Exception(message)

/** Encuentra24 Leads API: imports the contacts of the company's listings into the CRM inbox. */
object CrmEncuentra24 {
    private const val Endpoint = "https://www.encuentra24.com/api.php/v1/crm/index"
    private const val PageSize = 200
    private const val MaxPages = 25
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun load(tenant: Connection): CrmEncuentra24Settings = tenant.prepareStatement(
        "SELECT enabled, api_key, slug, last_sync, last_result, last_error FROM crm_encuentra24_settings WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            rows.next()
            CrmEncuentra24Settings(rows.getBoolean(1), rows.getString(2), rows.getString(3), rows.getTimestamp(4)?.toInstant(), rows.getString(5), rows.getString(6))
        }
    }

    fun toFormValues(settings: CrmEncuentra24Settings) = mapOf(
        "enabled" to settings.enabled.toString(),
        "apiKey" to if (settings.apiKey.isNullOrBlank()) "" else CrmMail.SecretMask,
        "slug" to settings.slug.orEmpty(),
    )

    fun saveForm(tenant: Connection, values: Map<String, String>) {
        val current = load(tenant)
        val key = values["apiKey"]?.trim()?.takeIf { it.isNotEmpty() && it != CrmMail.SecretMask } ?: current.apiKey
        val slug = values["slug"].orEmpty().trim().lowercase().ifEmpty { null }
        if (slug != null && !slug.matches(Regex("^[a-z0-9][a-z0-9-]{0,99}$"))) {
            throw CrmValidationException("El slug lleva letras minusculas, numeros y guiones (como en la URL de la empresa).")
        }
        val enabled = values["enabled"] == "true"
        if (enabled && (key.isNullOrBlank() || slug == null)) throw CrmValidationException("Para activar Encuentra24 escribe la API key y el slug de la empresa.")
        tenant.prepareStatement("UPDATE crm_encuentra24_settings SET enabled = ?, api_key = ?, slug = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1").use { update ->
            update.setBoolean(1, enabled)
            update.setString(2, key)
            update.setString(3, slug)
            update.executeUpdate()
        }
    }

    private fun record(tenant: Connection, syncedAt: Instant?, result: String, error: String) {
        tenant.prepareStatement(
            "UPDATE crm_encuentra24_settings SET last_sync = COALESCE(?, last_sync), last_result = ?, last_error = ? WHERE id = 1"
        ).use { update ->
            update.setTimestamp(1, syncedAt?.let { Timestamp.from(it) })
            update.setString(2, result.take(500))
            update.setString(3, error.take(500))
            update.executeUpdate()
        }
    }

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd[ ]['T']HH:mm[:ss]")

    private fun instant(raw: String): Instant? = raw.takeIf { it.isNotBlank() }?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { LocalDateTime.parse(it, dateTime).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    /** Leads of one API page (oldest first) and the id to continue from (0 = last page); simple list or {data, pagination}. */
    fun parsePage(body: String): Pair<List<E24Lead>, Long> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: throw CrmEncuentra24Exception("Encuentra24 respondio algo que no es JSON.")
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: JsonArray(emptyList())
            else -> throw CrmEncuentra24Exception("Encuentra24 respondio algo que no es una lista de leads.")
        }
        val next = (root as? JsonObject)?.get("pagination").obj()?.get("nextid").str().toLongOrNull() ?: 0
        val leads = items.mapNotNull { it.obj() }.map { lead ->
            val contact = lead["contact"].obj()
            E24Lead(
                id = lead["id"].str(),
                origin = lead["origin"].str(),
                title = lead["title"].str(),
                adId = lead["adid"].str().filter { it.isDigit() },
                message = lead["message"].str().ifEmpty { lead["preview"].str() },
                chat = lead["chat"].str(),
                name = contact?.get("name").str(),
                email = contact?.get("email").str(),
                phone = contact?.get("phone").str(),
                createdAt = instant(lead["created_at"].str()),
            )
        }.filter { it.id.isNotEmpty() }
        return leads.sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE } to next
    }

    /** Text stored in the conversation: origin, listing and message. */
    fun messageText(lead: E24Lead): String {
        val origin = mapOf("E24_MESSAGE" to "Mensaje", "E24_CHAT" to "Chat", "PHONECALL" to "Llamada")[lead.origin] ?: "Contacto"
        val header = "[Encuentra24 - $origin]" + (if (lead.title.isNotEmpty()) " ${lead.title}" else "") + (if (lead.adId.isNotEmpty()) " (anuncio ${lead.adId})" else "")
        val text = lead.message.replace(Regex("<[^>]+>"), "").trim()
        return listOfNotNull(
            header,
            text.ifEmpty { null } ?: "Llamada desde el anuncio.".takeIf { lead.origin == "PHONECALL" },
            lead.chat.takeIf { it.startsWith("https://") }?.let { "Chat en Encuentra24: $it" },
        ).joinToString("\n")
    }

    private fun fetch(settings: CrmEncuentra24Settings, params: Map<String, String>): Pair<List<E24Lead>, Long> {
        val query = (mapOf("slug" to settings.slug.orEmpty()) + params).entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
        val request = HttpRequest.newBuilder(URI.create("$Endpoint?$query"))
            .timeout(Duration.ofSeconds(20))
            .header("x-api-key", settings.apiKey.orEmpty())
            .header("Accept", "application/json")
            .GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: Exception) {
            throw CrmEncuentra24Exception("No se pudo contactar la API de Encuentra24.")
        }
        when (response.statusCode()) {
            401, 403 -> throw CrmEncuentra24Exception("Encuentra24 rechazo la API key (HTTP ${response.statusCode()}).")
            !in 200..299 -> throw CrmEncuentra24Exception("Encuentra24 respondio con un error (HTTP ${response.statusCode()}).")
        }
        return parsePage(response.body())
    }

    data class SyncResult(val imported: Int, val existing: Int, val withoutContact: Int, val spam: Int, val failed: Int) {
        override fun toString() = "$imported importados, $existing ya existentes, $withoutContact sin contacto, $spam spam, $failed con error"
    }

    /**
     * Imports the leads created since [fromDate] (default: one day before the last sync, or the
     * last 7 days the first time), oldest first. Leads already imported are skipped by their id.
     */
    fun sync(tenant: Connection, fromDate: LocalDate? = null, onNewLead: (UUID) -> Unit = {}): SyncResult {
        val settings = load(tenant)
        if (settings.apiKey.isNullOrBlank() || settings.slug.isNullOrBlank()) throw CrmEncuentra24Exception("Falta la API key o el slug de Encuentra24.")
        val started = Instant.now()
        val from = fromDate ?: settings.lastSync?.atZone(ZoneOffset.UTC)?.toLocalDate()?.minusDays(1) ?: LocalDate.now(ZoneOffset.UTC).minusDays(7)
        var imported = 0
        var existing = 0
        var withoutContact = 0
        var spam = 0
        var failed = 0
        val params = mutableMapOf("fromdate" to from.toString(), "limit" to PageSize.toString())
        try {
            for (page in 0 until MaxPages) {
                val (leads, next) = fetch(settings, params)
                leads.forEach { lead ->
                    val result = try {
                        CrmInbox.receive(
                            tenant,
                            CrmInbound(
                                channel = "encuentra24", name = lead.name, email = lead.email, phone = lead.phone,
                                message = messageText(lead), messageId = "e24:${lead.id}", source = "Encuentra24", sentAt = lead.createdAt,
                            ),
                            onNewLead,
                        )
                    } catch (failure: Exception) {
                        failed++
                        null
                    }
                    when (result) {
                        is CrmIntakeResult.Created, is CrmIntakeResult.Updated -> imported++
                        CrmIntakeResult.Duplicate -> existing++
                        is CrmIntakeResult.Ignored -> withoutContact++
                        is CrmIntakeResult.Blocked -> spam++
                        null -> Unit
                    }
                }
                if (next == 0L || leads.isEmpty() || next.toString() == params["fromid"]) break
                params["fromid"] = next.toString()
            }
        } catch (failure: CrmEncuentra24Exception) {
            record(tenant, null, "", failure.message.orEmpty())
            throw failure
        }
        return SyncResult(imported, existing, withoutContact, spam, failed).also { record(tenant, started, "Desde $from: $it", "") }
    }
}
