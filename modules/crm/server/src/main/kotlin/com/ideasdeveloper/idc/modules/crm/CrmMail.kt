package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.SmtpSettings
import jakarta.mail.Folder
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import java.sql.Connection
import java.time.Instant
import java.util.Properties
import java.util.UUID

data class CrmMailSettings(
    val smtpMode: String,
    val smtpHost: String?,
    val smtpPort: Int,
    val smtpUsername: String?,
    val smtpPassword: String?,
    val smtpFrom: String?,
    val smtpSecurity: String,
    val imapEnabled: Boolean,
    val imapHost: String?,
    val imapPort: Int,
    val imapUsername: String?,
    val imapPassword: String?,
    val imapFolder: String,
    val imapSsl: Boolean,
    val imapUidValidity: Long?,
    val imapLastUid: Long,
    val imapCheckedAt: Instant?,
    val imapLastError: String,
) {
    // SMTP the CRM sends with: the server's or the company's own, null when none is usable.
    fun smtp(serverMail: SmtpSettings?): SmtpSettings? =
        if (smtpMode == "empresa") {
            SmtpSettings(smtpHost.orEmpty(), smtpPort, smtpUsername.orEmpty(), smtpPassword.orEmpty(), smtpFrom.orEmpty(), smtpSecurity)
        } else {
            serverMail
        }?.takeIf { it.isUsable }

    val imapReady get() = imapEnabled && !imapHost.isNullOrBlank() && !imapUsername.isNullOrBlank() && !imapPassword.isNullOrBlank()
}

data class CrmTemplate(val id: UUID, val name: String, val subject: String, val body: String)

// A rendered email: subject, HTML and the plain-text alternative.
data class CrmEmail(val subject: String, val html: String, val text: String)

object CrmMail {
    const val SecretMask = "********"

    // Placeholders every template can use; modules of the CRM (reservations...) add their own values.
    val placeholders = linkedMapOf(
        "nombre" to "Nombre del cliente",
        "correo" to "Correo del cliente",
        "telefono" to "Telefono del cliente",
        "codigo_cliente" to "Codigo del cliente (acceso al WiFi por codigo)",
        "responsable" to "Responsable del lead",
        "etapa" to "Etapa actual",
        "interes" to "Interes",
        "checkin" to "Fecha de inicio (entrada)",
        "checkout" to "Fecha de fin (salida)",
        "valor" to "Valor estimado",
        "habitaciones" to "Habitaciones reservadas",
        "reserva" to "Numero de reserva",
    )

    // ---- Settings ----

