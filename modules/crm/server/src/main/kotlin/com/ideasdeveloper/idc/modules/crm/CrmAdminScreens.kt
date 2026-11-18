package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldCondition
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiNavigate
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
import java.util.UUID

/** Conversation, assignments and the mail, templates, anti-spam and team settings of the CRM. */
object CrmAdminScreens {
    private const val UiRoute = CrmScreens.UiRoute
    private const val NoTemplate = "Sin plantilla"
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun time(instant: java.time.Instant) = timeFormat.format(instant.atZone(ZoneId.systemDefault()))

    // ---- Conversation of a lead ----

    fun conversation(context: CrmScreens.Context, lead: CrmLead, canSend: Boolean, submitted: Map<String, String>?): UiNode {
        val messages = CrmInbox.messages(context.tenant, lead.id, 100)
        val entries = messages.map { message ->
            val incoming = message.direction == "in"
            val title = (if (incoming) "Recibido" else "Enviado") + " por ${CrmInbox.label(message.channel)}" +
                if (message.subject.isNotBlank()) ": ${message.subject}" else ""
            val tone = when {
                message.status == "failed" -> UiTone.Danger
                incoming -> UiTone.Info
                else -> UiTone.Success
            }
            val body = message.body + if (message.status == "failed") "\nNo se envio: ${message.error}" else ""
            UiTimelineEntry(title, body, "${message.author} - ${time(message.createdAt)}", tone)
        }
        val channelContext = CrmChannelContext(context.tenant, context.serverMail)
        val channels = CrmChannels.all.map { it to it.unavailable(channelContext, lead) }
        val usable = channels.filter { it.second == null }.map { it.first }
        val composer: List<UiNode> = when {
            !canSend -> emptyList()
            usable.isEmpty() -> listOf(UiText(channels.joinToString(" ") { (channel, reason) -> "${channel.label}: $reason" }, tone = UiTone.Warning))
            else -> {
                val templates = CrmMail.templates(context.tenant)
                val fields = listOf(
                    UiField("channel", "Canal", type = "dropdown", required = true, options = usable.map { it.label }),
                    UiField(
                        "template", "Plantilla", type = "dropdown", options = listOf(NoTemplate) + templates.map { it.name },
                        help = "Con plantilla y sin mensaje se envia la plantilla completa.",
                    ),
                    UiField("subject", "Asunto", help = "Solo para correo; con plantilla se usa el de la plantilla si lo dejas vacio."),
                    UiField("text", "Mensaje", type = "textarea"),
                )
                val defaults = mapOf("channel" to usable.first().label, "template" to NoTemplate)
                listOf(
                    UiForm(
                        "reply", fields, defaults + submitted.orEmpty().filterKeys { it in setOf("channel", "template", "subject", "text") },
                        submit = UiButton("Enviar", UiRequest(UiRoute + "/lead", params = mapOf("id" to lead.id.toString(), "op" to "send"), formId = "reply")),
                    ),
                )
            }
        }
        return UiCard(composer + UiTimeline(entries, emptyText = "Sin mensajes."), title = "Conversacion")
    }

    /**
     * Builds the message to send from the reply form: a typed text, or the chosen template filled for the lead (translated to
     * the guest's language when the template asks for it). A failed translation is added to [notes] for the lead's activity.
     */
    fun composeReply(context: CrmScreens.Context, lead: CrmLead, values: Map<String, String>, notes: MutableList<String>? = null): Pair<CrmChannel, CrmEmail> {
        val channel = CrmChannels.all.firstOrNull { it.label == values["channel"] } ?: throw CrmValidationException("Elige el canal.")
        channel.unavailable(CrmChannelContext(context.tenant, context.serverMail), lead)?.let { throw CrmValidationException(it) }
        val template = values["template"]?.takeIf { it.isNotBlank() && it != NoTemplate }?.let { name ->
            CrmMail.templates(context.tenant).firstOrNull { it.name == name } ?: throw CrmValidationException("Esa plantilla ya no existe.")
        }
        val text = values["text"].orEmpty().trim()
        val subject = values["subject"].orEmpty().trim()
        if (text.length > 20000) throw CrmValidationException("El mensaje admite hasta 20000 caracteres.")
        val placeholders = CrmInbox.templateValues(context.tenant, lead, context.names, context.stages) +
            if (text.isEmpty() && template != null) CrmTemplateMailer.dynamicValues(context.tenant, lead) else emptyMap()
        val email = when {
            text.isNotEmpty() -> CrmMail.render(subject.ifEmpty { template?.subject.orEmpty() }, text, placeholders)
            template != null && subject.isEmpty() -> {
                val mail = CrmTemplateMailer.renderFor(context.tenant, template, lead)
                mail.note?.let { notes?.add("${template.name}: $it") }
                mail.toEmail()
            }
            template != null -> CrmMail.render(subject, template.body, placeholders)
            else -> throw CrmValidationException("Escribe el mensaje o elige una plantilla.")
        }
        return channel to email
    }

