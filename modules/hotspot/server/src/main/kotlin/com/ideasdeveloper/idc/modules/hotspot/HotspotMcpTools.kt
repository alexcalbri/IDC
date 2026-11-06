package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.ui.UiTone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Agent tools of the hotspot, with the same permissions and validations as its screens. Secrets
 * (UniFi API key, SMTP password) and the UniFi console are not offered: they are typed or picked in
 * the app, so credentials never pass through the agent.
 */
object HotspotMcpTools {
    private const val MaxRangeDays = 366L

    private val range = mapOf(
        "from" to McpSchema.date("Primer dia (incluido); por defecto hace 6 dias."),
        "to" to McpSchema.date("Ultimo dia (incluido); por defecto hoy."),
    )

    // Settings an agent may change, by the key HotspotSettingsRepository.merge reads.
    private val settingArguments: Map<String, JsonObject> = mapOf(
        "domain" to McpSchema.string("Dominio del portal, por ejemplo wifi.miempresa.com; vacio lo quita. Al cambiarlo el servidor configura Nginx y HTTPS."),
        "networkName" to McpSchema.string("Nombre de la red WiFi que muestra el portal."),
        "portalTitle" to McpSchema.string("Titulo del portal."),
        "portalSubtitle" to McpSchema.string("Subtitulo del portal; vacio lo quita."),
        "logoUrl" to McpSchema.string("URL del logo (https://); vacio lo quita."),
        "primaryColor" to McpSchema.string("Color principal #RRGGBB."),
        "textColor" to McpSchema.string("Color del texto sobre el color principal, #RRGGBB."),
        "consentText" to McpSchema.string("Texto de consentimiento que acepta el visitante."),
        "termsUrl" to McpSchema.string("URL de terminos; vacio la quita."),
        "privacyUrl" to McpSchema.string("URL de privacidad; vacio la quita."),
        "footerText" to McpSchema.string("Pie de pagina; vacio lo quita."),
        "successUrl" to McpSchema.string("URLs que se abren al conectarse, separadas por comas; vacio las quita."),
        "accessMinutes" to McpSchema.integer("Tiempo de acceso general en minutos.", 1, 10080),
        "reconnectMinutes" to McpSchema.integer("Minutos que un correo espera para volver a conectarse.", 0, 525600),
        "codeTtlMinutes" to McpSchema.integer("Vigencia del codigo enviado por correo, en minutos.", 1, 1440),
        "dataLimitMb" to McpSchema.integer("Limite de datos por sesion en MB; 0 sin limite.", 0, 1048576),
        "rxKbps" to McpSchema.integer("Velocidad de descarga en kbps; 0 sin limite.", 0, 10000000),
        "txKbps" to McpSchema.integer("Velocidad de subida en kbps; 0 sin limite.", 0, 10000000),
        "smtpMode" to McpSchema.string("Con que se envian los correos: el SMTP del servidor o el de la empresa.", listOf("servidor", "empresa")),
        "smtpHost" to McpSchema.string("Servidor SMTP de la empresa."),
        "smtpPort" to McpSchema.integer("Puerto SMTP.", 1, 65535),
        "smtpSecurity" to McpSchema.string("Seguridad SMTP.", listOf("starttls", "ssl", "none")),
        "smtpUsername" to McpSchema.string("Usuario SMTP."),
        "smtpFrom" to McpSchema.string("Remitente de los correos.", format = "email"),
        "emailSubject" to McpSchema.string("Asunto del correo con el codigo."),
    )

    // What this module tells agents about itself (see ModuleMcp).
    val mcp: ModuleMcp
        get() = ModuleMcp(Instructions, tools)

    private const val Instructions =
        "Hotspot es el portal cautivo del WiFi de la empresa. Cada red (SSID) tiene un tipo de acceso: formulario (el visitante se registra " +
            "con su correo y recibe un codigo), codigo de cliente (el codigo de Clientes) o codigo de usuario (el codigo WiFi de cada usuario). " +
            "Las redes no configuradas usan el formulario y los tiempos generales. Los cambios de configuracion, redes e integraciones afectan " +
            "de inmediato a quienes se conectan: confirma con el usuario antes. Las credenciales (API key de UniFi, contrasena SMTP) se " +
            "configuran solo en la app; nunca las pidas ni las recibas."

