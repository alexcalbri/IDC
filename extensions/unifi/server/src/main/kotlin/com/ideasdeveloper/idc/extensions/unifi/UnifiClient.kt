package com.ideasdeveloper.idc.extensions.unifi

import com.ideasdeveloper.idc.modules.hotspot.ConnectedGuest
import com.ideasdeveloper.idc.modules.hotspot.GuestAuthorization
import com.ideasdeveloper.idc.modules.hotspot.HotspotSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class UnifiConsole(val id: String, val name: String, val model: String, val state: String)

data class UnifiSite(val id: String, val name: String, val reference: String)

/** A connected device and the SSID it is on ("" when wired). */
data class UnifiStation(val mac: String, val ssid: String)

class UnifiException(message: String) : Exception(message)

/**
 * UniFi Network Integration API through the UniFi Site Manager cloud connector (api.ui.com).
 */
object UnifiClient {
    private const val CloudApi = "https://api.ui.com"
    private const val PageSize = 200
    private const val MaxPages = 25
    private val stationMacPattern = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    // Site internal references by "console/site"; they do not change for a site.
    private val siteReferences = ConcurrentHashMap<String, String>()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Authorizes [mac] only while it is connected to [ssid] ("" for a wired guest). The redirect
     * that brings the guest to the portal is not signed, so the network it claims is checked
     * against the one UniFi reports before letting the device in.
     */
    fun authorizeGuest(settings: HotspotSettings, mac: String, ssid: String): GuestAuthorization {
        if (!settings.unifiConfigured) return GuestAuthorization(false, "UniFi no esta configurado.")
        // Both ids were validated against [A-Za-z0-9:._-]; the console id keeps its ':' as UniFi shows it.
        val base = "$CloudApi/v1/connector/consoles/${settings.unifiConsoleId}" +
            "/proxy/network/integration/v1/sites/${settings.unifiSiteId}"
        return try {
            val station = station(settings, mac)
                ?: return GuestAuthorization(false, "El dispositivo $mac no aparece conectado en UniFi.")
            if (!sameNetwork(station.ssid, ssid)) {
                return GuestAuthorization(
                    false,
                    "El dispositivo $mac esta conectado a '${station.ssid}' y el portal se abrio para '$ssid'.",
                    wrongNetwork = true,
                )
            }
            val clientId = findClientId(base, settings.unifiApiKey!!, mac)
                ?: return GuestAuthorization(false, "El dispositivo $mac no aparece conectado en UniFi.")
            val payload = buildMap<String, JsonPrimitive> {
                put("action", JsonPrimitive("AUTHORIZE_GUEST_ACCESS"))
                if (settings.accessMinutes > 0) put("timeLimitMinutes", JsonPrimitive(settings.accessMinutes))
                // UniFi treats an omitted time limit as unlimited.
                if (settings.dataLimitMb > 0) put("dataUsageLimitMBytes", JsonPrimitive(settings.dataLimitMb))
                if (settings.rxKbps > 0) put("rxRateLimitKbps", JsonPrimitive(settings.rxKbps))
                if (settings.txKbps > 0) put("txRateLimitKbps", JsonPrimitive(settings.txKbps))
            }
            val response = send(
                HttpRequest.newBuilder(URI.create("$base/clients/${encode(clientId)}/actions"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-API-Key", settings.unifiApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JsonObject(payload).toString())),
            )
            if (response.statusCode() in 200..299) {
                GuestAuthorization(true, "Autorizado")
            } else {
                GuestAuthorization(false, "UniFi respondio HTTP ${response.statusCode()}: ${response.body().take(300)}")
            }
        } catch (failure: Exception) {
            GuestAuthorization(false, "No se pudo contactar UniFi: ${failure.message}")
        }
    }

    /** Removes the guest access of [mac]; a device that is no longer connected has nothing to remove. */
    fun unauthorizeGuest(settings: HotspotSettings, mac: String): GuestAuthorization {
        if (!settings.unifiConfigured) return GuestAuthorization(false, "UniFi no esta configurado.")
        val base = "$CloudApi/v1/connector/consoles/${settings.unifiConsoleId}" +
            "/proxy/network/integration/v1/sites/${settings.unifiSiteId}"
        return try {
            val clientId = findClientId(base, settings.unifiApiKey!!, mac)
                ?: return GuestAuthorization(true, "El dispositivo ya no esta conectado.")
            val response = send(
                HttpRequest.newBuilder(URI.create("$base/clients/${encode(clientId)}/actions"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-API-Key", settings.unifiApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JsonObject(mapOf("action" to JsonPrimitive("UNAUTHORIZE_GUEST_ACCESS"))).toString())),
            )
            if (response.statusCode() in 200..299) {
                GuestAuthorization(true, "Desconectado")
            } else {
                GuestAuthorization(false, "UniFi respondio HTTP ${response.statusCode()}: ${response.body().take(300)}")
            }
        } catch (failure: Exception) {
            GuestAuthorization(false, "No se pudo contactar UniFi: ${failure.message}")
        }
    }

    // Consoles the API key can reach (UniFi Site Manager /v1/hosts).
    fun consoles(apiKey: String): List<UnifiConsole> {
        val consoles = mutableListOf<UnifiConsole>()
        var nextToken: String? = null
        repeat(MaxPages) {
            val page = getJson("$CloudApi/v1/hosts?pageSize=$PageSize" + nextToken?.let { "&nextToken=${encode(it)}" }.orEmpty(), apiKey)
            consoles += parseConsoles(page)
            nextToken = page.string("nextToken")?.takeIf { it.isNotBlank() } ?: return consoles
        }
        return consoles
    }

    // Sites of a console (Network Integration API); the console id was validated by the caller.
    fun sites(apiKey: String, consoleId: String): List<UnifiSite> =
        parseSites(getJson("$CloudApi/v1/connector/consoles/$consoleId/proxy/network/integration/v1/sites?offset=0&limit=$PageSize", apiKey))

    /**
     * SSIDs of the site (Network Integration API "WiFi broadcasts", UniFi Network 10 or newer). The
     * SSID a client is on comes from the station API instead (see [station]).
     */
    fun wifiNetworks(apiKey: String, consoleId: String, siteId: String): List<String> {
        val base = "$CloudApi/v1/connector/consoles/$consoleId/proxy/network/integration/v1/sites/$siteId/wifi/broadcasts"
        val names = mutableListOf<String>()
        var offset = 0
        repeat(MaxPages) {
            val page = getJson("$base?offset=$offset&limit=$PageSize", apiKey)
            val data = page["data"]?.jsonArray.orEmpty()
            names += parseWifiNetworks(page)
            val total = page["totalCount"]?.jsonPrimitive?.intOrNull ?: return names.distinct()
            offset += data.size
            if (data.isEmpty() || offset >= total) return names.distinct()
        }
        return names.distinct()
    }

    fun parseWifiNetworks(page: JsonObject): List<String> = page["data"]?.jsonArray.orEmpty().mapNotNull { element ->
        (element as? JsonObject)?.string("name")?.takeIf { it.isNotBlank() }
    }

    fun parseConsoles(page: JsonObject): List<UnifiConsole> = page["data"]?.jsonArray.orEmpty().mapNotNull { element ->
        val host = element as? JsonObject ?: return@mapNotNull null
        val id = host.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val reported = host["reportedState"] as? JsonObject
        UnifiConsole(
            id = id,
            name = reported?.string("name") ?: reported?.string("hostname") ?: id,
            model = (reported?.get("hardware") as? JsonObject)?.string("name") ?: host.string("type").orEmpty(),
            state = reported?.string("state").orEmpty(),
        )
    }

    fun parseSites(page: JsonObject): List<UnifiSite> = page["data"]?.jsonArray.orEmpty().mapNotNull { element ->
        val site = element as? JsonObject ?: return@mapNotNull null
        val id = site.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val reference = site.string("internalReference").orEmpty()
        UnifiSite(id = id, name = site.string("name") ?: reference.ifBlank { id }, reference = reference)
    }

    private fun getJson(url: String, apiKey: String): JsonObject {
        val response = try {
            send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").header("X-API-Key", apiKey).GET())
        } catch (failure: Exception) {
            throw UnifiException("No se pudo contactar UniFi: ${failure.message}")
        }
        when (response.statusCode()) {
            200 -> Unit
            401, 403 -> throw UnifiException("UniFi rechazo la API key (HTTP ${response.statusCode()}). Revisa que sea valida y tenga acceso a la consola.")
            404 -> throw UnifiException("UniFi no encontro la consola o la funcion pedida (HTTP 404). Las redes WiFi requieren UniFi Network 10 o superior.")
            else -> throw UnifiException("UniFi respondio HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        return runCatching { Json.parseToJsonElement(response.body()).jsonObject }
            .getOrElse { throw UnifiException("UniFi devolvio una respuesta que no se pudo leer.") }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * The device as UniFi Network sees it right now, or null when it is not connected. Reads the
     * Network application's own station API ("stat/sta"), the only one that tells the SSID; the
     * Integration API does not. Throws when UniFi cannot be asked, so callers fail closed.
     */
    private fun station(settings: HotspotSettings, mac: String): UnifiStation? {
        if (!stationMacPattern.matches(mac)) return null
        val reference = siteReference(settings.unifiApiKey!!, settings.unifiConsoleId!!, settings.unifiSiteId!!)
        val response = send(
            HttpRequest.newBuilder(
                URI.create("$CloudApi/v1/connector/consoles/${settings.unifiConsoleId}/proxy/network/api/s/${encode(reference)}/stat/sta/$mac")
            )
                .header("Accept", "application/json")
                .header("X-API-Key", settings.unifiApiKey)
                .GET(),
        )
        val page = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        // UniFi answers 400 api.err.UnknownStation for a device that is not connected.
        if (response.statusCode() == 400 && page?.let(::isUnknownStation) == true) return null
        if (response.statusCode() != 200 || page == null) {
            throw IllegalStateException("HTTP ${response.statusCode()} al consultar la red del dispositivo")
        }
        return parseStation(page)
    }

    // The station API addresses a site by its internal reference ("default"), not by the Integration API id.
    private fun siteReference(apiKey: String, consoleId: String, siteId: String): String =
        siteReferences["$consoleId/$siteId"] ?: (
            sites(apiKey, consoleId).firstOrNull { it.id == siteId }?.reference?.takeIf { it.isNotBlank() }
                ?: throw UnifiException("El sitio de UniFi configurado no aparece en la consola.")
            ).also { siteReferences["$consoleId/$siteId"] = it }

    /** Guest devices connected to the site now. Throws [UnifiException] when UniFi cannot be asked. */
    fun connectedGuests(settings: HotspotSettings): List<ConnectedGuest> {
        if (!settings.unifiConfigured) throw UnifiException("UniFi no esta configurado.")
        // Copia local: HotspotSettings es de otro modulo y el compilador no puede afinar el tipo de su propiedad.
        val apiKey = settings.unifiApiKey!!
        val reference = siteReference(apiKey, settings.unifiConsoleId!!, settings.unifiSiteId!!)
        val page = getJson(
            "$CloudApi/v1/connector/consoles/${settings.unifiConsoleId}/proxy/network/api/s/${encode(reference)}/stat/sta",
            apiKey,
        )
        if ((page["meta"] as? JsonObject)?.string("rc") != "ok") throw UnifiException("UniFi no devolvio los dispositivos conectados.")
        return parseGuests(page)
    }

    /** Guests of a stat/sta answer with their SSID ("" when wired) and authorization. */
    fun parseGuests(page: JsonObject): List<ConnectedGuest> = page["data"]?.jsonArray.orEmpty().mapNotNull { element ->
        val station = element as? JsonObject ?: return@mapNotNull null
        if (station.boolean("is_guest") != true) return@mapNotNull null
        val mac = station.string("mac")?.trim()?.lowercase()?.takeIf { stationMacPattern.matches(it) } ?: return@mapNotNull null
        ConnectedGuest(mac = mac, ssid = station.string("essid").orEmpty(), authorized = station.boolean("authorized") == true)
    }

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** First station of a stat/sta answer; a wired device has no SSID and is reported with "". */
    fun parseStation(page: JsonObject): UnifiStation? {
        if ((page["meta"] as? JsonObject)?.string("rc") != "ok") return null
        val station = page["data"]?.jsonArray?.firstOrNull() as? JsonObject ?: return null
        val mac = station.string("mac")?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return UnifiStation(mac = mac, ssid = station.string("essid").orEmpty())
    }

    private fun isUnknownStation(page: JsonObject): Boolean =
        (page["meta"] as? JsonObject)?.string("msg") == "api.err.UnknownStation"

    /** SSIDs are compared exactly, as WiFi does; only surrounding spaces from the redirect are ignored. */
    fun sameNetwork(connected: String, claimed: String): Boolean = connected.trim() == claimed.trim()

    private fun findClientId(base: String, apiKey: String, mac: String): String? {
        var offset = 0
        repeat(MaxPages) {
            val response = send(
                HttpRequest.newBuilder(URI.create("$base/clients?offset=$offset&limit=$PageSize"))
                    .header("Accept", "application/json")
                    .header("X-API-Key", apiKey)
                    .GET(),
            )
            if (response.statusCode() != 200) {
                throw IllegalStateException("HTTP ${response.statusCode()} al listar clientes")
            }
            val page = Json.parseToJsonElement(response.body()).jsonObject
            val data = page["data"]?.jsonArray.orEmpty()
            data.forEach { element ->
                val client = element.jsonObject
                val clientMac = client["macAddress"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                if (clientMac == mac) return client["id"]?.jsonPrimitive?.contentOrNull
            }
            val total = page["totalCount"]?.jsonPrimitive?.intOrNull ?: return null
            offset += data.size
            if (data.isEmpty() || offset >= total) return null
        }
        return null
    }

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString())

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
