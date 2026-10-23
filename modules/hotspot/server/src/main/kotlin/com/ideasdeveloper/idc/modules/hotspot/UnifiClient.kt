package com.ideasdeveloper.idc.modules.hotspot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

data class UnifiResult(val authorized: Boolean, val message: String)

/**
 * UniFi Network Integration API through the UniFi Site Manager cloud connector (api.ui.com).
 */
object UnifiClient {
    private const val PageSize = 200
    private const val MaxPages = 25
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun authorizeGuest(settings: HotspotSettings, mac: String): UnifiResult {
        if (!settings.unifiConfigured) return UnifiResult(false, "UniFi no esta configurado.")
        // Both ids were validated against [A-Za-z0-9:._-]; the console id keeps its ':' as UniFi shows it.
        val base = "https://api.ui.com/v1/connector/consoles/${settings.unifiConsoleId}" +
            "/proxy/network/integration/v1/sites/${settings.unifiSiteId}"
        return try {
            val clientId = findClientId(base, settings.unifiApiKey!!, mac)
                ?: return UnifiResult(false, "El dispositivo $mac no aparece conectado en UniFi.")
            val payload = buildMap<String, JsonPrimitive> {
                put("action", JsonPrimitive("AUTHORIZE_GUEST_ACCESS"))
                put("timeLimitMinutes", JsonPrimitive(settings.accessMinutes))
                // 0 means unlimited; UniFi expects the parameter to be omitted in that case.
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
                UnifiResult(true, "Autorizado")
            } else {
                UnifiResult(false, "UniFi respondio HTTP ${response.statusCode()}: ${response.body().take(300)}")
            }
        } catch (failure: Exception) {
            UnifiResult(false, "No se pudo contactar UniFi: ${failure.message}")
        }
    }

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
