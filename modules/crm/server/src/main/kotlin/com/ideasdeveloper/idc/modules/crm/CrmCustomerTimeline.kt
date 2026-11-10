package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.CustomerTimelineEntry
import com.ideasdeveloper.idc.ui.UiOpenModule
import java.sql.Connection
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What the CRM adds to a customer's history in Clientes besides the sales it writes when a lead is
 * won (CrmCustomerEvents): the reservations of the customer's leads, each linked to its lead. Only
 * the leads the user can see in the CRM (interests, assignments) are included.
 */
object CrmCustomerTimeline {
    private class VisibleLead(val id: UUID, val label: String, val createdAt: OffsetDateTime, val closedAt: OffsetDateTime?, val stage: String, val stageKind: String, val source: String)

    val timeline = CustomerTimeline(permission = CrmScreens.View) { query ->
        val scope = CrmRepository.scope(query.tenant, query.permissions)
        val leads = leads(query.tenant, query.customerId).filter { (lead, interestId, ownerId) -> scope.canSee(interestId, ownerId) }.map { it.first }
        if (leads.isEmpty()) return@CustomerTimeline emptyList()
        val byId = leads.associateBy { it.id }
        // Sales are written when the lead is won (CrmCustomerEvents); notes, stage changes and the rest of
        // the lead's activity stay in the lead. The reservations of the leads are listed here.
        buildList {
            reservations(query.tenant, byId.keys).forEach { (leadId, entry) -> add(entry(byId.getValue(leadId).label)) }
        }.sortedByDescending { it.at }.take(query.limit)
    }

    private fun leads(tenant: Connection, customerId: UUID): List<Triple<VisibleLead, UUID?, UUID?>> = tenant.prepareStatement(
        """
        SELECT leads.id, leads.interest_id, leads.owner_user_id, leads.created_at, leads.closed_at, leads.source,
               stages.name AS stage, stages.kind AS stage_kind, interests.name AS interest
        FROM crm_leads leads
        JOIN crm_stages stages ON stages.id = leads.stage_id
        LEFT JOIN crm_interests interests ON interests.id = leads.interest_id
        WHERE leads.customer_id = ?
        ORDER BY leads.created_at DESC
        LIMIT 200
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, customerId)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val interest = rows.getString("interest")
                    val created = rows.getObject("created_at", OffsetDateTime::class.java)
                    val lead = VisibleLead(
                        id = rows.getObject("id", UUID::class.java),
                        label = (interest ?: "sin interes") + " (" + created.toLocalDate() + ")",
                        createdAt = created,
                        closedAt = rows.getObject("closed_at", OffsetDateTime::class.java),
                        stage = rows.getString("stage"),
                        stageKind = rows.getString("stage_kind"),
                        source = rows.getString("source"),
                    )
                    add(Triple(lead, rows.getObject("interest_id", UUID::class.java), rows.getObject("owner_user_id", UUID::class.java)))
                }
            }
        }
    }

    private val reservationStatus = mapOf(
        "not_confirmed" to "sin confirmar", "confirmed" to "confirmada", "canceled" to "cancelada",
        "checked_in" to "en estancia", "checked_out" to "finalizada", "no_show" to "no se presento",
    )

    // Reservations (Cloudbeds) of the leads, built later with the lead's label.
    private fun reservations(tenant: Connection, leadIds: Set<UUID>): List<Pair<UUID, (String) -> CustomerTimelineEntry>> = tenant.prepareStatement(
        """
        SELECT lead_id, reservation_id, status, start_date, end_date, total, rooms, created_at
        FROM crm_reservations WHERE lead_id = ANY (?) ORDER BY start_date DESC LIMIT 200
        """.trimIndent()
    ).use { statement ->
        statement.setArray(1, tenant.createArrayOf("uuid", leadIds.toTypedArray()))
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val leadId = rows.getObject("lead_id", UUID::class.java)
                    val number = rows.getString("reservation_id")
                    val status = rows.getString("status")
                    val start = rows.getObject("start_date", LocalDate::class.java)
                    val end = rows.getObject("end_date", LocalDate::class.java)
                    val detail = listOfNotNull(
                        "Estado: ${reservationStatus[status] ?: status}",
                        rows.getString("rooms").takeIf { it.isNotBlank() }?.let { "Habitaciones: $it" },
                        rows.getBigDecimal("total")?.let { "Total: ${it.stripTrailingZeros().toPlainString()}" },
                    )
                    val at = rows.getObject("created_at", OffsetDateTime::class.java).toInstant()
                    add(leadId to { label: String ->
                        CustomerTimelineEntry(at, "reservation", "Reserva $number: $start al $end", (detail + "Lead: $label").joinToString("\n"))
                            .apply { link = UiOpenModule(CrmModule.id, "lead", mapOf("id" to leadId.toString())) }
                    })
                }
            }
        }
    }
}
