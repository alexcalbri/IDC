package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiBarChart
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiDownload
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiImage
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNavItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNavigation
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTheme
import com.ideasdeveloper.idc.ui.UiTone
import java.sql.Connection
import java.time.LocalDate

/**
 * Hotspot administration built only from generic UI pieces, modeled on the Conectiva panel:
 * Dashboard, Visitantes, Reportes, Portal cautivo and Configuracion.
 */
object HotspotScreens {
    const val UiRoute = "/modules/hotspot/ui"
    const val ExportRoute = "/modules/hotspot/report/export"
    private const val VisitorsPageSize = 25

    const val ReportsView = "hotspot.reports.view"
    const val ReportsExport = "hotspot.reports.export"
    const val SettingsView = "hotspot.settings.view"
    const val SettingsManage = "hotspot.settings.manage"

    private data class Section(val id: String, val label: String, val permission: String)

    private val sections = listOf(
        Section("dashboard", "Dashboard", ReportsView),
        Section("visitors", "Visitantes", ReportsView),
        Section("reports", "Reportes", ReportsView),
        Section("portal", "Portal cautivo", SettingsView),
        Section("settings", "Configuracion", SettingsView),
    )

    fun isScreen(id: String) = sections.any { it.id == id }

    fun permissionFor(screen: String): String? = sections.firstOrNull { it.id == screen }?.permission

    // First section the user may open; null when the user has no hotspot permission at all.
    fun home(permissions: ModulePermissions): String? = sections.firstOrNull { permissions.has(it.permission) }?.id

    fun build(
        screen: String,
        tenant: Connection,
        permissions: ModulePermissions,
        params: Map<String, String>,
        domainStatus: String,
        domainActive: Boolean,
        serverMailAvailable: Boolean,
        notice: UiNotice? = null,
        submittedValues: Map<String, String>? = null,
    ): UiScreen {
        val settings = HotspotSettingsRepository.load(tenant)
        val title = sections.first { it.id == screen }.label
        if (!permissions.has(permissionFor(screen)!!)) {
            return frame(settings, permissions, screen, title, UiNotice("No tienes permiso para ver esta seccion.", UiTone.Danger), emptyList())
        }
        val values = submittedValues ?: HotspotSettingsRepository.toFormValues(settings, domainStatus, serverMailAvailable)
        val body = when (screen) {
            "dashboard" -> dashboard(tenant, settings, permissions, domainActive)
            "visitors" -> visitors(tenant, params)
            "reports" -> reports(tenant, permissions, params)
            "portal" -> portal(permissions, values, domainStatus, domainActive)
            else -> settingsForm(permissions, values)
        }
        return frame(settings, permissions, screen, title, notice, body)
    }

    private fun frame(
        settings: HotspotSettings,
        permissions: ModulePermissions,
        selected: String,
        title: String,
        notice: UiNotice?,
        body: List<UiNode>,
    ) = UiScreen(
        title = title,
        theme = UiTheme(
            background = "#05060B",
            surface = "#11121B",
            surfaceAlt = "#181A2A",
            border = "#26293A",
            text = "#F5F5F5",
            muted = "#9EA3B5",
            accent = settings.primaryColor,
            onAccent = settings.textColor,
            success = "#3DD68C",
            warning = "#FF9B4A",
            danger = "#FF6B6B",
        ),
        navigation = UiNavigation(
            title = settings.networkName,
            items = sections.filter { permissions.has(it.permission) }.map { UiNavItem(it.id, it.label, UiNavigate(it.id)) },
            selected = selected,
        ),
        notice = notice,
        body = listOf(UiText(title, UiTextStyle.Title)) + body,
    )

    private fun dashboard(tenant: Connection, settings: HotspotSettings, permissions: ModulePermissions, domainActive: Boolean): List<UiNode> {
        val today = LocalDate.now()
        val todayCount = HotspotReports.summary(tenant, today, today).registrations
        val yesterdayCount = HotspotReports.summary(tenant, today.minusDays(1), today.minusDays(1)).registrations
        val (trend, trendTone) = trend(todayCount, yesterdayCount)
        val newThisMonth = HotspotReports.newEmailsSince(tenant, today.withDayOfMonth(1))
        val health = when {
            !settings.unifiConfigured -> UiStatus("Falta configurar UniFi", UiTone.Warning)
            settings.domain == null -> UiStatus("Falta configurar el dominio del portal", UiTone.Warning)
            !domainActive -> UiStatus("El dominio del portal aun no esta activo", UiTone.Warning)
            else -> UiStatus("Todo funcionando correctamente", UiTone.Success)
        }
        val from = today.minusDays(6)
        return listOf(
            UiGrid(
                minItemWidth = 220,
                children = listOf(
                    UiCard(listOf(UiMetric("Visitantes hoy", todayCount.toString(), trend, trendTone))),
                    UiCard(listOf(UiMetric("Correos obtenidos", HotspotReports.totalEmails(tenant).toString(), "+$newThisMonth este mes", UiTone.Success))),
                    UiCard(listOf(UiText("Red Wi-Fi activa", UiTextStyle.Caption), health)),
                ),
            ),
            UiCard(
                title = "Conexiones por dia",
                actions = listOfNotNull(exportButton(permissions, mapOf("from" to from.toString(), "to" to today.toString()))),
                children = listOf(UiBarChart(HotspotReports.connectionsPerDay(tenant, from, today))),
            ),
            UiCard(
                title = "Ultimos registros",
                actions = listOf(UiButton("Ver todos", UiNavigate("visitors"), style = "secondary")),
                children = listOf(UiTable(HotspotReports.columns, HotspotReports.visitors(tenant, "", 10, 0).rows, "Aun no hay registros.")),
            ),
        )
    }

