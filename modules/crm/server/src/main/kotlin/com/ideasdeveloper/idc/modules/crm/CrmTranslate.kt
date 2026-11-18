package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

// ---- HTTP behind an interface (tests use fakes) ----

data class CrmHttpReply(val status: Int, val body: String)

interface CrmHttp {
    fun post(url: String, headers: Map<String, String>, body: String): CrmHttpReply
}

/** java.net.http client: connect 10 s, request 25 s, no redirects. */
object CrmJavaHttp : CrmHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    override fun post(url: String, headers: Map<String, String>, body: String): CrmHttpReply {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return CrmHttpReply(response.statusCode(), response.body())
    }
}

class CrmTranslateException(message: String) : Exception(message)

/** Translates the text of a template. Receives text whose placeholders are already protected as tokens and must return the same structure. */
interface CrmTranslator {
    fun translate(subject: String, body: String, isHtml: Boolean, source: String, target: String): Pair<String, String>
}

data class CrmTranslationSettings(
    val provider: String = "",
    val apiKey: String = "",
    val model: String = "",
    // Key of the custom lead field that holds the guest's language.
    val languageField: String = "",
    // "Español" -> "es"
    val languageMap: Map<String, String> = emptyMap(),
) {
    val ready get() = provider.isNotEmpty() && apiKey.isNotBlank()
    val effectiveModel get() = model.ifBlank { CrmTranslate.DefaultModel }
}

/** Cache of translated templates (crm_template_translations). */
interface CrmTranslationCache {
    fun get(language: String, hash: String): Pair<String, String>?
    fun put(language: String, hash: String, subject: String, body: String)
}

/** Automatic translation of email templates into the guest's language (settings, providers, token protection, cache). */
object CrmTranslate {
    const val DefaultModel = "claude-haiku-5-5"
    const val NoField = "(ninguno)"
    val providerLabels = linkedMapOf("" to "Sin traduccion", "google" to "Google Cloud Translation", "anthropic" to "IA por API (Claude)")

    private val languageCode = Regex("^[a-z]{2}(-[A-Za-z]{2,4})?$")
    private val token = Regex("⟦(\\d+)⟧")
    private val tagName = Regex("<(/?)([a-zA-Z][a-zA-Z0-9-]*)")
    private val json = Json { ignoreUnknownKeys = true }

    fun isLanguageCode(value: String) = languageCode.matches(value)

    /** "EN", "en_us", "en-US" -> "en", "en", "en-US"; null when it is not a language code. */
    fun normalizeLanguage(value: String): String? {
        val parts = value.trim().replace('_', '-').split('-')
        if (parts.size !in 1..2) return null
        val primary = parts[0].lowercase()
        val normalized = if (parts.size == 2) "$primary-${parts[1]}" else primary
        return normalized.takeIf { isLanguageCode(it) }
    }

