package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.BeforeTest
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmTemplateTranslationTest {
    private val map = mapOf("Español" to "es", "Inglés" to "en", "Francés" to "fr")
    private val settings = CrmTranslationSettings("anthropic", "key", "", "idioma", map)

    // The failure memory of the translator is global: every test starts with a working one.
    @BeforeTest
    fun forgetFailures() = CrmTranslate.markWorking(settings)

    @Test
    fun aFailedTranslatorIsNotCalledAgainFor10Minutes() {
        var calls = 0
        val broken = FakeTranslator { _, _ -> calls++; throw CrmTranslateException("caido") }
        CrmTemplateMailer.compose(template(), values, "Inglés", settings, broken, null)
        val second = CrmTemplateMailer.compose(template(), values, "Inglés", settings, broken, null)
        assertEquals(1, calls)
        assertFalse(second.translated)
        assertContains(second.note.orEmpty(), "fallo hace poco")
        val failedAt = java.time.Instant.parse("2026-12-01T10:00:00Z")
        CrmTranslate.markFailed(settings, failedAt)
        assertTrue(CrmTranslate.coolingDown(settings, failedAt.plusSeconds(599)))
        assertFalse(CrmTranslate.coolingDown(settings, failedAt.plusSeconds(600)))
        CrmTranslate.markWorking(settings)
        assertFalse(CrmTranslate.coolingDown(settings, failedAt))
    }

    private fun template(subject: String = "Hola {nombre}", body: String = "<p>Hola {nombre}, tu codigo es <b>{codigo_cliente}</b>.</p>", translate: Boolean = true, base: String = "es") =
        CrmTemplate(UUID.randomUUID(), "Bienvenida", subject, body, base, translate)

    private val values = mapOf("nombre" to "Ana <3", "codigo_cliente" to "A-1")

    // Fake translator: replaces words, keeps the protected tokens and the tags.
    private class FakeTranslator(
        val change: (String, String) -> Pair<String, String> = { s, b -> s.replace("Hola", "Hello") to b.replace("Hola", "Hello").replace("tu codigo es", "your code is") },
    ) : CrmTranslator {
        var calls = 0
        var lastSubject = ""
        override fun translate(subject: String, body: String, isHtml: Boolean, source: String, target: String): Pair<String, String> {
            calls++
            lastSubject = subject
            return change(subject, body)
        }
    }

    private class MemoryCache : CrmTranslationCache {
        val rows = mutableMapOf<Pair<String, String>, Pair<String, String>>()
        override fun get(language: String, hash: String) = rows[language to hash]
        override fun put(language: String, hash: String, subject: String, body: String) {
            rows[language to hash] = subject to body
        }
    }

    // ---- Variables ----

    @Test
    fun placeholdersAcceptCampoAndClienteKeys() {
        val text = "{nombre} {campo:cf_vuelo} {cliente:pais} {campo:no_existe} {cliente:otro} {raro}"
        val unknown = CrmMail.unknownPlaceholders(text, setOf("cf_vuelo"), setOf("pais"))
        assertEquals(setOf("campo:no_existe", "cliente:otro", "raro"), unknown)
        // Without definitions every dynamic key is reported.
        assertEquals(setOf("campo:cf_vuelo", "cliente:pais"), CrmMail.unknownPlaceholders("{campo:cf_vuelo} {cliente:pais} {nombre}"))
    }

    @Test
    fun dynamicValuesAreFormattedForDisplay() {
        val definitions = listOf(
            CrmField(UUID.randomUUID(), "cf_vuelo", "Vuelo", CrmFieldType.Text),
            CrmField(UUID.randomUUID(), "cf_vip", "VIP", CrmFieldType.Boolean),
            CrmField(UUID.randomUUID(), "cf_extras", "Extras", CrmFieldType.Dropdown, multiple = true),
            CrmField(UUID.randomUUID(), "cf_vacio", "Vacio", CrmFieldType.Text),
            CrmField(UUID.randomUUID(), "cf_archivos", "Archivos", CrmFieldType.Files),
        )
        val lead = JsonObject(
            mapOf(
                "cf_vuelo" to JsonPrimitive("AV 123"),
                "cf_vip" to JsonPrimitive(true),
                "cf_extras" to JsonArray(listOf(JsonPrimitive("Spa"), JsonPrimitive("Cena"))),
            ),
        )
        val customer = JsonObject(mapOf("pais" to JsonPrimitive("Panama")))
        val result = CrmTemplateMailer.dynamicValues(
            lead, definitions, customer,
            listOf(CustomerField("pais", "Pais", "text", emptyList()), CustomerField("ciudad", "Ciudad", "text", emptyList())),
        )
        assertEquals("AV 123", result["campo:cf_vuelo"])
        assertEquals("Si", result["campo:cf_vip"])
        assertEquals("Spa, Cena", result["campo:cf_extras"])
        assertEquals("", result["campo:cf_vacio"])
        assertFalse("campo:cf_archivos" in result)
        assertEquals("Panama", result["cliente:pais"])
        assertEquals("", result["cliente:ciudad"])
    }

    @Test
    fun dynamicValuesAreEscapedInHtmlBodies() {
        val mail = CrmMail.render("Vuelo {campo:cf_vuelo}", "<p>Vuelo {campo:cf_vuelo} de {cliente:pais}</p>", mapOf("campo:cf_vuelo" to "<b>X</b>", "cliente:pais" to "Panama"))
        assertContains(mail.html, "Vuelo &lt;b&gt;X&lt;/b&gt; de Panama")
        assertEquals("Vuelo <b>X</b>", mail.subject)
    }

    @Test
    fun variableHelpListsDefinitions() {
        val help = CrmTemplateMailer.variableHelp(
            listOf(CrmField(UUID.randomUUID(), "cf_vuelo", "Vuelo", CrmFieldType.Text)),
            listOf(CustomerField("pais", "Pais", "text", emptyList())),
        )
        assertContains(help, "{nombre} - ")
        assertContains(help, "{campo:cf_vuelo} - Campo del lead: Vuelo")
        assertContains(help, "{cliente:pais} - Campo del cliente: Pais")
    }

    // ---- Language ----

    @Test
    fun languageResolvesThroughTheMapOrCodes() {
        assertEquals("en", CrmTranslate.resolveLanguage("Inglés", map))
        assertEquals("en", CrmTranslate.resolveLanguage("  inglés ", map))
        assertEquals("fr", CrmTranslate.resolveLanguage("fr", map))
        assertEquals("en-US", CrmTranslate.resolveLanguage("EN_US", map))
        assertNull(CrmTranslate.resolveLanguage("Klingon", map))
        assertNull(CrmTranslate.resolveLanguage("", map))
        assertNull(CrmTranslate.resolveLanguage(null, map))
        assertTrue(CrmTranslate.sameLanguage("en-US", "en"))
        assertFalse(CrmTranslate.sameLanguage("fr", "es"))
    }

    @Test
    fun languageMapParsesLines() {
        val parsed = CrmTranslate.parseLanguageMap("Español = es\n\nInglés=EN\n")
        assertEquals(mapOf("Español" to "es", "Inglés" to "en"), parsed)
        assertEquals(parsed, CrmTranslate.parseLanguageMap(CrmTranslate.formatLanguageMap(parsed)))
        assertFailsWith<CrmValidationException> { CrmTranslate.parseLanguageMap("Ingles en") }
        assertFailsWith<CrmValidationException> { CrmTranslate.parseLanguageMap("Ingles = english") }
    }

    @Test
    fun cacheKeyChangesWithTheContent() {
        val a = CrmTranslate.contentHash("Asunto", "Cuerpo", "es")
        assertEquals(a, CrmTranslate.contentHash("Asunto", "Cuerpo", "es"))
        assertEquals(64, a.length)
        assertNotEquals(a, CrmTranslate.contentHash("Asunto", "Cuerpo 2", "es"))
        assertNotEquals(a, CrmTranslate.contentHash("Asunto 2", "Cuerpo", "es"))
        assertNotEquals(a, CrmTranslate.contentHash("Asunto", "Cuerpo", "en"))
        // The boundary between subject and body counts.
        assertNotEquals(CrmTranslate.contentHash("ab", "c", "es"), CrmTranslate.contentHash("a", "bc", "es"))
    }

    // ---- Protection and validation ----

    @Test
    fun protectionRoundTrips() {
        val found = mutableListOf<String>()
        val protectedText = CrmTranslate.protect("<p>Hola {nombre}, {campo:cf_vuelo} y {nombre}</p>", found)
        assertEquals("<p>Hola ⟦0⟧, ⟦1⟧ y ⟦2⟧</p>", protectedText)
        assertEquals(listOf("{nombre}", "{campo:cf_vuelo}", "{nombre}"), found)
        assertEquals("<p>Hola {nombre}, {campo:cf_vuelo} y {nombre}</p>", CrmTranslate.restore(protectedText, found))
        assertNull(CrmTranslate.restore("Hello ⟦9⟧", found))
    }

    @Test
    fun validationRejectsChangedTokensOrTags() {
        val source = "<p>Hola ⟦0⟧ <b>amigo</b></p>"
        assertTrue(CrmTranslate.isValid(source, "<p>Hello ⟦0⟧ <b>friend</b></p>"))
        assertFalse(CrmTranslate.isValid(source, "<p>Hello <b>friend</b></p>"))
        assertFalse(CrmTranslate.isValid(source, "<p>Hello ⟦0⟧ ⟦0⟧ <b>friend</b></p>"))
        assertFalse(CrmTranslate.isValid(source, "<p>Hello ⟦0⟧ <i>friend</i></p>"))
        assertFalse(CrmTranslate.isValid(source, "<p>Hello ⟦0⟧ friend</p>"))
        assertFalse(CrmTranslate.isValid(source, "   "))
    }

    @Test
    fun translatorOnlySeesProtectedText() {
        val fake = FakeTranslator()
        val (subject, body) = CrmTranslate.translateTemplate(fake, "Hola {nombre}", "<p>Hola {nombre}, tu codigo es <b>{codigo_cliente}</b>.</p>", "es", "en")
        assertEquals("Hola ⟦0⟧", fake.lastSubject)
        assertEquals("Hello {nombre}", subject)
        assertEquals("<p>Hello {nombre}, your code is <b>{codigo_cliente}</b>.</p>", body)
    }

    @Test
    fun translationThatDropsAPlaceholderIsRejected() {
        val fake = FakeTranslator { s, b -> s to b.replace("⟦1⟧", "") }
        assertFailsWith<CrmTranslateException> { CrmTranslate.translateTemplate(fake, "Hola {nombre}", "<p>Hola {nombre}</p>", "es", "en") }
        val tagChange = FakeTranslator { s, b -> s to b.replace("<p>", "<div>").replace("</p>", "</div>") }
        assertFailsWith<CrmTranslateException> { CrmTranslate.translateTemplate(tagChange, "Hola", "<p>Hola</p>", "es", "en") }
    }

    // ---- Providers (fake HTTP) ----

    private class FakeHttp(val status: Int, val reply: String) : CrmHttp {
        var url = ""
        var headers: Map<String, String> = emptyMap()
        var sent = ""
        override fun post(url: String, headers: Map<String, String>, body: String): CrmHttpReply {
            this.url = url
            this.headers = headers
            sent = body
            return CrmHttpReply(status, reply)
        }
    }

    @Test
    fun googleSendsProtectedHtmlAndUnwrapsTokens() {
        val reply = """{"data":{"translations":[{"translatedText":"Hello &#39;⟦0⟧"},{"translatedText":"<p>Hello <span class=\"notranslate\">⟦1⟧</span></p>"}]}}"""
        val http = FakeHttp(200, reply)
        val (subject, body) = CrmTranslate.translateTemplate(CrmTranslate.GoogleTranslator(http, "abc"), "Hola {nombre}", "<p>Hola {nombre}</p>", "es", "en")
        assertEquals("https://translation.googleapis.com/language/translate/v2", http.url)
        assertEquals("abc", http.headers["X-goog-api-key"])
        assertContains(http.sent, "format=html")
        assertContains(http.sent, "source=es")
        assertContains(http.sent, "target=en")
        assertFalse(http.sent.contains("nombre"), "the placeholder never reaches the translator")
        assertEquals("Hello '{nombre}", subject)
        assertEquals("<p>Hello {nombre}</p>", body)
    }

    @Test
    fun googleErrorsBecomeFailures() {
        val http = FakeHttp(403, """{"error":{"message":"API key not valid"}}""")
        val failure = assertFailsWith<CrmTranslateException> { CrmTranslate.GoogleTranslator(http, "k").translate("Hola", "Hola", false, "es", "en") }
        assertContains(failure.message.orEmpty(), "403")
        assertContains(failure.message.orEmpty(), "API key not valid")
    }

    @Test
    fun anthropicUsesTheMessagesApi() {
        val http = FakeHttp(200, """{"stop_reason":"end_turn","content":[{"type":"text","text":"<subject>Hello ⟦0⟧</subject>\n<body><p>Hello ⟦1⟧</p></body>"}]}""")
        val translator = CrmTranslate.AnthropicTranslator(http, "secret", "claude-haiku-5-5")
        val (subject, body) = CrmTranslate.translateTemplate(translator, "Hola {nombre}", "<p>Hola {nombre}</p>", "es", "en")
        assertEquals("https://api.anthropic.com/v1/messages", http.url)
        assertEquals("secret", http.headers["x-api-key"])
        assertEquals("2023-06-01", http.headers["anthropic-version"])
        assertContains(http.sent, "claude-haiku-5-5")
        assertContains(http.sent, "<subject>Hola ⟦0⟧</subject><body><p>Hola ⟦1⟧</p></body>")
        assertEquals("Hello {nombre}", subject)
        assertEquals("<p>Hello {nombre}</p>", body)
    }

    @Test
    fun anthropicOutputWithoutTheFormatFails() {
        val http = FakeHttp(200, """{"stop_reason":"end_turn","content":[{"type":"text","text":"Here is your translation"}]}""")
        assertFailsWith<CrmTranslateException> { CrmTranslate.AnthropicTranslator(http, "k", "m").translate("a", "b", false, "es", "en") }
        val cut = FakeHttp(200, """{"stop_reason":"max_tokens","content":[{"type":"text","text":"<subject>a</subject><body>b"}]}""")
        assertFailsWith<CrmTranslateException> { CrmTranslate.AnthropicTranslator(cut, "k", "m").translate("a", "b", false, "es", "en") }
    }

    // ---- compose ----

    @Test
    fun translatesToTheGuestLanguageAndCaches() {
        val fake = FakeTranslator()
        val cache = MemoryCache()
        val template = template()
        val first = CrmTemplateMailer.compose(template, values, "Inglés", settings, fake, cache)
        assertTrue(first.translated)
        assertEquals("en", first.language)
        assertNull(first.note)
        assertEquals("Hello Ana <3", first.subject)
        assertContains(first.html, "Hello Ana &lt;3, your code is <b>A-1</b>.")
        assertEquals(1, cache.rows.size)
        // Second send: from the cache, the translator is not called again, and values are still the lead's.
        val second = CrmTemplateMailer.compose(template, mapOf("nombre" to "Luis", "codigo_cliente" to "B-2"), "Inglés", settings, fake, cache)
        assertEquals(1, fake.calls)
        assertEquals("Hello Luis", second.subject)
        // Editing the template does not use the old cache entry.
        CrmTemplateMailer.compose(template.copy(body = template.body + " "), values, "Inglés", settings, fake, cache)
        assertEquals(2, fake.calls)
    }

    @Test
    fun doesNotTranslateWithoutNeed() {
        val fake = FakeTranslator()
        val template = template()
        listOf("Español", "es", "", null, "Klingon").forEach { language ->
            val mail = CrmTemplateMailer.compose(template, values, language, settings, fake, null)
            assertFalse(mail.translated)
            assertNull(mail.note)
            assertEquals("es", mail.language)
            assertEquals("Hola Ana <3", mail.subject)
        }
        assertFalse(CrmTemplateMailer.compose(template(translate = false), values, "Inglés", settings, fake, null).translated)
        assertEquals(0, fake.calls)
    }

    @Test
    fun failedTranslationSendsTheOriginalWithANote() {
        val broken = FakeTranslator { _, _ -> throw CrmTranslateException("Google respondio HTTP 500") }
        val mail = CrmTemplateMailer.compose(template(), values, "Inglés", settings, broken, MemoryCache())
        assertFalse(mail.translated)
        assertEquals("No se pudo traducir: Google respondio HTTP 500", mail.note)
        assertEquals("Hola Ana <3", mail.subject)
        assertEquals("es", mail.language)

        val invalid = FakeTranslator { s, b -> s to b.replace("⟦1⟧", "") }
        assertContains(CrmTemplateMailer.compose(template(), values, "Inglés", settings, invalid, null).note.orEmpty(), "No se pudo traducir")

        val noProvider = CrmTemplateMailer.compose(template(), values, "Inglés", CrmTranslationSettings(languageMap = map), null, null)
        assertContains(noProvider.note.orEmpty(), "No se pudo traducir")
        assertEquals("Hola Ana <3", noProvider.subject)
    }

    @Test
    fun settingsMergeKeepsMaskedKey() {
        val field = CrmField(UUID.randomUUID(), "idioma", "Idioma", CrmFieldType.Dropdown)
        val current = CrmTranslationSettings("google", "saved-key", "", "idioma", map)
        val merged = CrmTranslate.merge(
            current,
            mapOf("provider" to "Google Cloud Translation", "apiKey" to CrmMail.SecretMask, "languageField" to "Idioma", "languageMap" to "Inglés = en"),
            listOf(field),
        )
        assertEquals("saved-key", merged.apiKey)
        assertEquals("idioma", merged.languageField)
        assertEquals(mapOf("Inglés" to "en"), merged.languageMap)
        assertFailsWith<CrmValidationException> {
            CrmTranslate.merge(CrmTranslationSettings(), mapOf("provider" to "Google Cloud Translation", "apiKey" to ""), listOf(field))
        }
        val form = CrmTranslate.toFormValues(merged, listOf(field))
        assertEquals(CrmMail.SecretMask, form["apiKey"])
        assertEquals(CrmTranslate.DefaultModel, form["model"])
    }
}
