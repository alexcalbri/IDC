package com.ideasdeveloper.idc.server.modules

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import com.ideasdeveloper.idc.server.net.PinnedHost
import com.ideasdeveloper.idc.server.net.PinnedHostException
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
    // true solo para el SMTP del servidor (lo configura el dueno): puede ser un relay interno. El de una empresa nunca.
    val trusted: Boolean = false,
) {
    val isUsable: Boolean
        get() = host.isNotBlank() && port in 1..65535 && from.isNotBlank()
}

class MailException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Validacion de los servidores de correo (SMTP/IMAP) que escribe el usuario: un host que apunte a la red interna
 * permitiria usar el servidor para escanear o atacar servicios locales (SSRF).
 */
object MailHostGuard {
    // Puertos de correo permitidos: SMTP (25, 465, 587) e IMAP (143, 993).
    val AllowedPorts = setOf(25, 465, 587, 993, 143)

    /** True para direcciones no publicas: loopback, enlace local/metadata, RFC1918, CGNAT 100.64/10, IPv6 ULA fc00::/7, multicast y 0.0.0.0/8. */
    fun blockedAddress(address: java.net.InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        return when (bytes.size) {
            4 -> blockedIpv4(bytes)
            16 -> {
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
                // NAT64 64:ff9b::/96 (IPv4 en los ultimos 4 bytes) y 6to4 2002::/16 (IPv4 en los bytes 2 a 5): se revalida el IPv4 incrustado.
                val nat64 = bytes[0].toInt() == 0x00 && bytes[1].toInt() == 0x64 && (bytes[2].toInt() and 0xFF) == 0xFF && (bytes[3].toInt() and 0xFF) == 0x9B &&
                    (4..11).all { bytes[it].toInt() == 0 }
                val sixToFour = (bytes[0].toInt() and 0xFF) == 0x20 && bytes[1].toInt() == 0x02
                when {
                    nat64 -> blockedAddress(java.net.InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
                    sixToFour -> blockedAddress(java.net.InetAddress.getByAddress(bytes.copyOfRange(2, 6)))
                    else -> false
                }
            }
            else -> true
        }
    }

    // Rangos IPv4 no publicos que Java no marca: 0/8, CGNAT, 192.0.0/24, documentacion (TEST-NET), pruebas 198.18/15 y reservado 240/4 (incluye 255.255.255.255).
    private fun blockedIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        val c = bytes[2].toInt() and 0xFF
        return a == 0 || (a == 100 && (b and 0xC0) == 64) ||
            (a == 192 && b == 0 && (c == 0 || c == 2)) ||
            (a == 198 && ((b and 0xFE) == 18 || (b == 51 && c == 100))) ||
            (a == 203 && b == 0 && c == 113) ||
            a >= 240
    }

    /**
     * Falla con [MailException] si el puerto no es de correo o el host resuelve a alguna direccion no publica.
     * Con [allowPrivate] (SMTP del servidor, relay interno legitimo) solo se revisa el puerto. [resolve] se cambia en pruebas.
     */
    fun check(
        host: String,
        port: Int,
        allowPrivate: Boolean = false,
        resolve: (String) -> List<java.net.InetAddress> = { name -> java.net.InetAddress.getAllByName(name).toList() },
    ) {
        if (port !in AllowedPorts) throw MailException("El puerto debe ser uno de: ${AllowedPorts.sorted().joinToString(", ")}.")
        val name = host.trim()
        if (name.isEmpty()) throw MailException("Escribe el servidor de correo.")
        if (allowPrivate) return
        val addresses = try {
            resolve(name)
        } catch (failure: Exception) {
            throw MailException("No se encontro el servidor de correo $name.")
        }
        if (addresses.isEmpty() || addresses.any(::blockedAddress)) throw MailException("El servidor de correo apunta a una red privada o local: no se permite.")
    }
}

object MailSender {
    fun sendText(settings: SmtpSettings, to: String, subject: String, body: String, allowPrivate: Boolean = false) =
        send(settings, to, subject, allowPrivate) { setText(body, Charsets.UTF_8.name()) }

    /**
     * Sends an HTML email with a plain-text alternative for clients that do not show HTML. Callers
     * build [html] themselves and must escape any value that comes from users or customers.
     */
    fun sendHtml(settings: SmtpSettings, to: String, subject: String, html: String, text: String, allowPrivate: Boolean = false) =
        send(settings, to, subject, allowPrivate) { setContent(htmlBody(html, text)) }

    // multipart/alternative with the text first: clients show the last part they understand.
    internal fun htmlBody(html: String, text: String) = MimeMultipart("alternative").apply {
        addBodyPart(MimeBodyPart().apply { setText(text, Charsets.UTF_8.name()) })
        addBodyPart(MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") })
    }

    private fun send(settings: SmtpSettings, to: String, subject: String, allowPrivate: Boolean, body: MimeMessage.() -> Unit) {
        if (!settings.isUsable) throw MailException("El correo SMTP no esta configurado.")
        // Se valida justo antes de conectar (el nombre pudo cambiar de destino desde que se guardo).
        // El SMTP del servidor (settings.trusted) o un llamador de confianza puede usar un relay interno.
        val trusted = allowPrivate || settings.trusted
        MailHostGuard.check(settings.host, settings.port, allowPrivate = trusted)
        // Se resuelve una sola vez y la conexion va a esa IP: JavaMail no vuelve a resolver el nombre (anti DNS rebinding).
        val validated = try {
            PinnedHost.resolveValidated(settings.host, trusted)
        } catch (failure: PinnedHostException) {
            throw MailException(failure.message.orEmpty(), failure)
        }
        try {
            val message = MimeMessage(session(settings, validated)).apply {
                setFrom(InternetAddress(settings.from, true))
                setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
                setSubject(subject, Charsets.UTF_8.name())
                body()
            }
            Transport.send(message)
        } catch (failure: Exception) {
            // Mensaje generico: el detalle de la conexion no se muestra al usuario (queda en la causa).
            throw MailException("No se pudo enviar el correo. Revisa la configuracion SMTP.", failure)
        }
    }

    private fun session(settings: SmtpSettings, validated: java.net.InetAddress): Session {
        val properties = Properties().apply {
            put("mail.smtp.host", settings.host)
            // El nombre sigue siendo el original (EHLO, certificado), pero el TCP va a la IP ya validada.
            put("mail.smtp.socketFactory", PinnedHost.pinnedSocketFactory(validated))
            put("mail.smtp.ssl.socketFactory", PinnedHost.pinnedSslSocketFactory(validated, settings.host))
            put("mail.smtps.socketFactory", PinnedHost.pinnedSocketFactory(validated))
            put("mail.smtps.ssl.socketFactory", PinnedHost.pinnedSslSocketFactory(validated, settings.host))
            put("mail.smtps.ssl.checkserveridentity", "true")
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
        return Session.getInstance(properties, authenticator)
    }
}
