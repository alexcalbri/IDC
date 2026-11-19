package com.ideasdeveloper.idc.modules.membresia

import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.UUID

/**
 * Acceso a datos del modulo con JDBC sincrono sobre la conexion de la empresa. El llamador lo envuelve en
 * withContext(Dispatchers.IO). Todo el SQL es parametrizado: solo se concatenan constantes de este archivo.
 * Los clientes se leen con el filtro de visibilidad de Clientes y nunca se devuelve customer_code ni campos personalizados.
 */
object MembresiaRepository {
    const val DefaultLimit = 200
    private const val MaxLimit = 500

    // Clientes visibles en esta empresa: propios o compartidos, y no archivados.
    private const val VisibleCustomer = "(c.origin_company_code = '' OR c.shared) AND c.status <> 'archived'"

    private const val MembershipColumns = """
        m.id, m.customer_id, c.display_name, m.plan_id, m.plan_name, m.starts_on, m.ends_on, m.amount, m.currency, m.status,
        m.source, m.host_user_id, m.host_name, m.unit, m.notes, m.included_visits, m.visits_used, m.guest_passes_total,
        m.guest_passes_used, m.renewed_from, m.cancelled_at, m.cancel_reason, m.expiring_notified_on, m.created_by
    """
    private const val MembershipFrom = "FROM membresia_memberships m JOIN customers c ON c.id = m.customer_id"
    private const val PlanColumns = "id, name, description, price, duration_days, included_visits, guest_passes, wifi_access, active, display_order"

    // ---------------------------------------------------------------- ajustes

    fun settings(tenant: Connection): MembresiaSettings =
        query(tenant, "SELECT currency, expiring_days, sources FROM membresia_settings WHERE id = 1") { rows ->
            MembresiaSettings(
                rows.getString(1),
                rows.getInt(2),
                (rows.getArray(3)?.array as? Array<*>)?.map { it.toString() }.orEmpty(),
            )
        }.firstOrNull() ?: MembresiaSettings.Default

    fun saveSettings(tenant: Connection, settings: MembresiaSettings) {
        val errors = MembresiaRules.validateSettings(settings)
        if (errors.isNotEmpty()) throw MembresiaValidationException(errors)
        val sources = tenant.createArrayOf("text", settings.sources.toTypedArray())
        try {
            execute(
                tenant,
                """
                INSERT INTO membresia_settings (id, currency, expiring_days, sources) VALUES (1, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET currency = EXCLUDED.currency, expiring_days = EXCLUDED.expiring_days,
                    sources = EXCLUDED.sources, updated_at = CURRENT_TIMESTAMP
                """.trimIndent(),
                listOf(settings.currency, settings.expiringDays, sources),
            )
        } finally {
            sources.free()
        }
    }

    // ---------------------------------------------------------------- planes

    fun plans(tenant: Connection, includeInactive: Boolean = false): List<Plan> =
        query(
            tenant,
            "SELECT $PlanColumns FROM membresia_plans WHERE (? OR active) ORDER BY display_order, lower(name)",
            listOf(includeInactive),
            ::readPlan,
        )

    fun plan(tenant: Connection, id: UUID): Plan? =
        query(tenant, "SELECT $PlanColumns FROM membresia_plans WHERE id = ?", listOf(id), ::readPlan).firstOrNull()

    /** Crea ([id] nulo) o actualiza un plan. Cambiar un plan no altera las membresias ya vendidas (llevan su copia). */
    fun savePlan(tenant: Connection, id: UUID?, input: PlanInput): Plan {
        val errors = MembresiaRules.validatePlan(input)
        if (errors.isNotEmpty()) throw MembresiaValidationException(errors)
        val clean = input.copy(name = input.name.trim(), description = input.description.trim())
        try {
            if (id == null) {
                val created = query(
                    tenant,
                    """
                    INSERT INTO membresia_plans (name, description, price, duration_days, included_visits, guest_passes, wifi_access, display_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING $PlanColumns
                    """.trimIndent(),
                    listOf(clean.name, clean.description, clean.price, clean.durationDays, clean.includedVisits, clean.guestPasses, clean.wifiAccess, clean.displayOrder),
                    ::readPlan,
                )
                return created.first()
            }
            val updated = query(
                tenant,
                """
                UPDATE membresia_plans SET name = ?, description = ?, price = ?, duration_days = ?, included_visits = ?,
                    guest_passes = ?, wifi_access = ?, display_order = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? RETURNING $PlanColumns
                """.trimIndent(),
                listOf(clean.name, clean.description, clean.price, clean.durationDays, clean.includedVisits, clean.guestPasses, clean.wifiAccess, clean.displayOrder, id),
                ::readPlan,
            )
            return updated.firstOrNull() ?: throw MembresiaValidationException(listOf("El plan no existe."))
        } catch (failure: SQLException) {
            if (failure.sqlState == UniqueViolation) throw MembresiaValidationException(listOf("Ya existe un plan con ese nombre."))
            throw failure
        }
    }

    /** Un plan con membresias no se borra: se desactiva (deja de ofrecerse). */
    fun setPlanActive(tenant: Connection, id: UUID, active: Boolean): Boolean =
        execute(tenant, "UPDATE membresia_plans SET active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", listOf(active, id)) > 0

