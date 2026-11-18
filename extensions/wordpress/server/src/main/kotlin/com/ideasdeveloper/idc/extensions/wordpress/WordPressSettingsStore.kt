package com.ideasdeveloper.idc.extensions.wordpress

import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmUser
import com.ideasdeveloper.idc.modules.crm.CrmValidationException
import com.ideasdeveloper.idc.modules.crm.CrmWordPressException
import com.ideasdeveloper.idc.modules.crm.CrmWordPressSettings
import com.ideasdeveloper.idc.modules.crm.WpAgent
import java.security.SecureRandom
import java.sql.Connection
import java.util.UUID

/**
 * Persistencia de WordPress en las tablas propias de la extension: los ajustes del sitio (ext_wordpress__settings,
 * fila id = 1) y los agentes del sitio (ext_wordpress__agents). El secreto se guarda sellado, siempre por el facade del CRM.
 */
object WordPressSettingsStore {
    const val SettingsTable = "ext_wordpress__settings"
    const val AgentsTable = "ext_wordpress__agents"
    private val random = SecureRandom()

    // Lo que se ve mientras no hay fila (extension recien instalada o sin datos): todo apagado.
    private val Disabled = CrmWordPressSettings(enabled = false, siteUrl = null, secret = null, siteName = "", lastEventAt = null, lastError = "")

    // ---- Ajustes ----

    /** Los ajustes con el secreto ya abierto; sin fila, los de por defecto con WordPress apagado. */
    fun load(host: CrmHost, tenant: Connection): CrmWordPressSettings = tenant.prepareStatement(
        "SELECT enabled, site_url, secret, site_name, last_event_at, last_error FROM $SettingsTable WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            if (!rows.next()) return@use Disabled
            CrmWordPressSettings(rows.getBoolean(1), rows.getString(2), host.openSecret(tenant, rows.getString(3)), rows.getString(4), rows.getTimestamp(5)?.toInstant(), rows.getString(6))
        }
    }

    /** Guarda el formulario de Integraciones (upsert: sin fila, la crea); el secreto no se toca aqui. */
    fun saveForm(host: CrmHost, tenant: Connection, values: Map<String, String>) {
        val url = values["siteUrl"].orEmpty().trim().trimEnd('/').ifEmpty { null }
        if (url != null && !url.matches(Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\\s]*)?$"))) {
            throw CrmValidationException("Escribe la direccion del sitio con https://, por ejemplo https://mi-inmobiliaria.com.")
        }
        host.extensionInactiveReason(tenant, CrmExtensionIds.WordPress)?.let { throw CrmValidationException(it) }
        if (url != null) {
            try {
                CrmWordPress.checkSite(url)
            } catch (failure: CrmWordPressException) {
                throw CrmValidationException(failure.message.orEmpty())
            }
        }
        val enabled = values["enabled"] == "true"
        val secret = load(host, tenant).secret
        if (enabled && (url == null || secret == null)) throw CrmValidationException("Para activar WordPress escribe la direccion del sitio y genera el secreto.")
        tenant.prepareStatement(
            """
            INSERT INTO $SettingsTable (id, enabled, site_url, updated_at) VALUES (1, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, site_url = EXCLUDED.site_url, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { update ->
            update.setBoolean(1, enabled)
            update.setString(2, url)
            update.executeUpdate()
        }
    }

    /** Crea un secreto nuevo (48 letras y numeros), lo guarda sellado y lo devuelve para pegarlo en el plugin. */
    fun newSecret(host: CrmHost, tenant: Connection): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val secret = (1..48).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        tenant.prepareStatement(
            """
            INSERT INTO $SettingsTable (id, secret, updated_at) VALUES (1, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET secret = EXCLUDED.secret, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { it.setString(1, host.sealSecret(tenant, secret)); it.executeUpdate() }
        return secret
    }

    /** Anota el ultimo evento del sitio: su nombre (si lo informo) y el error (vacio = sin error). */
    fun record(tenant: Connection, siteName: String?, error: String) {
        tenant.prepareStatement(
            "UPDATE $SettingsTable SET last_event_at = CURRENT_TIMESTAMP, site_name = COALESCE(?, site_name), last_error = ? WHERE id = 1"
        ).use { update ->
            update.setString(1, siteName)
            update.setString(2, error.take(500))
            update.executeUpdate()
        }
    }

    fun recordError(tenant: Connection, error: String) = record(tenant, null, error)

    // ---- Agentes del sitio ----

    /** Guarda los agentes del sitio y vincula a los nuevos con el usuario de IDC que tiene su mismo correo; devuelve cuantos recibio. */
    fun syncAgents(tenant: Connection, agents: List<WpAgent>, users: List<CrmUser>, userEmails: Map<UUID, String>): Int {
        agents.forEach { agent ->
            if (agent.wpUserId <= 0) return@forEach
            val match = users.firstOrNull { user -> userEmails[user.id]?.equals(agent.email, ignoreCase = true) == true }?.id
            tenant.prepareStatement(
                """
                INSERT INTO $AgentsTable (wp_user_id, name, email, user_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (wp_user_id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email,
                    user_id = COALESCE($AgentsTable.user_id, EXCLUDED.user_id), updated_at = CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { upsert ->
                upsert.setLong(1, agent.wpUserId)
                upsert.setString(2, agent.name.take(200))
                upsert.setString(3, agent.email.take(254))
                upsert.setObject(4, match)
                upsert.executeUpdate()
            }
        }
        return agents.size
    }

    fun agents(tenant: Connection): List<WpAgent> =
        tenant.prepareStatement("SELECT wp_user_id, name, email, user_id FROM $AgentsTable ORDER BY name").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(WpAgent(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getObject(4, UUID::class.java))) } }
        }

    fun mapAgent(tenant: Connection, wpUserId: Long, userId: UUID?) {
        tenant.prepareStatement("UPDATE $AgentsTable SET user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE wp_user_id = ?").use { update ->
            update.setObject(1, userId)
            update.setLong(2, wpUserId)
            update.executeUpdate()
        }
    }

    /** El usuario de IDC del agente del sitio; los agentes vistos por primera vez se anotan para poder vincularlos en Integraciones. */
    fun userForAgent(tenant: Connection, wpUserId: Long?, name: String, email: String): UUID? {
        if (wpUserId == null || wpUserId <= 0) return null
        tenant.prepareStatement(
            """
            INSERT INTO $AgentsTable (wp_user_id, name, email) VALUES (?, ?, ?)
            ON CONFLICT (wp_user_id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email
            RETURNING user_id
            """.trimIndent()
        ).use { upsert ->
            upsert.setLong(1, wpUserId)
            upsert.setString(2, name.take(200))
            upsert.setString(3, email.take(254))
            upsert.executeQuery().use { rows -> return if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
    }
}
