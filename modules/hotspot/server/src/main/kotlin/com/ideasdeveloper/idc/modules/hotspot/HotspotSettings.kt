package com.ideasdeveloper.idc.modules.hotspot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalTime
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

class HotspotValidationException(message: String) : Exception(message)

data class HotspotSettings(
    val domain: String?,
    val networkName: String,
    val portalTitle: String,
    val portalSubtitle: String,
    val logoUrl: String?,
    val consentText: String,
    val termsUrl: String?,
    val privacyUrl: String?,
    val footerText: String,
    val successUrl: String?,
    val unifiConsoleId: String?,
    val unifiSiteId: String?,
    val unifiApiKey: String?,
    val accessMinutes: Int,
    val dataLimitMb: Int,
    val rxKbps: Int,
    val txKbps: Int,
    val reconnectMinutes: Int,
    val codeTtlMinutes: Int,
    val emailSubject: String,
    val crmAccessEnabled: Boolean,
    val crmStageId: UUID?,
    val crmScheduleMode: String,
    val crmCheckIntervalMinutes: Int,
    val crmCheckTime: LocalTime,
    // Colors set in Hotspot > Diseno; a missing one uses the company's.
    val portalColors: PortalColors = PortalColors(),
    // Colors the portal uses (own, else the company's, else IDC's), set by the portal for each request.
    val brand: PortalBrand = PortalBrand.Idc,
    // Custom HTML of the entry views by view id (hotspot_portal_templates), loaded by the portal.
    val templates: Map<String, String> = emptyMap(),
) {
    // Pages opened after connecting; the setting holds them separated by commas.
    val successUrls: List<String>
        get() = successUrl.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val unifiConfigured: Boolean
        get() = !unifiConsoleId.isNullOrBlank() && !unifiSiteId.isNullOrBlank() && !unifiApiKey.isNullOrBlank()
}

object HotspotSettingsRepository {
    private val domainPattern = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$")
    private val unifiIdPattern = Regex("^[A-Za-z0-9:._-]{1,200}$")

