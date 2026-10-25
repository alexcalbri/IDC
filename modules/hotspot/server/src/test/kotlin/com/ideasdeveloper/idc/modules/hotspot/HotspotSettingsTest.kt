package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.SmtpSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotspotSettingsTest {
    private val defaults = HotspotSettings(
        domain = null, networkName = "WiFi", portalTitle = "WiFi", portalSubtitle = "", logoUrl = null,
        primaryColor = "#E4002B", textColor = "#FFFFFF", consentText = "Acepto", termsUrl = null, privacyUrl = null,
        footerText = "", successUrl = null, unifiConsoleId = "console:1", unifiSiteId = "site-1", unifiApiKey = "secret",
        accessMinutes = 60, dataLimitMb = 0, rxKbps = 0, txKbps = 0, reconnectMinutes = 1440, codeTtlMinutes = 15,
        smtpMode = "servidor", smtpHost = null, smtpPort = 587, smtpUsername = null, smtpPassword = "smtp-secret",
        smtpFrom = null, smtpSecurity = "starttls", emailSubject = "Codigo",
    )

    private fun values(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun blankSecretsKeepStoredValues() {
        val merged = HotspotSettingsRepository.merge(defaults, values("unifiApiKey" to "", "smtpPassword" to ""))
        assertEquals("secret", merged.unifiApiKey)
        assertEquals("smtp-secret", merged.smtpPassword)
    }

    @Test
    fun unchangedMaskKeepsStoredValuesAndNewSecretReplacesThem() {
        val kept = HotspotSettingsRepository.merge(defaults, values("unifiApiKey" to "********", "smtpPassword" to "********"))
        assertEquals("secret", kept.unifiApiKey)
        assertEquals("smtp-secret", kept.smtpPassword)
        assertEquals("nueva", HotspotSettingsRepository.merge(defaults, values("unifiApiKey" to "nueva")).unifiApiKey)
    }

    @Test
    fun domainIsNormalizedAndBlankRemovesIt() {
        assertEquals("wifi.example.com", HotspotSettingsRepository.merge(defaults, values("domain" to "WiFi.Example.com.")).domain)
        val withDomain = defaults.copy(domain = "wifi.example.com")
        assertNull(HotspotSettingsRepository.merge(withDomain, values("domain" to "")).domain)
    }

    @Test
    fun rejectsInvalidValues() {
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("domain" to "wifi example.com")) }
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("domain" to "localhost")) }
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("primaryColor" to "red")) }
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("accessMinutes" to "0")) }
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("logoUrl" to "javascript:alert(1)")) }
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("unifiSiteId" to "../../x?y")) }
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("smtpMode" to "otro")) }
    }

    @Test
    fun formValuesNeverExposeSecrets() {
        val form = HotspotSettingsRepository.toFormValues(defaults, "Activo", serverMailAvailable = false)
        assertEquals("********", form["unifiApiKey"])
        assertEquals("********", form["smtpPassword"])
        val empty = HotspotSettingsRepository.toFormValues(defaults.copy(unifiApiKey = null), "Activo", serverMailAvailable = false)
        assertEquals("", empty["unifiApiKey"])
    }

    @Test
    fun companySmtpRequiresCompleteSettings() {
        val server = SmtpSettings("smtp.server", 587, "", "", "no-reply@server.test")
        assertEquals(server, defaults.smtp(server))
        assertNull(defaults.copy(smtpMode = "empresa").smtp(server))
        val company = defaults.copy(smtpMode = "empresa", smtpHost = "smtp.company", smtpFrom = "wifi@company.test")
        assertEquals("smtp.company", company.smtp(server)?.host)
    }

    @Test
    fun successUrlsAreACommaSeparatedList() {
        val merged = HotspotSettingsRepository.merge(defaults, values("successUrl" to " https://a.test ,, https://b.test/x?y=1 "))
        assertEquals("https://a.test, https://b.test/x?y=1", merged.successUrl)
        assertEquals(listOf("https://a.test", "https://b.test/x?y=1"), merged.successUrls)
        assertNull(HotspotSettingsRepository.merge(defaults, values("successUrl" to " , ")).successUrl)
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("successUrl" to "https://a.test, ftp://b.test")) }
    }

    @Test
    fun successPageRedirectsAndFormsSubmitOnce() {
        val success = HotspotPortalPages.success(defaults, "Ana", "https://a.test/?q=\"x\"")
        assertTrue(success.contains("http-equiv=\"refresh\" content=\"3;url=https://a.test/?q=&quot;x&quot;\""))
        assertFalse(HotspotPortalPages.success(defaults, "Ana", null).contains("http-equiv"))
        val code = HotspotPortalPages.code(defaults, "id", "ana@test.com", "")
        assertTrue(code.contains("data-busy=\"Conectando...\""))
        assertTrue(code.contains("button.disabled = true"))
    }

    @Test
    fun portalEscapesUserContent() {
        val html = HotspotPortalPages.form(
            defaults.copy(portalTitle = "<script>alert(1)</script>"),
            HotspotPortalPages.GuestContext("aa:bb:cc:dd:ee:ff", "", "", ""),
            HotspotPortalPages.FormValues(name = "\"><img src=x onerror=alert(1)>"),
        )
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
}
