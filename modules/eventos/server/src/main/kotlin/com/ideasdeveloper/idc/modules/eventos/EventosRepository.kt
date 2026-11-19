package com.ideasdeveloper.idc.modules.eventos

import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Data access of the module. Every function is synchronous and takes the company's connection: callers run them
 * inside withContext(Dispatchers.IO) (routes) or on the tool thread (MCP), as Tareas does. All SQL is parameterized.
 * Customers are always read with the visibility filter of Clientes ([VisibleCustomer]).
 */
object EventosRepository {
    private val NilId = UUID(0L, 0L)
    private const val MaxListed = 500
    private const val MaxUsers = 500

    /** A customer of this company or shared with it, not archived (same filter as Clientes and Membresia). */
    const val VisibleCustomer = "(c.origin_company_code = '' OR c.shared) AND c.status <> 'archived'"

    val DefaultCategories = listOf("Taller", "Música", "Charla", "Clase", "Celebración", "Boda", "Retiro", "Otro")

    /** Today in the server's time zone. */
    fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    fun <T> transaction(tenant: Connection, block: () -> T): T {
        val previous = tenant.autoCommit
        if (!previous) {
            // Already inside a transaction of the caller: it decides when to commit; a savepoint isolates this block.
            val savepoint = tenant.setSavepoint()
            try {
                val result = block()
                tenant.releaseSavepoint(savepoint)
                return result
            } catch (failure: Throwable) {
                runCatching { tenant.rollback(savepoint) }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
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

    // ---- helpers ----

    private fun PreparedStatement.bind(params: List<Any?>) {
        params.forEachIndexed { index, value ->
            when (value) {
                null -> setObject(index + 1, null)
                is java.sql.Array -> setArray(index + 1, value)
                is Instant -> setObject(index + 1, value.atOffset(ZoneOffset.UTC))
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

    private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private fun ResultSet.money(column: String): BigDecimal? = getBigDecimal(column)

    private fun ResultSet.textList(column: String): List<String> =
        (getArray(column)?.array as? Array<*>)?.mapNotNull { it?.toString() }.orEmpty()

    // ---- settings ----

    fun settings(tenant: Connection): EventosSettings = tenant.query(
        "SELECT currency, spaces, categories FROM eventos_settings WHERE id = 1",
        emptyList(),
    ) { EventosSettings(it.getString("currency"), it.textList("spaces"), it.textList("categories")) }
        .firstOrNull() ?: EventosSettings("USD", emptyList(), DefaultCategories)

    fun saveSettings(tenant: Connection, settings: EventosSettings) {
        val updated = tenant.update(
            "UPDATE eventos_settings SET currency = ?, spaces = ?, categories = ?, updated_at = now() WHERE id = 1",
            listOf(settings.currency, tenant.createArrayOf("text", settings.spaces.toTypedArray()), tenant.createArrayOf("text", settings.categories.toTypedArray())),
        )
        if (updated == 0) {
            tenant.update(
                "INSERT INTO eventos_settings (id, currency, spaces, categories) VALUES (1, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                listOf(settings.currency, tenant.createArrayOf("text", settings.spaces.toTypedArray()), tenant.createArrayOf("text", settings.categories.toTypedArray())),
            )
        }
    }

    // ---- users ----

    /** Active users that can use the module: the business owner or holders of any eventos.* permission. */
    fun users(tenant: Connection): List<EventosUser> {
        class Row(val id: UUID, val role: String, val name: String, val email: String)
        val rows = tenant.query(
            """
            SELECT users.id, users.postgres_role, COALESCE(NULLIF(btrim(users.display_name), ''), users.postgres_role) AS label, COALESCE(users.email, '') AS email
            FROM application_users users
            WHERE users.is_active
              AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
                   OR EXISTS (SELECT 1 FROM application_user_permissions granted
                              WHERE granted.user_id = users.id AND granted.permission_id LIKE 'eventos.%')
            )
            ORDER BY label, users.postgres_role
            LIMIT $MaxUsers
            """.trimIndent(),
            emptyList(),
        ) { Row(it.uuid("id") ?: NilId, it.getString("postgres_role"), it.getString("label"), it.getString("email")) }
        val repeated = rows.groupingBy { it.name.lowercase() }.eachCount().filterValues { it > 1 }.keys
        // Two people with the same name are told apart by their username.
        return rows.map { EventosUser(it.id, if (it.name.lowercase() in repeated) "${it.name} (${it.role})" else it.name, it.email) }
    }

    fun isActiveUser(tenant: Connection, userId: UUID): Boolean =
        tenant.query("SELECT 1 AS found FROM application_users WHERE id = ? AND is_active", listOf(userId)) { it.getInt("found") }.isNotEmpty()

    /** Active users that manage events (permission eventos.manage) and the business owner: who is told when nobody is responsible. */
    fun managerIds(tenant: Connection): List<UUID> = tenant.query(
        """
        SELECT users.id FROM application_users users
        WHERE users.is_active
          AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
               OR EXISTS (SELECT 1 FROM application_user_permissions granted
                          WHERE granted.user_id = users.id AND granted.permission_id = 'eventos.manage'))
        ORDER BY users.id
        LIMIT $MaxUsers
        """.trimIndent(),
        emptyList(),
    ) { it.uuid("id") ?: NilId }

    // ---- events ----

    private const val EventColumns = """
        e.id, e.kind, e.title, e.description, e.category, e.series, e.space, e.starts_at, e.ends_at, e.capacity, e.host_name,
        e.responsible_user_id, e.status, e.price_member, e.price_public, e.currency, e.client_customer_id,
        COALESCE(c.display_name, '') AS client_name, e.agreed_amount, e.guests, e.notes, e.import_key, e.cancelled_at,
        e.cancel_reason, e.created_by, e.created_at, e.updated_at,
        COALESCE(r.occupied, 0) AS occupied, COALESCE(r.registrations, 0) AS registrations, COALESCE(r.waiting, 0) AS waiting,
        COALESCE(r.attended, 0) AS attended, COALESCE(r.collected, 0) AS collected, COALESCE(r.pending, 0) AS pending,
        COALESCE(p.total, 0) AS payments_total
    """

    private const val EventFrom = """
        FROM eventos_events e
        LEFT JOIN customers c ON c.id = e.client_customer_id AND (c.origin_company_code = '' OR c.shared)
        LEFT JOIN LATERAL (
            SELECT COALESCE(sum(g.seats) FILTER (WHERE g.status = 'inscrito'), 0) AS occupied,
                   count(*) FILTER (WHERE g.status = 'inscrito') AS registrations,
                   count(*) FILTER (WHERE g.status = 'espera') AS waiting,
                   count(*) FILTER (WHERE g.status = 'inscrito' AND g.attended_at IS NOT NULL) AS attended,
                   COALESCE(sum(g.amount) FILTER (WHERE g.status = 'inscrito' AND g.paid), 0) AS collected,
                   COALESCE(sum(g.amount) FILTER (WHERE g.status = 'inscrito' AND NOT g.paid), 0) AS pending
            FROM eventos_registrations g WHERE g.event_id = e.id
        ) r ON true
        LEFT JOIN LATERAL (SELECT sum(x.amount) AS total FROM eventos_payments x WHERE x.event_id = e.id) p ON true
    """

    private fun mapEvent(rows: ResultSet): EventWithStats = EventWithStats(
        Event(
            id = rows.uuid("id") ?: NilId,
            kind = EventKind.fromCode(rows.getString("kind")) ?: EventKind.Public,
            title = rows.getString("title"),
            description = rows.getString("description"),
            category = rows.getString("category"),
            series = rows.getString("series"),
            space = rows.getString("space"),
            startsAt = rows.instant("starts_at") ?: Instant.EPOCH,
            endsAt = rows.instant("ends_at"),
            capacity = rows.intOrNull("capacity"),
            hostName = rows.getString("host_name"),
            responsibleUserId = rows.uuid("responsible_user_id"),
            status = EventStatus.fromCode(rows.getString("status")) ?: EventStatus.Draft,
            priceMember = rows.money("price_member"),
            pricePublic = rows.money("price_public"),
            currency = rows.getString("currency"),
            clientCustomerId = rows.uuid("client_customer_id"),
            clientName = rows.getString("client_name"),
            agreedAmount = rows.money("agreed_amount"),
            guests = rows.intOrNull("guests"),
            notes = rows.getString("notes"),
            importKey = rows.getString("import_key"),
            cancelledAt = rows.instant("cancelled_at"),
            cancelReason = rows.getString("cancel_reason"),
            createdBy = rows.getString("created_by"),
            createdAt = rows.instant("created_at") ?: Instant.EPOCH,
            updatedAt = rows.instant("updated_at") ?: Instant.EPOCH,
        ),
        EventStats(
            occupied = rows.getInt("occupied"),
            registrations = rows.getInt("registrations"),
            waiting = rows.getInt("waiting"),
            attended = rows.getInt("attended"),
            collected = rows.money("collected") ?: BigDecimal.ZERO,
            pending = rows.money("pending") ?: BigDecimal.ZERO,
            paymentsTotal = rows.money("payments_total") ?: BigDecimal.ZERO,
        ),
    )

    fun getEvent(tenant: Connection, id: UUID): EventWithStats? =
        tenant.query("SELECT $EventColumns $EventFrom WHERE e.id = ?", listOf(id), ::mapEvent).firstOrNull()

    /** Locks the event row until the end of the transaction (what serializes registrations on the capacity). False when it does not exist. */
    fun lockEvent(tenant: Connection, id: UUID): Boolean =
        tenant.query("SELECT id FROM eventos_events WHERE id = ? FOR UPDATE", listOf(id)) { it.uuid("id") }.isNotEmpty()

    fun listEvents(tenant: Connection, filter: EventFilter): List<EventWithStats> {
        val params = mutableListOf<Any?>()
        val clauses = mutableListOf("TRUE")
        fun clause(sql: String, vararg values: Any?) {
            clauses.add(sql)
            values.forEach { params.add(it) }
        }
        filter.from?.let { clause("e.starts_at >= ?", it) }
        filter.to?.let { clause("e.starts_at < ?", it) }
        filter.kind?.let { clause("e.kind = ?", it.code) }
        if (filter.statuses.isNotEmpty()) {
            clause("e.status = ANY (?)", tenant.createArrayOf("text", filter.statuses.map { it.code }.toTypedArray()))
        }
        filter.series.trim().takeIf { it.isNotEmpty() }?.let { clause("lower(e.series) = lower(?)", it) }
        filter.category.trim().takeIf { it.isNotEmpty() }?.let { clause("lower(e.category) = lower(?)", it) }
        filter.space.trim().takeIf { it.isNotEmpty() }?.let { clause("lower(e.space) = lower(?)", it) }
        filter.query.trim().take(100).takeIf { it.isNotEmpty() }?.let { text ->
            val pattern = "%${EventosRules.escapeLike(text)}%"
            clause("(e.title ILIKE ? OR e.series ILIKE ? OR e.space ILIKE ? OR e.host_name ILIKE ?)", pattern, pattern, pattern, pattern)
        }
        params.add(filter.limit.coerceIn(1, MaxListed))
        val order = if (filter.newestFirst) "e.starts_at DESC, e.id" else "e.starts_at, e.id"
        return tenant.query("SELECT $EventColumns $EventFrom WHERE ${clauses.joinToString(" AND ")} ORDER BY $order LIMIT ?", params, ::mapEvent)
    }

    /** Numbers of the home screen: events of the next 30 days, people registered this week (Monday on) and full events still to come. */
    fun overview(tenant: Connection, now: Instant, zone: ZoneId = ZoneId.systemDefault()): EventosOverview {
        val weekStart = now.atZone(zone).toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant()
        val upcoming = tenant.query(
            "SELECT count(*) AS total FROM eventos_events WHERE status IN ('publicado', 'lleno') AND starts_at >= ? AND starts_at < ?",
            listOf(now, now.plusSeconds(30L * 24 * 3600)),
        ) { it.getInt("total") }.firstOrNull() ?: 0
        val registered = tenant.query(
            "SELECT count(*) AS total FROM eventos_registrations WHERE status = 'inscrito' AND created_at >= ?",
            listOf(weekStart),
        ) { it.getInt("total") }.firstOrNull() ?: 0
        val full = tenant.query(
            "SELECT count(*) AS total FROM eventos_events WHERE status = 'lleno' AND starts_at >= ?",
            listOf(now),
        ) { it.getInt("total") }.firstOrNull() ?: 0
        return EventosOverview(upcoming, registered, full)
    }

    fun insertEvent(tenant: Connection, id: UUID, input: EventInput, status: EventStatus, currency: String, createdBy: String, importKey: String? = null) {
        tenant.update(
            """
            INSERT INTO eventos_events (id, kind, title, description, category, series, space, starts_at, ends_at, capacity, host_name,
                responsible_user_id, status, price_member, price_public, currency, client_customer_id, agreed_amount, guests, notes,
                import_key, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                id, input.kind.code, input.title.trim(), input.description, input.category.trim(), input.series.trim(), input.space.trim(),
                input.startsAt, input.endsAt, input.capacity, input.hostName.trim(), input.responsibleUserId, status.code,
                input.priceMember?.let(EventosRules::money), input.pricePublic?.let(EventosRules::money), currency, input.clientCustomerId,
                input.agreedAmount?.let(EventosRules::money), input.guests, input.notes, importKey, createdBy.take(160),
            ),
        )
    }

    /** Rewrites the editable fields (never the kind, currency, state or import key). */
    fun updateEvent(tenant: Connection, id: UUID, input: EventInput): Int = tenant.update(
        """
        UPDATE eventos_events
        SET title = ?, description = ?, category = ?, series = ?, space = ?, starts_at = ?, ends_at = ?, capacity = ?, host_name = ?,
            responsible_user_id = ?, price_member = ?, price_public = ?, client_customer_id = ?, agreed_amount = ?, guests = ?, notes = ?,
            updated_at = now()
        WHERE id = ?
        """.trimIndent(),
        listOf(
            input.title.trim(), input.description, input.category.trim(), input.series.trim(), input.space.trim(), input.startsAt, input.endsAt,
            input.capacity, input.hostName.trim(), input.responsibleUserId, input.priceMember?.let(EventosRules::money),
            input.pricePublic?.let(EventosRules::money), input.clientCustomerId, input.agreedAmount?.let(EventosRules::money), input.guests,
            input.notes, id,
        ),
    )

    /** Sets the state; Cancelled records when and why, any other state clears them. */
    fun setStatus(tenant: Connection, id: UUID, status: EventStatus, cancelReason: String = "") {
        tenant.update(
            """
            UPDATE eventos_events
            SET status = ?, cancelled_at = CASE WHEN ?::boolean THEN COALESCE(cancelled_at, now()) ELSE NULL END,
                cancel_reason = ?, updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            listOf(status.code, status == EventStatus.Cancelled, if (status == EventStatus.Cancelled) cancelReason.trim() else "", id),
        )
    }

    /** Job body: published or full events that ended more than 6 hours ago become Done (batch of [limit]). Idempotent. */
    fun closeFinished(tenant: Connection, limit: Int = 500): Int = tenant.update(
        """
        UPDATE eventos_events SET status = 'realizado', updated_at = now()
        WHERE id IN (
            SELECT id FROM eventos_events
            WHERE status IN ('publicado', 'lleno') AND COALESCE(ends_at, starts_at) < now() - interval '6 hours'
            ORDER BY starts_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        ) AND status IN ('publicado', 'lleno')
        """.trimIndent(),
        listOf(limit.coerceIn(1, MaxListed)),
    )

    // ---- registrations ----

    private const val RegistrationSelect = """
        SELECT g.id, g.event_id, g.customer_id, COALESCE(c.display_name, 'Cliente no disponible') AS customer_name, g.status, g.seats,
               g.price_kind, g.amount, g.paid, g.paid_at, g.payment_note, g.attended_at, g.attended_by, g.waitlisted_at, g.note,
               g.cancelled_at, g.created_by, g.created_at, e.title AS event_title, e.starts_at AS event_starts_at, e.currency AS event_currency
        FROM eventos_registrations g
        JOIN eventos_events e ON e.id = g.event_id
        LEFT JOIN customers c ON c.id = g.customer_id AND (c.origin_company_code = '' OR c.shared)
    """

    private fun mapRegistration(rows: ResultSet) = Registration(
        id = rows.uuid("id") ?: NilId,
        eventId = rows.uuid("event_id") ?: NilId,
        customerId = rows.uuid("customer_id") ?: NilId,
        customerName = rows.getString("customer_name"),
        status = RegistrationStatus.fromCode(rows.getString("status")) ?: RegistrationStatus.Cancelled,
        seats = rows.getInt("seats"),
        priceKind = PriceKind.fromCode(rows.getString("price_kind")) ?: PriceKind.General,
        amount = rows.money("amount") ?: BigDecimal.ZERO,
        paid = rows.getBoolean("paid"),
        paidAt = rows.instant("paid_at"),
        paymentNote = rows.getString("payment_note"),
        attendedAt = rows.instant("attended_at"),
        attendedBy = rows.getString("attended_by"),
        waitlistedAt = rows.instant("waitlisted_at"),
        note = rows.getString("note"),
        cancelledAt = rows.instant("cancelled_at"),
        createdBy = rows.getString("created_by"),
        createdAt = rows.instant("created_at") ?: Instant.EPOCH,
        eventTitle = rows.getString("event_title"),
        eventStartsAt = rows.instant("event_starts_at") ?: Instant.EPOCH,
        eventCurrency = rows.getString("event_currency"),
    )

    fun getRegistration(tenant: Connection, id: UUID): Registration? =
        tenant.query("$RegistrationSelect WHERE g.id = ?", listOf(id), ::mapRegistration).firstOrNull()

    /** Registrations of an event: registered people by name, then the waiting list in order of arrival, then cancelled ones (only when [status] is Cancelled or null with [includeCancelled]). */
    fun listRegistrations(tenant: Connection, eventId: UUID, status: RegistrationStatus? = null, includeCancelled: Boolean = false, limit: Int = MaxListed): List<Registration> {
        val params = mutableListOf<Any?>(eventId)
        val filter = when {
            status != null -> " AND g.status = ?"
            includeCancelled -> ""
            else -> " AND g.status <> 'cancelado'"
        }
        if (status != null) params.add(status.code)
        params.add(limit.coerceIn(1, MaxListed))
        return tenant.query(
            """
            $RegistrationSelect
            WHERE g.event_id = ?$filter
            ORDER BY CASE g.status WHEN 'inscrito' THEN 0 WHEN 'espera' THEN 1 ELSE 2 END,
                     CASE WHEN g.status = 'espera' THEN g.waitlisted_at END, lower(COALESCE(c.display_name, '')), g.id
            LIMIT ?
            """.trimIndent(),
            params,
            ::mapRegistration,
        )
    }

    /** The live (not cancelled) registration of a customer in an event. */
    fun liveRegistration(tenant: Connection, eventId: UUID, customerId: UUID): UUID? = tenant.query(
        "SELECT id FROM eventos_registrations WHERE event_id = ? AND customer_id = ? AND status <> 'cancelado'",
        listOf(eventId, customerId),
    ) { it.uuid("id") }.firstOrNull()

    fun insertRegistration(
        tenant: Connection,
        id: UUID,
        eventId: UUID,
        customerId: UUID,
        status: RegistrationStatus,
        seats: Int,
        priceKind: PriceKind,
        amount: BigDecimal,
        paid: Boolean,
        note: String,
        createdBy: String,
    ) {
        tenant.update(
            """
            INSERT INTO eventos_registrations (id, event_id, customer_id, status, seats, price_kind, amount, paid, paid_at, waitlisted_at, note, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::boolean, CASE WHEN ?::boolean THEN now() ELSE NULL END,
                    CASE WHEN ? = 'espera' THEN now() ELSE NULL END, ?, ?)
            """.trimIndent(),
            listOf(id, eventId, customerId, status.code, seats, priceKind.code, EventosRules.money(amount), paid, paid, status.code, note.trim(), createdBy.take(160)),
        )
    }

    /** Moves a registration to [status] (Registered from the waiting list, or Cancelled, which records when). */
    fun setRegistrationStatus(tenant: Connection, id: UUID, status: RegistrationStatus) {
        tenant.update(
            """
            UPDATE eventos_registrations
            SET status = ?, cancelled_at = CASE WHEN ? = 'cancelado' THEN now() ELSE cancelled_at END, updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            listOf(status.code, status.code, id),
        )
    }

    fun setPaid(tenant: Connection, id: UUID, paid: Boolean, note: String) {
        tenant.update(
            """
            UPDATE eventos_registrations
            SET paid = ?::boolean, paid_at = CASE WHEN ?::boolean THEN COALESCE(paid_at, now()) ELSE NULL END, payment_note = ?, updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            listOf(paid, paid, note.trim().take(EventosRules.MaxPaymentNote), id),
        )
    }

    fun setAttendance(tenant: Connection, id: UUID, attended: Boolean, by: String) {
        tenant.update(
            """
            UPDATE eventos_registrations
            SET attended_at = CASE WHEN ?::boolean THEN COALESCE(attended_at, now()) ELSE NULL END,
                attended_by = CASE WHEN ?::boolean THEN COALESCE(NULLIF(attended_by, ''), ?) ELSE '' END, updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            listOf(attended, attended, by.take(160), id),
        )
    }

    // ---- payments (abonos) ----

    private fun mapPayment(rows: ResultSet) = EventPayment(
        id = rows.uuid("id") ?: NilId,
        eventId = rows.uuid("event_id") ?: NilId,
        paidOn = rows.getObject("paid_on", LocalDate::class.java),
        amount = rows.money("amount") ?: BigDecimal.ZERO,
        method = rows.getString("method"),
        note = rows.getString("note"),
        recordedBy = rows.getString("recorded_by"),
        createdAt = rows.instant("created_at") ?: Instant.EPOCH,
    )

    fun listPayments(tenant: Connection, eventId: UUID): List<EventPayment> = tenant.query(
        "SELECT id, event_id, paid_on, amount, method, note, recorded_by, created_at FROM eventos_payments WHERE event_id = ? ORDER BY paid_on, created_at, id LIMIT $MaxListed",
        listOf(eventId),
        ::mapPayment,
    )

    fun getPayment(tenant: Connection, id: UUID): EventPayment? = tenant.query(
        "SELECT id, event_id, paid_on, amount, method, note, recorded_by, created_at FROM eventos_payments WHERE id = ?",
        listOf(id),
        ::mapPayment,
    ).firstOrNull()

    fun insertPayment(tenant: Connection, id: UUID, eventId: UUID, paidOn: LocalDate, amount: BigDecimal, method: String, note: String, recordedBy: String) {
        tenant.update(
            "INSERT INTO eventos_payments (id, event_id, paid_on, amount, method, note, recorded_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
            listOf(id, eventId, paidOn, EventosRules.money(amount), method.trim(), note.trim(), recordedBy.take(160)),
        )
    }

    fun deletePayment(tenant: Connection, eventId: UUID, id: UUID): Int =
        tenant.update("DELETE FROM eventos_payments WHERE id = ? AND event_id = ?", listOf(id, eventId))

    // ---- customers ----

    fun visibleCustomer(tenant: Connection, id: UUID): CustomerRef? = tenant.query(
        "SELECT c.id, c.display_name, COALESCE(c.primary_email, '') AS email FROM customers c WHERE c.id = ? AND $VisibleCustomer",
        listOf(id),
    ) { CustomerRef(it.uuid("id") ?: NilId, it.getString("display_name"), it.getString("email")) }.firstOrNull()

    /** Visible customers whose name, e-mail or phone contain [text] (ILIKE escaped, 2 characters at least); [limit] 1..50. */
    fun searchCustomers(tenant: Connection, text: String, limit: Int = 10): List<CustomerRef> {
        val clean = text.trim().take(100)
        if (clean.length < 2) return emptyList()
        val pattern = "%${EventosRules.escapeLike(clean)}%"
        return tenant.query(
            """
            SELECT c.id, c.display_name, COALESCE(c.primary_email, '') AS email
            FROM customers c
            WHERE $VisibleCustomer AND (c.display_name ILIKE ? OR c.primary_email ILIKE ? OR c.primary_phone ILIKE ?)
            ORDER BY lower(c.display_name), c.id
            LIMIT ?
            """.trimIndent(),
            listOf(pattern, pattern, pattern, limit.coerceIn(1, 50)),
        ) { CustomerRef(it.uuid("id") ?: NilId, it.getString("display_name"), it.getString("email")) }
    }

    /** Of [emails] (any case), the ones that belong to a visible customer, in lower case. For the import preview. */
    fun existingEmails(tenant: Connection, emails: Collection<String>): Set<String> {
        val wanted = emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(5000)
        if (wanted.isEmpty()) return emptySet()
        return tenant.query(
            "SELECT DISTINCT lower(c.primary_email) AS email FROM customers c WHERE lower(c.primary_email) = ANY (?) AND $VisibleCustomer",
            listOf(tenant.createArrayOf("text", wanted.toTypedArray())),
        ) { it.getString("email") }.toSet()
    }

    /**
     * Reuses the visible customer with the same e-mail (any case); otherwise creates one (type eventos, active). The caller
     * tells Clientes with access.customerChanged after the commit when [CustomerLookup.created].
     */
    fun findOrCreateCustomer(tenant: Connection, input: NewCustomerInput): CustomerLookup {
        val name = input.name.trim()
        val email = input.email.trim()
        val phone = input.phone.trim()
        val errors = buildList {
            if (name.isEmpty()) add("Escribe el nombre del cliente.")
            if (name.length > 200) add("El nombre del cliente admite hasta 200 caracteres.")
            if (email.isNotEmpty() && (!email.contains('@') || email.length > 254 || email.any { it.isWhitespace() })) add("Escribe un correo válido o déjalo vacío.")
            if (phone.length > 40) add("El teléfono admite hasta 40 caracteres.")
        }
        if (errors.isNotEmpty()) throw EventosValidationException(errors)
        if (email.isNotEmpty()) {
            val existing = tenant.query(
                "SELECT c.id FROM customers c WHERE lower(c.primary_email) = lower(?) AND $VisibleCustomer ORDER BY c.created_at LIMIT 1",
                listOf(email),
            ) { it.uuid("id") }.firstOrNull()
            if (existing != null) return CustomerLookup(existing, created = false)
        }
        val id = UUID.randomUUID()
        tenant.update(
            "INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type) VALUES (?, ?, ?, ?, 'active', 'eventos')",
            listOf(id, name, email, phone),
        )
        return CustomerLookup(id, created = true)
    }
}
