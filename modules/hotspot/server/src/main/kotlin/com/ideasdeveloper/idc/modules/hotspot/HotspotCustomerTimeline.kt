package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.CustomerTimelineEntry
import java.time.OffsetDateTime

/**
 * What the hotspot adds to a customer's history in Clientes: each WiFi connection the guest got
 * through the portal, with the network and the time granted. Same permission as the visitor reports.
 */
object HotspotCustomerTimeline {
    private val accessModes = mapOf("formulario" to "formulario y codigo por correo", "codigo_cliente" to "codigo de cliente")

    val timeline = CustomerTimeline(permission = HotspotScreens.ReportsView) { query ->
        query.tenant.prepareStatement(
            """
            SELECT accesses.created_at, accesses.minutes, accesses.mac, accesses.joined, accesses.disconnected_at, registrations.ssid, registrations.access_mode
            FROM hotspot_accesses accesses
            JOIN hotspot_registrations registrations ON registrations.id = accesses.registration_id
            WHERE registrations.customer_id = ? AND accesses.authorized
            ORDER BY accesses.created_at DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, query.customerId)
            statement.setInt(2, query.limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val ssid = rows.getString("ssid")?.takeIf { it.isNotBlank() }
                        val mode = rows.getString("access_mode")
                        add(
                            CustomerTimelineEntry(
                                at = rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                                kind = "wifi",
                                title = "Conexion WiFi" + (ssid?.let { " a $it" } ?: ""),
                                detail = listOfNotNull(
                                    "Tiempo: ${rows.getInt("minutes")} minutos",
                                    "Acceso por ${accessModes[mode] ?: mode}",
                                    "Dispositivo: ${rows.getString("mac")}",
                                    "Se unio a la sesion de otro dispositivo con el mismo codigo".takeIf { rows.getBoolean("joined") },
                                    rows.getObject("disconnected_at", OffsetDateTime::class.java)?.let {
                                        "Desconectado al vencer el tiempo: ${it.toLocalDateTime().toString().take(16).replace('T', ' ')} UTC"
                                    },
                                ).joinToString("\n"),
                            )
                        )
                    }
                }
            }
        }
    }
}