    // ---------------------------------------------------------------- clientes y miembros

    /**
     * Clientes visibles cuyo nombre, correo o telefono contienen [text] (ILIKE escapado), con su membresia actual.
     * [onlyMembers] = solo clientes con alguna membresia (recepcion); falso = cualquier cliente (alta de membresia).
     */
    fun searchMembers(
        tenant: Connection,
        text: String,
        today: LocalDate,
        expiringDays: Int,
        limit: Int = 10,
        onlyMembers: Boolean = true,
    ): List<MemberSummary> {
        val clean = text.trim().take(100)
        if (clean.isEmpty()) return emptyList()
        val pattern = "%${MembresiaRules.escapeLike(clean)}%"
        val customers = query(
            tenant,
            """
            SELECT c.id, c.display_name, c.primary_email, c.primary_phone
            FROM customers c
            WHERE $VisibleCustomer
              AND (c.display_name ILIKE ? OR c.primary_email ILIKE ? OR c.primary_phone ILIKE ?)
              AND (NOT ? OR EXISTS (SELECT 1 FROM membresia_memberships x WHERE x.customer_id = c.id))
            ORDER BY lower(c.display_name), c.id
            LIMIT ?
            """.trimIndent(),
            listOf(pattern, pattern, pattern, onlyMembers, limit.coerceIn(1, 50)),
        ) { rows -> Triple(rows.uuid(1), rows.getString(2), rows.getString(3) to rows.getString(4)) }
        if (customers.isEmpty()) return emptyList()
        val byCustomer = membershipsOfCustomers(tenant, customers.map { it.first })
        return customers.map { (id, name, contact) ->
            MemberSummary(id, name, contact.first, contact.second, MembresiaRules.currentMembership(byCustomer[id].orEmpty(), today, expiringDays))
        }
    }

    /** Ficha de un cliente visible: sus membresias (la de fin mas lejano primero) y sus ultimas 20 visitas. Null si no existe o no es visible. */
    fun member(tenant: Connection, customerId: UUID, today: LocalDate, expiringDays: Int): MemberDetail? {
        val customer = query(
            tenant,
            "SELECT c.id, c.display_name, c.primary_email, c.primary_phone FROM customers c WHERE c.id = ? AND $VisibleCustomer",
            listOf(customerId),
        ) { rows -> MemberSummary(rows.uuid(1), rows.getString(2), rows.getString(3), rows.getString(4), null) }.firstOrNull() ?: return null
        val memberships = membershipsOfCustomers(tenant, listOf(customerId))[customerId].orEmpty()
        val visits = query(
            tenant,
            """
            $VisitSelect WHERE v.customer_id = ? AND $VisibleCustomer ORDER BY v.visited_at DESC LIMIT 20
            """.trimIndent(),
            listOf(customerId),
            ::readVisit,
        )
        return MemberDetail(customer.copy(current = MembresiaRules.currentMembership(memberships, today, expiringDays)), memberships, visits)
    }

    /** Nombre, correo y telefono de un cliente visible (para mostrar el cliente elegido en un formulario). */
    fun visibleCustomer(tenant: Connection, customerId: UUID): MemberSummary? =
        query(
            tenant,
            "SELECT c.id, c.display_name, c.primary_email, c.primary_phone FROM customers c WHERE c.id = ? AND $VisibleCustomer",
            listOf(customerId),
        ) { rows -> MemberSummary(rows.uuid(1), rows.getString(2), rows.getString(3), rows.getString(4), null) }.firstOrNull()

    /**
     * Codigo de cliente (la credencial WiFi) de un cliente visible. Unico lugar que lo lee: la ficha del miembro lo muestra solo
     * a quien tenga membresia.visits o membresia.manage; nunca va en listas, busquedas, MCP ni avisos.
     */
    fun customerCode(tenant: Connection, customerId: UUID): String? =
        query(tenant, "SELECT c.customer_code FROM customers c WHERE c.id = ? AND $VisibleCustomer", listOf(customerId)) { rows -> rows.getString(1) }.firstOrNull()

    /** Ejecuta [block] en una transaccion (confirma al terminar, deshace ante un error); si ya hay una, participa en ella. */
    fun <T> inTransaction(tenant: Connection, block: () -> T): T = transaction(tenant, block)

    /**
     * Reutiliza el cliente visible con el mismo correo (sin distinguir mayusculas); si no hay, crea uno
     * (tipo membresia, activo). El llamador avisa a Clientes con access.customerChanged tras confirmar si [CustomerLookup.created].
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
        if (errors.isNotEmpty()) throw MembresiaValidationException(errors)
        if (email.isNotEmpty()) {
            val existing = query(
                tenant,
                "SELECT c.id FROM customers c WHERE lower(c.primary_email) = lower(?) AND $VisibleCustomer ORDER BY c.created_at LIMIT 1",
                listOf(email),
            ) { rows -> rows.uuid(1) }.firstOrNull()
            if (existing != null) return CustomerLookup(existing, created = false)
        }
        val id = UUID.randomUUID()
        execute(
            tenant,
            "INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type) VALUES (?, ?, ?, ?, 'active', 'membresia')",
            listOf(id, name, email, phone),
        )
        return CustomerLookup(id, created = true)
    }

    /** Usuarios activos que pueden ser anfitriones: dueños o con algun permiso de este modulo. */
    fun hostCandidates(tenant: Connection): List<HostCandidate> = query(
        tenant,
        """
        SELECT users.id, COALESCE(NULLIF(trim(users.display_name), ''), users.postgres_role)
        FROM application_users users
        WHERE users.is_active
          AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
               OR EXISTS (SELECT 1 FROM application_user_permissions granted
                          WHERE granted.user_id = users.id AND granted.permission_id LIKE 'membresia.%'))
        ORDER BY 2
        """.trimIndent(),
    ) { rows -> HostCandidate(rows.uuid(1), rows.getString(2)) }

