package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretDecryptionException
import com.ideasdeveloper.idc.server.security.SecretMigration
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import java.sql.Connection

/**
 * Cifrado de los secretos del CRM (tokens, claves de API, contrasenas de correo). Toda lectura pasa por [open] y toda
 * escritura por [seal]; las pantallas y las herramientas MCP siguen mostrando solo la mascara. La llave de datos es la de
 * la base de la conexion que se pasa (cada empresa tiene la suya, y el espacio del servidor tambien).
 */
object CrmSecrets {
    // La pone CrmServerModule.routes con ModuleAccessService.secrets; nula en pruebas sin cifrado.
    @Volatile
    var box: SecretBox? = null

    const val UnavailableMessage = "No se pueden guardar secretos: falta la llave maestra del servidor (IDC_MASTER_KEY). Avisa al dueno del servidor."

    // Un secreto cifrado que no se puede abrir (llave maestra ausente o distinta) se lee como "unreadable:" + el valor crudo:
    // asi nunca se confunde con vacio y un guardado posterior lo reescribe tal cual en vez de borrarlo.
    const val UnreadablePrefix = "unreadable:"
    const val UnreadableText = "No se puede leer el secreto guardado (revisa IDC_MASTER_KEY)"

    fun isUnreadable(value: String?): Boolean = value != null && value.startsWith(UnreadablePrefix)

    /** Lo que muestran las pantallas: vacio, la mascara, o el aviso de que el secreto guardado no se puede leer. */
    fun mask(value: String?): String = when {
        value.isNullOrBlank() -> ""
        isUnreadable(value) -> UnreadableText
        else -> CrmMail.SecretMask
    }

    /** Valores de un formulario que significan "no cambiar el secreto": la mascara y el aviso de ilegible. */
    fun isPlaceholder(value: String?): Boolean = value == CrmMail.SecretMask || value == UnreadableText

    /** Valor en claro; lo que aun esta sin cifrar (anterior a la migracion) se devuelve tal cual. Sin poder descifrarlo, [UnreadablePrefix] + crudo. */
    fun open(tenant: Connection, value: String?): String? {
        if (value.isNullOrEmpty()) return value
        val sealed = value.startsWith(SecretBox.SealedPrefix)
        if (!sealed) return value
        val secrets = box ?: return UnreadablePrefix + value
        return try {
            secrets.open(tenant, value)
        } catch (failure: SecretsUnavailableException) {
            UnreadablePrefix + value
        } catch (failure: SecretDecryptionException) {
            UnreadablePrefix + value
        }
    }

    /**
     * Valor para guardar, cifrado con la llave de la base. Vacio o nulo se guarda como esta; un secreto ilegible se reescribe tal cual
     * (el crudo). Sin caja de cifrado o sin llave no se guarda nada en claro: falla.
     */
    fun seal(tenant: Connection, value: String?): String? {
        if (value.isNullOrEmpty()) return value
        if (isUnreadable(value)) return value.removePrefix(UnreadablePrefix)
        val secrets = box ?: throw CrmValidationException(UnavailableMessage)
        return try {
            secrets.seal(tenant, value)
        } catch (failure: SecretsUnavailableException) {
            throw CrmValidationException(UnavailableMessage)
        }
    }

    /** Una columna de secretos de una tabla del CRM. */
    data class Column(val table: String, val idColumns: List<String>, val secretColumns: List<String>)

    /** Todas las columnas con secretos de la base de una empresa. */
    val tenantColumns = listOf(
        Column("crm_meta_settings", listOf("id"), listOf("app_secret", "verify_token", "system_token", "page_token", "messenger_token", "instagram_token", "oauth_token")),
        Column("crm_wordpress_settings", listOf("id"), listOf("secret")),
        Column("crm_encuentra24_settings", listOf("id"), listOf("api_key")),
        Column("crm_cloudbeds_settings", listOf("id"), listOf("api_key")),
        Column("crm_settings", listOf("id"), listOf("monday_token", "translation_api_key")),
        Column("crm_mail_settings", listOf("id"), listOf("smtp_password", "imap_password")),
    )

    /** Columnas del espacio del servidor (la app de Meta del dueno del servidor). */
    val serverColumns = listOf(
        Column("crm_meta_server_app", listOf("id"), listOf("app_secret", "verify_token")),
    )

    /** Cifra lo que siga en claro en [columns]; devuelve cuantos valores cifro. Sin llave no hace nada. */
    fun sealAll(tenant: Connection, secrets: SecretBox, columns: List<Column>): Int {
        if (!secrets.available) return 0
        return columns.sumOf { SecretMigration.sealColumns(tenant, secrets, it.table, it.idColumns, it.secretColumns) }
    }
}
