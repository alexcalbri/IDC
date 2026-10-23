package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleActionDefinition
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleFieldDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ModuleViewDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.requireModulePermission
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate

object HotspotServerModule : ServerModule {
    override val migrationPaths = listOf(
        "modules/hotspot/migrations/V001__create_hotspot.sql",
    )

    override val runtimeTables = listOf(
        "hotspot_settings",
        "hotspot_registrations",
        "hotspot_codes",
        "hotspot_accesses",
    )

    private const val SettingsRoute = "/modules/hotspot/settings"
    private const val ReportRoute = "/modules/hotspot/report"

    override val definition = ModuleDefinition(
        id = HotspotModule.id,
        displayName = HotspotModule.displayName,
        description = "Portal cautivo WiFi con registro de visitantes, codigo por correo y autorizacion en UniFi.",
        version = "0.1.0",
        permissions = listOf(
            ModulePermissionDefinition("hotspot.settings.view", "Ver configuracion del hotspot", "Permite consultar la configuracion del portal WiFi."),
            ModulePermissionDefinition("hotspot.settings.manage", "Configurar hotspot", "Permite cambiar dominio, UniFi, correo, tiempos y apariencia del portal."),
            ModulePermissionDefinition("hotspot.reports.view", "Ver reportes del hotspot", "Permite consultar registros y conexiones del portal WiFi."),
            ModulePermissionDefinition("hotspot.reports.export", "Exportar reportes del hotspot", "Permite descargar los registros en CSV."),
        ),
        views = listOf(
            ModuleViewDefinition(
                id = "report",
                title = "Reporte",
                route = ReportRoute,
                kind = "report",
                fields = listOf(
                    ModuleFieldDefinition("from", "Desde", "date"),
                    ModuleFieldDefinition("to", "Hasta", "date"),
                ),
                actions = listOf(
                    ModuleActionDefinition("load", "Consultar", ReportRoute, "GET", "hotspot.reports.view"),
                    ModuleActionDefinition("export", "Exportar CSV", "$ReportRoute/export", "GET", "hotspot.reports.export"),
                ),
            ),
            ModuleViewDefinition(
                id = "settings",
                title = "Configuracion",
                route = SettingsRoute,
                kind = "form",
                fields = settingsFields(),
                actions = listOf(
                    ModuleActionDefinition("load", "Cargar", SettingsRoute, "GET", "hotspot.settings.view"),
                    ModuleActionDefinition("save", "Guardar", SettingsRoute, "PUT", "hotspot.settings.manage"),
                ),
            ),
        ),
    )