    fun sameLanguage(a: String, b: String) = a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)

    /** Language code of the guest from the value of the language field: the map first, else the value itself when it is a code. */
    fun resolveLanguage(value: String?, map: Map<String, String>): String? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        map.entries.firstOrNull { it.key.trim().equals(text, ignoreCase = true) }?.let { return normalizeLanguage(it.value) }
        return normalizeLanguage(text)
    }

    /** Hash of what is translated: editing the subject, the body or the base language changes it. */
    fun contentHash(subject: String, body: String, baseLanguage: String): String =
        MessageDigest.getInstance("SHA-256").digest("$baseLanguage\u0000$subject\u0000$body".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---- Settings ----

    fun load(tenant: Connection): CrmTranslationSettings = tenant.prepareStatement(
        "SELECT translation_provider, translation_api_key, translation_model, language_field, language_map::text FROM crm_settings WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            if (rows.next()) {
                val map = runCatching { json.parseToJsonElement(rows.getString(5)).jsonObject.mapValues { it.value.jsonPrimitive.content } }.getOrDefault(emptyMap())
                CrmTranslationSettings(rows.getString(1), CrmSecrets.open(tenant, rows.getString(2)).orEmpty(), rows.getString(3), rows.getString(4), map)
            } else {
                CrmTranslationSettings()
            }
        }
    }

    fun save(tenant: Connection, settings: CrmTranslationSettings) {
        val map = buildJsonObject { settings.languageMap.forEach { (name, code) -> put(name, code) } }.toString()
        tenant.prepareStatement(
            """
            INSERT INTO crm_settings (id, translation_provider, translation_api_key, translation_model, language_field, language_map)
            VALUES (1, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (id) DO UPDATE SET translation_provider = EXCLUDED.translation_provider, translation_api_key = EXCLUDED.translation_api_key,
                translation_model = EXCLUDED.translation_model, language_field = EXCLUDED.language_field, language_map = EXCLUDED.language_map,
                updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { update ->
            update.setString(1, settings.provider)
            update.setString(2, CrmSecrets.seal(tenant, settings.apiKey).orEmpty())
            update.setString(3, settings.model)
            update.setString(4, settings.languageField)
            update.setString(5, map)
            update.executeUpdate()
        }
    }

    /** Lead fields that can hold the guest's language: text and lists. */
    fun languageFields(fields: List<CrmField>) = fields.filter { it.type == CrmFieldType.Text || it.type == CrmFieldType.Dropdown }

    fun toFormValues(settings: CrmTranslationSettings, fields: List<CrmField>): Map<String, String> = mapOf(
        "provider" to (providerLabels[settings.provider] ?: providerLabels.getValue("")),
        "apiKey" to CrmSecrets.mask(settings.apiKey),
        "model" to settings.effectiveModel,
        "languageField" to (languageFields(fields).firstOrNull { it.key == settings.languageField }?.label ?: NoField),
        "languageMap" to formatLanguageMap(settings.languageMap),
    )

    fun formatLanguageMap(map: Map<String, String>) = map.entries.joinToString("\n") { "${it.key} = ${it.value}" }

    /** Lines "Valor = codigo" (blank lines are ignored). */
    fun parseLanguageMap(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val name = line.substringBefore('=', "").trim()
            val code = normalizeLanguage(line.substringAfter('=', ""))
            if (!line.contains('=') || name.isEmpty() || name.length > 80 || code == null) {
                throw CrmValidationException("Cada linea de equivalencias va como Valor = codigo (por ejemplo Ingles = en). Revisa: $line")
            }
            result[name] = code
        }
        if (result.size > 100) throw CrmValidationException("Maximo 100 equivalencias de idioma.")
        return result
    }

    /** Applies the form; a blank or masked key keeps the saved one. */
    fun merge(current: CrmTranslationSettings, values: Map<String, String>, fields: List<CrmField>): CrmTranslationSettings {
        val provider = providerLabels.entries.firstOrNull { it.value == values["provider"] }?.key ?: current.provider
        val key = values["apiKey"]?.trim()?.takeIf { it.isNotEmpty() && !CrmSecrets.isPlaceholder(it) } ?: current.apiKey
        val model = values["model"].orEmpty().trim().takeIf { it != DefaultModel }.orEmpty()
        if (model.length > 100 || !model.all { it.isLetterOrDigit() || it in "-._:/" }) throw CrmValidationException("El modelo de IA no es valido.")
        if (provider.isNotEmpty() && key.isBlank()) throw CrmValidationException("Escribe la clave de API del traductor.")
        val field = languageFields(fields).firstOrNull { it.label == values["languageField"] }?.key.orEmpty()
        return CrmTranslationSettings(provider, key, model, field, parseLanguageMap(values["languageMap"].orEmpty()))
    }

    // ---- Negative cache: after a failure the translator is not called again for [Cooldown] ----

    val Cooldown: Duration = Duration.ofMinutes(10)
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    // One entry per provider and key (a company); the key itself is never stored.
    private fun cooldownKey(settings: CrmTranslationSettings) = "${settings.provider}:${settings.apiKey.hashCode()}"

    fun markFailed(settings: CrmTranslationSettings, now: Instant = Instant.now()) {
        failures[cooldownKey(settings)] = now
    }

    fun markWorking(settings: CrmTranslationSettings) {
        failures.remove(cooldownKey(settings))
    }

    fun coolingDown(settings: CrmTranslationSettings, now: Instant = Instant.now()): Boolean =
        failures[cooldownKey(settings)]?.let { now.isBefore(it.plus(Cooldown)) } ?: false

    fun translator(settings: CrmTranslationSettings, http: CrmHttp): CrmTranslator? = when {
        !settings.ready -> null
        settings.provider == "google" -> GoogleTranslator(http, settings.apiKey)
        settings.provider == "anthropic" -> AnthropicTranslator(http, settings.apiKey, settings.effectiveModel)
        else -> null
    }

    // ---- Protecting placeholders ----

    /** Replaces each {placeholder} with an opaque token ⟦n⟧; [found] collects the originals in order. */
    fun protect(text: String, found: MutableList<String>): String =
        CrmMail.placeholder.replace(text) { match ->
            found += match.value
            "⟦${found.size - 1}⟧"
        }

    /** Puts the original placeholders back; null when a token is unknown. */
    fun restore(text: String, found: List<String>): String? {
        var unknown = false
        val restored = token.replace(text) { match ->
            val original = found.getOrNull(match.groupValues[1].toInt())
            if (original == null) unknown = true
            original ?: ""
        }
        return restored.takeUnless { unknown }
    }

    private fun tokens(text: String) = token.findAll(text).map { it.value }.sorted().toList()
    private fun tags(text: String) = tagName.findAll(text).map { it.groupValues[1] + it.groupValues[2].lowercase() }.sorted().toList()

    /** The translation keeps every token and the same HTML tags (same names and counts) as the source. */
    fun isValid(source: String, translated: String) = translated.isNotBlank() && tokens(source) == tokens(translated) && tags(source) == tags(translated)

    /**
     * Translates [subject] and [body] with [translator]: placeholders are protected first, so the translator never sees
     * lead data, and the result is checked. Throws [CrmTranslateException] when the answer cannot be trusted.
     */
    fun translateTemplate(translator: CrmTranslator, subject: String, body: String, source: String, target: String): Pair<String, String> {
        val found = mutableListOf<String>()
        val protectedSubject = protect(subject, found)
        val protectedBody = protect(body, found)
        val isHtml = Regex("<[a-zA-Z][^>]*>").containsMatchIn(body)
        val (translatedSubject, translatedBody) = translator.translate(protectedSubject, protectedBody, isHtml, source, target)
        if (!isValid(protectedSubject, translatedSubject) || !isValid(protectedBody, translatedBody)) {
            throw CrmTranslateException("la traduccion cambio las variables o el formato")
        }
        val subjectBack = restore(translatedSubject, found)
        val bodyBack = restore(translatedBody, found)
        if (subjectBack == null || bodyBack == null) throw CrmTranslateException("la traduccion cambio las variables")
        // A translator must not invent variables of its own.
        if (CrmMail.placeholder.findAll(subjectBack + bodyBack).count() != CrmMail.placeholder.findAll(subject + body).count()) {
            throw CrmTranslateException("la traduccion cambio las variables")
        }
        return subjectBack.replace(Regex("[\\r\\n]+"), " ").trim() to bodyBack
    }

    // ---- Providers ----

    private fun httpError(provider: String, reply: CrmHttpReply): CrmTranslateException {
        val detail = runCatching { json.parseToJsonElement(reply.body).jsonObject["error"] }.getOrNull().let { error ->
            when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        }?.take(160)
        return CrmTranslateException("$provider respondio HTTP ${reply.status}${detail?.let { ": $it" }.orEmpty()}")
    }

    private fun decodeEntities(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    class GoogleTranslator(private val http: CrmHttp, private val apiKey: String) : CrmTranslator {
        override fun translate(subject: String, body: String, isHtml: Boolean, source: String, target: String): Pair<String, String> {
            fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
            // HTML mode: tokens inside the body are wrapped so Google leaves them alone; the subject goes as escaped text.
            val wrapped = token.replace(body) { "<span class=\"notranslate\">${it.value}</span>" }
            val form = listOf(
                "q" to if (isHtml) CrmMail.escape(subject) else subject,
                "q" to if (isHtml) wrapped else body,
                "source" to source.substringBefore('-'),
                "target" to target,
                "format" to if (isHtml) "html" else "text",
            ).joinToString("&") { "${it.first}=${enc(it.second)}" }
            val reply = try {
                http.post(
                    // The key travels in a header, never in the URL (URLs end up in logs).
                    "https://translation.googleapis.com/language/translate/v2",
                    mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json", "X-goog-api-key" to apiKey),
                    form,
                )
            } catch (failure: Exception) {
                throw CrmTranslateException("no se pudo contactar a Google")
            }
            if (reply.status !in 200..299) throw httpError("Google", reply)
            val translations = runCatching { json.parseToJsonElement(reply.body).jsonObject["data"]!!.jsonObject["translations"]!!.jsonArray.map { it.jsonObject["translatedText"]!!.jsonPrimitive.content } }
                .getOrNull()?.takeIf { it.size == 2 } ?: throw CrmTranslateException("Google respondio algo inesperado")
            val translatedBody = Regex("<span[^>]*>\\s*(⟦\\d+⟧)\\s*</span>").replace(translations[1]) { it.groupValues[1] }
            return decodeEntities(translations[0]) to if (isHtml) translatedBody else decodeEntities(translatedBody)
        }
    }

    class AnthropicTranslator(private val http: CrmHttp, private val apiKey: String, private val model: String) : CrmTranslator {
        override fun translate(subject: String, body: String, isHtml: Boolean, source: String, target: String): Pair<String, String> {
            val system = "You are a translation engine for email templates. Translate from language code '$source' to language code '$target'. " +
                "Translate only the human-readable text. Keep every HTML tag and attribute exactly as it is, in the same order. " +
                "Tokens like ⟦0⟧ are placeholders: copy each one unchanged, exactly once per occurrence, and never translate, remove, add or reorder them. " +
                "Do not add, remove or rephrase content, and do not add notes, explanations or code fences. " +
                "The input has the form <subject>...</subject><body>...</body>. Return exactly the same form with the translated text and nothing else."
            val payload = buildJsonObject {
                put("model", model)
                put("max_tokens", 4096)
                put("system", system)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("content", "<subject>$subject</subject><body>$body</body>") })
                })
            }.toString()
            val reply = try {
                http.post(
                    "https://api.anthropic.com/v1/messages",
                    mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "content-type" to "application/json"),
                    payload,
                )
            } catch (failure: Exception) {
                throw CrmTranslateException("no se pudo contactar a la IA")
            }
            if (reply.status !in 200..299) throw httpError("La IA", reply)
            val root = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: throw CrmTranslateException("la IA respondio algo inesperado")
            if (root["stop_reason"]?.jsonPrimitive?.contentOrNull == "max_tokens") throw CrmTranslateException("la plantilla es demasiado larga para la IA")
            val text = (root["content"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                ?: throw CrmTranslateException("la IA respondio algo inesperado")
            val subjectText = Regex("<subject>(.*?)</subject>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            val bodyText = Regex("<body>(.*)</body>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            if (subjectText == null || bodyText == null) throw CrmTranslateException("la IA no devolvio el formato pedido")
            return subjectText.trim() to bodyText
        }
    }

    // ---- Cache (crm_template_translations) ----

    fun cache(tenant: Connection, templateId: UUID): CrmTranslationCache = object : CrmTranslationCache {
        override fun get(language: String, hash: String): Pair<String, String>? = tenant.prepareStatement(
            "SELECT subject, body FROM crm_template_translations WHERE template_id = ? AND language = ? AND content_hash = ?"
        ).use { query ->
            query.setObject(1, templateId)
            query.setString(2, language)
            query.setString(3, hash)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
        }

        override fun put(language: String, hash: String, subject: String, body: String) {
            tenant.prepareStatement(
                "INSERT INTO crm_template_translations (template_id, language, content_hash, subject, body) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { insert ->
                insert.setObject(1, templateId)
                insert.setString(2, language)
                insert.setString(3, hash)
                insert.setString(4, subject)
                insert.setString(5, body)
                insert.executeUpdate()
            }
        }
    }

    fun clearCache(tenant: Connection, templateId: UUID) {
        tenant.prepareStatement("DELETE FROM crm_template_translations WHERE template_id = ?").use { it.setObject(1, templateId); it.executeUpdate() }
    }
}
