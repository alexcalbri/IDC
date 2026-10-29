package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
) {
    val whatsAppReady get() = enabled && !systemToken.isNullOrBlank() && !phoneNumberId.isNullOrBlank()
    val pageReady get() = enabled && !pageToken.isNullOrBlank()
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

object CrmMeta {
    private const val GraphVersion = "v23.0"
    private const val Graph = "https://graph.facebook.com/$GraphVersion"
    private val numericId = Regex("^[0-9]{1,64}$")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    // ---- Settings and log ----

    fun load(tenant: Connection): CrmMetaSettings = tenant.prepareStatement(
        """
        SELECT enabled, app_secret, verify_token, system_token, page_id, page_name, page_token, instagram_account_id,
               waba_id, phone_number_id, record_echoes
        FROM crm_meta_settings WHERE id = 1
        """.trimIndent()
    ).use { query ->
        query.executeQuery().use { rows ->
            rows.next()
            CrmMetaSettings(
                rows.getBoolean(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                rows.getString(7), rows.getString(8), rows.getString(9), rows.getString(10), rows.getBoolean(11),
            )
        }
    }

    fun save(tenant: Connection, settings: CrmMetaSettings) {
        tenant.prepareStatement(
            """
            UPDATE crm_meta_settings SET enabled = ?, app_secret = ?, verify_token = ?, system_token = ?, page_id = ?, page_name = ?,
                page_token = ?, instagram_account_id = ?, waba_id = ?, phone_number_id = ?, record_echoes = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = 1
            """.trimIndent()
        ).use { update ->
            update.setBoolean(1, settings.enabled)
            update.setString(2, settings.appSecret)
            update.setString(3, settings.verifyToken)
            update.setString(4, settings.systemToken)
            update.setString(5, settings.pageId)
            update.setString(6, settings.pageName)
            update.setString(7, settings.pageToken)
            update.setString(8, settings.instagramAccountId)
            update.setString(9, settings.wabaId)
            update.setString(10, settings.phoneNumberId)
            update.setBoolean(11, settings.recordEchoes)
            update.executeUpdate()
        }
    }

    fun toFormValues(settings: CrmMetaSettings): Map<String, String> = mapOf(
        "enabled" to settings.enabled.toString(),
        "appSecret" to mask(settings.appSecret),
        "verifyToken" to settings.verifyToken.orEmpty(),
        "systemToken" to mask(settings.systemToken),
        "pageId" to settings.pageId.orEmpty(),
        "wabaId" to settings.wabaId.orEmpty(),
        "phoneNumberId" to settings.phoneNumberId.orEmpty(),
        "recordEchoes" to settings.recordEchoes.toString(),
    )

    private fun mask(secret: String?) = if (secret.isNullOrBlank()) "" else CrmMail.SecretMask

    /** Applies the Meta form; blank or masked secrets keep the saved ones. */
    fun merge(current: CrmMetaSettings, values: Map<String, String>): CrmMetaSettings {
        fun secret(key: String, saved: String?) = values[key]?.trim()?.takeIf { it.isNotEmpty() && it != CrmMail.SecretMask } ?: saved
        fun id(key: String, label: String): String? {
            val value = values[key].orEmpty().trim().ifEmpty { return null }
            if (!numericId.matches(value)) throw CrmValidationException("$label solo lleva numeros.")
            return value
        }
        val verify = values["verifyToken"].orEmpty().trim().ifEmpty { null }
        if (verify != null && !verify.matches(Regex("^[A-Za-z0-9_.-]{8,100}$"))) {
            throw CrmValidationException("El verify token debe tener entre 8 y 100 letras, numeros, puntos, guiones o guiones bajos.")
        }
        val systemToken = secret("systemToken", current.systemToken)
        val pageId = id("pageId", "El ID de la pagina")
        val merged = current.copy(
            enabled = values["enabled"] == "true",
            appSecret = secret("appSecret", current.appSecret),
            verifyToken = verify,
            systemToken = systemToken,
            pageId = pageId,
            wabaId = id("wabaId", "El WhatsApp Business Account ID"),
            phoneNumberId = id("phoneNumberId", "El Phone Number ID"),
            recordEchoes = values["recordEchoes"] == "true",
        )
        if (merged.enabled && (merged.appSecret.isNullOrBlank() || merged.verifyToken == null || merged.systemToken.isNullOrBlank())) {
            throw CrmValidationException("Para activar Meta escribe el App Secret, el verify token y el token del usuario del sistema.")
        }
        // Another token or page needs the page token again.
        return if (systemToken != current.systemToken || pageId != current.pageId) merged.copy(pageToken = null, pageName = null, instagramAccountId = null) else merged
    }

    fun log(tenant: Connection, event: String, detail: String = "") {
        tenant.prepareStatement("INSERT INTO crm_meta_log (id, event, detail) VALUES (?, ?, ?)").use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, event.take(40))
            insert.setString(3, detail.take(300))
            insert.executeUpdate()
        }
        tenant.prepareStatement("DELETE FROM crm_meta_log WHERE id NOT IN (SELECT id FROM crm_meta_log ORDER BY created_at DESC LIMIT 30)").use { it.executeUpdate() }
    }

    data class LogEntry(val event: String, val detail: String, val at: Instant)

    fun recent(tenant: Connection): List<LogEntry> =
        tenant.prepareStatement("SELECT event, detail, created_at FROM crm_meta_log ORDER BY created_at DESC LIMIT 30").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(LogEntry(rows.getString(1), rows.getString(2), rows.getTimestamp(3).toInstant())) } }
        }

    // ---- Webhook ----

    /** GET verification: the challenge to answer, or null when the token does not match. */
    fun verify(settings: CrmMetaSettings, mode: String?, token: String?, challenge: String?): String? {
        val expected = settings.verifyToken ?: return null
        if (mode != "subscribe" || token == null || challenge == null || !challenge.matches(Regex("^[A-Za-z0-9_-]{1,255}$"))) return null
        return if (MessageDigest.isEqual(expected.toByteArray(), token.toByteArray())) challenge else null
    }

    /** X-Hub-Signature-256 = "sha256=" + HMAC-SHA256(raw body, App Secret), compared in constant time. */
    fun validSignature(appSecret: String?, header: String?, body: ByteArray): Boolean {
        if (appSecret.isNullOrBlank() || header == null || !header.startsWith("sha256=")) return false
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(appSecret.toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal(body).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(), header.removePrefix("sha256=").lowercase().toByteArray())
    }

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonElement?.truthy() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull?.isNotEmpty() == true) } == true
    private fun JsonObject.timestamp(key: String) = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }?.let {
        // WhatsApp sends seconds, Messenger milliseconds.
        if (it > 100_000_000_000L) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it)
    }

    /** Readable text of any WhatsApp message; "" for reactions, system and unsupported messages. */
    fun whatsAppText(message: JsonObject): String {
        val type = message["type"].str()
        return when (type) {
            "text" -> message["text"].obj()?.get("body").str()
            "button" -> message["button"].obj()?.get("text").str()
            "interactive" -> message["interactive"].obj()?.let { it["button_reply"].obj()?.get("title").str().ifEmpty { it["list_reply"].obj()?.get("title").str() } }.orEmpty()
            "image", "video", "document", "audio", "sticker" -> {
                val label = mapOf("image" to "Imagen", "video" to "Video", "document" to "Documento", "audio" to "Audio", "sticker" to "Sticker").getValue(type)
                val media = message[type].obj()
                val name = media?.get("filename").str()
                "[$label de WhatsApp${if (name.isNotEmpty()) ": $name" else ""}] ${media?.get("caption").str()}".trim()
            }
            "location" -> message["location"].obj()?.let { location ->
                val name = location["name"].str()
                "[Ubicacion: ${location["latitude"].str()}, ${location["longitude"].str()}${if (name.isNotEmpty()) " - $name" else ""}]"
            }.orEmpty()
            "contacts" -> "[Contacto compartido por WhatsApp]"
            "reaction", "unsupported", "system", "" -> ""
            else -> "[Mensaje de WhatsApp: $type]"
        }
    }

    /**
     * Events of a webhook body: WhatsApp messages and echoes (object whatsapp_business_account) and
     * Messenger / Instagram messages (object page / instagram, in messaging, standby or changes).
     * Echoes of the page, deleted and unsupported messages and the company's own Instagram account
     * are dropped.
     */
    fun parse(body: String, ownInstagramId: String?): List<MetaEvent> {
        var payload = runCatching { json.parseToJsonElement(body) }.getOrNull().obj() ?: return emptyList()
        // Some Meta tests send {"field":"messages","value":{...}} without the envelope.
        if (payload["field"].str() == "messages" && payload["value"] is JsonObject) {
            payload = buildJsonObject {
                put("object", "page")
                put("entry", JsonArray(listOf(JsonObject(mapOf("changes" to JsonArray(listOf(payload)))))))
            }
        }
        val events = mutableListOf<MetaEvent>()
        when (val kind = payload["object"].str()) {
            "whatsapp_business_account" -> payload["entry"].arr().forEach { entry ->
                entry.obj()?.get("changes").arr().mapNotNull { it.obj() }.forEach { change ->
                    val value = change["value"].obj() ?: return@forEach
                    val number = value["metadata"].obj()?.get("phone_number_id").str()
                    when (change["field"].str()) {
                        "messages" -> {
                            val names = value["contacts"].arr().mapNotNull { it.obj() }.associate { it["wa_id"].str() to it["profile"].obj()?.get("name").str() }
                            value["messages"].arr().mapNotNull { it.obj() }.forEach { message ->
                                val from = message["from"].str().filter { it.isDigit() }
                                val text = whatsAppText(message)
                                if (from.isNotEmpty() && text.isNotEmpty()) {
                                    events += MetaEvent.WhatsAppMessage(number, from, names[from].orEmpty(), message["id"].str(), text, message.timestamp("timestamp"))
                                }
                            }
                        }
                        "smb_message_echoes" -> value["message_echoes"].arr().mapNotNull { it.obj() }.forEach { echo ->
                            val to = echo["to"].str().filter { it.isDigit() }
                            val text = whatsAppText(echo)
                            if (to.isNotEmpty() && text.isNotEmpty()) events += MetaEvent.WhatsAppEcho(number, to, echo["id"].str(), text, echo.timestamp("timestamp"))
                        }
                    }
                }
            }
            "page", "instagram" -> {
                val platform = if (kind == "instagram") "instagram" else "messenger"
                payload["entry"].arr().mapNotNull { it.obj() }.forEach { entry ->
                    val messaging = entry["messaging"].arr() + entry["standby"].arr()
                    val changes = entry["changes"].arr().mapNotNull { it.obj() }.filter { it["field"].str() in setOf("messages", "standby") }.mapNotNull { it["value"] }
                    (messaging + changes).mapNotNull { it.obj() }.forEach { event -> pageMessage(event, platform, ownInstagramId)?.let { events += it } }
                }
            }
        }
        return events
    }

    private fun pageMessage(event: JsonObject, platform: String, ownInstagramId: String?): MetaEvent.PageMessage? {
        val message = event["message"].obj() ?: return null
        if (message["is_echo"].truthy() || message["is_deleted"].truthy() || message["is_unsupported"].truthy()) return null
        val sender = event["sender"].obj()?.get("id").str()
        if (!numericId.matches(sender)) return null
        if (platform == "instagram" && sender == ownInstagramId) return null
        val label = if (platform == "instagram") "Instagram" else "Messenger"
        var text = message["text"].str()
        if (text.isEmpty() && message["attachments"] is JsonArray) {
            val types = message["attachments"].arr().mapNotNull { it.obj()?.get("type").str().takeIf { type -> type.isNotEmpty() } }.distinct()
            text = "[Adjunto de $label: ${types.ifEmpty { listOf("archivo") }.joinToString()}]"
        }
        if (platform == "instagram" && message["reply_to"].obj()?.get("story") != null) text = "[Respuesta a una historia de Instagram] $text".trim()
        if (text.isEmpty()) return null
        return MetaEvent.PageMessage(platform, sender, message["mid"].str(), text, event.timestamp("timestamp"))
    }

    // Leads that reach the company from an Encuentra24 listing keep Encuentra24 as their origin.
    fun source(text: String, channelLabel: String): String =
        if (Regex("(?i)\\bencuentra24\\.com\\b").containsMatchIn(text)) "Encuentra24 ($channelLabel)" else channelLabel

    /** Stores the events of one webhook call. Returns how many messages were stored. */
    fun handle(tenant: Connection, settings: CrmMetaSettings, events: List<MetaEvent>, onNewLead: (UUID) -> Unit): Int {
        var stored = 0
        events.forEach { event ->
            when (event) {
                is MetaEvent.WhatsAppMessage -> {
                    if (!settings.phoneNumberId.isNullOrBlank() && event.phoneNumberId.isNotEmpty() && event.phoneNumberId != settings.phoneNumberId) {
                        log(tenant, "wa_ignorado", "otro numero (${event.phoneNumberId})")
                        return@forEach
                    }
                    val result = CrmInbox.receive(
                        tenant,
                        CrmInbound(
                            channel = "whatsapp", name = event.name, phone = "+${event.from}", message = event.text,
                            messageId = event.id.ifEmpty { null }, source = source(event.text, "WhatsApp"), sentAt = event.at,
                        ),
                        onNewLead,
                    )
                    log(tenant, "wa_${resultName(result)}", "+${event.from}")
                    if (result is CrmIntakeResult.Created || result is CrmIntakeResult.Updated) stored++
                }
                is MetaEvent.WhatsAppEcho -> {
                    if (!settings.recordEchoes) return@forEach
                    // Echoes never create leads: they join the open lead of that phone.
                    val lead = leadByPhone(tenant, "+${event.to}")
                    if (lead == null) {
                        log(tenant, "wa_eco_sin_lead", "+${event.to}")
                        return@forEach
                    }
                    CrmInbox.addMessage(tenant, lead, "out", "whatsapp", "", event.text, event.id.ifEmpty { null }, "sent", "", null, "WhatsApp Business", event.at)
                    stored++
                }
                is MetaEvent.PageMessage -> {
                    val name = senderName(settings, event.senderId, event.platform)
                    val label = if (event.platform == "instagram") "Instagram" else "Messenger"
                    val result = CrmInbox.receive(
                        tenant,
                        CrmInbound(
                            channel = event.platform, name = name, identity = event.senderId, message = event.text,
                            messageId = event.id.ifEmpty { null }, source = source(event.text, label), sentAt = event.at,
                        ),
                        onNewLead,
                    )
                    log(tenant, "${event.platform}_${resultName(result)}", event.senderId)
                    if (result is CrmIntakeResult.Created || result is CrmIntakeResult.Updated) stored++
                }
            }
        }
        return stored
    }

    private fun resultName(result: CrmIntakeResult) = when (result) {
        is CrmIntakeResult.Created -> "lead_nuevo"
        is CrmIntakeResult.Updated -> "mensaje"
        CrmIntakeResult.Duplicate -> "repetido"
        is CrmIntakeResult.Blocked -> "spam"
        is CrmIntakeResult.Ignored -> "ignorado"
    }

    private fun leadByPhone(tenant: Connection, phone: String): UUID? = tenant.prepareStatement(
        """
        SELECT l.id FROM crm_leads l
        WHERE l.closed_at IS NULL AND l.customer_id IN (
            SELECT customer_id FROM crm_identities WHERE channel = 'phone' AND external_id = ?
            UNION SELECT id FROM customers WHERE primary_phone = ?
        )
        ORDER BY l.updated_at DESC LIMIT 1
        """.trimIndent()
    ).use { query ->
        query.setString(1, phone)
        query.setString(2, phone)
        query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    // ---- Graph API ----

    private fun request(url: String, token: String, method: String = "GET", body: JsonObject? = null): Pair<Int, JsonObject> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (failure: Exception) {
            throw CrmMetaException("No se pudo contactar la API de Meta.")
        }
        val parsed = runCatching { json.parseToJsonElement(response.body()) }.getOrNull().obj() ?: JsonObject(emptyMap())
        return response.statusCode() to parsed
    }

    private fun errorCode(body: JsonObject) = body["error"].obj()?.get("code").str().toIntOrNull() ?: 0
    private fun errorMessage(body: JsonObject) = body["error"].obj()?.get("message").str()

    /**
     * Reads the page token (it does not expire) from the system user token: the page with the
     * saved page ID, or the only page the user manages. Also reads the linked Instagram account.
     */
    fun connectPage(settings: CrmMetaSettings): CrmMetaSettings {
        val token = settings.systemToken ?: throw CrmMetaException("Guarda primero el token del usuario del sistema.")
        val (status, body) = request("$Graph/me/accounts?fields=id,name,access_token,instagram_business_account&limit=100", token)
        if (status != 200) throw CrmMetaException("Meta rechazo la lista de paginas: ${graphHint(body, status)}")
        val pages = body["data"].arr().mapNotNull { it.obj() }
        val page = when {
            settings.pageId != null -> pages.firstOrNull { it["id"].str() == settings.pageId }
                ?: throw CrmMetaException("El token no administra la pagina ${settings.pageId}.")
            pages.size == 1 -> pages.single()
            pages.isEmpty() -> throw CrmMetaException("El token no administra ninguna pagina; asignale la pagina al usuario del sistema.")
            else -> throw CrmMetaException("El token administra ${pages.size} paginas: escribe el ID de la pagina (${pages.joinToString { "${it["name"].str()} ${it["id"].str()}" }}).")
        }
        val pageToken = page["access_token"].str()
        if (pageToken.isEmpty()) throw CrmMetaException("Meta no devolvio el token de la pagina; revisa el permiso pages_messaging.")
        return settings.copy(
            pageId = page["id"].str(),
            pageName = page["name"].str(),
            pageToken = pageToken,
            instagramAccountId = page["instagram_business_account"].obj()?.get("id").str().ifEmpty { null },
        )
    }

    data class WhatsAppNumber(val id: String, val number: String, val name: String, val status: String)

    /** Subscribes the app to the WhatsApp Business Account and lists its numbers. */
    fun connectWhatsApp(settings: CrmMetaSettings): List<WhatsAppNumber> {
        val waba = settings.wabaId ?: throw CrmMetaException("Guarda primero el WhatsApp Business Account ID.")
        val token = settings.systemToken ?: throw CrmMetaException("Guarda primero el token del usuario del sistema.")
        val (subStatus, subBody) = request("$Graph/$waba/subscribed_apps", token, "POST")
        if (subStatus !in 200..299 || subBody["success"].str() != "true") throw CrmMetaException("Meta rechazo la suscripcion de la app: ${graphHint(subBody, subStatus)}")
        val (status, body) = request("$Graph/$waba/phone_numbers?fields=id,display_phone_number,verified_name,status", token)
        if (status != 200) throw CrmMetaException("Meta rechazo la lista de numeros: ${graphHint(body, status)}")
        return body["data"].arr().mapNotNull { it.obj() }.map {
            WhatsAppNumber(it["id"].str(), it["display_phone_number"].str(), it["verified_name"].str(), it["status"].str())
        }.filter { numericId.matches(it.id) }
    }

    private fun graphHint(body: JsonObject, status: Int) = when (errorCode(body)) {
        190 -> "el token es invalido o vencio."
        10, 100, 200 -> "${errorMessage(body)} - revisa que el usuario del sistema tenga la pagina o la cuenta de WhatsApp y sus permisos."
        else -> errorMessage(body).ifEmpty { "HTTP $status" }
    }

    // Public name of a Messenger / Instagram sender; "" when the page token cannot read it.
    private fun senderName(settings: CrmMetaSettings, senderId: String, platform: String): String {
        val token = settings.pageToken ?: return ""
        val fields = if (platform == "instagram") "name,username" else "first_name,last_name,name"
        return try {
            val (status, body) = request("$Graph/$senderId?fields=$fields", token)
            if (status != 200) return ""
            body["name"].str().ifEmpty { body["username"].str().takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "${body["first_name"].str()} ${body["last_name"].str()}".trim() }
        } catch (failure: CrmMetaException) {
            ""
        }
    }

    /** Sends a WhatsApp text; returns the wamid. Meta errors become clear messages for the agent. */
    fun sendWhatsApp(settings: CrmMetaSettings, toDigits: String, text: String): String {
        val body = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", toDigits)
            put("type", "text")
            putJsonObject("text") {
                put("preview_url", false)
                put("body", text.take(4096))
            }
        }
        val (status, response) = request("$Graph/${settings.phoneNumberId}/messages", settings.systemToken.orEmpty(), "POST", body)
        response["messages"].arr().firstOrNull().obj()?.get("id").str().takeIf { status in 200..299 && it.isNotEmpty() }?.let { return it }
        throw CrmMetaException(
            when (errorCode(response)) {
                131047 -> "Pasaron mas de 24 horas desde el ultimo mensaje del cliente: WhatsApp solo permite una plantilla aprobada. Responde desde la app de WhatsApp Business o espera a que escriba."
                131026 -> "El numero del lead no puede recibir este mensaje (puede que no tenga WhatsApp)."
                131030 -> "El numero del lead no esta permitido para esta cuenta de WhatsApp."
                190 -> "El token de Meta Business es invalido o vencio; actualizalo en Integraciones."
                10, 200 -> "El token no tiene permiso para enviar mensajes desde este numero."
                else -> "WhatsApp rechazo el mensaje: ${errorMessage(response).ifEmpty { "HTTP $status" }}"
            },
        )
    }

    // Splits a text in parts of up to [limit] characters, cutting at a line break or space when possible.
    fun split(text: String, limit: Int): List<String> {
        val parts = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > limit) {
            val chunk = rest.take(limit)
            val cut = maxOf(chunk.lastIndexOf('\n'), chunk.lastIndexOf(' ')).takeIf { it >= limit / 2 } ?: limit
            parts += rest.take(cut).trimEnd()
            rest = rest.drop(cut).trimStart()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    /**
     * Sends a Messenger or Instagram text with the Send API (2000 / 1000 characters per part). When
     * another app (Business Suite inbox) controls the thread, takes control once and retries.
     */
    fun sendPage(settings: CrmMetaSettings, recipient: String, text: String, platform: String): String {
        val token = settings.pageToken ?: throw CrmMetaException("Conecta la pagina de Facebook en Integraciones.")
        val label = if (platform == "instagram") "Instagram" else "Messenger"
        var first = ""
        var tookControl = false
        split(text, if (platform == "instagram") 1000 else 2000).forEach { part ->
            val payload = buildJsonObject {
                putJsonObject("recipient") { put("id", recipient) }
                put("messaging_type", "RESPONSE")
                putJsonObject("message") { put("text", part) }
            }
            var (status, body) = request("$Graph/me/messages", token, "POST", payload)
            val controlledElsewhere = errorCode(body) == 10 &&
                (body["error"].obj()?.get("error_subcode").str() == "2018300" || errorMessage(body).contains("controlling this thread", ignoreCase = true))
            if (!tookControl && controlledElsewhere) {
                tookControl = true
                val (takeStatus, takeBody) = request("$Graph/me/take_thread_control", token, "POST", buildJsonObject {
                    putJsonObject("recipient") { put("id", recipient) }
                    put("metadata", "Respuesta desde el CRM")
                })
                if (takeStatus !in 200..299 || takeBody["success"].str() != "true") {
                    throw CrmMetaException("Otra app (la bandeja de Meta Business Suite) controla esta conversacion y no se pudo tomar el control. Configura esta app como principal de la pagina o responde desde Business Suite.")
                }
                val retry = request("$Graph/me/messages", token, "POST", payload)
                status = retry.first
                body = retry.second
            }
            val id = body["message_id"].str()
            if (status !in 200..299 || id.isEmpty()) {
                throw CrmMetaException(
                    when (errorCode(body)) {
                        10, 200 -> "$label solo permite responder dentro de las 24 horas desde el ultimo mensaje del cliente."
                        190 -> "El token de la pagina es invalido o vencio; vuelve a conectar la pagina en Integraciones."
                        551 -> "Esta persona no esta disponible en $label."
                        else -> "$label rechazo el mensaje: ${errorMessage(body).ifEmpty { "HTTP $status" }}"
                    },
                )
            }
            if (first.isEmpty()) first = id
        }
        return first
    }
}

