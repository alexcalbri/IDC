package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.UserIdentity
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

// Who performs a change. userId is null for server owners: they are not users of the company database.
data class CrmActor(val userId: UUID?, val name: String)

/** Tenant database access of the CRM. Every lead query applies the caller's [CrmScope]. */
object CrmRepository {
    private const val LeadColumns = """
        l.id, l.customer_id, c.display_name, c.primary_email, c.primary_phone, c.customer_code, l.stage_id, l.interest_id,
        ${CrmAssignees.ListSql} AS owner_ids, l.source, l.language, l.message, l.details, l.target_start, l.target_end,
        l.estimated_value, l.follow_up_on, l.stage_changed_at, l.closed_at, l.created_at, l.suggested_owner_user_id, l.review_reason, l.archived_at, l.restored_at,
        l.custom_fields::text AS custom_fields
    """
    private const val LeadFrom = "FROM crm_leads l JOIN customers c ON c.id = l.customer_id"

    // ---- Settings, stages and interests ----

    fun interestsEnabled(tenant: Connection): Boolean =
        tenant.prepareStatement("SELECT interests_enabled FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    fun setInterestsEnabled(tenant: Connection, enabled: Boolean) {
        tenant.prepareStatement(
            "INSERT INTO crm_settings (id, interests_enabled) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET interests_enabled = EXCLUDED.interests_enabled, updated_at = CURRENT_TIMESTAMP"
        ).use { it.setBoolean(1, enabled); it.executeUpdate() }
    }

