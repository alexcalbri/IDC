package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.CompanyMail
import com.ideasdeveloper.idc.server.modules.CompanyMailInput
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretDecryptionException
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import java.sql.Connection
import java.sql.SQLException

/**
 * Migracion de una sola vez del SMTP propio del CRM al SMTP de la empresa (company_mail_settings). El CRM ya no tiene
 * SMTP propio: lo que una empresa tenia configurado ("Cuenta propia") se copia al de la empresa y las columnas smtp_* de
 * crm_mail_settings se limpian (smtp_mode vuelve a 'servidor'), con lo que la migracion no se repite.
 *
 * Solo siembra si la empresa aun no tiene SMTP: nunca pisa lo que el dueno configuro. El job del CRM corre antes que el de
 * Hotspot (un solo hilo), asi que el CRM gana sobre Hotspot. Las columnas siguen existiendo vacias: el usuario de ejecucion
 * no puede borrarlas, eso queda para una migracion SQL posterior.
 */
object CrmMailMigration {
    private const val MaxText = 255
    private val securities = setOf("starttls", "ssl", "none")

    /** SMTP propio del CRM tal como estaba en la base; la contrasena puede seguir sellada. */
    data class Legacy(
        val mode: String,
        val host: String?,
        val port: Int,
        val username: String?,
        val password: String?,
        val from: String?,
        val security: String,
    ) {
        // Ya limpio: no queda nada que migrar ni que borrar.
        val isClean: Boolean
            get() = mode != "empresa" && host.isNullOrBlank() && username.isNullOrBlank() && from.isNullOrBlank() && password.isNullOrEmpty()
    }

    sealed interface Plan {
        /** No habia SMTP propio: solo se limpia lo que quede. */
        data object Skip : Plan

        /** La empresa ya tiene SMTP (del dueno o sembrado antes): se conserva y el del CRM se descarta. */
        data object KeptCompany : Plan

        /** Falta la llave maestra o la contrasena no se puede abrir: se reintenta despues, sin tocar nada. */
        data class Wait(val reason: String) : Plan

        /** Copiar este SMTP a la empresa. */
        data class Copy(val input: CompanyMailInput) : Plan
    }

    enum class Outcome { Absent, Skipped, KeptCompany, Waiting, Migrated, Failed }

    /** Resultado de una pasada: que paso y un detalle para el registro (host sembrado, motivo de la espera o del fallo). Nunca la contrasena. */
    data class Result(val outcome: Outcome, val detail: String? = null)

    /** Datos legados validos para company_mail_settings: seguridad conocida y textos acotados. */
    fun normalized(legacy: Legacy): Legacy = legacy.copy(
        host = legacy.host?.trim()?.take(MaxText),
        username = legacy.username?.trim()?.take(MaxText),
        from = legacy.from?.trim()?.take(MaxText),
        security = legacy.security.trim().lowercase().takeIf { it in securities } ?: "starttls",
        port = legacy.port.takeIf { it in 1..65535 } ?: 587,
    )

    /**
     * Decide que hacer con el SMTP propio. [open] abre una contrasena sellada con la llave de la empresa. La contrasena de la
     * empresa se sella al guardar ([CompanyMail.save]); aqui solo se obtiene en claro, y sin llave maestra no se migra una que exista.
     */
    fun plan(legacy: Legacy, companyHasHost: Boolean, secretsAvailable: Boolean, open: (String) -> String): Plan {
        if (legacy.mode != "empresa" || legacy.host.isNullOrBlank()) return Plan.Skip
        if (companyHasHost) return Plan.KeptCompany
        val data = normalized(legacy)
        val raw = data.password.orEmpty()
        val plain = when {
            raw.isEmpty() -> ""
            // Con llave se abre (o se sella el claro mas adelante); sin ella no se puede dejar la contrasena segura.
            !secretsAvailable -> return Plan.Wait("falta IDC_MASTER_KEY")
            raw.startsWith(SecretBox.SealedPrefix) -> try {
                open(raw)
            } catch (failure: SecretDecryptionException) {
                return Plan.Wait("la contrasena guardada no se puede abrir (${failure::class.simpleName}: ${failure.message})")
            } catch (failure: SecretsUnavailableException) {
                return Plan.Wait("falta IDC_MASTER_KEY (${failure::class.simpleName}: ${failure.message})")
            }
            else -> raw
        }
        return Plan.Copy(
            CompanyMailInput(
                host = data.host.orEmpty(),
                port = data.port,
                username = data.username.orEmpty(),
                from = data.from.orEmpty(),
                security = data.security,
                // "" = sin contrasena.
                password = plain,
            ),
        )
    }

    /** Una pasada en una empresa; idempotente. */
    fun run(tenant: Connection, secrets: SecretBox): Result {
        if (!hasLegacyColumns(tenant)) return Result(Outcome.Absent)
        val legacy = readLegacy(tenant) ?: return Result(Outcome.Absent)
        if (legacy.isClean) return Result(Outcome.Absent)
        val companyHasHost = CompanyMail.load(tenant).host.isNotBlank()
        return when (val plan = plan(legacy, companyHasHost, secrets.available) { secrets.open(tenant, it) }) {
            is Plan.Wait -> Result(Outcome.Waiting, plan.reason)
            is Plan.Skip -> {
                clear(tenant)
                Result(Outcome.Skipped, discarded(legacy))
            }
            is Plan.KeptCompany -> {
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
                Result(Outcome.Failed, "no se pudo copiar el SMTP (${failure::class.simpleName}: ${failure.message}); se descarta el del CRM")
            }
        }
    }

    // Que se descarta, sin la contrasena.
    private fun discarded(legacy: Legacy) = "se descartan smtp_mode=${legacy.mode}, smtp_host=${legacy.host.orEmpty()}, smtp_username=${legacy.username.orEmpty()}, smtp_from=${legacy.from.orEmpty()}"

    fun hasLegacyColumns(tenant: Connection): Boolean = tenant.prepareStatement(
        "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'crm_mail_settings' AND column_name = 'smtp_mode'"
    ).use { query -> query.executeQuery().use { it.next() } }

    private fun readLegacy(tenant: Connection): Legacy? = tenant.prepareStatement(
        "SELECT smtp_mode, smtp_host, smtp_port, smtp_username, smtp_password, smtp_from, smtp_security FROM crm_mail_settings WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            if (!rows.next()) null
            else Legacy(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7))
        }
    }

    private fun clear(tenant: Connection) {
        tenant.prepareStatement(
            "UPDATE crm_mail_settings SET smtp_mode = 'servidor', smtp_host = NULL, smtp_username = NULL, smtp_password = NULL, smtp_from = NULL WHERE id = 1"
        ).use { it.executeUpdate() }
    }
}
