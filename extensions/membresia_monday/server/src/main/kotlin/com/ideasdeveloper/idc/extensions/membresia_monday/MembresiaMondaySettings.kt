package com.ideasdeveloper.idc.extensions.membresia_monday

import com.ideasdeveloper.idc.server.extensions.ExtensionStatus
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretDecryptionException
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import java.sql.Connection
import java.sql.SQLException

/**
 * Persistencia del token de monday en la tabla propia de la extension (ext_membresia_monday__settings, fila id = 1).
 * El token se guarda sellado con la llave de la empresa; jamas se registra ni se devuelve a una pantalla o herramienta:
 * lo unico que se muestra es si esta configurado.
 */
object MembresiaMondaySettings {
    const val Table = "ext_membresia_monday__settings"
    const val UnavailableMessage = "No se pueden guardar secretos: falta la llave maestra del servidor (IDC_MASTER_KEY). Avisa al dueno del servidor."

    /** La extension esta activa en la empresa (tenant_extensions.status = enabled); sin la tabla o la fila, no. */
    fun extensionEnabled(tenant: Connection): Boolean = try {
        tenant.prepareStatement("SELECT status FROM tenant_extensions WHERE extension_id = ?").use { query ->
            query.setString(1, MembresiaMondayId)
            query.executeQuery().use { it.next() && it.getString(1) == ExtensionStatus.Enabled }
        }
    } catch (failure: SQLException) {
        false
    }

    /** Hay un token guardado (no dice si se puede abrir). */
    fun configured(tenant: Connection): Boolean =
        tenant.prepareStatement("SELECT 1 FROM $Table WHERE id = 1 AND coalesce(token, '') <> ''").use { query ->
            query.executeQuery().use { it.next() }
        }

    /** El token ya abierto, o null si no hay o no se puede abrir (llave maestra ausente o distinta): no debe enviarse a monday. */
    fun loadToken(secrets: SecretBox, tenant: Connection): String? {
        val stored = tenant.prepareStatement("SELECT token FROM $Table WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }?.takeIf { it.isNotBlank() } ?: return null
        return try {
            secrets.open(tenant, stored).takeIf { it.isNotBlank() }
        } catch (failure: SecretsUnavailableException) {
            null
        } catch (failure: SecretDecryptionException) {
            null
        }
    }

    /** Guarda el token sellado (upsert); null o vacio lo borra. Sin llave maestra no guarda nada en claro. */
    fun saveToken(secrets: SecretBox, tenant: Connection, token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        val sealed = clean?.let {
            try {
                secrets.seal(tenant, it)
            } catch (failure: SecretsUnavailableException) {
                throw MembresiaMondayException(UnavailableMessage)
            }
        }
        tenant.prepareStatement(
            """
            INSERT INTO $Table (id, token) VALUES (1, ?)
            ON CONFLICT (id) DO UPDATE SET token = EXCLUDED.token, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).use { update ->
            update.setString(1, sealed)
            update.executeUpdate()
        }
    }
}