    fun stages(tenant: Connection): List<CrmStage> =
        tenant.prepareStatement("SELECT id, name, kind, tone, position FROM crm_stages ORDER BY position, name").use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(
                        CrmStage(
                            rows.getObject("id", UUID::class.java), rows.getString("name"),
                            StageKind.fromId(rows.getString("kind")), StageTone.fromId(rows.getString("tone")), rows.getInt("position"),
                        )
                    )
                }
            }
        }

    fun saveStage(tenant: Connection, id: UUID?, input: CrmStageInput) {
        val sql = if (id == null) {
            "INSERT INTO crm_stages (name, kind, tone, position, id) VALUES (?, ?, ?, ?, ?)"
        } else {
            "UPDATE crm_stages SET name = ?, kind = ?, tone = ?, position = ? WHERE id = ?"
        }
        tenant.prepareStatement(sql).use { statement ->
            statement.setString(1, input.name)
            statement.setString(2, input.kind.id)
            statement.setString(3, input.tone.id)
            statement.setInt(4, input.position)
            statement.setObject(5, id ?: UUID.randomUUID())
            if (statement.executeUpdate() != 1) throw CrmValidationException("Esa etapa ya no existe.")
        }
        // Leads keep their closing date in line with the kind of the stage they are in.
        if (id != null) tenant.prepareStatement(
            """
            UPDATE crm_leads SET closed_at = CASE WHEN ? = 'open' THEN NULL ELSE COALESCE(closed_at, CURRENT_TIMESTAMP) END
            WHERE stage_id = ?
            """.trimIndent()
        ).use { it.setString(1, input.kind.id); it.setObject(2, id); it.executeUpdate() }
    }

    fun stageLeadCount(tenant: Connection, id: UUID): Int =
        tenant.prepareStatement("SELECT count(*) FROM crm_leads WHERE stage_id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    // Only stages without leads can be deleted, so no lead is left without a stage.
    fun deleteStage(tenant: Connection, id: UUID): Boolean {
        if (stageLeadCount(tenant, id) > 0) throw CrmValidationException("Mueve primero los leads de esta etapa a otra.")
        return transaction(tenant) {
            // The exit automation forgets the stage; without stages left it is switched off.
            tenant.prepareStatement(
                "UPDATE crm_settings SET exit_enabled = exit_enabled AND cardinality(array_remove(exit_stage_ids, ?)) > 0, exit_stage_ids = array_remove(exit_stage_ids, ?) WHERE id = 1"
            ).use { it.setObject(1, id); it.setObject(2, id); it.executeUpdate() }
            tenant.prepareStatement("DELETE FROM crm_stages WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() == 1 }
        }
    }

    fun leadCount(tenant: Connection): Int =
        tenant.prepareStatement("SELECT count(*) FROM crm_leads").use { query -> query.executeQuery().use { rows -> rows.next(); rows.getInt(1) } }

    // Replaces every stage with a template. Only allowed while the company has no leads.
    fun applyTemplate(tenant: Connection, template: CrmTemplates.Template) = transaction(tenant) {
        if (leadCount(tenant) > 0) throw CrmValidationException("Las plantillas solo se aplican mientras no hay leads.")
        tenant.prepareStatement("DELETE FROM crm_stages").use { it.executeUpdate() }
        // The old stages are gone: the exit automation starts over.
        tenant.prepareStatement("UPDATE crm_settings SET exit_enabled = FALSE, exit_stage_ids = '{}' WHERE id = 1").use { it.executeUpdate() }
        template.stages.forEach { saveStage(tenant, null, it) }
    }

    fun interests(tenant: Connection): List<CrmInterest> =
        tenant.prepareStatement("SELECT id, name, position FROM crm_interests ORDER BY position, name").use { query ->
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(CrmInterest(rows.getObject("id", UUID::class.java), rows.getString("name"), rows.getInt("position"))) }
            }
        }

    fun saveInterest(tenant: Connection, id: UUID?, name: String) {
        val updated = if (id == null) {
            tenant.prepareStatement("INSERT INTO crm_interests (id, name, position) SELECT ?, ?, COALESCE(max(position) + 1, 0) FROM crm_interests").use {
                it.setObject(1, UUID.randomUUID()); it.setString(2, name); it.executeUpdate()
            }
        } else {
            tenant.prepareStatement("UPDATE crm_interests SET name = ? WHERE id = ?").use { it.setString(1, name); it.setObject(2, id); it.executeUpdate() }
        }
        if (updated != 1) throw CrmValidationException("Ese interes ya no existe.")
    }

    // The leads of a deleted interest go back to the reception inbox.
    fun deleteInterest(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("DELETE FROM crm_interests WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() == 1 }

    fun userInterests(tenant: Connection): Map<UUID, Set<UUID>> =
        tenant.prepareStatement("SELECT user_id, interest_id FROM crm_user_interests").use { query ->
            query.executeQuery().use { rows ->
                buildMap<UUID, MutableSet<UUID>> {
                    while (rows.next()) getOrPut(rows.getObject(1, UUID::class.java)) { mutableSetOf() } += rows.getObject(2, UUID::class.java)
                }
            }
        }

    fun setUserInterests(tenant: Connection, userId: UUID, interests: Set<UUID>) = transaction(tenant) {
        tenant.prepareStatement("DELETE FROM crm_user_interests WHERE user_id = ?").use { it.setObject(1, userId); it.executeUpdate() }
        tenant.prepareStatement("INSERT INTO crm_user_interests (user_id, interest_id) VALUES (?, ?)").use { insert ->
            interests.forEach { interest ->
                insert.setObject(1, userId)
                insert.setObject(2, interest)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    // "Mostrar archivados" of the lead views, remembered per user.
    fun showArchived(tenant: Connection, userId: UUID): Boolean =
        tenant.prepareStatement("SELECT show_archived FROM crm_user_settings WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    // Last view of "Leads" the user used ("pipeline" or "list"); the pipeline until they choose.
    fun leadsView(tenant: Connection, userId: UUID): String =
        tenant.prepareStatement("SELECT leads_view FROM crm_user_settings WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else CrmScreens.ViewPipeline }
        }

    fun setLeadsView(tenant: Connection, userId: UUID, value: String) {
        tenant.prepareStatement(
            "INSERT INTO crm_user_settings (user_id, leads_view) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET leads_view = EXCLUDED.leads_view"
        ).use { upsert ->
            upsert.setObject(1, userId)
            upsert.setString(2, value)
            upsert.executeUpdate()
        }
    }

    fun setShowArchived(tenant: Connection, userId: UUID, value: Boolean) {
        tenant.prepareStatement(
            "INSERT INTO crm_user_settings (user_id, show_archived) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET show_archived = EXCLUDED.show_archived"
        ).use { upsert ->
            upsert.setObject(1, userId)
            upsert.setBoolean(2, value)
            upsert.executeUpdate()
        }
    }

    // Users that can own leads: active and either business owner or holder of a CRM permission.
    fun users(tenant: Connection): List<CrmUser> = tenant.prepareStatement(
        """
        SELECT users.id, users.postgres_role, COALESCE(users.email, '')
        FROM application_users users
        WHERE users.is_active
          AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
               OR EXISTS (SELECT 1 FROM application_user_permissions granted
                          WHERE granted.user_id = users.id AND granted.permission_id LIKE 'crm.%'))
        ORDER BY users.postgres_role
        """.trimIndent()
    ).use { query ->
        query.executeQuery().use { rows -> buildList { while (rows.next()) add(CrmUser(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3))) } }
    }

    /** Users that can be mentioned in a note: active, business owner or holder of the CRM view permission (they open the CRM). */
    fun mentionableUsers(tenant: Connection): List<CrmUser> = tenant.prepareStatement(
        """
        SELECT users.id, users.postgres_role, COALESCE(users.email, '')
        FROM application_users users
        WHERE users.is_active
          AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
               OR EXISTS (SELECT 1 FROM application_user_permissions granted
                          WHERE granted.user_id = users.id AND granted.permission_id = '${CrmScreens.View}'))
        ORDER BY users.postgres_role
        """.trimIndent()
    ).use { query ->
        query.executeQuery().use { rows -> buildList { while (rows.next()) add(CrmUser(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3))) } }
    }

    fun scope(tenant: Connection, permissions: ModulePermissions): CrmScope {
        val userId = permissions.userId?.takeUnless { permissions.serverOwner }
        val interests = userId?.let { userInterests(tenant)[it] }.orEmpty()
        return CrmScope(
            userId = userId,
            seesAll = permissions.has(CrmScreens.SeeAll),
            reception = permissions.has(CrmScreens.Reception),
            interestsEnabled = interestsEnabled(tenant),
            interests = interests,
        )
    }

    // [identity]: ModuleAccessService.userIdentity, so changes show the user's name.
    fun actor(tenant: Connection, permissions: ModulePermissions, identity: UserIdentity? = null): CrmActor {
        if (permissions.serverOwner || permissions.userId == null) {
            return CrmActor(null, identity?.let { "${it.name} (dueno del servidor)" } ?: "Dueno del servidor")
        }
        identity?.let { return CrmActor(permissions.userId, it.name) }
        val name = tenant.prepareStatement("SELECT postgres_role FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, permissions.userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        return CrmActor(permissions.userId, name ?: "Usuario")
    }

    // ---- Leads ----

    private fun where(scope: CrmScope, filter: CrmLeadFilter): Pair<String, List<Any>> {
        val (visible, params) = scope.sqlCondition(filter.withMentions)
        val parts = mutableListOf(visible)
        archivedSql(filter)?.let { parts += it }
        val values = params.toMutableList()
        if (scope.interestsEnabled) {
            if (filter.withoutInterest) parts += "l.interest_id IS NULL"
            filter.interestId?.let { parts += "l.interest_id = ?"; values += it }
        }
        if (filter.unqualified) parts += unqualifiedSql(scope.interestsEnabled)
        filter.ownerUserId?.let { parts += CrmAssignees.OfUserSql; values += it }
        if (filter.unassigned) parts += CrmAssignees.NoneSql
        filter.stageId?.let { parts += "l.stage_id = ?"; values += it }
        if (filter.openOnly) parts += "l.closed_at IS NULL"
        val search = filter.search.trim().lowercase()
        if (search.isNotEmpty()) {
            val pattern = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            parts += "(lower(c.display_name) LIKE ? OR lower(c.primary_email) LIKE ? OR c.primary_phone LIKE ? OR lower(l.source) LIKE ? OR lower(l.message) LIKE ?)"
            repeat(5) { values += pattern }
        }
        return parts.joinToString(" AND ") to values
    }

    fun leads(tenant: Connection, scope: CrmScope, filter: CrmLeadFilter, limit: Int, offset: Int): List<CrmLead> {
        val (condition, params) = where(scope, filter)
        // Archived leads: the most recently archived first (crm_leads_archived_idx).
        val order = if (filter.archived) "l.updated_at DESC" else "l.created_at DESC"
        return tenant.prepareStatement("SELECT $LeadColumns $LeadFrom WHERE $condition ORDER BY $order LIMIT ? OFFSET ?").use { query ->
            query.bind(tenant, params + limit + offset)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.lead()) } }
        }
    }

    fun count(tenant: Connection, scope: CrmScope, filter: CrmLeadFilter): Int {
        val (condition, params) = where(scope, filter)
        return tenant.prepareStatement("SELECT count(*) $LeadFrom WHERE $condition").use { query ->
            query.bind(tenant, params)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    // The most recently moved [perStage] leads of each stage and the total per stage.
    fun board(tenant: Connection, scope: CrmScope, filter: CrmLeadFilter, perStage: Int): Pair<Map<UUID, List<CrmLead>>, Map<UUID, Int>> {
        val (condition, params) = where(scope, filter)
        val leads = tenant.prepareStatement(
            """
            SELECT * FROM (
                SELECT $LeadColumns, row_number() OVER (PARTITION BY l.stage_id ORDER BY l.stage_changed_at DESC) AS position
                $LeadFrom WHERE $condition
            ) ranked WHERE position <= ?
            """.trimIndent()
        ).use { query ->
            query.bind(tenant, params + perStage)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.lead()) } }
        }
        val totals = tenant.prepareStatement("SELECT l.stage_id, count(*) $LeadFrom WHERE $condition GROUP BY l.stage_id").use { query ->
            query.bind(tenant, params)
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getInt(2)) } }
        }
        return leads.groupBy { it.stageId } to totals
    }

    // Open leads with a follow-up up to [until] (overdue ones included), soonest first.
    fun followUps(tenant: Connection, scope: CrmScope, filter: CrmLeadFilter, until: LocalDate, limit: Int): List<CrmLead> {
        val (condition, params) = where(scope, filter.copy(openOnly = true))
        return tenant.prepareStatement(
            "SELECT $LeadColumns $LeadFrom WHERE $condition AND l.follow_up_on IS NOT NULL AND l.follow_up_on <= ? ORDER BY l.follow_up_on, c.display_name LIMIT ?"
        ).use { query ->
            query.bind(tenant, params + until + limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.lead()) } }
        }
    }

    fun lead(tenant: Connection, id: UUID): CrmLead? =
        tenant.prepareStatement("SELECT $LeadColumns $LeadFrom WHERE l.id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.lead() else null }
        }

    /**
     * Creates a lead for an existing customer when there is one, in this order: [chosenCustomerId] (chosen
     * from the suggestions of the form), the same email, the same phone (compared by its digits).
     * The customer keeps its data; only an email or phone it did not have yet is added. Without a
     * match a new prospect customer is created in Clientes.
     */
    fun createLead(tenant: Connection, entered: CrmLeadInput, stage: CrmStage, actor: CrmActor, chosenCustomerId: UUID? = null, fields: List<CrmField> = emptyList()): UUID =
        insertLead(tenant, entered, stage, actor, chosenCustomerId, fields).also { id ->
            // Once saved: the "lead creado" automations (best effort, never fails the creation).
            CrmAutomationEvents.leadCreated(tenant, id)
        }

    private fun insertLead(tenant: Connection, entered: CrmLeadInput, stage: CrmStage, actor: CrmActor, chosenCustomerId: UUID?, fields: List<CrmField>): UUID = transaction(tenant) {
        // target_start and target_end are copied from the mapped date fields (empty without mapping).
        val input = withStayDates(tenant, entered, JsonObject(emptyMap()), fields)
        val existing = chosenCustomerId?.takeIf { activeCustomer(tenant, it) } ?: matchCustomer(tenant, input.email, input.phone)
        val customerId = existing?.also { CrmInbox.fillMissingContact(tenant, it, input.email, input.phone) } ?: UUID.randomUUID().also { id ->
            tenant.prepareStatement(
                "INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type) VALUES (?, ?, ?, ?, 'prospect', 'crm')"
            ).use { insert ->
                insert.setObject(1, id)
                insert.setString(2, input.name)
                insert.setString(3, input.email)
                insert.setString(4, input.phone)
                insert.executeUpdate()
            }
        }
        val id = UUID.randomUUID()
        tenant.prepareStatement(
            """
            INSERT INTO crm_leads (
                customer_id, stage_id, interest_id, source, language, message, details,
                target_start, target_end, estimated_value, follow_up_on, closed_at, id, custom_fields
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP END, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use { insert ->
            insert.bind(tenant, listOf(customerId) + leadValues(input) + (stage.kind != StageKind.Open) + id + CrmFields.merge(JsonObject(emptyMap()), input.custom).toString())
            insert.executeUpdate()
        }
        CrmAssignees.add(tenant, id, input.ownerUserIds.orEmpty())
        addActivity(tenant, id, "created", "Lead creado en ${stage.name}.", actor, toStage = stage.id)
        linkFiles(tenant, id, input, fields, actor)
        id
    }

    private fun activeCustomer(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("SELECT 1 FROM customers WHERE id = ? AND status <> 'archived'").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { it.next() }
        }

    // Digits of a phone; shorter than 7 digits it is too ambiguous to match on.
    fun phoneDigits(phone: String): String = phone.filter { it.isDigit() }.takeIf { it.length >= 7 }.orEmpty()

    /** The oldest active customer with this email or, failing that, this phone (by its digits). */
    fun matchCustomer(tenant: Connection, email: String, phone: String): UUID? {
        fun first(condition: String, value: String): UUID? =
            tenant.prepareStatement("SELECT id FROM customers WHERE $condition AND status <> 'archived' ORDER BY created_at LIMIT 1").use { query ->
                query.setString(1, value)
                query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
            }
        val digits = phoneDigits(phone)
        return email.trim().takeIf { it.isNotEmpty() }?.let { first("lower(primary_email) = lower(?)", it) }
            ?: digits.takeIf { it.isNotEmpty() }?.let { first("regexp_replace(primary_phone, '[^0-9]', '', 'g') = ?", it) }
    }

    data class CustomerSuggestion(val id: UUID, val name: String, val email: String, val phone: String, val code: String?, val openLeads: Int, val exact: Boolean)

    /**
     * Customers that match what is being typed in the new lead form: [query] in the [field] being
     * typed (name contains it, email or phone digits start/contain it), plus any customer that
     * already has exactly the [email] or [phone] of the form, which comes first.
     */
    fun customerSuggestions(tenant: Connection, field: String, query: String, email: String, phone: String, limit: Int = 6): List<CustomerSuggestion> {
        fun like(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val typed = query.trim()
        val typedDigits = typed.filter { it.isDigit() }
        val (typedCondition, typedValue) = when (field) {
            "email" -> "lower(c.primary_email) LIKE ?" to "${like(typed.lowercase())}%"
            "phone" -> "regexp_replace(c.primary_phone, '[^0-9]', '', 'g') LIKE ?" to (if (typedDigits.length >= 3) "%${like(typedDigits)}%" else "")
            else -> "lower(c.display_name) LIKE ?" to "%${like(typed.lowercase())}%"
        }
        val exactEmail = email.trim()
        val exactPhone = phoneDigits(phone)
        if (typedValue.isEmpty() && exactEmail.isEmpty() && exactPhone.isEmpty()) return emptyList()
        val exact = "((? <> '' AND lower(c.primary_email) = lower(?)) OR (? <> '' AND regexp_replace(c.primary_phone, '[^0-9]', '', 'g') = ?))"
        return tenant.prepareStatement(
            """
            SELECT c.id, c.display_name, c.primary_email, c.primary_phone, c.customer_code,
                (SELECT count(*) FROM crm_leads l WHERE l.customer_id = c.id AND l.closed_at IS NULL) AS open_leads,
                $exact AS exact
            FROM customers c
            WHERE c.status <> 'archived' AND ($exact OR (? <> '' AND $typedCondition))
            ORDER BY exact DESC, c.display_name
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            val exactValues = listOf(exactEmail, exactEmail, exactPhone, exactPhone)
            (exactValues + exactValues + listOf(typedValue, typedValue)).forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.setInt(11, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(
                        CustomerSuggestion(
                            rows.getObject(1, UUID::class.java), rows.getString(2).orEmpty(), rows.getString(3).orEmpty(), rows.getString(4).orEmpty(),
                            rows.getString(5), rows.getInt(6), rows.getBoolean(7),
                        )
                    )
                }
            }
        }
    }

    /**
     * Saves the lead form; contact changes are written to the shared customer. Logs what changed. Returns what really
     * changed in the assigned users (to notify the ones added). [CrmLeadInput.ownerUserIds] null leaves them alone;
     * otherwise only the difference between [ownersBefore] (the list the form was drawn with; the lead's current one
     * by default) and the new list is applied, so assignments made meanwhile by somebody else are not undone.
     */
    fun updateLead(
        tenant: Connection,
        lead: CrmLead,
        entered: CrmLeadInput,
        stages: List<CrmStage>,
        names: CrmNames,
        actor: CrmActor,
        fields: List<CrmField> = emptyList(),
        ownersBefore: List<UUID>? = null,
    ): CrmAssignees.Change {
        val input = withStayDates(tenant, entered, lead.customFields, fields)
        var ownersChange = CrmAssignees.Change(emptyList(), emptyList())
        var stageMoved = false
        transaction(tenant) {
        tenant.prepareStatement(
            "UPDATE customers SET display_name = ?, primary_email = ?, primary_phone = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
        ).use { update ->
            update.setString(1, input.name)
            update.setString(2, input.email)
            update.setString(3, input.phone)
            update.setObject(4, lead.customerId)
            update.executeUpdate()
        }
        tenant.prepareStatement(
            """
            UPDATE crm_leads SET stage_id = ?, interest_id = ?, source = ?, language = ?, message = ?, details = ?,
                target_start = ?, target_end = ?, estimated_value = ?, follow_up_on = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent()
        ).use { update ->
            update.bind(tenant, leadValues(input) + lead.id)
            update.executeUpdate()
        }
        if (input.stageId != lead.stageId) {
            stages.firstOrNull { it.id == input.stageId }?.let { stageMoved = applyMove(tenant, lead, it, stages, actor) }
        }
        // A changed end date puts a restored lead back under the exit automation (a stage change does it in moveLead).
        if (!CrmExit.restoreSurvives(lead.stageId, input.stageId, lead.targetEnd, input.targetEnd)) {
            tenant.prepareStatement("UPDATE crm_leads SET restored_at = NULL WHERE id = ?").use { it.setObject(1, lead.id); it.executeUpdate() }
        }
        input.ownerUserIds?.let { wanted ->
            ownersChange = CrmAssignees.apply(tenant, lead.id, CrmAssignees.diff(ownersBefore ?: lead.ownerUserIds, wanted))
            if (!ownersChange.isEmpty) addActivity(tenant, lead.id, "owner", ownersActivity(tenant, names, CrmAssignees.of(tenant, lead.id), ownersChange), actor)
        }
        if (input.interestId != lead.interestId) {
            addActivity(tenant, lead.id, "interest", "Interes: ${names.interest(input.interestId)}.", actor)
        }
        if (input.custom.isNotEmpty()) {
            tenant.prepareStatement("UPDATE crm_leads SET custom_fields = CAST(? AS jsonb) WHERE id = ?").use { update ->
                update.setString(1, CrmFields.merge(lead.customFields, input.custom).toString())
                update.setObject(2, lead.id)
                update.executeUpdate()
            }
        }
            leadChanges(lead, input, fields)?.let { addActivity(tenant, lead.id, "updated", it, actor) }
            linkFiles(tenant, lead.id, input, fields, actor)
        }
        // Name, email and phone belong to the customer: they change at once in every company it is shared with.
        CrmCustomerEvents.changed(tenant, lead.customerId)
        // Everything is saved: the automations of the stage change and of the changed fields run now (best effort).
        if (stageMoved) CrmAutomationEvents.stageChanged(tenant, lead.id, input.stageId)
        val changedFields = changedFieldKeys(lead, input, fields)
        if (changedFields.isNotEmpty()) CrmAutomationEvents.fieldsChanged(tenant, lead.id, changedFields)
        return ownersChange
    }

    /** Keys of the custom fields (and of the follow-up date) whose value this save changes: the ones a "field changed" automation watches. */
    fun changedFieldKeys(lead: CrmLead, input: CrmLeadInput, fields: List<CrmField>): List<String> {
        val after = CrmFields.merge(lead.customFields, input.custom)
        val custom = fields.filter { it.type != CrmFieldType.Files && it.type != CrmFieldType.Button && CrmFields.display(lead.customFields[it.key]) != CrmFields.display(after[it.key]) }.map { it.key }
        return custom + listOfNotNull(CrmAutomationRefs.FollowUpField.takeIf { lead.followUpOn != input.followUpOn })
    }

    /** "Campo: antes -> despues" for each changed value of the lead form, or null when nothing changed. */
    // Links the files of each files field sent in the form and records what was added or removed.
    private fun linkFiles(tenant: Connection, leadId: UUID, input: CrmLeadInput, fields: List<CrmField>, actor: CrmActor) {
        val lines = input.files.mapNotNull { (key, ids) ->
            val label = if (key == CrmFields.Documents) CrmFields.DocumentsLabel else fields.firstOrNull { it.key == key }?.label ?: key
            CrmAttachments.describe(label, CrmAttachments.link(tenant, leadId, key, ids))
        }
        if (lines.isNotEmpty()) addActivity(tenant, leadId, "files", lines.joinToString("\n"), actor)
    }

    fun leadChanges(lead: CrmLead, input: CrmLeadInput, fields: List<CrmField> = emptyList()): String? {
        fun shown(value: Any?) = value?.toString()?.takeIf { it.isNotBlank() } ?: "(vacio)"
        val lines = buildList {
            fun compare(label: String, before: Any?, after: Any?) {
                if (before != after) add("$label: ${shown(before)} -> ${shown(after)}")
            }
            compare("Nombre", lead.name, input.name)
            compare("Correo", lead.email, input.email)
            compare("Telefono", lead.phone, input.phone)
            compare("Origen", lead.source, input.source)
            compare("Idioma", lead.language, input.language)
            // Long texts would flood the history: only say they changed.
            if (lead.message != input.message) add("Mensaje modificado")
            if (lead.details != input.details) add("Detalles modificados")
            // The dates are custom fields now: their changes are listed with the label of the field (CrmFields.changes).
            compare("Valor estimado", lead.estimatedValue?.stripTrailingZeros()?.toPlainString(), input.estimatedValue?.stripTrailingZeros()?.toPlainString())
            compare("Seguimiento", lead.followUpOn, input.followUpOn)
        }
        val custom = CrmFields.changes(fields, lead.customFields, CrmFields.merge(lead.customFields, input.custom))
        return (lines + custom).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    // Moves a lead to another stage, records the stage history and keeps closed_at in line with the stage kind.
    /**
     * With rooms chosen in Cloudbeds a lead is won by confirming the reservation, not by moving it: true when moving
     * [leadId] to [stage] must be refused for that reason. Shared by the pipeline drag and the bulk move.
     */
    fun wonNeedsReservation(tenant: Connection, leadId: UUID, stage: CrmStage): Boolean {
        if (stage.kind != StageKind.Won) return false
        val pendingRooms = CrmItems.list(tenant, leadId, CrmCloudbeds.Source).any { it.status == "chosen" }
        val reserved = CrmCloudbeds.forLead(tenant, leadId).any { it.status !in setOf("canceled", "no_show") }
        return pendingRooms && !reserved
    }

    fun moveLead(tenant: Connection, lead: CrmLead, stage: CrmStage, stages: List<CrmStage>, actor: CrmActor) {
        // Moved: the "cambio a una etapa" automations run (best effort). A caller inside a transaction uses [applyMove] and fires them after committing.
        if (applyMove(tenant, lead, stage, stages, actor)) CrmAutomationEvents.stageChanged(tenant, lead.id, stage.id)
    }

    /** [moveLead] without the automations; true when the lead really changed stage (or closed state). */
    private fun applyMove(tenant: Connection, lead: CrmLead, stage: CrmStage, stages: List<CrmStage>, actor: CrmActor): Boolean {
        // Archived leads sit outside the stages (a stage change would mix them back into the pipeline).
        if (lead.archivedAt != null) throw CrmValidationException("Restaura el lead antes de moverlo.")
        if (stage.id == lead.stageId && lead.closedAt == null == (stage.kind == StageKind.Open)) return false
        tenant.prepareStatement(
            """
            UPDATE crm_leads SET stage_id = ?, stage_changed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, restored_at = NULL,
                closed_at = CASE WHEN ? THEN NULL ELSE COALESCE(closed_at, CURRENT_TIMESTAMP) END
            WHERE id = ?
            """.trimIndent()
        ).use { update ->
            update.setObject(1, stage.id)
            update.setBoolean(2, stage.kind == StageKind.Open)
            update.setObject(3, lead.id)
            update.executeUpdate()
        }
        val from = stages.firstOrNull { it.id == lead.stageId }?.name ?: "otra etapa"
        addActivity(tenant, lead.id, "stage", "$from -> ${stage.name}", actor, fromStage = lead.stageId, toStage = stage.id)
        // A won lead is a sale: it goes to the customer's history of the company, with a link to the lead.
        if (stage.kind == StageKind.Won && stages.firstOrNull { it.id == lead.stageId }?.kind != StageKind.Won) {
            CrmCustomerEvents.won(tenant, lead, actor)
        }
        return stage.id != lead.stageId
    }

    /**
     * Brings an archived lead back: it keeps the stage it had and its closed_at. Idempotent: returns false
     * (and writes nothing) when the lead was not archived. Cloudbeds and imports never call this.
     */
    fun restoreLead(tenant: Connection, leadId: UUID, actor: CrmActor): Boolean = transaction(tenant) {
        val restored = tenant.prepareStatement(
            "UPDATE crm_leads SET archived_at = NULL, restored_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND archived_at IS NOT NULL"
        ).use { update -> update.setObject(1, leadId); update.executeUpdate() } > 0
        if (restored) addActivity(tenant, leadId, "updated", "Lead restaurado.", actor)
        restored
    }

    /** Archives an open lead by hand (bulk actions). Idempotent: false when it was already archived. */
    fun archiveLead(tenant: Connection, leadId: UUID, actor: CrmActor): Boolean = transaction(tenant) {
        val archived = tenant.prepareStatement(
            "UPDATE crm_leads SET archived_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND archived_at IS NULL"
        ).use { update -> update.setObject(1, leadId); update.executeUpdate() } > 0
        if (archived) addActivity(tenant, leadId, "updated", "Lead archivado.", actor)
        archived
    }

    // [id]: the history entry's id, so the mentions of the note can hang from it (see CrmMentions.addNote).
    fun addNote(tenant: Connection, leadId: UUID, body: String, actor: CrmActor, id: UUID = UUID.randomUUID()) {
        addActivity(tenant, leadId, "note", body, actor, id = id)
        tenant.prepareStatement("UPDATE crm_leads SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, leadId); it.executeUpdate() }
    }

    // Deletes the lead and its activity; the customer stays in Clientes.
    fun deleteLead(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("DELETE FROM crm_leads WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() == 1 }

    fun activity(tenant: Connection, leadId: UUID, limit: Int): List<CrmActivity> = tenant.prepareStatement(
        """
        SELECT a.kind, a.body, fs.name, ts.name, a.author, a.created_at, a.id
        FROM crm_activity a
        LEFT JOIN crm_stages fs ON fs.id = a.from_stage_id
        LEFT JOIN crm_stages ts ON ts.id = a.to_stage_id
        WHERE a.lead_id = ?
        ORDER BY a.created_at DESC
        LIMIT ?
        """.trimIndent()
    ).use { query ->
        query.setObject(1, leadId)
        query.setInt(2, limit)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(
                    CrmActivity(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getTimestamp(6).toInstant(), rows.getObject(7, UUID::class.java))
                )
            }
        }
    }

    // Leads created since [since], open leads and leads won since [since], within the scope.
    data class Summary(val open: Int, val createdSince: Int, val wonSince: Int, val overdue: Int)

    fun summary(tenant: Connection, scope: CrmScope, since: LocalDate, today: LocalDate): Summary {
        val (condition, params) = where(scope, CrmLeadFilter())
        return tenant.prepareStatement(
            """
            SELECT count(*) FILTER (WHERE l.closed_at IS NULL),
                   count(*) FILTER (WHERE l.created_at >= ?),
                   count(*) FILTER (WHERE l.closed_at >= ? AND s.kind = 'won'),
                   count(*) FILTER (WHERE l.closed_at IS NULL AND l.follow_up_on < ?)
            $LeadFrom JOIN crm_stages s ON s.id = l.stage_id
            WHERE $condition
            """.trimIndent()
        ).use { query ->
            val start = java.sql.Timestamp.valueOf(since.atStartOfDay())
            query.bind(tenant, listOf<Any>(start, start, today) + params)
            query.executeQuery().use { rows -> rows.next(); Summary(rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getInt(4)) }
        }
    }

    /** Users in [ids] that are not in [users] (deactivated or without CRM access), named "<usuario> (sin acceso)". */
    fun legacyUsers(tenant: Connection, users: List<CrmUser>, ids: Collection<UUID>): List<CrmUser> {
        val missing = ids.distinct().filter { id -> users.none { it.id == id } }
        if (missing.isEmpty()) return emptyList()
        val roles = tenant.prepareStatement("SELECT id, postgres_role FROM application_users WHERE id = ANY(?)").use { query ->
            query.setArray(1, tenant.createArrayOf("uuid", missing.toTypedArray()))
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getString(2)) } }
        }
        return missing.map { CrmUser(it, "${roles[it] ?: "Usuario eliminado"} (sin acceso)") }
    }

    /** Names of [ids] for the history: users without access keep their name with "(sin acceso)". */
    fun displayNames(tenant: Connection, names: CrmNames, ids: Collection<UUID>): String {
        val legacy = legacyUsers(tenant, emptyList(), ids.filter { !names.knows(it) }).associate { it.id to it.name }
        return ids.joinToString(", ") { if (names.knows(it)) names.user(it) else legacy[it] ?: names.user(it) }
    }

    /** History line: who the lead has now, and who was added or removed. */
    fun ownersActivity(tenant: Connection, names: CrmNames, now: Collection<UUID>, change: CrmAssignees.Change, suffix: String = ""): String {
        val parts = mutableListOf("Asignados: ${if (now.isEmpty()) CrmForms.Unassigned else displayNames(tenant, names, now)}$suffix")
        if (change.added.isNotEmpty()) parts += "Se anadio: ${displayNames(tenant, names, change.added)}"
        if (change.removed.isNotEmpty()) parts += "Se quito: ${displayNames(tenant, names, change.removed)}"
        return parts.joinToString(". ") + "."
    }

    /**
     * Adds, removes or replaces assigned users of a lead in one transaction, with its history entry. Returns what
     * really changed ([CrmAssignees.Change.added] are the ones to notify); nothing is written when nothing changes.
     */
    fun assign(tenant: Connection, lead: CrmLead, mode: CrmAssignees.Mode, ids: Collection<UUID>, names: CrmNames, actor: CrmActor, suffix: String = ""): CrmAssignees.Change = transaction(tenant) {
        val change = when (mode) {
            CrmAssignees.Mode.Add -> CrmAssignees.Change(CrmAssignees.add(tenant, lead.id, ids), emptyList())
            CrmAssignees.Mode.Remove -> CrmAssignees.Change(emptyList(), CrmAssignees.remove(tenant, lead.id, ids))
            CrmAssignees.Mode.Replace -> CrmAssignees.replace(tenant, lead.id, ids)
        }
        if (!change.isEmpty) {
            tenant.prepareStatement("UPDATE crm_leads SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, lead.id); it.executeUpdate() }
            addActivity(tenant, lead.id, "owner", ownersActivity(tenant, names, CrmAssignees.of(tenant, lead.id), change, suffix), actor)
        }
        change
    }

    /** [added]: the user the approval really added (to notify); [noAccess]: the suggested user can no longer have leads, so nobody was assigned. */
    data class SuggestionResult(val added: UUID?, val noAccess: Boolean)

    // Adds the suggested user to the assigned ones (approve = true) or drops the suggestion. A suggested user without
    // access to the CRM (deactivated, no permission) is never assigned: the suggestion is only discarded.
    fun resolveSuggestion(tenant: Connection, lead: CrmLead, approve: Boolean, names: CrmNames, actor: CrmActor): SuggestionResult = transaction(tenant) {
        val suggested = lead.suggestedOwnerUserId ?: return@transaction SuggestionResult(null, false)
        val noAccess = CrmAssignees.suggestionLacksAccess(approve, suggested) { names.knows(it) }
        tenant.prepareStatement("UPDATE crm_leads SET suggested_owner_user_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setObject(1, lead.id)
            update.executeUpdate()
        }
        val added = if (approve && !noAccess) CrmAssignees.add(tenant, lead.id, listOf(suggested)).firstOrNull() else null
        addActivity(
            tenant, lead.id, "owner",
            when {
                noAccess -> "Sugerencia de ${displayNames(tenant, names, listOf(suggested))} descartada: el usuario ya no tiene acceso."
                approve -> "Asignado: ${names.user(suggested)} (sugerencia aprobada)."
                else -> "Sugerencia de ${names.user(suggested)} descartada."
            },
            actor,
        )
        SuggestionResult(added, noAccess)
    }

    fun clearReview(tenant: Connection, lead: CrmLead, actor: CrmActor) {
        tenant.prepareStatement("UPDATE crm_leads SET review_reason = '' WHERE id = ?").use { it.setObject(1, lead.id); it.executeUpdate() }
        addActivity(tenant, lead.id, "updated", "Revisado: ${lead.reviewReason}", actor)
    }

    // Leads with a responsible suggestion waiting for approval, within the scope.
    fun pendingSuggestions(tenant: Connection, scope: CrmScope, limit: Int): List<CrmLead> {
        val (condition, params) = where(scope, CrmLeadFilter())
        return tenant.prepareStatement("SELECT $LeadColumns $LeadFrom WHERE $condition AND l.suggested_owner_user_id IS NOT NULL ORDER BY l.created_at LIMIT ?").use { query ->
            query.bind(tenant, params + limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.lead()) } }
        }
    }

    fun addActivity(
        tenant: Connection,
        leadId: UUID,
        kind: String,
        body: String,
        actor: CrmActor,
        fromStage: UUID? = null,
        toStage: UUID? = null,
        id: UUID = UUID.randomUUID(),
    ) {
        tenant.prepareStatement(
            "INSERT INTO crm_activity (id, lead_id, kind, body, from_stage_id, to_stage_id, user_id, author) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { insert ->
            insert.setObject(1, id)
            insert.setObject(2, leadId)
            insert.setString(3, kind)
            insert.setString(4, body.take(5000))
            insert.setObject(5, fromStage)
            insert.setObject(6, toStage)
            insert.setObject(7, actor.userId)
            insert.setString(8, actor.name)
            insert.executeUpdate()
        }
    }

    /**
     * [input] with the internal copy of the dates (target_start, target_end) taken from the mapped custom fields of what the
     * lead will have after saving ([stored] + the custom values sent). Without mapping both are empty. Throws when the end is
     * before the start.
     */
    fun withStayDates(tenant: Connection, input: CrmLeadInput, stored: JsonObject, fields: List<CrmField> = emptyList()): CrmLeadInput {
        val known = fields.ifEmpty { CrmFields.all(tenant) }
        return CrmStay.withDates(input, CrmStay.load(tenant), known, stored)
    }

    private fun leadValues(input: CrmLeadInput): List<Any?> = listOf(
        input.stageId, input.interestId, input.source, input.language, input.message, input.details,
        input.targetStart, input.targetEnd, input.estimatedValue, input.followUpOn,
    )

    private fun ResultSet.lead() = CrmLead(
        id = getObject("id", UUID::class.java),
        customerId = getObject("customer_id", UUID::class.java),
        name = getString("display_name"),
        email = getString("primary_email"),
        phone = getString("primary_phone"),
        stageId = getObject("stage_id", UUID::class.java),
        interestId = getObject("interest_id", UUID::class.java),
        ownerUserIds = uuids("owner_ids"),
        source = getString("source"),
        language = getString("language"),
        message = getString("message"),
        details = getString("details"),
        targetStart = getObject("target_start", LocalDate::class.java),
        targetEnd = getObject("target_end", LocalDate::class.java),
        estimatedValue = getBigDecimal("estimated_value"),
        followUpOn = getObject("follow_up_on", LocalDate::class.java),
        stageChangedAt = getTimestamp("stage_changed_at").toInstant(),
        closedAt = getTimestamp("closed_at")?.toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
        suggestedOwnerUserId = getObject("suggested_owner_user_id", UUID::class.java),
        reviewReason = getString("review_reason"),
        archivedAt = getTimestamp("archived_at")?.toInstant(),
        restoredAt = getTimestamp("restored_at")?.toInstant(),
        customFields = CrmFields.parseStored(getString("custom_fields")),
        customerCode = getString("customer_code").orEmpty(),
    )

    // A uuid[] column as a list (empty for NULL).
    private fun ResultSet.uuids(column: String): List<UUID> =
        (getArray(column)?.array as? Array<*>).orEmpty().mapNotNull { it?.toString()?.let(UUID::fromString) }

    // Binds positional parameters; collections become uuid[] (used by "= ANY(?)").
    private fun PreparedStatement.bind(connection: Connection, params: List<Any?>) {
        params.forEachIndexed { index, value ->
            when (value) {
                null -> setObject(index + 1, null)
                is Collection<*> -> setArray(index + 1, connection.createArrayOf("uuid", value.toTypedArray()))
                is BigDecimal -> setBigDecimal(index + 1, value)
                is Boolean -> setBoolean(index + 1, value)
                is Int -> setInt(index + 1, value)
                is String -> setString(index + 1, value)
                else -> setObject(index + 1, value)
            }
        }
    }

    internal fun <T> transaction(tenant: Connection, block: () -> T): T {
        val previous = tenant.autoCommit
        tenant.autoCommit = false
        try {
            val result = block()
            tenant.commit()
            return result
        } catch (failure: Exception) {
            tenant.rollback()
            throw failure
        } finally {
            tenant.autoCommit = previous
        }
    }
}

// Names of users and interests for messages and screens.
class CrmNames(private val users: List<CrmUser>, private val interests: List<CrmInterest>) {
    /** Whether [id] is a user that can still be assigned to leads. */
    fun knows(id: UUID): Boolean = users.any { it.id == id }
    fun user(id: UUID?): String = id?.let { users.firstOrNull { user -> user.id == it }?.name ?: "Usuario sin acceso al CRM" } ?: CrmForms.Unassigned
    fun interest(id: UUID?): String = id?.let { interests.firstOrNull { interest -> interest.id == it }?.name } ?: CrmForms.NoInterest
}