    /** Usuarios activos que reciben avisos de vencimiento: los dueños y quienes gestionan membresias, mas el anfitrion si sigue activo. */
    fun notificationRecipients(tenant: Connection, hostUserId: UUID?): List<UUID> = query(
        tenant,
        """
        SELECT users.id
        FROM application_users users
        WHERE users.is_active
          AND (users.id = ?
               OR EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
               OR EXISTS (SELECT 1 FROM application_user_permissions granted
                          WHERE granted.user_id = users.id AND granted.permission_id = ?))
        ORDER BY users.id
        """.trimIndent(),
        listOf(hostUserId, MembresiaModule.Manage),
    ) { rows -> rows.uuid(1) }.distinct()

    // ---------------------------------------------------------------- membresias: lecturas

    fun membership(tenant: Connection, id: UUID): Membership? =
        query(tenant, "SELECT $MembershipColumns $MembershipFrom WHERE m.id = ? AND $VisibleCustomer", listOf(id), ::readMembership).firstOrNull()

    /** Membresias segun el estado que ve el equipo (null = todas), con tope de [limit] (200 por defecto). */
    fun memberships(tenant: Connection, status: DisplayStatus?, today: LocalDate, expiringDays: Int, limit: Int = DefaultLimit): List<Membership> {
        val (condition, params) = statusCondition(status, today, expiringDays)
        val order = if (status == DisplayStatus.VENCIDA || status == DisplayStatus.CANCELADA || status == null) "DESC" else "ASC"
        return query(
            tenant,
            "SELECT $MembershipColumns $MembershipFrom WHERE $VisibleCustomer AND $condition ORDER BY m.ends_on $order, m.id LIMIT ?",
            params + limit.coerceIn(1, DefaultLimit),
            ::readMembership,
        )
    }

    /** Cuantas membresias hay por estado visible (todas las claves presentes, con 0 si no hay), en una sola consulta. */
    fun countsByStatus(tenant: Connection, today: LocalDate, expiringDays: Int): Map<DisplayStatus, Int> {
        val entries = DisplayStatus.entries
        val conditions = entries.map { statusCondition(it, today, expiringDays) }
        val sql = "SELECT " + conditions.joinToString(", ") { "count(*) FILTER (WHERE ${it.first})" } + " $MembershipFrom WHERE $VisibleCustomer"
        return query(tenant, sql, conditions.flatMap { it.second }) { rows ->
            entries.mapIndexed { index, status -> status to rows.getInt(index + 1) }.toMap()
        }.first()
    }

    /** Membresias vigentes hoy (activas o por vencer) agrupadas por nombre de plan, de fin mas cercano a mas lejano. */
    fun activeByPlan(tenant: Connection, today: LocalDate, limit: Int = 1000): Map<String, List<Membership>> =
        query(
            tenant,
            """
            SELECT $MembershipColumns $MembershipFrom
            WHERE $VisibleCustomer AND m.status <> 'cancelada' AND m.starts_on <= ? AND m.ends_on >= ?
            ORDER BY m.plan_name, m.ends_on, m.id LIMIT ?
            """.trimIndent(),
            listOf(today, today, limit.coerceIn(1, 2000)),
            ::readMembership,
        ).groupByTo(LinkedHashMap()) { it.planName }

    /** Visitas (de miembros e invitados) de un dia, la mas reciente primero. */
    fun visitsOn(tenant: Connection, date: LocalDate, limit: Int = 300): List<Visit> =
        query(
            tenant,
            "$VisitSelect WHERE v.visit_on = ? AND $VisibleCustomer ORDER BY v.visited_at DESC LIMIT ?",
            listOf(date, limit.coerceIn(1, MaxLimit)),
            ::readVisit,
        )

    fun visitsCountOn(tenant: Connection, date: LocalDate): Int =
        query(
            tenant,
            "SELECT count(*) FROM membresia_visits v JOIN customers c ON c.id = v.customer_id WHERE v.visit_on = ? AND $VisibleCustomer",
            listOf(date),
        ) { rows -> rows.getInt(1) }.first()

    /**
     * Ingresos de los ultimos [months] meses (incluido el actual), agregados en SQL por mes de inicio, plan y moneda;
     * las membresias canceladas no cuentan.
     */
    fun revenueRows(tenant: Connection, today: LocalDate, months: Int = 12): List<RevenueRow> {
        val current = YearMonth.from(today)
        val first = current.minusMonths(months.toLong() - 1).atDay(1)
        return query(
            tenant,
            """
            SELECT date_trunc('month', starts_on)::date, plan_name, currency, sum(amount), count(*)
            FROM membresia_memberships
            WHERE status <> 'cancelada' AND starts_on >= ? AND starts_on <= ?
            GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
            """.trimIndent(),
            listOf(first, current.atEndOfMonth()),
        ) { rows -> RevenueRow(rows.date(1), rows.getString(2), rows.getString(3), rows.getBigDecimal(4), rows.getInt(5)) }
    }

