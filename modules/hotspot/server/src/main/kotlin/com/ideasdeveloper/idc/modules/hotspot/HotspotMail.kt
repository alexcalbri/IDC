package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.CompanyMail
import com.ideasdeveloper.idc.server.modules.CompanyMailInput
import com.ideasdeveloper.idc.server.modules.CompanyMailResult
import com.ideasdeveloper.idc.server.modules.SmtpSettings
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import java.sql.Connection
import java.sql.SQLException

/**
 * Correo del hotspot (codigos de acceso). Hotspot no tiene SMTP propio: usa el de la empresa (company_mail_settings) y,
 * si la empresa no tiene, el del servidor.
 */
object HotspotMail {
    private val log = org.slf4j.LoggerFactory.getLogger(HotspotMail::class.java)

    // Empresas cuyo SMTP roto ya se aviso: se llama en cada codigo enviado y cada pantalla, el aviso sale una vez (luego debug).
    private val brokenWarned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * SMTP de la empresa. null si no hay uno guardado o esta roto (falta IDC_MASTER_KEY o la contrasena no se puede descifrar):
     * en ese caso se avisa en el log y quien llama cae al SMTP del servidor.
     */
    fun companySmtp(tenant: Connection, secrets: SecretBox?): SmtpSettings? {
        val company = runCatching { tenant.catalog }.getOrNull() ?: "?"
        if (secrets == null) {
            warnBroken(company, "no hay caja de cifrado", null)
            return null
        }
        return when (val result = CompanyMail.readOrNull(tenant, secrets)) {
            is CompanyMailResult.Ok -> result.settings
            is CompanyMailResult.Unset -> null
            is CompanyMailResult.Broken -> {
                warnBroken(company, result.reason, result.cause)
                null
            }
        }
    }

    private fun warnBroken(company: String, reason: String, cause: Throwable?) {
        if (!brokenWarned.add(company)) {
            log.debug("Hotspot {}: el SMTP de la empresa sigue roto ({}); se usa el del servidor.", company, reason)
            return
        }
        if (cause == null || cause is SecretsUnavailableException) {
            log.warn("Hotspot {}: el SMTP de la empresa no se puede usar ({}); se usa el del servidor.", company, reason)
        } else {
            log.error("Hotspot {}: el SMTP de la empresa no se puede descifrar ({}); se usa el del servidor.", company, reason, cause)
        }
    }

    /** El SMTP de la empresa y, si esta incompleto o no hay, el del servidor; null cuando ninguno sirve. */
    fun resolveSmtp(company: SmtpSettings?, serverMail: SmtpSettings?): SmtpSettings? = CompanyMail.resolveUsable(company, serverMail)

    fun smtp(tenant: Connection, serverMail: SmtpSettings?, secrets: SecretBox?): SmtpSettings? =
        resolveSmtp(companySmtp(tenant, secrets), serverMail)
}

/**
 * Migracion de una sola vez del SMTP propio de Hotspot al SMTP de la empresa. Solo siembra si la empresa aun no tiene SMTP
 * (ni el del dueno ni el que el CRM sembro antes: su job corre primero). La contrasena de Hotspot estaba en claro: se sella al
 * guardarla en la empresa. Despues las columnas smtp_* de hotspot_settings se limpian y no se vuelve a migrar.
 */
object HotspotMailMigration {
    private const val MaxText = 255
    private val securities = setOf("starttls", "ssl", "none")

    data class Legacy(
        val mode: String,
        val host: String?,
        val port: Int,
        val username: String?,
        // En claro: Hotspot no la cifraba.
        val password: String?,
        val from: String?,
        val security: String,
    ) {
        val isClean: Boolean
            get() = mode != "empresa" && host.isNullOrBlank() && username.isNullOrBlank() && from.isNullOrBlank() && password.isNullOrEmpty()
    }

    sealed interface Plan {
        /** No habia SMTP propio: solo se limpia lo que quede. */
        data object Skip : Plan

        /** La empresa ya tiene SMTP (el del CRM o uno propio): se conserva y el de Hotspot se descarta. */
        data object CompanyHasMail : Plan

        /** Falta la llave maestra para sellar la contrasena: se reintenta despues. */
        data class Wait(val reason: String) : Plan

        data class Copy(val input: CompanyMailInput) : Plan
    }

