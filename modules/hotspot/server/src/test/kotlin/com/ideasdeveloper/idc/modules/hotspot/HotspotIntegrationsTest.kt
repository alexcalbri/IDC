package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HotspotIntegrationsTest {
    // Un proveedor de controlador de red de prueba (como lo aportaria la extension UniFi).
    private object FakeNetworkController : NetworkController {
        override val id = "unifi"
        override val name = "UniFi"
        override val description = "prueba"
        override fun isConfigured(settings: HotspotSettings) = false
        override fun authorizeGuest(settings: HotspotSettings, mac: String, ssid: String) = GuestAuthorization(false, "")
        override fun disconnectGuest(settings: HotspotSettings, mac: String) = GuestAuthorization(false, "")
        override fun wifiNetworks(settings: HotspotSettings): List<String> = emptyList()
        override fun connectedGuests(settings: HotspotSettings): List<ConnectedGuest> = emptyList()
    }

    private object FakeNetworkExtension : ServerExtension, NetworkControllerProvider {
        override val manifest = ExtensionManifest(id = "unifi", module = "hotspot", displayName = "UniFi", version = "1.0.0")
        override fun controller(): NetworkController = FakeNetworkController
        override fun consoles(settings: HotspotSettings): List<ControllerOption> = emptyList()
        override fun sites(settings: HotspotSettings, consoleId: String): List<ControllerOption> = emptyList()
    }

    @Test
    fun networkControllersComeFromLinkedExtensionsAndUsersIsAlwaysAModule() {
        try {
            // Sin extensiones de controlador no se ofrece ninguno; Usuarios sigue estando.
            HotspotModuleExtensions.link(emptyList())
            assertEquals(emptyList(), HotspotIntegrations.builtIn.filter { it.kind == HotspotIntegrations.Network })
            assertEquals(
                listOf(HotspotIntegrations.Users),
                HotspotIntegrations.builtIn.filter { it.kind == HotspotIntegrations.Module }.map { it.id },
            )

            // La extension UniFi enlazada aparece como controlador de red.
            HotspotModuleExtensions.link(listOf(FakeNetworkExtension))
            assertEquals(
                listOf("unifi"),
                HotspotIntegrations.builtIn.filter { it.kind == HotspotIntegrations.Network }.map { it.id },
            )
        } finally {
            HotspotModuleExtensions.link(emptyList())
        }
    }

    @Test
    fun userCodeModeNeedsTheUsersIntegration() {
        assertEquals(
            listOf("Formulario", "Codigo de cliente"),
            HotspotNetworks.modeOptions(usersIntegration = false, crmIntegration = true),
        )
        assertEquals(
            listOf("Formulario", "Codigo de cliente", "Codigo de usuario"),
            HotspotNetworks.modeOptions(usersIntegration = true, crmIntegration = true),
        )
        val values = mapOf("ssid" to "Personal", "accessMode" to "Codigo de usuario")
        assertFailsWith<HotspotValidationException> { HotspotNetworks.parse(UUID.randomUUID(), values, usersIntegration = false) }
        val network = HotspotNetworks.parse(UUID.randomUUID(), values, usersIntegration = true)
        assertTrue(network.byUserCode && network.byCode)
        assertEquals(emptyList(), network.accessSources)
    }

    @Test
    fun generatedUserCodesHaveTheUserPrefixAndGuestCodeFormat() {
        repeat(200) {
            val code = HotspotUserCodes.generate()
            assertTrue(code.startsWith("us"))
            assertTrue(HotspotUserCodes.isUserCode(code))
            assertTrue(HotspotNetworks.isCustomerCode(code.drop(2)))
        }
        assertTrue(HotspotUserCodes.isUserCode("US23456789"))
        assertFalse(HotspotUserCodes.isUserCode("23456789"))
        assertFalse(HotspotUserCodes.isUserCode("us23456780"))
        assertTrue(HotspotUserCodes.isUserCode(" us23456789 "))
    }
}