    // ---------------------------------------------------------------- membresias: cambios

    /** Alta de una membresia de un cliente existente copiando los datos del [plan]. Lanza [MembresiaValidationException] si algo no es valido. */
    fun createMembership(tenant: Connection, input: MembershipInput, plan: Plan, today: LocalDate, createdBy: String): Membership =
        createMembershipWithCustomer(tenant, input, null, plan, today, createdBy).first

    /**
     * Alta atomica: en UNA transaccion valida, busca o crea el cliente ([newCustomer] no nulo; entonces [MembershipInput.customerId] se ignora)
     * y crea la membresia. Un candado por correo evita clientes duplicados y otro por cliente evita membresias solapadas del mismo plan.
     * Devuelve la membresia y si se creo el cliente (el llamador avisa a Clientes con customerChanged tras confirmar).
     */
    fun createMembershipWithCustomer(
        tenant: Connection,
        input: MembershipInput,
        newCustomer: NewCustomerInput?,
        plan: Plan,
        today: LocalDate,
        createdBy: String,
    ): Pair<Membership, Boolean> = transaction(tenant) {
        val settings = settings(tenant)
        val amount = input.amount ?: plan.price
        val startsOn = input.startsOn ?: today
        val errors = buildList {
            if (!plan.active) add("El plan ${plan.name} está desactivado.")
            if (newCustomer == null && visibleCustomer(tenant, input.customerId) == null) add("El cliente no existe o no está disponible.")
            addAll(MembresiaRules.validateMembership(input, amount, settings))
            addAll(MembresiaRules.validateDates(startsOn, MembresiaRules.endsOn(startsOn, plan.durationDays), today))
        }
        if (errors.isNotEmpty()) throw MembresiaValidationException(errors)
        val lookup = if (newCustomer != null) {
            if (newCustomer.email.isNotBlank()) advisoryLock(tenant, newCustomer.email.trim().lowercase())
            findOrCreateCustomer(tenant, newCustomer)
        } else {
            CustomerLookup(input.customerId, created = false)
        }
        insertMembership(tenant, lookup.customerId, plan, startsOn, amount, settings, input.source, input.hostUserId, input.hostName, input.unit, input.notes, null, createdBy, today) to lookup.created
    }

    /** Renueva: crea una nueva fila enlazada (renewed_from) que empieza en MembresiaRules.renewalStart salvo que se indique otra fecha. */
    fun renew(tenant: Connection, id: UUID, input: RenewalInput, today: LocalDate, createdBy: String): Membership = transaction(tenant) {
        val previous = lockedMembership(tenant, id) ?: throw MembresiaValidationException(listOf("La membresía no existe."))
        val settings = settings(tenant)
        val plan = plan(tenant, input.planId ?: previous.planId) ?: throw MembresiaValidationException(listOf("El plan no existe."))
        val startsOn = input.startsOn ?: MembresiaRules.renewalStart(previous.endsOn, today)
        val amount = input.amount ?: plan.price
        val errors = buildList {
            if (previous.status == StoredStatus.Cancelada) add("Una membresía cancelada no se renueva: crea una nueva.")
            if (!plan.active) add("El plan ${plan.name} está desactivado.")
            val alreadyRenewed = query(
                tenant,
                "SELECT 1 FROM membresia_memberships WHERE renewed_from = ? AND status <> 'cancelada' LIMIT 1",
                listOf(id),
            ) { 1 }.isNotEmpty()
            if (alreadyRenewed) add("Esta membresía ya fue renovada.")
            val probe = MembershipInput(previous.customerId, plan.id, startsOn, amount, previous.source, previous.hostUserId, previous.hostName, previous.unit, "")
            addAll(MembresiaRules.validateMembership(probe, amount, settings.copy(sources = settings.sources + previous.source)))
            addAll(MembresiaRules.validateDates(startsOn, MembresiaRules.endsOn(startsOn, plan.durationDays), today))
        }
        if (errors.isNotEmpty()) throw MembresiaValidationException(errors)
        insertMembership(tenant, previous.customerId, plan, startsOn, amount, settings, previous.source, previous.hostUserId, previous.hostName, previous.unit, "", previous.id, createdBy, today)
    }

    /** Edita fin, monto, fuente, anfitrion, unidad y notas; recalcula el estado guardado y, si cambia el fin, vuelve a permitir el aviso. Null si no existe o no es visible. */
    fun edit(tenant: Connection, id: UUID, edit: MembershipEdit, today: LocalDate): Membership? = transaction(tenant) {
        val current = lockedMembership(tenant, id) ?: return@transaction null
        val settings = settings(tenant)
        val errors = buildList {
            if (current.status == StoredStatus.Cancelada) add("Una membresía cancelada no se edita.")
            addAll(MembresiaRules.validateEdit(edit, current.startsOn, settings.copy(sources = settings.sources + current.source), today))
        }
        if (errors.isNotEmpty()) throw MembresiaValidationException(errors)
        ensureNoOverlap(tenant, current.customerId, current.planId, current.startsOn, edit.endsOn, excludeId = id)
        val status = MembresiaRules.initialStatus(current.startsOn, edit.endsOn, today)
        val hostName = resolveHostName(tenant, edit.hostUserId, edit.hostName)
        execute(
            tenant,
            """
            UPDATE membresia_memberships SET ends_on = ?, amount = ?, status = ?, source = ?, host_user_id = ?, host_name = ?,
                unit = ?, notes = ?,
                expiring_notified_on = CASE WHEN ends_on = ? THEN expiring_notified_on ELSE NULL END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status <> 'cancelada'
            """.trimIndent(),
            listOf(edit.endsOn, edit.amount, status, edit.source, edit.hostUserId, hostName, edit.unit.trim(), edit.notes.trim(), edit.endsOn, id),
        )
        membershipForJob(tenant, id)
    }

