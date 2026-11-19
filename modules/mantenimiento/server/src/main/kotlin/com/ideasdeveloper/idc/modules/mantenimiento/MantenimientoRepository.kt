package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.FileTooLargeException
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.server.modules.StoredFileRef
import com.ideasdeveloper.idc.ui.UiFileRef
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Resultado de crear una orden: [created] es false cuando la clave de importación ya existía y se devolvió la orden de antes. */
data class OrderCreated(val id: UUID, val created: Boolean)

/** La orden que el generador creó para un plan. */
data class GeneratedOrder(val orderId: UUID, val number: Long, val title: String, val assigneeId: UUID?)

/**
 * Acceso a datos del módulo. Cada función es síncrona y recibe la conexión de la empresa: quien llama las ejecuta dentro de
 * withContext(Dispatchers.IO). Todo el SQL es parametrizado; las listas usan `= ANY(?)` con un arreglo uuid.
 */
object MantenimientoRepository {
    const val MaxFileBytes = 25L * 1024 * 1024
    const val MaxFilesPerOrder = 100
    const val MaxChecklistItems = 100
    const val MaxNoteLength = 5000
    const val MaxNotesPerOrder = 2000
    const val MaxPendingFiles = 20
    const val MaxPendingBytes = 200L * 1024 * 1024
    const val MaxPropertyName = 120
    private const val MaxListed = 500
    private const val MaxProperties = 500
    private val log = LoggerFactory.getLogger(MantenimientoRepository::class.java)
    private val NilId = UUID(0L, 0L)
    private const val ClosedStatuses = "('done', 'rejected', 'cancelled')"
    private const val WorkingStatuses = "('open', 'in_progress', 'on_hold')"

    /** Hoy en la zona horaria del servidor: el mismo día que usan los avisos y los filtros. */
    fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    fun <T> transaction(tenant: Connection, block: () -> T): T {
        val previous = tenant.autoCommit
        // Ya dentro de una transacción de quien llama: él decide cuándo confirmar.
        if (!previous) return block()
        tenant.autoCommit = false
        try {
            val result = block()
            tenant.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { tenant.rollback() }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            tenant.autoCommit = previous
        }
    }

    // ---- ayudas ----

