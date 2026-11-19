package com.ideasdeveloper.idc.modules.compras

import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * Todo el SQL del modulo. Cada funcion recibe la conexion de la empresa (`tenant`) y, cuando devuelve o
 * cambia solicitudes, el [Actor]: la visibilidad y los permisos se aplican aqui, no en la ruta.
 *
 * Las funciones son BLOQUEANTES (JDBC): las rutas y herramientas MCP las llaman dentro de
 * `withContext(Dispatchers.IO)`. Los errores de negocio son [ComprasValidationException]; los de base de
 * datos (SQLException) se dejan subir. Las escrituras multiples van en una transaccion.
 *
 * Columnas del Core usadas (base de la empresa): `application_users(id, postgres_role, display_name, email,
 * is_active)`, `business_owner(user_id)` y `application_user_permissions(user_id, permission_id)`.
 */
object ComprasRepository {
    const val MaxQuotesPerRequest = 50
    const val MaxPageSize = 200
    const val MaxOffset = 10_000

    private val AmountSql = ComprasVisibility.approvalAmountSql("r")
    private val CurrencySql = ComprasVisibility.approvalCurrencySql("r")

    private val RequestSelect = """
        SELECT r.id, r.number, r.title, r.description, r.department_id, d.name AS department_name,
               r.estimated_amount, r.currency, r.required_date, r.priority, r.status,
               r.requester_id, r.requester_name, r.decided_by, r.decided_by_name, r.decided_at, r.decision_reason,
               r.approved_amount, r.approved_currency, r.source_kind, r.source_ref, r.submitted_at, r.completed_at,
               r.created_at, r.updated_at, $AmountSql AS approval_amount, $CurrencySql AS approval_currency
        FROM compras_requests r
        LEFT JOIN compras_departments d ON d.id = r.department_id
    """.trimIndent()

    private fun ResultSet.request() = PurchaseRequest(
        id = getObject("id", UUID::class.java),
        number = getLong("number"),
        title = getString("title"),
        description = getString("description"),
        departmentId = getObject("department_id", UUID::class.java),
        departmentName = getString("department_name"),
        estimatedAmount = getBigDecimal("estimated_amount"),
        currency = getString("currency"),
        requiredDate = getObject("required_date", LocalDate::class.java),
        priority = Priority.fromId(getString("priority")) ?: Priority.Medium,
        status = RequestStatus.fromId(getString("status")) ?: RequestStatus.Draft,
        requesterId = getObject("requester_id", UUID::class.java),
        requesterName = getString("requester_name"),
        decidedBy = getObject("decided_by", UUID::class.java),
        decidedByName = getString("decided_by_name"),
        decidedAt = getTimestamp("decided_at")?.toInstant(),
        decisionReason = getString("decision_reason"),
        approvedAmount = getBigDecimal("approved_amount"),
        approvedCurrency = getString("approved_currency"),
        sourceKind = getString("source_kind"),
        sourceRef = getString("source_ref"),
        submittedAt = getTimestamp("submitted_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        approvalAmount = getBigDecimal("approval_amount"),
        approvalCurrency = getString("approval_currency"),
    )

    private fun notFound() = ComprasValidationException("La solicitud no existe o no tienes acceso a ella.")

    private fun changed() = ComprasValidationException("La solicitud cambio, recarga.")

    // ---- Ajustes ----

    fun settings(tenant: Connection): ComprasSettings = tenant.prepareStatement("SELECT default_currency FROM compras_settings WHERE id = 1").use { query ->
        query.executeQuery().use { rows -> ComprasSettings(if (rows.next()) rows.getString(1) else "USD") }
    }

    fun setDefaultCurrency(tenant: Connection, currency: String) {
        val code = ComprasMoney.currency(currency)
        tenant.prepareStatement(
            "INSERT INTO compras_settings (id, default_currency) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET default_currency = EXCLUDED.default_currency, updated_at = CURRENT_TIMESTAMP"
        ).use { upsert ->
            upsert.setString(1, code)
            upsert.executeUpdate()
        }
    }

    // ---- Departamentos ----

    fun departments(tenant: Connection, onlyActive: Boolean = false): List<Department> = tenant.prepareStatement(
        "SELECT id, name, active, sort_order FROM compras_departments WHERE (? = FALSE OR active) ORDER BY sort_order, lower(name)"
    ).use { query ->
        query.setBoolean(1, onlyActive)
        query.executeQuery().use { rows ->
            buildList { while (rows.next()) add(Department(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getBoolean(3), rows.getInt(4))) }
        }
    }

    /** Crea ([id] nulo) o renombra un departamento. Requiere compras.manage. Devuelve su id. */
    fun saveDepartment(tenant: Connection, actor: Actor, id: UUID?, name: String): UUID {
        requireManage(actor)
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 80) throw ComprasValidationException("El nombre del departamento debe tener entre 1 y 80 caracteres.")
        try {
            if (id == null) {
                val newId = UUID.randomUUID()
                tenant.prepareStatement(
                    "INSERT INTO compras_departments (id, name, sort_order) VALUES (?, ?, COALESCE((SELECT max(sort_order) + 1 FROM compras_departments), 0))"
                ).use { insert ->
                    insert.setObject(1, newId)
                    insert.setString(2, clean)
                    insert.executeUpdate()
                }
                return newId
            }
            val updated = tenant.prepareStatement("UPDATE compras_departments SET name = ? WHERE id = ?").use { update ->
                update.setString(1, clean)
                update.setObject(2, id)
                update.executeUpdate()
            }
            if (updated == 0) throw ComprasValidationException("El departamento no existe.")
            return id
        } catch (failure: SQLException) {
            if (failure.sqlState == "23505") throw ComprasValidationException("Ya existe un departamento con ese nombre.")
            throw failure
        }
    }

