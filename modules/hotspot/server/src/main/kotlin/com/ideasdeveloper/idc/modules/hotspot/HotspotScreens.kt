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
import com.ideasdeveloper.idc.ui.UiTone
import java.sql.Connection
import java.time.LocalDate

/**
 * Hotspot administration built only from generic UI pieces, modeled on the Conectiva panel:
 * Dashboard, Visitantes, Reportes, Portal cautivo and Configuracion. The screens carry no colors:
 * the app draws them with the theme the user chose.
 */
object HotspotScreens {
    const val UiRoute = "/modules/hotspot/ui"
    const val ExportRoute = "/modules/hotspot/report/export"
    private const val VisitorsPageSize = 25

    const val ReportsView = "hotspot.reports.view"
    const val ReportsExport = "hotspot.reports.export"
    const val SettingsView = "hotspot.settings.view"
    const val SettingsManage = "hotspot.settings.manage"
    // Opens the module and shows the user's own WiFi code.
    const val View = "hotspot.view"
    // Never granted: only the business owner (and server owners in the server company) hold it.
    const val OwnerOnly = "hotspot.owner"

    private data class Section(val id: String, val label: String, val permission: String)

    private val sections = listOf(
        Section("dashboard", "Dashboard", ReportsView),
        Section("visitors", "Visitantes", ReportsView),
        Section("reports", "Reportes", ReportsView),
        Section("access", "Accesos WiFi", View),
        Section("portal", "Portal cautivo", SettingsView),
        Section("settings", "Configuracion", SettingsView),
        Section("integrations", "Integraciones", OwnerOnly),
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
        // Console and site pickers of the UniFi connection.
        pickers: List<UiNode> = emptyList(),
        // Network form as the user typed it, shown again when saving it failed.
        networkValues: Map<String, String>? = null,
        // Body of sections built outside (Accesos WiFi, Integraciones).
        customBody: List<UiNode>? = null,
    ): UiScreen {
        val settings = HotspotSettingsRepository.load(tenant)
        val title = sections.first { it.id == screen }.label
        if (!permissions.has(permissionFor(screen)!!)) {
            return frame(settings, permissions, screen, title, UiNotice("No tienes permiso para ver esta seccion.", UiTone.Danger), emptyList())
        }
        val values = submittedValues ?: HotspotSettingsRepository.toFormValues(settings, domainStatus, serverMailAvailable)
        val body = customBody ?: when (screen) {
            "dashboard" -> dashboard(tenant, settings, permissions, domainActive)
            "visitors" -> visitors(tenant, params)
            "reports" -> reports(tenant, permissions, params)
            "portal" -> portal(permissions, values, domainStatus, domainActive)
            else -> settingsForm(tenant, permissions, values, pickers, params, networkValues)
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
        val controller = HotspotIntegrations.controller(tenant)
        val health = when {
            controller == null -> UiStatus("Falta elegir el controlador de red en Integraciones", UiTone.Warning)
            !controller.isConfigured(settings) -> UiStatus("Falta configurar ${controller.name}", UiTone.Warning)
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
                    UiCard(listOf(UiMetric("Conexiones", summary.connections.toString(), "Autorizadas en la red"))),
                    UiCard(listOf(UiMetric("Fallas al autorizar", summary.failures.toString(), captionTone = if (summary.failures > 0) UiTone.Danger else UiTone.Neutral))),
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
            UiCard(listOf(settingsFormNode("portal", "portal", portalFields, permissions, values))),
        )
    }

    private fun settingsForm(
        tenant: Connection,
        permissions: ModulePermissions,
        values: Map<String, String>,
        pickers: List<UiNode>,
        params: Map<String, String>,
        networkValues: Map<String, String>?,
    ): List<UiNode> {
        val settings = HotspotSettingsRepository.load(tenant)
        val controller = HotspotIntegrations.controller(tenant)
        // Only the enabled ecosystem's settings are shown; access times and mail work with any of them.
        val controllerCards = when (controller) {
            null -> listOf(
                UiCard(
                    title = "Controlador de red",
                    children = listOf(UiStatus("Elige el controlador de red (UniFi u otro) en Integraciones.", UiTone.Warning)),
                )
            )
            UnifiController -> listOf(unifiCard(settings, permissions, values)) + pickers
            else -> emptyList()
        }
        return controllerCards + networks(tenant, settings, controller, permissions, params, networkValues) +
            UiCard(title = "Acceso", children = listOf(settingsFormNode("settings", "times", accessFields, permissions, values))) +
            UiCard(title = "Correo", children = listOf(settingsFormNode("settings", "mail", mailFields, permissions, values)))
    }

    /**
     * UniFi connection. Buscar consolas shows only while no console is saved; with a console but no
     * site it offers to pick the site. Clearing the console ID and saving brings the search back.
     */
    private fun unifiCard(settings: HotspotSettings, permissions: ModulePermissions, values: Map<String, String>): UiNode {
        val canManage = permissions.has(SettingsManage)
        val consoleId = settings.unifiConsoleId?.takeIf { it.isNotBlank() }
        val (hint, action) = when {
            !canManage -> null to null
            consoleId == null -> "Escribe la API key y pulsa Buscar consolas para elegir la consola y el sitio por su nombre." to
                UiButton("Buscar consolas", unifiStep("consoles"))
            settings.unifiSiteId.isNullOrBlank() -> "Falta elegir el sitio de la consola." to UiButton("Elegir sitio", unifiStep("console", consoleId))
            else -> null to null
        }
        return UiCard(
            title = "Conexion con UniFi",
            actions = listOfNotNull(action),
            children = listOfNotNull(
                hint?.let { UiText(it, UiTextStyle.Caption) },
                settingsFormNode("settings", "unifi", unifiFields, permissions, values),
            ),
        )
    }

    /**
     * WiFi networks and their access type. ?networkId=<id> opens a network in the form; saving and
     * deleting post to the settings route with ?network=save|delete&id=<id>. The SSID is picked from
     * the networks of the enabled controller; without one it is typed.
     */
    private fun networks(
        tenant: Connection,
        settings: HotspotSettings,
        controller: NetworkController?,
        permissions: ModulePermissions,
        params: Map<String, String>,
        submitted: Map<String, String>?,
    ): List<UiNode> {
        val canManage = permissions.has(SettingsManage)
        val networks = HotspotNetworks.list(tenant)
        val list = UiCard(
            title = "Redes WiFi",
            children = listOf(
                UiText(
                    "Segun la red a la que se conecta el visitante, el portal muestra el formulario de registro o pide su codigo de cliente o de usuario. " +
                        "Las redes que no esten en la lista usan el formulario.",
                    UiTextStyle.Caption,
                ),
                if (networks.isEmpty()) {
                    UiText("Aun no hay redes configuradas.", UiTextStyle.Caption)
                } else {
                    UiGrid(minItemWidth = 260, children = networks.map { networkCard(it, canManage) })
                },
            ),
        )
        if (!canManage) return listOf(list)
        val editing = params["networkId"]?.let { id -> networks.firstOrNull { it.id.toString() == id } }
        val console = consoleSsids(settings, controller)
        // Networks already configured are left out, except the one being edited.
        val available = console.ssids?.let { ssids ->
            val taken = networks.map { it.ssid }.toSet() - setOfNotNull(editing?.ssid)
            (ssids + listOfNotNull(editing?.ssid)).distinct().filter { it !in taken }
        }
        val title = editing?.let { "Editar red ${it.ssid}" } ?: "Agregar red"
        if (editing == null && available != null && available.isEmpty()) {
            return listOf(list, UiCard(title = title, children = listOf(UiText("Todas las redes de la consola ya estan configuradas.", UiTextStyle.Caption))))
        }
        val types = HotspotNetworks.existingCustomerTypes(tenant)
        val usersEnabled = HotspotIntegrations.isEnabled(tenant, HotspotIntegrations.Users)
        val form = UiCard(
            title = title,
            actions = if (editing == null) emptyList() else listOf(UiButton("Cancelar", UiNavigate("settings"), style = "secondary")),
            children = listOfNotNull(
                console.problem?.let { UiStatus(it, UiTone.Warning) },
                UiForm(
                    id = "network",
                    fields = listOf(ssidField(available, controller)) + networkFields(types, usersEnabled),
                    values = submitted ?: HotspotNetworks.toFormValues(editing),
                    submit = UiButton("Guardar red", networkAction("save", editing?.id?.toString(), withForm = true)),
                ),
            ),
        )
        return listOf(list, form)
    }

    // SSIDs read from the controller (null when they cannot be read) and why they could not be read.
    private data class ConsoleSsids(val ssids: List<String>?, val problem: String?)

    private fun consoleSsids(settings: HotspotSettings, controller: NetworkController?): ConsoleSsids = when {
        controller == null -> ConsoleSsids(null, null)
        !controller.isConfigured(settings) -> ConsoleSsids(null, "Configura ${controller.name} para elegir la red de la lista de la consola.")
        else -> try {
            ConsoleSsids(controller.wifiNetworks(settings), null)
        } catch (failure: NetworkControllerException) {
            ConsoleSsids(null, "No se pudieron leer las redes de ${controller.name}: ${failure.message.orEmpty()} Escribe el nombre de la red.")
        }
    }

    private fun ssidField(options: List<String>?, controller: NetworkController?): UiField = if (options != null) {
        UiField("ssid", "Red WiFi (SSID)", "dropdown", required = true, options = options,
            help = "Redes WiFi de la consola de ${controller?.name.orEmpty()}.")
    } else {
        UiField("ssid", "Nombre de la red (SSID)", required = true, placeholder = "MiEmpresa_WiFi",
            help = "Exactamente como aparece en el controlador de red; distingue mayusculas y minusculas.")
    }

    private fun networkCard(network: HotspotNetwork, canManage: Boolean): UiNode {
        fun minutes(value: Int?) = value?.let { "$it minutos" } ?: "el de la configuracion general"
        return UiCard(
            title = network.ssid,
            children = listOfNotNull(
                UiText("Acceso: ${HotspotNetworks.modeLabel(network.accessMode)}", UiTextStyle.Caption),
                if (!network.byCustomerCode) null else UiText(
                    "Tipos de cliente: " + network.customerTypes.joinToString(", ").ifEmpty { "todos" },
                    UiTextStyle.Caption,
                ),
                UiText("Tiempo de acceso: ${minutes(network.accessMinutes)}", UiTextStyle.Caption),
                UiText("Tiempo para reconectar: ${minutes(network.reconnectMinutes)}", UiTextStyle.Caption),
                if (!canManage) null else UiRow(
                    listOf(
                        UiButton("Editar", UiNavigate("settings", mapOf("networkId" to network.id.toString())), style = "secondary"),
                        UiButton(
                            "Eliminar",
                            networkAction("delete", network.id.toString(), confirm = "Eliminar la red ${network.ssid}? Volvera a usar el formulario de registro."),
                            style = "danger",
                        ),
                    )
                ),
            ),
        )
    }

    private fun networkAction(step: String, id: String?, withForm: Boolean = false, confirm: String? = null) = UiRequest(
        route = "$UiRoute/settings",
        params = buildMap {
            put("network", step)
            if (id != null) put("id", id)
        },
        confirm = confirm,
        formId = if (withForm) "network" else null,
    )

    private fun networkFields(existingTypes: List<String>, usersEnabled: Boolean) = listOf(
        UiField("accessMode", "Tipo de acceso", "select", required = true, options = HotspotNetworks.modeOptions(usersEnabled),
            help = "Formulario: registro y codigo por correo. Codigo de cliente: el codigo de su ficha en Clientes." +
                if (usersEnabled) " Codigo de usuario: el codigo de Accesos WiFi de cada usuario." else " Activa la integracion Usuarios para el acceso por codigo de usuario."),
        UiField("customerTypes", "Tipos de cliente permitidos", placeholder = "miembro, huesped",
            help = "Solo para acceso por codigo. Separados por coma; vacio = cualquier tipo." +
                if (existingTypes.isEmpty()) "" else " Tipos actuales en Clientes: ${existingTypes.joinToString(", ")}."),
        UiField("accessMinutes", "Tiempo de acceso (minutos)", "number", help = "Vacio usa el de la configuracion general."),
        UiField("reconnectMinutes", "Tiempo para reconectar (minutos)", "number", help = "Vacio usa el de la configuracion general. 0 = sin espera."),
    )

    // UniFi picker steps post the UniFi form so a typed API key survives the round trip.
    fun unifiStep(step: String, id: String? = null) = UiRequest(
        route = "$UiRoute/settings",
        params = buildMap {
            put("unifi", step)
            if (id != null) put("id", id)
        },
        formId = "unifi",
    )

    fun consolePicker(consoles: List<UnifiConsole>, selectedId: String?): UiNode = UiCard(
        title = "Selecciona la consola",
        children = if (consoles.isEmpty()) {
            listOf(UiText("La API key no tiene acceso a ninguna consola.", UiTextStyle.Caption))
        } else {
            listOf(
                UiGrid(
                    minItemWidth = 260,
                    children = consoles.map { console ->
                        UiCard(
                            title = console.name,
                            children = listOfNotNull(
                                UiText(listOf(console.model, console.state).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "Consola UniFi" }, UiTextStyle.Caption),
                                UiText(console.id, UiTextStyle.Caption),
                                if (console.id == selectedId) UiStatus("Seleccionada", UiTone.Success) else null,
                                UiButton("Usar esta consola", unifiStep("console", console.id)),
                            ),
                        )
                    },
                ),
            )
        },
    )

