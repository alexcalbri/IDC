package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.CustomerTimelineEntry
import com.ideasdeveloper.idc.ui.UiOpenModule
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** A registration of the customer (never one on the waiting list) with the data of its event. */
data class TimelineRegistration(
    val eventId: UUID,
    val title: String,
    val startsAt: Instant,
    val createdAt: Instant,
    val cancelled: Boolean,
    val seats: Int,
    val amount: BigDecimal,
    val currency: String,
    val paid: Boolean,
    val paidAt: Instant?,
    val attendedAt: Instant?,
    val cancelledAt: Instant?,
)

/** A private event hired by the customer, with what has been paid so far. */
data class TimelinePrivateEvent(
    val eventId: UUID,
    val title: String,
    val startsAt: Instant,
    val createdAt: Instant,
    val space: String,
    val agreedAmount: BigDecimal?,
    val currency: String,
    val cancelled: Boolean,
    val cancelledAt: Instant?,
    val paidTotal: BigDecimal,
)

/** A payment (abono) of a private event of the customer. Its note and method are never read. */
data class TimelinePayment(
    val eventId: UUID,
    val title: String,
    val paidOn: LocalDate,
    val amount: BigDecimal,
    val currency: String,
)

/**
 * What Eventos adds to a customer's history in Clientes: registrations, payments and attendance at public events, and the
 * private events the customer hired with their payments. Same permission as the module screens (`eventos.view` shows every
 * event); private events only appear once confirmed (not in draft). Reads the module's own tables, so an imported event
 * shows with its real date and nothing is written in the customer history.
 */
object EventosCustomerTimeline {
    private const val MaxTitle = 200

    val timeline = CustomerTimeline(permission = EventosModule.View) { query ->
        val limit = query.limit
        if (limit <= 0) {
            emptyList()
        } else {
            entries(
                registrations = registrations(query.tenant, query.customerId, limit),
                privateEvents = privateEvents(query.tenant, query.customerId, limit),
                payments = payments(query.tenant, query.customerId, limit),
                limit = limit,
            )
        }
    }

