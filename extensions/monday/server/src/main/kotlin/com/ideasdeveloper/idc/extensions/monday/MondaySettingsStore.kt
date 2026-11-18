package com.ideasdeveloper.idc.extensions.monday

import com.ideasdeveloper.idc.modules.crm.CrmHost
import java.sql.Connection

/** Persistencia del token de monday en la tabla propia de la extension (ext_monday__settings, fila id = 1). */
object MondaySettingsStore {
    const val Table = "ext_monday__settings"

    /** El token ya abierto, o null si no hay (o no se puede leer: no debe enviarse a monday). */
    fun loadToken(host: CrmHost, tenant: Connection): String? =
        tenant.prepareStatement("SELECT token FROM $Table WHERE id = 1").use { query ->
            query.executeQuery().use { rows ->
                if (rows.next()) host.openSecret(tenant, rows.getString(1))?.takeIf { it.isNotBlank() && !host.isSecretUnreadable(it) } else null
            }
        }

    /** Guarda el token sellado (upsert: sin fila, la crea); null o vacio lo borra. */
    fun saveToken(host: CrmHost, tenant: Connection, token: String?) {
        tenant.prepareStatement(
            """
            INSERT INTO $Table (id, token) VALUES (1, ?)
            ON CONFLICT (id) DO UPDATE SET token = EXCLUDED.token, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { update ->
            update.setString(1, host.sealSecret(tenant, token?.trim()?.takeIf { it.isNotEmpty() }))
            update.executeUpdate()
        }
    }
}
