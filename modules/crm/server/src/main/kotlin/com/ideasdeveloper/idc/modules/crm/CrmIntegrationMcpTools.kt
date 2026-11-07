package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * Agent tools of the CRM integrations (Meta, WordPress, Encuentra24, Cloudbeds) and of the lead
 * items they bring (website properties, Cloudbeds rooms and reservations).
 *
 * Credentials never pass through an agent: API keys, tokens, secrets and the WordPress shared
 * secret are typed (or generated) only in the app. The tools neither receive nor return them; to
 * save the other settings they send the stored credentials back to the same save functions as the
 * screens, server side, so nothing changes in them.
 */
object CrmIntegrationMcpTools {
    private const val Confirm = " Confirma con el usuario antes."
    private const val NoCredentials = " Las credenciales (API keys, tokens, secretos) se configuran solo en la app."

    private val leadId = "lead" to McpSchema.uuid("Id del lead.")
    private val itemId = "item" to McpSchema.uuid("Id del elemento del lead (get_lead_items).")

    val tools = listOf(
        // ---- Integration settings (crm.settings) ----
        McpTool(
            name = "get_integrations",
            title = "Integraciones del CRM",
            description = "Estado de las integraciones (Meta: WhatsApp, Messenger e Instagram; WordPress; Encuentra24; Cloudbeds) sin credenciales: " +
                "solo si estan configuradas, mas los ultimos errores y los agentes del sitio WordPress.",
            permission = CrmScreens.Settings,
            readOnly = true,
        ) { call -> integrations(call) },
        McpTool(
            name = "update_meta",
            title = "Cambiar integracion Meta",
            description = "Activa o desactiva Meta y cambia la pagina, si Messenger e Instagram comparten el token de Meta Business, la cuenta de WhatsApp Business, el numero y si se guardan los mensajes enviados desde la app de WhatsApp. " +
                "Solo cambia lo enviado. Al guardar con Meta activo conecta la pagina e Instagram y suscribe la cuenta de WhatsApp.$NoCredentials$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    "enabled" to McpSchema.boolean("Activar Meta."),
                    "sharedPageAccess" to McpSchema.boolean("Messenger e Instagram usan el token de la pagina obtenido del token de Meta Business; false: cada uno usa su propio token, que se escribe en la app."),
                    "pageId" to McpSchema.string("ID numerico de la pagina de Facebook; vacio la quita."),
                    "wabaId" to McpSchema.string("WhatsApp Business Account ID numerico; vacio lo quita."),
                    "phoneNumberId" to McpSchema.string("Phone Number ID numerico de WhatsApp; vacio lo quita."),
                    "recordEchoes" to McpSchema.boolean("Guardar en el CRM los mensajes enviados desde la app de WhatsApp Business."),
                ),
            ),
        ) { call -> updateMeta(call) },
        McpTool(
            name = "meta_connect_page",
            title = "Conectar pagina de Meta",
            description = "Conecta la pagina de Facebook configurada y su cuenta de Instagram con el token guardado y suscribe Instagram a los mensajes " +
                "(con tokens propios, lee la cuenta de Instagram de la pagina del token de Instagram), para recibir y enviar mensajes.",
            permission = CrmScreens.Settings,
        ) { call -> metaConnectPage(call) },
        McpTool(
            name = "meta_fill_names",
            title = "Completar nombres desde Meta",
            description = "Pide a Meta el nombre de los contactos de Messenger e Instagram que llegaron sin nombre (hasta 100 por canal) y lo guarda. " +
                "Nunca cambia un nombre existente.",
            permission = CrmScreens.Settings,
        ) { call -> metaFillNames(call) },
        McpTool(
            name = "meta_connect_whatsapp",
            title = "Conectar WhatsApp",
            description = "Suscribe la app a la cuenta de WhatsApp Business y lista sus numeros. Con un solo numero y ninguno elegido, lo elige.",
            permission = CrmScreens.Settings,
        ) { call -> metaConnectWhatsApp(call) },
        McpTool(
            name = "update_wordpress",
            title = "Cambiar integracion WordPress",
            description = "Activa o desactiva el sitio WordPress (plugin IDC Connector) y cambia su direccion. Solo cambia lo enviado. El secreto del plugin " +
                "se genera en la app.$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    "enabled" to McpSchema.boolean("Activar WordPress."),
                    "siteUrl" to McpSchema.string("Direccion del sitio con https://; vacio la quita."),
                ),
            ),
        ) { call -> updateWordPress(call) },
        McpTool(
            name = "wordpress_test",
            title = "Probar conexion con WordPress",
            description = "Comprueba la conexion con el plugin del sitio y devuelve su nombre y versiones.",
            permission = CrmScreens.Settings,
        ) { call -> wordPressTest(call) },
        McpTool(
            name = "wordpress_sync_agents",
            title = "Sincronizar agentes del sitio",
            description = "Lee los agentes del sitio WordPress; los nuevos se vinculan al usuario del CRM con el mismo correo de avisos.",
            permission = CrmScreens.Settings,
        ) { call -> wordPressSyncAgents(call) },
        McpTool(
            name = "wordpress_map_agent",
            title = "Vincular agente del sitio",
            description = "Vincula un agente del sitio (wpUserId de get_integrations) a un usuario del CRM, que sera el responsable sugerido de sus leads.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    "wpUserId" to McpSchema.integer("Id del agente en el sitio.", 1),
                    "user" to McpSchema.string("Nombre o id del usuario del CRM; vacio quita el vinculo."),
                ),
                required = listOf("wpUserId", "user"),
            ),
        ) { call -> wordPressMapAgent(call) },
        McpTool(
            name = "update_encuentra24",
            title = "Cambiar integracion Encuentra24",
            description = "Activa o desactiva la importacion de contactos de Encuentra24 y cambia el slug de la empresa. Solo cambia lo enviado.$NoCredentials$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    "enabled" to McpSchema.boolean("Activar Encuentra24."),
                    "slug" to McpSchema.string("Slug de la empresa (como en su URL de Encuentra24)."),
                ),
            ),
        ) { call -> updateEncuentra24(call) },
        McpTool(
            name = "encuentra24_sync",
            title = "Importar contactos de Encuentra24",
            description = "Importa ahora los contactos de los anuncios de Encuentra24 como leads, desde una fecha o desde la ultima importacion.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mapOf("fromDate" to McpSchema.date("Importar desde este dia (opcional)."))),
        ) { call -> encuentra24Sync(call) },
        McpTool(
            name = "update_cloudbeds",
            title = "Cambiar integracion Cloudbeds",
            description = "Activa o desactiva Cloudbeds y cambia la propiedad, el metodo de pago de las reservas, el correo de bienvenida y si se archivan " +
                "los leads al salir del hotel. Solo cambia lo enviado.$NoCredentials$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    "enabled" to McpSchema.boolean("Activar Cloudbeds."),
                    "propertyId" to McpSchema.string("ID numerico de la propiedad; vacio usa la de la API key."),
                    "paymentMethod" to McpSchema.string("Metodo de pago de las reservas.", CrmCloudbeds.paymentLabels.keys.toList()),
                    "welcomeEnabled" to McpSchema.boolean("Enviar correo de bienvenida antes del check-in."),
                    "welcomeTemplate" to McpSchema.string("Nombre de la plantilla de mensaje del correo de bienvenida."),
                    "welcomeDaysBefore" to McpSchema.integer("Dias antes del check-in.", 0, 60),
                    "welcomeHour" to McpSchema.integer("Hora de envio (0 a 23).", 0, 23),
                    "archiveOnCheckout" to McpSchema.boolean("Archivar el lead cuando el huesped sale del hotel."),
                ),
            ),
        ) { call -> updateCloudbeds(call) },
        McpTool(
            name = "cloudbeds_test",
            title = "Probar conexion con Cloudbeds",
            description = "Comprueba la conexion con Cloudbeds con la API key guardada.",
            permission = CrmScreens.Settings,
        ) { call -> cloudbedsTest(call) },

        // ---- Lead items (crm.leads.manage, like the property and room screens) ----
        McpTool(
            name = "get_lead_items",
            title = "Propiedades, habitaciones y reservas de un lead",
            description = "Lista los elementos de interes de un lead visible (propiedades del sitio, habitaciones de Cloudbeds, otros) y sus reservas de Cloudbeds.",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf(leadId), required = listOf("lead")),
        ) { call -> leadItems(call) },
        McpTool(
            name = "search_properties",
            title = "Buscar propiedades del sitio",
            description = "Busca propiedades en el sitio WordPress por texto y rango de precio, 20 por pagina.",
            permission = CrmScreens.Manage,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "query" to McpSchema.string("Texto a buscar: zona, tipo, MLS..."),
                    "minPrice" to McpSchema.number("Precio minimo."),
                    "maxPrice" to McpSchema.number("Precio maximo."),
                    "page" to McpSchema.integer("Pagina.", 1, 100),
                ),
            ),
        ) { call -> searchProperties(call) },
        McpTool(
            name = "link_property",
            title = "Vincular propiedad a un lead",
            description = "Agrega una propiedad del sitio (id de search_properties) a los intereses de un lead visible.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(mapOf(leadId, "listing" to McpSchema.integer("Id de la propiedad en el sitio.", 1)), required = listOf("lead", "listing")),
        ) { call -> linkProperty(call) },
        McpTool(
            name = "add_lead_item",
            title = "Agregar interes manual",
            description = "Agrega a un lead visible un elemento de interes escrito a mano (algo que no viene de una integracion).",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf(leadId, "title" to McpSchema.string("Nombre, hasta 300 caracteres."), "subtitle" to McpSchema.string("Detalle opcional.")),
                required = listOf("lead", "title"),
            ),
        ) { call -> addItem(call) },
        McpTool(
            name = "set_lead_item_status",
            title = "Cambiar estado de un interes",
            description = "Marca un elemento de un lead como interesado, ofrecido, elegido o descartado.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf(leadId, itemId, "status" to McpSchema.string("Estado.", listOf("interested", "offered", "chosen", "discarded"))),
                required = listOf("lead", "item", "status"),
            ),
        ) { call -> setItemStatus(call) },
        McpTool(
            name = "remove_lead_item",
            title = "Quitar interes de un lead",
            description = "Quita un elemento de los intereses de un lead visible.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(mapOf(leadId, itemId), required = listOf("lead", "item")),
        ) { call -> removeItem(call) },
        McpTool(
            name = "close_property_sale",
            title = "Cerrar venta de propiedad",
            description = "Cierra la venta de una propiedad del sitio vinculada a un lead: la marca vendida en el sitio web y en el lead, y gana el lead.$Confirm",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf(leadId, itemId, "price" to McpSchema.number("Precio de venta (opcional)."), "date" to McpSchema.date("Fecha de venta; por defecto hoy.")),
                required = listOf("lead", "item"),
            ),
        ) { call -> closeSale(call) },
        McpTool(
            name = "search_rooms",
            title = "Buscar habitaciones libres",
            description = "Busca en Cloudbeds las habitaciones libres para unas fechas y huespedes, con el mejor total de su tipo. Sin fechas usa las del lead.",
            permission = CrmScreens.Manage,
            readOnly = true,
            inputSchema = McpSchema.obj(stayArguments + leadId, required = listOf("lead")),
        ) { call -> searchRooms(call) },
        McpTool(
            name = "assign_room",
            title = "Asignar habitacion a un lead",
            description = "Asigna a un lead visible una habitacion libre (roomId de search_rooms) para la estadia. Las habitaciones de una reserva comparten fechas.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(stayArguments + leadId + ("roomId" to McpSchema.string("Id de la habitacion (search_rooms).")), required = listOf("lead", "roomId")),
        ) { call -> assignRoom(call) },
        McpTool(
            name = "create_reservation",
            title = "Crear reserva en Cloudbeds",
            description = "Crea en Cloudbeds la reserva de las habitaciones elegidas del lead, con los datos de contacto del lead, y gana el lead. " +
                "Es una reserva real en el hotel: muestra el resumen al usuario y espera su confirmacion.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf(
                    leadId,
                    "rooms" to buildJsonObject {
                        put("type", "array")
                        put("description", "Huespedes de cada habitacion elegida.")
                        put(
                            "items",
                            McpSchema.obj(
                                mapOf(
                                    "item" to McpSchema.uuid("Id de la habitacion elegida (get_lead_items)."),
                                    "adults" to McpSchema.integer("Adultos.", 1, 50),
                                    "children" to McpSchema.integer("Ninos.", 0, 50),
                                ),
                                required = listOf("item", "adults"),
                            ),
                        )
                    },
                    "country" to McpSchema.string("Pais del huesped, codigo de 2 letras (opcional)."),
                    "zip" to McpSchema.string("Codigo postal (opcional)."),
                    "payment" to McpSchema.string("Metodo de pago; por defecto el de la integracion.", CrmCloudbeds.paymentLabels.keys.toList()),
                ),
                required = listOf("lead", "rooms"),
            ),
        ) { call -> reserve(call) },
    )

    private val stayArguments: Map<String, JsonObject>
        get() = mapOf(
            "start" to McpSchema.date("Entrada."),
            "end" to McpSchema.date("Salida."),
            "adults" to McpSchema.integer("Adultos.", 1, 50),
            "children" to McpSchema.integer("Ninos.", 0, 50),
        )

    private fun context(call: McpToolCall) = CrmScreens.Context.load(call.tenant, call.permissions, call.access.serverMail)

    private fun actor(call: McpToolCall): CrmActor {
        val actor = CrmRepository.actor(call.tenant, call.permissions, call.access.userIdentity(call.tenant, call.permissions))
        return actor.copy(name = "${actor.name} (agente ${call.agentName})")
    }

    private fun visibleLead(call: McpToolCall, context: CrmScreens.Context): CrmLead =
        CrmRepository.lead(call.tenant, call.requiredUuid("lead"))?.takeIf { context.scope.canSee(it.interestId, it.ownerUserId) }
            ?: throw McpToolException("Lead no encontrado o no visible para el usuario.")

    private fun item(call: McpToolCall, lead: CrmLead): CrmItem =
        CrmItems.find(call.tenant, lead.id, call.requiredUuid("item")) ?: throw McpToolException("Ese elemento no existe en el lead; revisa get_lead_items.")

    private fun result(message: String) = buildJsonObject { put("result", message) }

    // Every error of the integrations and the shared actions becomes a message the agent can act on.
    private inline fun <T> checked(block: () -> T): T = try {
        block()
    } catch (failure: CrmValidationException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: CrmActionNotice) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: CrmMetaException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: CrmWordPressException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: CrmCloudbedsException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: CrmEncuentra24Exception) {
        throw McpToolException(failure.message.orEmpty())
    }

    private fun text(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

    private fun configured(value: String?) = !value.isNullOrBlank()

    private fun user(context: CrmScreens.Context, wanted: String): CrmUser =
        context.users.firstOrNull { it.id.toString() == wanted || it.name.equals(wanted, ignoreCase = true) }
            ?: throw McpToolException("'$wanted' no es un usuario con acceso al CRM. Usuarios: ${context.users.joinToString { it.name }}.")

    // ---- Settings ----

    private fun integrations(call: McpToolCall): JsonElement {
        val context = context(call)
        val tenant = call.tenant
        val meta = CrmMeta.load(tenant)
        val wordPress = CrmWordPress.load(tenant)
        val e24 = CrmEncuentra24.load(tenant)
        val cloudbeds = CrmCloudbeds.load(tenant)
        val templates = CrmMail.templates(tenant)
        return buildJsonObject {
            put("meta", buildJsonObject {
                put("enabled", meta.enabled)
                put("appSecretConfigured", configured(meta.appSecret))
                put("verifyTokenConfigured", configured(meta.verifyToken))
                put("systemTokenConfigured", configured(meta.systemToken))
                put("pageId", text(meta.pageId))
                put("pageName", text(meta.pageName))
                put("sharedPageAccess", meta.sharedTokens)
                if (!meta.sharedTokens) {
                    put("messengerTokenConfigured", configured(meta.messengerToken))
                    put("instagramTokenConfigured", configured(meta.instagramToken))
                }
                put("pageConnected", meta.sharedTokens && meta.pageToken != null)
                put("messengerReady", meta.ready("messenger"))
                put("instagramAccountId", text(meta.instagramAccountId))
                put("instagramUsername", text(meta.instagramUsername))
                put("instagramReady", meta.ready("instagram") && meta.instagramAccountId != null)
                put("wabaId", text(meta.wabaId))
                put("phoneNumberId", text(meta.phoneNumberId))
                put("whatsAppReady", meta.whatsAppReady)
                put("recordEchoes", meta.recordEchoes)
            })
            put("wordpress", buildJsonObject {
                put("enabled", wordPress.enabled)
                put("siteUrl", text(wordPress.siteUrl))
                put("secretConfigured", configured(wordPress.secret))
                put("siteName", wordPress.siteName)
                put("ready", wordPress.ready)
                put("lastEventAt", text(wordPress.lastEventAt?.toString()))
                if (wordPress.lastError.isNotEmpty()) put("lastError", wordPress.lastError)
                putJsonArray("agents") {
                    CrmWordPress.agents(tenant).forEach { agent ->
                        add(buildJsonObject {
                            put("wpUserId", agent.wpUserId)
                            put("name", agent.name)
                            put("email", agent.email)
                            put("user", text(agent.userId?.let { context.names.user(it) }))
                        })
                    }
                }
            })
            put("encuentra24", buildJsonObject {
                put("enabled", e24.enabled)
                put("apiKeyConfigured", configured(e24.apiKey))
                put("slug", text(e24.slug))
                put("ready", e24.ready)
                put("lastSync", text(e24.lastSync?.toString()))
                if (e24.lastResult.isNotEmpty()) put("lastResult", e24.lastResult)
                if (e24.lastError.isNotEmpty()) put("lastError", e24.lastError)
            })
            put("cloudbeds", buildJsonObject {
                put("enabled", cloudbeds.enabled)
                put("apiKeyConfigured", configured(cloudbeds.apiKey))
                put("propertyId", text(cloudbeds.propertyId))
                put("paymentMethod", cloudbeds.paymentMethod)
                put("welcomeEnabled", cloudbeds.welcomeEnabled)
                put("welcomeTemplate", text(templates.firstOrNull { it.id == cloudbeds.welcomeTemplateId }?.name))
                put("welcomeDaysBefore", cloudbeds.welcomeDaysBefore)
                put("welcomeHour", cloudbeds.welcomeHour)
                put("archiveOnCheckout", cloudbeds.archiveOnCheckout)
                put("ready", cloudbeds.ready)
                if (cloudbeds.lastError.isNotEmpty()) put("lastError", cloudbeds.lastError)
            })
        }
    }

    private fun updateMeta(call: McpToolCall): JsonElement {
        val keys = listOf("enabled", "pageId", "wabaId", "phoneNumberId", "recordEchoes", "sharedPageAccess")
        if (keys.none(call::has)) throw McpToolException("Envia al menos un campo a cambiar.")
        val tenant = call.tenant
        val current = CrmMeta.load(tenant)
        if (current.serverMode) throw McpToolException("La empresa usa la app de Meta del servidor: se conecta desde Configuracion > Integraciones con Conectar con Meta.")
        // CrmMeta.merge reads the whole form: blank secrets keep the stored ones, the verify token is sent back as stored.
        val values = mutableMapOf(
            "enabled" to current.enabled.toString(),
            "verifyToken" to current.verifyToken.orEmpty(),
            "pageId" to current.pageId.orEmpty(),
            "wabaId" to current.wabaId.orEmpty(),
            "phoneNumberId" to current.phoneNumberId.orEmpty(),
            "recordEchoes" to current.recordEchoes.toString(),
        )
        if (call.has("enabled")) values["enabled"] = call.boolean("enabled").toString()
        if (call.has("recordEchoes")) values["recordEchoes"] = call.boolean("recordEchoes").toString()
        if (call.has("sharedPageAccess")) values["sharedTokens"] = call.boolean("sharedPageAccess").toString()
        listOf("pageId", "wabaId", "phoneNumberId").forEach { key -> if (call.has(key)) values[key] = call.string(key, 64) }
        val merged = checked { CrmMeta.merge(current, values) }
        CrmMeta.save(tenant, merged)
        // Same as the screen: saving connects the page, Instagram and the WhatsApp account.
        if (!merged.enabled || merged.systemToken == null) return result("Meta guardado.")
        return result("Meta guardado. ${CrmMeta.setup(tenant, merged).notice}".trim())
    }

    private fun metaConnectPage(call: McpToolCall): JsonElement {
        if (CrmMeta.load(call.tenant).serverMode) throw McpToolException("La empresa usa la app de Meta del servidor: se conecta desde Configuracion > Integraciones con Conectar con Meta.")
        val setup = CrmMeta.setup(call.tenant, CrmMeta.load(call.tenant), whatsApp = false)
        if (setup.done.isEmpty()) throw McpToolException(setup.notice.ifEmpty { "No hay nada que conectar: revisa el token y la cuenta de Instagram." })
        return result(setup.notice)
    }

    private fun metaFillNames(call: McpToolCall): JsonElement {
        val settings = CrmMeta.load(call.tenant)
        if (!settings.ready("messenger") && !settings.ready("instagram")) throw McpToolException("Conecta Messenger o Instagram antes de completar los nombres.")
        val names = CrmMeta.fillNames(call.tenant, settings)
        return buildJsonObject {
            put("result", "$names.")
            put("reviewed", names.reviewed)
            put("filled", names.filled)
            put("missing", names.missing)
        }
    }

    private fun metaConnectWhatsApp(call: McpToolCall): JsonElement {
        val settings = CrmMeta.load(call.tenant)
        if (settings.wabaId == null) throw McpToolException("Guarda primero el WhatsApp Business Account ID con update_meta (wabaId).")
        val setup = CrmMeta.setup(call.tenant, settings, page = false)
        if (setup.problems.isNotEmpty()) throw McpToolException(setup.notice)
        val numbers = setup.numbers
        val chosen = setup.settings.phoneNumberId
        return buildJsonObject {
            put("result", if (chosen != null) "WhatsApp conectado." else "App suscrita a la cuenta. Elige el numero con update_meta (phoneNumberId).")
            put("phoneNumberId", text(chosen))
            putJsonArray("numbers") {
                numbers.forEach { number ->
                    add(buildJsonObject {
                        put("id", number.id)
                        put("number", number.number)
                        put("name", number.name)
                        put("status", number.status)
                        put("platform", number.platform)
                    })
                }
            }
        }
    }

    private fun updateWordPress(call: McpToolCall): JsonElement {
        if (!call.has("enabled") && !call.has("siteUrl")) throw McpToolException("Envia al menos un campo a cambiar.")
        val current = CrmWordPress.load(call.tenant)
        val values = mapOf(
            "enabled" to (if (call.has("enabled")) call.boolean("enabled") else current.enabled).toString(),
            "siteUrl" to if (call.has("siteUrl")) call.string("siteUrl", 500) else current.siteUrl.orEmpty(),
        )
        checked { CrmWordPress.saveForm(call.tenant, values) }
        return result("WordPress guardado.")
    }

    private fun wordPressTest(call: McpToolCall): JsonElement = try {
        result("Conexion correcta: ${CrmWordPress.ping(call.tenant)}.")
    } catch (failure: CrmWordPressException) {
        CrmWordPress.recordError(call.tenant, failure.message.orEmpty())
        throw McpToolException(failure.message.orEmpty())
    }

    private fun wordPressSyncAgents(call: McpToolCall): JsonElement {
        val context = context(call)
        val emails = CrmNotify.userSettings(call.tenant).mapValues { it.value.email }
        val count = checked { CrmWordPress.syncAgents(call.tenant, context.users, emails) }
        return result("$count agente(s) del sitio sincronizados.")
    }

    private fun wordPressMapAgent(call: McpToolCall): JsonElement {
        val context = context(call)
        val wpUserId = call.int("wpUserId", 0, 0..Int.MAX_VALUE).toLong()
        val agent = CrmWordPress.agents(call.tenant).firstOrNull { it.wpUserId == wpUserId }
            ?: throw McpToolException("No existe ese agente del sitio; usa wordpress_sync_agents y get_integrations.")
        val wanted = call.string("user", 200)
        val target = wanted.takeIf { it.isNotEmpty() }?.let { user(context, it) }
        CrmWordPress.mapAgent(call.tenant, agent.wpUserId, target?.id)
        return result(target?.let { "${agent.name} vinculado a ${it.name}." } ?: "${agent.name} sin usuario vinculado.")
    }

    private fun updateEncuentra24(call: McpToolCall): JsonElement {
        if (!call.has("enabled") && !call.has("slug")) throw McpToolException("Envia al menos un campo a cambiar.")
        val current = CrmEncuentra24.load(call.tenant)
        // Without "apiKey" the stored key is kept.
        val values = mapOf(
            "enabled" to (if (call.has("enabled")) call.boolean("enabled") else current.enabled).toString(),
            "slug" to if (call.has("slug")) call.string("slug", 100) else current.slug.orEmpty(),
        )
        checked { CrmEncuentra24.saveForm(call.tenant, values) }
        return result("Encuentra24 guardado.")
    }

    private fun encuentra24Sync(call: McpToolCall): JsonElement {
        val from = call.date("fromDate")
        val outcome = checked { CrmEncuentra24.sync(call.tenant, from) { leadId -> CrmNotify.newLead(call.tenant, leadId) } }
        return result("Encuentra24: $outcome.")
    }

    private fun updateCloudbeds(call: McpToolCall): JsonElement {
        val keys = listOf("enabled", "propertyId", "paymentMethod", "welcomeEnabled", "welcomeTemplate", "welcomeDaysBefore", "welcomeHour", "archiveOnCheckout")
        if (keys.none(call::has)) throw McpToolException("Envia al menos un campo a cambiar.")
        val tenant = call.tenant
        val current = CrmCloudbeds.load(tenant)
        val templates = CrmMail.templates(tenant)
        fun flag(key: String, saved: Boolean) = (if (call.has(key)) call.boolean(key) else saved).toString()
        val payment = if (call.has("paymentMethod")) {
            val wanted = call.string("paymentMethod", 40)
            CrmCloudbeds.paymentLabels.entries.firstOrNull { it.key == wanted || it.value.equals(wanted, ignoreCase = true) }?.key
                ?: throw McpToolException("Metodo de pago desconocido. Opciones: ${CrmCloudbeds.paymentLabels.keys.joinToString()}.")
        } else {
            current.paymentMethod
        }
        val template = if (call.has("welcomeTemplate")) {
            val wanted = call.string("welcomeTemplate", 200)
            templates.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.name
                ?: throw McpToolException("No existe la plantilla '$wanted'. Plantillas: ${templates.joinToString { it.name }}.")
        } else {
            templates.firstOrNull { it.id == current.welcomeTemplateId }?.name
        }
        // CrmCloudbeds.saveForm reads the whole form; without "apiKey" the stored key is kept.
        val values = buildMap {
            put("enabled", flag("enabled", current.enabled))
            put("propertyId", if (call.has("propertyId")) call.string("propertyId", 30) else current.propertyId.orEmpty())
            put("paymentMethod", CrmCloudbeds.paymentLabels[payment].orEmpty())
            put("welcomeEnabled", flag("welcomeEnabled", current.welcomeEnabled))
            template?.let { put("welcomeTemplate", it) }
            put("welcomeDaysBefore", (if (call.has("welcomeDaysBefore")) call.int("welcomeDaysBefore", 1, 0..60) else current.welcomeDaysBefore).toString())
            put("welcomeHour", (if (call.has("welcomeHour")) call.int("welcomeHour", 9, 0..23) else current.welcomeHour).toString())
            put("archiveOnCheckout", flag("archiveOnCheckout", current.archiveOnCheckout))
        }
        checked { CrmCloudbeds.saveForm(tenant, values, templates) }
        return result("Cloudbeds guardado.")
    }

    private fun cloudbedsTest(call: McpToolCall): JsonElement =
        result("Conexion correcta con Cloudbeds: ${checked { CrmCloudbeds.test(call.tenant) }}.")

    // ---- Lead items ----

    private fun leadItems(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        return buildJsonObject {
            put("lead", lead.id.toString())
            putJsonArray("items") {
                CrmItems.list(call.tenant, lead.id).forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id.toString())
                        put("source", item.source)
                        put("title", item.title)
                        if (item.subtitle.isNotEmpty()) put("subtitle", item.subtitle)
                        if (item.url.isNotEmpty()) put("url", item.url)
                        put("price", text(item.price?.toPlainString()))
                        put("status", item.status)
                        put("statusLabel", CrmItems.statusLabels[item.status] ?: item.status)
                        item.startsOn?.let { put("startsOn", it.toString()) }
                        item.endsOn?.let { put("endsOn", it.toString()) }
                        listOf("adults", "children", "max", "reservation").forEach { key -> item.data[key]?.takeIf { it.isNotEmpty() }?.let { put(key, it) } }
                    })
                }
            }
            putJsonArray("reservations") {
                CrmCloudbeds.forLead(call.tenant, lead.id).forEach { reservation ->
                    add(buildJsonObject {
                        put("reservationId", reservation.reservationId)
                        put("status", CrmCloudbeds.statusLabels[reservation.status] ?: reservation.status)
                        put("start", reservation.startDate.toString())
                        put("end", reservation.endDate.toString())
                        put("rooms", reservation.rooms)
                        put("total", text(reservation.total?.toPlainString()))
                        put("welcomeSent", reservation.welcomeSent)
                    })
                }
            }
        }
    }

    private fun searchProperties(call: McpToolCall): JsonElement {
        val found = checked {
            CrmWordPress.search(
                call.tenant, call.string("query", 100), emptyMap(),
                call.decimal("minPrice")?.toPlainString().orEmpty(), call.decimal("maxPrice")?.toPlainString().orEmpty(), call.int("page", 1, 1..100),
            )
        }
        return buildJsonObject {
            put("total", found.total)
            putJsonArray("properties") {
                found.items.forEach { listing ->
                    add(buildJsonObject {
                        put("id", listing.id)
                        put("title", listing.title)
                        put("price", listing.priceText.ifEmpty { listing.price })
                        put("summary", listing.summary)
                        put("url", listing.url)
                        if (listing.agentName.isNotEmpty()) put("agent", listing.agentName)
                    })
                }
            }
        }
    }

    private fun linkProperty(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val listingId = call.int("listing", 0, 0..Int.MAX_VALUE).toLong().takeIf { it > 0 } ?: throw McpToolException("Propiedad invalida.")
        val listing = checked { CrmWordPress.listing(call.tenant, listingId) }
        CrmWordPress.linkListing(call.tenant, lead.id, listing, "Propiedad vinculada por ${actor(call).name}: ${listing.title}.")
        return result("${listing.title} vinculada a ${lead.name}.")
    }

    private fun addItem(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val title = call.string("title", 301)
        if (title.isEmpty() || title.length > 300) throw McpToolException("Escribe el nombre del interes (hasta 300 caracteres).")
        CrmItems.link(call.tenant, lead.id, "manual", UUID.randomUUID().toString(), title, call.string("subtitle", 500))
        CrmRepository.addActivity(call.tenant, lead.id, "item", "Interes agregado: $title.", actor(call))
        return result("$title agregado.")
    }

    private fun setItemStatus(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val item = item(call, lead)
        val status = call.requiredString("status", 20).takeIf { it in setOf("offered", "chosen", "discarded", "interested") }
            ?: throw McpToolException("Estado invalido: interested, offered, chosen o discarded.")
        CrmItems.setStatus(call.tenant, item, status)
        CrmRepository.addActivity(call.tenant, lead.id, "item", "${item.title}: ${CrmItems.statusLabels[status]}.", actor(call))
        return result("${item.title}: ${CrmItems.statusLabels[status]}.")
    }

    private fun removeItem(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val item = item(call, lead)
        CrmItems.remove(call.tenant, item)
        CrmRepository.addActivity(call.tenant, lead.id, "item", "Quitado: ${item.title}.", actor(call))
        return result("${item.title} quitado.")
    }

    private fun closeSale(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val item = item(call, lead)
        val values = mapOf("price" to call.decimal("price")?.toPlainString().orEmpty(), "date" to call.date("date")?.toString().orEmpty())
        return result(checked { CrmLeadItemActions.markSold(context, lead, item, values, actor(call)) })
    }

    // The stay as the room screens read it: dates and guests, defaulting to the lead's dates.
    private fun stayParams(call: McpToolCall): Map<String, String> = buildMap {
        call.date("start")?.let { put("start", it.toString()) }
        call.date("end")?.let { put("end", it.toString()) }
        if (call.has("adults")) put("adults", call.int("adults", 2, 1..50).toString())
        if (call.has("children")) put("children", call.int("children", 0, 0..50).toString())
    }

    private fun searchRooms(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val stay = checked { CrmCloudbedsScreens.stay(lead, stayParams(call), context.today) }
        val rooms = checked { CrmCloudbeds.availability(call.tenant, stay.start, stay.end, stay.adults, stay.children) }
        return buildJsonObject {
            put("start", stay.start.toString())
            put("end", stay.end.toString())
            put("adults", stay.adults)
            put("children", stay.children)
            putJsonArray("rooms") {
                rooms.forEach { room ->
                    add(buildJsonObject {
                        put("roomId", room.roomId)
                        put("room", room.roomName)
                        put("type", room.roomTypeName)
                        put("maxGuests", room.maxGuests)
                        put("total", text(room.total?.toPlainString()))
                    })
                }
            }
        }
    }

    private fun assignRoom(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val params = stayParams(call)
        val stay = checked { CrmCloudbedsScreens.stay(lead, params, context.today) }
        // Names, rate and capacity come from Cloudbeds again, never from the agent.
        val roomId = call.requiredString("roomId", 60)
        val room = checked { CrmCloudbeds.availability(call.tenant, stay.start, stay.end, stay.adults, stay.children) }.firstOrNull { it.roomId == roomId }
            ?: throw McpToolException("Esa habitacion no esta libre para esas fechas y huespedes; usa search_rooms.")
        val roomParams = params + mapOf(
            "start" to stay.start.toString(), "end" to stay.end.toString(), "adults" to stay.adults.toString(), "children" to stay.children.toString(),
            "room" to room.roomId, "type" to room.roomTypeId, "roomName" to room.roomName, "typeName" to room.roomTypeName,
            "total" to room.total?.toPlainString().orEmpty(), "max" to room.maxGuests.toString(),
        )
        return result(checked { CrmLeadItemActions.assignRoom(context, lead, roomParams, actor(call)) })
    }

    private fun reserve(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val rooms = (call.arguments["rooms"] as? JsonArray) ?: throw McpToolException("'rooms' debe ser una lista.")
        // The reservation form: guests per chosen room, country, zip and the payment label.
        val values = mutableMapOf("country" to call.string("country", 2), "zip" to call.string("zip", 20))
        rooms.forEach { element ->
            val room = element as? JsonObject ?: throw McpToolException("Cada habitacion de 'rooms' debe ser un objeto.")
            fun field(key: String) = (room[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val item = runCatching { UUID.fromString(field("item")) }.getOrNull() ?: throw McpToolException("Cada habitacion necesita el id 'item'.")
            values["adults_$item"] = field("adults")
            values["children_$item"] = field("children")
        }
        if (call.has("payment")) {
            val wanted = call.string("payment", 40)
            values["payment"] = CrmCloudbeds.paymentLabels.entries.firstOrNull { it.key == wanted || it.value.equals(wanted, ignoreCase = true) }?.value
                ?: throw McpToolException("Metodo de pago desconocido. Opciones: ${CrmCloudbeds.paymentLabels.keys.joinToString()}.")
        }
        val reservation = checked { CrmLeadItemActions.reserve(context, lead, values, actor(call)) }
        return result(reservation.message)
    }
}
