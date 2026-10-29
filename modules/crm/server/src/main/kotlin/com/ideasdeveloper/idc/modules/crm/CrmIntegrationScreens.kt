package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldCondition
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiNode
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
        meta(context, submitted, pickers = params["numbers"]) + encuentra24(context) + CrmWordPressScreens.settings(context, params["secret"]) +
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
                    UiText("Cada 5 minutos se importan los mensajes, chats y llamadas de los anuncios. La API no permite responder desde el CRM: los mensajes de WhatsApp con enlace de un anuncio tambien quedan con origen Encuentra24."),
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

    private fun meta(context: CrmScreens.Context, submitted: Map<String, String>?, pickers: String?): List<UiNode> {
        val settings = CrmMeta.load(context.tenant)
        val on = UiFieldCondition("enabled", listOf("true"))
        val fields = listOf(
            UiField("enabled", "Recibir y responder por WhatsApp, Messenger e Instagram", type = "boolean"),
            UiField("appSecret", "App Secret", type = "password", visibleWhen = on, section = "App de Meta",
                help = "Meta for Developers > tu app > Configuracion > Basica. Valida la firma de cada mensaje."),
            UiField("verifyToken", "Verify token", visibleWhen = on, section = "App de Meta",
                help = "Texto libre que escribes igual en Meta al configurar el webhook."),
            UiField("systemToken", "Token del usuario del sistema (Meta Business)", type = "password", visibleWhen = on, section = "App de Meta",
                help = "Con permisos whatsapp_business_messaging, whatsapp_business_management, pages_messaging, instagram_manage_messages."),
            UiField("pageId", "ID de la pagina de Facebook", visibleWhen = on, section = "Messenger e Instagram",
                help = "Solo si el token administra varias paginas."),
            UiField("wabaId", "WhatsApp Business Account ID", visibleWhen = on, section = "WhatsApp"),
            UiField("phoneNumberId", "Phone Number ID", visibleWhen = on, section = "WhatsApp",
                help = "Se completa con Conectar WhatsApp si la cuenta tiene un solo numero."),
            UiField("recordEchoes", "Guardar lo que el equipo responde desde la app de WhatsApp Business", type = "boolean", visibleWhen = on, section = "WhatsApp"),
        )
        val status = listOf(
            UiStatus(
                if (settings.whatsAppReady) "WhatsApp listo (numero ${settings.phoneNumberId})" else "WhatsApp sin configurar",
                if (settings.whatsAppReady) UiTone.Success else UiTone.Neutral,
            ),
            UiStatus(
                if (settings.pageReady) "Pagina conectada: ${settings.pageName.orEmpty()}${if (settings.instagramAccountId != null) " (con Instagram)" else " (sin Instagram vinculado)"}" else "Pagina de Facebook sin conectar",
                if (settings.pageReady) UiTone.Success else UiTone.Neutral,
            ),
        )
        val numbers = pickers?.split(';')?.mapNotNull { row ->
            val parts = row.split('|')
            if (parts.size < 2) null else UiRow(
                listOf(
                    UiText("${parts[1]} ${parts.getOrNull(2).orEmpty()}"),
                    UiButton("Usar este numero", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaNumber", "id" to parts[0])), style = "secondary"),
                ),
            )
        }.orEmpty()
        val log = CrmMeta.recent(context.tenant).map { UiTimelineEntry(it.event, it.detail, timeFormat.format(it.at.atZone(ZoneId.systemDefault()))) }
        return listOf(
            UiText("Meta (WhatsApp, Messenger e Instagram)", UiTextStyle.Subtitle),
            UiCard(
                status + listOf(
                    UiText("Callback URL para el webhook de Meta (WhatsApp: campos messages y smb_message_echoes; Pagina: messages y standby; Instagram: messages):", UiTextStyle.Caption),
                    UiText(hookUrl(context, "meta")),
                ),
            ),
            UiCard(
                listOf(
                    UiForm(
                        "meta", fields, CrmMeta.toFormValues(settings) + submitted.orEmpty(),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaSave"), formId = "meta")),
                    ),
                ),
                actions = listOf(
                    UiButton("Conectar pagina", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaPage")), style = "secondary"),
                    UiButton("Conectar WhatsApp", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaWhatsApp")), style = "secondary"),
                ),
            ),
        ) + listOfNotNull(
            numbers.takeIf { it.isNotEmpty() }?.let { UiCard(it, title = "Numeros de la cuenta de WhatsApp") },
            UiCard(listOf(UiTimeline(log, emptyText = "Sin eventos de Meta.")), title = "Ultimos eventos de Meta"),
        )
    }
}