    private fun visitors(tenant: Connection, params: Map<String, String>): List<UiNode> {
        val search = params["q"].orEmpty().take(100)
        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val result = HotspotReports.visitors(tenant, search, VisitorsPageSize, (page - 1) * VisitorsPageSize)
        val first = if (result.total == 0L) 0 else (page - 1) * VisitorsPageSize + 1
        val last = minOf(result.total, (page * VisitorsPageSize).toLong())
        val searchParams = if (search.isBlank()) emptyMap() else mapOf("q" to search)
        return listOf(
            UiCard(
                listOf(
                    UiForm(
                        id = "search",
                        fields = listOf(UiField("q", "Buscar", placeholder = "Nombre, correo o celular")),
                        values = mapOf("q" to search),
                        submit = UiButton("Buscar", UiNavigate("visitors", formId = "search")),
                    ),
                )
            ),
            UiCard(
                title = "Visitantes",
                children = listOf(
                    UiTable(HotspotReports.columns, result.rows, if (search.isBlank()) "Aun no hay registros." else "Sin resultados para \"$search\"."),
                    UiRow(
                        listOf(
                            UiText("Mostrando $first-$last de ${result.total}", UiTextStyle.Caption),
                            UiButton("Anterior", UiNavigate("visitors", searchParams + ("page" to (page - 1).toString())), style = "secondary", enabled = page > 1),
                            UiButton("Siguiente", UiNavigate("visitors", searchParams + ("page" to (page + 1).toString())), style = "secondary", enabled = last < result.total),
                        )
                    ),
                ),
            ),
        )
    }