    /** Activa o desactiva un departamento (los desactivados no se ofrecen en solicitudes nuevas). Requiere manage. */
    fun setDepartmentActive(tenant: Connection, actor: Actor, id: UUID, active: Boolean) {
        requireManage(actor)
        val updated = tenant.prepareStatement("UPDATE compras_departments SET active = ? WHERE id = ?").use { update ->
            update.setBoolean(1, active)
            update.setObject(2, id)
            update.executeUpdate()
        }
        if (updated == 0) throw ComprasValidationException("El departamento no existe.")
    }

    private fun requireManage(actor: Actor) {
        if (!actor.can(ComprasModule.Manage)) throw ComprasValidationException("No tienes permiso para administrar compras.")
    }

    // ---- Usuarios y aprobadores ----

    private const val UserName = "COALESCE(NULLIF(trim(u.display_name), ''), u.postgres_role)"

    /**
     * Usuarios activos de la empresa para elegir aprobadores. Nombre = display_name con respaldo en
     * postgres_role; [CompanyUser.canApprove] = dueno del negocio o permiso compras.approve.
     */
    fun companyUsers(tenant: Connection): List<CompanyUser> = tenant.prepareStatement(
        """
        SELECT u.id, $UserName, COALESCE(u.email, ''),
               (EXISTS (SELECT 1 FROM business_owner o WHERE o.user_id = u.id)
                OR EXISTS (SELECT 1 FROM application_user_permissions g WHERE g.user_id = u.id AND g.permission_id = ?))
        FROM application_users u
        WHERE u.is_active
        ORDER BY lower($UserName)
        """.trimIndent()
    ).use { query ->
        query.setString(1, ComprasModule.Approve)
        query.executeQuery().use { rows ->
            buildList { while (rows.next()) add(CompanyUser(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getBoolean(4))) }
        }
    }

    private const val ApproverSelect = """
        SELECT a.user_id, COALESCE($UserName, 'Usuario eliminado'), a.max_amount, a.limit_currency, a.active
        FROM compras_approvers a
        LEFT JOIN application_users u ON u.id = a.user_id
    """

    private fun ResultSet.approver() = Approver(getObject(1, UUID::class.java), getString(2), getBigDecimal(3), getString(4), getBoolean(5))

