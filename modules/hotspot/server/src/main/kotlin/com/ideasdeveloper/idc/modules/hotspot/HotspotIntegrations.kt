package com.ideasdeveloper.idc.modules.hotspot

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/** Result of asking the network controller to let a device in. */
data class GuestAuthorization(val authorized: Boolean, val message: String)

/**
 * A network ecosystem the hotspot can work with (UniFi today; MikroTik or others later). Exactly
 * one is enabled per company, chosen in Integraciones; the portal and the settings only talk to
 * this interface.
 */
interface NetworkController {
    val id: String
    val name: String
    val description: String

    fun isConfigured(settings: HotspotSettings): Boolean

    fun authorizeGuest(settings: HotspotSettings, mac: String): GuestAuthorization

    /** SSIDs configured in the controller, to pick them in Redes WiFi. Throws [NetworkControllerException]. */
    fun wifiNetworks(settings: HotspotSettings): List<String>
}

class NetworkControllerException(message: String) : Exception(message)

object UnifiController : NetworkController {
    override val id = "unifi"
    override val name = "UniFi"
    override val description = "Autoriza a los visitantes en una consola UniFi a traves de UniFi Site Manager (api.ui.com)."

    override fun isConfigured(settings: HotspotSettings) = settings.unifiConfigured

    override fun authorizeGuest(settings: HotspotSettings, mac: String) = UnifiClient.authorizeGuest(settings, mac)

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

/** An integration the hotspot offers: a network controller ("network") or another module ("module"). */
data class HotspotIntegration(val id: String, val kind: String, val title: String, val description: String)

/**
 * Integrations defined by the hotspot module itself; their state lives in hotspot_integrations.
 * Only the business owner or a server owner changes them.
 */
object HotspotIntegrations {
    const val Network = "network"
    const val Module = "module"
    // Users of the company connect with their user code on networks set to "Codigo de usuario".
    const val Users = "usuarios"

    val controllers: List<NetworkController> = listOf(UnifiController)

    val catalog: List<HotspotIntegration> = controllers.map { HotspotIntegration(it.id, Network, it.name, it.description) } +
        HotspotIntegration(
            Users, Module, "Usuarios",
            "Los usuarios de la empresa se conectan con su codigo de usuario a las redes con acceso \"Codigo de usuario\".",
        )

    fun enabled(connection: Connection): Set<String> = connection.prepareStatement(
        "SELECT id FROM hotspot_integrations WHERE enabled"
    ).use { query ->
        query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
    }

    fun isEnabled(connection: Connection, id: String): Boolean = id in enabled(connection)

    // The enabled network controller, or null when none is chosen.
    fun controller(connection: Connection): NetworkController? {
        val enabled = enabled(connection)
        return controllers.firstOrNull { it.id in enabled }
    }

    /** Turns an integration on or off; enabling a network controller disables the other ones. */
    fun setEnabled(connection: Connection, id: String, enabled: Boolean) {
        val integration = catalog.firstOrNull { it.id == id } ?: throw HotspotValidationException("Integracion desconocida.")
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            if (enabled && integration.kind == Network) {
                connection.prepareStatement("UPDATE hotspot_integrations SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP WHERE kind = ? AND id <> ?").use { update ->
                    update.setString(1, Network)
                    update.setString(2, id)
                    update.executeUpdate()
                }
            }
            connection.prepareStatement(
                """
                INSERT INTO hotspot_integrations (id, kind, enabled) VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { upsert ->
                upsert.setString(1, id)
                upsert.setString(2, integration.kind)
                upsert.setBoolean(3, enabled)
                upsert.executeUpdate()
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = autoCommit
        }
    }
}