    // ---- Mail settings ----

    fun mail(context: CrmScreens.Context, submitted: Map<String, String>?): List<UiNode> {
        val settings = CrmMail.load(context.tenant)
        val imapOn = UiFieldCondition("imapEnabled", listOf("true"))
        val fields = listOf(
            UiField("imapEnabled", "Leer las respuestas de los clientes (IMAP)", type = "boolean", section = "Recepcion (IMAP)",
                help = "Cada 5 minutos se leen los correos nuevos del buzon; se agregan a la conversacion del lead o crean uno nuevo."),
            UiField("imapHost", "Servidor IMAP", visibleWhen = imapOn, section = "Recepcion (IMAP)"),
            UiField("imapPort", "Puerto", type = "number", visibleWhen = imapOn, section = "Recepcion (IMAP)"),
            UiField("imapSsl", "Conexion segura (SSL)", type = "boolean", visibleWhen = imapOn, section = "Recepcion (IMAP)"),
            UiField("imapUsername", "Usuario", visibleWhen = imapOn, section = "Recepcion (IMAP)"),
            UiField("imapPassword", "Contrasena", type = "password", visibleWhen = imapOn, section = "Recepcion (IMAP)"),
            UiField("imapFolder", "Carpeta", visibleWhen = imapOn, section = "Recepcion (IMAP)"),
        )
        val status = when {
            !settings.imapEnabled -> UiStatus("Lectura de correo desactivada", UiTone.Neutral)
            settings.imapLastError.isNotEmpty() -> UiStatus("Error al leer el buzon: ${settings.imapLastError}", UiTone.Danger)
            settings.imapCheckedAt != null -> UiStatus("Ultima lectura: ${time(settings.imapCheckedAt)}", UiTone.Success)
            else -> UiStatus("Esperando la primera lectura", UiTone.Warning)
        }
        // El envio usa el SMTP de la empresa; sin el, el del servidor.
        val sending = if (CrmMail.smtp(context.tenant, context.serverMail) == null) {
            UiStatus("No hay SMTP disponible: el dueno de la empresa debe configurar el correo de la empresa para enviar correos", UiTone.Danger)
        } else {
            UiStatus("Envio de correo listo", UiTone.Success)
        }
        return listOf(
            UiCard(listOf(sending, status)),
            UiCard(
                listOf(
                    UiForm(
                        "mail", fields, CrmMail.toFormValues(settings) + submitted.orEmpty(),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/mail", params = mapOf("op" to "save"), formId = "mail")),
                        // Reading the inbox (IMAP) is configured once: closed. Sending uses the company SMTP.
                        collapsibleSections = true,
                    ),
                ),
            ),
        )
    }

    // ---- Templates ----