    private fun settingsFields(): List<ModuleFieldDefinition> {
        val domain = "Dominio"
        val unifi = "UniFi"
        val access = "Acceso"
        val mail = "Correo"
        val look = "Apariencia del portal"
        return listOf(
            ModuleFieldDefinition("domain", "Dominio del portal", "text", section = domain,
                help = "Ej: wifi.miempresa.com. Crea un registro DNS A/AAAA que apunte a este servidor; HTTPS se configura solo en unos minutos."),
            ModuleFieldDefinition("portalUrl", "Direccion para UniFi (portal externo)", "text", readOnly = true, section = domain),
            ModuleFieldDefinition("domainStatus", "Estado del dominio", "text", readOnly = true, section = domain),

            ModuleFieldDefinition("unifiConsoleId", "ID de consola", "text", section = unifi,
                help = "Aparece en la URL de unifi.ui.com/consoles/<ID>/..."),
            ModuleFieldDefinition("unifiSiteId", "ID de sitio", "text", section = unifi),
            ModuleFieldDefinition("unifiApiKey", "API key de UniFi", "password", section = unifi,
                help = "Dejalo vacio para conservar la clave guardada."),
            ModuleFieldDefinition("unifiApiKeyConfigured", "API key guardada", "text", readOnly = true, section = unifi),

            ModuleFieldDefinition("accessMinutes", "Tiempo de acceso (minutos)", "number", required = true, section = access),
            ModuleFieldDefinition("reconnectMinutes", "Tiempo para reconectar (minutos)", "number", required = true, section = access,
                help = "Un mismo correo no puede volver a conectarse hasta que pase este tiempo desde su ultimo acceso. 0 = sin espera."),
            ModuleFieldDefinition("codeTtlMinutes", "Vigencia del codigo (minutos)", "number", required = true, section = access),
            ModuleFieldDefinition("dataLimitMb", "Limite de datos (MB, 0 = sin limite)", "number", section = access),
            ModuleFieldDefinition("rxKbps", "Velocidad de descarga (kbps, 0 = sin limite)", "number", section = access),
            ModuleFieldDefinition("txKbps", "Velocidad de subida (kbps, 0 = sin limite)", "number", section = access),

            ModuleFieldDefinition("smtpMode", "Enviar correos con", "select", required = true, options = listOf("servidor", "empresa"), section = mail,
                help = "servidor: usa el SMTP configurado por el administrador del servidor. empresa: usa los datos de abajo."),
            ModuleFieldDefinition("serverMailAvailable", "SMTP del servidor disponible", "text", readOnly = true, section = mail),
            ModuleFieldDefinition("smtpHost", "Servidor SMTP", "text", section = mail),
            ModuleFieldDefinition("smtpPort", "Puerto SMTP", "number", section = mail),
            ModuleFieldDefinition("smtpSecurity", "Seguridad SMTP", "select", options = listOf("starttls", "ssl", "none"), section = mail),
            ModuleFieldDefinition("smtpUsername", "Usuario SMTP", "text", section = mail),
            ModuleFieldDefinition("smtpPassword", "Contrasena SMTP", "password", section = mail,
                help = "Dejala vacia para conservar la contrasena guardada."),
            ModuleFieldDefinition("smtpPasswordConfigured", "Contrasena SMTP guardada", "text", readOnly = true, section = mail),
            ModuleFieldDefinition("smtpFrom", "Remitente", "email", section = mail),
            ModuleFieldDefinition("emailSubject", "Asunto del correo", "text", required = true, section = mail),

            ModuleFieldDefinition("networkName", "Nombre de la red WiFi", "text", required = true, section = look),
            ModuleFieldDefinition("portalTitle", "Titulo", "text", required = true, section = look),
            ModuleFieldDefinition("portalSubtitle", "Subtitulo", "text", section = look),
            ModuleFieldDefinition("logoUrl", "URL del logo", "url", section = look),
            ModuleFieldDefinition("primaryColor", "Color principal", "color", required = true, section = look),
            ModuleFieldDefinition("textColor", "Color del texto sobre el color principal", "color", required = true, section = look),
            ModuleFieldDefinition("consentText", "Texto de consentimiento", "textarea", required = true, section = look),
            ModuleFieldDefinition("termsUrl", "URL de terminos", "url", section = look),
            ModuleFieldDefinition("privacyUrl", "URL de privacidad", "url", section = look),
            ModuleFieldDefinition("footerText", "Pie de pagina", "text", section = look),
            ModuleFieldDefinition("successUrl", "Abrir al conectarse (URL)", "url", section = look),
        )
    }