    /** Cancela (queda en el historial; no se recrea). Null si no existe, no es visible o ya estaba cancelada. */
    fun cancel(tenant: Connection, id: UUID, reason: String): Membership? = transaction(tenant) {
        val clean = reason.trim()
        if (clean.length > 1000) throw MembresiaValidationException(listOf("El motivo admite hasta 1000 caracteres."))
        val current = lockedMembership(tenant, id) ?: return@transaction null
        if (current.status == StoredStatus.Cancelada) return@transaction null
        execute(
            tenant,
            "UPDATE membresia_memberships SET status = 'cancelada', cancelled_at = CURRENT_TIMESTAMP, cancel_reason = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status <> 'cancelada'",
            listOf(clean, id),
        )
        membershipForJob(tenant, id)
    }

    /**
     * Registra la visita de hoy del miembro. Idempotente por dia (indice parcial): un doble toque devuelve
     * [VisitResult.AlreadyToday]. Si hay aviso (vencida, programada, visitas agotadas) pide [override]; la cancelada se rechaza siempre.
     */
    fun recordMemberVisit(tenant: Connection, membershipId: UUID, today: LocalDate, override: Boolean, recordedBy: String, note: String = ""): VisitResult = transaction(tenant) {
        val membership = lockedMembership(tenant, membershipId) ?: throw MembresiaValidationException(listOf("La membresía no existe."))
        val already = query(
            tenant,
            "SELECT 1 FROM membresia_visits WHERE membership_id = ? AND visit_on = ? AND kind = 'miembro'",
            listOf(membershipId, today),
        ) { 1 }.isNotEmpty()
        if (already) return@transaction VisitResult.AlreadyToday
        val overridden = when (val decision = MembresiaRules.checkIn(membership, today, settings(tenant).expiringDays, override)) {
            is CheckInDecision.Refused -> return@transaction VisitResult.Refused(decision.reason)
            is CheckInDecision.NeedsOverride -> return@transaction VisitResult.NeedsOverride(decision.reason)
            is CheckInDecision.Allowed -> decision.overridden
        }
        val inserted = execute(
            tenant,
            """
            INSERT INTO membresia_visits (membership_id, customer_id, kind, visit_on, override, recorded_by, note)
            VALUES (?, ?, 'miembro', ?, ?, ?, ?)
            ON CONFLICT (membership_id, visit_on) WHERE kind = 'miembro' DO NOTHING
            """.trimIndent(),
            listOf(membershipId, membership.customerId, today, overridden, recordedBy.take(160), note.trim().take(500)),
        )
        if (inserted == 0) return@transaction VisitResult.AlreadyToday
        execute(tenant, "UPDATE membresia_memberships SET visits_used = visits_used + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", listOf(membershipId))
        VisitResult.Recorded(overridden)
    }

    /** Descuenta un pase de invitado (UPDATE atomico con cupo) y registra la visita del invitado. */
    fun useGuestPass(tenant: Connection, membershipId: UUID, guestName: String, today: LocalDate, recordedBy: String): GuestPassResult = transaction(tenant) {
        val name = guestName.trim()
        if (name.isEmpty()) throw MembresiaValidationException(listOf("Escribe el nombre del invitado."))
        if (name.length > 160) throw MembresiaValidationException(listOf("El nombre del invitado admite hasta 160 caracteres."))
        val membership = lockedMembership(tenant, membershipId) ?: throw MembresiaValidationException(listOf("La membresía no existe."))
        when (val decision = MembresiaRules.guestPassAllowed(membership, today, settings(tenant).expiringDays)) {
            is CheckInDecision.Refused -> return@transaction GuestPassResult.Refused(decision.reason)
            is CheckInDecision.NeedsOverride -> return@transaction GuestPassResult.Refused(decision.reason)
            is CheckInDecision.Allowed -> Unit
        }
        val customerId = query(
            tenant,
            """
            UPDATE membresia_memberships SET guest_passes_used = guest_passes_used + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND guest_passes_used < guest_passes_total AND status <> 'cancelada' AND starts_on <= ? AND ends_on >= ?
            RETURNING customer_id
            """.trimIndent(),
            listOf(membershipId, today, today),
        ) { rows -> rows.uuid(1) }.firstOrNull() ?: return@transaction GuestPassResult.Refused("No quedan pases de invitado.")
        execute(
            tenant,
            "INSERT INTO membresia_visits (membership_id, customer_id, kind, guest_name, visit_on, recorded_by) VALUES (?, ?, 'invitado', ?, ?, ?)",
            listOf(membershipId, customerId, name, today, recordedBy.take(160)),
        )
        GuestPassResult.Recorded
    }