    fun sitePicker(sites: List<UnifiSite>, selectedId: String?): UiNode = UiCard(
        title = "Selecciona el sitio",
        children = if (sites.isEmpty()) {
            listOf(UiText("Esta consola no tiene sitios disponibles en la API de UniFi.", UiTextStyle.Caption))
        } else {
            listOf(
                UiGrid(
                    minItemWidth = 260,
                    children = sites.map { site ->
                        UiCard(
                            title = site.name,
                            children = listOfNotNull(
                                site.reference.takeIf { it.isNotBlank() }?.let { UiText("Referencia: $it", UiTextStyle.Caption) },
                                UiText(site.id, UiTextStyle.Caption),
                                if (site.id == selectedId) UiStatus("Seleccionado", UiTone.Success) else null,
                                UiButton("Usar este sitio", unifiStep("site", site.id)),
                            ),
                        )
                    },
                ),
            )
        },
    )

    // A form of [screen] saved on its own; it only carries the values of its fields.
    private fun settingsFormNode(screen: String, id: String, fields: List<UiField>, permissions: ModulePermissions, values: Map<String, String>): UiForm {
        val canManage = permissions.has(SettingsManage)
        val keys = fields.map { it.key }.toSet()
        return UiForm(
            id = id,
            fields = if (canManage) fields else fields.map { it.copy(readOnly = true) },
            values = values.filterKeys { it in keys },
            submit = if (canManage) UiButton("Guardar cambios", UiRequest("$UiRoute/$screen", formId = id)) else null,
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
        UiField("portalUrl", "Direccion del portal externo (para el controlador de red)", readOnly = true, section = "Dominio"),
        UiField("networkName", "Nombre de la red WiFi", required = true, section = "Apariencia",
            help = "Se muestra cuando el controlador de red no envia el nombre de la red. El acceso por red se configura en Configuracion > Redes WiFi."),
        UiField("portalTitle", "Titulo", required = true, section = "Apariencia"),
        UiField("portalSubtitle", "Subtitulo", section = "Apariencia"),
        UiField("logoUrl", "URL del logo", "url", section = "Apariencia"),
        UiField("primaryColor", "Color principal", "color", required = true, section = "Apariencia"),
        UiField("textColor", "Color del texto sobre el color principal", "color", required = true, section = "Apariencia"),
        UiField("consentText", "Texto de consentimiento", "textarea", required = true, section = "Textos legales"),
        UiField("termsUrl", "URL de terminos", "url", section = "Textos legales"),
        UiField("privacyUrl", "URL de privacidad", "url", section = "Textos legales"),
        UiField("footerText", "Pie de pagina", section = "Textos legales"),
        UiField("successUrl", "Abrir al conectarse (URLs)", section = "Textos legales", placeholder = "https://miempresa.com, https://promo.miempresa.com",
            help = "Una o varias direcciones separadas por coma. Al conectarse se abre una de ellas al azar."),
    )

    // Only shown while UniFi is the enabled network controller.
    val unifiFields = listOf(
        UiField("unifiConsoleId", "ID de consola", help = "Para cambiar de consola borralo y guarda; volvera a aparecer Buscar consolas."),
        UiField("unifiSiteId", "ID de sitio", help = "Se llena al elegir el sitio despues de la consola."),
        UiField("unifiApiKey", "API key de UniFi", "password", help = "Si ya hay una clave guardada se muestra oculta; dejala asi para conservarla."),
    )

    private val accessFields = listOf(
        UiField("accessMinutes", "Tiempo de acceso (minutos)", "number", required = true),
        UiField("reconnectMinutes", "Tiempo para reconectar (minutos)", "number", required = true,
            help = "Un correo no puede volver a conectarse hasta que pase este tiempo desde su ultimo acceso. 0 = sin espera."),
        UiField("codeTtlMinutes", "Vigencia del codigo (minutos)", "number", required = true),
        UiField("dataLimitMb", "Limite de datos (MB, 0 = sin limite)", "number"),
        UiField("rxKbps", "Descarga (kbps, 0 = sin limite)", "number"),
        UiField("txKbps", "Subida (kbps, 0 = sin limite)", "number"),
    )

    // Codes are emailed the same way whatever network controller is enabled.
    private val mailFields = listOf(
        UiField("smtpMode", "Enviar correos con", "select", required = true, options = listOf("servidor", "empresa"),
            help = "servidor: SMTP configurado por el administrador del servidor. empresa: los datos de abajo."),
        UiField("serverMailAvailable", "SMTP del servidor disponible", readOnly = true),
        UiField("smtpHost", "Servidor SMTP"),
        UiField("smtpPort", "Puerto SMTP", "number"),
        UiField("smtpSecurity", "Seguridad SMTP", "select", options = listOf("starttls", "ssl", "none")),
        UiField("smtpUsername", "Usuario SMTP"),
        UiField("smtpPassword", "Contrasena SMTP", "password", help = "Si ya hay una contrasena guardada se muestra oculta; dejala asi para conservarla."),
        UiField("smtpFrom", "Remitente", "email"),
        UiField("emailSubject", "Asunto del correo", required = true),
    )
}
