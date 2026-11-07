package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldCondition
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiOpenUrl
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTimeline
import com.ideasdeveloper.idc.ui.UiTimelineEntry
import com.ideasdeveloper.idc.ui.UiTone
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Integrations of the CRM with external channels and systems. */
object CrmIntegrationScreens {
    private const val UiRoute = CrmScreens.UiRoute
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // Public URL a service calls for this company, e.g. .../modules/crm/hooks/acme/meta.
    fun hookUrl(context: CrmScreens.Context, name: String) = "${context.publicBase}/modules/crm/hooks/${context.permissions.companyCode}/$name"

    fun integrations(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> =
        meta(context, submitted, pickers = params["numbers"], link = params["link"]) + encuentra24(context) + CrmWordPressScreens.settings(context, params["secret"]) +
            CrmCloudbedsScreens.settings(context)

    // ---- Encuentra24 Leads API ----

    private fun encuentra24(context: CrmScreens.Context): List<UiNode> {
        val settings = CrmEncuentra24.load(context.tenant)
        val on = UiFieldCondition("enabled", listOf("true"))
        val status = when {
            !settings.ready -> UiStatus("Encuentra24 desactivado", UiTone.Neutral)
            settings.lastError.isNotEmpty() -> UiStatus("Error: ${settings.lastError}", UiTone.Danger)
            settings.lastSync != null -> UiStatus("Ultima sincronizacion ${timeFormat.format(settings.lastSync.atZone(ZoneId.systemDefault()))}: ${settings.lastResult}", UiTone.Success)
            else -> UiStatus("Esperando la primera sincronizacion", UiTone.Warning)
        }
        return listOf(
            UiText("Encuentra24", UiTextStyle.Subtitle),
            UiCard(
                listOf(
                    status,
                    UiText("Cada 5 minutos se importan los mensajes, chats y llamadas de los anuncios, y el anuncio queda en los intereses del lead. " +
                        "La API no permite responder desde el CRM. Un WhatsApp, Messenger, Instagram, correo o formulario con el enlace de un anuncio " +
                        "tambien queda con origen Encuentra24 y con ese anuncio en sus intereses."),
                    UiForm(
                        "e24",
                        listOf(
                            UiField("enabled", "Importar los leads de Encuentra24", type = "boolean"),
                            UiField("apiKey", "API key", type = "password", visibleWhen = on),
                            UiField("slug", "Slug de la empresa", visibleWhen = on, placeholder = "mi-inmobiliaria"),
                        ),
                        CrmEncuentra24.toFormValues(settings),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "e24Save"), formId = "e24")),
                    ),
                ),
            ),
            UiCard(
                listOf(
                    UiForm(
                        "e24history",
                        listOf(UiField("fromDate", "Desde", type = "date", help = "Vacio: desde la ultima sincronizacion.")),
                        submit = UiButton("Importar ahora", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "e24Sync"), formId = "e24history"), style = "secondary", enabled = settings.ready),
                    ),
                ),
                title = "Importar historial",
            ),
        )
    }

    // ---- Meta: WhatsApp, Messenger, Instagram ----

    private fun meta(context: CrmScreens.Context, submitted: Map<String, String>?, pickers: String?, link: String?): List<UiNode> {
        val settings = CrmMeta.load(context.tenant)
        val serverApp = CrmMetaServer.app()
        val company = context.permissions.companyCode
        val workspace = context.permissions.serverOwner && CrmMetaServer.serverWorkspace() == company
        val header = listOfNotNull(
            UiText("Meta Business (WhatsApp, Messenger e Instagram)", UiTextStyle.Subtitle),
            if (workspace) serverAppCard(context, serverApp ?: CrmMetaServerApp(), submitted) else null,
        )
        if (settings.serverMode) return header + serverModeNodes(context, settings, serverApp, link)
        val switch = if (serverApp?.ready == true) UiCard(
            listOf(
                UiText("El servidor tiene su propia app de Meta: la empresa solo inicia sesion con Facebook y elige su pagina, Instagram y numero de " +
                    "WhatsApp. No hace falta crear ni revisar una app en Meta for Developers.", UiTextStyle.Caption),
            ),
            title = "Conectar con la app del servidor",
            actions = listOf(
                UiButton(
                    "Usar la app del servidor",
                    UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaMode", "mode" to ServerMode), confirm = "Se borran el App Secret y los tokens de la app propia. ¿Continuar?"),
                ),
            ),
        ) else null
        return header + listOfNotNull(switch) + ownModeNodes(context, settings, submitted, pickers)
    }

    // Company connected through the server app: status per channel, the connection link and options.
    private fun serverModeNodes(context: CrmScreens.Context, settings: CrmMetaSettings, app: CrmMetaServerApp?, link: String?): List<UiNode> {
        fun status(ready: Boolean, yes: String, no: String) = UiStatus(if (ready) yes else no, if (ready) UiTone.Success else UiTone.Neutral)
        val ready = app?.ready == true
        val log = CrmMeta.recent(context.tenant).map { UiTimelineEntry(it.event, it.detail, timeFormat.format(it.at.atZone(ZoneId.systemDefault()))) }
        val instagram = settings.instagramUsername?.let { "@$it" } ?: settings.instagramAccountId
        return listOfNotNull(
            UiCard(
                listOfNotNull(
                    if (!ready) UiStatus("La app de Meta del servidor no esta activa: los mensajes no llegan. Avisa al dueno del servidor.", UiTone.Danger) else null,
                    status(settings.whatsAppReady, "WhatsApp: numero ${settings.phoneNumberId} conectado", "WhatsApp sin conectar"),
                    status(settings.ready("messenger"), "Messenger: pagina ${settings.pageName.orEmpty()}", "Messenger sin conectar"),
                    status(
                        settings.ready("instagram") && settings.instagramAccountId != null, "Instagram: $instagram",
                        if (settings.pageToken != null) "Instagram: la pagina no tiene una cuenta vinculada" else "Instagram sin conectar",
                    ),
                    UiText("Pulsa Conectar con Meta, abre la ventana, inicia sesion con la cuenta de Facebook que administra la pagina y el WhatsApp " +
                        "de la empresa, y elige la pagina y el numero. Para cambiar de pagina o de numero, conecta otra vez.", UiTextStyle.Caption),
                    if (link != null) UiButton("Abrir la ventana de Meta", UiOpenUrl(link)) else null,
                ),
                title = "Conexion con la app del servidor",
                actions = listOfNotNull(
                    UiButton("Conectar con Meta", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaLink")), style = if (link == null) "primary" else "secondary")
                        .takeIf { ready },
                    UiButton(
                        "Desconectar", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaDisconnect"), confirm = "Se dejan de recibir y enviar mensajes de Meta. ¿Desconectar?"),
                        style = "secondary",
                    ).takeIf { settings.enabled },
                ),
            ),
            UiCard(
                listOf(
                    UiForm(
                        "metaOptions",
                        listOf(UiField("recordEchoes", "Guardar lo que el equipo responde desde la app de WhatsApp Business (coexistencia)", type = "boolean")),
                        mapOf("recordEchoes" to settings.recordEchoes.toString()),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaServerOptions"), formId = "metaOptions"), style = "secondary"),
                    ),
                ),
                title = "Opciones",
            ),
            UiCard(
                listOf(UiText("Configura una app de Meta propia de la empresa en lugar de la del servidor.", UiTextStyle.Caption)),
                title = "App propia",
                actions = listOf(
                    UiButton(
                        "Usar una app propia",
                        UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaMode", "mode" to OwnMode), confirm = "Se desconecta la app del servidor. ¿Continuar?"),
                        style = "secondary",
                    ),
                ),
                collapsible = true,
                expanded = false,
            ),
            UiCard(listOf(UiTimeline(log, emptyText = "Sin eventos de Meta.")), title = "Registro de eventos", collapsible = true, expanded = false),
        )
    }

    // Server workspace: the server owner's Meta app that every company can connect to.
    private fun serverAppCard(context: CrmScreens.Context, app: CrmMetaServerApp, submitted: Map<String, String>?): UiNode {
        val on = UiFieldCondition("serverEnabled", listOf("true"))
        val base = context.publicBase
        val host = base.substringAfter("://").substringBefore('/').substringBefore(':')
        val steps = listOf(
            "1. Portafolio de Meta Business de tu empresa (la del servidor), verificado. En Configuracion del negocio > Informacion del negocio, " +
                "completa ademas la verificacion de acceso como Tech Provider: es obligatoria para manejar paginas y WhatsApp de otras empresas.",
            "2. App de Meta for Developers tipo Business en ese portafolio, con los casos de uso de Messenger (Instagram con Facebook login) y de " +
                "WhatsApp, y el producto Facebook Login for Business. Permisos: pages_messaging, pages_manage_metadata, pages_read_engagement, " +
                "pages_show_list, business_management, instagram_basic, instagram_manage_messages, whatsapp_business_messaging y whatsapp_business_management.",
            "3. Configuracion > Basica: dominio de la app $host, URL de la politica de privacidad y, en Facebook Login for Business > Configuracion, " +
                "Dominios permitidos para el SDK de JavaScript: $base. Callback de desautorizacion: $base${CrmMetaServer.DeauthorizePath}. " +
                "Solicitud de eliminacion de datos: $base${CrmMetaServer.DataDeletionPath}.",
            "4. Facebook Login for Business > Configuraciones: crea una para Messenger e Instagram (tipo token de usuario, con los permisos de paginas e " +
                "Instagram) y otra de WhatsApp Embedded Signup (registro integrado). Copia sus IDs aqui.",
            "5. Webhooks con la Callback URL $base${CrmMetaServer.WebhookPath} y el verify token de aqui: Page (messages y standby), Instagram " +
                "(messages) y WhatsApp Business Account (messages y smb_message_echoes). No hace falta suscribir paginas a mano: cada empresa lo hace al conectarse.",
            "6. App Review de los permisos (el video muestra Conectar con Meta desde el CRM) y publica la app. Mientras tanto solo funcionan las " +
                "personas con rol en la app.",
        )
        return UiCard(
            steps.map { UiText(it, UiTextStyle.Caption) } + listOf(
                UiForm(
                    "metaServer",
                    listOf(
                        UiField("serverEnabled", "Permitir que las empresas del servidor se conecten con esta app", type = "boolean"),
                        UiField("serverAppId", "App ID", visibleWhen = on),
                        UiField("serverAppSecret", "App Secret", type = "password", visibleWhen = on),
                        UiField("serverVerifyToken", "Verify token", visibleWhen = on, help = "El mismo que escribes en los webhooks de la app."),
                        UiField("serverPagesConfigId", "ID de la configuracion de Messenger e Instagram", visibleWhen = on),
                        UiField("serverWhatsAppConfigId", "ID de la configuracion de WhatsApp (Embedded Signup)", visibleWhen = on),
                    ),
                    CrmMetaServer.appFormValues(app) + submitted.orEmpty().filterKeys { it.startsWith("server") },
                    submit = UiButton("Guardar app del servidor", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaServerApp"), formId = "metaServer")),
                ),
            ),
            title = if (app.ready) "App de Meta del servidor (activa)" else "App de Meta del servidor",
            collapsible = true,
            expanded = !app.ready,
        )
    }

    // The company's own Meta app (the screen of the WordPress plugin).
    private fun ownModeNodes(context: CrmScreens.Context, settings: CrmMetaSettings, submitted: Map<String, String>?, pickers: String?): List<UiNode> {
        val on = UiFieldCondition("enabled", listOf("true"))
        val shared = UiFieldCondition("sharedTokens", listOf("true"))
        val own = UiFieldCondition("sharedTokens", listOf("false"))
        val whatsApp = "WhatsApp"
        val messenger = "Messenger"
        val instagram = "Instagram"
        // Common settings first (no section); each channel is an accordion with what only it uses.
        val fields = listOf(
            UiField("enabled", "Recibir y responder por WhatsApp, Messenger e Instagram", type = "boolean"),
            UiField("appSecret", "App Secret", type = "password", visibleWhen = on,
                help = "Meta for Developers > tu app > Configuracion > Basica. Valida la firma de cada mensaje de los tres canales."),
            UiField("verifyToken", "Verify token", visibleWhen = on,
                help = "Texto libre que escribes igual en Meta al configurar los webhooks de los tres canales."),
            UiField("systemToken", "Token del usuario del sistema (Meta Business)", type = "password", visibleWhen = on,
                help = "Token que no caduca, con whatsapp_business_messaging y whatsapp_business_management. Para compartirlo con Messenger e Instagram, " +
                    "tambien pages_messaging, pages_show_list, pages_manage_metadata, pages_read_engagement, instagram_basic e instagram_manage_messages."),
            UiField("sharedTokens", "Usar este token tambien para Messenger e Instagram", type = "boolean", visibleWhen = on,
                help = "Al guardar se obtiene el token de la pagina y su cuenta de Instagram. Desactivado, Messenger e Instagram usan cada uno su propio token de pagina."),
            UiField("wabaId", "WhatsApp Business Account ID", visibleWhen = on, section = whatsApp,
                help = "WhatsApp Manager > Configuracion de la cuenta. Al guardar, la app se suscribe a esta cuenta."),
            UiField("phoneNumberId", "Phone Number ID", visibleWhen = on, section = whatsApp,
                help = "Se completa al guardar si la cuenta tiene un solo numero. Solo se reciben los mensajes de este numero."),
            UiField("recordEchoes", "Guardar lo que el equipo responde desde la app de WhatsApp Business (coexistencia)", type = "boolean", visibleWhen = on, section = whatsApp),
            UiField("pageName", "Pagina conectada", readOnly = true, visibleWhen = on, section = messenger),
            UiField("pageId", "ID de la pagina de Facebook", visibleWhen = shared, section = messenger,
                help = "Solo si el token administra varias paginas."),
            UiField("messengerToken", "Token de Messenger", type = "password", visibleWhen = own, section = messenger,
                help = "Token de la pagina con el permiso pages_messaging."),
            UiField("instagramAccount", "Cuenta de Instagram", readOnly = true, visibleWhen = on, section = instagram,
                help = "La cuenta vinculada a la pagina; se lee al guardar."),
            UiField("instagramToken", "Token de Instagram", type = "password", visibleWhen = own, section = instagram,
                help = "Token de la pagina vinculada a la cuenta de Instagram, con instagram_basic e instagram_manage_messages."),
        )
        val notes = mapOf(
            whatsApp to if (settings.whatsAppReady) "Listo: numero ${settings.phoneNumberId}" else "Sin configurar",
            messenger to when {
                settings.ready("messenger") -> if (settings.sharedTokens) "Listo: pagina ${settings.pageName.orEmpty()}" else "Listo (token propio)"
                settings.sharedTokens -> "Falta conectar la pagina de Facebook"
                else -> "Falta el token de Messenger"
            },
            instagram to when {
                settings.ready("instagram") && settings.instagramAccountId != null ->
                    "Listo: ${settings.instagramUsername?.let { "@$it" } ?: settings.instagramAccountId}"
                settings.sharedTokens && settings.pageToken != null -> "La pagina no tiene una cuenta de Instagram vinculada"
                settings.sharedTokens -> "Falta conectar la pagina de Facebook"
                settings.instagramToken.isNullOrBlank() -> "Falta el token de Instagram"
                else -> "Guarda para leer la cuenta de Instagram"
            },
        )
        val numbers = pickers?.split(';')?.mapNotNull { row ->
            val parts = row.split('|')
            if (parts.size < 2) null else {
                val chosen = parts[0] == settings.phoneNumberId
                val detail = listOf(parts.getOrNull(2).orEmpty(), parts.getOrNull(3).orEmpty()).filter { it.isNotEmpty() }.joinToString(" - ")
                UiRow(
                    listOfNotNull(
                        UiText("${parts[1]}${if (detail.isNotEmpty()) " ($detail)" else ""}"),
                        if (chosen) UiStatus("Numero del CRM", UiTone.Success)
                        else UiButton("Usar este numero", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaNumber", "id" to parts[0])), style = "secondary"),
                    ),
                )
            }
        }.orEmpty()
        val log = CrmMeta.recent(context.tenant).map { UiTimelineEntry(it.event, it.detail, timeFormat.format(it.at.atZone(ZoneId.systemDefault()))) }
        return listOfNotNull(
            metaGuide(context, open = !settings.enabled),
            UiCard(
                listOf(
                    UiText("Callback URL de los webhooks, la misma para los tres canales:", UiTextStyle.Caption),
                    UiText(hookUrl(context, "meta")),
                    UiForm(
                        "meta", fields, CrmMeta.toFormValues(settings) + submitted.orEmpty(),
                        submit = UiButton("Guardar y conectar", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaSave"), formId = "meta")),
                        collapsibleSections = true,
                        sectionNotes = notes,
                    ),
                ),
                title = "Configuracion",
            ),
            numbers.takeIf { it.isNotEmpty() }?.let { UiCard(it, title = "Numeros de la cuenta de WhatsApp") },
            UiCard(listOf(UiTimeline(log, emptyText = "Sin eventos de Meta.")), title = "Registro de eventos", collapsible = true, expanded = false),
        )
    }

    // Step by step guide to set up the Meta app and connect it here; open while Meta is off.
    private fun metaGuide(context: CrmScreens.Context, open: Boolean): UiNode {
        val steps = listOf(
            "1. Negocio: el portafolio de Meta Business de la empresa debe estar verificado y ser dueno de la pagina, la cuenta de Instagram y la de " +
                "WhatsApp Business. Vincula Instagram a la pagina y, en la app de Instagram, activa Mensajes > Herramientas conectadas > Permitir acceso a los mensajes.",
            "2. App de Meta for Developers (tipo Business, del portafolio de la empresa): agrega los casos de uso de Messenger y de WhatsApp " +
                "(Instagram se configura dentro del de Messenger, con Facebook login). Permisos: pages_messaging, pages_manage_metadata, " +
                "pages_read_engagement, pages_show_list, business_management, instagram_basic, instagram_manage_messages, " +
                "whatsapp_business_messaging y whatsapp_business_management. Copia el App Secret de Configuracion > Basica y publica la app " +
                "(para responder a cualquier persona Meta pide App Review de pages_messaging e instagram_manage_messages).",
            "3. Usuario del sistema: en Configuracion del negocio > Usuarios del sistema crea uno administrador, asignale la app, la pagina, " +
                "Instagram y la cuenta de WhatsApp, y genera un token que no caduque con los permisos del paso 2.",
            "4. Webhooks, con la Callback URL ${hookUrl(context, "meta")} y el mismo verify token: Messenger (objeto Page) suscribe messages y " +
                "standby; Instagram suscribe messages; WhatsApp suscribe messages y, si usan la app de WhatsApp Business en el mismo numero, smb_message_echoes.",
            "5. Aqui: escribe el App Secret, el verify token y el token del sistema; en WhatsApp, el WhatsApp Business Account ID. Pulsa Guardar y " +
                "conectar: se obtiene el token de la pagina, se suscribe Instagram y la cuenta de WhatsApp. Envia un mensaje de prueba por cada canal " +
                "y revisa el registro de eventos: firma_rechazada significa que el App Secret no coincide.",
        )
        return UiCard(steps.map { UiText(it, UiTextStyle.Caption) }, title = "Guia paso a paso", collapsible = true, expanded = open)
    }
}