    private fun PreparedStatement.bind(params: List<Any?>) {
        params.forEachIndexed { index, value ->
            when (value) {
                null -> setObject(index + 1, null)
                is java.sql.Array -> setArray(index + 1, value)
                is Instant -> setObject(index + 1, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
                else -> setObject(index + 1, value)
            }
        }
    }

    private fun <T> Connection.query(sql: String, params: List<Any?>, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            statement.bind(params)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
        }

    private fun Connection.update(sql: String, params: List<Any?>): Int =
        prepareStatement(sql).use { statement ->
            statement.bind(params)
            statement.executeUpdate()
        }

    private fun Connection.uuidArray(ids: Collection<UUID>): java.sql.Array = createArrayOf("uuid", ids.toTypedArray())

    private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun ResultSet.date(column: String): LocalDate? = getObject(column, LocalDate::class.java)

    /** Escapa los comodines de LIKE para que lo que escribe el usuario se busque tal cual. */
    private fun like(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun fail(message: String): Nothing = throw MantenimientoValidationException(message)

    private fun firstMessage(problems: List<String>) {
        if (problems.isNotEmpty()) fail(problems.first())
    }

    // ---- usuarios ----

    private const val AssignableSql = """
        u.is_active
        AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = u.id)
             OR EXISTS (SELECT 1 FROM application_user_permissions granted
                        WHERE granted.user_id = u.id AND granted.permission_id IN ('${MantenimientoModule.Execute}', '${MantenimientoModule.Manage}')))
    """

    /** Usuarios activos que pueden ser responsables: el dueño del negocio o quienes ejecutan o gestionan mantenimiento. */
    fun assignableUsers(tenant: Connection): List<MantenimientoUser> {
        class Row(val id: UUID, val role: String, val name: String, val email: String?)
        val rows = tenant.query(
            """
            SELECT u.id, u.postgres_role, COALESCE(NULLIF(btrim(u.display_name), ''), u.postgres_role) AS label, NULLIF(btrim(COALESCE(u.email, '')), '') AS email
            FROM application_users u
            WHERE $AssignableSql
            ORDER BY label, u.postgres_role
            LIMIT $MaxListed
            """.trimIndent(),
            emptyList(),
        ) { Row(it.uuid("id") ?: NilId, it.getString("postgres_role"), it.getString("label"), it.getString("email")) }
        val repeated = rows.groupingBy { it.name.lowercase() }.eachCount().filterValues { it > 1 }.keys
        // Dos personas con el mismo nombre se distinguen por su usuario.
        return rows.map { MantenimientoUser(it.id, if (it.name.lowercase() in repeated) "${it.name} (${it.role})" else it.name, it.email) }
    }

    fun isAssignable(tenant: Connection, userId: UUID): Boolean =
        tenant.query("SELECT 1 AS found FROM application_users u WHERE u.id = ? AND $AssignableSql", listOf(userId)) { it.getInt("found") }.isNotEmpty()

    /** Cualquier usuario activo por id (para avisos y notas), asignable o no. */
    fun userById(tenant: Connection, userId: UUID): MantenimientoUser? = tenant.query(
        "SELECT u.id, COALESCE(NULLIF(btrim(u.display_name), ''), u.postgres_role) AS label, NULLIF(btrim(COALESCE(u.email, '')), '') AS email FROM application_users u WHERE u.id = ?",
        listOf(userId),
    ) { MantenimientoUser(it.uuid("id") ?: NilId, it.getString("label"), it.getString("email")) }.firstOrNull()

    /** Usuarios activos que gestionan mantenimiento más el dueño del negocio: reciben los avisos de averías y proyectos nuevos. */
    fun managerIds(tenant: Connection): List<UUID> = tenant.query(
        """
        SELECT u.id FROM application_users u
        WHERE u.is_active
          AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = u.id)
               OR EXISTS (SELECT 1 FROM application_user_permissions granted WHERE granted.user_id = u.id AND granted.permission_id = ?))
        ORDER BY u.id
        LIMIT $MaxListed
        """.trimIndent(),
        listOf(MantenimientoModule.Manage),
    ) { it.uuid("id") ?: NilId }

    // ---- propiedades ----

    private fun property(rows: ResultSet) = Property(rows.uuid("id") ?: NilId, rows.getString("name"), rows.getInt("position"), rows.getBoolean("archived"))

    fun listProperties(tenant: Connection, includeArchived: Boolean = false): List<Property> = tenant.query(
        "SELECT id, name, position, archived FROM mantenimiento_properties${if (includeArchived) "" else " WHERE NOT archived"} ORDER BY archived, position, lower(name), id LIMIT $MaxProperties",
        emptyList(),
    ) { property(it) }

    fun getProperty(tenant: Connection, id: UUID): Property? =
        tenant.query("SELECT id, name, position, archived FROM mantenimiento_properties WHERE id = ?", listOf(id)) { property(it) }.firstOrNull()

    private fun cleanPropertyName(name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) fail("El nombre de la propiedad es obligatorio.")
        if (clean.length > MaxPropertyName) fail("El nombre de la propiedad admite hasta $MaxPropertyName caracteres.")
        return clean
    }

    fun addProperty(tenant: Connection, name: String): UUID {
        val clean = cleanPropertyName(name)
        val id = UUID.randomUUID()
        val inserted = tenant.update(
            "INSERT INTO mantenimiento_properties (id, name, position) VALUES (?, ?, (SELECT COALESCE(MAX(position), 0) + 1 FROM mantenimiento_properties)) ON CONFLICT DO NOTHING",
            listOf(id, clean),
        )
        if (inserted == 0) fail("Ya existe una propiedad con ese nombre.")
        return id
    }

    /** Devuelve false si la propiedad no existe. */
    fun renameProperty(tenant: Connection, id: UUID, name: String): Boolean {
        val clean = cleanPropertyName(name)
        val updated = try {
            tenant.update("UPDATE mantenimiento_properties SET name = ? WHERE id = ?", listOf(clean, id))
        } catch (failure: java.sql.SQLException) {
            if (failure.sqlState == "23505") fail("Ya existe una propiedad con ese nombre.")
            throw failure
        }
        return updated > 0
    }

    fun archiveProperty(tenant: Connection, id: UUID, archived: Boolean = true): Boolean {
        val updated = try {
            tenant.update("UPDATE mantenimiento_properties SET archived = ? WHERE id = ?", listOf(archived, id))
        } catch (failure: java.sql.SQLException) {
            if (failure.sqlState == "23505") fail("Ya existe una propiedad activa con ese nombre.")
            throw failure
        }
        return updated > 0
    }

    /** Sube o baja una propiedad activa un lugar (renumera las activas). */
    fun moveProperty(tenant: Connection, id: UUID, up: Boolean) {
        transaction(tenant) {
            val ids = tenant.query("SELECT id FROM mantenimiento_properties WHERE NOT archived ORDER BY position, lower(name), id FOR UPDATE", emptyList()) { it.uuid("id") ?: NilId }.toMutableList()
            val index = ids.indexOf(id)
            if (index < 0) return@transaction
            val target = if (up) index - 1 else index + 1
            if (target !in ids.indices) return@transaction
            ids[index] = ids[target].also { ids[target] = ids[index] }
            ids.forEachIndexed { position, propertyId -> tenant.update("UPDATE mantenimiento_properties SET position = ? WHERE id = ?", listOf(position + 1, propertyId)) }
        }
    }

    /** La propiedad activa con ese nombre (sin mayúsculas); si no existe, se crea. Para importaciones. */
    fun findOrCreateProperty(tenant: Connection, name: String): UUID {
        val clean = name.trim().take(MaxPropertyName).trim()
        if (clean.isEmpty()) fail("El nombre de la propiedad es obligatorio.")
        fun existing() = tenant.query("SELECT id FROM mantenimiento_properties WHERE lower(name) = lower(?) AND NOT archived", listOf(clean)) { it.uuid("id") ?: NilId }.firstOrNull()
        return existing() ?: run {
            tenant.update(
                "INSERT INTO mantenimiento_properties (id, name, position) VALUES (?, ?, (SELECT COALESCE(MAX(position), 0) + 1 FROM mantenimiento_properties)) ON CONFLICT DO NOTHING",
                listOf(UUID.randomUUID(), clean),
            )
            existing() ?: fail("No se pudo crear la propiedad.")
        }
    }

    // ---- activos ----

    private const val AssetSelect = """
        a.id, a.property_id, p.name AS property_name, a.name, a.category, a.location, a.notes, a.archived, a.created_at, a.updated_at
        FROM mantenimiento_assets a
        LEFT JOIN mantenimiento_properties p ON p.id = a.property_id
    """

    private fun asset(rows: ResultSet) = Asset(
        rows.uuid("id") ?: NilId, rows.uuid("property_id"), rows.getString("property_name"), rows.getString("name"), rows.getString("category"),
        rows.getString("location"), rows.getString("notes"), rows.getBoolean("archived"), rows.instant("created_at") ?: Instant.EPOCH, rows.instant("updated_at") ?: Instant.EPOCH,
    )

    fun listAssets(tenant: Connection, propertyId: UUID? = null, query: String? = null, includeArchived: Boolean = false, limit: Int = MaxListed): List<Asset> {
        val params = mutableListOf<Any?>()
        val where = mutableListOf<String>()
        if (!includeArchived) where += "NOT a.archived"
        if (propertyId != null) { where += "a.property_id = ?"; params += propertyId }
        val text = query?.trim()?.lowercase()?.take(200).orEmpty()
        if (text.isNotEmpty()) {
            val pattern = "%${like(text)}%"
            where += "(lower(a.name) LIKE ? OR lower(a.category) LIKE ? OR lower(a.location) LIKE ?)"
            params.addAll(listOf(pattern, pattern, pattern))
        }
        params += limit.coerceIn(1, MaxListed)
        return tenant.query(
            "SELECT $AssetSelect ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY lower(a.name), a.id LIMIT ?",
            params,
        ) { asset(it) }
    }

    fun getAsset(tenant: Connection, id: UUID): Asset? = tenant.query("SELECT $AssetSelect WHERE a.id = ?", listOf(id)) { asset(it) }.firstOrNull()

    private fun requireProperty(tenant: Connection, propertyId: UUID?) {
        if (propertyId != null && getProperty(tenant, propertyId) == null) fail("La propiedad ya no existe.")
    }

    fun createAsset(tenant: Connection, input: AssetInput): UUID {
        firstMessage(input.validate())
        requireProperty(tenant, input.propertyId)
        val id = UUID.randomUUID()
        tenant.update(
            "INSERT INTO mantenimiento_assets (id, property_id, name, category, location, notes) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(id, input.propertyId, input.name.trim(), input.category.trim(), input.location.trim(), input.notes.trim()),
        )
        return id
    }

    /** Devuelve false si el activo no existe. */
    fun updateAsset(tenant: Connection, id: UUID, input: AssetInput): Boolean {
        firstMessage(input.validate())
        requireProperty(tenant, input.propertyId)
        return tenant.update(
            "UPDATE mantenimiento_assets SET property_id = ?, name = ?, category = ?, location = ?, notes = ?, updated_at = now() WHERE id = ?",
            listOf(input.propertyId, input.name.trim(), input.category.trim(), input.location.trim(), input.notes.trim(), id),
        ) > 0
    }

    fun archiveAsset(tenant: Connection, id: UUID, archived: Boolean = true): Boolean =
        tenant.update("UPDATE mantenimiento_assets SET archived = ?, updated_at = now() WHERE id = ?", listOf(archived, id)) > 0

    /** El activo activo con ese nombre en esa propiedad (sin mayúsculas); si no existe, se crea. Para importaciones. */
    fun findOrCreateAsset(tenant: Connection, propertyId: UUID?, name: String): UUID {
        val clean = name.trim().take(AssetInput.MaxName).trim()
        if (clean.isEmpty()) fail("El nombre del activo es obligatorio.")
        fun existing() = tenant.query(
            "SELECT id FROM mantenimiento_assets WHERE lower(name) = lower(?) AND NOT archived AND property_id IS NOT DISTINCT FROM ? ORDER BY created_at, id LIMIT 1",
            listOf(clean, propertyId),
        ) { it.uuid("id") ?: NilId }.firstOrNull()
        return existing() ?: run {
            // El índice único (propiedad, nombre) evita duplicados si dos importaciones corren a la vez: se relee lo que ganó.
            tenant.update("INSERT INTO mantenimiento_assets (id, property_id, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", listOf(UUID.randomUUID(), propertyId, clean))
            existing() ?: fail("No se pudo crear el activo.")
        }
    }

    // ---- ajustes ----

    fun settings(tenant: Connection): MantenimientoSettings = tenant.query(
        "SELECT tareas_board_id, tareas_publisher_id, due_soon_days FROM mantenimiento_settings WHERE id = 1", emptyList(),
    ) { MantenimientoSettings(it.uuid("tareas_board_id"), it.uuid("tareas_publisher_id"), it.getInt("due_soon_days")) }
        .firstOrNull() ?: MantenimientoSettings(null, null, 1)

    fun saveDueSoonDays(tenant: Connection, days: Int) {
        if (days !in 0..30) fail("Los días de aviso por vencer van de 0 a 30.")
        tenant.update("UPDATE mantenimiento_settings SET due_soon_days = ?, updated_at = now() WHERE id = 1", listOf(days))
    }

    /** Guarda el tablero de Tareas y el usuario con cuyos permisos se publica; null en ambos apaga la integración. */
    fun saveTareasBoard(tenant: Connection, boardId: UUID?, publisherId: UUID?) {
        if ((boardId == null) != (publisherId == null)) fail("El tablero y el usuario que publica van juntos.")
        transaction(tenant) {
            tenant.update("UPDATE mantenimiento_settings SET tareas_board_id = ?, tareas_publisher_id = ?, updated_at = now() WHERE id = 1", listOf(boardId, publisherId))
            // Al encender la integración se publican las órdenes que ya están abiertas y asignadas.
            if (boardId != null) {
                tenant.update(
                    "UPDATE mantenimiento_orders SET tareas_sync_needed = TRUE WHERE archived_at IS NULL AND assignee_id IS NOT NULL AND status IN $WorkingStatuses AND NOT tareas_sync_needed",
                    emptyList(),
                )
            }
        }
    }

    // ---- planes ----

    private const val PlanSelect = """
        pl.id, pl.title, pl.description, pl.property_id, pr.name AS property_name, pl.asset_id, a.name AS asset_name, pl.frequency, pl.interval_days,
        pl.schedule_mode, pl.next_due, pl.lead_days, pl.default_assignee, pl.priority, pl.active, pl.created_by, pl.created_at, pl.updated_at,
        pl.anchor_date, pl.last_error_at
        FROM mantenimiento_plans pl
        LEFT JOIN mantenimiento_properties pr ON pr.id = pl.property_id
        LEFT JOIN mantenimiento_assets a ON a.id = pl.asset_id
    """

    private fun plan(rows: ResultSet) = Plan(
        id = rows.uuid("id") ?: NilId,
        title = rows.getString("title"),
        description = rows.getString("description"),
        propertyId = rows.uuid("property_id"),
        propertyName = rows.getString("property_name"),
        assetId = rows.uuid("asset_id"),
        assetName = rows.getString("asset_name"),
        frequency = PlanFrequency.fromId(rows.getString("frequency")) ?: PlanFrequency.Monthly,
        intervalDays = rows.getInt("interval_days").takeUnless { rows.wasNull() },
        scheduleMode = ScheduleMode.fromId(rows.getString("schedule_mode")) ?: ScheduleMode.Fixed,
        nextDue = rows.date("next_due"),
        leadDays = rows.getInt("lead_days"),
        defaultAssignee = rows.uuid("default_assignee"),
        priority = OrderPriority.fromId(rows.getString("priority")) ?: OrderPriority.Medium,
        active = rows.getBoolean("active"),
        createdBy = rows.uuid("created_by"),
        createdAt = rows.instant("created_at") ?: Instant.EPOCH,
        updatedAt = rows.instant("updated_at") ?: Instant.EPOCH,
        anchorDate = rows.date("anchor_date"),
        lastErrorAt = rows.instant("last_error_at"),
    )

    /** Planes ordenados por próxima fecha (los que esperan su fecha, al final). [nextDueOnOrBefore] limita a los que vencen pronto. */
    fun listPlans(
        tenant: Connection,
        active: Boolean? = null,
        propertyId: UUID? = null,
        assetId: UUID? = null,
        nextDueOnOrBefore: LocalDate? = null,
        limit: Int = MaxListed,
    ): List<Plan> {
        val params = mutableListOf<Any?>()
        val where = mutableListOf<String>()
        if (active != null) { where += "pl.active = ?"; params += active }
        if (propertyId != null) { where += "pl.property_id = ?"; params += propertyId }
        if (assetId != null) { where += "pl.asset_id = ?"; params += assetId }
        if (nextDueOnOrBefore != null) { where += "pl.next_due IS NOT NULL AND pl.next_due <= ?"; params += nextDueOnOrBefore }
        params += limit.coerceIn(1, MaxListed)
        return tenant.query(
            "SELECT $PlanSelect ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY (pl.next_due IS NULL), pl.next_due, lower(pl.title), pl.id LIMIT ?",
            params,
        ) { plan(it) }
    }

    fun getPlan(tenant: Connection, id: UUID): Plan? = tenant.query("SELECT $PlanSelect WHERE pl.id = ?", listOf(id)) { plan(it) }.firstOrNull()

    fun planChecklist(tenant: Connection, planId: UUID): List<PlanCheck> = tenant.query(
        "SELECT id, plan_id, text, position FROM mantenimiento_plan_checklist WHERE plan_id = ? ORDER BY position, id",
        listOf(planId),
    ) { PlanCheck(it.uuid("id") ?: NilId, it.uuid("plan_id") ?: NilId, it.getString("text"), it.getInt("position")) }

    private fun resolveRefs(tenant: Connection, propertyId: UUID?, assetId: UUID?, assigneeId: UUID?): UUID? {
        var property = propertyId
        requireProperty(tenant, propertyId)
        if (assetId != null) {
            val rows = tenant.query("SELECT property_id FROM mantenimiento_assets WHERE id = ?", listOf(assetId)) { it.uuid("property_id") }
            if (rows.isEmpty()) fail("El activo ya no existe.")
            if (property == null) property = rows.first()
        }
        if (assigneeId != null && !isAssignable(tenant, assigneeId)) fail("Esa persona no puede ser responsable de órdenes de mantenimiento.")
        return property
    }

    private fun replacePlanChecklist(tenant: Connection, planId: UUID, texts: List<String>) {
        tenant.update("DELETE FROM mantenimiento_plan_checklist WHERE plan_id = ?", listOf(planId))
        texts.map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, text ->
            tenant.update("INSERT INTO mantenimiento_plan_checklist (id, plan_id, text, position) VALUES (?, ?, ?, ?)", listOf(UUID.randomUUID(), planId, text, index + 1))
        }
    }

