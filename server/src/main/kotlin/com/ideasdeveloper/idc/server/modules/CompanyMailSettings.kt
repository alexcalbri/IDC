package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretDecryptionException
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import kotlinx.serialization.Serializable
import java.sql.Connection

// Lo que ve el dueno de la empresa del correo saliente: nunca incluye la contrasena, solo si hay una guardada.
@Serializable
data class CompanyMailForm(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    val from: String = "",
    // "starttls", "ssl" o "none".
    val security: String = "starttls",
    val passwordSet: Boolean = false,
)

// Lo que envia el dueno. password: null conserva la guardada, "" la borra, otro valor la reemplaza.
@Serializable
data class CompanyMailInput(
    val host: String,
    val port: Int,
    val username: String,
    val from: String,
    val security: String,
    val password: String? = null,
)

// Resultado de leer el SMTP de la empresa: listo, sin configurar, o configurado pero ilegible (p. ej. llave maestra distinta).
sealed interface CompanyMailResult {
    data class Ok(val settings: SmtpSettings) : CompanyMailResult
    data object Unset : CompanyMailResult
    data class Broken(val reason: String, val cause: Throwable?) : CompanyMailResult
}

/**
 * SMTP de la empresa (tabla company_mail_settings de la base de cada empresa, tenant V007). Lo comparten los modulos
 * (CRM, Hotspot...) para enviar correo; la contrasena se sella con [SecretBox] y la validacion anti-SSRF se repite al
 * enviar en MailSender: este SMTP nunca es de confianza (trusted = false).
 */
object CompanyMail {
    val Securities = setOf("starttls", "ssl", "none")

    // Correo simple o "Nombre <a@b.c>"; sin espacios ni signos raros dentro de la direccion.
    private val addressPattern = Regex("[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]{2,}")
    private val fromPattern = Regex("(?:[^<>\"\\r\\n]*<${addressPattern.pattern}>|${addressPattern.pattern})")

    // Fila tal como esta en la base; la contrasena sigue sellada.
    data class Row(
        val host: String,
        val port: Int,
        val username: String,
        val passwordEnc: String?,
        val from: String,
        val security: String,
    )

    /** SMTP listo para enviar, o null si la empresa no ha configurado un servidor. */
    fun read(tenant: Connection, secrets: SecretBox): SmtpSettings? = toSettings(loadRow(tenant)) { secrets.open(tenant, it) }

    /** Como [read], pero distingue "sin configurar" de "existe pero no se pudo abrir la contrasena". */
    fun readOrNull(tenant: Connection, secrets: SecretBox): CompanyMailResult {
        val row = loadRow(tenant)
        if (row.host.isBlank()) return CompanyMailResult.Unset
        return try {
            toSettings(row) { secrets.open(tenant, it) }?.let { CompanyMailResult.Ok(it) } ?: CompanyMailResult.Unset
        } catch (failure: SecretsUnavailableException) {
            CompanyMailResult.Broken("No se puede leer la contrasena del correo: falta la llave maestra.", failure)
        } catch (failure: SecretDecryptionException) {
            CompanyMailResult.Broken("No se puede leer la contrasena del correo guardada (revisa IDC_MASTER_KEY).", failure)
        }
    }

    /** SMTP de la empresa si sirve; si no, el del servidor si sirve; si no, null. */
    fun resolveUsable(company: SmtpSettings?, server: SmtpSettings?): SmtpSettings? =
        company?.takeIf { it.isUsable } ?: server?.takeIf { it.isUsable }

    /** Datos para mostrar en pantalla; nunca la contrasena. */
    fun load(tenant: Connection): CompanyMailForm = toForm(loadRow(tenant))

