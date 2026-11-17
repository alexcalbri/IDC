package com.ideasdeveloper.idc.modules.crm

import java.sql.Connection
import java.util.UUID

/**
 * People assigned to a lead (table crm_lead_owners, V017). They are all equal: there is no main person.
 * The table is the only source of truth; crm_leads.owner_user_id is no longer read or written (it stays in
 * the schema only so the change can be rolled back).
 *
 * The rules that need no database (merging, matching people to users, initials) are plain functions so
 * they can be tested.
 */
object CrmAssignees {
    // ---- SQL over crm_leads aliased "l" ----

    /** The lead has at least one assigned user. */
    const val AnySql = "EXISTS (SELECT 1 FROM crm_lead_owners o WHERE o.lead_id = l.id)"

    /** The lead has nobody assigned ("Sin asignar"). */
    const val NoneSql = "NOT EXISTS (SELECT 1 FROM crm_lead_owners o WHERE o.lead_id = l.id)"

    /** One parameter: the lead is assigned to that user ("Mis leads", visibility of the assigned). */
    const val OfUserSql = "EXISTS (SELECT 1 FROM crm_lead_owners o WHERE o.lead_id = l.id AND o.user_id = ?)"

    /** Column expression with the ids of the assigned users, oldest assignment first, for the lead aliased "l". */
    const val ListSql = "COALESCE((SELECT array_agg(o.user_id ORDER BY o.created_at, o.user_id) FROM crm_lead_owners o WHERE o.lead_id = l.id), ARRAY[]::uuid[])"

    /** [ListSql] for a lead table with another alias. */
    fun listSql(alias: String): String =
        "COALESCE((SELECT array_agg(o.user_id ORDER BY o.created_at, o.user_id) FROM crm_lead_owners o WHERE o.lead_id = $alias.id), ARRAY[]::uuid[])"

    // ---- Rules without a database ----

    /** How a list of people is applied to the ones a lead already has. */
    enum class Mode { Add, Remove, Replace }

    /** The assigned users after applying [mode]; never repeats a user and keeps the order of [current]. */
    fun combine(current: List<UUID>, mode: Mode, ids: Collection<UUID>): List<UUID> = when (mode) {
        Mode.Add -> (current + ids).distinct()
        Mode.Remove -> current.filter { it !in ids }
        Mode.Replace -> ids.distinct()
    }

    /**
     * Like [combine], but a replacement keeps the assigned users that can no longer be chosen (not in [assignable],
     * e.g. deactivated ones): an agent or a form that does not know them must not drop them.
     */
    fun combineKeepingInactive(current: List<UUID>, mode: Mode, ids: Collection<UUID>, assignable: Collection<UUID>): List<UUID> =
        if (mode == Mode.Replace) (ids.distinct() + current.filter { it !in assignable }).distinct() else combine(current, mode, ids)

    /** What an assigner's save asks for: the new list; without permission to assign, null = leave the assigned users untouched. */
    fun wanted(canAssign: Boolean, parsed: List<UUID>?): List<UUID>? = if (canAssign) parsed else null

    /** Users can be added only when they still have access ([assignable]); removing is always allowed. */
    fun canApply(mode: Mode, ids: Collection<UUID>, assignable: Collection<UUID>): Boolean = mode == Mode.Remove || ids.all { it in assignable }

    /** Approving a suggestion only assigns a user that still has access; otherwise it is just discarded. */
    fun suggestionLacksAccess(approve: Boolean, suggested: UUID, hasAccess: (UUID) -> Boolean): Boolean = approve && !hasAccess(suggested)

    /** Users in [after] that [before] did not have: the ones that get "Te asignaron un lead". */
    fun added(before: Collection<UUID>, after: Collection<UUID>): List<UUID> = after.distinct().filter { it !in before }

    /**
     * Names of the assigned users separated by comma; "" without any. Assigned users without access to the CRM
     * (deactivated) are left out: this is what templates show to a customer.
     */
    fun names(names: CrmNames, ids: Collection<UUID>): String = ids.filter { names.knows(it) }.joinToString(", ") { names.user(it) }

    /** Like [names] but says "Sin asignar" when nobody is assigned (screens). */
    fun label(names: CrmNames, ids: Collection<UUID>): String = if (ids.isEmpty()) CrmForms.Unassigned else names(names, ids)

    /** "Ana Perez" -> "AP", "ana" -> "A"; at most two letters. */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(1).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    /** Initials of the assigned users, as short labels: the first [max] and "+N" for the rest. */
    fun initialsLabels(names: CrmNames, ids: List<UUID>, max: Int = 3): List<String> =
        ids.take(max).map { initials(names.user(it)) } + listOfNotNull((ids.size - max).takeIf { it > 0 }?.let { "+$it" })

