package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Writes imported rows into Eventos. Every row is its own transaction, idempotent (the event is found by its import_key),
 * and never sends notices. Everything imported is a private event with its client.
 */
object EventosImportApply {
    private val log = LoggerFactory.getLogger(EventosImportApply::class.java)
    private const val MaxKey = 200
    private const val MaxCustomerName = 200
    private const val MaxPhone = 40
    private const val ImportAuthor = "Importación"
    private const val NoSpace = "Sin espacio"
    private const val CancelledInSource = "Cancelado en el sistema de origen."

    // ---- pure rules ----

    /** Cuts [text] to [max] characters ending with "…" instead of failing. */
    fun clip(text: String, max: Int): String = EventosRules.clip(text, max)

    /** The user whose email is [email], ignoring case and spaces. */
    fun matchUser(email: String?, users: List<EventosUser>): EventosUser? {
        val wanted = email?.trim()?.lowercase().orEmpty()
        if (wanted.isEmpty()) return null
        return users.firstOrNull { it.email.trim().lowercase() == wanted }
    }

    /** A usable e-mail in lower case, or "" when it is empty or not valid. */
    fun cleanEmail(email: String): String {
        val clean = email.trim()
        return if (clean.isEmpty() || !clean.contains('@') || clean.length > 254 || clean.any { it.isWhitespace() }) "" else clean.lowercase()
    }

    /** The notes followed by [extra]; the notes give up room for it, so the result never exceeds the notes limit. */
    fun notesWithExtra(notes: String, extra: String?): String {
        val base = notes.trim()
        val add = extra?.trim().orEmpty()
        if (add.isEmpty()) return clip(base, EventosRules.MaxText)
        if (base.isEmpty()) return clip(add, EventosRules.MaxText)
        val room = (EventosRules.MaxText - add.length - 2).coerceAtLeast(0)
        return if (room == 0) clip(add, EventosRules.MaxText) else clip(base, room) + "\n\n" + add
    }

    /** What an import would do, for the preview. Pure. [existingEmails] are the e-mails (any case) of customers that already exist. */
    fun summary(rows: List<ImportedEvent>, users: List<EventosUser>, existingEmails: Set<String> = emptySet()): ImportSummary {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val valid = mutableListOf<ImportedEvent>()
        rows.forEach { row ->
            val key = row.key.trim()
            when {
                key.isEmpty() -> problems += "Un ítem no tiene identificador y se omitirá."
                row.startsAt == null -> problems += "El ítem $key no tiene fecha y se omitirá."
                row.title.isBlank() && row.clientName.isBlank() -> problems += "El ítem $key no tiene título ni cliente y se omitirá."
                !seen.add(key) -> problems += "El ítem $key aparece más de una vez."
                else -> valid += row
            }
        }
        val known = existingEmails.map { it.trim().lowercase() }.toSet()
        val newEmails = linkedSetOf<String>()
        var withoutEmail = 0
        valid.forEach { row ->
            val email = cleanEmail(row.clientEmail)
            if (email.isEmpty()) withoutEmail++ else if (email !in known) newEmails += email
        }
        val matched = linkedMapOf<String, String>()
        val unmatched = linkedMapOf<String, String>()
        valid.forEach { row ->
            val user = matchUser(row.responsibleEmail, users)
            val email = row.responsibleEmail?.trim().orEmpty()
            when {
                user != null -> matched.putIfAbsent(user.id.toString(), user.name)
                email.isNotEmpty() -> unmatched.putIfAbsent(email.lowercase(), email)
                !row.responsibleName.isNullOrBlank() -> unmatched.putIfAbsent(row.responsibleName.trim().lowercase(), row.responsibleName.trim())
                else -> Unit
            }
        }
        return ImportSummary(
            events = valid.size,
            newCustomers = newEmails.size + withoutEmail,
            matchedPeople = matched.values.toList(),
            unmatchedPeople = unmatched.values.toList(),
            bySpace = valid.groupingBy { it.space.trim().ifEmpty { NoSpace } }.eachCount(),
            problems = problems,
        )
    }

