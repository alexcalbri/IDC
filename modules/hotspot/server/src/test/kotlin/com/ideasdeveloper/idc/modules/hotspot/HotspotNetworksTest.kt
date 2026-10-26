package com.ideasdeveloper.idc.modules.hotspot

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotspotNetworksTest {
    private val id = UUID.randomUUID()
    private val settings = HotspotSettings(
        domain = null, networkName = "WiFi", portalTitle = "WiFi", portalSubtitle = "", logoUrl = null,
        primaryColor = "#E4002B", textColor = "#FFFFFF", consentText = "Acepto", termsUrl = null, privacyUrl = null,
        footerText = "", successUrl = null, unifiConsoleId = "console:1", unifiSiteId = "site-1", unifiApiKey = "secret",
        accessMinutes = 60, dataLimitMb = 0, rxKbps = 0, txKbps = 0, reconnectMinutes = 1440, codeTtlMinutes = 15,
        smtpMode = "servidor", smtpHost = null, smtpPort = 587, smtpUsername = null, smtpPassword = null,
        smtpFrom = null, smtpSecurity = "starttls", emailSubject = "Codigo",
    )

    @Test
    fun parsesCodeNetworkWithNormalizedTypesAndOverrides() {
        val network = HotspotNetworks.parse(
            id,
            mapOf(
                "ssid" to " Miembros VIP ",
                "accessMode" to "Codigo de cliente",
                "customerTypes" to "Miembro, huesped,, miembro",
                "accessMinutes" to "120",
                "reconnectMinutes" to "0",
            ),
        )
        assertEquals("Miembros VIP", network.ssid)
        assertTrue(network.byCustomerCode)
        assertEquals(listOf("miembro", "huesped"), network.customerTypes)
        val applied = network.applyTo(settings)
        assertEquals(120, applied.accessMinutes)
        assertEquals(0, applied.reconnectMinutes)
    }

    @Test
    fun formNetworkIgnoresTypesAndBlankTimesInheritTheGeneralOnes() {
        val network = HotspotNetworks.parse(id, mapOf("ssid" to "Publica", "accessMode" to "Formulario", "customerTypes" to "miembro"))
        assertFalse(network.byCustomerCode)
        assertEquals(emptyList(), network.customerTypes)
        assertNull(network.accessMinutes)
        assertEquals(settings, network.applyTo(settings))
    }

    @Test
    fun rejectsInvalidNetworks() {
        fun parse(vararg values: Pair<String, String>) = HotspotNetworks.parse(id, mapOf("ssid" to "Red", "accessMode" to "Formulario") + values)
        assertFailsWith<HotspotValidationException> { parse("ssid" to " ") }
        assertFailsWith<HotspotValidationException> { parse("ssid" to "x".repeat(33)) }
        assertFailsWith<HotspotValidationException> { parse("ssid" to "ñ".repeat(17)) } // 34 bytes
        assertFailsWith<HotspotValidationException> { parse("accessMode" to "Voucher") }
        assertFailsWith<HotspotValidationException> { parse("accessMode" to "Codigo de cliente", "customerTypes" to "Miembro VIP") }
        assertFailsWith<HotspotValidationException> { parse("accessMinutes" to "0") }
        assertFailsWith<HotspotValidationException> { parse("reconnectMinutes" to "-1") }
    }

    @Test
    fun formValuesRoundTrip() {
        val network = HotspotNetwork(id, "Hotel", HotspotNetworks.CustomerCode, listOf("huesped"), 90, null)
        assertEquals(network, HotspotNetworks.parse(id, HotspotNetworks.toFormValues(network)))
        assertEquals("Formulario", HotspotNetworks.toFormValues(null)["accessMode"])
    }

    @Test
    fun customerCodesAreNormalizedAndChecked() {
        assertEquals("K7M2QX9D", HotspotNetworks.normalizeCustomerCode(" k7m2-qx9d "))
        assertTrue(HotspotNetworks.isCustomerCode("K7M2QX9D"))
        assertFalse(HotspotNetworks.isCustomerCode("K7M2QX9"))
        // 0, O, 1 and I are never generated.
        assertFalse(HotspotNetworks.isCustomerCode("K7M2QX0D"))
        assertFalse(HotspotNetworks.isCustomerCode("K7M2QXID"))
    }

    @Test
    fun codePageShowsTheJoinedNetworkAndEscapesIt() {
        val guest = HotspotPortalPages.GuestContext("aa:bb:cc:dd:ee:ff", "", "<b>Miembros</b>", "")
        val html = HotspotPortalPages.accessCode(settings, guest, error = "Codigo <invalido>")
        assertTrue(html.contains("action=\"/guest/s/default/code\""))
        assertTrue(html.contains("Red: &lt;b&gt;Miembros&lt;/b&gt;"))
        assertTrue(html.contains("Codigo &lt;invalido&gt;"))
        assertTrue(html.contains("name=\"ssid\" value=\"&lt;b&gt;Miembros&lt;/b&gt;\""))
        assertTrue(html.contains("Codigo de cliente"))
        assertTrue(HotspotPortalPages.accessCode(settings, guest, byUser = true).contains("Codigo de usuario"))
        val withoutSsid = HotspotPortalPages.form(settings, guest.copy(ssid = ""))
        assertTrue(withoutSsid.contains("Red: WiFi"))
    }
}
