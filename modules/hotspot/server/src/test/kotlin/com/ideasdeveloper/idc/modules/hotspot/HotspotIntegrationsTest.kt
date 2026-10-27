package com.ideasdeveloper.idc.modules.hotspot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HotspotIntegrationsTest {
    @Test
    fun catalogOffersUnifiAsControllerAndUsersAsModule() {
        val network = HotspotIntegrations.builtIn.filter { it.kind == HotspotIntegrations.Network }.map { it.id }
        val modules = HotspotIntegrations.builtIn.filter { it.kind == HotspotIntegrations.Module }.map { it.id }
        assertEquals(listOf("unifi"), network)
        assertEquals(listOf(HotspotIntegrations.Users), modules)
    }

    @Test
    fun userCodeModeNeedsTheUsersIntegration() {
        assertEquals(listOf("Formulario", "Codigo de cliente"), HotspotNetworks.modeOptions(usersIntegration = false))
        assertEquals(listOf("Formulario", "Codigo de cliente", "Codigo de usuario"), HotspotNetworks.modeOptions(usersIntegration = true))
        val values = mapOf("ssid" to "Personal", "accessMode" to "Codigo de usuario")
        assertFailsWith<HotspotValidationException> { HotspotNetworks.parse(UUID.randomUUID(), values, usersIntegration = false) }
        val network = HotspotNetworks.parse(UUID.randomUUID(), values, usersIntegration = true)
        assertTrue(network.byUserCode && network.byCode)
        assertEquals(emptyList(), network.accessSources)
    }

    @Test
    fun generatedUserCodesUseTheCodeAlphabet() {
        repeat(200) { assertTrue(HotspotNetworks.isCustomerCode(HotspotUserCodes.generate())) }
    }

    @Test
    fun wifiBroadcastsUseTheirNames() {
        val page = Json.parseToJsonElement(
            """{"offset": 0, "limit": 200, "count": 3, "totalCount": 3, "data": [
                 {"id": "a", "type": "STANDARD", "name": "KFC_WIFI", "enabled": true},
                 {"id": "b", "type": "IOT_OPTIMIZED", "name": "Sensores", "enabled": false},
                 {"id": "c", "type": "STANDARD", "name": ""}
               ]}"""
        ).jsonObject
        assertEquals(listOf("KFC_WIFI", "Sensores"), UnifiClient.parseWifiNetworks(page))
    }
}
