package com.ideasdeveloper.idc.modules.hotspot

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** A WiFi network (SSID) with its own way of getting access; see [HotspotNetworks]. */
data class HotspotNetwork(
    val id: UUID,
    val ssid: String,
    val accessMode: String,
    // Who connects on customer-code networks: [HotspotPasses.HotspotCustomers] and/or pass source ids.
    val accessSources: List<String>,
    // Null uses the access and reconnection times of the general configuration.
    val accessMinutes: Int?,
    val reconnectMinutes: Int?,
) {
    val byCustomerCode: Boolean
        get() = accessMode == HotspotNetworks.CustomerCode

    val byUserCode: Boolean
        get() = accessMode == HotspotNetworks.UserCode

    // Networks that ask for a code instead of showing the registration form.
    val byCode: Boolean
        get() = byCustomerCode || byUserCode

    // General settings with this network's times, used to authorize, limit reconnection and show the session length.
    fun applyTo(settings: HotspotSettings): HotspotSettings = settings.copy(
        accessMinutes = accessMinutes ?: settings.accessMinutes,
        reconnectMinutes = reconnectMinutes ?: settings.reconnectMinutes,
    )
}

/**
 * Networks configured for the portal. The network controller sends the SSID the guest joined in the
 * redirect; the portal shows the registration form or asks for the customer or user code
 * accordingly. SSIDs that are not configured keep using the form. "Codigo de usuario" needs the
 * Usuarios integration. "Codigo de cliente" lets in customers of type hotspot and/or customers with
 * an access pass of the sources the network lists (see [HotspotPasses]).
 */
object HotspotNetworks {
    const val Form = "formulario"
    const val CustomerCode = "codigo_cliente"
    const val UserCode = "codigo_usuario"

    // Labels shown by the configuration select, by stored mode.
    private val modeLabels = linkedMapOf(Form to "Formulario", CustomerCode to "Codigo de cliente", UserCode to "Codigo de usuario")
    // Form key of the checkbox of each access source.
    fun sourceKey(sourceId: String) = "source_$sourceId"
    // Same alphabet as customers_generate_code() in the Clientes migration V004.
    private const val CodeAlphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val CodeLength = 8

    // "Codigo de usuario" is offered only while the Usuarios integration is enabled.
    fun modeOptions(usersIntegration: Boolean): List<String> =
        modeLabels.filterKeys { usersIntegration || it != UserCode }.values.toList()

    fun modeLabel(mode: String): String = modeLabels[mode] ?: mode

    // Guests may type the code in lowercase or with spaces/dashes.
    fun normalizeCustomerCode(value: String): String = value.uppercase().filter { it.isLetterOrDigit() }

    fun isCustomerCode(code: String): Boolean = code.length == CodeLength && code.all { it in CodeAlphabet }

    /**
     * Validates the network form ([values] by field key) into a network with [id]. [sources] are the
     * access sources that may be chosen: customers of type hotspot and the enabled pass sources.
     */
    fun parse(
        id: UUID,
        values: Map<String, String>,
        usersIntegration: Boolean = true,
        sources: Set<String> = setOf(HotspotPasses.HotspotCustomers),
    ): HotspotNetwork {
        val ssid = values["ssid"].orEmpty().trim()
        if (ssid.isEmpty()) throw HotspotValidationException("El nombre de la red (SSID) es obligatorio.")
        if (ssid.toByteArray(Charsets.UTF_8).size > 32 || ssid.any { it.isISOControl() }) {
            throw HotspotValidationException("El nombre de la red (SSID) admite hasta 32 bytes y no puede tener caracteres de control.")
        }
        val modeLabel = values["accessMode"].orEmpty().trim()
        val mode = modeLabels.entries.firstOrNull { it.value == modeLabel || it.key == modeLabel }?.key
            ?: throw HotspotValidationException("Elige el tipo de acceso de la red.")
        if (mode == UserCode && !usersIntegration) {
            throw HotspotValidationException("Activa la integracion Usuarios para usar el acceso por codigo de usuario.")
        }
        val chosen = if (mode == CustomerCode) sources.filter { values[sourceKey(it)] == "true" }.sorted() else emptyList()
        if (mode == CustomerCode && chosen.isEmpty()) {
            throw HotspotValidationException("Elige quien puede conectarse con codigo de cliente en esta red.")
        }
        // Blank inherits the general configuration.
        fun minutes(key: String, range: IntRange, label: String): Int? {
            val text = values[key].orEmpty().trim()
            if (text.isEmpty()) return null
            return text.toIntOrNull()?.takeIf { it in range }
                ?: throw HotspotValidationException("$label debe estar entre ${range.first} y ${range.last} minutos, o vacio para usar el general.")
        }
        return HotspotNetwork(
            id = id,
            ssid = ssid,
            accessMode = mode,
            accessSources = chosen,
            accessMinutes = minutes("accessMinutes", 1..10080, "El tiempo de acceso de la red"),
            reconnectMinutes = minutes("reconnectMinutes", 0..525600, "El tiempo para reconectar de la red"),
        )
    }

