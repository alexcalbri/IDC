package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.CustomerTimelineEntry
import com.ideasdeveloper.idc.ui.UiOpenModule
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/** Datos de una membresia del cliente que el historial necesita; sin notas ni motivo de cancelacion. */
data class MembershipTimelineRow(
    val planName: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    /** Estado guardado (StoredStatus). */
    val status: String,
    val guestPassesTotal: Int,
    val guestPassesUsed: Int,
    val renewal: Boolean,
    val createdAt: Instant,
    val cancelledAt: Instant?,
)

/** Visitas de un mes calendario (UTC) de un cliente, contadas; nunca nombres de invitados ni notas. */
data class VisitMonthRow(val month: YearMonth, val memberVisits: Int, val guestVisits: Int, val lastVisitAt: Instant)

/**
 * Lo que la membresia aporta al historial del cliente en Clientes: altas, renovaciones, cancelaciones y
 * vencimientos, y las visitas agregadas por mes. Se calcula al leer (no se escribe en customer_history), asi
 * solo lo ve quien tiene `membresia.view` con el modulo activo, y lo importado sale con su fecha real.
 * La visibilidad del cliente es la de las pantallas (compartido o de esta empresa, no archivado).
 */
object MembresiaCustomerTimeline {
    private const val VisibleCustomer = "(c.origin_company_code = '' OR c.shared) AND c.status <> 'archived'"

    private val monthNames = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )

    val timeline = CustomerTimeline(permission = MembresiaModule.View) { query ->
        entries(
            memberships = membershipRows(query.tenant, query.customerId, query.limit),
            visitMonths = visitMonthRows(query.tenant, query.customerId, query.limit),
            customerId = query.customerId,
            limit = query.limit,
        )
    }

    /** Convierte las filas en entradas: las mas nuevas primero, como mucho [limit]. Pura: no toca la base de datos. */
    fun entries(
        memberships: List<MembershipTimelineRow>,
        visitMonths: List<VisitMonthRow>,
        customerId: UUID,
        limit: Int,
    ): List<CustomerTimelineEntry> {
        val link = UiOpenModule(MembresiaModule.Id, "miembro", mapOf("customer" to customerId.toString()))
        val all = buildList {
            for (row in memberships) {
                val start = row.startsOn.atStartOfDay(ZoneOffset.UTC).toInstant()
                add(
                    CustomerTimelineEntry(
                        at = minOf(row.createdAt, start),
                        kind = if (row.renewal) "membresia_renovada" else "membresia_alta",
                        title = (if (row.renewal) "Membresía ${row.planName} renovada" else "Membresía ${row.planName}").take(300),
                        detail = createdDetail(row),
                    ).also { it.link = link }
                )
                row.cancelledAt?.let { cancelledAt ->
                    add(
                        CustomerTimelineEntry(
                            at = cancelledAt,
                            kind = "membresia_cancelada",
                            title = "Membresía ${row.planName} cancelada".take(300),
                        ).also { it.link = link }
                    )
                }
                if (row.status == StoredStatus.Vencida) {
                    add(
                        CustomerTimelineEntry(
                            at = row.endsOn.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                            kind = "membresia_vencida",
                            title = "Membresía ${row.planName} vencida".take(300),
                            detail = createdDetail(row),
                        ).also { it.link = link }
                    )
                }
            }
            for (month in visitMonths) {
                add(
                    CustomerTimelineEntry(
                        at = month.lastVisitAt,
                        kind = "membresia_visitas",
                        title = "Visitas de ${monthLabel(month.month)}: ${month.memberVisits + month.guestVisits}",
                        detail = if (month.guestVisits > 0) "Con invitado: ${month.guestVisits}" else "",
                    ).also { it.link = link }
                )
            }
        }
        return all.sortedByDescending { it.at }.take(limit.coerceAtLeast(0))
    }

    /** "{inicio} a {fin} · {monto} {moneda}" y, si el plan trae pases de invitado, "Pases de invitado: usados/total". */
    fun createdDetail(row: MembershipTimelineRow): String = listOfNotNull(
        "${row.startsOn} a ${row.endsOn} · ${row.amount.toPlainString()} ${row.currency}",
        "Pases de invitado: ${row.guestPassesUsed}/${row.guestPassesTotal}".takeIf { row.guestPassesTotal > 0 },
    ).joinToString("\n")

    /** "noviembre 2026". */
    fun monthLabel(month: YearMonth): String = "${monthNames[month.monthValue - 1]} ${month.year}"

    private fun membershipRows(tenant: Connection, customerId: UUID, limit: Int): List<MembershipTimelineRow> =
        tenant.prepareStatement(
            """
            SELECT m.plan_name, m.starts_on, m.ends_on, m.amount, m.currency, m.status, m.guest_passes_total, m.guest_passes_used,
                   m.renewed_from IS NOT NULL AS renewal, m.created_at, m.cancelled_at
            FROM membresia_memberships m
            JOIN customers c ON c.id = m.customer_id
            WHERE m.customer_id = ? AND $VisibleCustomer
            ORDER BY GREATEST(
                LEAST(m.created_at, m.starts_on::timestamp AT TIME ZONE 'UTC'),
                m.cancelled_at,
                CASE WHEN m.status = 'vencida' THEN (m.ends_on + 1)::timestamp AT TIME ZONE 'UTC' END
            ) DESC, m.id
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, customerId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            MembershipTimelineRow(
                                planName = rows.getString("plan_name"),
                                startsOn = rows.getObject("starts_on", LocalDate::class.java),
                                endsOn = rows.getObject("ends_on", LocalDate::class.java),
                                amount = rows.getBigDecimal("amount"),
                                currency = rows.getString("currency"),
                                status = rows.getString("status"),
                                guestPassesTotal = rows.getInt("guest_passes_total"),
                                guestPassesUsed = rows.getInt("guest_passes_used"),
                                renewal = rows.getBoolean("renewal"),
                                createdAt = rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                                cancelledAt = rows.getObject("cancelled_at", OffsetDateTime::class.java)?.toInstant(),
                            )
                        )
                    }
                }
            }
        }

    private fun visitMonthRows(tenant: Connection, customerId: UUID, limit: Int): List<VisitMonthRow> =
        tenant.prepareStatement(
            """
            SELECT to_char(v.visited_at AT TIME ZONE 'UTC', 'YYYY-MM') AS month,
                   count(*) FILTER (WHERE v.kind = 'miembro') AS member_visits,
                   count(*) FILTER (WHERE v.kind = 'invitado') AS guest_visits,
                   max(v.visited_at) AS last_visit_at
            FROM membresia_visits v
            JOIN customers c ON c.id = v.customer_id
            WHERE v.customer_id = ? AND $VisibleCustomer
            GROUP BY 1
            ORDER BY 1 DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, customerId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            VisitMonthRow(
                                month = YearMonth.parse(rows.getString("month")),
                                memberVisits = rows.getInt("member_visits"),
                                guestVisits = rows.getInt("guest_visits"),
                                lastVisitAt = rows.getObject("last_visit_at", OffsetDateTime::class.java).toInstant(),
                            )
                        )
                    }
                }
            }
        }
}
