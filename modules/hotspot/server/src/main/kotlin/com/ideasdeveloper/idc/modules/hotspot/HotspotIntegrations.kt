package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import java.sql.Connection

/**
 * Result of asking the network controller to let a device in. [wrongNetwork] marks a refusal
 * because the device is not on the WiFi network the portal was opened for.
 */
data class GuestAuthorization(val authorized: Boolean, val message: String, val wrongNetwork: Boolean = false)

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

    /**
     * Lets [mac] in only if the controller reports it connected to [ssid] ("" for wired guests),
     * the network the portal chose the access method for. Anything it cannot confirm is refused.
     */
    fun authorizeGuest(settings: HotspotSettings, mac: String, ssid: String): GuestAuthorization

    /**
     * Ends the guest access of [mac] (its code session expired). authorized = true means the device
     * no longer has access, including when it is not connected anymore.
     */
    fun disconnectGuest(settings: HotspotSettings, mac: String): GuestAuthorization

    /** SSIDs configured in the controller, to pick them in Redes WiFi. Throws [NetworkControllerException]. */
    fun wifiNetworks(settings: HotspotSettings): List<String>

    /**
     * Guest devices connected now, with the SSID each is on. The controller keeps a guest authorized
     * on every SSID of the site, so [HotspotNetworkGuard] uses this to catch network changes.
     * Throws [NetworkControllerException].
     */
    fun connectedGuests(settings: HotspotSettings): List<ConnectedGuest>
}

/** A guest device connected to the controller now; [ssid] is "" for wired guests. */
data class ConnectedGuest(val mac: String, val ssid: String, val authorized: Boolean)

class NetworkControllerException(message: String) : Exception(message)

/**
 * Opcion id->nombre (con un detalle opcional) que una extension de controlador ofrece para elegir la
 * consola o el sitio en la pantalla de Integraciones y por MCP, sin que el hotspot conozca los tipos
 * propios del controlador.
 */
data class ControllerOption(val id: String, val name: String, val detail: String = "")

/**
 * Lo implementa una extension (p. ej. UniFi) para aportar un [NetworkController] sin que el hotspot
 * dependa de su codigo. Las extensiones habilitadas se enlazan en [HotspotModuleExtensions] al arrancar
 * ([HotspotServerModule.linkExtensions]); el modulo las descubre solo por este contrato.
 */
interface NetworkControllerProvider {
    fun controller(): NetworkController

    /**
     * Consolas que alcanza la API key guardada en [settings] (controladores con seleccion de
     * consola/sitio, como UniFi). Lanza [NetworkControllerException] si no se pueden leer.
     */
    fun consoles(settings: HotspotSettings): List<ControllerOption>

    /** Sitios de la consola [consoleId]. Lanza [NetworkControllerException] si no se pueden leer. */
    fun sites(settings: HotspotSettings, consoleId: String): List<ControllerOption>
}

/** Registro de los controladores de red que aportan las extensiones del hotspot (las entrega el Core al arrancar). */
object HotspotModuleExtensions {
    @Volatile
    private var providers: List<NetworkControllerProvider> = emptyList()

    fun link(extensions: List<ServerExtension>) {
        providers = extensions.filterIsInstance<NetworkControllerProvider>()
    }

    fun controllers(): List<NetworkController> = providers.map { it.controller() }

    /** El proveedor del controlador [id], o null si su extension no esta instalada. */
    fun providerFor(id: String): NetworkControllerProvider? = providers.firstOrNull { it.controller().id == id }
}

/** An integration the hotspot offers: a network controller ("network") or another module ("module"). */
data class HotspotIntegration(val id: String, val kind: String, val title: String, val description: String)

/**
 * Integrations of the hotspot; their state lives in hotspot_integrations. Network controllers and
 * Usuarios are defined by the hotspot itself; other modules join as access pass sources
 * ([HotspotPasses]) by registering in hotspot_pass_sources. Only the business owner or a server
 * owner changes them.
 */
object HotspotIntegrations {
    const val Network = "network"
    const val Module = "module"
    // Users of the company connect with their user code on networks set to "Codigo de usuario".
    const val Users = "usuarios"
    // Id del controlador UniFi (su codigo vive en la extension "unifi"); el hotspot lo conoce solo para su UI/MCP.
    const val NetworkUnifi = "unifi"

    // Network controllers come from the enabled extensions; none when no controller extension is installed.
    val controllers: List<NetworkController> get() = HotspotModuleExtensions.controllers()

    // Integrations defined by the hotspot itself (network controllers from extensions + Usuarios).
    val builtIn: List<HotspotIntegration> get() = controllers.map { HotspotIntegration(it.id, Network, it.name, it.description) } +
        HotspotIntegration(
            Users, Module, "Usuarios",
            "Los usuarios de la empresa se conectan con su codigo de usuario a las redes con acceso \"Codigo de usuario\".",
        )

    // Built-in integrations plus the access pass sources registered by other modules.
    fun catalog(connection: Connection): List<HotspotIntegration> = builtIn +
        HotspotPasses.sources(connection).map { source ->
            HotspotIntegration(
                source.id, Module, source.name,
                source.description.ifBlank { "Sus clientes se conectan con su codigo de cliente mientras tengan un pase vigente." },
            )
        }

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
        val integration = catalog(connection).firstOrNull { it.id == id } ?: throw HotspotValidationException("Integracion desconocida.")
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