    /** Guarda el SMTP (upsert de la fila 1). Llama antes a [validate]; aqui solo se persiste. */
    fun save(tenant: Connection, secrets: SecretBox, input: CompanyMailInput) {
        // Sin servidor (limpiar la configuracion) la contrasena tambien se borra, no queda huerfana.
        val clearing = input.host.isBlank()
        val touchPassword = clearing || input.password != null
        val sealed = if (clearing) null else sealedPassword(input.password) { secrets.seal(tenant, it) }
        val sql = if (touchPassword) {
            """
            INSERT INTO company_mail_settings (id, host, port, username, password_enc, from_address, security, updated_at)
            VALUES (1, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET host = EXCLUDED.host, port = EXCLUDED.port, username = EXCLUDED.username,
                password_enc = EXCLUDED.password_enc, from_address = EXCLUDED.from_address,
                security = EXCLUDED.security, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        } else {
            """
            INSERT INTO company_mail_settings (id, host, port, username, from_address, security, updated_at)
            VALUES (1, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET host = EXCLUDED.host, port = EXCLUDED.port, username = EXCLUDED.username,
                from_address = EXCLUDED.from_address, security = EXCLUDED.security, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        }
        tenant.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, input.host.trim())
            statement.setInt(index++, input.port)
            statement.setString(index++, input.username.trim())
            if (touchPassword) statement.setString(index++, sealed)
            statement.setString(index++, input.from.trim())
            statement.setString(index, input.security.trim().lowercase())
            statement.executeUpdate()
        }
    }

    /**
     * Reglas de lo que escribe el dueno; falla con [MailException] (mensaje en espanol). Sin servidor (host en blanco) se
     * acepta: limpia la configuracion. [resolve] se cambia en pruebas.
     */
    fun validate(
        input: CompanyMailInput,
        resolve: (String) -> List<java.net.InetAddress> = { name -> java.net.InetAddress.getAllByName(name).toList() },
    ) {
        if (input.port !in 1..65535) throw MailException("El puerto debe estar entre 1 y 65535.")
        if (input.security.trim().lowercase() !in Securities) throw MailException("La seguridad debe ser una de: starttls, ssl, none.")
        if (input.host.length > 255 || input.username.length > 255 || input.from.length > 255) throw MailException("Servidor, usuario y remitente admiten hasta 255 caracteres.")
        if (input.from.any { it == '\r' || it == '\n' } || input.username.any { it == '\r' || it == '\n' }) {
            throw MailException("El usuario y el remitente no pueden tener saltos de linea.")
        }
        if (input.password != null && input.password.startsWith(SecretBox.SealedPrefix)) throw MailException("La contrasena no puede empezar por ${SecretBox.SealedPrefix}.")
        // Sin servidor se limpia la configuracion; el remitente solo se exige con servidor.
        if (input.from.isNotBlank() && !fromPattern.matches(input.from.trim())) throw MailException("El remitente no es un correo valido.")
        if (input.host.isBlank()) return
        if (input.from.isBlank()) throw MailException("Escribe la direccion de correo desde la que se envia.")
        MailHostGuard.check(input.host.trim(), input.port, resolve = resolve)
    }

    // ---- Logica pura (sin base de datos) ----

    /** Contrasena a guardar: null o "" = ninguna (null en la base; null se trata aparte en [save] para no tocarla), otro = sellada con [seal]. */
    fun sealedPassword(password: String?, seal: (String) -> String): String? =
        if (password.isNullOrEmpty()) null else seal(password)

    fun toSettings(row: Row, open: (String) -> String): SmtpSettings? {
        if (row.host.isBlank()) return null
        val sealed = row.passwordEnc
        val password = if (sealed.isNullOrEmpty()) "" else open(sealed)
        return SmtpSettings(
            host = row.host,
            port = row.port,
            username = row.username,
            password = password,
            from = row.from,
            security = row.security,
            trusted = false,
        )
    }

    fun toForm(row: Row) = CompanyMailForm(
        host = row.host,
        port = row.port,
        username = row.username,
        from = row.from,
        security = row.security,
        passwordSet = !row.passwordEnc.isNullOrEmpty(),
    )

    private fun loadRow(tenant: Connection): Row =
        tenant.prepareStatement("SELECT host, port, username, password_enc, from_address, security FROM company_mail_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows ->
                if (!rows.next()) Row("", 587, "", null, "", "starttls")
                else Row(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6))
            }
        }
}