    // ---------------------------------------------------------------- trabajo diario

    /** programada -> activa cuando ya empezo. Devuelve cuantas cambio (lote acotado). */
    fun activateStarted(tenant: Connection, today: LocalDate, limit: Int = 500): Int = execute(
        tenant,
        """
        UPDATE membresia_memberships SET status = 'activa', updated_at = CURRENT_TIMESTAMP
        WHERE id IN (SELECT id FROM membresia_memberships WHERE status = 'programada' AND starts_on <= ? ORDER BY starts_on, id LIMIT ?)
        """.trimIndent(),
        listOf(today, limit.coerceIn(1, MaxLimit)),
    )

    /** activa -> vencida cuando su fin ya paso. Devuelve las membresias vencidas ahora. */
    fun expireEnded(tenant: Connection, today: LocalDate, limit: Int = 500): List<Membership> = transaction(tenant) {
        val ids = query(
            tenant,
            """
            UPDATE membresia_memberships SET status = 'vencida', updated_at = CURRENT_TIMESTAMP
            WHERE id IN (SELECT id FROM membresia_memberships WHERE status = 'activa' AND ends_on < ? ORDER BY ends_on, id LIMIT ?)
            RETURNING id
            """.trimIndent(),
            listOf(today, limit.coerceIn(1, MaxLimit)),
        ) { rows -> rows.uuid(1) }
        membershipsByIds(tenant, ids)
    }

    /** Activas que vencen entre hoy y hoy + [days] y aun no se avisaron (lote acotado). */
    fun expiringToNotify(tenant: Connection, today: LocalDate, days: Int, limit: Int = 200): List<Membership> = query(
        tenant,
        """
        SELECT $MembershipColumns $MembershipFrom
        WHERE m.status = 'activa' AND m.starts_on <= ? AND m.ends_on >= ? AND m.ends_on <= ? AND m.expiring_notified_on IS NULL
          AND c.status <> 'archived'
          AND NOT EXISTS (SELECT 1 FROM membresia_memberships r WHERE r.renewed_from = m.id AND r.status <> 'cancelada')
        ORDER BY m.ends_on, m.id LIMIT ?
        """.trimIndent(),
        listOf(today, today, today.plusDays(days.toLong()), limit.coerceIn(1, MaxLimit)),
        ::readMembership,
    )

    fun markExpiringNotified(tenant: Connection, id: UUID, today: LocalDate) {
        execute(tenant, "UPDATE membresia_memberships SET expiring_notified_on = ? WHERE id = ?", listOf(today, id))
    }

    /**
     * Programadas y activas de clientes no archivados (con la marca wifi de su plan) para sincronizar los pases del hotspot,
     * paginadas por keyset (ends_on, id): [after] es la ultima fila de la pagina anterior.
     */
    fun syncCandidates(tenant: Connection, limit: Int = 500, after: Pair<LocalDate, UUID>? = null): List<Pair<Membership, Boolean>> = query(
        tenant,
        """
        SELECT $MembershipColumns, COALESCE(p.wifi_access, false)
        $MembershipFrom LEFT JOIN membresia_plans p ON p.id = m.plan_id
        WHERE m.status IN ('programada', 'activa') AND c.status <> 'archived'
        ${if (after != null) "AND (m.ends_on, m.id) > (?, ?)" else ""}
        ORDER BY m.ends_on, m.id LIMIT ?
        """.trimIndent(),
        (if (after != null) listOf(after.first, after.second) else emptyList()) + limit.coerceIn(1, MaxLimit),
    ) { rows -> readMembership(rows) to rows.getBoolean(25) }

    /**
     * Borra los pases de la fuente "membresia" cuyo source_ref no corresponde a una membresia programada o activa
     * con plan wifi de un cliente no archivado. Devuelve cuantos borro (lote acotado). Solo se llama si el hotspot existe.
     */
    fun deleteOrphanPasses(tenant: Connection, limit: Int = 500): Int = execute(
        tenant,
        """
        DELETE FROM hotspot_access_passes WHERE id IN (
            SELECT p.id FROM hotspot_access_passes p
            WHERE p.source_id = ? AND NOT EXISTS (
                SELECT 1 FROM membresia_memberships m
                JOIN membresia_plans pl ON pl.id = m.plan_id
                JOIN customers c ON c.id = m.customer_id
                WHERE p.source_ref = 'membership:' || m.id::text
                  AND m.status IN ('programada', 'activa') AND pl.wifi_access AND c.status <> 'archived')
            LIMIT ?)
        """.trimIndent(),
        listOf(MembresiaModule.HotspotSourceId, limit.coerceIn(1, MaxLimit)),
    )

    /** Marca wifi del plan de la membresia (false si el plan ya no existe). */
    fun planWifi(tenant: Connection, planId: UUID): Boolean =
        query(tenant, "SELECT wifi_access FROM membresia_plans WHERE id = ?", listOf(planId)) { rows -> rows.getBoolean(1) }.firstOrNull() ?: false

    // ---------------------------------------------------------------- internos

    private const val UniqueViolation = "23505"