    val tools = listOf(
        McpTool(
            name = "summary",
            title = "Resumen del hotspot",
            description = "Registros, visitantes unicos, conexiones autorizadas y fallidas en un rango de fechas, con las conexiones de cada dia " +
                "y el total de correos obtenidos. Maximo 366 dias.",
            permission = HotspotScreens.ReportsView,
            readOnly = true,
            inputSchema = McpSchema.obj(range),
        ) { call -> summary(call) },
        McpTool(
            name = "search_visitors",
            title = "Buscar visitantes del WiFi",
            description = "Busca registros del portal WiFi por nombre, correo o celular, del mas reciente al mas antiguo. Sin texto devuelve los ultimos.",
            permission = HotspotScreens.ReportsView,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "query" to McpSchema.string("Texto a buscar (opcional)."),
                    "limit" to McpSchema.integer("Cantidad maxima de resultados.", 1, 100),
                    "offset" to McpSchema.integer("Resultados a saltar, para paginar.", 0),
                ),
            ),
        ) { call -> visitors(call) },
        McpTool(
            name = "get_settings",
            title = "Configuracion del hotspot",
            description = "Devuelve la configuracion del portal: dominio y su estado, apariencia, textos legales, tiempos, limites, correo e integraciones. " +
                "Las claves no se muestran; solo si estan configuradas.",
            permission = HotspotScreens.SettingsView,
            readOnly = true,
        ) { call -> settings(call) },
        McpTool(
            name = "update_settings",
            title = "Cambiar configuracion del hotspot",
            description = "Cambia la configuracion del portal. Solo cambia los campos enviados. Afecta de inmediato a los visitantes del WiFi: " +
                "confirma los cambios con el usuario antes. La API key de UniFi, la consola y la contrasena SMTP se configuran en la app.",
            permission = HotspotScreens.SettingsManage,
            inputSchema = McpSchema.obj(settingArguments),
        ) { call -> updateSettings(call) },
        McpTool(
            name = "list_networks",
            title = "Redes WiFi del hotspot",
            description = "Lista las redes (SSID) configuradas con su id, tipo de acceso (formulario, codigo de cliente o de usuario), quien entra " +
                "y los tiempos de acceso y reconexion, y las fuentes de acceso que se pueden elegir. Las redes no listadas usan el formulario.",
            permission = HotspotScreens.SettingsView,
            readOnly = true,
        ) { call -> networks(call) },
        McpTool(
            name = "save_network",
            title = "Crear o editar red WiFi",
            description = "Crea una red (sin id) o edita una existente (con su id; solo cambia lo enviado). accessMode codigo_cliente necesita " +
                "whoGetsIn con ids de list_networks; codigo_usuario necesita la integracion Usuarios. Confirma con el usuario antes.",
            permission = HotspotScreens.SettingsManage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.uuid("Id de la red a editar; sin id se crea una nueva."),
                    "ssid" to McpSchema.string("Nombre exacto de la red WiFi (SSID), hasta 32 bytes."),
                    "accessMode" to McpSchema.string(
                        "formulario: registro con correo; codigo_cliente: codigo de Clientes; codigo_usuario: codigo de cada usuario.",
                        listOf(HotspotNetworks.Form, HotspotNetworks.CustomerCode, HotspotNetworks.UserCode),
                    ),
                    "whoGetsIn" to buildJsonObject {
                        put("type", "array")
                        put("description", "Solo codigo_cliente: ids de las fuentes de acceso (accessSources de list_networks).")
                        put("items", buildJsonObject { put("type", "string") })
                    },
                    "accessMinutes" to McpSchema.integer("Tiempo de acceso de esta red; 0 usa el general.", 0, 10080),
                    "reconnectMinutes" to McpSchema.integer("Tiempo para reconectar de esta red; -1 usa el general.", -1, 525600),
                ),
            ),
        ) { call -> saveNetwork(call) },
        McpTool(
            name = "delete_network",
            title = "Eliminar red WiFi",
            description = "Elimina la configuracion de una red; sus visitantes vuelven al formulario de registro y a los tiempos generales. Confirma con el usuario antes.",
            permission = HotspotScreens.SettingsManage,
            destructive = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id de la red (list_networks).")), required = listOf("id")),
        ) { call -> deleteNetwork(call) },
        McpTool(
            name = "set_integration",
            title = "Activar o desactivar integracion",
            description = "Activa o desactiva una integracion del hotspot (ids en get_settings). Activar un controlador de red desactiva el otro. " +
                "Solo el dueno de la empresa o del servidor. Confirma con el usuario antes.",
            permission = HotspotScreens.SettingsManage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.string("Id de la integracion."),
                    "enabled" to McpSchema.boolean("true para activarla, false para desactivarla."),
                ),
                required = listOf("id", "enabled"),
            ),
        ) { call -> setIntegration(call) },
        McpTool(
            name = "my_wifi_code",
            title = "Mi codigo WiFi",
            description = "Devuelve el codigo de acceso WiFi del usuario conectado, para redes con acceso por codigo de usuario.",
            permission = HotspotScreens.View,
            // Creates the code the first time, exactly like opening "Accesos WiFi"; no data of anyone else changes.
            readOnly = true,
        ) { call -> myCode(call) },
        McpTool(
            name = "export_report",
            title = "Exportar registros del hotspot",
            description = "Devuelve en CSV (separado por punto y coma) los registros del portal de un rango de fechas, como la descarga de Reportes. Maximo 366 dias.",
            permission = HotspotScreens.ReportsExport,
            readOnly = true,
            inputSchema = McpSchema.obj(range),
        ) { call -> export(call) },
        McpTool(
            name = "list_user_codes",
            title = "Codigos WiFi de los usuarios",
            description = "Lista el codigo WiFi de cada usuario de la empresa (y de los duenos del servidor en la empresa del servidor). Solo el dueno de la empresa o del servidor.",
            permission = HotspotScreens.View,
            readOnly = true,
        ) { call -> userCodes(call) },
        McpTool(
            name = "list_access_grants",
            title = "Accesos a redes de otras empresas",
            description = "Solo duenos del servidor, en la empresa del servidor: usuarios que pueden entrar con su codigo a redes \"codigo de usuario\" de otras " +
                "empresas, y las redes que se pueden dar.",
            permission = HotspotScreens.View,
            readOnly = true,
        ) { call -> grants(call) },
        McpTool(
            name = "add_access_grant",
            title = "Dar acceso a red de otra empresa",
            description = "Solo duenos del servidor, en la empresa del servidor: permite a un usuario conectarse con su codigo a una red de otra empresa.$ConfirmFirst",
            permission = HotspotScreens.View,
            inputSchema = McpSchema.obj(
                mapOf(
                    "company" to McpSchema.string("Codigo de la empresa (list_access_grants)."),
                    "ssid" to McpSchema.string("Red (SSID) con acceso por codigo de usuario."),
                    "user" to McpSchema.string("Usuario de la empresa del servidor: su usuario de login o su nombre completo."),
                ),
                required = listOf("company", "ssid", "user"),
            ),
        ) { call -> addGrant(call) },
        McpTool(
            name = "remove_access_grant",
            title = "Quitar acceso a red de otra empresa",
            description = "Solo duenos del servidor, en la empresa del servidor: quita un acceso (id de list_access_grants).$ConfirmFirst",
            permission = HotspotScreens.View,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del acceso.")), required = listOf("id")),
        ) { call -> removeGrant(call) },
        McpTool(
            name = "unifi_list_consoles",
            title = "Consolas UniFi",
            description = "Lista las consolas UniFi de la cuenta con la API key guardada en la app, para elegir la del hotspot.",
            permission = HotspotScreens.SettingsManage,
            readOnly = true,
        ) { call -> consoles(call) },
        McpTool(
            name = "unifi_choose_console",
            title = "Elegir consola UniFi",
            description = "Guarda la consola UniFi del hotspot y lista sus sitios; si tiene uno solo lo elige.$ConfirmFirst",
            permission = HotspotScreens.SettingsManage,
            inputSchema = McpSchema.obj(mapOf("consoleId" to McpSchema.string("Id de la consola (unifi_list_consoles).")), required = listOf("consoleId")),
        ) { call -> chooseConsole(call) },
        McpTool(
            name = "unifi_choose_site",
            title = "Elegir sitio UniFi",
            description = "Guarda el sitio de la consola UniFi donde se autoriza a los visitantes.$ConfirmFirst",
            permission = HotspotScreens.SettingsManage,
            inputSchema = McpSchema.obj(mapOf("siteId" to McpSchema.string("Id del sitio (unifi_choose_console).")), required = listOf("siteId")),
        ) { call -> chooseSite(call) },
        McpTool(
            name = "list_controller_ssids",
            title = "Redes del controlador",
            description = "Lista las redes WiFi (SSID) configuradas en el controlador de red, para crear redes con save_network.",
            permission = HotspotScreens.SettingsView,
            readOnly = true,
        ) { call -> controllerSsids(call) },
    )

    private const val ConfirmFirst = " Confirma con el usuario antes."

    private fun summary(call: McpToolCall): JsonElement {
        val to = call.date("to") ?: LocalDate.now()
        val from = call.date("from") ?: to.minusDays(6)
        if (from.isAfter(to)) throw McpToolException("'from' debe ser anterior o igual a 'to'.")
        if (ChronoUnit.DAYS.between(from, to) >= MaxRangeDays) throw McpToolException("El rango no puede pasar de $MaxRangeDays dias.")
        val totals = HotspotReports.summary(call.tenant, from, to)
        // One point per day of the range, in order.
        val perDay = HotspotReports.connectionsPerDay(call.tenant, from, to).mapIndexed { index, point ->
            buildJsonObject {
                put("date", from.plusDays(index.toLong()).toString())
                put("connections", point.value.toLong())
            }
        }
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("registrations", totals.registrations)
            put("uniqueVisitors", totals.visitors)
            put("authorizedConnections", totals.connections)
            put("failedConnections", totals.failures)
            put("connectionsPerDay", JsonArray(perDay))
            put("totalEmails", HotspotReports.totalEmails(call.tenant))
            put("newEmailsInRange", HotspotReports.newEmailsSince(call.tenant, from))
        }
    }

    private fun visitors(call: McpToolCall): JsonElement {
        val limit = call.int("limit", 25, 1..100)
        val offset = call.int("offset", 0, 0..1_000_000)
        val page = HotspotReports.visitors(call.tenant, call.string("query", 100), limit, offset)
        return buildJsonObject {
            put("total", page.total)
            put("visitors", JsonArray(page.rows.map { row -> JsonObject(row.mapValues { JsonPrimitive(it.value) }) }))
        }
    }

    private fun settings(call: McpToolCall): JsonElement {
        val settings = HotspotSettingsRepository.load(call.tenant)
        val (domainStatus, domainActive) = HotspotServerModule.domainState(call.access, call.permissions.companyCode, settings.domain)
        val controller = HotspotIntegrations.controller(call.tenant)
        val enabled = HotspotIntegrations.enabled(call.tenant)
        fun text(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull
        return buildJsonObject {
            put("domain", text(settings.domain))
            put("domainStatus", domainStatus)
            put("portalPublished", domainActive)
            put("networkName", settings.networkName)
            put("portalTitle", settings.portalTitle)
            put("portalSubtitle", settings.portalSubtitle)
            put("logoUrl", text(settings.logoUrl))
            put("primaryColor", settings.primaryColor)
            put("textColor", settings.textColor)
            put("consentText", settings.consentText)
            put("termsUrl", text(settings.termsUrl))
            put("privacyUrl", text(settings.privacyUrl))
            put("footerText", settings.footerText)
            put("successUrl", text(settings.successUrl))
            put("accessMinutes", settings.accessMinutes)
            put("reconnectMinutes", settings.reconnectMinutes)
            put("codeTtlMinutes", settings.codeTtlMinutes)
            put("dataLimitMb", settings.dataLimitMb)
            put("rxKbps", settings.rxKbps)
            put("txKbps", settings.txKbps)
            put("smtpMode", settings.smtpMode)
            put("serverMailAvailable", call.access.serverMail != null)
            put("smtpHost", text(settings.smtpHost))
            put("smtpPort", settings.smtpPort)
            put("smtpSecurity", settings.smtpSecurity)
            put("smtpUsername", text(settings.smtpUsername))
            put("smtpPasswordConfigured", !settings.smtpPassword.isNullOrBlank())
            put("smtpFrom", text(settings.smtpFrom))
            put("emailSubject", settings.emailSubject)
            put("networkController", text(controller?.name))
            put("networkControllerConfigured", controller?.isConfigured(settings) ?: false)
            put(
                "integrations",
                JsonArray(
                    HotspotIntegrations.catalog(call.tenant).map { integration ->
                        buildJsonObject {
                            put("id", integration.id)
                            put("title", integration.title)
                            put("kind", if (integration.kind == HotspotIntegrations.Network) "controlador de red" else "modulo")
                            put("enabled", integration.id in enabled)
                        }
                    },
                ),
            )
        }
    }

    private fun updateSettings(call: McpToolCall): JsonElement {
        // merge reads every value as text, exactly like the form posts it.
        val values = JsonObject(
            call.arguments.filterKeys { it in settingArguments }
                .mapValues { (key, value) -> JsonPrimitive((value as? JsonPrimitive)?.contentOrNull ?: throw McpToolException("'$key' debe ser un valor simple.")) },
        )
        if (values.isEmpty()) throw McpToolException("Envia al menos un campo a cambiar.")
        val current = HotspotSettingsRepository.load(call.tenant)
        val updated = try {
            HotspotSettingsRepository.merge(current, values)
        } catch (failure: HotspotValidationException) {
            throw McpToolException(failure.message.orEmpty())
        }
        if (!HotspotServerModule.registerDomain(call.access, call.permissions.companyCode, updated.domain)) {
            throw McpToolException("Ese dominio ya esta registrado en este servidor.")
        }
        HotspotSettingsRepository.save(call.tenant, updated)
        return buildJsonObject {
            put("saved", true)
            put("changed", JsonArray(values.keys.sorted().map(::JsonPrimitive)))
            if (updated.domain != current.domain) {
                put("note", "El dominio queda pendiente mientras el servidor configura Nginx y HTTPS; revisa domainStatus con get_settings en unos minutos.")
            }
        }
    }

    private fun networks(call: McpToolCall): JsonElement {
        val choosable = HotspotPasses.choosable(call.tenant)
        val sourceNames = (listOf(HotspotPasses.hotspotCustomers) + HotspotPasses.sources(call.tenant)).associate { it.id to it.name }
        val general = HotspotSettingsRepository.load(call.tenant)
        return buildJsonObject {
            put("generalAccessMinutes", general.accessMinutes)
            put("generalReconnectMinutes", general.reconnectMinutes)
            put("usersIntegrationEnabled", HotspotIntegrations.isEnabled(call.tenant, HotspotIntegrations.Users))
            put(
                "accessSources",
                JsonArray(
                    choosable.map { source ->
                        buildJsonObject {
                            put("id", source.id)
                            put("name", source.name)
                            put("description", source.description)
                        }
                    },
                ),
            )
            put(
                "networks",
                JsonArray(
                    HotspotNetworks.list(call.tenant).map { network ->
                        buildJsonObject {
                            put("id", network.id.toString())
                            put("ssid", network.ssid)
                            put("accessMode", network.accessMode)
                            put("accessModeLabel", HotspotNetworks.modeLabel(network.accessMode))
                            if (network.byCustomerCode) {
                                put(
                                    "whoGetsIn",
                                    JsonArray(
                                        network.accessSources.map { id ->
                                            buildJsonObject {
                                                put("id", id)
                                                put("name", sourceNames[id] ?: id)
                                            }
                                        },
                                    ),
                                )
                            }
                            put("accessMinutes", network.accessMinutes?.let(::JsonPrimitive) ?: JsonNull)
                            put("reconnectMinutes", network.reconnectMinutes?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    },
                ),
            )
        }
    }

    private fun saveNetwork(call: McpToolCall): JsonElement {
        val id = call.uuid("id")
        val existing = id?.let { HotspotNetworks.find(call.tenant, it) ?: throw McpToolException("Esa red no existe; revisa list_networks.") }
        // Same form values as the settings screen: the existing network with what the agent sent on top.
        val values = HotspotNetworks.toFormValues(existing).toMutableMap()
        if (call.has("ssid")) values["ssid"] = call.string("ssid", 100)
        if (call.has("accessMode")) values["accessMode"] = call.string("accessMode", 40)
        if (call.has("whoGetsIn")) {
            val chosen = (call.arguments["whoGetsIn"] as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.contentOrNull ?: throw McpToolException("'whoGetsIn' debe ser una lista de ids.") }
                ?: throw McpToolException("'whoGetsIn' debe ser una lista de ids.")
            values.keys.removeAll { it.startsWith(HotspotNetworks.sourceKey("")) }
            chosen.forEach { values[HotspotNetworks.sourceKey(it)] = "true" }
        }
        // 0 / -1 mean "use the general one", which the form expresses with a blank field.
        if (call.has("accessMinutes")) values["accessMinutes"] = call.int("accessMinutes", 0, 0..10080).takeIf { it > 0 }?.toString().orEmpty()
        if (call.has("reconnectMinutes")) values["reconnectMinutes"] = call.int("reconnectMinutes", -1, -1..525600).takeIf { it >= 0 }?.toString().orEmpty()
        val network = try {
            val usersEnabled = HotspotIntegrations.isEnabled(call.tenant, HotspotIntegrations.Users)
            val sources = HotspotPasses.choosable(call.tenant).map { it.id }.toSet()
            HotspotNetworks.parse(id ?: UUID.randomUUID(), values, usersEnabled, sources)
        } catch (failure: HotspotValidationException) {
            throw McpToolException(failure.message.orEmpty())
        }
        HotspotNetworks.findBySsid(call.tenant, network.ssid)?.takeIf { it.id != network.id }?.let {
            throw McpToolException("Ya hay una red configurada con el SSID ${network.ssid} (id ${it.id}); editala en vez de crear otra.")
        }
        try {
            HotspotNetworks.save(call.tenant, network)
        } catch (failure: HotspotValidationException) {
            throw McpToolException(failure.message.orEmpty())
        }
        return buildJsonObject {
            put("saved", true)
            put("id", network.id.toString())
            put("ssid", network.ssid)
            put("accessMode", network.accessMode)
        }
    }

    private fun deleteNetwork(call: McpToolCall): JsonElement {
        val id = call.requiredUuid("id")
        if (!HotspotNetworks.delete(call.tenant, id)) throw McpToolException("Esa red no existe; revisa list_networks.")
        return buildJsonObject {
            put("deleted", true)
            put("note", "Los visitantes de esa red vuelven a usar el formulario de registro.")
        }
    }

    private fun setIntegration(call: McpToolCall): JsonElement {
        if (!call.permissions.businessOwner) {
            throw McpToolException("Solo el dueno de la empresa o del servidor cambia las integraciones.")
        }
        if (!call.has("enabled")) throw McpToolException("El argumento 'enabled' es obligatorio.")
        val id = call.requiredString("id", 100)
        val enabled = call.boolean("enabled")
        try {
            HotspotIntegrations.setEnabled(call.tenant, id, enabled)
        } catch (failure: HotspotValidationException) {
            throw McpToolException(failure.message.orEmpty())
        }
        return buildJsonObject {
            put("id", id)
            put("enabled", enabled)
            put("enabledIntegrations", JsonArray(HotspotIntegrations.enabled(call.tenant).sorted().map(::JsonPrimitive)))
        }
    }

    private fun dateRange(call: McpToolCall): Pair<LocalDate, LocalDate> {
        val to = call.date("to") ?: LocalDate.now()
        val from = call.date("from") ?: to.minusDays(6)
        if (from.isAfter(to)) throw McpToolException("'from' debe ser anterior o igual a 'to'.")
        if (ChronoUnit.DAYS.between(from, to) >= MaxRangeDays) throw McpToolException("El rango no puede pasar de $MaxRangeDays dias.")
        return from to to
    }

    private fun export(call: McpToolCall): JsonElement {
        val (from, to) = dateRange(call)
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("csv", HotspotReports.csv(call.tenant, from, to))
        }
    }

    private fun userCodes(call: McpToolCall): JsonElement {
        if (!call.permissions.businessOwner) throw McpToolException("Solo el dueno de la empresa o del servidor ve los codigos de todos los usuarios.")
        val access = HotspotAccess(call.access)
        val users = if (access.isServerCompanyOwner(call.permissions)) {
            call.access.centralConnection().use { central -> HotspotUserCodes.users(call.tenant, central) }
        } else {
            HotspotUserCodes.users(call.tenant, null)
        }
        return buildJsonObject {
            put("usersIntegrationEnabled", HotspotIntegrations.isEnabled(call.tenant, HotspotIntegrations.Users))
            put("users", JsonArray(users.map { user ->
                buildJsonObject {
                    put("user", user.username)
                    put("name", user.name)
                    put("code", user.code)
                    put("serverOwner", user.serverOwner)
                    put("active", user.active)
                }
            }))
        }
    }

    // Grants exist only in the server company, and only server owners manage them.
    private fun serverCompanyAccess(call: McpToolCall): HotspotAccess {
        val access = HotspotAccess(call.access)
        if (!access.isServerCompanyOwner(call.permissions)) throw McpToolException("Solo los duenos del servidor, en la empresa del servidor, manejan estos accesos.")
        return access
    }

    private fun grants(call: McpToolCall): JsonElement {
        val access = serverCompanyAccess(call)
        val users = call.access.centralConnection().use { central -> HotspotUserCodes.users(call.tenant, central) }
        val names = users.associate { it.id to access.userLabel(it) }
        return buildJsonObject {
            put("grants", JsonArray(HotspotUserCodes.grants(call.tenant).map { grant ->
                buildJsonObject {
                    put("id", grant.id.toString())
                    put("company", grant.companyCode)
                    put("ssid", grant.ssid)
                    put("user", names[grant.userId] ?: "Usuario eliminado")
                }
            }))
            put("networks", JsonArray(access.otherCompanyNetworks().map(::JsonPrimitive)))
            put("users", JsonArray(users.filter { it.active }.map { JsonPrimitive(access.userLabel(it)) }))
        }
    }

    // grantAction answers a message and a tone; anything but success is an error the agent can fix.
    private fun grantResult(result: Pair<String, String>): JsonElement {
        val (message, tone) = result
        if (tone != UiTone.Success) throw McpToolException(message)
        return buildJsonObject { put("result", message) }
    }

    private fun addGrant(call: McpToolCall): JsonElement {
        val access = serverCompanyAccess(call)
        val wanted = call.requiredString("user", 200)
        val user = call.access.centralConnection().use { central -> HotspotUserCodes.users(call.tenant, central) }
            .firstOrNull { user ->
                user.active && listOf(user.username, user.name, access.userLabel(user)).any { it.equals(wanted, ignoreCase = true) }
            }
            ?: throw McpToolException("'$wanted' no es un usuario activo de la empresa del servidor.")
        val network = "${call.requiredString("company", 63)} · ${call.requiredString("ssid", 64)}"
        return grantResult(access.grantAction(call.tenant, "add", null, mapOf("network" to network, "user" to access.userLabel(user))))
    }

    private fun removeGrant(call: McpToolCall): JsonElement {
        val access = serverCompanyAccess(call)
        return grantResult(access.grantAction(call.tenant, "delete", call.requiredUuid("id").toString(), emptyMap()))
    }

    // The UniFi console and site are picked with the API key saved in the app; the key never reaches the agent.
    private fun unifiSettings(call: McpToolCall): Pair<HotspotSettings, String> {
        if (HotspotIntegrations.controller(call.tenant) != UnifiController) throw McpToolException("Activa la integracion UniFi en Integraciones.")
        val settings = HotspotSettingsRepository.load(call.tenant)
        val apiKey = settings.unifiApiKey?.takeIf { it.isNotBlank() } ?: throw McpToolException("Escribe la API key de UniFi en la app (Configuracion) y vuelve a intentar.")
        return settings to apiKey
    }

    private fun store(call: McpToolCall, settings: HotspotSettings, vararg changes: Pair<String, String>): HotspotSettings {
        val updated = try {
            HotspotSettingsRepository.merge(settings, JsonObject(changes.associate { it.first to JsonPrimitive(it.second) }))
        } catch (failure: HotspotValidationException) {
            throw McpToolException(failure.message.orEmpty())
        }
        HotspotSettingsRepository.save(call.tenant, updated)
        return updated
    }

    private inline fun <T> unifi(block: () -> T): T = try {
        block()
    } catch (failure: UnifiException) {
        throw McpToolException(failure.message.orEmpty())
    }

    private fun consoles(call: McpToolCall): JsonElement {
        val (settings, apiKey) = unifiSettings(call)
        val consoles = unifi { UnifiClient.consoles(apiKey) }
        return buildJsonObject {
            put("selected", settings.unifiConsoleId?.let(::JsonPrimitive) ?: JsonNull)
            put("consoles", JsonArray(consoles.map { console ->
                buildJsonObject {
                    put("id", console.id)
                    put("name", console.name)
                    put("model", console.model)
                    put("state", console.state)
                }
            }))
        }
    }

    private fun chooseConsole(call: McpToolCall): JsonElement {
        val (settings, apiKey) = unifiSettings(call)
        val consoleId = call.requiredString("consoleId", 200)
        // Same as the screen: another console clears the site.
        var saved = if (consoleId != settings.unifiConsoleId) store(call, settings, "unifiConsoleId" to consoleId, "unifiSiteId" to "") else settings
        val sites = unifi { UnifiClient.sites(apiKey, consoleId) }
        if (sites.size == 1) {
            saved = store(call, saved, "unifiSiteId" to sites.single().id)
            return buildJsonObject { put("result", "Consola guardada y sitio \"${sites.single().name}\" seleccionado. UniFi quedo configurado.") }
        }
        return buildJsonObject {
            put("result", "Consola guardada. Ahora elige el sitio con unifi_choose_site.")
            put("sites", JsonArray(sites.map { site ->
                buildJsonObject {
                    put("id", site.id)
                    put("name", site.name)
                    put("reference", site.reference)
                }
            }))
        }
    }

    private fun chooseSite(call: McpToolCall): JsonElement {
        val (settings, _) = unifiSettings(call)
        if (settings.unifiConsoleId.isNullOrBlank()) throw McpToolException("Elige primero la consola con unifi_choose_console.")
        store(call, settings, "unifiSiteId" to call.requiredString("siteId", 200))
        return buildJsonObject { put("result", "Sitio guardado. UniFi quedo configurado.") }
    }

    private fun controllerSsids(call: McpToolCall): JsonElement {
        val controller = HotspotIntegrations.controller(call.tenant) ?: throw McpToolException("Elige el controlador de red en Integraciones.")
        val ssids = try {
            controller.wifiNetworks(HotspotSettingsRepository.load(call.tenant))
        } catch (failure: NetworkControllerException) {
            throw McpToolException(failure.message.orEmpty())
        }
        val configured = HotspotNetworks.list(call.tenant).map { it.ssid }.toSet()
        return buildJsonObject {
            put("controller", controller.name)
            put("ssids", JsonArray(ssids.map { ssid ->
                buildJsonObject {
                    put("ssid", ssid)
                    put("configured", ssid in configured)
                }
            }))
        }
    }

    private fun myCode(call: McpToolCall): JsonElement {
        val userId = call.permissions.userId ?: throw McpToolException("No se pudo identificar al usuario.")
        val usersEnabled = HotspotIntegrations.isEnabled(call.tenant, HotspotIntegrations.Users)
        return buildJsonObject {
            val code = if (call.permissions.serverOwner) {
                call.access.centralConnection().use { central ->
                    HotspotUserCodes.codeFor(call.tenant, userId, true, central)
                }
            } else {
                HotspotUserCodes.codeFor(call.tenant, userId, false)
            }
            put("code", code)
            put(
                "note",
                if (usersEnabled) {
                    "Sirve en las redes WiFi con acceso por codigo de usuario."
                } else {
                    "La integracion Usuarios del hotspot esta desactivada: por ahora ninguna red acepta codigos de usuario."
                },
            )
        }
    }
}
