package com.ideasdeveloper.idc.modules.crm

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Mentions in the notes of a lead (table crm_lead_mentions, V018). Any user with access to the CRM can be mentioned,
 * even if the lead is not theirs. While the mention is active (not revoked) the user can READ the lead (open it, read
 * its notes and conversation, see it in the "Leads" views while it is open) and ADD notes: nothing else.
 *
 * The two kinds of visibility are kept apart on purpose:
 * - visible by scope ([CrmScope.canSee]): the user's interests, assignments, reception, "see all". Every write asks this.
 * - visible by mention ([CrmScope.canRead]): only reading and notes. See [LeadAccess].
 *
 * The rules that need no database (finding @mentions in a text, who to notify, what an access allows) are plain
 * functions so they can be tested.
 */
object CrmMentions {
    /** One parameter: the user has an active mention in the lead aliased "l". */
    // A mention only reads while the lead is open: an archived lead is not reached through it (not even with "Mostrar archivados").
    const val OfUserSql = "(l.archived_at IS NULL AND EXISTS (SELECT 1 FROM crm_lead_mentions m WHERE m.lead_id = l.id AND m.user_id = ? AND m.revoked_at IS NULL))"

    // ---- What an access allows ----

    /**
     * How a user reaches a lead. [Full]: inside their scope (what they could always do, with their permissions).
     * [ReadNotes]: only through a mention. [None]: they do not see it.
     */
    enum class LeadAccess {
        None, ReadNotes, Full;

        /** Open the lead and read its data, notes and conversation. */
        val canRead get() = this != None

        /** Move, edit, assign, restore, archive, delete, send messages, bulk actions: only with the scope (plus the permission of each action). */
        val canWrite get() = this == Full
    }

    /** The access to a lead of a user with [scope]; [mentioned] says whether they have an active mention in it. */
    fun leadAccess(scope: CrmScope, interestId: UUID?, ownerUserIds: Collection<UUID>, mentioned: Boolean, archived: Boolean = false): LeadAccess = when {
        scope.canSee(interestId, ownerUserIds) -> LeadAccess.Full
        scope.canRead(interestId, ownerUserIds, mentioned, archived) -> LeadAccess.ReadNotes
        else -> LeadAccess.None
    }

    /**
     * Whether the user may add a note: with the scope it needs `crm.leads.manage` ([canManage], as before); through a
     * mention it is enough to have the mention (answering is what it is for).
     */
    fun canAddNote(access: LeadAccess, canManage: Boolean): Boolean = when (access) {
        LeadAccess.Full -> canManage
        LeadAccess.ReadNotes -> true
        LeadAccess.None -> false
    }

    /**
     * Who gives access by mentioning: only a user inside the lead's normal scope. Someone who reads through a mention can
     * write notes, but their @ are plain text: they create no access, bring back no revoked one and notify nobody.
     */
    fun canGrant(access: LeadAccess): Boolean = access == LeadAccess.Full

    /** Who may take a mention away: whoever manages the lead (`crm.leads.manage` with the lead inside their scope). */
    fun canRevoke(access: LeadAccess, canManage: Boolean): Boolean = access.canWrite && canManage

    // ---- Finding mentions in a text ----

    /** The users a text mentioned and the "@words" that matched nobody. */
    data class Parsed(val users: List<CrmUser>, val unknown: List<String>)

    private fun isWordChar(char: Char) = char.isLetterOrDigit() || char == '_'

    private fun isSpace(char: Char) = char.isWhitespace() || char == '\u00A0'

    /**
     * End of [candidate] matched in [text] from [start] (ignoring case; any run of spaces, tabs or NBSP counts as one
     * space), or -1. The name must end the word: not followed by a letter or digit, nor by `. _ + -` and a letter or
     * digit ("@Ana-Maria" is not "@Ana").
     */
    private fun matchEnd(text: String, start: Int, candidate: String): Int {
        var t = start
        var c = 0
        while (c < candidate.length) {
            if (isSpace(candidate[c])) {
                while (c < candidate.length && isSpace(candidate[c])) c++
                if (t >= text.length || !isSpace(text[t])) return -1
                while (t < text.length && isSpace(text[t])) t++
            } else {
                if (t >= text.length || !text[t].equals(candidate[c], ignoreCase = true)) return -1
                t++
                c++
            }
        }
        if (t < text.length) {
            if (isWordChar(text[t])) return -1
            if (text[t] in "._+-" && t + 1 < text.length && text[t + 1].isLetterOrDigit()) return -1
        }
        return t
    }

