package com.ideasdeveloper.idc.extensions.inmobiliaria_monday

import com.ideasdeveloper.idc.modules.crm.CrmHost
import java.sql.Connection

/** Persistencia del token de monday en la tabla propia de la extension (ext_inmobiliaria_monday__settings, fila id = 1). */
object InmobiliariaMondaySettingsStore {
    const val Table = "ext_inmobiliaria_monday__settings"

    /** El token ya abierto, o null si no hay (o no se puede leer: no debe enviarse a monday). */
    fun loadToken(tenant: Connection): String? =
        tenant.prepareStatement("SELECT token FROM $Table WHERE id = 1").use { query ->
            query.executeQuery().use { rows ->
                if (rows.next()) CrmHost.openSecret(tenant, rows.getString(1))?.takeIf { it.isNotBlank() && !CrmHost.isSecretUnreadable(it) } else null
            }
        }

    /** Guarda el token sellado (upsert: sin fila, la crea); null o vacio lo borra. */
    fun saveToken(tenant: Connection, token: String?) {
        tenant.prepareStatement(
            """
            INSERT INTO $Table (id, token) VALUES (1, ?)
            ON CONFLICT (id) DO UPDATE SET token = EXCLUDED.token, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).use { update ->
            update.setString(1, CrmHost.sealSecret(tenant, token?.trim()?.takeIf { it.isNotEmpty() }))
            update.executeUpdate()
        }
    }
}
