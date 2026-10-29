package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModulePermissions
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
        l.id, l.customer_id, c.display_name, c.primary_email, c.primary_phone, l.stage_id, l.interest_id,
        l.owner_user_id, l.source, l.language, l.message, l.details, l.target_start, l.target_end,
        l.estimated_value, l.follow_up_on, l.stage_changed_at, l.closed_at, l.created_at
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
        return tenant.prepareStatement("DELETE FROM crm_stages WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() == 1 }
    }

    fun leadCount(tenant: Connection): Int =
        tenant.prepareStatement("SELECT count(*) FROM crm_leads").use { query -> query.executeQuery().use { rows -> rows.next(); rows.getInt(1) } }

    // Replaces every stage with a template. Only allowed while the company has no leads.
    fun applyTemplate(tenant: Connection, template: CrmTemplates.Template) = transaction(tenant) {
        if (leadCount(tenant) > 0) throw CrmValidationException("Las plantillas solo se aplican mientras no hay leads.")
        tenant.prepareStatement("DELETE FROM crm_stages").use { it.executeUpdate() }
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

    // Users that can own leads: active and either business owner or holder of a CRM permission.
    fun users(tenant: Connection): List<CrmUser> = tenant.prepareStatement(
        """
        SELECT users.id, users.postgres_role
        FROM application_users users
        WHERE users.is_active
          AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
               OR EXISTS (SELECT 1 FROM application_user_permissions granted
                          WHERE granted.user_id = users.id AND granted.permission_id LIKE 'crm.%'))
        ORDER BY users.postgres_role
        """.trimIndent()
    ).use { query ->
        query.executeQuery().use { rows -> buildList { while (rows.next()) add(CrmUser(rows.getObject(1, UUID::class.java), rows.getString(2))) } }
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

    fun actor(tenant: Connection, permissions: ModulePermissions): CrmActor {
        if (permissions.serverOwner || permissions.userId == null) return CrmActor(null, "Dueno del servidor")
        val name = tenant.prepareStatement("SELECT postgres_role FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, permissions.userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        return CrmActor(permissions.userId, name ?: "Usuario")
    }

    // ---- Leads ----

    private fun where(scope: CrmScope, filter: CrmLeadFilter): Pair<String, List<Any>> {
        val (visible, params) = scope.sqlCondition()
        val parts = mutableListOf(visible)
        val values = params.toMutableList()
        if (scope.interestsEnabled) {
            if (filter.withoutInterest) parts += "l.interest_id IS NULL"
            filter.interestId?.let { parts += "l.interest_id = ?"; values += it }
        }
        filter.ownerUserId?.let { parts += "l.owner_user_id = ?"; values += it }
        if (filter.unassigned) parts += "l.owner_user_id IS NULL"
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
        return tenant.prepareStatement("SELECT $LeadColumns $LeadFrom WHERE $condition ORDER BY l.created_at DESC LIMIT ? OFFSET ?").use { query ->
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
     * Creates a lead. The person is the existing customer with the same email when there is one
     * (its data is not changed); otherwise a new prospect customer is created in Clientes.
     */
    fun createLead(tenant: Connection, input: CrmLeadInput, stage: CrmStage, actor: CrmActor): UUID = transaction(tenant) {
        val customerId = tenant.prepareStatement(
            "SELECT id FROM customers WHERE lower(primary_email) = lower(?) AND status <> 'archived' ORDER BY created_at LIMIT 1"
        ).use { query ->
            query.setString(1, input.email)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: UUID.randomUUID().also { id ->
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
                customer_id, stage_id, interest_id, owner_user_id, source, language, message, details,
                target_start, target_end, estimated_value, follow_up_on, closed_at, id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP END, ?)
            """.trimIndent()
        ).use { insert ->
            insert.bind(tenant, listOf(customerId) + leadValues(input) + (stage.kind != StageKind.Open) + id)
            insert.executeUpdate()
        }
        addActivity(tenant, id, "created", "Lead creado en ${stage.name}.", actor, toStage = stage.id)
        id
    }

    /** Saves the lead form; contact changes are written to the shared customer. Logs what changed. */
    fun updateLead(tenant: Connection, lead: CrmLead, input: CrmLeadInput, stages: List<CrmStage>, names: CrmNames, actor: CrmActor) = transaction(tenant) {
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
            UPDATE crm_leads SET stage_id = ?, interest_id = ?, owner_user_id = ?, source = ?, language = ?, message = ?, details = ?,
                target_start = ?, target_end = ?, estimated_value = ?, follow_up_on = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent()
        ).use { update ->
            update.bind(tenant, leadValues(input) + lead.id)
            update.executeUpdate()
        }
        if (input.stageId != lead.stageId) {
            stages.firstOrNull { it.id == input.stageId }?.let { moveLead(tenant, lead, it, stages, actor) }
        }
        if (input.ownerUserId != lead.ownerUserId) {
            addActivity(tenant, lead.id, "owner", "Responsable: ${names.user(input.ownerUserId)}.", actor)
        }
        if (input.interestId != lead.interestId) {
            addActivity(tenant, lead.id, "interest", "Interes: ${names.interest(input.interestId)}.", actor)
        }
        val changed = listOf(
            input.name != lead.name, input.email != lead.email, input.phone != lead.phone, input.source != lead.source,
            input.language != lead.language, input.message != lead.message, input.details != lead.details,
            input.targetStart != lead.targetStart, input.targetEnd != lead.targetEnd,
            input.estimatedValue?.stripTrailingZeros() != lead.estimatedValue?.stripTrailingZeros(), input.followUpOn != lead.followUpOn,
        ).any { it }
        if (changed) addActivity(tenant, lead.id, "updated", "Datos del lead actualizados.", actor)
    }

    // Moves a lead to another stage, records the stage history and keeps closed_at in line with the stage kind.
    fun moveLead(tenant: Connection, lead: CrmLead, stage: CrmStage, stages: List<CrmStage>, actor: CrmActor) {
        if (stage.id == lead.stageId && lead.closedAt == null == (stage.kind == StageKind.Open)) return
        tenant.prepareStatement(
            """
            UPDATE crm_leads SET stage_id = ?, stage_changed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
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
    }

    fun addNote(tenant: Connection, leadId: UUID, body: String, actor: CrmActor) {
        addActivity(tenant, leadId, "note", body, actor)
        tenant.prepareStatement("UPDATE crm_leads SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, leadId); it.executeUpdate() }
    }

    // Deletes the lead and its activity; the customer stays in Clientes.
    fun deleteLead(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("DELETE FROM crm_leads WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() == 1 }

    fun activity(tenant: Connection, leadId: UUID, limit: Int): List<CrmActivity> = tenant.prepareStatement(
        """
        SELECT a.kind, a.body, fs.name, ts.name, a.author, a.created_at
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
                    CrmActivity(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getTimestamp(6).toInstant())
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

    private fun addActivity(
        tenant: Connection,
        leadId: UUID,
        kind: String,
        body: String,
        actor: CrmActor,
        fromStage: UUID? = null,
        toStage: UUID? = null,
    ) {
        tenant.prepareStatement(
            "INSERT INTO crm_activity (id, lead_id, kind, body, from_stage_id, to_stage_id, user_id, author) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
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

    private fun leadValues(input: CrmLeadInput): List<Any?> = listOf(
        input.stageId, input.interestId, input.ownerUserId, input.source, input.language, input.message, input.details,
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
        ownerUserId = getObject("owner_user_id", UUID::class.java),
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
    )

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

    private fun <T> transaction(tenant: Connection, block: () -> T): T {
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
    fun user(id: UUID?): String = id?.let { users.firstOrNull { user -> user.id == it }?.name ?: "Usuario sin acceso al CRM" } ?: CrmForms.Unassigned
    fun interest(id: UUID?): String = id?.let { interests.firstOrNull { interest -> interest.id == it }?.name } ?: CrmForms.NoInterest
}