    fun load(tenant: Connection): CrmMailSettings = tenant.prepareStatement(
        """
        SELECT smtp_mode, smtp_host, smtp_port, smtp_username, smtp_password, smtp_from, smtp_security,
               imap_enabled, imap_host, imap_port, imap_username, imap_password, imap_folder, imap_ssl,
               imap_uid_validity, imap_last_uid, imap_checked_at, imap_last_error
        FROM crm_mail_settings WHERE id = 1
        """.trimIndent()
    ).use { query ->
        query.executeQuery().use { rows ->
            rows.next()
            CrmMailSettings(
                rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7),
                rows.getBoolean(8), rows.getString(9), rows.getInt(10), rows.getString(11), rows.getString(12), rows.getString(13), rows.getBoolean(14),
                rows.getObject(15) as Long?, rows.getLong(16), rows.getTimestamp(17)?.toInstant(), rows.getString(18),
            )
        }
    }

    fun toFormValues(settings: CrmMailSettings): Map<String, String> = mapOf(
        "smtpMode" to if (settings.smtpMode == "empresa") "Cuenta propia" else "SMTP del servidor",
        "smtpHost" to settings.smtpHost.orEmpty(),
        "smtpPort" to settings.smtpPort.toString(),
        "smtpUsername" to settings.smtpUsername.orEmpty(),
        "smtpPassword" to masked(settings.smtpPassword),
        "smtpFrom" to settings.smtpFrom.orEmpty(),
        "smtpSecurity" to settings.smtpSecurity,
        "imapEnabled" to settings.imapEnabled.toString(),
        "imapHost" to settings.imapHost.orEmpty(),
        "imapPort" to settings.imapPort.toString(),
        "imapUsername" to settings.imapUsername.orEmpty(),
        "imapPassword" to masked(settings.imapPassword),
        "imapFolder" to settings.imapFolder,
        "imapSsl" to settings.imapSsl.toString(),
    )

    private fun masked(secret: String?) = if (secret.isNullOrBlank()) "" else SecretMask

    /** Applies the mail form; a blank or masked password keeps the saved one. */
    fun merge(current: CrmMailSettings, values: Map<String, String>): CrmMailSettings {
        fun text(key: String) = values[key]?.trim()?.takeIf { it.isNotEmpty() }
        fun secret(key: String, saved: String?) = values[key]?.takeIf { it.isNotBlank() && it != SecretMask } ?: saved
        fun port(key: String, label: String) = values[key].orEmpty().trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw CrmValidationException("$label debe ser un numero entre 1 y 65535.")
        val security = values["smtpSecurity"].orEmpty().ifBlank { current.smtpSecurity }
        if (security !in setOf("starttls", "ssl", "none")) throw CrmValidationException("La seguridad SMTP debe ser starttls, ssl o none.")
        val merged = current.copy(
            smtpMode = if (values["smtpMode"] == "Cuenta propia") "empresa" else "servidor",
            smtpHost = text("smtpHost"),
            smtpPort = port("smtpPort", "El puerto SMTP"),
            smtpUsername = text("smtpUsername"),
            smtpPassword = secret("smtpPassword", current.smtpPassword),
            smtpFrom = text("smtpFrom"),
            smtpSecurity = security,
            imapEnabled = values["imapEnabled"] == "true",
            imapHost = text("imapHost"),
            imapPort = port("imapPort", "El puerto IMAP"),
            imapUsername = text("imapUsername"),
            imapPassword = secret("imapPassword", current.imapPassword),
            imapFolder = text("imapFolder") ?: "INBOX",
            imapSsl = values["imapSsl"] == "true",
        )
        merged.smtpFrom?.let { if (!CrmContacts.isEmail(it)) throw CrmValidationException("El remitente debe ser un correo valido.") }
        if (merged.smtpMode == "empresa" && (merged.smtpHost == null || merged.smtpFrom == null)) {
            throw CrmValidationException("Con cuenta propia escribe el servidor SMTP y el remitente.")
        }
        if (merged.imapEnabled && (merged.imapHost == null || merged.imapUsername == null || merged.imapPassword.isNullOrBlank())) {
            throw CrmValidationException("Para leer el correo escribe el servidor IMAP, el usuario y la contrasena.")
        }
        // Another mailbox starts from its newest message instead of importing the whole inbox.
        val sameMailbox = merged.imapHost == current.imapHost && merged.imapUsername == current.imapUsername && merged.imapFolder == current.imapFolder
        return if (sameMailbox) merged else merged.copy(imapUidValidity = null, imapLastUid = 0)
    }

    fun save(tenant: Connection, settings: CrmMailSettings) {
        tenant.prepareStatement(
            """
            UPDATE crm_mail_settings SET smtp_mode = ?, smtp_host = ?, smtp_port = ?, smtp_username = ?, smtp_password = ?, smtp_from = ?,
                smtp_security = ?, imap_enabled = ?, imap_host = ?, imap_port = ?, imap_username = ?, imap_password = ?, imap_folder = ?,
                imap_ssl = ?, imap_uid_validity = ?, imap_last_uid = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = 1
            """.trimIndent()
        ).use { update ->
            update.setString(1, settings.smtpMode)
            update.setString(2, settings.smtpHost)
            update.setInt(3, settings.smtpPort)
            update.setString(4, settings.smtpUsername)
            update.setString(5, settings.smtpPassword)
            update.setString(6, settings.smtpFrom)
            update.setString(7, settings.smtpSecurity)
            update.setBoolean(8, settings.imapEnabled)
            update.setString(9, settings.imapHost)
            update.setInt(10, settings.imapPort)
            update.setString(11, settings.imapUsername)
            update.setString(12, settings.imapPassword)
            update.setString(13, settings.imapFolder)
            update.setBoolean(14, settings.imapSsl)
            update.setObject(15, settings.imapUidValidity)
            update.setLong(16, settings.imapLastUid)
            update.executeUpdate()
        }
    }

    // ---- Templates ----

    fun templates(tenant: Connection): List<CrmTemplate> =
        tenant.prepareStatement("SELECT id, name, subject, body FROM crm_email_templates ORDER BY name").use { query ->
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(CrmTemplate(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getString(4))) }
            }
        }

    fun saveTemplate(tenant: Connection, id: UUID?, values: Map<String, String>): String {
        val name = values["name"].orEmpty().trim()
        val subject = values["subject"].orEmpty().trim()
        val body = values["body"].orEmpty()
        if (name.isEmpty() || name.length > 80) throw CrmValidationException("El nombre de la plantilla debe tener entre 1 y 80 caracteres.")
        if (subject.isEmpty() || subject.length > 300) throw CrmValidationException("El asunto debe tener entre 1 y 300 caracteres.")
        if (body.isBlank() || body.length > 50000) throw CrmValidationException("Escribe el contenido del correo (hasta 50000 caracteres).")
        unknownPlaceholders(subject + body).takeIf { it.isNotEmpty() }?.let {
            throw CrmValidationException("Variables desconocidas: ${it.joinToString { name -> "{$name}" }}.")
        }
        if (templates(tenant).any { it.name.equals(name, ignoreCase = true) && it.id != id }) throw CrmValidationException("Ya existe la plantilla $name.")
        val sql = if (id == null) {
            "INSERT INTO crm_email_templates (name, subject, body, id) VALUES (?, ?, ?, ?)"
        } else {
            "UPDATE crm_email_templates SET name = ?, subject = ?, body = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
        }
        tenant.prepareStatement(sql).use { statement ->
            statement.setString(1, name)
            statement.setString(2, subject)
            statement.setString(3, body)
            statement.setObject(4, id ?: UUID.randomUUID())
            if (statement.executeUpdate() != 1) throw CrmValidationException("Esa plantilla ya no existe.")
        }
        return name
    }

    fun deleteTemplate(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("DELETE FROM crm_email_templates WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() == 1 }

    private val placeholder = Regex("\\{([a-z_]+)\\}")

    fun unknownPlaceholders(text: String): Set<String> =
        placeholder.findAll(text).map { it.groupValues[1] }.filter { it !in placeholders }.toSet()

    /**
     * Fills a template. [body] is HTML written by the company; a body without tags is treated as
     * plain text (escaped, line breaks kept). Values are always HTML-escaped in the body; unknown
     * or missing values become empty.
     */
    fun render(subject: String, body: String, values: Map<String, String>): CrmEmail {
        val filledSubject = placeholder.replace(subject) { values[it.groupValues[1]].orEmpty() }.replace(Regex("[\\r\\n]+"), " ").trim()
        val isHtml = Regex("<[a-zA-Z][^>]*>").containsMatchIn(body)
        val htmlBody = if (isHtml) {
            placeholder.replace(body) { escape(values[it.groupValues[1]].orEmpty()) }
        } else {
            textToHtml(placeholder.replace(body) { values[it.groupValues[1]].orEmpty() })
        }
        return CrmEmail(filledSubject, layout(htmlBody), htmlToText(htmlBody))
    }

    // A message typed by a user: plain text.
    fun plain(subject: String, text: String) = CrmEmail(subject.trim(), layout(textToHtml(text)), text.trim())

    fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    fun textToHtml(text: String): String =
        text.trim().split(Regex("\\n\\s*\\n")).joinToString("") { paragraph -> "<p>${escape(paragraph.trim()).replace("\n", "<br>")}</p>" }

    fun htmlToText(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun layout(content: String) = """
        <!doctype html><html><body style="margin:0;padding:24px;background:#f4f5f7;font-family:Arial,Helvetica,sans-serif;color:#1f2328;">
        <div style="max-width:600px;margin:0 auto;background:#ffffff;border-radius:12px;padding:28px;line-height:1.5;font-size:15px;">$content</div>
        </body></html>
    """.trimIndent()

    // ---- IMAP ----

    data class InboundEmail(val fromEmail: String, val fromName: String, val subject: String, val text: String, val messageId: String?, val sentAt: Instant?)

    /**
     * Reads up to [limit] new messages of the mailbox, oldest first, and hands each one to [handle].
     * The first read of a mailbox only records where it stands, so old mail is not imported.
     * Returns the new UIDVALIDITY and last UID to save.
     */
    fun poll(settings: CrmMailSettings, limit: Int, handle: (InboundEmail) -> Unit): Pair<Long, Long> {
        val properties = Properties().apply {
            put("mail.imap.connectiontimeout", "10000")
            put("mail.imap.timeout", "20000")
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.timeout", "20000")
            put("mail.imaps.ssl.checkserveridentity", "true")
        }
        val store = Session.getInstance(properties).getStore(if (settings.imapSsl) "imaps" else "imap")
        store.connect(settings.imapHost, settings.imapPort, settings.imapUsername, settings.imapPassword)
        try {
            val folder = store.getFolder(settings.imapFolder)
            folder.open(Folder.READ_ONLY)
            try {
                val uids = folder as UIDFolder
                val validity = uids.uidValidity
                if (settings.imapUidValidity != validity) {
                    // New mailbox (or the server renumbered it): continue from its newest message.
                    return validity to (uids.uidNext - 1).coerceAtLeast(0)
                }
                val own = setOfNotNull(settings.imapUsername?.lowercase(), settings.smtpFrom?.lowercase())
                var last = settings.imapLastUid
                uids.getMessagesByUID(settings.imapLastUid + 1, UIDFolder.LASTUID)
                    .filter { uids.getUID(it) > settings.imapLastUid }
                    .take(limit)
                    .forEach { message ->
                        val uid = uids.getUID(message)
                        val from = (message.from?.firstOrNull() as? InternetAddress)
                        val email = from?.address?.lowercase().orEmpty()
                        if (email.isNotEmpty() && email !in own) {
                            handle(
                                InboundEmail(
                                    fromEmail = email,
                                    fromName = from?.personal?.trim().orEmpty(),
                                    subject = message.subject.orEmpty().take(300),
                                    text = bodyText(message).take(20000),
                                    messageId = message.getHeader("Message-ID")?.firstOrNull()?.take(300),
                                    sentAt = message.sentDate?.toInstant(),
                                ),
                            )
                        }
                        last = uid
                    }
                return validity to last
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }

    fun recordPoll(tenant: Connection, validity: Long?, lastUid: Long?, error: String) {
        tenant.prepareStatement(
            """
            UPDATE crm_mail_settings SET imap_uid_validity = COALESCE(?, imap_uid_validity), imap_last_uid = COALESCE(?, imap_last_uid),
                imap_checked_at = CURRENT_TIMESTAMP, imap_last_error = ?
            WHERE id = 1
            """.trimIndent()
        ).use { update ->
            update.setObject(1, validity)
            update.setObject(2, lastUid)
            update.setString(3, error.take(500))
            update.executeUpdate()
        }
    }

    // Plain text of a message: its text/plain part, or the HTML part without tags.
    private fun bodyText(part: Part): String {
        if (part.isMimeType("text/plain")) return part.content.toString()
        if (part.isMimeType("text/html")) return htmlToText(part.content.toString())
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as Multipart
            val parts = (0 until multipart.count).map { multipart.getBodyPart(it) }
            parts.firstOrNull { it.isMimeType("text/plain") && it.disposition == null }?.let { return it.content.toString() }
            parts.forEach { child -> bodyText(child).takeIf { it.isNotBlank() }?.let { return it } }
        }
        return ""
    }

    // Quoted history of a reply is cut so the conversation shows only the new text.
    fun withoutQuote(text: String): String {
        val lines = text.lines()
        val cut = lines.indexOfFirst { line ->
            line.startsWith(">") || Regex("^(On|El) .+(wrote|escribi[oó]):\\s*$").matches(line.trim()) || line.trim() == "-----Original Message-----"
        }
        return (if (cut > 0) lines.take(cut) else lines).joinToString("\n").trim()
    }
}