    fun load(connection: Connection): HotspotSettings = connection.prepareStatement(
        "SELECT * FROM hotspot_settings WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            if (!rows.next()) throw IllegalStateException("hotspot_settings is empty")
            rows.toSettings()
        }
    }

    /**
     * Applies the editable fields present in [values] over [current] and validates the result.
     * Blank secret fields keep the stored secret so the client never needs to read it back.
     */
    fun merge(current: HotspotSettings, values: JsonObject): HotspotSettings {
        fun text(key: String): String? = values[key]?.jsonPrimitive?.contentOrNull?.trim()
        fun optional(key: String, fallback: String?): String? = if (values.containsKey(key)) text(key)?.takeIf { it.isNotBlank() } else fallback
        fun required(key: String, fallback: String, label: String): String {
            if (!values.containsKey(key)) return fallback
            return text(key)?.takeIf { it.isNotBlank() } ?: throw HotspotValidationException("$label es obligatorio.")
        }
        fun number(key: String, fallback: Int, range: IntRange, label: String): Int {
            if (!values.containsKey(key)) return fallback
            val value = text(key)?.toIntOrNull() ?: throw HotspotValidationException("$label debe ser un numero entero.")
            if (value !in range) throw HotspotValidationException("$label debe estar entre ${range.first} y ${range.last}.")
            return value
        }
        fun boolean(key: String, fallback: Boolean): Boolean {
            if (!values.containsKey(key)) return fallback
            return values[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: throw HotspotValidationException("El valor de $key no es valido.")
        }
        // Empty or the unchanged mask keeps the saved secret.
        fun secret(key: String, fallback: String?): String? =
            text(key)?.takeIf { it.isNotBlank() && it != SecretMask } ?: fallback

        val merged = HotspotSettings(
            domain = optional("domain", current.domain)?.lowercase()?.removeSuffix("."),
            networkName = required("networkName", current.networkName, "El nombre de la red"),
            portalTitle = required("portalTitle", current.portalTitle, "El titulo del portal"),
            portalSubtitle = if (values.containsKey("portalSubtitle")) text("portalSubtitle").orEmpty() else current.portalSubtitle,
            logoUrl = optional("logoUrl", current.logoUrl),
            consentText = required("consentText", current.consentText, "El texto de consentimiento"),
            termsUrl = optional("termsUrl", current.termsUrl),
            privacyUrl = optional("privacyUrl", current.privacyUrl),
            footerText = if (values.containsKey("footerText")) text("footerText").orEmpty() else current.footerText,
            successUrl = optional("successUrl", current.successUrl)?.let(::normalizeUrlList),
            unifiConsoleId = optional("unifiConsoleId", current.unifiConsoleId),
            unifiSiteId = optional("unifiSiteId", current.unifiSiteId),
            unifiApiKey = secret("unifiApiKey", current.unifiApiKey),
            accessMinutes = number("accessMinutes", current.accessMinutes, 1..10080, "El tiempo de acceso"),
            dataLimitMb = number("dataLimitMb", current.dataLimitMb, 0..1048576, "El limite de datos"),
            rxKbps = number("rxKbps", current.rxKbps, 0..10000000, "La velocidad de descarga"),
            txKbps = number("txKbps", current.txKbps, 0..10000000, "La velocidad de subida"),
            reconnectMinutes = number("reconnectMinutes", current.reconnectMinutes, 0..525600, "El tiempo de reconexion"),
            codeTtlMinutes = number("codeTtlMinutes", current.codeTtlMinutes, 1..1440, "La vigencia del codigo"),
            emailSubject = required("emailSubject", current.emailSubject, "El asunto del correo"),
            crmAccessEnabled = boolean("crmAccessEnabled", current.crmAccessEnabled),
            crmStageId = optional("crmStageId", current.crmStageId?.toString())?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
                    ?: throw HotspotValidationException("El estado del CRM no es valido.")
            },
            crmScheduleMode = when (required("crmScheduleMode", current.crmScheduleMode, "La programacion del CRM")) {
                "Cada intervalo" -> "interval"
                "Diario a una hora" -> "daily"
                "interval", "daily" -> required("crmScheduleMode", current.crmScheduleMode, "La programacion del CRM")
                else -> throw HotspotValidationException("Elige una programacion del CRM valida.")
            },
            crmCheckIntervalMinutes = number("crmCheckIntervalMinutes", current.crmCheckIntervalMinutes, 1..10080, "El intervalo del CRM"),
            crmCheckTime = if (values.containsKey("crmCheckTime")) {
                runCatching { LocalTime.parse(text("crmCheckTime").orEmpty()) }.getOrNull()
                    ?: throw HotspotValidationException("La hora de revision debe tener el formato HH:mm.")
            } else current.crmCheckTime,
            portalColors = current.portalColors,
        )
        if (merged.crmAccessEnabled && merged.crmStageId == null) {
            throw HotspotValidationException("Selecciona el estado del embudo del CRM que permite el acceso.")
        }
        if (merged.crmScheduleMode !in setOf("daily", "interval")) {
            throw HotspotValidationException("Elige una programacion del CRM valida.")
        }
        validate(merged)
        return merged
    }

    private fun normalizeUrlList(value: String): String? =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }

    private fun validate(settings: HotspotSettings) {
        settings.domain?.let {
            if (it.length > 253 || !domainPattern.matches(it)) throw HotspotValidationException("El dominio no es valido. Ejemplo: wifi.miempresa.com")
        }
        (listOfNotNull(settings.logoUrl, settings.termsUrl, settings.privacyUrl) + settings.successUrls).forEach { url ->
            if (!url.startsWith("https://") && !url.startsWith("http://") || url.any { it.isWhitespace() }) {
                throw HotspotValidationException("Las direcciones web deben iniciar con https:// o http:// y no llevar espacios.")
            }
        }
        if (settings.successUrl.orEmpty().length > 4000) throw HotspotValidationException("La lista de direcciones al conectarse es demasiado larga.")
        listOf(settings.unifiConsoleId, settings.unifiSiteId).forEach { id ->
            if (id != null && !unifiIdPattern.matches(id)) throw HotspotValidationException("El identificador de consola o sitio de UniFi no es valido.")
        }
    }

    fun save(connection: Connection, settings: HotspotSettings) {
        connection.prepareStatement(
            """
            UPDATE hotspot_settings SET
                domain = ?, network_name = ?, portal_title = ?, portal_subtitle = ?, logo_url = ?,
                consent_text = ?, terms_url = ?, privacy_url = ?,
                footer_text = ?, success_url = ?, unifi_console_id = ?, unifi_site_id = ?, unifi_api_key = ?,
                access_minutes = ?, data_limit_mb = ?, rx_kbps = ?, tx_kbps = ?, reconnect_minutes = ?,
                code_ttl_minutes = ?, email_subject = ?,
                crm_access_enabled = ?, crm_stage_id = ?, crm_schedule_mode = ?, crm_check_interval_minutes = ?,
                crm_check_time = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = 1
            """.trimIndent()
        ).use { update ->
            listOf<Any?>(
                settings.domain, settings.networkName, settings.portalTitle, settings.portalSubtitle, settings.logoUrl,
                settings.consentText, settings.termsUrl, settings.privacyUrl,
                settings.footerText, settings.successUrl, settings.unifiConsoleId, settings.unifiSiteId, settings.unifiApiKey,
                settings.accessMinutes, settings.dataLimitMb, settings.rxKbps, settings.txKbps, settings.reconnectMinutes,
                settings.codeTtlMinutes, settings.emailSubject,
                settings.crmAccessEnabled, settings.crmStageId, settings.crmScheduleMode, settings.crmCheckIntervalMinutes,
                settings.crmCheckTime,
            ).forEachIndexed { index, value -> update.setObject(index + 1, value) }
            update.executeUpdate()
        }
    }

    // Placeholder shown instead of a saved secret; sending it back unchanged keeps the secret.
    private const val SecretMask = "********"

    private fun masked(secret: String?): String = if (secret.isNullOrBlank()) "" else SecretMask

    // Values for the generic form. Secrets are never sent back; a saved one shows as [SecretMask].
    fun toFormValues(
        settings: HotspotSettings,
        domainStatus: String,
        sendingAvailable: Boolean,
        crmStageName: String = "",
    ): Map<String, String> {
        val values = mapOf(
            "domain" to settings.domain.orEmpty(),
            "portalUrl" to settings.domain?.let { "$it/guest/s/default/" }.orEmpty(),
            "domainStatus" to domainStatus,
            "unifiConsoleId" to settings.unifiConsoleId.orEmpty(),
            "unifiSiteId" to settings.unifiSiteId.orEmpty(),
            "unifiApiKey" to masked(settings.unifiApiKey),
            "accessMinutes" to settings.accessMinutes.toString(),
            "dataLimitMb" to settings.dataLimitMb.toString(),
            "rxKbps" to settings.rxKbps.toString(),
            "txKbps" to settings.txKbps.toString(),
            "reconnectMinutes" to settings.reconnectMinutes.toString(),
            "codeTtlMinutes" to settings.codeTtlMinutes.toString(),
            "mailSource" to if (sendingAvailable) "SMTP de la empresa (o del servidor): listo" else "Sin SMTP: configura el correo de la empresa",
            "emailSubject" to settings.emailSubject,
            "networkName" to settings.networkName,
            "portalTitle" to settings.portalTitle,
            "portalSubtitle" to settings.portalSubtitle,
            "logoUrl" to settings.logoUrl.orEmpty(),
            "consentText" to settings.consentText,
            "termsUrl" to settings.termsUrl.orEmpty(),
            "privacyUrl" to settings.privacyUrl.orEmpty(),
            "footerText" to settings.footerText,
            "successUrl" to settings.successUrl.orEmpty(),
            "crmAccessEnabled" to settings.crmAccessEnabled.toString(),
            "crmStageId" to settings.crmStageId?.toString().orEmpty(),
            "crmStage" to crmStageName,
            "crmScheduleMode" to if (settings.crmScheduleMode == "daily") "Diario a una hora" else "Cada intervalo",
            "crmCheckIntervalMinutes" to settings.crmCheckIntervalMinutes.toString(),
            "crmCheckTime" to settings.crmCheckTime.toString(),
        )
        return values
    }

    private fun ResultSet.toSettings() = HotspotSettings(
        domain = getString("domain"),
        networkName = getString("network_name"),
        portalTitle = getString("portal_title"),
        portalSubtitle = getString("portal_subtitle"),
        logoUrl = getString("logo_url"),
        consentText = getString("consent_text"),
        termsUrl = getString("terms_url"),
        privacyUrl = getString("privacy_url"),
        footerText = getString("footer_text"),
        successUrl = getString("success_url"),
        unifiConsoleId = getString("unifi_console_id"),
        unifiSiteId = getString("unifi_site_id"),
        unifiApiKey = getString("unifi_api_key"),
        accessMinutes = getInt("access_minutes"),
        dataLimitMb = getInt("data_limit_mb"),
        rxKbps = getInt("rx_kbps"),
        txKbps = getInt("tx_kbps"),
        reconnectMinutes = getInt("reconnect_minutes"),
        codeTtlMinutes = getInt("code_ttl_minutes"),
        emailSubject = getString("email_subject"),
        crmAccessEnabled = getBoolean("crm_access_enabled"),
        crmStageId = getObject("crm_stage_id", UUID::class.java),
        crmScheduleMode = getString("crm_schedule_mode"),
        crmCheckIntervalMinutes = getInt("crm_check_interval_minutes"),
        crmCheckTime = getObject("crm_check_time", java.time.LocalTime::class.java),
        portalColors = PortalColors(
            primary = getString("portal_primary_color")?.trim(),
            secondary = getString("portal_secondary_color")?.trim(),
            text = getString("portal_text_color")?.trim(),
        ),
    )

    /**
     * Validates and saves the portal colors of Hotspot > Diseno; a blank one goes back to the
     * company's color. Values are #RRGGBB.
     */
    fun saveColors(connection: Connection, primary: String?, secondary: String?, text: String?): PortalColors {
        fun color(value: String?, label: String): String? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val normalized = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
            if (!PortalBrand.isColor(normalized)) throw HotspotValidationException("$label debe tener el formato #RRGGBB, por ejemplo #667EEA.")
            return normalized.uppercase()
        }
        val colors = PortalColors(
            primary = color(primary, "El color principal"),
            secondary = color(secondary, "El color secundario"),
            text = color(text, "El color del texto"),
        )
        connection.prepareStatement(
            "UPDATE hotspot_settings SET portal_primary_color = ?, portal_secondary_color = ?, portal_text_color = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1"
        ).use { update ->
            update.setString(1, colors.primary)
            update.setString(2, colors.secondary)
            update.setString(3, colors.text)
            update.executeUpdate()
        }
        return colors
    }
}

