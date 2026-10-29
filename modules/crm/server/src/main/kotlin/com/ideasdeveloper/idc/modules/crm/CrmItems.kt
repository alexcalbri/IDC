package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Something a lead is interested in or bought: a property of the website (source "wordpress"), a
 * room of a PMS ("cloudbeds") or an item typed by hand ("manual").
 */
data class CrmItem(
    val id: UUID,
    val leadId: UUID,
    val source: String,
    val externalId: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val price: BigDecimal?,
    val status: String,
    val startsOn: LocalDate?,
    val endsOn: LocalDate?,
    // Source details, e.g. the room type and guests of a PMS room.
    val data: Map<String, String> = emptyMap(),
)

object CrmItems {
    val statusLabels = linkedMapOf(
        "interested" to "Interesado",
        "offered" to "Ofrecida",
        "chosen" to "Elegida",
        "discarded" to "Descartada",
        "sold" to "Vendida",
        "booked" to "Reservada",
    )

    fun list(tenant: Connection, leadId: UUID, source: String? = null): List<CrmItem> = tenant.prepareStatement(
        """
        SELECT id, lead_id, source, external_id, title, subtitle, url, price, status, starts_on, ends_on, data::text
        FROM crm_lead_items WHERE lead_id = ? AND (? IS NULL OR source = ?) ORDER BY created_at
        """.trimIndent()
    ).use { query ->
        query.setObject(1, leadId)
        query.setString(2, source)
        query.setString(3, source)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(
                    CrmItem(
                        rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getString(3), rows.getString(4),
                        rows.getString(5), rows.getString(6), rows.getString(7), rows.getBigDecimal(8), rows.getString(9),
                        rows.getObject(10, LocalDate::class.java), rows.getObject(11, LocalDate::class.java), parseData(rows.getString(12)),
                    )
                )
            }
        }
    }

    fun find(tenant: Connection, leadId: UUID, itemId: UUID): CrmItem? = list(tenant, leadId).firstOrNull { it.id == itemId }

    /**
     * Links an item to a lead, or refreshes its title, subtitle, url and price when it is already
     * linked (the status is kept). Returns true when it was new.
     */
    fun link(
        tenant: Connection,
        leadId: UUID,
        source: String,
        externalId: String,
        title: String,
        subtitle: String = "",
        url: String = "",
        price: BigDecimal? = null,
        status: String = "interested",
        startsOn: LocalDate? = null,
        endsOn: LocalDate? = null,
        data: Map<String, String> = emptyMap(),
    ): Boolean = tenant.prepareStatement(
        """
        INSERT INTO crm_lead_items (id, lead_id, source, external_id, title, subtitle, url, price, status, starts_on, ends_on, data)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
        ON CONFLICT (lead_id, source, external_id) DO UPDATE SET title = EXCLUDED.title, subtitle = EXCLUDED.subtitle,
            url = EXCLUDED.url, price = COALESCE(EXCLUDED.price, crm_lead_items.price),
            starts_on = COALESCE(EXCLUDED.starts_on, crm_lead_items.starts_on), ends_on = COALESCE(EXCLUDED.ends_on, crm_lead_items.ends_on),
            data = crm_lead_items.data || EXCLUDED.data, updated_at = CURRENT_TIMESTAMP
        RETURNING (xmax = 0)
        """.trimIndent()
    ).use { upsert ->
        upsert.setObject(1, UUID.randomUUID())
        upsert.setObject(2, leadId)
        upsert.setString(3, source)
        upsert.setString(4, externalId.take(200))
        upsert.setString(5, title.trim().ifEmpty { externalId }.take(300))
        upsert.setString(6, subtitle.take(500))
        upsert.setString(7, url.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty())
        upsert.setBigDecimal(8, price)
        upsert.setString(9, status)
        upsert.setObject(10, startsOn)
        upsert.setObject(11, endsOn)
        upsert.setString(12, JsonObject(data.mapValues { JsonPrimitive(it.value) }).toString())
        upsert.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
    }

    fun setStatus(tenant: Connection, item: CrmItem, status: String, price: BigDecimal? = item.price) {
        require(status in statusLabels) { "Unknown item status $status" }
        tenant.prepareStatement("UPDATE crm_lead_items SET status = ?, price = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setString(1, status)
            update.setBigDecimal(2, price)
            update.setObject(3, item.id)
            update.executeUpdate()
        }
    }

    fun remove(tenant: Connection, item: CrmItem): Boolean =
        tenant.prepareStatement("DELETE FROM crm_lead_items WHERE id = ?").use { it.setObject(1, item.id); it.executeUpdate() == 1 }
}

private fun parseData(raw: String?): Map<String, String> =
    runCatching { Json.parseToJsonElement(raw.orEmpty()) as JsonObject }.getOrNull()
        ?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()

// Replaces the details of an item (e.g. the guests of a room).
fun CrmItems.setData(tenant: java.sql.Connection, item: CrmItem, data: Map<String, String>) {
    tenant.prepareStatement("UPDATE crm_lead_items SET data = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
        update.setString(1, JsonObject(data.mapValues { JsonPrimitive(it.value) }).toString())
        update.setObject(2, item.id)
        update.executeUpdate()
    }
}
