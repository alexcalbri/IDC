package com.ideasdeveloper.idc.extensions.unifi

import com.ideasdeveloper.idc.modules.hotspot.ConnectedGuest
import com.ideasdeveloper.idc.modules.hotspot.ControllerOption
import com.ideasdeveloper.idc.modules.hotspot.HotspotSettings
import com.ideasdeveloper.idc.modules.hotspot.NetworkController
import com.ideasdeveloper.idc.modules.hotspot.NetworkControllerException
import com.ideasdeveloper.idc.modules.hotspot.NetworkControllerProvider
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionSource
import com.ideasdeveloper.idc.server.extensions.ExtensionSourceType
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.extensions.ServerExtensionProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Extension "unifi": el controlador de red UniFi del hotspot. Autoriza/desconecta visitantes y lista
 * las redes WiFi en una consola UniFi via UniFi Site Manager (api.ui.com). No tiene rutas ni tablas
 * propias: los ajustes (consola/sitio/api key) viven en hotspot_settings y llegan en [HotspotSettings].
 */
object UnifiExtension : ServerExtension, NetworkControllerProvider {
    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = "unifi",
        module = "hotspot",
        displayName = "UniFi",
        description = "Controlador de red UniFi: autoriza y desconecta a los visitantes del portal en una consola UniFi a traves de UniFi Site Manager (api.ui.com) y lista sus redes WiFi.",
        version = "1.0.0",
        minModuleVersion = "0.11.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/unifi",
        ),
        permissions = listOf(ExtensionPermissions.NetworkOutboundPrefix + "api.ui.com"),
    )

    override fun controller(): NetworkController = UnifiController

    override fun consoles(settings: HotspotSettings): List<ControllerOption> = try {
        UnifiClient.consoles(settings.unifiApiKey.orEmpty()).map { console ->
            ControllerOption(console.id, console.name, listOf(console.model, console.state).filter { it.isNotBlank() }.joinToString(" - "))
        }
    } catch (failure: UnifiException) {
        throw NetworkControllerException(failure.message.orEmpty())
    }

    override fun sites(settings: HotspotSettings, consoleId: String): List<ControllerOption> = try {
        UnifiClient.sites(settings.unifiApiKey.orEmpty(), consoleId).map { site ->
            ControllerOption(site.id, site.name, site.reference.takeIf { it.isNotBlank() }?.let { "Referencia: $it" }.orEmpty())
        }
    } catch (failure: UnifiException) {
        throw NetworkControllerException(failure.message.orEmpty())
    }
}

/** El [NetworkController] de UniFi; delega en [UnifiClient] (movido del modulo hotspot). */
object UnifiController : NetworkController {
    override val id = "unifi"
    override val name = "UniFi"
    override val description = "Autoriza a los visitantes en una consola UniFi a traves de UniFi Site Manager (api.ui.com)."

    override fun isConfigured(settings: HotspotSettings) = settings.unifiConfigured

    override fun authorizeGuest(settings: HotspotSettings, mac: String, ssid: String) = UnifiClient.authorizeGuest(settings, mac, ssid)

    override fun disconnectGuest(settings: HotspotSettings, mac: String) = UnifiClient.unauthorizeGuest(settings, mac)

    override fun connectedGuests(settings: HotspotSettings): List<ConnectedGuest> = try {
        UnifiClient.connectedGuests(settings)
    } catch (failure: Exception) {
        throw NetworkControllerException(failure.message.orEmpty())
    }

    // The settings screen lists the SSIDs on every render; a short cache spares a cloud call each time.
    private const val SsidCacheMillis = 60_000L
    private val ssidCache = ConcurrentHashMap<List<String>, Pair<Long, List<String>>>()

    override fun wifiNetworks(settings: HotspotSettings): List<String> {
        if (!settings.unifiConfigured) throw NetworkControllerException("Configura la consola y el sitio de UniFi primero.")
        val key = listOf(settings.unifiApiKey!!, settings.unifiConsoleId!!, settings.unifiSiteId!!)
        val now = System.currentTimeMillis()
        ssidCache[key]?.let { (readAt, ssids) -> if (now - readAt < SsidCacheMillis) return ssids }
        return try {
            UnifiClient.wifiNetworks(key[0], key[1], key[2]).also { ssids ->
                ssidCache.entries.removeIf { now - it.value.first >= SsidCacheMillis }
                ssidCache[key] = now to ssids
            }
        } catch (failure: UnifiException) {
            throw NetworkControllerException(failure.message.orEmpty())
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension (ServiceLoader). */
class UnifiServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = UnifiExtension
}