    /**
     * Finds `@Nombre` and `@correo` in [text] against [users] (name or email, ignoring case). Names may have spaces
     * ("@Beto Ruiz"): the longest name or email that matches wins, and it must end at a word boundary. An `@` glued to
     * a word is an email address, not a mention. Users are returned once, in order of appearance; "@words" that match
     * nobody are returned in [Parsed.unknown] (without repeats).
     */
    fun parseText(text: String, users: List<CrmUser>): Parsed {
        val candidates = users.flatMap { user -> listOf(user.name, user.email).filter { it.isNotBlank() }.map { it to user } }
        val found = linkedMapOf<UUID, CrmUser>()
        val unknown = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '@' || index > 0 && (isWordChar(text[index - 1]) || text[index - 1] == '@')) {
                index++
                continue
            }
            val start = index + 1
            val match = candidates
                .map { (candidate, user) -> Triple(candidate, user, matchEnd(text, start, candidate)) }
                .filter { it.third >= 0 }
                .maxByOrNull { it.third }
            if (match != null) {
                found.putIfAbsent(match.second.id, match.second)
                index = match.third
            } else {
                // A word that looks like a mention but names nobody (an "@" alone is just a symbol).
                var end = start
                while (end < text.length && (isWordChar(text[end]) || text[end] in "._-@+")) end++
                val word = text.substring(start, end).trimEnd('.', '_', '-', '@', '+')
                if (word.isNotEmpty() && unknown.none { it.equals(word, ignoreCase = true) }) unknown += word
                index = maxOf(end, start)
            }
        }
        return Parsed(found.values.toList(), unknown)
    }

    /**
     * Everybody a note mentions: the users chosen in the "Mencionar a" field (or sent as `mentions` by an agent; names
     * or emails) and the `@Nombre` / `@correo` of the text, each once. [Parsed.unknown] lists what matched nobody
     * (chosen names as they were sent, text mentions without the "@").
     */
    fun resolve(text: String, chosen: List<String>, mentionable: List<CrmUser>): Parsed {
        val fromField = CrmAssignees.match(chosen.map { it.trim() }.filter { it.isNotEmpty() }, mentionable)
        val fromText = parseText(text, mentionable)
        val ids = (fromField.ids + fromText.users.map { it.id }).distinct()
        val users = ids.mapNotNull { id -> mentionable.firstOrNull { it.id == id } }
        val unknown = (fromField.unmatched + fromText.unknown).distinctBy { it.lowercase() }
        return Parsed(users, unknown)
    }

    /**
     * The users of [mentioned] that must be told and given access: the ones the note had not mentioned yet
     * ([already]: users that already have a row for this note, active or revoked) and never the writer ([byUserId]).
     * Mentioning yourself, or mentioning again in the same note, notifies nobody.
     */
    fun toNotify(already: Collection<UUID>, mentioned: Collection<UUID>, byUserId: UUID?): List<UUID> =
        mentioned.distinct().filter { it !in already && it != byUserId }

    /** "Ana" -> "@Ana"; the line shown for a note that mentions people ("Menciona a @Ana, @Beto"). */
    fun label(names: Collection<String>): String = names.joinToString(", ") { "@$it" }

    // ---- Database ----

    /** The leads where [userId] has an active mention (one read per request; the views compare against it). */
    fun leadIds(tenant: Connection, userId: UUID): Set<UUID> =
        tenant.prepareStatement("SELECT DISTINCT lead_id FROM crm_lead_mentions WHERE user_id = ? AND revoked_at IS NULL").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }

    /** Whether [userId] has an active mention in the lead. */
    fun isMentioned(tenant: Connection, leadId: UUID, userId: UUID): Boolean =
        tenant.prepareStatement("SELECT 1 FROM crm_lead_mentions WHERE lead_id = ? AND user_id = ? AND revoked_at IS NULL LIMIT 1").use { query ->
            query.setObject(1, leadId)
            query.setObject(2, userId)
            query.executeQuery().use { it.next() }
        }

    /** The access of the user of [scope] to [lead], reading their mention from the database only when the scope is not enough. */
    fun access(tenant: Connection, scope: CrmScope, lead: CrmLead): LeadAccess {
        if (scope.canSee(lead.interestId, lead.ownerUserIds)) return LeadAccess.Full
        val mentioned = scope.userId?.let { isMentioned(tenant, lead.id, it) } ?: false
        return leadAccess(scope, lead.interestId, lead.ownerUserIds, mentioned, lead.archivedAt != null)
    }

    /** An active mention shown in the "Mencionados" list of the lead. */
    data class Mention(val userId: UUID, val byUserId: UUID?, val byName: String, val at: Instant)

    /** The users with an active mention in the lead (each once, the oldest mention), with who mentioned them. */
    fun active(tenant: Connection, leadId: UUID): List<Mention> = tenant.prepareStatement(
        """
        SELECT m.user_id, m.mentioned_by, COALESCE(bu.postgres_role, a.author, ''), m.created_at
        FROM crm_lead_mentions m
        LEFT JOIN application_users bu ON bu.id = m.mentioned_by
        LEFT JOIN crm_activity a ON a.id = m.activity_id
        WHERE m.lead_id = ? AND m.revoked_at IS NULL
        ORDER BY m.created_at, m.user_id
        """.trimIndent()
    ).use { query ->
        query.setObject(1, leadId)
        query.executeQuery().use { rows ->
            buildList { while (rows.next()) add(Mention(rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getString(3), rows.getTimestamp(4).toInstant())) }
        }.distinctBy { it.userId }
    }

    /** Who each note of the lead mentions (note id -> users), to highlight them in the history. */
    fun byNote(tenant: Connection, leadId: UUID): Map<UUID, List<UUID>> =
        tenant.prepareStatement("SELECT activity_id, user_id FROM crm_lead_mentions WHERE lead_id = ? AND activity_id IS NOT NULL ORDER BY created_at, user_id").use { query ->
            query.setObject(1, leadId)
            query.executeQuery().use { rows ->
                buildMap<UUID, MutableList<UUID>> {
                    while (rows.next()) getOrPut(rows.getObject(1, UUID::class.java)) { mutableListOf() } += rows.getObject(2, UUID::class.java)
                }
            }
        }

    /**
     * Records the mentions of a note and returns the users really added (the ones to notify): the note's rows that
     * already exist, even revoked, are not created again, and the writer is never recorded.
     */
    fun record(tenant: Connection, leadId: UUID, activityId: UUID, mentioned: Collection<UUID>, byUserId: UUID?): List<UUID> {
        val already = tenant.prepareStatement("SELECT user_id FROM crm_lead_mentions WHERE activity_id = ?").use { query ->
            query.setObject(1, activityId)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
        val added = toNotify(already, mentioned, byUserId)
        if (added.isEmpty()) return emptyList()
        val inserted = tenant.prepareStatement(
            """
            INSERT INTO crm_lead_mentions (lead_id, user_id, activity_id, mentioned_by) SELECT ?::uuid, u, ?::uuid, ?::uuid FROM unnest(?::uuid[]) AS u
            WHERE EXISTS (SELECT 1 FROM application_users au WHERE au.id = u)
            ON CONFLICT DO NOTHING RETURNING user_id
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, leadId)
            insert.setObject(2, activityId)
            insert.setObject(3, byUserId)
            insert.setArray(4, tenant.createArrayOf("uuid", added.toTypedArray()))
            insert.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
        return added.filter { it in inserted }
    }

    /** What adding a note did: who was mentioned (and told) and what looked like a mention but matched nobody. */
    class NoteResult(val mentioned: List<CrmUser>, val unknown: List<String>, val granted: Boolean = true)

    /**
     * Adds the note and records who it mentions in one transaction. [mentionable] are the users that can be mentioned
     * (see [CrmRepository.mentionableUsers]); [chosen] the names or emails of the "Mencionar a" field. The users to tell
     * come back in [NoteResult.mentioned] (never the writer, never twice for the note): the caller sends the notices with
     * [CrmNotify.mentioned] once the note is saved.
     */
    fun addNote(tenant: Connection, leadId: UUID, body: String, chosen: List<String>, mentionable: List<CrmUser>, actor: CrmActor, canGrant: Boolean): NoteResult {
        val resolved = resolve(body, chosen, mentionable)
        val activityId = UUID.randomUUID()
        val added = CrmRepository.transaction(tenant) {
            CrmRepository.addNote(tenant, leadId, body, actor, activityId)
            // Only who has the lead in their scope gives access; for the rest the @ stay plain text.
            if (canGrant) record(tenant, leadId, activityId, resolved.users.map { it.id }, actor.userId) else emptyList()
        }
        return NoteResult(added.mapNotNull { id -> resolved.users.firstOrNull { it.id == id } }, resolved.unknown, canGrant || resolved.users.isEmpty())
    }

    /**
     * Takes the access of [userId] away from the lead (sets revoked_at on every active mention) and records it in the
     * history. Returns false when the user had no active mention. Mentions in later notes give access again.
     */
    fun revoke(tenant: Connection, leadId: UUID, userId: UUID, names: CrmNames, actor: CrmActor): Boolean = CrmRepository.transaction(tenant) {
        val revoked = tenant.prepareStatement(
            "UPDATE crm_lead_mentions SET revoked_at = CURRENT_TIMESTAMP WHERE lead_id = ? AND user_id = ? AND revoked_at IS NULL"
        ).use { update ->
            update.setObject(1, leadId)
            update.setObject(2, userId)
            update.executeUpdate()
        } > 0
        if (revoked) CrmRepository.addActivity(tenant, leadId, "updated", "Acceso por mencion quitado a ${CrmRepository.displayNames(tenant, names, listOf(userId))}.", actor)
        revoked
    }
}