    private fun reports(tenant: Connection, permissions: ModulePermissions, params: Map<String, String>): List<UiNode> {
        val today = LocalDate.now()
        var from = params["from"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today.minusDays(29)
        var to = params["to"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        if (to.isBefore(from)) from = to.also { to = from }
        if (from.plusDays(366).isBefore(to)) from = to.minusDays(366)
        val range = mapOf("from" to from.toString(), "to" to to.toString())
        val summary = HotspotReports.summary(tenant, from, to)
        return listOf(
            UiCard(
                listOf(
                    UiForm(
                        id = "range",
                        fields = listOf(UiField("from", "Desde", "date"), UiField("to", "Hasta", "date")),
                        values = range,
                        submit = UiButton("Consultar", UiNavigate("reports", formId = "range")),
                        minFieldWidth = 180,
                    ),
                )
            ),
            UiGrid(
                minItemWidth = 180,
                children = listOf(
                    UiCard(listOf(UiMetric("Registros", summary.registrations.toString()))),
                    UiCard(listOf(UiMetric("Visitantes unicos", summary.visitors.toString(), "Correos distintos"))),
                    UiCard(listOf(UiMetric("Conexiones", summary.connections.toString(), "Autorizadas en UniFi"))),
                    UiCard(listOf(UiMetric("Fallas de UniFi", summary.failures.toString(), captionTone = if (summary.failures > 0) UiTone.Danger else UiTone.Neutral))),
                ),
            ),
            UiCard(
                title = "Conexiones por dia",
                actions = listOfNotNull(exportButton(permissions, range)),
                children = listOf(UiBarChart(HotspotReports.connectionsPerDay(tenant, from, to))),
            ),
            UiCard(
                title = "Registros del periodo",
                children = listOf(UiTable(HotspotReports.columns, HotspotReports.latest(tenant, from, to, limit = 50), "Sin registros en este periodo.")),
            ),
        )
    }

    private fun portal(permissions: ModulePermissions, values: Map<String, String>, domainStatus: String, domainActive: Boolean): List<UiNode> {
        val tone = when {
            domainActive -> UiTone.Success
            domainStatus.startsWith("Error") -> UiTone.Danger
            else -> UiTone.Warning
        }
        val logo = values["logoUrl"].orEmpty().takeIf { it.startsWith("https://") || it.startsWith("http://") }
        return listOfNotNull(
            UiCard(title = "Estado del portal", children = listOf(UiStatus(domainStatus, tone))),
            logo?.let { UiCard(title = "Logo actual", children = listOf(UiImage(it, height = 80, description = "Logo del portal"))) },
            UiCard(listOf(settingsFormNode("portal", portalFields, permissions, values))),
        )
    }

    private fun settingsForm(permissions: ModulePermissions, values: Map<String, String>): List<UiNode> =
        listOf(UiCard(listOf(settingsFormNode("settings", connectionFields, permissions, values))))

    private fun settingsFormNode(id: String, fields: List<UiField>, permissions: ModulePermissions, values: Map<String, String>): UiForm {
        val canManage = permissions.has(SettingsManage)
        return UiForm(
            id = id,
            fields = if (canManage) fields else fields.map { it.copy(readOnly = true) },
            values = values,
            submit = if (canManage) UiButton("Guardar cambios", UiRequest("$UiRoute/$id", formId = id)) else null,
        )
    }

    private fun exportButton(permissions: ModulePermissions, range: Map<String, String>): UiButton? =
        if (!permissions.has(ReportsExport)) null else UiButton(
            label = "Exportar CSV",
            action = UiDownload(ExportRoute, "hotspot-${range["from"]}-${range["to"]}.csv", range),
            style = "secondary",
        )

    private fun trend(today: Long, yesterday: Long): Pair<String, String> = when {
        yesterday == 0L && today == 0L -> "Sin visitantes ayer" to UiTone.Neutral
        yesterday == 0L -> "Ayer no hubo visitantes" to UiTone.Success
        else -> {
            val percent = (today - yesterday) * 1000 / yesterday / 10.0
            (if (percent >= 0) "+$percent% vs ayer" else "$percent% vs ayer") to (if (percent >= 0) UiTone.Success else UiTone.Danger)
        }
    }

    private val portalFields = listOf(
        UiField("domain", "Dominio del portal", section = "Dominio", placeholder = "wifi.miempresa.com",
            help = "Crea un registro DNS A/AAAA que apunte a este servidor. HTTPS se configura solo en unos minutos."),
        UiField("portalUrl", "Direccion para UniFi (portal externo)", readOnly = true, section = "Dominio"),
        UiField("networkName", "Nombre de la red WiFi", required = true, section = "Apariencia"),
        UiField("portalTitle", "Titulo", required = true, section = "Apariencia"),
        UiField("portalSubtitle", "Subtitulo", section = "Apariencia"),
        UiField("logoUrl", "URL del logo", "url", section = "Apariencia"),
        UiField("primaryColor", "Color principal", "color", required = true, section = "Apariencia"),
        UiField("textColor", "Color del texto sobre el color principal", "color", required = true, section = "Apariencia"),
        UiField("consentText", "Texto de consentimiento", "textarea", required = true, section = "Textos legales"),
        UiField("termsUrl", "URL de terminos", "url", section = "Textos legales"),
        UiField("privacyUrl", "URL de privacidad", "url", section = "Textos legales"),
        UiField("footerText", "Pie de pagina", section = "Textos legales"),
        UiField("successUrl", "Abrir al conectarse (URL)", "url", section = "Textos legales"),
    )

    private val connectionFields = listOf(
        UiField("unifiConsoleId", "ID de consola", section = "UniFi", help = "Aparece en la URL de unifi.ui.com/consoles/<ID>/..."),
        UiField("unifiSiteId", "ID de sitio", section = "UniFi"),
        UiField("unifiApiKey", "API key de UniFi", "password", section = "UniFi", help = "Dejala vacia para conservar la clave guardada."),
        UiField("unifiApiKeyConfigured", "API key guardada", readOnly = true, section = "UniFi"),
        UiField("accessMinutes", "Tiempo de acceso (minutos)", "number", required = true, section = "Acceso"),
        UiField("reconnectMinutes", "Tiempo para reconectar (minutos)", "number", required = true, section = "Acceso",
            help = "Un correo no puede volver a conectarse hasta que pase este tiempo desde su ultimo acceso. 0 = sin espera."),
        UiField("codeTtlMinutes", "Vigencia del codigo (minutos)", "number", required = true, section = "Acceso"),
        UiField("dataLimitMb", "Limite de datos (MB, 0 = sin limite)", "number", section = "Acceso"),
        UiField("rxKbps", "Descarga (kbps, 0 = sin limite)", "number", section = "Acceso"),
        UiField("txKbps", "Subida (kbps, 0 = sin limite)", "number", section = "Acceso"),
        UiField("smtpMode", "Enviar correos con", "select", required = true, options = listOf("servidor", "empresa"), section = "Correo",
            help = "servidor: SMTP configurado por el administrador del servidor. empresa: los datos de abajo."),
        UiField("serverMailAvailable", "SMTP del servidor disponible", readOnly = true, section = "Correo"),
        UiField("smtpHost", "Servidor SMTP", section = "Correo"),
        UiField("smtpPort", "Puerto SMTP", "number", section = "Correo"),
        UiField("smtpSecurity", "Seguridad SMTP", "select", options = listOf("starttls", "ssl", "none"), section = "Correo"),
        UiField("smtpUsername", "Usuario SMTP", section = "Correo"),
        UiField("smtpPassword", "Contrasena SMTP", "password", section = "Correo", help = "Dejala vacia para conservar la contrasena guardada."),
        UiField("smtpPasswordConfigured", "Contrasena SMTP guardada", readOnly = true, section = "Correo"),
        UiField("smtpFrom", "Remitente", "email", section = "Correo"),
        UiField("emailSubject", "Asunto del correo", required = true, section = "Correo"),
    )
}