    // A new network offers customers of type hotspot already checked.
    fun toFormValues(network: HotspotNetwork?): Map<String, String> = mapOf(
        "ssid" to network?.ssid.orEmpty(),
        "accessMode" to modeLabel(network?.accessMode ?: Form),
        "accessMinutes" to network?.accessMinutes?.toString().orEmpty(),
        "reconnectMinutes" to network?.reconnectMinutes?.toString().orEmpty(),
    ) + (network?.accessSources ?: listOf(HotspotPasses.HotspotCustomers)).associate { sourceKey(it) to "true" }

    fun list(connection: Connection): List<HotspotNetwork> = connection.prepareStatement(
        "SELECT * FROM hotspot_networks ORDER BY lower(ssid)"
    ).use { query ->
        query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toNetwork()) } }
    }

    fun find(connection: Connection, id: UUID): HotspotNetwork? = connection.prepareStatement(
        "SELECT * FROM hotspot_networks WHERE id = ?"
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) rows.toNetwork() else null }
    }

    // SSIDs are case-sensitive, so the match is exact.
    fun findBySsid(connection: Connection, ssid: String): HotspotNetwork? {
        if (ssid.isBlank()) return null
        return connection.prepareStatement("SELECT * FROM hotspot_networks WHERE ssid = ?").use { query ->
            query.setString(1, ssid)
            query.executeQuery().use { rows -> if (rows.next()) rows.toNetwork() else null }
        }
    }

    fun save(connection: Connection, network: HotspotNetwork) {
        val taken = connection.prepareStatement("SELECT 1 FROM hotspot_networks WHERE ssid = ? AND id <> ?").use { query ->
            query.setString(1, network.ssid)
            query.setObject(2, network.id)
            query.executeQuery().use { it.next() }
        }
        if (taken) throw HotspotValidationException("La red ${network.ssid} ya esta configurada.")
        connection.prepareStatement(
            """
            INSERT INTO hotspot_networks (id, ssid, access_mode, access_sources, access_minutes, reconnect_minutes)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                ssid = EXCLUDED.ssid,
                access_mode = EXCLUDED.access_mode,
                access_sources = EXCLUDED.access_sources,
                access_minutes = EXCLUDED.access_minutes,
                reconnect_minutes = EXCLUDED.reconnect_minutes,
                updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { upsert ->
            upsert.setObject(1, network.id)
            upsert.setString(2, network.ssid)
            upsert.setString(3, network.accessMode)
            upsert.setArray(4, connection.createArrayOf("text", network.accessSources.toTypedArray()))
            upsert.setObject(5, network.accessMinutes)
            upsert.setObject(6, network.reconnectMinutes)
            upsert.executeUpdate()
        }
    }

    fun delete(connection: Connection, id: UUID): Boolean = connection.prepareStatement(
        "DELETE FROM hotspot_networks WHERE id = ?"
    ).use { delete ->
        delete.setObject(1, id)
        delete.executeUpdate() == 1
    }

    private fun ResultSet.toNetwork(): HotspotNetwork {
        @Suppress("UNCHECKED_CAST")
        val sources = (getArray("access_sources")?.array as? Array<String>).orEmpty().toList()
        return HotspotNetwork(
            id = getObject("id", UUID::class.java),
            ssid = getString("ssid"),
            accessMode = getString("access_mode"),
            accessSources = sources,
            accessMinutes = getObject("access_minutes") as Int?,
            reconnectMinutes = getObject("reconnect_minutes") as Int?,
        )
    }
}
