package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.server.modules.requireModulePermission
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiTone
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    override val definition = ModuleDefinition(
        id = HotspotModule.id,
        displayName = HotspotModule.displayName,
        description = "Portal cautivo WiFi con registro de visitantes, codigo por correo y autorizacion en UniFi.",
        version = "0.2.4",
        permissions = listOf(
            ModulePermissionDefinition(HotspotScreens.SettingsView, "Ver configuracion del hotspot", "Permite consultar la configuracion del portal WiFi."),
            ModulePermissionDefinition(HotspotScreens.SettingsManage, "Configurar hotspot", "Permite cambiar dominio, UniFi, correo, tiempos y apariencia del portal."),
            ModulePermissionDefinition(HotspotScreens.ReportsView, "Ver reportes del hotspot", "Permite consultar el dashboard, visitantes y reportes del portal WiFi."),
            ModulePermissionDefinition(HotspotScreens.ReportsExport, "Exportar reportes del hotspot", "Permite descargar los registros en CSV."),
        ),
        ui = ModuleUiEntry(type = "native", entry = HotspotScreens.UiRoute, home = "dashboard"),
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        HotspotPortal(access).install(route)

        route.route(HotspotScreens.UiRoute) {
            get("/{screen}") {
                val permissions = call.requireModuleAccess(access, HotspotModule.id) ?: return@get
                val screen = call.screenId(permissions) ?: return@get
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.respondScreen(access) { tenant -> render(access, tenant, permissions, screen, params) }
            }

            // Saves the "portal" and "settings" forms and answers with the same screen and a notice.
            post("/{screen}") {
                val permissions = call.requireModuleAccess(access, HotspotModule.id) ?: return@post
                val screen = call.screenId(permissions)?.takeIf { it == "portal" || it == "settings" }
                    ?: return@post call.respond(HttpStatusCode.NotFound, HotspotErrorResponse("UNKNOWN_SCREEN", "Seccion no encontrada."))
                if (!permissions.has(HotspotScreens.SettingsManage)) {
                    return@post call.respond(HttpStatusCode.Forbidden, HotspotErrorResponse("missing_permission", "No tienes permiso para cambiar la configuracion."))
                }
                val values = runCatching { UiJson.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, HotspotErrorResponse("INVALID_BODY", "Datos invalidos."))
                val unifiStep = call.request.queryParameters["unifi"]
                if (screen == "settings" && unifiStep != null) {
                    val id = call.request.queryParameters["id"]
                    return@post call.respondScreen(access) { tenant -> unifiPicker(access, tenant, permissions, unifiStep, id, values) }
                }
                call.respondScreen(access) { tenant -> save(access, tenant, permissions, screen, values) }
            }
        }

        route.get(HotspotScreens.ExportRoute) {
            if (!call.requireModulePermission(access, HotspotModule.id, HotspotScreens.ReportsExport)) return@get
            val range = call.dateRange() ?: return@get
            val companyCode = access.companyCode(call).orEmpty()
            call.respondScreen(access) { tenant ->
                val csv = HotspotReports.csv(tenant, range.first, range.second)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "hotspot-$companyCode-${range.first}-${range.second}.csv").toString(),
                )
                call.respondText(csv, ContentType.Text.CSV.withParameter("charset", "utf-8"))
                null
            }
        }
    }

    private fun render(
        access: ModuleAccessService,
        tenant: Connection,
        permissions: ModulePermissions,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice? = null,
        submittedValues: Map<String, String>? = null,
    ): UiScreen {
        val settings = HotspotSettingsRepository.load(tenant)
        val (status, active) = domainState(access, permissions.companyCode, settings.domain)
        return HotspotScreens.build(screen, tenant, permissions, params, status, active, access.serverMail != null, notice, submittedValues)
    }

    private fun save(access: ModuleAccessService, tenant: Connection, permissions: ModulePermissions, screen: String, values: JsonObject): UiScreen {
        val current = HotspotSettingsRepository.load(tenant)
        val submitted = values.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
        fun failed(message: String): UiScreen {
            // Keep what the user typed so the error can be corrected without retyping.
            val (status, _) = domainState(access, permissions.companyCode, current.domain)
            val shown = HotspotSettingsRepository.toFormValues(current, status, access.serverMail != null) + submitted
            return render(access, tenant, permissions, screen, emptyMap(), UiNotice(message, UiTone.Danger), shown)
        }
        val updated = try {
            HotspotSettingsRepository.merge(current, values)
        } catch (failure: HotspotValidationException) {
            return failed(failure.message.orEmpty())
        }
        if (!registerDomain(access, permissions.companyCode, updated.domain)) {
            return failed("Ese dominio ya esta registrado en este servidor.")
        }
        HotspotSettingsRepository.save(tenant, updated)
        return render(access, tenant, permissions, screen, emptyMap(), UiNotice("Cambios guardados.", UiTone.Success))
    }

    /**
     * Picks the UniFi console and site by name: "consoles" saves a typed API key and lists its
     * consoles, "console" saves one and lists its sites (a single site is chosen right away) and
     * "site" saves the site. Other typed values stay in the form without being saved.
     */
    private fun unifiPicker(
        access: ModuleAccessService,
        tenant: Connection,
        permissions: ModulePermissions,
        step: String,
        id: String?,
        values: JsonObject,
    ): UiScreen {
        var settings = HotspotSettingsRepository.load(tenant)
        val submitted = values.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } - "unifiApiKey"
        fun screen(notice: UiNotice, picker: UiNode? = null): UiScreen {
            val (status, active) = domainState(access, permissions.companyCode, settings.domain)
            val saved = HotspotSettingsRepository.toFormValues(settings, status, access.serverMail != null)
            // The ids just chosen win over what the form had; the API key always shows the saved mask.
            val shown = saved + submitted + mapOf("unifiConsoleId" to saved.getValue("unifiConsoleId"), "unifiSiteId" to saved.getValue("unifiSiteId"))
            return HotspotScreens.build("settings", tenant, permissions, emptyMap(), status, active, access.serverMail != null, notice, shown, listOfNotNull(picker))
        }
        fun store(vararg changes: Pair<String, String>) {
            settings = HotspotSettingsRepository.merge(settings, JsonObject(changes.associate { it.first to JsonPrimitive(it.second) }))
            HotspotSettingsRepository.save(tenant, settings)
        }
        return try {
            when (step) {
                "consoles" -> {
                    values["unifiApiKey"]?.jsonPrimitive?.contentOrNull?.let { store("unifiApiKey" to it) }
                    val apiKey = settings.unifiApiKey ?: return screen(UiNotice("Escribe la API key de UniFi y vuelve a buscar.", UiTone.Warning))
                    val consoles = UnifiClient.consoles(apiKey)
                    screen(UiNotice("${consoles.size} consola(s) encontradas. Elige la del hotspot.", UiTone.Success), HotspotScreens.consolePicker(consoles, settings.unifiConsoleId))
                }
                "console" -> {
                    val consoleId = id?.takeIf { it.isNotBlank() } ?: return screen(UiNotice("Elige una consola de la lista.", UiTone.Warning))
                    if (consoleId != settings.unifiConsoleId) store("unifiConsoleId" to consoleId, "unifiSiteId" to "") else store("unifiConsoleId" to consoleId)
                    val apiKey = settings.unifiApiKey ?: return screen(UiNotice("Escribe la API key de UniFi y vuelve a buscar.", UiTone.Warning))
                    val sites = UnifiClient.sites(apiKey, consoleId)
                    if (sites.size == 1) {
                        store("unifiSiteId" to sites.single().id)
                        screen(UiNotice("Consola guardada y sitio \"${sites.single().name}\" seleccionado.", UiTone.Success))
                    } else {
                        screen(UiNotice("Consola guardada. Ahora elige el sitio.", UiTone.Success), HotspotScreens.sitePicker(sites, settings.unifiSiteId))
                    }
                }
                "site" -> {
                    val siteId = id?.takeIf { it.isNotBlank() } ?: return screen(UiNotice("Elige un sitio de la lista.", UiTone.Warning))
                    store("unifiSiteId" to siteId)
                    screen(UiNotice("Sitio guardado. UniFi quedo configurado.", UiTone.Success))
                }
                else -> screen(UiNotice("Paso de UniFi desconocido.", UiTone.Danger))
            }
        } catch (failure: HotspotValidationException) {
            screen(UiNotice(failure.message.orEmpty(), UiTone.Danger))
        } catch (failure: UnifiException) {
            screen(UiNotice(failure.message.orEmpty(), UiTone.Danger))
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

    // Human-readable domain status and whether the portal is published.
    private fun domainState(access: ModuleAccessService, companyCode: String, domain: String?): Pair<String, Boolean> {
        if (domain == null) return "Sin dominio configurado" to false
        return access.centralConnection().use { central ->
            central.prepareStatement(
                "SELECT status, last_error FROM public_domains WHERE domain = ? AND company_code = ? AND module_id = ?"
            ).use { query ->
                query.setString(1, domain)
                query.setString(2, companyCode)
                query.setString(3, HotspotModule.id)
                query.executeQuery().use { rows ->
                    if (!rows.next()) {
                        "No registrado; guarda la configuracion de nuevo" to false
                    } else when (rows.getString("status")) {
                        "active" -> "Activo: $domain" to true
                        "pending" -> "Pendiente: el servidor esta configurando Nginx y HTTPS (puede tardar unos minutos)" to false
                        "error" -> "Error: ${rows.getString("last_error").orEmpty()} Corrige el DNS y guarda de nuevo para reintentar." to false
                        else -> rows.getString("status") to false
                    }
                }
            }
        }
    }

    private suspend fun ApplicationCall.screenId(permissions: ModulePermissions): String? {
        val requested = parameters["screen"].orEmpty()
        val screen = if (requested == "home") HotspotScreens.home(permissions) else requested.takeIf { HotspotScreens.isScreen(it) }
        if (screen == null) {
            respond(HttpStatusCode.NotFound, HotspotErrorResponse("UNKNOWN_SCREEN", "Seccion no encontrada o sin permisos."))
        }
        return screen
    }

    // Opens the tenant database of the authorized company, runs [block] and responds with its screen.
    private suspend fun ApplicationCall.respondScreen(access: ModuleAccessService, block: suspend (Connection) -> UiScreen?) {
        val companyCode = access.companyCode(this)
            ?: return respond(HttpStatusCode.BadRequest, HotspotErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
        val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode) }
            ?: return respond(HttpStatusCode.Forbidden, HotspotErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
        try {
            val screen = connection.use { tenant -> withContext(Dispatchers.IO) { block(tenant) } }
            if (screen != null) respondText(UiJson.encodeToString(UiScreen.serializer(), screen), ContentType.Application.Json)
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