    // ---- database ----

    private fun PreparedStatement.bind(params: List<Any?>) {
        params.forEachIndexed { index, value ->
            setObject(index + 1, if (value is java.time.Instant) value.atOffset(java.time.ZoneOffset.UTC) else value)
        }
    }

    private fun <T> Connection.rows(sql: String, params: List<Any?>, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            statement.bind(params)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
        }

    private fun Connection.exec(sql: String, params: List<Any?>): Int =
        prepareStatement(sql).use { statement ->
            statement.bind(params)
            statement.executeUpdate()
        }

    private class Applied(val outcome: ImportOutcome, val eventId: UUID, val customerId: UUID, val createdCustomer: Boolean)

    /**
     * Creates or updates the private event of [row] (one transaction, same result when repeated) with its client, and adds the
     * payments not there yet. Sends no notices and writes nothing in the customer history (the timeline reads the event with its own date). A row without key, date, or title and client is skipped.
     */
    fun apply(
        access: ModuleAccessService,
        tenant: Connection,
        row: ImportedEvent,
        users: List<EventosUser>,
        author: String,
        @Suppress("UNUSED_PARAMETER") today: LocalDate,
    ): ImportOutcome {
        val key = row.key.trim()
        if (key.isEmpty()) return ImportOutcome.Skipped("sin identificador")
        if (key.length > MaxKey) return ImportOutcome.Skipped("identificador demasiado largo: $key")
        if (row.startsAt == null) return ImportOutcome.Skipped("sin fecha")
        if (row.title.isBlank() && row.clientName.isBlank()) return ImportOutcome.Skipped("sin título ni cliente")
        val applied = EventosRepository.transaction(tenant) { applyRow(tenant, key, row, users, author) }
        if (applied.createdCustomer) {
            runCatching { access.customerChanged(tenant, applied.customerId) }
                .onFailure { log.warn("No se pudo avisar a Clientes de un cliente importado ({}).", it.javaClass.simpleName) }
        }
        return applied.outcome
    }

    private fun resolveCustomer(tenant: Connection, row: ImportedEvent, title: String): Pair<UUID, Boolean> {
        val email = cleanEmail(row.clientEmail)
        if (email.isNotEmpty()) {
            val existing = tenant.rows(
                "SELECT c.id FROM customers c WHERE lower(c.primary_email) = lower(?) AND ${EventosRepository.VisibleCustomer} ORDER BY c.created_at LIMIT 1",
                listOf(email),
            ) { it.getObject("id", UUID::class.java) }.firstOrNull()
            if (existing != null) return existing to false
        }
        val id = UUID.randomUUID()
        val name = clip(row.clientName.trim().ifEmpty { title }, MaxCustomerName)
        tenant.exec(
            "INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type) VALUES (?, ?, ?, ?, 'active', 'eventos')",
            listOf(id, name, email, clip(row.clientPhone.trim(), MaxPhone)),
        )
        return id to true
    }