    private const val VisitSelect = """
        SELECT v.id, v.membership_id, v.customer_id, c.display_name, v.kind, v.guest_name, v.visit_on, v.visited_at, v.override, v.recorded_by, v.note
        FROM membresia_visits v JOIN customers c ON c.id = v.customer_id
    """

    /** Condicion SQL (con parametros) de cada estado visible; igual que MembresiaRules.displayStatus. */
    private fun statusCondition(status: DisplayStatus?, today: LocalDate, expiringDays: Int): Pair<String, List<Any?>> {
        val limit = today.plusDays(expiringDays.toLong())
        return when (status) {
            null -> "TRUE" to emptyList()
            DisplayStatus.CANCELADA -> "m.status = 'cancelada'" to emptyList()
            DisplayStatus.VENCIDA -> "(m.status <> 'cancelada' AND m.ends_on < ?)" to listOf(today)
            DisplayStatus.PROGRAMADA -> "(m.status <> 'cancelada' AND m.ends_on >= ? AND m.starts_on > ?)" to listOf(today, today)
            DisplayStatus.POR_VENCER ->
                "(m.status <> 'cancelada' AND m.starts_on <= ? AND m.ends_on >= ? AND m.ends_on <= ?)" to listOf(today, today, limit)
            DisplayStatus.ACTIVA -> "(m.status <> 'cancelada' AND m.starts_on <= ? AND m.ends_on > ?)" to listOf(today, limit)
        }
    }

    private fun membershipsOfCustomers(tenant: Connection, customerIds: List<UUID>): Map<UUID, List<Membership>> {
        val ids = tenant.createArrayOf("uuid", customerIds.toTypedArray())
        try {
            return query(
                tenant,
                "SELECT $MembershipColumns $MembershipFrom WHERE m.customer_id = ANY (?) AND $VisibleCustomer ORDER BY m.ends_on DESC, m.id",
                listOf(ids),
                ::readMembership,
            ).groupBy { it.customerId }
        } finally {
            ids.free()
        }
    }

    // Lectura interna sin filtro de visibilidad (trabajos y operaciones ya autorizadas sobre un id conocido).
    /** Lectura sin filtro de visibilidad: solo para trabajos y para releer una fila ya autorizada y bloqueada. */
    fun membershipForJob(tenant: Connection, id: UUID): Membership? =
        query(tenant, "SELECT $MembershipColumns $MembershipFrom WHERE m.id = ?", listOf(id), ::readMembership).firstOrNull()

    private fun membershipsByIds(tenant: Connection, ids: List<UUID>): List<Membership> {
        if (ids.isEmpty()) return emptyList()
        val array = tenant.createArrayOf("uuid", ids.toTypedArray())
        try {
            return query(tenant, "SELECT $MembershipColumns $MembershipFrom WHERE m.id = ANY (?) ORDER BY m.ends_on, m.id", listOf(array), ::readMembership)
        } finally {
            array.free()
        }
    }

    // Lee y bloquea la fila de la membresia (FOR UPDATE) solo si su cliente es visible: null si no existe o no es visible.
    private fun lockedMembership(tenant: Connection, id: UUID): Membership? {
        val locked = query(
            tenant,
            "SELECT m.id FROM membresia_memberships m JOIN customers c ON c.id = m.customer_id WHERE m.id = ? AND $VisibleCustomer FOR UPDATE OF m",
            listOf(id),
        ) { rows -> rows.uuid(1) }
        if (locked.isEmpty()) return null
        return membershipForJob(tenant, id)
    }

    // Variante sin filtro de visibilidad para los trabajos.
    fun lockedMembershipForJob(tenant: Connection, id: UUID): Membership? {
        val locked = query(tenant, "SELECT id FROM membresia_memberships WHERE id = ? FOR UPDATE", listOf(id)) { rows -> rows.uuid(1) }
        return if (locked.isEmpty()) null else membershipForJob(tenant, id)
    }

    // Candado de transaccion por texto (correo o cliente): serializa altas concurrentes del mismo cliente.
    private fun advisoryLock(tenant: Connection, key: String) {
        query(tenant, "SELECT pg_advisory_xact_lock(hashtext(?))", listOf(key)) { 1 }
    }

    // Rechaza una membresia no cancelada del mismo cliente y plan que se solape con [startsOn, endsOn].
    private fun ensureNoOverlap(tenant: Connection, customerId: UUID, planId: UUID, startsOn: LocalDate, endsOn: LocalDate, excludeId: UUID? = null) {
        advisoryLock(tenant, "membresia:$customerId")
        val overlaps = query(
            tenant,
            """
            SELECT 1 FROM membresia_memberships
            WHERE customer_id = ? AND plan_id = ? AND status <> 'cancelada' AND starts_on <= ? AND ends_on >= ?
              AND (?::uuid IS NULL OR id <> ?::uuid)
            LIMIT 1
            """.trimIndent(),
            listOf(customerId, planId, endsOn, startsOn, excludeId, excludeId),
        ) { 1 }.isNotEmpty()
        if (overlaps) throw MembresiaValidationException(listOf("Ya tiene una membresía vigente de ese plan en esas fechas."))
    }

    private fun resolveHostName(tenant: Connection, hostUserId: UUID?, given: String): String {
        if (given.isNotBlank()) return given.trim()
        if (hostUserId == null) return ""
        return hostCandidates(tenant).firstOrNull { it.userId == hostUserId }?.name.orEmpty()
    }