    enum class Outcome { Absent, Skipped, KeptCompany, Waiting, Migrated, Failed }

    /** Que paso y un detalle para el registro (nunca la contrasena). */
    data class Result(val outcome: Outcome, val detail: String? = null)

    /** Datos legados validos para company_mail_settings: seguridad conocida y textos acotados. */
    fun normalized(legacy: Legacy): Legacy = legacy.copy(
        host = legacy.host?.trim()?.take(MaxText),
        username = legacy.username?.trim()?.take(MaxText),
        from = legacy.from?.trim()?.take(MaxText),
        security = legacy.security.trim().lowercase().takeIf { it in securities } ?: "starttls",
        port = legacy.port.takeIf { it in 1..65535 } ?: 587,
    )

    fun plan(legacy: Legacy, companyHasHost: Boolean, secretsAvailable: Boolean): Plan {
        if (legacy.mode != "empresa" || legacy.host.isNullOrBlank()) return Plan.Skip
        if (companyHasHost) return Plan.CompanyHasMail
        val data = normalized(legacy)
        val password = data.password.orEmpty()
        // Sin llave no se puede sellar: no se deja la contrasena sin cifrar en company_mail_settings.
        if (password.isNotEmpty() && !secretsAvailable) return Plan.Wait("falta IDC_MASTER_KEY para sellar la contrasena")
        return Plan.Copy(
            CompanyMailInput(
                host = data.host.orEmpty(),
                port = data.port,
                username = data.username.orEmpty(),
                from = data.from.orEmpty(),
                security = data.security,
                password = password,
            ),
        )
    }

    /** Una pasada en una empresa; idempotente. */
    fun run(tenant: Connection, secrets: SecretBox): Result {
        if (!hasLegacyColumns(tenant)) return Result(Outcome.Absent)
        val legacy = readLegacy(tenant) ?: return Result(Outcome.Absent)
        if (legacy.isClean) return Result(Outcome.Absent)
        val companyHasHost = CompanyMail.load(tenant).host.isNotBlank()
        return when (val plan = plan(legacy, companyHasHost, secrets.available)) {
            is Plan.Wait -> Result(Outcome.Waiting, plan.reason)
            is Plan.Skip -> {
                clear(tenant)
                Result(Outcome.Skipped, discarded(legacy))
            }
            is Plan.CompanyHasMail -> {
                clear(tenant)
                Result(Outcome.KeptCompany, discarded(legacy))
            }
            is Plan.Copy -> try {
                CompanyMail.save(tenant, secrets, plan.input)
                clear(tenant)
                Result(Outcome.Migrated, "host ${plan.input.host}")
            } catch (failure: SecretsUnavailableException) {
                Result(Outcome.Waiting, "falta IDC_MASTER_KEY")
            } catch (failure: SQLException) {
                // Un dato legado que la tabla rechaza (CHECK, longitud) no se arregla reintentando: se limpia para no repetirlo cada 5 minutos.
                clear(tenant)
                Result(Outcome.Failed, "no se pudo copiar el SMTP (${failure::class.simpleName}: ${failure.message}); se descarta el de Hotspot")
            }
        }
    }

    // Que se descarta, sin la contrasena.
    private fun discarded(legacy: Legacy) = "se descartan smtp_mode=${legacy.mode}, smtp_host=${legacy.host.orEmpty()}, smtp_username=${legacy.username.orEmpty()}, smtp_from=${legacy.from.orEmpty()}"

    fun hasLegacyColumns(tenant: Connection): Boolean = tenant.prepareStatement(
        "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'hotspot_settings' AND column_name = 'smtp_mode'"
    ).use { query -> query.executeQuery().use { it.next() } }

    private fun readLegacy(tenant: Connection): Legacy? = tenant.prepareStatement(
        "SELECT smtp_mode, smtp_host, smtp_port, smtp_username, smtp_password, smtp_from, smtp_security FROM hotspot_settings WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            if (!rows.next()) null
            else Legacy(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7))
        }
    }

    private fun clear(tenant: Connection) {
        tenant.prepareStatement(
            "UPDATE hotspot_settings SET smtp_mode = 'servidor', smtp_host = NULL, smtp_username = NULL, smtp_password = NULL, smtp_from = NULL WHERE id = 1"
        ).use { it.executeUpdate() }
    }
}