    fun templates(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val templates = CrmMail.templates(context.tenant)
        val editing = params["template"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id -> templates.firstOrNull { it.id == id } }
        val rows = templates.map { template ->
            UiRow(
                listOf(
                    UiText(template.name),
                    UiButton("Editar", UiNavigate("templates", mapOf("template" to template.id.toString())), style = "secondary"),
                    UiButton(
                        "Eliminar", UiRequest(UiRoute + "/templates", params = mapOf("op" to "delete", "id" to template.id.toString()), confirm = "Eliminar la plantilla ${template.name}?"),
                        style = "danger",
                    ),
                ),
            )
        }
        val variables = CrmTemplateMailer.variableHelp(context.fields, CrmImportTargets.customerFields(context.tenant))
        val translation = CrmTranslate.load(context.tenant)
        val values = (editing?.let { mapOf("name" to it.name, "subject" to it.subject, "body" to it.body, "baseLanguage" to it.baseLanguage, "translate" to it.translate.toString()) }
            ?: mapOf("baseLanguage" to "es", "translate" to "false")) + submitted.orEmpty()
        val recent = recentLeads(context)
        val saveParams = mapOf("op" to "save") + listOfNotNull(editing?.let { "id" to it.id.toString() })
        return listOf(
            UiCard(rows.ifEmpty { listOf(UiText("Aun no hay plantillas.", tone = UiTone.Warning)) }, title = "Plantillas"),
            UiCard(
                listOf(
                    UiForm(
                        "template",
                        listOf(
                            UiField("name", "Nombre", required = true),
                            UiField("subject", "Asunto", required = true),
                            UiField("body", "Contenido", type = "textarea", required = true,
                                help = "Texto o HTML. Las variables se reemplazan con los datos del lead al enviar."),
                            UiField("baseLanguage", "Idioma en que esta escrita (codigo)", help = "Por ejemplo es o en.", section = "Traduccion"),
                            UiField(
                                "translate", "Traducir al idioma del huesped", type = "boolean", section = "Traduccion",
                                help = if (translation.ready && translation.languageField.isNotEmpty()) "Se traduce solo si el huesped tiene otro idioma. Las variables y el formato no se tocan."
                                else "Configura el traductor y el campo de idioma en Integraciones > Traduccion.",
                            ),
                        ),
                        values,
                        submit = UiButton(if (editing == null) "Crear plantilla" else "Guardar plantilla", UiRequest(UiRoute + "/templates", params = saveParams, formId = "template")),
                    ),
                ),
                title = if (editing == null) "Nueva plantilla" else "Editar ${editing.name}",
                actions = listOfNotNull(editing?.let { UiButton("Nueva", UiNavigate("templates"), style = "secondary") }),
            ),
            editing?.let { template ->
                UiCard(
                    listOf(
                        UiText("Muestra el correo como se enviaria a un lead (con la traduccion si corresponde). No envia nada.", UiTextStyle.Caption),
                        UiForm(
                            "preview",
                            listOf(UiField("previewLead", "Lead de ejemplo", type = "dropdown", options = recent.map { previewLabel(it) })),
                            mapOf("previewLead" to recent.firstOrNull()?.let { previewLabel(it) }.orEmpty()) + submitted.orEmpty().filterKeys { it == "previewLead" },
                            submit = UiButton("Vista previa", UiRequest(UiRoute + "/templates", params = mapOf("op" to "preview", "id" to template.id.toString()), formId = "preview"), style = "secondary"),
                        ),
                    ),
                    title = "Vista previa",
                )
            },
            UiCard(listOf(UiText(variables)), title = "Variables"),
        ).filterNotNull()
    }

    // ---- Template preview ----

    private fun recentLeads(context: CrmScreens.Context) = CrmRepository.leads(context.tenant, context.scope, CrmLeadFilter(), 25, 0)

    private fun previewLabel(lead: CrmLead) = "${lead.name} #${lead.id.toString().take(8)}"

    /** The lead chosen in the preview form, only among the recent ones the user can see. */
    fun previewLead(context: CrmScreens.Context, label: String): CrmLead? =
        recentLeads(context).firstOrNull { previewLabel(it) == label }

    fun previewText(template: CrmTemplate, lead: CrmLead, mail: RenderedMail): String = buildString {
        append("Vista previa de ${template.name} para ${lead.name} (idioma: ${mail.language}${if (mail.translated) ", traducida" else ""}).\n")
        mail.note?.let { append("$it Se usaria el original.\n") }
        append("Asunto: ${mail.subject}\n\n")
        append(mail.text.take(1500))
    }

    // ---- Anti-spam ----