    private fun insertMembership(
        tenant: Connection,
        customerId: UUID,
        plan: Plan,
        startsOn: LocalDate,
        amount: BigDecimal,
        settings: MembresiaSettings,
        source: String,
        hostUserId: UUID?,
        hostName: String,
        unit: String,
        notes: String,
        renewedFrom: UUID?,
        createdBy: String,
        today: LocalDate,
    ): Membership {
        val endsOn = MembresiaRules.endsOn(startsOn, plan.durationDays)
        ensureNoOverlap(tenant, customerId, plan.id, startsOn, endsOn)
        val id = query(
            tenant,
            """
            INSERT INTO membresia_memberships (customer_id, plan_id, plan_name, starts_on, ends_on, amount, currency, status, source,
                host_user_id, host_name, unit, notes, included_visits, guest_passes_total, renewed_from, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
            listOf(
                customerId, plan.id, plan.name, startsOn, endsOn, amount, settings.currency,
                MembresiaRules.initialStatus(startsOn, endsOn, today), source, hostUserId, resolveHostName(tenant, hostUserId, hostName),
                unit.trim(), notes.trim(), plan.includedVisits, plan.guestPasses, renewedFrom, createdBy.take(160),
            ),
        ) { rows -> rows.uuid(1) }.first()
        return membershipForJob(tenant, id) ?: error("La membresía recién creada no se pudo leer.")
    }

    private fun readPlan(rows: ResultSet) = Plan(
        id = rows.uuid(1),
        name = rows.getString(2),
        description = rows.getString(3),
        price = rows.getBigDecimal(4),
        durationDays = rows.getInt(5),
        includedVisits = rows.intOrNull(6),
        guestPasses = rows.getInt(7),
        wifiAccess = rows.getBoolean(8),
        active = rows.getBoolean(9),
        displayOrder = rows.getInt(10),
    )

    private fun readMembership(rows: ResultSet) = Membership(
        id = rows.uuid(1),
        customerId = rows.uuid(2),
        customerName = rows.getString(3),
        planId = rows.uuid(4),
        planName = rows.getString(5),
        startsOn = rows.date(6),
        endsOn = rows.date(7),
        amount = rows.getBigDecimal(8),
        currency = rows.getString(9),
        status = rows.getString(10),
        source = rows.getString(11),
        hostUserId = rows.uuidOrNull(12),
        hostName = rows.getString(13),
        unit = rows.getString(14),
        notes = rows.getString(15),
        includedVisits = rows.intOrNull(16),
        visitsUsed = rows.getInt(17),
        guestPassesTotal = rows.getInt(18),
        guestPassesUsed = rows.getInt(19),
        renewedFrom = rows.uuidOrNull(20),
        cancelledAt = rows.getObject(21, OffsetDateTime::class.java)?.toInstant(),
        cancelReason = rows.getString(22),
        expiringNotifiedOn = rows.getObject(23, LocalDate::class.java),
        createdBy = rows.getString(24),
    )

    private fun readVisit(rows: ResultSet) = Visit(
        id = rows.uuid(1),
        membershipId = rows.uuid(2),
        customerId = rows.uuid(3),
        customerName = rows.getString(4),
        kind = rows.getString(5),
        guestName = rows.getString(6),
        visitOn = rows.date(7),
        visitedAt = rows.getObject(8, OffsetDateTime::class.java).toInstant(),
        override = rows.getBoolean(9),
        recordedBy = rows.getString(10),
        note = rows.getString(11),
    )

    private fun ResultSet.uuid(index: Int): UUID = getObject(index, UUID::class.java)
    private fun ResultSet.uuidOrNull(index: Int): UUID? = getObject(index, UUID::class.java)
    private fun ResultSet.date(index: Int): LocalDate = getObject(index, LocalDate::class.java)
    private fun ResultSet.intOrNull(index: Int): Int? = getInt(index).takeUnless { wasNull() }

    private fun bind(statement: PreparedStatement, params: List<Any?>) {
        params.forEachIndexed { index, value ->
            if (value == null) statement.setNull(index + 1, Types.NULL) else statement.setObject(index + 1, value)
        }
    }

    private fun <T> query(tenant: Connection, sql: String, params: List<Any?> = emptyList(), map: (ResultSet) -> T): List<T> =
        tenant.prepareStatement(sql).use { statement ->
            bind(statement, params)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
        }

    private fun execute(tenant: Connection, sql: String, params: List<Any?> = emptyList()): Int =
        tenant.prepareStatement(sql).use { statement ->
            bind(statement, params)
            statement.executeUpdate()
        }

    /** Transaccion: confirma al terminar, deshace ante cualquier error y restaura autoCommit. Si ya hay una, participa en ella. */
    private fun <T> transaction(tenant: Connection, block: () -> T): T {
        if (!tenant.autoCommit) return block()
        tenant.autoCommit = false
        try {
            val result = block()
            tenant.commit()
            return result
        } catch (failure: Throwable) {
            try {
                tenant.rollback()
            } catch (rollbackFailure: SQLException) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            try {
                tenant.autoCommit = true
            } catch (_: SQLException) {
                // La conexion ya no sirve; el llamador la cierra.
            }
        }
    }
}
