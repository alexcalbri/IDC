package com.ideasdeveloper.idc.extensions.encuentra24

import com.ideasdeveloper.idc.modules.crm.CrmEncuentra24Ads
import com.ideasdeveloper.idc.modules.crm.CrmEncuentra24Settings
import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmValidationException
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

/** Persistencia de los ajustes de Encuentra24 en la tabla propia de la extension (ext_encuentra24__settings, fila id = 1). */
object Encuentra24SettingsStore {
    const val Table = "ext_encuentra24__settings"

    /** Los ajustes con la API key ya abierta; sin fila, los de por defecto con Encuentra24 apagado. */
    fun load(host: CrmHost, tenant: Connection): CrmEncuentra24Settings = tenant.prepareStatement(
        "SELECT enabled, api_key, slug, last_sync, last_result, last_error FROM $Table WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            if (!rows.next()) return@use CrmEncuentra24Ads.Disabled
            CrmEncuentra24Settings(
                rows.getBoolean(1), host.openSecret(tenant, rows.getString(2)), rows.getString(3),
                rows.getTimestamp(4)?.toInstant(), rows.getString(5), rows.getString(6),
            )
        }
    }

    fun saveForm(host: CrmHost, tenant: Connection, values: Map<String, String>) {
        host.extensionInactiveReason(tenant, CrmExtensionIds.Encuentra24)?.let { throw CrmValidationException(it) }
        val current = load(host, tenant)
        val key = values["apiKey"]?.trim()?.takeIf { it.isNotEmpty() && !host.isSecretPlaceholder(it) } ?: current.apiKey
        val slug = values["slug"].orEmpty().trim().lowercase().ifEmpty { null }
        if (slug != null && !slug.matches(Regex("^[a-z0-9][a-z0-9-]{0,99}$"))) {
            throw CrmValidationException("El slug lleva letras minusculas, numeros y guiones (como en la URL de la empresa).")
        }
        val enabled = values["enabled"] == "true"
        if (enabled && (key.isNullOrBlank() || slug == null)) throw CrmValidationException("Para activar Encuentra24 escribe la API key y el slug de la empresa.")
        // Upsert: si la fila no existe (extension recien instalada) se crea aqui en vez de perder el guardado.
        tenant.prepareStatement(
            """
            INSERT INTO $Table (id, enabled, api_key, slug, updated_at) VALUES (1, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, api_key = EXCLUDED.api_key, slug = EXCLUDED.slug, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { update ->
            update.setBoolean(1, enabled)
            update.setString(2, host.sealSecret(tenant, key))
            update.setString(3, slug)
            update.executeUpdate()
        }
    }

    /** Guarda el resultado de la ultima importacion (upsert: sin fila no se pierde el resultado). */
    fun record(tenant: Connection, syncedAt: Instant?, result: String, error: String) {
        tenant.prepareStatement(
            """
            INSERT INTO $Table (id, last_sync, last_result, last_error) VALUES (1, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET last_sync = COALESCE(EXCLUDED.last_sync, $Table.last_sync),
                last_result = EXCLUDED.last_result, last_error = EXCLUDED.last_error
            """.trimIndent()
        ).use { update ->
            update.setTimestamp(1, syncedAt?.let { Timestamp.from(it) })
            update.setString(2, result.take(500))
            update.setString(3, error.take(500))
            update.executeUpdate()
        }
    }
}