    fun spam(context: CrmScreens.Context, submitted: Map<String, String>?): List<UiNode> {
        val settings = CrmSpam.load(context.tenant)
        val fields = listOf(
            UiField("words", "Palabras bloqueadas", type = "textarea", help = "Una por linea. Tambien detecta plurales, separadores (s.e.o) y numeros por letras (c4sino)."),
            UiField("maxLinks", "Maximo de enlaces", type = "number", help = "No cuentan los enlaces al sitio propio ni a encuentra24.com."),
            UiField("blockCyrillic", "Bloquear texto en cirilico", type = "boolean"),
            UiField("blockedDomains", "Dominios de correo bloqueados", type = "textarea", help = "Uno por linea, por ejemplo spam.ru."),
            UiField("skipKnownLeads", "No filtrar a contactos que ya son leads", type = "boolean"),
        ) + CrmSpam.channelLabels.map { (id, label) -> UiField("channel_$id", label, type = "boolean", section = "Aplicar en") }
        val values = mapOf(
            "words" to settings.words.joinToString("\n"),
            "maxLinks" to settings.maxLinks.toString(),
            "blockCyrillic" to settings.blockCyrillic.toString(),
            "blockedDomains" to settings.blockedDomains.joinToString("\n"),
            "skipKnownLeads" to settings.skipKnownLeads.toString(),
        ) + CrmSpam.channelLabels.keys.associate { "channel_$it" to (it in settings.channels).toString() }
        val log = CrmSpam.recent(context.tenant).map { blocked ->
            UiTimelineEntry("${CrmSpam.channelLabels[blocked.channel] ?: blocked.channel}: ${blocked.reason}", blocked.excerpt, "${blocked.sender} - ${time(blocked.at)}", UiTone.Danger)
        }
        return listOf(
            UiCard(
                listOf(
                    UiForm(
                        "spam", fields, values + submitted.orEmpty(),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/spam", params = mapOf("op" to "save"), formId = "spam")),
                        minFieldWidth = 220,
                    ),
                ),
                title = "Reglas",
            ),
            UiCard(listOf(UiTimeline(log, emptyText = "Nada bloqueado todavia.")), title = "Ultimos bloqueos"),
        )
    }

    // ---- Team: notification email and options per user, digest hour ----

    /** Hour of the daily digest: a card of the General tab of Configuracion (action "settings:digest"). */
    fun digestCard(context: CrmScreens.Context): UiNode {
        val digestHour = context.tenant.prepareStatement("SELECT digest_hour FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 7 }
        }
        return UiCard(
            listOf(
                UiForm(
                    "digest",
                    listOf(UiField("digestHour", "Hora del resumen diario (0-23)", type = "number")),
                    mapOf("digestHour" to digestHour.toString()),
                    submit = UiButton("Guardar", UiRequest(UiRoute + "/settings", params = mapOf("op" to "digest"), formId = "digest"), style = "secondary"),
                ),
            ),
            title = "Resumen diario",
        )
    }

    fun team(context: CrmScreens.Context): List<UiNode> {
        val saved = CrmNotify.userSettings(context.tenant)
        val users = context.users.map { user ->
            val settings = saved[user.id]
            val formId = "team_${user.id}"
            UiCard(
                listOf(
                    UiForm(
                        formId,
                        listOf(
                            UiField("email", "Correo para avisos", type = "email"),
                            UiField("notifyNewLeads", "Aviso de cada lead nuevo", type = "boolean", help = "Solo llega a quien ve todos los leads."),
                            UiField("notifyAssigned", "Aviso cuando le asignan un lead", type = "boolean"),
                            UiField("dailyDigest", "Resumen diario de sus leads", type = "boolean"),
                            UiField(
                                "notifyMentioned", "Avisarme por correo cuando me mencionen", type = "boolean",
                                help = "Llega al correo del perfil del usuario. El aviso en la campana de la app siempre se crea.",
                            ),
                            UiField("notifyNewMessage", "Correo con cada mensaje nuevo de mis leads", type = "boolean", help = "Solo de los leads asignados. El aviso en la campana siempre se crea."),
                            UiField("notifyFollowUp", "Correo cuando vence un seguimiento", type = "boolean", help = "Solo de los leads asignados. El aviso en la campana siempre se crea."),
                        ),
                        mapOf(
                            "email" to settings?.email.orEmpty(),
                            "notifyNewMessage" to (settings?.notifyNewMessage ?: true).toString(),
                            "notifyFollowUp" to (settings?.notifyFollowUp ?: true).toString(),
                            "notifyNewLeads" to (settings?.notifyNewLeads ?: false).toString(),
                            "notifyAssigned" to (settings?.notifyAssigned ?: true).toString(),
                            "dailyDigest" to (settings?.dailyDigest ?: false).toString(),
                            "notifyMentioned" to (settings?.notifyMentioned ?: true).toString(),
                        ),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/team", params = mapOf("op" to "user", "user" to user.id.toString()), formId = formId), style = "secondary"),
                        minFieldWidth = 220,
                    ),
                ),
                title = user.name,
            )
        }
        return listOf(
            UiText("Usuarios que pueden tener leads (con algun permiso del CRM o duenos de la empresa).", UiTextStyle.Caption),
        ) + users.ifEmpty { listOf(UiText("Ningun usuario tiene permisos del CRM todavia.", tone = UiTone.Warning)) }
    }
}