/** Reply channels of Meta. */
object CrmWhatsAppChannel : CrmChannel {
    override val id = "whatsapp"
    override val label = "WhatsApp"

    override fun unavailable(context: CrmChannelContext, lead: CrmLead): String? = when {
        !CrmMeta.load(context.tenant).whatsAppReady -> "Configura WhatsApp en Integraciones."
        CrmContacts.normalizePhone(lead.phone).isEmpty() -> "El lead no tiene un telefono valido."
        else -> null
    }

    override fun send(context: CrmChannelContext, lead: CrmLead, email: CrmEmail): String? = try {
        CrmMeta.sendWhatsApp(CrmMeta.load(context.tenant), CrmContacts.normalizePhone(lead.phone).removePrefix("+"), email.text)
    } catch (failure: CrmMetaException) {
        throw CrmChannelException(failure.message.orEmpty())
    }
}

class CrmPageChannel(override val id: String, override val label: String) : CrmChannel {
    override fun unavailable(context: CrmChannelContext, lead: CrmLead): String? = when {
        !CrmMeta.load(context.tenant).pageReady -> "Conecta la pagina de Facebook en Integraciones."
        CrmInbox.identity(context.tenant, lead.customerId, id) == null -> "El lead no ha escrito por $label."
        else -> null
    }

    override fun send(context: CrmChannelContext, lead: CrmLead, email: CrmEmail): String? {
        val recipient = CrmInbox.identity(context.tenant, lead.customerId, id) ?: throw CrmChannelException("El lead no ha escrito por $label.")
        return try {
            CrmMeta.sendPage(CrmMeta.load(context.tenant), recipient, email.text, id)
        } catch (failure: CrmMetaException) {
            throw CrmChannelException(failure.message.orEmpty())
        }
    }
}
