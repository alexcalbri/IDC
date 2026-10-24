package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.SmtpSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.ResultSet

class HotspotValidationException(message: String) : Exception(message)

data class HotspotSettings(
    val domain: String?,
    val networkName: String,
    val portalTitle: String,
    val portalSubtitle: String,
    val logoUrl: String?,
    val primaryColor: String,
    val textColor: String,
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
    val smtpMode: String,
    val smtpHost: String?,
    val smtpPort: Int,
    val smtpUsername: String?,
    val smtpPassword: String?,
    val smtpFrom: String?,
    val smtpSecurity: String,
    val emailSubject: String,
) {
    // Pages opened after connecting; the setting holds them separated by commas.
    val successUrls: List<String>
        get() = successUrl.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val unifiConfigured: Boolean
        get() = !unifiConsoleId.isNullOrBlank() && !unifiSiteId.isNullOrBlank() && !unifiApiKey.isNullOrBlank()

    fun smtp(serverMail: SmtpSettings?): SmtpSettings? = if (smtpMode == "empresa") {
        SmtpSettings(
            host = smtpHost.orEmpty(),
            port = smtpPort,
            username = smtpUsername.orEmpty(),
            password = smtpPassword.orEmpty(),
            from = smtpFrom.orEmpty(),
            security = smtpSecurity,
        ).takeIf { it.isUsable }
    } else {
        serverMail
    }
}

object HotspotSettingsRepository {
    private val domainPattern = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$")
    private val colorPattern = Regex("^#[0-9A-Fa-f]{6}$")
    private val unifiIdPattern = Regex("^[A-Za-z0-9:._-]{1,200}$")
    private val emailPattern = Regex("^[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]+$")

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
        // Empty or the unchanged mask keeps the saved secret.
        fun secret(key: String, fallback: String?): String? =
            text(key)?.takeIf { it.isNotBlank() && it != SecretMask } ?: fallback

