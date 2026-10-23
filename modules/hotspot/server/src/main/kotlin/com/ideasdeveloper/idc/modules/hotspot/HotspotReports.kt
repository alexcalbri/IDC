package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.ModuleReport
import com.ideasdeveloper.idc.server.modules.ModuleReportChart
import com.ideasdeveloper.idc.server.modules.ModuleReportColumn
import com.ideasdeveloper.idc.server.modules.ModuleReportMetric
import com.ideasdeveloper.idc.server.modules.ModuleReportPoint
import com.ideasdeveloper.idc.server.modules.ModuleReportTable
import java.sql.Connection
import java.sql.Date
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object HotspotReports {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dayFormat = DateTimeFormatter.ofPattern("dd/MM")

    private val columns = listOf(
        ModuleReportColumn("createdAt", "Fecha"),
        ModuleReportColumn("name", "Nombre"),
        ModuleReportColumn("email", "Correo"),
        ModuleReportColumn("phone", "Celular"),
        ModuleReportColumn("mac", "Dispositivo"),
        ModuleReportColumn("connected", "Conectado"),
    )

    fun report(connection: Connection, from: LocalDate, to: LocalDate): ModuleReport {
        val registrations = count(connection, "SELECT count(*) FROM hotspot_registrations WHERE created_at::date BETWEEN ? AND ?", from, to)
        val visitors = count(connection, "SELECT count(DISTINCT lower(email)) FROM hotspot_registrations WHERE created_at::date BETWEEN ? AND ?", from, to)
        val connections = count(connection, "SELECT count(*) FROM hotspot_accesses WHERE authorized AND created_at::date BETWEEN ? AND ?", from, to)
        val failures = count(connection, "SELECT count(*) FROM hotspot_accesses WHERE NOT authorized AND created_at::date BETWEEN ? AND ?", from, to)

        val perDay = connection.prepareStatement(
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
                        add(ModuleReportPoint(rows.getDate("day").toLocalDate().format(dayFormat), rows.getLong("total").toDouble()))
                    }
                }
            }
        }

        return ModuleReport(
            metrics = listOf(
                ModuleReportMetric("Registros", registrations.toString()),
                ModuleReportMetric("Visitantes unicos", visitors.toString(), "Correos distintos"),
                ModuleReportMetric("Conexiones", connections.toString(), "Accesos autorizados en UniFi"),
                ModuleReportMetric("Fallas de UniFi", failures.toString()),
            ),
            charts = listOf(ModuleReportChart("Conexiones por dia", perDay)),
            tables = listOf(ModuleReportTable("Ultimos registros", columns, rows(connection, from, to, limit = 50))),
        )
    }

    fun csv(connection: Connection, from: LocalDate, to: LocalDate): String = buildString {
        appendLine((columns.map { it.label } + "Cumpleanos").joinToString(";") { csvCell(it) })
        rows(connection, from, to, limit = 50_000).forEach { row ->
            appendLine((columns.map { row[it.key].orEmpty() } + row["birthday"].orEmpty()).joinToString(";") { csvCell(it) })
        }
    }

    private fun rows(connection: Connection, from: LocalDate, to: LocalDate, limit: Int): List<Map<String, String>> =
        connection.prepareStatement(
            """
            SELECT registrations.created_at, registrations.name, registrations.email, registrations.phone,
                   registrations.birthday, registrations.mac,
                   EXISTS (
                       SELECT 1 FROM hotspot_accesses accesses
                       WHERE accesses.registration_id = registrations.id AND accesses.authorized
                   ) AS connected
            FROM hotspot_registrations registrations
            WHERE registrations.created_at::date BETWEEN ? AND ?
            ORDER BY registrations.created_at DESC
            LIMIT ?
            """.trimIndent()
        ).use { query ->
            query.setDate(1, Date.valueOf(from))
            query.setDate(2, Date.valueOf(to))
            query.setInt(3, limit)
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
