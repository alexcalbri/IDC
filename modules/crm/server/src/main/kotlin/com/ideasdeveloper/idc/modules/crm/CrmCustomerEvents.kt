package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.CustomerHistoryEvent
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.ui.UiOpenModule
import java.sql.Connection

/**
 * What the CRM writes in the customer's history of the company (Clientes, one tab per company):
 * the moment a lead is won, "Venta convertida en {empresa}", with a link to the lead, where the
 * customer's requirements, documents, notes and every change stay. Notes and stage changes are not
 * copied to the customer: they belong to the lead.
 */
object CrmCustomerEvents {
    // Set when the module's routes are registered, before any job runs.
    @Volatile
    var access: ModuleAccessService? = null

    /**
     * The CRM changed the customer's name, email, phone or custom fields: Clientes copies it now to
     * the companies the customer is shared with (one record for all of them).
     */
    fun changed(tenant: Connection, customerId: java.util.UUID) {
        access?.customerChanged(tenant, customerId)
    }

    /** Records that [lead] was won. Written once per lead; does nothing without Clientes. */
    fun won(tenant: Connection, lead: CrmLead, actor: CrmActor) {
        val access = access ?: return
        val company = access.companyOf(tenant)?.name ?: "la empresa"
        val interest = lead.interestId?.let { id ->
            tenant.prepareStatement("SELECT name FROM crm_interests WHERE id = ?").use { query ->
                query.setObject(1, id)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }
        val items = tenant.prepareStatement(
            "SELECT title, status, price FROM crm_lead_items WHERE lead_id = ? AND status IN ('sold', 'booked') ORDER BY updated_at"
        ).use { query ->
            query.setObject(1, lead.id)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val price = rows.getBigDecimal("price")?.let { " (${it.stripTrailingZeros().toPlainString()})" }.orEmpty()
                        add("${if (rows.getString("status") == "sold") "Vendido" else "Reservado"}: ${rows.getString("title")}$price")
                    }
                }
            }
        }
        val detail = listOfNotNull(
            interest?.let { "Interes: $it" },
            lead.estimatedValue?.let { "Valor estimado: ${it.stripTrailingZeros().toPlainString()}" },
            lead.targetStart?.let { start -> "Fechas: $start" + (lead.targetEnd?.let { " al $it" } ?: "") },
        ) + items
        access.recordCustomerEvent(
            tenant,
            CustomerHistoryEvent(
                customerId = lead.customerId,
                moduleId = CrmModule.id,
                kind = "sale",
                title = "Venta convertida en $company",
                detail = detail.joinToString("\n"),
                author = actor.name,
                link = UiOpenModule(CrmModule.id, "lead", mapOf("id" to lead.id.toString())),
                sourceRef = "lead:${lead.id}:won",
            ),
        )
    }
}