    fun createPlan(tenant: Connection, input: PlanInput, actorId: UUID?): UUID {
        firstMessage(input.validate())
        val id = UUID.randomUUID()
        transaction(tenant) {
            val property = resolveRefs(tenant, input.propertyId, input.assetId, input.defaultAssignee)
            tenant.update(
                """
                INSERT INTO mantenimiento_plans (id, title, description, property_id, asset_id, frequency, interval_days, schedule_mode, next_due, anchor_date, lead_days, default_assignee, priority, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                listOf(
                    id, input.title.trim(), input.description.trim(), property, input.assetId, input.frequency.id, input.intervalDays, input.scheduleMode.id,
                    input.nextDue, input.nextDue, input.leadDays, input.defaultAssignee, input.priority.id, actorId,
                ),
            )
            replacePlanChecklist(tenant, id, input.checklist)
        }
        return id
    }

    /** Edita el plan y reemplaza su lista de comprobación. Devuelve false si el plan no existe. */
    fun updatePlan(tenant: Connection, id: UUID, input: PlanInput): Boolean = transaction(tenant) {
        tenant.query("SELECT id FROM mantenimiento_plans WHERE id = ? FOR UPDATE", listOf(id)) { 1 }.firstOrNull() ?: return@transaction false
        val current = getPlan(tenant, id) ?: return@transaction false
        // Un plan "desde el último cierre" sin fecha espera a que se cierre su orden: no se le exige una.
        val waiting = current.nextDue == null && current.scheduleMode == ScheduleMode.Floating
        firstMessage(input.validate(requireNextDue = !waiting))
        val property = resolveRefs(tenant, input.propertyId, input.assetId, input.defaultAssignee)
        tenant.update(
            """
            UPDATE mantenimiento_plans
            SET title = ?, description = ?, property_id = ?, asset_id = ?, frequency = ?, interval_days = ?, schedule_mode = ?, next_due = ?,
                anchor_date = CASE WHEN ?::date IS NOT NULL AND ?::date IS DISTINCT FROM next_due THEN ?::date ELSE anchor_date END, last_error_at = NULL,
                lead_days = ?, default_assignee = ?, priority = ?, updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            listOf(
                input.title.trim(), input.description.trim(), property, input.assetId, input.frequency.id, input.intervalDays, input.scheduleMode.id,
                input.nextDue, input.nextDue, input.nextDue, input.nextDue, input.leadDays, input.defaultAssignee, input.priority.id, id,
            ),
        )
        replacePlanChecklist(tenant, id, input.checklist)
        true
    }

    fun setPlanActive(tenant: Connection, id: UUID, active: Boolean): Boolean =
        tenant.update("UPDATE mantenimiento_plans SET active = ?, updated_at = now() WHERE id = ?", listOf(active, id)) > 0

    /** Planes activos con fecha cuya anticipación ya llegó y sin orden abierta: lote del generador. */
    fun plansDueForGeneration(tenant: Connection, today: LocalDate, limit: Int = 200): List<Plan> = tenant.query(
        """
        SELECT $PlanSelect
        WHERE pl.active AND pl.next_due IS NOT NULL AND pl.next_due <= ?::date + 90 AND pl.next_due - pl.lead_days <= ?::date
          AND (pl.last_error_at IS NULL OR pl.last_error_at < now() - interval '6 hours')
          AND NOT EXISTS (SELECT 1 FROM mantenimiento_orders o WHERE o.plan_id = pl.id AND o.status NOT IN $ClosedStatuses)
        ORDER BY pl.next_due, pl.id
        LIMIT ?
        """.trimIndent(),
        listOf(today, today, limit.coerceIn(1, 1000)),
    ) { plan(it) }

    private fun hasOpenOrder(tenant: Connection, planId: UUID): Boolean =
        tenant.query("SELECT 1 AS found FROM mantenimiento_orders WHERE plan_id = ? AND status NOT IN $ClosedStatuses LIMIT 1", listOf(planId)) { it.getInt("found") }.isNotEmpty()

    /**
     * Crea la orden preventiva del plan (una transacción por plan) y deja la plan lista para la siguiente vez: ON CONFLICT evita
     * duplicar la orden de la misma fecha. Devuelve null si no tocaba crear nada o si la orden de esa fecha ya existía.
     */
    fun generateOrder(tenant: Connection, plan: Plan, today: LocalDate): GeneratedOrder? = transaction(tenant) {
        tenant.query("SELECT id FROM mantenimiento_plans WHERE id = ? FOR UPDATE", listOf(plan.id)) { 1 }.firstOrNull() ?: return@transaction null
        // Se relee con el plan bloqueado: otro trabajo pudo haberlo generado ya.
        val current = getPlan(tenant, plan.id) ?: return@transaction null
        if (!current.active || !PlanSchedule.due(current.nextDue, current.leadDays, today, hasOpenOrder(tenant, current.id))) return@transaction null
        val due = current.nextDue ?: return@transaction null
        val assignee = current.defaultAssignee?.takeIf { isAssignable(tenant, it) }
        val id = UUID.randomUUID()
        val created = tenant.query(
            """
            INSERT INTO mantenimiento_orders (id, kind, status, priority, title, description, property_id, asset_id, plan_id, plan_due, due_date, assignee_id, requested_by_name)
            VALUES (?, 'preventive', 'open', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Plan preventivo')
            ON CONFLICT (plan_id, plan_due) WHERE plan_id IS NOT NULL AND plan_due IS NOT NULL DO NOTHING
            RETURNING number
            """.trimIndent(),
            listOf(id, current.priority.id, current.title, current.description, current.propertyId, current.assetId, current.id, due, due, assignee),
        ) { it.getLong("number") }.firstOrNull()
        if (created != null) {
            tenant.update(
                "INSERT INTO mantenimiento_order_checklist (id, order_id, text, position) SELECT gen_random_uuid(), ?, text, position FROM mantenimiento_plan_checklist WHERE plan_id = ?",
                listOf(id, current.id),
            )
            insertNote(tenant, id, "system", "Generada por el plan preventivo.", MantenimientoActor(null, "Plan preventivo"))
            markSync(tenant, id)
        }
        // La fecha siguiente se calcula siempre: si la orden de esa fecha ya existía, el plan no debe quedarse atascado en ella.
        tenant.update(
            "UPDATE mantenimiento_plans SET next_due = ?, anchor_date = COALESCE(anchor_date, ?), last_error_at = NULL, updated_at = now() WHERE id = ?",
            listOf(PlanSchedule.afterGeneration(due, current.frequency, current.intervalDays, current.scheduleMode, today, current.anchorDate ?: due), due, current.id),
        )
        created?.let { GeneratedOrder(id, it, current.title, assignee) }
    }

    /** El generador falló con este plan: se excluye del lote 6 horas para que no bloquee a los demás. */
    fun markPlanError(tenant: Connection, planId: UUID) {
        tenant.update("UPDATE mantenimiento_plans SET last_error_at = now() WHERE id = ?", listOf(planId))
    }

    // ---- órdenes ----

    private val OrderSelect = """
        o.id, o.number, o.kind, o.status, o.priority, o.title, o.description, o.property_id, pr.name AS property_name, o.asset_id, a.name AS asset_name,
        o.plan_id, o.plan_due, o.due_date, o.assignee_id, o.requested_by, o.requested_by_name, o.department, o.quote_amount, o.quote_currency, o.quoted_by,
        o.cost_amount, o.cost_currency, o.resolution, o.rejected_reason, o.approved_at, o.approved_by, o.completed_at, o.completed_by, o.tareas_task_id,
        o.purchase_request_id, o.purchase_folio, o.purchase_linking_at, o.created_by, o.created_at, o.updated_at, o.archived_at,
        (SELECT count(*) FROM mantenimiento_order_checklist c WHERE c.order_id = o.id AND c.done) AS checks_done,
        (SELECT count(*) FROM mantenimiento_order_checklist c WHERE c.order_id = o.id) AS checks_total
        FROM mantenimiento_orders o
        LEFT JOIN mantenimiento_properties pr ON pr.id = o.property_id
        LEFT JOIN mantenimiento_assets a ON a.id = o.asset_id
    """.trimIndent()

    private fun order(rows: ResultSet) = Order(
        id = rows.uuid("id") ?: NilId,
        number = rows.getLong("number"),
        kind = OrderKind.fromId(rows.getString("kind")) ?: OrderKind.Corrective,
        status = OrderStatus.fromId(rows.getString("status")) ?: OrderStatus.Open,
        priority = OrderPriority.fromId(rows.getString("priority")) ?: OrderPriority.Medium,
        title = rows.getString("title"),
        description = rows.getString("description"),
        propertyId = rows.uuid("property_id"),
        propertyName = rows.getString("property_name"),
        assetId = rows.uuid("asset_id"),
        assetName = rows.getString("asset_name"),
        planId = rows.uuid("plan_id"),
        planDue = rows.date("plan_due"),
        dueDate = rows.date("due_date"),
        assigneeId = rows.uuid("assignee_id"),
        requestedBy = rows.uuid("requested_by"),
        requestedByName = rows.getString("requested_by_name"),
        department = rows.getString("department"),
        quoteAmount = rows.getBigDecimal("quote_amount"),
        quoteCurrency = rows.getString("quote_currency"),
        quotedBy = rows.getString("quoted_by"),
        costAmount = rows.getBigDecimal("cost_amount"),
        costCurrency = rows.getString("cost_currency"),
        resolution = rows.getString("resolution"),
        rejectedReason = rows.getString("rejected_reason"),
        approvedAt = rows.instant("approved_at"),
        approvedBy = rows.uuid("approved_by"),
        completedAt = rows.instant("completed_at"),
        completedBy = rows.uuid("completed_by"),
        tareasTaskId = rows.uuid("tareas_task_id"),
        purchaseRequestId = rows.uuid("purchase_request_id"),
        purchaseFolio = rows.getString("purchase_folio"),
        purchaseLinkingAt = rows.instant("purchase_linking_at"),
        createdBy = rows.uuid("created_by"),
        createdAt = rows.instant("created_at") ?: Instant.EPOCH,
        updatedAt = rows.instant("updated_at") ?: Instant.EPOCH,
        archivedAt = rows.instant("archived_at"),
        checklistDone = rows.getInt("checks_done"),
        checklistTotal = rows.getInt("checks_total"),
    )

    /** La condición SQL de visibilidad del [scope] sobre la tabla con alias [alias] y sus parámetros. Pura: es la misma regla de [MantenimientoScope.seesOrder]. */
    fun visibleClause(scope: MantenimientoScope, alias: String = "o"): Pair<String, List<Any?>> = when {
        scope.seesAll -> "TRUE" to emptyList()
        scope.userId == null -> "FALSE" to emptyList()
        else -> "($alias.requested_by = ? OR $alias.assignee_id = ?)" to listOf(scope.userId, scope.userId)
    }

    private val PriorityRank = "CASE o.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END"

    /** Las órdenes que el [scope] ve (el filtro de visibilidad va en SQL), sin las archivadas. */
    fun listOrders(tenant: Connection, scope: MantenimientoScope, filter: OrderFilter = OrderFilter()): List<Order> {
        val params = mutableListOf<Any?>()
        val where = mutableListOf("o.archived_at IS NULL")
        val (visible, visibleParams) = visibleClause(scope)
        where += visible
        params.addAll(visibleParams)
        filter.status?.let { where += "o.status = ?"; params += it.id }
        if (filter.activeOnly) where += "o.status NOT IN $ClosedStatuses"
        filter.kind?.let { where += "o.kind = ?"; params += it.id }
        filter.propertyId?.let { where += "o.property_id = ?"; params += it }
        filter.assetId?.let { where += "o.asset_id = ?"; params += it }
        filter.assigneeId?.let { where += "o.assignee_id = ?"; params += it }
        filter.planId?.let { where += "o.plan_id = ?"; params += it }
        filter.mineOf?.let {
            where += "(o.assignee_id = ? OR o.requested_by = ?) AND o.status NOT IN $ClosedStatuses"
            params.addAll(listOf(it, it))
        }
        filter.dueOnOrBefore?.let { where += "o.due_date IS NOT NULL AND o.due_date <= ?"; params += it }
        val text = filter.query?.trim()?.lowercase()?.take(200).orEmpty()
        if (text.isNotEmpty()) {
            val pattern = "%${like(text)}%"
            val number = MantenimientoFolio.parse(text)
            where += "(lower(o.title) LIKE ? OR lower(o.description) LIKE ?" + (if (number != null) " OR o.number = ?" else "") + ")"
            params.addAll(listOf(pattern, pattern))
            if (number != null) params += number
        }
        params += filter.limit.coerceIn(1, MaxListed)
        val sort = when (filter.sort) {
            OrderSort.Due -> "(o.due_date IS NULL), o.due_date, $PriorityRank, o.number DESC"
            OrderSort.Newest -> "o.created_at DESC, o.number DESC"
        }
        return tenant.query("SELECT $OrderSelect WHERE ${where.joinToString(" AND ")} ORDER BY $sort LIMIT ?", params) { order(it) }
    }

    /** Los contadores del encabezado de Próximos entre las órdenes que el [scope] ve. */
    fun orderMetrics(tenant: Connection, scope: MantenimientoScope, today: LocalDate, propertyId: UUID? = null): OrderMetrics {
        val params = mutableListOf<Any?>(today)
        val (visible, visibleParams) = visibleClause(scope)
        params.addAll(visibleParams)
        val property = if (propertyId != null) { params += propertyId; " AND o.property_id = ?" } else ""
        return tenant.query(
            """
            SELECT count(*) FILTER (WHERE o.status NOT IN $ClosedStatuses) AS open_count,
                   count(*) FILTER (WHERE o.status NOT IN $ClosedStatuses AND o.due_date < ?) AS overdue_count,
                   count(*) FILTER (WHERE o.status IN ('requested', 'quoting')) AS pending_count,
                   count(*) FILTER (WHERE o.status = 'done' AND o.completed_at >= date_trunc('month', now())) AS done_count
            FROM mantenimiento_orders o
            WHERE o.archived_at IS NULL AND $visible$property
            """.trimIndent(),
            params,
        ) { OrderMetrics(it.getInt("open_count"), it.getInt("overdue_count"), it.getInt("pending_count"), it.getInt("done_count")) }
            .firstOrNull() ?: OrderMetrics(0, 0, 0, 0)
    }

    /** Sin comprobar permisos: quien llama aplica [MantenimientoScope.seesOrder]. */
    fun getOrder(tenant: Connection, id: UUID): Order? = tenant.query("SELECT $OrderSelect WHERE o.id = ?", listOf(id)) { order(it) }.firstOrNull()

    fun getOrderByNumber(tenant: Connection, number: Long): Order? = tenant.query("SELECT $OrderSelect WHERE o.number = ?", listOf(number)) { order(it) }.firstOrNull()

    /** La orden solo si el [scope] la ve (la visibilidad va en SQL) y no está archivada. */
    fun getOrderVisible(tenant: Connection, scope: MantenimientoScope, id: UUID): Order? {
        val (visible, params) = visibleClause(scope)
        return tenant.query("SELECT $OrderSelect WHERE o.id = ? AND o.archived_at IS NULL AND $visible", listOf<Any?>(id) + params) { order(it) }.firstOrNull()
    }

    fun getOrderVisibleByNumber(tenant: Connection, scope: MantenimientoScope, number: Long): Order? {
        val (visible, params) = visibleClause(scope)
        return tenant.query("SELECT $OrderSelect WHERE o.number = ? AND o.archived_at IS NULL AND $visible", listOf<Any?>(number) + params) { order(it) }.firstOrNull()
    }

    fun getOrderByImportKey(tenant: Connection, key: String): Order? = tenant.query("SELECT $OrderSelect WHERE o.import_key = ?", listOf(key)) { order(it) }.firstOrNull()

    private fun lockOrder(tenant: Connection, id: UUID): Order? {
        tenant.query("SELECT id FROM mantenimiento_orders WHERE id = ? FOR UPDATE", listOf(id)) { 1 }.firstOrNull() ?: return null
        return getOrder(tenant, id)
    }

    private fun cleanOrder(input: OrderInput): OrderInput {
        val clean = input.copy(
            title = input.title.trim(),
            description = input.description.trim(),
            department = input.department.trim(),
            quotedBy = input.quotedBy.trim(),
            resolution = input.resolution.trim(),
            quoteCurrency = input.quoteCurrency.trim().uppercase(),
            costCurrency = input.costCurrency.trim().uppercase(),
        )
        firstMessage(clean.validate())
        return clean
    }

    /** Marca la orden para que el trabajo tareas-sync la refleje en Tareas (solo si hay tablero configurado y la orden tiene responsable o tarea). */
    fun markSync(tenant: Connection, orderId: UUID) {
        tenant.update(
            """
            UPDATE mantenimiento_orders SET tareas_sync_needed = TRUE
            WHERE id = ? AND (assignee_id IS NOT NULL OR tareas_task_id IS NOT NULL)
              AND EXISTS (SELECT 1 FROM mantenimiento_settings s WHERE s.tareas_board_id IS NOT NULL)
            """.trimIndent(),
            listOf(orderId),
        )
    }

    /**
     * Crea una orden: las correctivas y preventivas nacen abiertas y los proyectos esperan aprobación. Con [importKey] la operación es
     * idempotente (devuelve la orden que ya la tenía con created=false). No manda avisos: eso lo hace quien llama.
     */
    fun createOrder(tenant: Connection, input: OrderInput, actor: MantenimientoActor, importKey: String? = null): OrderCreated {
        val clean = cleanOrder(input)
        val key = importKey?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        return transaction(tenant) {
            val property = resolveRefs(tenant, clean.propertyId, clean.assetId, clean.assigneeId)
            val id = UUID.randomUUID()
            val inserted = tenant.update(
                """
                INSERT INTO mantenimiento_orders (id, kind, status, priority, title, description, property_id, asset_id, due_date, assignee_id, requested_by, requested_by_name,
                    department, quote_amount, quote_currency, quoted_by, cost_amount, cost_currency, resolution, import_key, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(
                    id, clean.kind.id, OrderTransitions.initial(clean.kind).id, clean.priority.id, clean.title, clean.description, property, clean.assetId, clean.dueDate,
                    clean.assigneeId, actor.userId, actor.name.trim().take(120), clean.department, clean.quoteAmount, clean.quoteCurrency, clean.quotedBy, clean.costAmount,
                    clean.costCurrency, clean.resolution, key, actor.userId,
                ),
            )
            if (inserted == 0) {
                val existing = key?.let { getOrderByImportKey(tenant, it) } ?: fail("No se pudo crear la orden.")
                return@transaction OrderCreated(existing.id, false)
            }
            insertNote(tenant, id, "system", if (clean.kind == OrderKind.Project) "Proyecto solicitado." else "Orden creada.", actor)
            if (clean.assigneeId != null) {
                insertNote(tenant, id, "assign", "Asignada a ${userLabel(tenant, clean.assigneeId)}.", actor)
                markSync(tenant, id)
            }
            OrderCreated(id, true)
        }
    }

    private fun userLabel(tenant: Connection, userId: UUID): String = userById(tenant, userId)?.label ?: "otro usuario"

    /**
     * Edita los datos de la orden (permiso de gestionar; el tipo no cambia). Devuelve la orden actualizada o null si no existe; el
     * responsable nuevo queda anotado. Quien llama compara con la orden anterior para los avisos.
     */
    fun updateOrder(tenant: Connection, id: UUID, input: OrderInput, actor: MantenimientoActor): Order? {
        val clean = cleanOrder(input)
        return transaction(tenant) {
            val before = lockOrder(tenant, id) ?: return@transaction null
            val property = resolveRefs(tenant, clean.propertyId, clean.assetId, clean.assigneeId.takeIf { it != before.assigneeId })
            tenant.update(
                """
                UPDATE mantenimiento_orders
                SET title = ?, description = ?, property_id = ?, asset_id = ?, priority = ?, due_date = ?, assignee_id = ?, department = ?,
                    quote_amount = ?, quote_currency = ?, quoted_by = ?, cost_amount = ?, cost_currency = ?, resolution = ?, updated_at = now()
                WHERE id = ?
                """.trimIndent(),
                listOf(
                    clean.title, clean.description, property, clean.assetId, clean.priority.id, clean.dueDate, clean.assigneeId, clean.department, clean.quoteAmount,
                    clean.quoteCurrency, clean.quotedBy, clean.costAmount, clean.costCurrency, clean.resolution, id,
                ),
            )
            if (clean.assigneeId != before.assigneeId) insertNote(tenant, id, "assign", assignText(tenant, clean.assigneeId), actor)
            markSync(tenant, id)
            getOrder(tenant, id)
        }
    }

    private fun assignText(tenant: Connection, assigneeId: UUID?): String =
        if (assigneeId == null) "Se quitó el responsable." else "Asignada a ${userLabel(tenant, assigneeId)}."

    /** Costo real y resolución (quien trabaja la orden). Devuelve false si la orden no existe. */
    fun updateWork(tenant: Connection, id: UUID, costAmount: BigDecimal?, costCurrency: String, resolution: String): Boolean {
        val currency = costCurrency.trim().uppercase()
        val probe = OrderInput(title = "-", costAmount = costAmount, costCurrency = currency, resolution = resolution.trim())
        firstMessage(probe.validate())
        return tenant.update(
            "UPDATE mantenimiento_orders SET cost_amount = ?, cost_currency = ?, resolution = ?, updated_at = now() WHERE id = ?",
            listOf(costAmount, currency, resolution.trim(), id),
        ) > 0
    }

    /** Asigna (o quita con null) el responsable; solo a usuarios asignables. Devuelve la orden actualizada o null si no existe. */
    fun assignOrder(tenant: Connection, id: UUID, assigneeId: UUID?, actor: MantenimientoActor): Order? = transaction(tenant) {
        lockOrder(tenant, id) ?: return@transaction null
        if (assigneeId != null && !isAssignable(tenant, assigneeId)) fail("Esa persona no puede ser responsable de órdenes de mantenimiento.")
        tenant.update("UPDATE mantenimiento_orders SET assignee_id = ?, updated_at = now() WHERE id = ?", listOf(assigneeId, id))
        insertNote(tenant, id, "assign", assignText(tenant, assigneeId), actor)
        markSync(tenant, id)
        getOrder(tenant, id)
    }

    /** Tomar una orden de trabajo sin responsable: atómico, true solo si esta llamada la tomó. */
    fun takeOrder(tenant: Connection, id: UUID, actor: MantenimientoActor): Boolean {
        val userId = actor.userId ?: return false
        return transaction(tenant) {
            val taken = tenant.update(
                "UPDATE mantenimiento_orders SET assignee_id = ?, updated_at = now() WHERE id = ? AND assignee_id IS NULL AND status IN $WorkingStatuses AND archived_at IS NULL",
                listOf(userId, id),
            ) > 0
            if (taken) {
                insertNote(tenant, id, "assign", "${actor.name.trim().ifEmpty { "Alguien" }} tomó la orden.", actor)
                markSync(tenant, id)
            }
            taken
        }
    }

    /**
     * Cambia el estado con las reglas de [OrderTransitions] y los permisos del [scope]. Anota el cambio, guarda quién aprobó o completó,
     * exige el motivo al rechazar y mueve la fecha de un plan "desde el último cierre". Devuelve la orden actualizada.
     */
    fun changeStatus(
        tenant: Connection,
        id: UUID,
        to: OrderStatus,
        actor: MantenimientoActor,
        scope: MantenimientoScope,
        reason: String = "",
        today: LocalDate = today(),
    ): Order = transaction(tenant) {
        val current = lockOrder(tenant, id)?.takeIf { scope.seesOrder(it) } ?: fail("La orden ya no existe.")
        if (!OrderTransitions.allowed(current.kind, current.status, to, TransitionRole.of(scope, current))) {
            fail("No puedes pasar la orden de ${current.status.label} a ${to.label}.")
        }
        // Reabrir una orden de un plan que ya generó otra abierta rompería "una sola orden abierta por plan".
        if (current.status == OrderStatus.Done && current.planId != null && hasOtherOpenOrder(tenant, current.planId, current.id)) {
            fail("No se puede reabrir: el plan preventivo ya tiene otra orden abierta. Complétala o cancélala primero.")
        }
        val cleanReason = reason.trim()
        if (OrderTransitions.requiresReason(to) && cleanReason.isEmpty()) fail("Indica el motivo del rechazo.")
        if (cleanReason.length > OrderTransitions.MaxReason) fail("El motivo admite hasta ${OrderTransitions.MaxReason} caracteres.")
        val done = to == OrderStatus.Done
        val approving = OrderTransitions.isApproval(current.kind, current.status, to)
        val rejecting = to == OrderStatus.Rejected
        tenant.update(
            """
            UPDATE mantenimiento_orders
            SET status = ?,
                completed_at = CASE WHEN ?::boolean THEN now() ELSE NULL END,
                completed_by = CASE WHEN ?::boolean THEN ?::uuid ELSE NULL END,
                approved_at = CASE WHEN ?::boolean THEN now() ELSE approved_at END,
                approved_by = CASE WHEN ?::boolean THEN ?::uuid ELSE approved_by END,
                rejected_reason = CASE WHEN ?::boolean THEN ? ELSE rejected_reason END,
                updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            listOf(to.id, done, done, actor.userId, approving, approving, actor.userId, rejecting, cleanReason, id),
        )
        val note = "Estado: ${current.status.label} → ${to.label}." + if (cleanReason.isNotEmpty()) " Motivo: $cleanReason" else ""
        insertNote(tenant, id, "status", note.take(MaxNoteLength), actor)
        if (current.planId != null && (done || to == OrderStatus.Cancelled)) advanceFloatingPlan(tenant, current.planId, done, today)
        markSync(tenant, id)
        getOrder(tenant, id) ?: fail("La orden ya no existe.")
    }

    private fun hasOtherOpenOrder(tenant: Connection, planId: UUID, exceptId: UUID): Boolean = tenant.query(
        "SELECT 1 AS found FROM mantenimiento_orders WHERE plan_id = ? AND id <> ? AND status NOT IN $ClosedStatuses LIMIT 1",
        listOf(planId, exceptId),
    ) { it.getInt("found") }.isNotEmpty()

    // Plan "desde el último cierre" que espera su fecha: al completar cuenta desde hoy (el cierre), al cancelar también desde hoy.
    private fun advanceFloatingPlan(tenant: Connection, planId: UUID, closed: Boolean, today: LocalDate) {
        val plan = getPlan(tenant, planId) ?: return
        if (!plan.active || plan.scheduleMode != ScheduleMode.Floating || plan.nextDue != null) return
        val next = if (closed) PlanSchedule.afterClose(today, plan.frequency, plan.intervalDays) else PlanSchedule.afterCancel(today, plan.frequency, plan.intervalDays)
        // Desde el último cierre la nueva fecha es también el ancla.
        tenant.update("UPDATE mantenimiento_plans SET next_due = ?, anchor_date = ?, updated_at = now() WHERE id = ? AND next_due IS NULL", listOf(next, next, planId))
    }

    /** Guarda resolución y costo real (si se envían) y completa la orden, todo o nada. Exige poder trabajarla. */
    fun closeOrder(
        tenant: Connection,
        id: UUID,
        resolution: String?,
        costAmount: BigDecimal?,
        actor: MantenimientoActor,
        scope: MantenimientoScope,
        today: LocalDate = today(),
    ): Order = transaction(tenant) {
        val current = getOrder(tenant, id)?.takeIf { scope.seesOrder(it) } ?: fail("La orden ya no existe.")
        if (!scope.canWork(current)) fail("No puedes trabajar esta orden.")
        // La transición se valida antes de escribir nada (changeStatus la vuelve a comprobar con la fila bloqueada).
        if (!OrderTransitions.allowed(current.kind, current.status, OrderStatus.Done, TransitionRole.of(scope, current))) {
            fail("No puedes pasar la orden de ${current.status.label} a ${OrderStatus.Done.label}.")
        }
        if (resolution != null || costAmount != null) {
            updateWork(tenant, id, costAmount ?: current.costAmount, current.costCurrency, resolution ?: current.resolution)
        }
        changeStatus(tenant, id, OrderStatus.Done, actor, scope, today = today)
    }

    // ---- notas ----

    private fun insertNote(tenant: Connection, orderId: UUID, kind: String, body: String, actor: MantenimientoActor): UUID {
        val id = UUID.randomUUID()
        tenant.update(
            "INSERT INTO mantenimiento_order_notes (id, order_id, kind, body, author_id, author_name) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(id, orderId, kind, body, actor.userId, actor.name.trim().take(120)),
        )
        return id
    }

    /** Agrega una nota escrita (hasta [MaxNoteLength] caracteres y [MaxNotesPerOrder] por orden). */
    fun addNote(tenant: Connection, orderId: UUID, body: String, actor: MantenimientoActor): UUID {
        val clean = body.trim()
        if (clean.isEmpty()) fail("La nota no puede estar vacía.")
        if (clean.length > MaxNoteLength) fail("La nota admite hasta $MaxNoteLength caracteres.")
        return transaction(tenant) {
            lockOrder(tenant, orderId) ?: fail("La orden ya no existe.")
            val total = tenant.query("SELECT count(*) AS total FROM mantenimiento_order_notes WHERE order_id = ?", listOf(orderId)) { it.getInt("total") }.firstOrNull() ?: 0
            if (total >= MaxNotesPerOrder) fail("La orden ya tiene el máximo de $MaxNotesPerOrder notas.")
            insertNote(tenant, orderId, "note", clean, actor)
        }
    }

    /** Las últimas [limit] notas e historial, la más reciente primero. */
    fun listNotes(tenant: Connection, scope: MantenimientoScope, orderId: UUID, limit: Int = 100): List<OrderNote> {
        val (visible, params) = visibleClause(scope)
        return tenant.query(
            """
            SELECT n.id, n.order_id, n.kind, n.body, n.author_id, n.author_name, n.created_at
            FROM mantenimiento_order_notes n JOIN mantenimiento_orders o ON o.id = n.order_id
            WHERE n.order_id = ? AND o.archived_at IS NULL AND $visible
            ORDER BY n.created_at DESC, n.id LIMIT ?
            """.trimIndent(),
            listOf<Any?>(orderId) + params + limit.coerceIn(1, 500),
        ) { noteOf(it) }
    }

    private fun noteOf(it: ResultSet) = run {
        OrderNote(it.uuid("id") ?: NilId, it.uuid("order_id") ?: NilId, it.getString("kind"), it.getString("body"), it.uuid("author_id"), it.getString("author_name"), it.instant("created_at") ?: Instant.EPOCH)
    }

    // ---- lista de comprobación de la orden ----

    fun listChecklist(tenant: Connection, scope: MantenimientoScope, orderId: UUID): List<OrderCheck> {
        val (visible, params) = visibleClause(scope)
        return tenant.query(
            """
            SELECT c.id, c.order_id, c.text, c.done, c.position, c.done_by, c.done_at
            FROM mantenimiento_order_checklist c JOIN mantenimiento_orders o ON o.id = c.order_id
            WHERE c.order_id = ? AND o.archived_at IS NULL AND $visible
            ORDER BY c.position, c.id
            """.trimIndent(),
            listOf<Any?>(orderId) + params,
        ) { checkOf(it) }
    }

    /** Sin comprobar permisos: solo para trabajos e importaciones. */
    fun listChecklistUnscoped(tenant: Connection, orderId: UUID): List<OrderCheck> = tenant.query(
        "SELECT id, order_id, text, done, position, done_by, done_at FROM mantenimiento_order_checklist WHERE order_id = ? ORDER BY position, id",
        listOf(orderId),
    ) { checkOf(it) }

    private fun checkOf(it: ResultSet) = run {
        OrderCheck(it.uuid("id") ?: NilId, it.uuid("order_id") ?: NilId, it.getString("text"), it.getBoolean("done"), it.getInt("position"), it.uuid("done_by"), it.instant("done_at")) }

    fun addCheckItem(tenant: Connection, orderId: UUID, text: String): UUID {
        val clean = text.trim()
        if (clean.isEmpty()) fail("El elemento no puede estar vacío.")
        if (clean.length > PlanInput.MaxChecklistText) fail("Cada elemento admite hasta ${PlanInput.MaxChecklistText} caracteres.")
        return transaction(tenant) {
            lockOrder(tenant, orderId) ?: fail("La orden ya no existe.")
            val total = tenant.query("SELECT count(*) AS total FROM mantenimiento_order_checklist WHERE order_id = ?", listOf(orderId)) { it.getInt("total") }.firstOrNull() ?: 0
            if (total >= MaxChecklistItems) fail("La lista admite hasta $MaxChecklistItems elementos.")
            val id = UUID.randomUUID()
            tenant.update(
                "INSERT INTO mantenimiento_order_checklist (id, order_id, text, position) VALUES (?, ?, ?, (SELECT COALESCE(MAX(position), 0) + 1 FROM mantenimiento_order_checklist WHERE order_id = ?))",
                listOf(id, orderId, clean, orderId),
            )
            id
        }
    }

    /** Marca o desmarca un elemento de ESA orden; false si el elemento no pertenece a la orden. */
    fun setCheckDone(tenant: Connection, orderId: UUID, itemId: UUID, done: Boolean, userId: UUID?): Boolean = tenant.update(
        """
        UPDATE mantenimiento_order_checklist
        SET done = ?, done_by = CASE WHEN ?::boolean THEN ?::uuid ELSE NULL END, done_at = CASE WHEN ?::boolean THEN now() ELSE NULL END
        WHERE id = ? AND order_id = ?
        """.trimIndent(),
        listOf(done, done, userId, done, itemId, orderId),
    ) > 0

    fun removeCheckItem(tenant: Connection, orderId: UUID, itemId: UUID): Boolean =
        tenant.update("DELETE FROM mantenimiento_order_checklist WHERE id = ? AND order_id = ?", listOf(itemId, orderId)) > 0

    // ---- adjuntos ----

    class StoredAttachment(val attachment: Attachment, val open: () -> InputStream?)

    // Nombre sin carpetas ni caracteres de control. La carpeta se guarda aparte, normalizada a a/b/c.
    fun cleanFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.trim().take(255)

    fun cleanFilePath(path: String): String = path.replace('\\', '/').split('/')
        .map { part -> part.filter { it >= ' ' }.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")
        .take(500)

    /**
     * Guarda una subida que espera a que se guarde su formulario (order_id NULL) y devuelve la referencia que el formulario conserva.
     * El contenido va primero a [files]: si la fila no se puede guardar, se borra el archivo.
     */
    fun upload(tenant: Connection, files: ModuleFiles, name: String, path: String, contentType: String, content: InputStream, uploadedBy: UUID?): UiFileRef {
        val fileName = cleanFileName(name)
        if (fileName.isEmpty()) fail("El archivo no tiene nombre.")
        val type = contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" }
        // Las subidas que esperan su formulario se limitan por usuario para que formularios abandonados no llenen el almacenamiento.
        // El candado por usuario (hasta el fin de la transacción) hace que dos subidas simultáneas no pasen el tope a la vez:
        // el conteo y el insert quedan serializados.
        return transaction(tenant) {
            tenant.query("SELECT pg_advisory_xact_lock(hashtext(?))", listOf("mantenimiento-upload:" + (uploadedBy?.toString() ?: "none"))) { 1 }
            val pending = tenant.query(
                "SELECT count(*) AS total, COALESCE(sum(size), 0) AS bytes FROM mantenimiento_attachments WHERE order_id IS NULL AND NOT delete_pending AND uploaded_by IS NOT DISTINCT FROM ?",
                listOf(uploadedBy),
            ) { it.getInt("total") to it.getLong("bytes") }.firstOrNull() ?: (0 to 0L)
            if (pending.first >= MaxPendingFiles || pending.second >= MaxPendingBytes) fail("Tienes demasiados archivos sin guardar. Guarda o espera.")
            val stored = try {
                files.put(fileName, type, content, MaxFileBytes)
            } catch (_: FileTooLargeException) {
                fail("$fileName supera el máximo de ${MaxFileBytes / (1024 * 1024)} MB.")
            }
            val ref = UiFileRef(UUID.randomUUID().toString(), fileName, cleanFilePath(path), stored.size)
            try {
                tenant.update(
                    "INSERT INTO mantenimiento_attachments (id, name, path, content_type, size, backend, ref, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(UUID.fromString(ref.id), ref.name, ref.path, type, ref.size, stored.backend, stored.ref, uploadedBy),
                )
            } catch (failure: Exception) {
                runCatching { files.delete(stored) }
                throw failure
            }
            ref
        }
    }

    /** Enlaza a la orden las subidas pendientes de [uploaderId] y devuelve sus nombres. Las de otra persona o ya enlazadas se ignoran. */
    fun attach(tenant: Connection, orderId: UUID, ids: Collection<UUID>, uploaderId: UUID?): List<String> {
        val pending = ids.distinct()
        if (pending.isEmpty() || uploaderId == null) return emptyList()
        return transaction(tenant) {
            lockOrder(tenant, orderId) ?: fail("La orden ya no existe.")
            val total = tenant.query("SELECT count(*) AS total FROM mantenimiento_attachments WHERE order_id = ? AND NOT delete_pending", listOf(orderId)) { it.getInt("total") }.firstOrNull() ?: 0
            if (total + pending.size > MaxFilesPerOrder) fail("Una orden admite hasta $MaxFilesPerOrder archivos.")
            tenant.query(
                """
                UPDATE mantenimiento_attachments SET order_id = ?
                WHERE order_id IS NULL AND NOT delete_pending AND uploaded_by = ? AND id = ANY(?)
                RETURNING name
                """.trimIndent(),
                listOf(orderId, uploaderId, tenant.uuidArray(pending)),
            ) { it.getString("name") }
        }
    }

    private fun attachment(rows: ResultSet) = Attachment(
        rows.uuid("id") ?: NilId, rows.uuid("order_id"), rows.getString("name"), rows.getString("path"), rows.getString("content_type"),
        rows.getLong("size"), rows.uuid("uploaded_by"), rows.instant("created_at") ?: Instant.EPOCH,
    )

    /** Los adjuntos de la orden, solo si el [scope] la ve. */
    fun listAttachments(tenant: Connection, scope: MantenimientoScope, orderId: UUID): List<Attachment> {
        val (visible, params) = visibleClause(scope)
        return tenant.query(
            """
            SELECT t.id, t.order_id, t.name, t.path, t.content_type, t.size, t.uploaded_by, t.created_at
            FROM mantenimiento_attachments t JOIN mantenimiento_orders o ON o.id = t.order_id
            WHERE t.order_id = ? AND NOT t.delete_pending AND o.archived_at IS NULL AND $visible
            ORDER BY t.path, t.name, t.id
            """.trimIndent(),
            listOf<Any?>(orderId) + params,
        ) { attachment(it) }
    }

    /**
     * Un archivo que no está marcado para borrar y que el [scope] puede ver: el de una orden visible, o una subida pendiente
     * (sin orden) solo para quien la subió.
     */
    fun file(tenant: Connection, files: ModuleFiles, scope: MantenimientoScope, id: UUID): StoredAttachment? {
        class Row(val attachment: Attachment, val backend: String, val ref: String)
        val (visible, params) = visibleClause(scope)
        val userId = scope.userId
        val row = tenant.query(
            """
            SELECT t.id, t.order_id, t.name, t.path, t.content_type, t.size, t.uploaded_by, t.created_at, t.backend, t.ref
            FROM mantenimiento_attachments t LEFT JOIN mantenimiento_orders o ON o.id = t.order_id
            WHERE t.id = ? AND NOT t.delete_pending
              AND ((t.order_id IS NULL AND ?::uuid IS NOT NULL AND t.uploaded_by = ?::uuid)
                   OR (t.order_id IS NOT NULL AND o.archived_at IS NULL AND $visible))
            """.trimIndent(),
            listOf<Any?>(id, userId, userId) + params,
        ) { Row(attachment(it), it.getString("backend"), it.getString("ref")) }.firstOrNull() ?: return null
        val stored = StoredFileRef(row.backend, row.ref, row.attachment.size)
        return StoredAttachment(row.attachment) { files.open(stored) }
    }

    /** Marca archivos de la orden para borrar. El trabajo de limpieza quita el contenido después. */
    fun markDeleted(tenant: Connection, orderId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return tenant.update("UPDATE mantenimiento_attachments SET delete_pending = TRUE WHERE order_id = ? AND id = ANY(?)", listOf(orderId, tenant.uuidArray(ids.distinct())))
    }

    /** Borra el contenido y las filas de los adjuntos quitados y de las subidas cuyo formulario nunca se guardó en un día. */
    fun cleanup(tenant: Connection, files: ModuleFiles, batch: Int = 200) {
        class Doomed(val id: UUID, val stored: StoredFileRef)
        val doomed = tenant.query(
            """
            SELECT id, backend, ref, size FROM mantenimiento_attachments
            WHERE delete_pending OR (order_id IS NULL AND created_at < now() - INTERVAL '24 hours')
            ORDER BY created_at
            LIMIT ?
            """.trimIndent(),
            listOf(batch.coerceIn(1, 1000)),
        ) { Doomed(it.uuid("id") ?: NilId, StoredFileRef(it.getString("backend"), it.getString("ref"), it.getLong("size"))) }
        doomed.forEach { item ->
            try {
                // Se marca primero: así un attach() concurrente (que solo enlaza filas sin borrar) ya no puede tomarla. 0 filas = se enlazó antes.
                val claimed = tenant.update("UPDATE mantenimiento_attachments SET delete_pending = TRUE WHERE id = ? AND (order_id IS NULL OR delete_pending)", listOf(item.id))
                if (claimed == 0) return@forEach
                files.delete(item.stored)
                tenant.update("DELETE FROM mantenimiento_attachments WHERE id = ? AND delete_pending", listOf(item.id))
            } catch (failure: Exception) {
                // La fila se queda y se reintenta en la siguiente ejecución.
                log.warn("No se pudo borrar el adjunto {} de {}", item.id, files.companyCode, failure)
            }
        }
    }

    // ---- recordatorios ----

    /** Avisos sin enviar: órdenes de trabajo con responsable que vencen en [dueSoonDays] días ("due_soon") o antes de [today] ("overdue"). */
    fun reminderCandidates(tenant: Connection, today: LocalDate, dueSoonDays: Int, limit: Int = 200): List<ReminderCandidate> = tenant.query(
        """
        SELECT o.id, o.assignee_id, o.number, o.title, o.due_date,
               CASE WHEN o.due_date < ? THEN 'overdue' ELSE 'due_soon' END AS kind
        FROM mantenimiento_orders o
        JOIN application_users au ON au.id = o.assignee_id AND au.is_active
        WHERE o.archived_at IS NULL AND o.status IN $WorkingStatuses AND o.assignee_id IS NOT NULL
          AND (o.due_date < ? OR o.due_date BETWEEN ? AND ?)
          AND NOT EXISTS (
              SELECT 1 FROM mantenimiento_reminders r
              WHERE r.order_id = o.id AND r.user_id = o.assignee_id AND r.due_date = o.due_date
                AND r.kind = CASE WHEN o.due_date < ? THEN 'overdue' ELSE 'due_soon' END)
        ORDER BY o.due_date, o.id
        LIMIT ?
        """.trimIndent(),
        listOf(today, today, today, today.plusDays(dueSoonDays.coerceIn(0, 30).toLong()), today, limit.coerceIn(1, 1000)),
    ) {
        ReminderCandidate(it.uuid("id") ?: NilId, it.uuid("assignee_id") ?: NilId, it.getString("kind"), it.date("due_date") ?: today, it.getLong("number"), it.getString("title"))
    }

    /** Registra el aviso: true solo para quien lo insertó, así cada aviso se envía una vez. */
    fun claimReminder(tenant: Connection, orderId: UUID, userId: UUID, kind: String, dueDate: LocalDate): Boolean = tenant.update(
        "INSERT INTO mantenimiento_reminders (order_id, user_id, kind, due_date) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        listOf(orderId, userId, kind, dueDate),
    ) > 0

    // ---- integración con Compras ----

    /** Resultado de [reservePurchase]. */
    enum class PurchaseReservation { Reserved, AlreadyLinked, InProgress, Missing }

    /**
     * Reserva atómica para pedir la compra: Reserved solo si la orden no tiene solicitud y nadie la está pidiendo hace menos de
     * 5 minutos. Si no, distingue "ya tiene solicitud" de "en curso" (o la orden ya no existe).
     */
    fun reservePurchase(tenant: Connection, orderId: UUID): PurchaseReservation {
        val reserved = tenant.update(
            """
            UPDATE mantenimiento_orders SET purchase_linking_at = now()
            WHERE id = ? AND purchase_request_id IS NULL AND (purchase_linking_at IS NULL OR purchase_linking_at < now() - interval '5 minutes')
            """.trimIndent(),
            listOf(orderId),
        ) > 0
        if (reserved) return PurchaseReservation.Reserved
        val linked = tenant.query("SELECT purchase_request_id FROM mantenimiento_orders WHERE id = ?", listOf(orderId)) { it.uuid("purchase_request_id") }
        return when {
            linked.isEmpty() -> PurchaseReservation.Missing
            linked.first() != null -> PurchaseReservation.AlreadyLinked
            else -> PurchaseReservation.InProgress
        }
    }

    /** Guarda la solicitud creada y libera la reserva. */
    fun savePurchase(tenant: Connection, orderId: UUID, requestId: UUID, folio: String): Boolean = tenant.update(
        "UPDATE mantenimiento_orders SET purchase_request_id = ?, purchase_folio = ?, purchase_linking_at = NULL WHERE id = ?",
        listOf(requestId, folio.take(40), orderId),
    ) > 0

    /** Libera la reserva (también cuando la solicitud falló). */
    fun releasePurchase(tenant: Connection, orderId: UUID) {
        tenant.update("UPDATE mantenimiento_orders SET purchase_linking_at = NULL WHERE id = ?", listOf(orderId))
    }

    // ---- integración con Tareas ----

    /** Órdenes con la bandera tareas_sync_needed, las más antiguas primero. */
    fun syncCandidates(tenant: Connection, limit: Int = 50): List<Order> = tenant.query(
        "SELECT $OrderSelect WHERE o.tareas_sync_needed AND o.archived_at IS NULL ORDER BY o.updated_at, o.id LIMIT ?",
        listOf(limit.coerceIn(1, 500)),
    ) { order(it) }

    /** Guarda la tarea enlazada sin tocar updated_at (así [clearSyncFlag] sabe si la orden cambió mientras se publicaba). */
    fun setTareasTask(tenant: Connection, orderId: UUID, taskId: UUID) {
        tenant.update("UPDATE mantenimiento_orders SET tareas_task_id = ? WHERE id = ?", listOf(taskId, orderId))
    }

    /** Baja la bandera solo si la orden no cambió desde que se leyó ([readUpdatedAt]); si cambió, se vuelve a publicar. */
    fun clearSyncFlag(tenant: Connection, orderId: UUID, readUpdatedAt: Instant): Boolean = tenant.update(
        "UPDATE mantenimiento_orders SET tareas_sync_needed = FALSE WHERE id = ? AND updated_at = ?",
        listOf(orderId, readUpdatedAt),
    ) > 0
}
