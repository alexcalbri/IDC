package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.ui.UiChartPoint
import com.ideasdeveloper.idc.ui.UiTableColumn
import java.sql.Connection
import java.sql.Date
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Queries behind the hotspot dashboard, visitor list, report and CSV export.
object HotspotReports {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dayFormat = DateTimeFormatter.ofPattern("dd/MM")

    val columns = listOf(
        UiTableColumn("createdAt", "Fecha"),
        UiTableColumn("name", "Nombre"),
        UiTableColumn("email", "Correo"),
        UiTableColumn("phone", "Celular"),
        UiTableColumn("mac", "Dispositivo"),
        UiTableColumn("connected", "Conectado"),
    )

    data class RangeSummary(val registrations: Long, val visitors: Long, val connections: Long, val failures: Long)

    data class Page(val rows: List<Map<String, String>>, val total: Long)

    fun summary(connection: Connection, from: LocalDate, to: LocalDate) = RangeSummary(
        registrations = count(connection, "SELECT count(*) FROM hotspot_registrations WHERE created_at::date BETWEEN ? AND ?", from, to),
        visitors = count(connection, "SELECT count(DISTINCT lower(email)) FROM hotspot_registrations WHERE created_at::date BETWEEN ? AND ?", from, to),
        connections = count(connection, "SELECT count(*) FROM hotspot_accesses WHERE authorized AND created_at::date BETWEEN ? AND ?", from, to),
        failures = count(connection, "SELECT count(*) FROM hotspot_accesses WHERE NOT authorized AND created_at::date BETWEEN ? AND ?", from, to),
    )

    fun totalEmails(connection: Connection): Long = connection.prepareStatement(
        "SELECT count(DISTINCT lower(email)) FROM hotspot_registrations"
    ).use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0 } }

    // Emails whose first registration happened on or after [since].
    fun newEmailsSince(connection: Connection, since: LocalDate): Long = connection.prepareStatement(
        """
        SELECT count(*) FROM (
            SELECT lower(email) FROM hotspot_registrations GROUP BY lower(email) HAVING min(created_at)::date >= ?
        ) first_seen
        """.trimIndent()
    ).use { query ->
        query.setDate(1, Date.valueOf(since))
        query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0 }
    }

    fun connectionsPerDay(connection: Connection, from: LocalDate, to: LocalDate): List<UiChartPoint> = connection.prepareStatement(
        """
        SELECT day::date AS day, count(accesses.id) AS total
        FROM generate_series(?::date, ?::date, interval '1 day') AS day
        LEFT JOIN hotspot_accesses accesses
          ON accesses.authorized AND accesses.created_at::date = day::date
        GROUP BY day
        ORDER BY day
        """.trimIndent()
    ).use { query ->
        query.setDate(1, Date.valueOf(from))
        query.setDate(2, Date.valueOf(to))
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(UiChartPoint(rows.getDate("day").toLocalDate().format(dayFormat), rows.getLong("total").toDouble()))
                }
            }
        }
    }

    fun latest(connection: Connection, from: LocalDate, to: LocalDate, limit: Int): List<Map<String, String>> =
        registrations(connection, "registrations.created_at::date BETWEEN ? AND ?", listOf(Date.valueOf(from), Date.valueOf(to)), limit, 0)

    // Visitor list with a case-insensitive search over name, email and phone.
    fun visitors(connection: Connection, search: String, limit: Int, offset: Int): Page {
        val pattern = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val filter = "(registrations.name ILIKE ? OR registrations.email ILIKE ? OR registrations.phone ILIKE ?)"
        val arguments = listOf(pattern, pattern, pattern)
        val total = connection.prepareStatement("SELECT count(*) FROM hotspot_registrations registrations WHERE $filter").use { query ->
            arguments.forEachIndexed { index, value -> query.setObject(index + 1, value) }
            query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0 }
        }
        return Page(registrations(connection, filter, arguments, limit, offset), total)
    }

    fun csv(connection: Connection, from: LocalDate, to: LocalDate): String = buildString {
        appendLine((columns.map { it.label } + "Cumpleanos").joinToString(";") { csvCell(it) })
        latest(connection, from, to, limit = 50_000).forEach { row ->
            appendLine((columns.map { row[it.key].orEmpty() } + row["birthday"].orEmpty()).joinToString(";") { csvCell(it) })
        }
    }

    private fun registrations(connection: Connection, filter: String, arguments: List<Any>, limit: Int, offset: Int): List<Map<String, String>> =
        connection.prepareStatement(
            """
            SELECT registrations.created_at, registrations.name, registrations.email, registrations.phone,
                   registrations.birthday, registrations.mac,
                   EXISTS (
                       SELECT 1 FROM hotspot_accesses accesses
                       WHERE accesses.registration_id = registrations.id AND accesses.authorized
                   ) AS connected
            FROM hotspot_registrations registrations
            WHERE $filter
            ORDER BY registrations.created_at DESC
            LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { query ->
            arguments.forEachIndexed { index, value -> query.setObject(index + 1, value) }
            query.setInt(arguments.size + 1, limit)
            query.setInt(arguments.size + 2, offset)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            mapOf(
                                "createdAt" to rows.getTimestamp("created_at").toLocalDateTime().format(timestampFormat),
                                "name" to rows.getString("name"),
                                "email" to rows.getString("email"),
                                "phone" to rows.getString("phone"),
                                "birthday" to (rows.getDate("birthday")?.toLocalDate()?.toString() ?: ""),
                                "mac" to rows.getString("mac"),
                                "connected" to if (rows.getBoolean("connected")) "Si" else "No",
                            )
                        )
                    }
                }
            }
        }

    private fun count(connection: Connection, sql: String, from: LocalDate, to: LocalDate): Long =
        connection.prepareStatement(sql).use { query ->
            query.setDate(1, Date.valueOf(from))
            query.setDate(2, Date.valueOf(to))
            query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0 }
        }

    // Neutralizes spreadsheet formulas (=, +, -, @) and quotes every cell.
    private fun csvCell(value: String): String {
        val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