    private fun applyRow(tenant: Connection, key: String, row: ImportedEvent, users: List<EventosUser>, author: String): Applied {
        val zone = ZoneId.systemDefault()
        val startsAt = (row.startsAt ?: throw EventosValidationException("El evento no tiene fecha.")).atZone(zone).toInstant()
        val endsAt = row.endsAt?.atZone(zone)?.toInstant()?.takeIf { !it.isBefore(startsAt) }
        val title = clip(row.title.trim().ifEmpty { row.clientName.trim() }, EventosRules.MaxTitle)
        val settings = EventosRepository.settings(tenant)

        val existing = tenant.rows("SELECT id, client_customer_id FROM eventos_events WHERE import_key = ?", listOf(key)) {
            it.getObject("id", UUID::class.java) to it.getObject("client_customer_id", UUID::class.java)
        }.firstOrNull()
        var createdCustomer = false
        val customerId = existing?.second ?: resolveCustomer(tenant, row, title).let { (id, created) ->
            createdCustomer = created
            id
        }

        val responsible = matchUser(row.responsibleEmail, users)
        val unmatched = if (responsible != null) null else row.responsibleEmail?.trim()?.takeIf { it.isNotEmpty() } ?: row.responsibleName?.trim()?.takeIf { it.isNotEmpty() }
        val status = if (row.status == EventStatus.Full) EventStatus.Published else row.status
        val input = EventInput(
            kind = EventKind.Private,
            title = title,
            description = clip(row.description.trim(), EventosRules.MaxText),
            category = clip(row.category.trim(), EventosRules.MaxCategory),
            space = clip(row.space.trim(), EventosRules.MaxSpace),
            startsAt = startsAt,
            endsAt = endsAt,
            responsibleUserId = responsible?.id,
            clientCustomerId = customerId,
            agreedAmount = row.agreedAmount?.let { EventosRules.money(it) }?.takeIf { EventosRules.validMoney(it) },
            guests = row.guests?.takeIf { it in 1..EventosRules.MaxGuests },
            notes = notesWithExtra(row.notes, unmatched?.let { "Responsable en Monday: $it" }),
        )
        val cancelReason = if (status == EventStatus.Cancelled) CancelledInSource else ""

        val eventId: UUID
        val created: Boolean
        if (existing == null) {
            eventId = UUID.randomUUID()
            EventosRepository.insertEvent(tenant, eventId, input, status, settings.currency, author.ifBlank { ImportAuthor }.take(160), importKey = key)
            if (status == EventStatus.Cancelled) EventosRepository.setStatus(tenant, eventId, status, cancelReason)
            created = true
        } else {
            eventId = existing.first
            // Only the fields Monday owns: series, host and notes stay as the team left them; the responsible changes only when matched.
            tenant.exec(
                """
                UPDATE eventos_events
                SET title = ?, description = ?, category = ?, space = ?, starts_at = ?, ends_at = ?, client_customer_id = ?,
                    agreed_amount = ?, guests = ?, responsible_user_id = COALESCE(?::uuid, responsible_user_id), updated_at = now()
                WHERE id = ?
                """.trimIndent(),
                listOf(
                    input.title, input.description, input.category, input.space, startsAt, endsAt, customerId,
                    input.agreedAmount, input.guests, responsible?.id, eventId,
                ),
            )
            // The state changes only from draft (tentative) or towards cancelled.
            val currentStatus = tenant.rows("SELECT status FROM eventos_events WHERE id = ?", listOf(eventId)) { it.getString("status") }.firstOrNull()
            if (currentStatus == EventStatus.Draft.code || status == EventStatus.Cancelled) {
                EventosRepository.setStatus(tenant, eventId, status, cancelReason)
            }
            created = false
        }

        row.payments.forEach { payment ->
            val paymentKey = payment.key.trim()
            val amount = EventosRules.money(payment.amount)
            if (paymentKey.isEmpty() || paymentKey.length > MaxKey || amount.signum() <= 0 || !EventosRules.validMoney(amount)) return@forEach
            tenant.exec(
                """
                INSERT INTO eventos_payments (id, event_id, paid_on, amount, method, note, import_key, recorded_by)
                VALUES (?, ?, ?, ?, '', ?, ?, ?)
                ON CONFLICT (import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(UUID.randomUUID(), eventId, payment.paidOn, amount, clip(payment.note.trim(), EventosRules.MaxAbonoNote), paymentKey, clip(author.ifBlank { ImportAuthor }, 160)),
            )
        }

        return Applied(if (created) ImportOutcome.Created(eventId) else ImportOutcome.Updated(eventId), eventId, customerId, createdCustomer)
    }
}
