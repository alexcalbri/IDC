package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.SmtpSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

class HotspotSettingsTest {
    private val defaults = HotspotSettings(
        domain = null, networkName = "WiFi", portalTitle = "WiFi", portalSubtitle = "", logoUrl = null,
        consentText = "Acepto", termsUrl = null, privacyUrl = null,
        footerText = "", successUrl = null, unifiConsoleId = "console:1", unifiSiteId = "site-1", unifiApiKey = "secret",
        accessMinutes = 60, dataLimitMb = 0, rxKbps = 0, txKbps = 0, reconnectMinutes = 1440, codeTtlMinutes = 15,
        emailSubject = "Codigo",
        crmAccessEnabled = false, crmStageId = null, crmScheduleMode = "daily",
        crmCheckIntervalMinutes = 60, crmCheckTime = LocalTime.of(7, 0),
    )

    private fun values(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun blankSecretsKeepStoredValues() {
        val merged = HotspotSettingsRepository.merge(defaults, values("unifiApiKey" to ""))
        assertEquals("secret", merged.unifiApiKey)
    }

    @Test
    fun unchangedMaskKeepsStoredValuesAndNewSecretReplacesThem() {
        val kept = HotspotSettingsRepository.merge(defaults, values("unifiApiKey" to "********"))
        assertEquals("secret", kept.unifiApiKey)
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
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("accessMinutes" to "0")) }
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("logoUrl" to "javascript:alert(1)")) }
        assertFailsWith<HotspotValidationException> { HotspotSettingsRepository.merge(defaults, values("unifiSiteId" to "../../x?y")) }
    }

    @Test
    fun smtpFieldsAreIgnoredBecauseHotspotHasNoOwnSmtp() {
        val merged = HotspotSettingsRepository.merge(defaults, values("smtpMode" to "empresa", "smtpHost" to "smtp.propio.test", "emailSubject" to "Tu codigo"))
        assertEquals("Tu codigo", merged.emailSubject)
        assertFalse(HotspotSettingsRepository.toFormValues(merged, "Activo", sendingAvailable = true).keys.any { it.startsWith("smtp") })
    }

    @Test
    fun crmStageAndScheduleCanBeConfiguredWithoutReservations() {
        val stageId = UUID.fromString("6f726000-7e53-4d4b-b97e-21dd9b9d1c4f")
        val configured = HotspotSettingsRepository.merge(
            defaults,
            values(
                "crmAccessEnabled" to "true",
                "crmStageId" to stageId.toString(),
                "crmScheduleMode" to "Cada intervalo",
                "crmCheckIntervalMinutes" to "45",
            ),
        )
        assertTrue(configured.crmAccessEnabled)
        assertEquals(stageId, configured.crmStageId)
        assertEquals("interval", configured.crmScheduleMode)
        assertEquals(45, configured.crmCheckIntervalMinutes)
    }

    @Test
    fun crmAccessRequiresASelectedStage() {
        assertFailsWith<HotspotValidationException> {
            HotspotSettingsRepository.merge(defaults, values("crmAccessEnabled" to "true"))
        }
    }

    @Test
    fun formValuesNeverExposeSecrets() {
        val form = HotspotSettingsRepository.toFormValues(defaults, "Activo", sendingAvailable = false)
        assertEquals("********", form["unifiApiKey"])
        val empty = HotspotSettingsRepository.toFormValues(defaults.copy(unifiApiKey = null), "Activo", sendingAvailable = false)
        assertEquals("", empty["unifiApiKey"])
    }

    @Test
    fun hotspotSendsWithTheCompanySmtpAndFallsBackToTheServerOne() {
        val server = SmtpSettings("smtp.server", 587, "", "", "no-reply@server.test", trusted = true)
        val company = SmtpSettings("smtp.company", 587, "u", "p", "wifi@company.test")
        assertEquals("smtp.company", HotspotMail.resolveSmtp(company, server)?.host)
        assertEquals(server, HotspotMail.resolveSmtp(null, server))
        assertNull(HotspotMail.resolveSmtp(null, null))
        // Un SMTP incompleto no sirve para enviar.
        assertNull(HotspotMail.resolveSmtp(company.copy(from = ""), null))
    }

    @Test
    fun withoutSecretsTheCompanySmtpIsNotReadAndTheServerOneIsUsed() {
        val untouched = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, _ ->
            throw AssertionError("No debia usar la conexion: ${method.name}")
        } as java.sql.Connection
        val server = SmtpSettings("smtp.server", 587, "", "", "no-reply@server.test", trusted = true)
        assertNull(HotspotMail.companySmtp(untouched, null))
        assertEquals(server, HotspotMail.smtp(untouched, server, null))
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

    @Test
    fun portalUsesTheCompanyColorsOrIdcOnes() {
        assertEquals(PortalBrand("#FBB606", "#023047", "#FFFFFF"), PortalBrand.of("#FBB606", "#023047", "#FFFFFF"))
        assertEquals(PortalBrand.Idc, PortalBrand.of(null, "red", " "))
        assertEquals(PortalBrand.Idc, defaults.brand)
    }

    @Test
    fun ownPortalColorsGoOverTheCompanyOnes() {
        val company = PortalBrand("#FBB606", "#023047", "#FFFFFF")
        assertEquals(company, company.with(PortalColors()))
        assertEquals(PortalBrand("#E4002B", "#023047", "#000000"), company.with(PortalColors(primary = "#E4002B", text = "#000000")))
        // Merging other settings keeps the colors saved in Diseño.
        val colored = defaults.copy(portalColors = PortalColors(primary = "#E4002B"))
        assertEquals(colored.portalColors, HotspotSettingsRepository.merge(colored, JsonObject(mapOf("portalTitle" to JsonPrimitive("Hola")))).portalColors)
    }
}