    fun approvers(tenant: Connection): List<Approver> = tenant.prepareStatement("$ApproverSelect ORDER BY lower(COALESCE($UserName, ''))").use { query ->
        query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.approver()) } }
    }

    /** Fila de aprobador de [userId], o null si no esta designado. */
    fun approver(tenant: Connection, userId: UUID): Approver? = tenant.prepareStatement("$ApproverSelect WHERE a.user_id = ?").use { query ->
        query.setObject(1, userId)
        query.executeQuery().use { rows -> if (rows.next()) rows.approver() else null }
    }

    /** Fila de aprobador del actor (null si no tiene usuario o no esta designado). */
    fun approver(tenant: Connection, actor: Actor): Approver? = actor.userId?.let { approver(tenant, it) }

    /** Aprobadores cuyo usuario no tiene el permiso compras.approve (ni es dueno): aviso en Ajustes. */
    fun approversWithoutPermission(tenant: Connection): Set<UUID> = tenant.prepareStatement(
        """
        SELECT a.user_id FROM compras_approvers a
        WHERE NOT EXISTS (SELECT 1 FROM business_owner o WHERE o.user_id = a.user_id)
          AND NOT EXISTS (SELECT 1 FROM application_user_permissions g WHERE g.user_id = a.user_id AND g.permission_id = ?)
        """.trimIndent()
    ).use { query ->
        query.setString(1, ComprasModule.Approve)
        query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
    }

    /** Designa o actualiza un aprobador. [maxAmount] nulo = sin limite. Requiere manage; el usuario debe existir. */
    fun saveApprover(tenant: Connection, actor: Actor, userId: UUID, maxAmount: BigDecimal?, limitCurrency: String, active: Boolean) {
        requireManage(actor)
        val max = maxAmount?.let { ComprasMoney.check(it) }
        val currency = ComprasMoney.currency(limitCurrency)
        val exists = tenant.prepareStatement("SELECT 1 FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { it.next() }
        }
        if (!exists) throw ComprasValidationException("El usuario no existe.")
        tenant.prepareStatement(
            """
            INSERT INTO compras_approvers (user_id, max_amount, limit_currency, active, updated_by) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET max_amount = EXCLUDED.max_amount, limit_currency = EXCLUDED.limit_currency,
                active = EXCLUDED.active, updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by
            """.trimIndent()
        ).use { upsert ->
            upsert.setObject(1, userId)
            upsert.setBigDecimal(2, max)
            upsert.setString(3, currency)
            upsert.setBoolean(4, active)
            upsert.setString(5, actor.name.take(200))
            upsert.executeUpdate()
        }
    }

    fun removeApprover(tenant: Connection, actor: Actor, userId: UUID) {
        requireManage(actor)
        tenant.prepareStatement("DELETE FROM compras_approvers WHERE user_id = ?").use { delete ->
            delete.setObject(1, userId)
            delete.executeUpdate()
        }
    }

    // ---- Solicitudes ----

    /** Solicitud visible para [actor], o null si no existe o no la puede ver. */
    fun get(tenant: Connection, id: UUID, actor: Actor): PurchaseRequest? {
        val visible = ComprasVisibility.where(actor, approver(tenant, actor), "r")
        return tenant.prepareStatement("$RequestSelect WHERE r.id = ? AND (${visible.sql})").use { query ->
            query.setObject(1, id)
            ComprasSql.bind(query, visible.params, 2)
            query.executeQuery().use { rows -> if (rows.next()) rows.request() else null }
        }
    }

    /** Como [get] por folio ("C-00012" o 12). */
    fun getByNumber(tenant: Connection, number: Long, actor: Actor): PurchaseRequest? {
        val visible = ComprasVisibility.where(actor, approver(tenant, actor), "r")
        return tenant.prepareStatement("$RequestSelect WHERE r.number = ? AND (${visible.sql})").use { query ->
            query.setLong(1, number)
            ComprasSql.bind(query, visible.params, 2)
            query.executeQuery().use { rows -> if (rows.next()) rows.request() else null }
        }
    }

    // Sin visibilidad: solo para recargar lo que el propio codigo acaba de escribir con permiso comprobado.
    private fun load(tenant: Connection, id: UUID): PurchaseRequest? = tenant.prepareStatement("$RequestSelect WHERE r.id = ?").use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) rows.request() else null }
    }

    private fun idBySource(tenant: Connection, source: SourceRef): UUID? =
        tenant.prepareStatement("SELECT id FROM compras_requests WHERE source_kind = ? AND source_ref = ?").use { query ->
            query.setString(1, source.kind)
            query.setString(2, source.ref)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    private fun checkDepartment(tenant: Connection, departmentId: UUID?, current: UUID? = null) {
        if (departmentId == null || departmentId == current) return
        val active = tenant.prepareStatement("SELECT active FROM compras_departments WHERE id = ?").use { query ->
            query.setObject(1, departmentId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getBoolean(1) else null }
        }
        if (active == null) throw ComprasValidationException("El departamento no existe.")
        if (!active) throw ComprasValidationException("El departamento esta desactivado.")
    }

    /**
     * Crea una solicitud en borrador del actor (requiere compras.create). Con [source] es idempotente por
     * (kind, ref): si ya existe una para ese origen se devuelve esa. Registra el evento "created".
     */
    fun create(tenant: Connection, actor: Actor, draft: RequestDraft, source: SourceRef? = null): PurchaseRequest {
        if (!actor.can(ComprasModule.Create)) throw ComprasValidationException("No tienes permiso para crear solicitudes de compra.")
        val requesterId = actor.userId ?: throw ComprasValidationException("No se pudo identificar al usuario.")
        val clean = draft.validated()
        if (source != null) {
            if (source.kind.isBlank() || source.ref.isBlank() || source.kind.length > 100 || source.ref.length > 200) {
                throw ComprasValidationException("El origen de la solicitud no es valido.")
            }
            idBySource(tenant, source)?.let { existing -> return load(tenant, existing) ?: throw notFound() }
        }
        checkDepartment(tenant, clean.departmentId)
        return ComprasSql.transaction(tenant) {
            val id = UUID.randomUUID()
            val inserted = tenant.prepareStatement(
                """
                INSERT INTO compras_requests (id, title, description, department_id, estimated_amount, currency, required_date, priority,
                                              requester_id, requester_name, source_kind, source_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_kind, source_ref) DO NOTHING
                """.trimIndent()
            ).use { insert ->
                ComprasSql.bind(
                    insert,
                    listOf(id, clean.title, clean.description, clean.departmentId, clean.estimatedAmount, clean.currency, clean.requiredDate,
                        clean.priority.id, requesterId, actor.name.take(200), source?.kind, source?.ref),
                )
                insert.executeUpdate()
            }
            if (inserted == 0) {
                // Otro proceso creo la del mismo origen entre la consulta y el insert.
                val existing = source?.let { idBySource(tenant, it) } ?: throw changed()
                return@transaction load(tenant, existing) ?: throw notFound()
            }
            addEvent(tenant, id, actor, "created", detail = clean.title)
            load(tenant, id) ?: throw notFound()
        }
    }

    /**
     * Bloquea la fila de la solicitud (`FOR UPDATE`) hasta el fin de la transaccion: serializa aprobaciones, cambios de monto,
     * cotizaciones y adjuntos de la misma solicitud. Debe llamarse DENTRO de una transaccion y antes de cargar y validar.
     */
    fun lockRequest(tenant: Connection, id: UUID) {
        val found = tenant.prepareStatement("SELECT 1 FROM compras_requests WHERE id = ? FOR UPDATE").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { it.next() }
        }
        if (!found) throw notFound()
    }

    // Dentro de la transaccion y con la fila bloqueada: solicitud visible y con monto/cotizaciones editables, o error claro.
    private fun lockedEditable(tenant: Connection, actor: Actor, id: UUID): PurchaseRequest {
        lockRequest(tenant, id)
        val current = get(tenant, id, actor) ?: throw notFound()
        if (!ComprasFlow.canEdit(current.status, actor, current.requesterId)) {
            throw ComprasValidationException("No puedes editar el monto ni las cotizaciones en estado ${current.status.label}; retira la solicitud a borrador.")
        }
        return current
    }

    /**
     * Edita la solicitud. Monto, moneda y cotizaciones solo en borrador o cotizacion (solicitante o manage). Con manage,
     * fuera de esos estados y mientras no sea terminal, solo cambian texto, departamento, fecha y prioridad.
     */
    fun update(tenant: Connection, actor: Actor, id: UUID, draft: RequestDraft): PurchaseRequest {
        val clean = draft.validated()
        return ComprasSql.transaction(tenant) {
            lockRequest(tenant, id)
            val current = get(tenant, id, actor) ?: throw notFound()
            if (!ComprasFlow.canEdit(current.status, actor, current.requesterId)) {
                if (!ComprasFlow.canEditText(current.status, actor)) {
                    throw ComprasValidationException("No puedes editar esta solicitud en estado ${current.status.label}.")
                }
                val before = current.estimatedAmount
                val after = clean.estimatedAmount
                val sameAmount = if (before == null || after == null) before == after else before.compareTo(after) == 0
                if (!sameAmount || clean.currency != current.currency) {
                    throw ComprasValidationException("El monto y la moneda solo se cambian en borrador o cotizacion; retira la solicitud a borrador.")
                }
            }
            checkDepartment(tenant, clean.departmentId, current.departmentId)
            val updated = tenant.prepareStatement(
                """
                UPDATE compras_requests SET title = ?, description = ?, department_id = ?, estimated_amount = ?, currency = ?,
                    required_date = ?, priority = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = ?
                """.trimIndent()
            ).use { update ->
                ComprasSql.bind(
                    update,
                    listOf(clean.title, clean.description, clean.departmentId, clean.estimatedAmount, clean.currency, clean.requiredDate,
                        clean.priority.id, id, current.status.id),
                )
                update.executeUpdate()
            }
            if (updated == 0) throw changed()
            val changes = clean.changesFrom(current)
            if (changes.isNotEmpty()) addEvent(tenant, id, actor, "edited", detail = changes)
            load(tenant, id) ?: throw notFound()
        }
    }

    private fun scoped(tenant: Connection, actor: Actor, filter: RequestFilter): SqlFragment {
        val approver = approver(tenant, actor)
        return ComprasVisibility.where(actor, approver, "r") and ComprasVisibility.filter(filter, actor, approver, "r")
    }

    /**
     * Solicitudes visibles que cumplen [filter]. Mas recientes primero; con `highOnly`, criticas primero y por
     * fecha requerida. [limit] se limita a [MaxPageSize].
     */
    fun list(tenant: Connection, actor: Actor, filter: RequestFilter = RequestFilter(), limit: Int = 50, offset: Int = 0): List<PurchaseRequest> {
        val where = scoped(tenant, actor, filter)
        val order = if (filter.highOnly) {
            "ORDER BY CASE r.priority WHEN 'critical' THEN 0 ELSE 1 END, r.required_date ASC NULLS LAST, r.created_at DESC"
        } else {
            "ORDER BY r.created_at DESC"
        }
        return tenant.prepareStatement("$RequestSelect WHERE ${where.sql} $order LIMIT ? OFFSET ?").use { query ->
            ComprasSql.bind(query, where.params + limit.coerceIn(1, MaxPageSize) + offset.coerceIn(0, MaxOffset))
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.request()) } }
        }
    }

    /** Cuantas solicitudes visibles cumplen [filter]. */
    fun count(tenant: Connection, actor: Actor, filter: RequestFilter = RequestFilter()): Int {
        val where = scoped(tenant, actor, filter)
        return tenant.prepareStatement("SELECT count(*) FROM compras_requests r WHERE ${where.sql}").use { query ->
            ComprasSql.bind(query, where.params)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    /** Pendientes de aprobacion que [actor] puede aprobar por su limite. */
    fun countPendingForMe(tenant: Connection, actor: Actor): Int = count(tenant, actor, RequestFilter(pendingMyApproval = true))

    // Pasa de [from] a [to]; el SQL fijo de cada destino y sus valores van aparte (nunca concatenados).
    private fun transitionSet(to: RequestStatus, actor: Actor, reason: String?, context: FlowContext): SqlFragment = when (to) {
        RequestStatus.PendingApproval -> SqlFragment("submitted_at = CURRENT_TIMESTAMP")
        RequestStatus.Approved -> SqlFragment(
            "decided_by = ?, decided_by_name = ?, decided_at = CURRENT_TIMESTAMP, decision_reason = ?, approved_amount = ?, approved_currency = ?",
            listOf(actor.userId, actor.name.take(200), reason, context.amount, context.currency),
        )
        RequestStatus.Rejected -> SqlFragment(
            "decided_by = ?, decided_by_name = ?, decided_at = CURRENT_TIMESTAMP, decision_reason = ?, approved_amount = NULL, approved_currency = NULL",
            listOf(actor.userId, actor.name.take(200), reason),
        )
        RequestStatus.Draft -> SqlFragment(
            "submitted_at = NULL, decided_by = NULL, decided_by_name = NULL, decided_at = NULL, decision_reason = NULL, approved_amount = NULL, approved_currency = NULL"
        )
        RequestStatus.Completed -> SqlFragment("completed_at = CURRENT_TIMESTAMP")
        else -> SqlFragment("")
    }

    /**
     * Mueve la solicitud a [to] si el flujo y los permisos de [actor] lo permiten ([ComprasFlow.validate]).
     * Rechazar exige [reason]. `UPDATE ... WHERE id = ? AND status = ?` evita pisar un cambio concurrente
     * ("La solicitud cambio, recarga"). Aprobar congela approved_amount/approved_currency. Registra el evento.
     */
    fun transition(tenant: Connection, actor: Actor, id: UUID, to: RequestStatus, reason: String? = null): TransitionResult {
        val cleanReason = reason?.trim()?.take(1000)?.takeIf { it.isNotEmpty() }
        if ((to == RequestStatus.Approved || to == RequestStatus.Rejected) && actor.userId == null) {
            throw ComprasValidationException("No se pudo identificar al usuario que decide.")
        }
        return ComprasSql.transaction(tenant) {
            // Fila bloqueada antes de leer el monto y el limite: dos decisiones o un cambio de monto no se cruzan.
            lockRequest(tenant, id)
            val approver = approver(tenant, actor)
            val current = get(tenant, id, actor) ?: throw notFound()
            val context = ComprasFlow.contextOf(current, approver)
            ComprasFlow.validate(current.status, to, actor, context, cleanReason, current.title)
            val extra = transitionSet(to, actor, cleanReason, context)
            val sql = "UPDATE compras_requests SET status = ?, updated_at = CURRENT_TIMESTAMP" +
                (if (extra.sql.isEmpty()) "" else ", ${extra.sql}") + " WHERE id = ? AND status = ?"
            val updated = tenant.prepareStatement(sql).use { update ->
                ComprasSql.bind(update, listOf<Any?>(to.id) + extra.params + listOf(id, current.status.id))
                update.executeUpdate()
            }
            if (updated == 0) throw changed()
            addEvent(tenant, id, actor, "status", cleanReason.orEmpty(), current.status, to)
            TransitionResult(load(tenant, id) ?: throw notFound(), current.status)
        }
    }

    // ---- Cotizaciones ----

    private fun ResultSet.quote() = Quote(
        id = getObject("id", UUID::class.java),
        requestId = getObject("request_id", UUID::class.java),
        supplier = getString("supplier"),
        amount = getBigDecimal("amount"),
        currency = getString("currency"),
        notes = getString("notes"),
        selected = getBoolean("selected"),
        quotedBy = getObject("quoted_by", UUID::class.java),
        quotedByName = getString("quoted_by_name"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private const val QuoteSelect = "SELECT id, request_id, supplier, amount, currency, notes, selected, quoted_by, quoted_by_name, created_at FROM compras_quotes"

    /** Cotizaciones de la solicitud (vacio si no es visible para [actor]). La elegida primero, luego por monto. */
    fun quotes(tenant: Connection, actor: Actor, requestId: UUID): List<Quote> {
        if (get(tenant, requestId, actor) == null) return emptyList()
        return tenant.prepareStatement("$QuoteSelect WHERE request_id = ? ORDER BY selected DESC, amount, created_at").use { query ->
            query.setObject(1, requestId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.quote()) } }
        }
    }

    private fun quote(tenant: Connection, id: UUID): Quote? = tenant.prepareStatement("$QuoteSelect WHERE id = ?").use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) rows.quote() else null }
    }

    private fun cleanQuote(supplier: String, amount: BigDecimal, currency: String, notes: String): Triple<String, BigDecimal, String> {
        val name = supplier.trim()
        if (name.isEmpty() || name.length > 200) throw ComprasValidationException("El proveedor debe tener entre 1 y 200 caracteres.")
        if (notes.length > 2000) throw ComprasValidationException("Las notas admiten hasta 2000 caracteres.")
        return Triple(name, ComprasMoney.check(amount), ComprasMoney.currency(currency))
    }

    // Las cotizaciones cambian el monto a aprobar: la solicitud se toca para que otras pantallas vean que cambio.
    private fun touch(tenant: Connection, id: UUID) {
        tenant.prepareStatement("UPDATE compras_requests SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setObject(1, id)
            update.executeUpdate()
        }
    }

    // La cotizacion debe pertenecer a la solicitud indicada.
    private fun quoteOf(tenant: Connection, requestId: UUID, quoteId: UUID): Quote =
        quote(tenant, quoteId)?.takeIf { it.requestId == requestId } ?: throw ComprasValidationException("La cotizacion no existe en esta solicitud.")

    /** Agrega una cotizacion (la solicitud debe estar en borrador o cotizacion y ser editable por [actor]). */
    fun addQuote(tenant: Connection, actor: Actor, requestId: UUID, supplier: String, amount: BigDecimal, currency: String, notes: String = ""): Quote {
        val (name, money, code) = cleanQuote(supplier, amount, currency, notes)
        return ComprasSql.transaction(tenant) {
            lockedEditable(tenant, actor, requestId)
            val count = tenant.prepareStatement("SELECT count(*) FROM compras_quotes WHERE request_id = ?").use { query ->
                query.setObject(1, requestId)
                query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            if (count >= MaxQuotesPerRequest) throw ComprasValidationException("Una solicitud admite hasta $MaxQuotesPerRequest cotizaciones.")
            val id = UUID.randomUUID()
            tenant.prepareStatement(
                "INSERT INTO compras_quotes (id, request_id, supplier, amount, currency, notes, quoted_by, quoted_by_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { insert ->
                ComprasSql.bind(insert, listOf(id, requestId, name, money, code, notes.trim(), actor.userId, actor.name.take(200)))
                insert.executeUpdate()
            }
            touch(tenant, requestId)
            addEvent(tenant, requestId, actor, "quote", "Cotizacion agregada: $name ${ComprasMoney.format(money, code)}")
            quote(tenant, id) ?: throw notFound()
        }
    }

    /** Edita una cotizacion de [requestId]; si es la elegida, el monto a aprobar cambia con ella. */
    fun updateQuote(tenant: Connection, actor: Actor, requestId: UUID, quoteId: UUID, supplier: String, amount: BigDecimal, currency: String, notes: String = ""): Quote {
        val (name, money, code) = cleanQuote(supplier, amount, currency, notes)
        return ComprasSql.transaction(tenant) {
            lockedEditable(tenant, actor, requestId)
            quoteOf(tenant, requestId, quoteId)
            tenant.prepareStatement("UPDATE compras_quotes SET supplier = ?, amount = ?, currency = ?, notes = ? WHERE id = ? AND request_id = ?").use { update ->
                ComprasSql.bind(update, listOf(name, money, code, notes.trim(), quoteId, requestId))
                update.executeUpdate()
            }
            touch(tenant, requestId)
            addEvent(tenant, requestId, actor, "quote", "Cotizacion editada: $name ${ComprasMoney.format(money, code)}")
            quote(tenant, quoteId) ?: throw notFound()
        }
    }

    /** Quita una cotizacion de [requestId]. */
    fun deleteQuote(tenant: Connection, actor: Actor, requestId: UUID, quoteId: UUID) {
        ComprasSql.transaction(tenant) {
            lockedEditable(tenant, actor, requestId)
            val existing = quoteOf(tenant, requestId, quoteId)
            tenant.prepareStatement("DELETE FROM compras_quotes WHERE id = ? AND request_id = ?").use { delete ->
                delete.setObject(1, quoteId)
                delete.setObject(2, requestId)
                delete.executeUpdate()
            }
            touch(tenant, requestId)
            addEvent(tenant, requestId, actor, "quote", "Cotizacion quitada: ${existing.supplier}")
        }
    }

    /** Elige la cotizacion [quoteId] (desmarca las demas en la misma transaccion); nulo quita la eleccion. */
    fun selectQuote(tenant: Connection, actor: Actor, requestId: UUID, quoteId: UUID?) {
        try {
            ComprasSql.transaction(tenant) {
                lockedEditable(tenant, actor, requestId)
                val chosen = quoteId?.let { quoteOf(tenant, requestId, it) }
                tenant.prepareStatement("UPDATE compras_quotes SET selected = FALSE WHERE request_id = ? AND selected").use { clear ->
                    clear.setObject(1, requestId)
                    clear.executeUpdate()
                }
                if (chosen != null) {
                    tenant.prepareStatement("UPDATE compras_quotes SET selected = TRUE WHERE id = ? AND request_id = ?").use { select ->
                        select.setObject(1, chosen.id)
                        select.setObject(2, requestId)
                        select.executeUpdate()
                    }
                }
                touch(tenant, requestId)
                addEvent(tenant, requestId, actor, "quote", chosen?.let { "Cotizacion elegida: ${it.supplier} ${ComprasMoney.format(it.amount, it.currency)}" } ?: "Cotizacion elegida: ninguna")
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == "23505") throw changed()
            throw failure
        }
    }

    // ---- Historial ----

    /** Agrega una linea al historial. Pensado para usarse dentro de la transaccion del cambio que describe. */
    fun addEvent(tenant: Connection, requestId: UUID, actor: Actor, kind: String, detail: String = "", from: RequestStatus? = null, to: RequestStatus? = null) {
        tenant.prepareStatement(
            "INSERT INTO compras_events (request_id, actor_id, actor_name, kind, from_status, to_status, detail) VALUES (?, ?, ?, ?, ?, ?, ?)"
        ).use { insert ->
            ComprasSql.bind(insert, listOf(requestId, actor.userId, actor.name.take(200), kind, from?.id, to?.id, detail.take(2000)))
            insert.executeUpdate()
        }
    }

    /** Historial de la solicitud, mas reciente primero (vacio si no es visible para [actor]). */
    fun events(tenant: Connection, actor: Actor, requestId: UUID, limit: Int = 200): List<RequestEvent> {
        if (get(tenant, requestId, actor) == null) return emptyList()
        return tenant.prepareStatement(
            "SELECT id, request_id, at, actor_id, actor_name, kind, from_status, to_status, detail FROM compras_events WHERE request_id = ? ORDER BY at DESC, id DESC LIMIT ?"
        ).use { query ->
            query.setObject(1, requestId)
            query.setInt(2, limit.coerceIn(1, 500))
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            RequestEvent(
                                id = rows.getLong(1),
                                requestId = rows.getObject(2, UUID::class.java),
                                at = rows.getTimestamp(3).toInstant(),
                                actorId = rows.getObject(4, UUID::class.java),
                                actorName = rows.getString(5),
                                kind = rows.getString(6),
                                fromStatus = RequestStatus.fromId(rows.getString(7)),
                                toStatus = RequestStatus.fromId(rows.getString(8)),
                                detail = rows.getString(9),
                            )
                        )
                    }
                }
            }
        }
    }

    // ---- Totales ----

    /**
     * Rango y estados de los totales. Por defecto el ano actual y las compras aprobadas, en progreso o
     * completadas. El monto es el aprobado (congelado) o, si no hay, el monto a aprobar; la fecha es la de decision.
     */
    data class TotalsFilter(
        val from: LocalDate = LocalDate.now().withDayOfYear(1),
        val to: LocalDate = LocalDate.now(),
        val statuses: Set<RequestStatus> = RequestStatus.Approvals,
    )

    private val TotalAmount = "COALESCE(r.approved_amount, $AmountSql)"
    private val TotalCurrency = "COALESCE(r.approved_currency, $CurrencySql)"
    private const val TotalDate = "COALESCE(r.decided_at, r.created_at)"

    private fun totals(tenant: Connection, actor: Actor, label: String, conditions: List<SqlFragment>): List<TotalRow> {
        val approver = approver(tenant, actor)
        val where = SqlFragment.all(listOf(ComprasVisibility.where(actor, approver, "r"), SqlFragment("$TotalAmount IS NOT NULL")) + conditions)
        val sql = """
            SELECT $label, $TotalCurrency, sum($TotalAmount), count(*)
            FROM compras_requests r
            LEFT JOIN compras_departments d ON d.id = r.department_id
            WHERE ${where.sql}
            GROUP BY 1, 2
            ORDER BY 2, 1
        """.trimIndent()
        return tenant.prepareStatement(sql).use { query ->
            ComprasSql.bind(query, where.params)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(TotalRow(rows.getString(1), rows.getString(2), rows.getBigDecimal(3), rows.getInt(4))) }
            }
        }
    }

    private fun statusAndRange(filter: TotalsFilter): List<SqlFragment> {
        if (filter.statuses.isEmpty()) return listOf(SqlFragment.False)
        return listOf(
            SqlFragment("r.status IN (${filter.statuses.joinToString(", ") { "?" }})", filter.statuses.map { it.id }),
            SqlFragment("$TotalDate >= CAST(? AS date) AND $TotalDate < CAST(? AS date) + 1", listOf(filter.from, filter.to)),
        )
    }

    /** Totales por departamento y moneda (solo solicitudes visibles). */
    fun totalsByDepartment(tenant: Connection, actor: Actor, filter: TotalsFilter = TotalsFilter()): List<TotalRow> =
        totals(tenant, actor, "COALESCE(d.name, 'Sin departamento')", statusAndRange(filter))

    /** Totales por mes (AAAA-MM) y moneda de los ultimos 12 meses; respeta los estados de [filter], no su rango. */
    fun totalsByMonth(tenant: Connection, actor: Actor, filter: TotalsFilter = TotalsFilter()): List<TotalRow> {
        if (filter.statuses.isEmpty()) return emptyList()
        return totals(
            tenant,
            actor,
            "to_char($TotalDate, 'YYYY-MM')",
            listOf(
                SqlFragment("r.status IN (${filter.statuses.joinToString(", ") { "?" }})", filter.statuses.map { it.id }),
                SqlFragment("$TotalDate >= date_trunc('month', CURRENT_DATE) - INTERVAL '11 months'"),
            ),
        )
    }

    /** Totales por estado (id de estado en label) y moneda, de las solicitudes creadas en el rango de [filter]. */
    fun totalsByStatus(tenant: Connection, actor: Actor, filter: TotalsFilter = TotalsFilter()): List<TotalRow> =
        totals(
            tenant,
            actor,
            "r.status",
            listOf(SqlFragment("r.created_at >= CAST(? AS date) AND r.created_at < CAST(? AS date) + 1", listOf(filter.from, filter.to))),
        )
}