    /** Several names or emails separated by comma, semicolon or new line: trimmed, without empties or repeats. */
    fun parseList(raw: String): List<String> =
        raw.split(',', ';', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** The users a list of people matched and the ones nobody matched (never creates users). */
    data class Matched(val ids: List<UUID>, val unmatched: List<String>)

    /**
     * Pairs each of [people] (names or emails) with a CRM user: first by the [equivalences] table (source value
     * -> user name), then by the user's email, then by the user's name; ignoring case. People without a user
     * are returned in [Matched.unmatched] so the report can list them.
     */
    fun match(people: List<String>, users: List<CrmUser>, equivalences: Map<String, String> = emptyMap()): Matched {
        val ids = linkedSetOf<UUID>()
        val unmatched = mutableListOf<String>()
        people.forEach { person ->
            val equivalent = equivalences[person] ?: equivalences.entries.firstOrNull { it.key.equals(person, ignoreCase = true) }?.value
            val user = equivalent?.let { name -> users.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                ?: users.firstOrNull { it.email.isNotEmpty() && it.email.equals(person, ignoreCase = true) }
                ?: users.firstOrNull { it.name.equals(person, ignoreCase = true) }
            if (user != null) ids += user.id else if (unmatched.none { it.equals(person, ignoreCase = true) }) unmatched += person
        }
        return Matched(ids.toList(), unmatched)
    }

    /** Text of the import report for people that had no user ("" when none). */
    fun unmatchedNote(unmatched: List<String>): String =
        if (unmatched.isEmpty()) "" else "Sin usuario para asignar (omitidos): ${unmatched.joinToString(", ")}."

    // ---- Database ----

    /** The assigned users of a lead, oldest assignment first. */
    fun of(tenant: Connection, leadId: UUID): List<UUID> =
        tenant.prepareStatement("SELECT user_id FROM crm_lead_owners WHERE lead_id = ? ORDER BY created_at, user_id").use { query ->
            query.setObject(1, leadId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }

    /** What really changed in the database: the users added and the users removed (never the ones that were already so). */
    data class Change(val added: List<UUID>, val removed: List<UUID>) {
        val isEmpty get() = added.isEmpty() && removed.isEmpty()
    }

    /**
     * The changes that turn the list the user saw ([before], sent back by the form) into the list they chose
     * ([wanted]): only the difference is applied, so assignments made by somebody else in between are not undone.
     */
    fun diff(before: Collection<UUID>, wanted: Collection<UUID>): Change =
        Change(wanted.distinct().filter { it !in before }, before.distinct().filter { it !in wanted })

    // Locks the lead row so concurrent changes of its assigned users run one after another. Inside a transaction.
    private fun <T> locked(tenant: Connection, leadId: UUID, block: () -> T): T {
        val body = {
            tenant.prepareStatement("SELECT 1 FROM crm_leads WHERE id = ? FOR UPDATE").use { it.setObject(1, leadId); it.executeQuery().use { rows -> rows.next() } }
            block()
        }
        return if (tenant.autoCommit) CrmRepository.transaction(tenant, body) else body()
    }

    private fun uuidArray(tenant: Connection, ids: Collection<UUID>) = tenant.createArrayOf("uuid", ids.distinct().toTypedArray())

    /** Assigns [ids] to the lead; the ones it already had are left alone. Returns the users that were really added. */
    fun add(tenant: Connection, leadId: UUID, ids: Collection<UUID>): List<UUID> {
        if (ids.isEmpty()) return emptyList()
        val inserted = locked(tenant, leadId) {
            tenant.prepareStatement(
                "INSERT INTO crm_lead_owners (lead_id, user_id) SELECT ?::uuid, u FROM unnest(?::uuid[]) AS u ON CONFLICT DO NOTHING RETURNING user_id"
            ).use { insert ->
                insert.setObject(1, leadId)
                insert.setArray(2, uuidArray(tenant, ids))
                insert.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
            }
        }
        return ids.distinct().filter { it in inserted }
    }

    /** Takes [ids] out of the lead's assigned users. Returns the users that were really removed. */
    fun remove(tenant: Connection, leadId: UUID, ids: Collection<UUID>): List<UUID> {
        if (ids.isEmpty()) return emptyList()
        val deleted = locked(tenant, leadId) {
            tenant.prepareStatement("DELETE FROM crm_lead_owners WHERE lead_id = ? AND user_id = ANY(?) RETURNING user_id").use { delete ->
                delete.setObject(1, leadId)
                delete.setArray(2, uuidArray(tenant, ids))
                delete.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
            }
        }
        return ids.distinct().filter { it in deleted }
    }

    /** Leaves exactly [ids] assigned to the lead. */
    fun replace(tenant: Connection, leadId: UUID, ids: Collection<UUID>): Change = locked(tenant, leadId) {
        val removed = tenant.prepareStatement("DELETE FROM crm_lead_owners WHERE lead_id = ? AND NOT (user_id = ANY(?)) RETURNING user_id").use { delete ->
            delete.setObject(1, leadId)
            delete.setArray(2, uuidArray(tenant, ids))
            delete.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
        Change(add(tenant, leadId, ids), removed)
    }

    /** Applies the difference [change] (users to add and to remove) and returns what really changed. */
    fun apply(tenant: Connection, leadId: UUID, change: Change): Change = locked(tenant, leadId) {
        Change(add(tenant, leadId, change.added), remove(tenant, leadId, change.removed))
    }
}
