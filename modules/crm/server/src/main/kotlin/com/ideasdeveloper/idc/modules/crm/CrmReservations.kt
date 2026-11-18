package com.ideasdeveloper.idc.modules.crm

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.LocalDate
import java.util.UUID

/** Persistencia de las reservas (tabla crm_reservations, que se queda en el CRM). */
object CrmReservations {
    val statusLabels = mapOf(
        "not_confirmed" to "Sin confirmar", "confirmed" to "Confirmada", "canceled" to "Cancelada",
        "checked_in" to "En el hotel", "checked_out" to "Salio", "no_show" to "No llego",
    )

    /** Reparte [total] en [parts] montos de 2 decimales que suman exactamente [total] (los centavos sobrantes van primero). */
    fun splitEvenly(total: BigDecimal, parts: Int): List<BigDecimal> {
        if (parts <= 0) return emptyList()
        val cents = total.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toBigInteger()
        val partsBig = BigInteger.valueOf(parts.toLong())
        val base = cents / partsBig
        val extra = (cents % partsBig).toInt()
        return (0 until parts).map { i ->
            BigDecimal((if (i < extra) base + BigInteger.ONE else base)).movePointLeft(2)
        }
    }

    fun save(tenant: Connection, leadId: UUID, result: CbReservationResult, start: LocalDate, end: LocalDate, rooms: String, email: String) {
        tenant.prepareStatement(
            """
            INSERT INTO crm_reservations (id, lead_id, reservation_id, status, start_date, end_date, total, rooms, guest_email)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, leadId)
            insert.setString(3, result.reservationId)
            insert.setString(4, result.status.takeIf { it in statusLabels } ?: "confirmed")
            insert.setObject(5, start)
            insert.setObject(6, end)
            insert.setBigDecimal(7, result.total)
            insert.setString(8, rooms.take(1000))
            insert.setString(9, email)
            insert.executeUpdate()
        }
    }

    /** Reservas que cumplen [condition] (fragmento SQL del WHERE; [bind] enlaza sus parametros), por fecha de entrada. */
    fun query(tenant: Connection, condition: String, bind: (PreparedStatement) -> Unit = {}): List<CrmReservation> = tenant.prepareStatement(
        "SELECT id, lead_id, reservation_id, status, start_date, end_date, total, rooms, guest_email, welcome_sent_at FROM crm_reservations WHERE $condition ORDER BY start_date"
    ).use { query ->
        bind(query)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(
                    CrmReservation(
                        rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getString(3), rows.getString(4),
                        rows.getObject(5, LocalDate::class.java), rows.getObject(6, LocalDate::class.java), rows.getBigDecimal(7),
                        rows.getString(8), rows.getString(9), rows.getTimestamp(10) != null,
                    )
                )
            }
        }
    }

    fun forLead(tenant: Connection, leadId: UUID): List<CrmReservation> = query(tenant, "lead_id = ?") { it.setObject(1, leadId) }

    fun markCheckedIn(tenant: Connection, reservationId: UUID) {
        tenant.prepareStatement("UPDATE crm_reservations SET status = 'checked_in', updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setObject(1, reservationId)
            update.executeUpdate()
        }
    }

    fun markWelcomeSent(tenant: Connection, reservationId: UUID) {
        tenant.prepareStatement("UPDATE crm_reservations SET welcome_sent_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, reservationId); it.executeUpdate() }
    }

    fun updateStatus(tenant: Connection, reservationId: UUID, status: String) {
        tenant.prepareStatement(
            "UPDATE crm_reservations SET status = ?, checked_out_at = CASE WHEN ? = 'checked_out' THEN CURRENT_TIMESTAMP END, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
        ).use { update ->
            update.setString(1, status)
            update.setString(2, status)
            update.setObject(3, reservationId)
            update.executeUpdate()
        }
    }
}