    /** The entries of the three sources, newest first and at most [limit]. */
    fun entries(
        registrations: List<TimelineRegistration>,
        privateEvents: List<TimelinePrivateEvent>,
        payments: List<TimelinePayment>,
        limit: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CustomerTimelineEntry> {
        val all = buildList {
            registrations.forEach { addAll(registrationEntries(it, zone)) }
            privateEvents.forEach { addAll(privateEventEntries(it, zone)) }
            payments.forEach { add(paymentEntry(it, zone)) }
        }
        return all.sortedByDescending { it.at }.take(limit.coerceAtLeast(0))
    }

    private fun entry(at: Instant, kind: String, title: String, detail: String, eventId: UUID): CustomerTimelineEntry {
        val result = CustomerTimelineEntry(at = at, kind = kind, title = title.take(MaxTitle), detail = detail)
        result.link = UiOpenModule(EventosModule.id, "evento", mapOf("id" to eventId.toString()))
        return result
    }

    private fun money(amount: BigDecimal, currency: String) = "${amount.toPlainString()} $currency"

    private fun registrationEntries(row: TimelineRegistration, zone: ZoneId): List<CustomerTimelineEntry> = buildList {
        // An imported or back-dated event happened before the record was created, so the earlier date is the real one.
        add(
            entry(
                at = minOf(row.createdAt, row.startsAt),
                kind = "evento_inscripcion",
                title = "Inscripción: ${row.title}",
                detail = listOf(
                    EventosRules.stamp(row.startsAt, zone),
                    EventosRules.seatsLabel(row.seats),
                    money(row.amount, row.currency),
                    if (row.paid) "Pagado" else "Pago pendiente",
                ).joinToString(" · "),
                eventId = row.eventId,
            )
        )
        row.paidAt?.let {
            add(entry(it, "evento_pago", "Pago de inscripción: ${row.title}", money(row.amount, row.currency), row.eventId))
        }
        row.attendedAt?.let {
            add(entry(it, "evento_asistencia", "Asistió: ${row.title}", EventosRules.stamp(row.startsAt, zone), row.eventId))
        }
        if (row.cancelled) {
            row.cancelledAt?.let {
                add(entry(it, "evento_inscripcion_cancelada", "Inscripción cancelada: ${row.title}", EventosRules.stamp(row.startsAt, zone), row.eventId))
            }
        }
    }

    private fun privateEventEntries(row: TimelinePrivateEvent, zone: ZoneId): List<CustomerTimelineEntry> = buildList {
        add(
            entry(
                at = minOf(row.createdAt, row.startsAt),
                kind = "evento_privado",
                title = "Evento privado: ${row.title}",
                detail = listOfNotNull(
                    EventosRules.stamp(row.startsAt, zone),
                    row.space.trim().takeIf { it.isNotEmpty() },
                    row.agreedAmount?.let { "Acordado: ${money(it, row.currency)}" },
                    "Pagado: ${money(row.paidTotal, row.currency)}",
                ).joinToString(" · "),
                eventId = row.eventId,
            )
        )
        // The reason of the cancellation can be internal, so it is not shown.
        if (row.cancelled) {
            row.cancelledAt?.let {
                add(entry(it, "evento_cancelado", "Evento cancelado: ${row.title}", EventosRules.stamp(row.startsAt, zone), row.eventId))
            }
        }
    }

    private fun paymentEntry(row: TimelinePayment, zone: ZoneId) =
        entry(row.paidOn.atStartOfDay(zone).toInstant(), "evento_pago", "Pago del evento privado: ${row.title}", money(row.amount, row.currency), row.eventId)

    private fun instant(rows: ResultSet, column: String): Instant? = rows.getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun registrations(tenant: Connection, customerId: UUID, limit: Int): List<TimelineRegistration> =
        tenant.prepareStatement(
            """
            SELECT r.event_id, e.title, e.starts_at, e.currency,
                   r.created_at, r.status, r.seats, r.amount, r.paid, r.paid_at, r.attended_at, r.cancelled_at
            FROM eventos_registrations r
            JOIN eventos_events e ON e.id = r.event_id
            WHERE r.customer_id = ? AND r.status IN ('inscrito', 'cancelado')
            ORDER BY GREATEST(LEAST(r.created_at, e.starts_at), r.paid_at, r.attended_at, CASE WHEN r.status = 'cancelado' THEN r.cancelled_at END) DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, customerId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            TimelineRegistration(
                                eventId = rows.getObject("event_id", UUID::class.java),
                                title = rows.getString("title"),
                                startsAt = instant(rows, "starts_at")!!,
                                createdAt = instant(rows, "created_at")!!,
                                cancelled = rows.getString("status") == "cancelado",
                                seats = rows.getInt("seats"),
                                amount = rows.getBigDecimal("amount"),
                                currency = rows.getString("currency"),
                                paid = rows.getBoolean("paid"),
                                paidAt = instant(rows, "paid_at"),
                                attendedAt = instant(rows, "attended_at"),
                                cancelledAt = instant(rows, "cancelled_at"),
                            )
                        )
                    }
                }
            }
        }

    private fun privateEvents(tenant: Connection, customerId: UUID, limit: Int): List<TimelinePrivateEvent> =
        tenant.prepareStatement(
            """
            SELECT e.id, e.title, e.starts_at, e.created_at, e.space, e.agreed_amount, e.currency, e.status, e.cancelled_at,
                   COALESCE((SELECT sum(p.amount) FROM eventos_payments p WHERE p.event_id = e.id), 0) AS paid_total
            FROM eventos_events e
            WHERE e.kind = 'privado' AND e.client_customer_id = ? AND e.status <> 'borrador'
            ORDER BY GREATEST(LEAST(e.created_at, e.starts_at), e.cancelled_at) DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, customerId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            TimelinePrivateEvent(
                                eventId = rows.getObject("id", UUID::class.java),
                                title = rows.getString("title"),
                                startsAt = instant(rows, "starts_at")!!,
                                createdAt = instant(rows, "created_at")!!,
                                space = rows.getString("space").orEmpty(),
                                agreedAmount = rows.getBigDecimal("agreed_amount"),
                                currency = rows.getString("currency"),
                                cancelled = rows.getString("status") == "cancelado",
                                cancelledAt = instant(rows, "cancelled_at"),
                                paidTotal = rows.getBigDecimal("paid_total"),
                            )
                        )
                    }
                }
            }
        }

    // Only the amount and the date are read: the note and the method of a payment may be internal.
    private fun payments(tenant: Connection, customerId: UUID, limit: Int): List<TimelinePayment> =
        tenant.prepareStatement(
            """
            SELECT p.event_id, e.title, e.currency, p.paid_on, p.amount
            FROM eventos_payments p
            JOIN eventos_events e ON e.id = p.event_id
            WHERE e.kind = 'privado' AND e.client_customer_id = ? AND e.status <> 'borrador'
            ORDER BY p.paid_on DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, customerId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            TimelinePayment(
                                eventId = rows.getObject("event_id", UUID::class.java),
                                title = rows.getString("title"),
                                paidOn = rows.getObject("paid_on", LocalDate::class.java),
                                amount = rows.getBigDecimal("amount"),
                                currency = rows.getString("currency"),
                            )
                        )
                    }
                }
            }
        }
}
