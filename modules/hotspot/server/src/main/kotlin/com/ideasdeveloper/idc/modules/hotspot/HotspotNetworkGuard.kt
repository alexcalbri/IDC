package com.ideasdeveloper.idc.modules.hotspot

import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * UniFi authorizes a guest for the whole site, not for one SSID: a device let in on a customer
 * network could switch to the staff network (or any other) and keep browsing without its code. Each
 * run (job "network-guard", every minute) compares the SSID of every authorized guest with the SSID
 * of its latest access in this company and removes the authorization when they differ, so the new
 * network's portal asks for its own access method. Its access on the first network is kept: going
 * back there, the portal lets it in again for the time left ("Bienvenido de nuevo").
 *
 * Devices this company never let in (vouchers, manual authorizations in UniFi) are left alone.
 */
object HotspotNetworkGuard {
    private val log = LoggerFactory.getLogger(HotspotNetworkGuard::class.java)

    fun run(tenant: Connection, companyCode: String, settings: HotspotSettings, controller: NetworkController?) {
        if (controller == null || !controller.isConfigured(settings)) return
        val guests = try {
            controller.connectedGuests(settings).filter { it.authorized }
        } catch (failure: NetworkControllerException) {
            log.warn("Hotspot {}: no se pudieron leer los dispositivos de {}: {}", companyCode, controller.name, failure.message)
            return
        }
        if (guests.isEmpty()) return
        mismatches(guests, lastSsids(tenant, guests.map { it.mac })).forEach { (guest, authorizedFor) ->
            val result = controller.disconnectGuest(settings, guest.mac)
            if (result.authorized) {
                log.info("Hotspot {}: {} paso de '{}' a '{}'; se le pidio acceso de nuevo", companyCode, guest.mac, authorizedFor, guest.ssid)
            } else {
                log.warn("Hotspot {}: no se pudo desautorizar {} en '{}': {}", companyCode, guest.mac, guest.ssid, result.message)
            }
        }
    }

    /** Authorized guests on another SSID than the one of their latest access, with that SSID. */
    fun mismatches(guests: List<ConnectedGuest>, lastSsids: Map<String, String>): List<Pair<ConnectedGuest, String>> =
        guests.mapNotNull { guest ->
            val authorizedFor = lastSsids[guest.mac] ?: return@mapNotNull null
            if (UnifiClient.sameNetwork(guest.ssid, authorizedFor)) null else guest to authorizedFor
        }

    // SSID ("" when wired or unknown) of the latest authorized access of each MAC in this company.
    private fun lastSsids(tenant: Connection, macs: List<String>): Map<String, String> = tenant.prepareStatement(
        """
        SELECT DISTINCT ON (accesses.mac) accesses.mac, coalesce(registrations.ssid, '')
        FROM hotspot_accesses accesses
        JOIN hotspot_registrations registrations ON registrations.id = accesses.registration_id
        WHERE accesses.authorized AND accesses.mac = ANY (?)
        ORDER BY accesses.mac, accesses.created_at DESC
        """.trimIndent()
    ).use { query ->
        query.setArray(1, tenant.createArrayOf("varchar", macs.toTypedArray()))
        query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } }
    }
}