        val merged = HotspotSettings(
            domain = optional("domain", current.domain)?.lowercase()?.removeSuffix("."),
            networkName = required("networkName", current.networkName, "El nombre de la red"),
            portalTitle = required("portalTitle", current.portalTitle, "El titulo del portal"),
            portalSubtitle = if (values.containsKey("portalSubtitle")) text("portalSubtitle").orEmpty() else current.portalSubtitle,
            logoUrl = optional("logoUrl", current.logoUrl),
            primaryColor = required("primaryColor", current.primaryColor, "El color principal"),
            textColor = required("textColor", current.textColor, "El color de texto"),
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
            smtpMode = required("smtpMode", current.smtpMode, "El origen del correo"),
            smtpHost = optional("smtpHost", current.smtpHost),
            smtpPort = number("smtpPort", current.smtpPort, 1..65535, "El puerto SMTP"),
            smtpUsername = optional("smtpUsername", current.smtpUsername),
            smtpPassword = secret("smtpPassword", current.smtpPassword),
            smtpFrom = optional("smtpFrom", current.smtpFrom),
            smtpSecurity = required("smtpSecurity", current.smtpSecurity, "La seguridad SMTP"),
            emailSubject = required("emailSubject", current.emailSubject, "El asunto del correo"),
        )
        validate(merged)
        return merged
    }

    private fun normalizeUrlList(value: String): String? =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }

    private fun validate(settings: HotspotSettings) {
        settings.domain?.let {
            if (it.length > 253 || !domainPattern.matches(it)) throw HotspotValidationException("El dominio no es valido. Ejemplo: wifi.miempresa.com")
        }
        if (!colorPattern.matches(settings.primaryColor) || !colorPattern.matches(settings.textColor)) {
            throw HotspotValidationException("Los colores deben tener formato #RRGGBB.")
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
        if (settings.smtpMode !in setOf("servidor", "empresa")) throw HotspotValidationException("El origen del correo debe ser servidor o empresa.")
        if (settings.smtpSecurity !in setOf("starttls", "ssl", "none")) throw HotspotValidationException("La seguridad SMTP debe ser starttls, ssl o none.")
        settings.smtpFrom?.let {
            if (!emailPattern.matches(it.substringAfterLast('<').removeSuffix(">").trim())) throw HotspotValidationException("El remitente SMTP no es un correo valido.")
        }
    }

    fun save(connection: Connection, settings: HotspotSettings) {
        connection.prepareStatement(
            """
            UPDATE hotspot_settings SET
                domain = ?, network_name = ?, portal_title = ?, portal_subtitle = ?, logo_url = ?,
                primary_color = ?, text_color = ?, consent_text = ?, terms_url = ?, privacy_url = ?,
                footer_text = ?, success_url = ?, unifi_console_id = ?, unifi_site_id = ?, unifi_api_key = ?,
                access_minutes = ?, data_limit_mb = ?, rx_kbps = ?, tx_kbps = ?, reconnect_minutes = ?,
                code_ttl_minutes = ?, smtp_mode = ?, smtp_host = ?, smtp_port = ?, smtp_username = ?,
                smtp_password = ?, smtp_from = ?, smtp_security = ?, email_subject = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = 1
            """.trimIndent()
        ).use { update ->
            listOf<Any?>(
                settings.domain, settings.networkName, settings.portalTitle, settings.portalSubtitle, settings.logoUrl,
                settings.primaryColor, settings.textColor, settings.consentText, settings.termsUrl, settings.privacyUrl,
                settings.footerText, settings.successUrl, settings.unifiConsoleId, settings.unifiSiteId, settings.unifiApiKey,
                settings.accessMinutes, settings.dataLimitMb, settings.rxKbps, settings.txKbps, settings.reconnectMinutes,
                settings.codeTtlMinutes, settings.smtpMode, settings.smtpHost, settings.smtpPort, settings.smtpUsername,
                settings.smtpPassword, settings.smtpFrom, settings.smtpSecurity, settings.emailSubject,
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
        serverMailAvailable: Boolean,
    ): Map<String, String> {
        fun yesNo(value: Boolean) = if (value) "Si" else "No"
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
            "smtpMode" to settings.smtpMode,
            "serverMailAvailable" to yesNo(serverMailAvailable),
            "smtpHost" to settings.smtpHost.orEmpty(),
            "smtpPort" to settings.smtpPort.toString(),
            "smtpUsername" to settings.smtpUsername.orEmpty(),
            "smtpPassword" to masked(settings.smtpPassword),
            "smtpFrom" to settings.smtpFrom.orEmpty(),
            "smtpSecurity" to settings.smtpSecurity,
            "emailSubject" to settings.emailSubject,
            "networkName" to settings.networkName,
            "portalTitle" to settings.portalTitle,
            "portalSubtitle" to settings.portalSubtitle,
            "logoUrl" to settings.logoUrl.orEmpty(),
            "primaryColor" to settings.primaryColor,
            "textColor" to settings.textColor,
            "consentText" to settings.consentText,
            "termsUrl" to settings.termsUrl.orEmpty(),
            "privacyUrl" to settings.privacyUrl.orEmpty(),
            "footerText" to settings.footerText,
            "successUrl" to settings.successUrl.orEmpty(),
        )
        return values
    }

    private fun ResultSet.toSettings() = HotspotSettings(
        domain = getString("domain"),
        networkName = getString("network_name"),
        portalTitle = getString("portal_title"),
        portalSubtitle = getString("portal_subtitle"),
        logoUrl = getString("logo_url"),
        primaryColor = getString("primary_color"),
        textColor = getString("text_color"),
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
        smtpMode = getString("smtp_mode"),
        smtpHost = getString("smtp_host"),
        smtpPort = getInt("smtp_port"),
        smtpUsername = getString("smtp_username"),
        smtpPassword = getString("smtp_password"),
        smtpFrom = getString("smtp_from"),
        smtpSecurity = getString("smtp_security"),
        emailSubject = getString("email_subject"),
    )
}
