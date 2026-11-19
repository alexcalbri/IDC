package com.ideasdeveloper.idc.modules.propietarios

import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.CustomerTimelineEntry
import com.ideasdeveloper.idc.ui.UiOpenModule
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Una titularidad del cliente con los datos de su lote. [handedOver] y [received] dicen si en el mismo lote, ese mismo dia, otra
 * persona recibio (o entrego) la titularidad: asi se distingue un traspaso de una alta o baja sin saber quien es esa persona.
 */
data class TimelineOwnership(
    val lotId: UUID,
    val lotNumber: String,
    val developmentName: String,
    val startsOn: LocalDate?,
    val endsOn: LocalDate?,
    val sharePercent: BigDecimal?,
    val createdAt: Instant,
    val handedOver: Boolean,
    val received: Boolean,
)

/**
 * Lo que Propietarios agrega al historial del cliente en Clientes: cuando pasa a ser titular de un lote, cuando deja de serlo y los
 * traspasos. Mismo permiso que las pantallas (`propietarios.view`) y la misma regla: los lotes y desarrollos archivados no se ven.
 * Lee las tablas del modulo, asi lo importado sale con su fecha real y no se escribe nada en el historial del cliente. Nunca incluye
 * al otro titular de un traspaso, ni notas, ni adjuntos.
 */
object PropietariosCustomerTimeline {
    private const val MaxTitle = 200

    val timeline = CustomerTimeline(permission = PropietariosModule.View) { query ->
        if (query.limit <= 0) {
            emptyList()
        } else {
            val today = PropietariosRepository.today()
            entries(ownerships(query.tenant, query.customerId, today, query.limit), today, query.limit)
        }
    }

    /** Las entradas de las titularidades, de la mas nueva a la mas vieja y como mucho [limit]. */
    fun entries(
        rows: List<TimelineOwnership>,
        today: LocalDate,
        limit: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CustomerTimelineEntry> {
        val all = buildList {
            // Una titularidad que empieza en el futuro todavia no ha pasado: no sale.
            rows.filter { it.startsOn == null || !it.startsOn.isAfter(today) }.forEach { row ->
                val start = row.startsOn
                add(
                    entry(
                        at = start?.atStartOfDay(zone)?.toInstant() ?: row.createdAt,
                        kind = if (row.received) "propietario_traspaso_entrada" else "propietario_alta",
                        title = if (row.received) "Recibió el lote ${row.lotNumber} por traspaso" else "Propietario del lote ${row.lotNumber}",
                        detail = listOfNotNull(
                            if (start != null) "${row.developmentName} · desde $start" else row.developmentName,
                            row.sharePercent?.let { "Participación: ${it.stripTrailingZeros().toPlainString()}%" },
                        ).joinToString("\n"),
                        lotId = row.lotId,
                    )
                )
                val end = row.endsOn
                if (end != null && !end.isAfter(today)) {
                    add(
                        entry(
                            at = end.atStartOfDay(zone).toInstant(),
                            kind = if (row.handedOver) "propietario_traspaso_salida" else "propietario_baja",
                            title = if (row.handedOver) "Traspasó el lote ${row.lotNumber}" else "Dejó de ser propietario del lote ${row.lotNumber}",
                            detail = "${row.developmentName} · hasta $end",
                            lotId = row.lotId,
                        )
                    )
                }
            }
        }
        return all.sortedByDescending { it.at }.take(limit.coerceAtLeast(0))
    }

    private fun entry(at: Instant, kind: String, title: String, detail: String, lotId: UUID): CustomerTimelineEntry {
        val result = CustomerTimelineEntry(at = at, kind = kind, title = title.take(MaxTitle), detail = detail)
        result.link = UiOpenModule(PropietariosModule.id, "lote", mapOf("id" to lotId.toString()))
        return result
    }

    // Solo se leen fechas, numeros y nombres del lote y el desarrollo; de otros titulares unicamente si existen (EXISTS), nunca quienes son.
    private fun ownerships(tenant: Connection, customerId: UUID, today: LocalDate, limit: Int): List<TimelineOwnership> =
        tenant.prepareStatement(
            """
            SELECT o.lot_id, l.lot_number, d.name AS development_name, o.starts_on, o.ends_on, o.share_percent, o.created_at,
                   (o.ends_on IS NOT NULL AND EXISTS (
                       SELECT 1 FROM propietarios_ownerships other
                       WHERE other.lot_id = o.lot_id AND other.id <> o.id AND other.customer_id <> o.customer_id AND other.starts_on = o.ends_on
                   )) AS handed_over,
                   (o.starts_on IS NOT NULL AND EXISTS (
                       SELECT 1 FROM propietarios_ownerships other
                       WHERE other.lot_id = o.lot_id AND other.id <> o.id AND other.customer_id <> o.customer_id AND other.ends_on = o.starts_on
                   )) AS received
            FROM propietarios_ownerships o
            JOIN propietarios_lots l ON l.id = o.lot_id
            JOIN propietarios_developments d ON d.id = l.development_id
            WHERE o.customer_id = ? AND (o.starts_on IS NULL OR o.starts_on <= ?) AND l.archived_at IS NULL AND d.archived_at IS NULL
            ORDER BY GREATEST(COALESCE(o.starts_on, o.created_at::date), CASE WHEN o.ends_on <= ? THEN o.ends_on END) DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, customerId)
            statement.setObject(2, today)
            statement.setObject(3, today)
            statement.setInt(4, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            TimelineOwnership(
                                lotId = rows.getObject("lot_id", UUID::class.java),
                                lotNumber = rows.getString("lot_number"),
                                developmentName = rows.getString("development_name"),
                                startsOn = rows.getObject("starts_on", LocalDate::class.java),
                                endsOn = rows.getObject("ends_on", LocalDate::class.java),
                                sharePercent = rows.getBigDecimal("share_percent"),
                                createdAt = rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                                handedOver = rows.getBoolean("handed_over"),
                                received = rows.getBoolean("received"),
                            )
                        )
                    }
                }
            }
        }
}
