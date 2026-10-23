package com.ideasdeveloper.idc.server.modules

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

// SMTP connection used by modules that send email (server default or a company override).
data class SmtpSettings(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val from: String,
    // "starttls", "ssl" or "none".
    val security: String = "starttls",
) {
    val isUsable: Boolean
        get() = host.isNotBlank() && port in 1..65535 && from.isNotBlank()
}

class MailException(message: String, cause: Throwable? = null) : Exception(message, cause)

object MailSender {
    fun sendText(settings: SmtpSettings, to: String, subject: String, body: String) {
        if (!settings.isUsable) throw MailException("El correo SMTP no esta configurado.")
        val properties = Properties().apply {
            put("mail.smtp.host", settings.host)
            put("mail.smtp.port", settings.port.toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "15000")
            put("mail.smtp.auth", settings.username.isNotBlank().toString())
            when (settings.security) {
                "ssl" -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
                "none" -> Unit
                else -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
            }
        }
        val authenticator = settings.username.takeIf { it.isNotBlank() }?.let {
            object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(settings.username, settings.password)
            }
        }
        try {
            val message = MimeMessage(Session.getInstance(properties, authenticator)).apply {
                setFrom(InternetAddress(settings.from, true))
                setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
                setSubject(subject, Charsets.UTF_8.name())
                setText(body, Charsets.UTF_8.name())
            }
            Transport.send(message)
        } catch (failure: Exception) {
            throw MailException("No se pudo enviar el correo: ${failure.message}", failure)
        }
    }
}
