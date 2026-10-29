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

    /** Builds the message to send from the reply form: a typed text, or the chosen template filled for the lead. */
    fun composeReply(context: CrmScreens.Context, lead: CrmLead, values: Map<String, String>): Pair<CrmChannel, CrmEmail> {
        val channel = CrmChannels.all.firstOrNull { it.label == values["channel"] } ?: throw CrmValidationException("Elige el canal.")
        channel.unavailable(CrmChannelContext(context.tenant, context.serverMail), lead)?.let { throw CrmValidationException(it) }
        val template = values["template"]?.takeIf { it.isNotBlank() && it != NoTemplate }?.let { name ->
            CrmMail.templates(context.tenant).firstOrNull { it.name == name } ?: throw CrmValidationException("Esa plantilla ya no existe.")
        }
        val text = values["text"].orEmpty().trim()
        val subject = values["subject"].orEmpty().trim()
        if (text.length > 20000) throw CrmValidationException("El mensaje admite hasta 20000 caracteres.")
        val placeholders = CrmInbox.templateValues(context.tenant, lead, context.names, context.stages)
        val email = when {
            text.isNotEmpty() -> CrmMail.render(subject.ifEmpty { template?.subject.orEmpty() }, text, placeholders)
            template != null -> CrmMail.render(subject.ifEmpty { template.subject }, template.body, placeholders)
            else -> throw CrmValidationException("Escribe el mensaje o elige una plantilla.")
        }
        return channel to email
    }

    // ---- Assignments ----

    fun assignments(context: CrmScreens.Context): List<UiNode> {
        val pending = CrmRepository.pendingSuggestions(context.tenant, context.scope, 100)
        val items = pending.map { lead ->
            UiListItem(
                title = lead.name,
                subtitle = "Sugerido: ${context.names.user(lead.suggestedOwnerUserId)} - Origen: ${lead.source}",
                trailing = lead.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                action = UiNavigate("lead", mapOf("id" to lead.id.toString())),
            )
        }
        return listOf(
            UiText("Los leads nuevos no se asignan solos: el origen (por ejemplo el agente de una propiedad) deja una sugerencia que apruebas, descartas o cambias desde la ficha del lead."),
            UiCard(listOf(UiList(items, emptyText = "No hay asignaciones pendientes.")), title = "${pending.size} pendiente(s)"),
        )
    }

    // ---- Mail settings ----

    fun mail(context: CrmScreens.Context, submitted: Map<String, String>?): List<UiNode> {
        val settings = CrmMail.load(context.tenant)
        val own = UiFieldCondition("smtpMode", listOf("Cuenta propia"))
        val imapOn = UiFieldCondition("imapEnabled", listOf("true"))
        val fields = listOf(
            UiField("smtpMode", "Enviar con", type = "select", options = listOf("SMTP del servidor", "Cuenta propia"), section = "Envio (SMTP)",
                help = if (context.serverMail == null) "El servidor no tiene SMTP configurado: usa una cuenta propia." else ""),
            UiField("smtpHost", "Servidor SMTP", visibleWhen = own, section = "Envio (SMTP)"),
            UiField("smtpPort", "Puerto", type = "number", visibleWhen = own, section = "Envio (SMTP)"),
            UiField("smtpSecurity", "Seguridad", type = "dropdown", options = listOf("starttls", "ssl", "none"), visibleWhen = own, section = "Envio (SMTP)"),
            UiField("smtpUsername", "Usuario", visibleWhen = own, section = "Envio (SMTP)"),
            UiField("smtpPassword", "Contrasena", type = "password", visibleWhen = own, section = "Envio (SMTP)"),
            UiField("smtpFrom", "Remitente", type = "email", visibleWhen = own, section = "Envio (SMTP)"),
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
        val sending = if (settings.smtp(context.serverMail) == null) UiStatus("No hay SMTP disponible: los correos no se pueden enviar", UiTone.Danger) else UiStatus("Envio de correo listo", UiTone.Success)
        return listOf(
            UiCard(listOf(sending, status)),
            UiCard(
                listOf(
                    UiForm(
                        "mail", fields, CrmMail.toFormValues(settings) + submitted.orEmpty(),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/mail", params = mapOf("op" to "save"), formId = "mail")),
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
        val variables = CrmMail.placeholders.entries.joinToString("\n") { (key, label) -> "{$key} - $label" }
        val values = editing?.let { mapOf("name" to it.name, "subject" to it.subject, "body" to it.body) }.orEmpty() + submitted.orEmpty()
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
                        ),
                        values,
                        submit = UiButton(if (editing == null) "Crear plantilla" else "Guardar plantilla", UiRequest(UiRoute + "/templates", params = saveParams, formId = "template")),
                    ),
                ),
                title = if (editing == null) "Nueva plantilla" else "Editar ${editing.name}",
                actions = listOfNotNull(editing?.let { UiButton("Nueva", UiNavigate("templates"), style = "secondary") }),
            ),
            UiCard(listOf(UiText(variables)), title = "Variables"),
        )
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

    fun team(context: CrmScreens.Context): List<UiNode> {
        val saved = CrmNotify.userSettings(context.tenant)
        val digestHour = context.tenant.prepareStatement("SELECT digest_hour FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 7 }
        }
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
                        ),
                        mapOf(
                            "email" to settings?.email.orEmpty(),
                            "notifyNewLeads" to (settings?.notifyNewLeads ?: false).toString(),
                            "notifyAssigned" to (settings?.notifyAssigned ?: true).toString(),
                            "dailyDigest" to (settings?.dailyDigest ?: false).toString(),
                        ),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/team", params = mapOf("op" to "user", "user" to user.id.toString()), formId = formId), style = "secondary"),
                        minFieldWidth = 220,
                    ),
                ),
                title = user.name,
            )
        }
        return listOf(
            UiCard(
                listOf(
                    UiForm(
                        "digest",
                        listOf(UiField("digestHour", "Hora del resumen diario (0-23)", type = "number")),
                        mapOf("digestHour" to digestHour.toString()),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/team", params = mapOf("op" to "digest"), formId = "digest"), style = "secondary"),
                    ),
                ),
                title = "Resumen diario",
            ),
            UiText("Usuarios que pueden tener leads (con algun permiso del CRM o duenos de la empresa).", UiTextStyle.Caption),
        ) + users.ifEmpty { listOf(UiText("Ningun usuario tiene permisos del CRM todavia.", tone = UiTone.Warning)) }
    }
}