    override fun routes(route: Route, access: ModuleAccessService) {
        HotspotPortal(access).install(route)

        route.route("/modules/hotspot") {
            get("/settings") {
                if (!call.requireModulePermission(access, HotspotModule.id, "hotspot.settings.view")) return@get
                call.withTenant(access) { tenant, companyCode ->
                    val settings = HotspotSettingsRepository.load(tenant)
                    val status = domainStatus(access, companyCode, settings.domain)
                    call.respond(HttpStatusCode.OK, HotspotSettingsRepository.toFormValues(settings, status, access.serverMail != null))
                }
            }

            put("/settings") {
                if (!call.requireModulePermission(access, HotspotModule.id, "hotspot.settings.manage")) return@put
                val values = call.receive<JsonObject>()
                call.withTenant(access) { tenant, companyCode ->
                    val current = HotspotSettingsRepository.load(tenant)
                    val updated = try {
                        HotspotSettingsRepository.merge(current, values)
                    } catch (failure: HotspotValidationException) {
                        return@withTenant call.respond(HttpStatusCode.BadRequest, HotspotErrorResponse("INVALID_SETTINGS", failure.message.orEmpty()))
                    }
                    if (!registerDomain(access, companyCode, updated.domain)) {
                        return@withTenant call.respond(HttpStatusCode.Conflict, HotspotErrorResponse("DOMAIN_IN_USE", "Ese dominio ya esta registrado en este servidor."))
                    }
                    HotspotSettingsRepository.save(tenant, updated)
                    val status = domainStatus(access, companyCode, updated.domain)
                    call.respond(HttpStatusCode.OK, HotspotSettingsRepository.toFormValues(updated, status, access.serverMail != null))
                }
            }

            get("/report") {
                if (!call.requireModulePermission(access, HotspotModule.id, "hotspot.reports.view")) return@get
                val range = call.dateRange() ?: return@get
                call.withTenant(access) { tenant, _ ->
                    call.respond(HttpStatusCode.OK, HotspotReports.report(tenant, range.first, range.second))
                }
            }

            get("/report/export") {
                if (!call.requireModulePermission(access, HotspotModule.id, "hotspot.reports.export")) return@get
                val range = call.dateRange() ?: return@get
                call.withTenant(access) { tenant, companyCode ->
                    val csv = HotspotReports.csv(tenant, range.first, range.second)
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "hotspot-$companyCode-${range.first}-${range.second}.csv").toString(),
                    )
                    call.respondText(csv, ContentType.Text.CSV.withParameter("charset", "utf-8"))
                }
            }
        }
    }

    /**
     * Keeps central public_domains in sync with the company's hotspot domain. A changed or failed
     * domain goes back to 'pending' so the root helper publishes it again. Returns false when the
     * domain belongs to another company.
     */
    private fun registerDomain(access: ModuleAccessService, companyCode: String, domain: String?): Boolean =
        access.centralConnection().use { central ->
            central.autoCommit = false
            try {
                val owner = domain?.let {
                    central.prepareStatement("SELECT company_code, module_id FROM public_domains WHERE domain = ? FOR UPDATE").use { query ->
                        query.setString(1, it)
                        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
                    }
                }
                if (owner != null && owner != (companyCode to HotspotModule.id)) {
                    central.rollback()
                    return@use false
                }
                central.prepareStatement(
                    "DELETE FROM public_domains WHERE company_code = ? AND module_id = ? AND domain IS DISTINCT FROM ?"
                ).use { delete ->
                    delete.setString(1, companyCode)
                    delete.setString(2, HotspotModule.id)
                    delete.setString(3, domain)
                    delete.executeUpdate()
                }
                if (domain != null) {
                    central.prepareStatement(
                        """
                        INSERT INTO public_domains (domain, company_code, module_id, status)
                        VALUES (?, ?, ?, 'pending')
                        ON CONFLICT (domain) DO UPDATE SET
                            status = CASE WHEN public_domains.status = 'error' THEN 'pending' ELSE public_domains.status END,
                            last_error = CASE WHEN public_domains.status = 'error' THEN NULL ELSE public_domains.last_error END,
                            updated_at = CURRENT_TIMESTAMP
                        """.trimIndent()
                    ).use { insert ->
                        insert.setString(1, domain)
                        insert.setString(2, companyCode)
                        insert.setString(3, HotspotModule.id)
                        insert.executeUpdate()
                    }
                }
                central.commit()
                true
            } catch (failure: Exception) {
                central.rollback()
                throw failure
            } finally {
                central.autoCommit = true
            }
        }

    private fun domainStatus(access: ModuleAccessService, companyCode: String, domain: String?): String {
        if (domain == null) return "Sin dominio configurado"
        return access.centralConnection().use { central ->
            central.prepareStatement(
                "SELECT status, last_error FROM public_domains WHERE domain = ? AND company_code = ? AND module_id = ?"
            ).use { query ->
                query.setString(1, domain)
                query.setString(2, companyCode)
                query.setString(3, HotspotModule.id)
                query.executeQuery().use { rows ->
                    if (!rows.next()) {
                        "No registrado; guarda la configuracion de nuevo"
                    } else when (rows.getString("status")) {
                        "active" -> "Activo"
                        "pending" -> "Pendiente: el servidor esta configurando Nginx y HTTPS (puede tardar unos minutos)"
                        "error" -> "Error: ${rows.getString("last_error").orEmpty()} Corrige el DNS y guarda de nuevo para reintentar."
                        else -> rows.getString("status")
                    }
                }
            }
        }
    }

    private suspend fun ApplicationCall.withTenant(access: ModuleAccessService, block: suspend (Connection, String) -> Unit) {
        val companyCode = access.companyCode(this)
            ?: return respond(HttpStatusCode.BadRequest, HotspotErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
        val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode) }
            ?: return respond(HttpStatusCode.Forbidden, HotspotErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
        try {
            connection.use { tenant -> block(tenant, companyCode) }
        } catch (failure: SQLException) {
            respond(HttpStatusCode.InternalServerError, HotspotErrorResponse("DATABASE_ERROR", "No se pudo completar la operacion."))
        }
    }

    private suspend fun ApplicationCall.dateRange(): Pair<LocalDate, LocalDate>? {
        val today = LocalDate.now()
        val from = request.queryParameters["from"]?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return invalidRange() }
            ?: today.minusDays(6)
        val to = request.queryParameters["to"]?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return invalidRange() }
            ?: today
        if (to.isBefore(from) || from.plusDays(366).isBefore(to)) return invalidRange()
        return from to to
    }

    private suspend fun ApplicationCall.invalidRange(): Pair<LocalDate, LocalDate>? {
        respond(HttpStatusCode.BadRequest, HotspotErrorResponse("INVALID_RANGE", "Usa fechas AAAA-MM-DD; el rango maximo es de un ano."))
        return null
    }
}

@Serializable
data class HotspotErrorResponse(val code: String, val message: String)

class HotspotServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = HotspotServerModule
}
