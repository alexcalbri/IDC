package com.ideasdeveloper.idc.modules.crm

import java.sql.Connection
import java.util.UUID

data class CrmSpamSettings(
    val words: List<String>,
    val maxLinks: Int,
    val blockCyrillic: Boolean,
    val blockedDomains: List<String>,
    val channels: Set<String>,
    val skipKnownLeads: Boolean,
)

/** Content rules for incoming leads and messages (forms, email, chats, portals). */
object CrmSpam {
    // Links to these domains do not count towards the link limit (portals that send leads).
    private val trustedDomains = setOf("encuentra24.com")
    private val url = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    // Bare domains such as "cheap-seo.net", not part of an email address.
    private val bareDomain = Regex("(?i)(?<![@\\w.-])(?:[a-z0-9-]+\\.)+(?:com|net|org|info|biz|ru|io|co|xyz|top|online|site|shop)\\b")
    private val cyrillic = Regex("\\p{IsCyrillic}")
    private val leet = mapOf('4' to 'a', '@' to 'a', '3' to 'e', '1' to 'i', '!' to 'i', '0' to 'o', '$' to 's', '5' to 's', '7' to 't')

    val channelLabels = linkedMapOf(
        "form" to "Formularios",
        "email" to "Correo",
        "whatsapp" to "WhatsApp",
        "messenger" to "Messenger",
        "instagram" to "Instagram",
        "encuentra24" to "Encuentra24",
    )

    /**
     * Reason to block the content, or null. Words match whole (also plural, with separators like
     * "s.e.o" and digit substitutions like "c4sino"); links count URLs and bare domains except the
     * company's own and [trustedDomains]; any Cyrillic letter blocks when enabled.
     */
    fun check(settings: CrmSpamSettings, text: String, email: String, ownDomains: Set<String> = emptySet()): String? {
        val domain = email.substringAfterLast('@', "").lowercase()
        if (domain.isNotEmpty() && settings.blockedDomains.any { domain == it || domain.endsWith(".$it") }) {
            return "Dominio de correo bloqueado: $domain"
        }
        if (settings.blockCyrillic && cyrillic.containsMatchIn(text)) return "Texto en cirilico"
        val normalized = text.lowercase().map { leet[it] ?: it }.joinToString("")
        settings.words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.firstOrNull { word ->
            val letters = word.map { Regex.escape(it.toString()) }.joinToString("[\\s._*-]?")
            Regex("(?<![\\p{L}\\d])$letters(?:e?s)?(?![\\p{L}\\d])").containsMatchIn(normalized)
        }?.let { return "Palabra bloqueada: $it" }
        val allowed = trustedDomains + ownDomains.map { it.lowercase() }
        val links = (url.findAll(text).map { it.value } + bareDomain.findAll(text).map { it.value })
            .map { it.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/') }
            .distinct()
            .filter { host -> allowed.none { host == it || host.endsWith(".$it") } }
            .count()
        if (links > settings.maxLinks) return "Demasiados enlaces ($links)"
        return null
    }

    fun load(tenant: Connection): CrmSpamSettings = tenant.prepareStatement(
        "SELECT words, max_links, block_cyrillic, blocked_domains, channels, skip_known_leads FROM crm_spam_settings WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            if (!rows.next()) return CrmSpamSettings(emptyList(), 50, false, emptyList(), emptySet(), true)
            fun array(index: Int) = (rows.getArray(index)?.array as? Array<*>)?.map { it.toString() }.orEmpty()
            CrmSpamSettings(array(1), rows.getInt(2), rows.getBoolean(3), array(4), array(5).toSet(), rows.getBoolean(6))
        }
    }

    fun save(tenant: Connection, settings: CrmSpamSettings) {
        tenant.prepareStatement(
            """
            UPDATE crm_spam_settings SET words = ?, max_links = ?, block_cyrillic = ?, blocked_domains = ?, channels = ?,
                skip_known_leads = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = 1
            """.trimIndent()
        ).use { update ->
            update.setArray(1, tenant.createArrayOf("text", settings.words.toTypedArray()))
            update.setInt(2, settings.maxLinks)
            update.setBoolean(3, settings.blockCyrillic)
            update.setArray(4, tenant.createArrayOf("text", settings.blockedDomains.toTypedArray()))
            update.setArray(5, tenant.createArrayOf("text", settings.channels.toTypedArray()))
            update.setBoolean(6, settings.skipKnownLeads)
            update.executeUpdate()
        }
    }

    fun log(tenant: Connection, channel: String, sender: String, reason: String, excerpt: String) {
        tenant.prepareStatement("INSERT INTO crm_spam_log (id, channel, sender, reason, excerpt) VALUES (?, ?, ?, ?, ?)").use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, channel)
            insert.setString(3, sender.take(200))
            insert.setString(4, reason.take(300))
            insert.setString(5, excerpt.replace('\n', ' ').take(300))
            insert.executeUpdate()
        }
        // Only the last 50 blocks are kept.
        tenant.prepareStatement(
            "DELETE FROM crm_spam_log WHERE id NOT IN (SELECT id FROM crm_spam_log ORDER BY created_at DESC LIMIT 50)"
        ).use { it.executeUpdate() }
    }

    data class Blocked(val channel: String, val sender: String, val reason: String, val excerpt: String, val at: java.time.Instant)

    fun recent(tenant: Connection): List<Blocked> =
        tenant.prepareStatement("SELECT channel, sender, reason, excerpt, created_at FROM crm_spam_log ORDER BY created_at DESC LIMIT 50").use { query ->
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(Blocked(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getTimestamp(5).toInstant())) }
            }
        }

    /** Reads the anti-spam form: words and domains one per line or separated by commas. */
    fun parse(values: Map<String, String>): CrmSpamSettings {
        fun list(key: String) = values[key].orEmpty().split('\n', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val words = list("words")
        if (words.any { it.length > 40 }) throw CrmValidationException("Cada palabra admite hasta 40 caracteres.")
        val domains = list("blockedDomains").map { it.removePrefix("@") }
        if (domains.any { !it.matches(Regex("^[a-z0-9.-]+\\.[a-z]{2,}$")) }) throw CrmValidationException("Escribe dominios como ejemplo.com.")
        val maxLinks = values["maxLinks"].orEmpty().trim().ifEmpty { "1" }.toIntOrNull()?.takeIf { it in 0..50 }
            ?: throw CrmValidationException("El maximo de enlaces debe ser un numero entre 0 y 50.")
        return CrmSpamSettings(
            words = words,
            maxLinks = maxLinks,
            blockCyrillic = values["blockCyrillic"] == "true",
            blockedDomains = domains,
            channels = channelLabels.keys.filter { values["channel_$it"] == "true" }.toSet(),
            skipKnownLeads = values["skipKnownLeads"] == "true",
        )
    }
}
