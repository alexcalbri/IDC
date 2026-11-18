package com.ideasdeveloper.idc.extensions.encuentra24

import com.ideasdeveloper.idc.modules.crm.CrmEncuentra24Ads
import com.ideasdeveloper.idc.modules.crm.CrmEncuentra24Exception
import com.ideasdeveloper.idc.modules.crm.CrmEncuentra24Settings
import com.ideasdeveloper.idc.modules.crm.CrmEncuentra24SyncResult
import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmInbound
import com.ideasdeveloper.idc.modules.crm.CrmIntakeResult
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

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

/** Encuentra24 Leads API: imports the contacts of the company's listings into the CRM inbox. */
object CrmEncuentra24 {
    private const val Endpoint = "https://www.encuentra24.com/api.php/v1/crm/index"
    // Advisory lock of the sync, so the job and "Importar ahora" never run together.
    private const val LockKey = 0x45323453594E43L
    private const val PageSize = 200
    private const val MaxPages = 25
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    /**
     * Links the listing a lead asked about (by listing id, or by link when it has none). A listing
     * already linked is left as it is.
     */
    fun rememberAd(host: CrmHost, tenant: Connection, leadId: UUID, id: String, url: String, title: String): Boolean {
        val key = id.ifEmpty { url.lowercase().removeSuffix("/") }.ifEmpty { return false }.take(200)
        if (host.items(tenant, leadId, CrmEncuentra24Ads.Source).any { it.externalId == key }) return false
        return host.linkItem(
            tenant, leadId, CrmEncuentra24Ads.Source, key,
            title = title.ifEmpty { if (id.isNotEmpty()) "Anuncio $id" else "Anuncio de Encuentra24" },
            subtitle = "Encuentra24" + if (id.isNotEmpty() && title.isNotEmpty()) " - anuncio $id" else "",
            url = url,
        )
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

    private fun fetch(host: CrmHost, settings: CrmEncuentra24Settings, params: Map<String, String>): Pair<List<E24Lead>, Long> {
        val query = (mapOf("slug" to settings.slug.orEmpty()) + params).entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
        // Una llave ilegible (cifrada que no se pudo abrir) nunca se envia: cuenta como sin llave.
        if (host.isSecretUnreadable(settings.apiKey)) throw CrmEncuentra24Exception(host.UnreadableSecretText)
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

    /**
     * Imports the leads created since [fromDate] (default: one day before the last sync, or the
     * last 7 days the first time), oldest first. Leads already imported are skipped by their id; the
     * listing of each lead is linked to it. Encuentra24 leads never get a suggested responsible.
     */
    fun sync(host: CrmHost, tenant: Connection, fromDate: LocalDate? = null, onNewLead: (UUID) -> Unit = {}): CrmEncuentra24SyncResult {
        host.extensionInactiveReason(tenant, CrmExtensionIds.Encuentra24)?.let { throw CrmEncuentra24Exception(it) }
        val settings = Encuentra24SettingsStore.load(host, tenant)
        if (settings.apiKey.isNullOrBlank() || host.isSecretUnreadable(settings.apiKey) || settings.slug.isNullOrBlank()) throw CrmEncuentra24Exception("Falta la API key o el slug de Encuentra24.")
        val locked = tenant.prepareStatement("SELECT pg_try_advisory_lock(?)").use { query ->
            query.setLong(1, LockKey)
            query.executeQuery().use { it.next() && it.getBoolean(1) }
        }
        if (!locked) throw CrmEncuentra24Exception("Ya hay una importacion de Encuentra24 en curso; intenta en unos minutos.")
        try {
            return syncLocked(host, tenant, settings, fromDate, onNewLead)
        } finally {
            tenant.prepareStatement("SELECT pg_advisory_unlock(?)").use { it.setLong(1, LockKey); it.executeQuery().close() }
        }
    }

    private fun syncLocked(host: CrmHost, tenant: Connection, settings: CrmEncuentra24Settings, fromDate: LocalDate?, onNewLead: (UUID) -> Unit): CrmEncuentra24SyncResult {
        val started = Instant.now()
        val from = fromDate ?: settings.lastSync?.atZone(ZoneOffset.UTC)?.toLocalDate()?.minusDays(1) ?: LocalDate.now(ZoneOffset.UTC).minusDays(7)
        var imported = 0
        var existing = 0
        var withoutContact = 0
        var spam = 0
        var failed = 0
        var firstError = ""
        val params = mutableMapOf("fromdate" to from.toString(), "limit" to PageSize.toString())
        try {
            for (page in 0 until MaxPages) {
                val (leads, next) = fetch(host, settings, params)
                leads.forEach { lead ->
                    val result = try {
                        host.receive(
                            tenant,
                            CrmInbound(
                                channel = CrmEncuentra24Ads.Source, name = lead.name, email = lead.email, phone = lead.phone,
                                message = messageText(lead), messageId = "e24:${lead.id}", source = "Encuentra24", sentAt = lead.createdAt,
                            ),
                            onNewLead,
                        ).also { result ->
                            val leadId = (result as? CrmIntakeResult.Created)?.leadId ?: (result as? CrmIntakeResult.Updated)?.leadId
                            // The message is already stored: a listing that fails to link does not fail the lead.
                            if (leadId != null && lead.adId.isNotEmpty()) runCatching { rememberAd(host, tenant, leadId, lead.adId, "", lead.title) }
                        }
                    } catch (failure: Exception) {
                        failed++
                        if (firstError.isEmpty()) firstError = "lead ${lead.id}: ${failure.message ?: failure.javaClass.simpleName}".take(200)
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
            Encuentra24SettingsStore.record(tenant, null, "", failure.message.orEmpty())
            throw failure
        }
        return CrmEncuentra24SyncResult(imported, existing, withoutContact, spam, failed, firstError).also { Encuentra24SettingsStore.record(tenant, started, "Desde $from: $it", "") }
    }
}