/** Colors set in Hotspot > Diseno (#RRGGBB); null uses the company's color. */
data class PortalColors(val primary: String? = null, val secondary: String? = null, val text: String? = null)

/**
 * Colors of the portal: the ones set in Hotspot > Diseno, else the company's (Empresa > primary,
 * secondary and accent), which are IDC's own colors until the company changes them.
 */
data class PortalBrand(val primary: String, val secondary: String, val onPrimary: String) {
    /** [own] colors of the hotspot over these (the company's). */
    fun with(own: PortalColors): PortalBrand = PortalBrand(
        primary = own.primary?.takeIf(::isColor) ?: primary,
        secondary = own.secondary?.takeIf(::isColor) ?: secondary,
        onPrimary = own.text?.takeIf(::isColor) ?: onPrimary,
    )

    companion object {
        // Same defaults as a new company and the app theme.
        val Idc = PortalBrand(primary = "#667EEA", secondary = "#764BA2", onPrimary = "#FFFFFF")
        private val colorPattern = Regex("^#[0-9A-Fa-f]{6}$")

        fun isColor(value: String) = colorPattern.matches(value)

        /** The company's colors; any missing or invalid one falls back to IDC's. */
        fun of(primary: String?, secondary: String?, onPrimary: String?): PortalBrand = PortalBrand(
            primary = primary?.trim()?.takeIf { colorPattern.matches(it) } ?: Idc.primary,
            secondary = secondary?.trim()?.takeIf { colorPattern.matches(it) } ?: Idc.secondary,
            onPrimary = onPrimary?.trim()?.takeIf { colorPattern.matches(it) } ?: Idc.onPrimary,
        )
    }
}
