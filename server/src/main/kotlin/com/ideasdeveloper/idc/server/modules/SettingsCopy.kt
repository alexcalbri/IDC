package com.ideasdeveloper.idc.server.modules

import java.sql.Connection
import java.sql.Types

/**
 * A table whose rows are copied as they are to a duplicated company, except the [reset] columns,
 * which get the given value (null, a default text...). The copy replaces the rows the module's
 * migrations seeded in the new company.
 */
data class SettingsTable(val name: String, val reset: Map<String, Any?> = emptyMap())

/** Copies [tables] from [source] to [target] (two tenant databases with the same migrations). */
object SettingsCopy {
    private val identifier = Regex("[a-z][a-z0-9_]{0,62}")

    fun copy(source: Connection, target: Connection, tables: List<SettingsTable>) {
        tables.forEach { table ->
            require(identifier.matches(table.name) && table.reset.keys.all { identifier.matches(it) }) { "Tabla o columna invalida: ${table.name}" }
        }
        val autoCommit = target.autoCommit
        target.autoCommit = false
        try {
            // Children first when emptying, parents first when filling.
            tables.asReversed().forEach { table -> target.createStatement().use { it.executeUpdate("DELETE FROM ${table.name}") } }
            tables.forEach { table -> copyRows(source, target, table) }
            target.commit()
        } catch (failure: Exception) {
            target.rollback()
            throw failure
        } finally {
            target.autoCommit = autoCommit
        }
    }

    private fun copyRows(source: Connection, target: Connection, table: SettingsTable) {
        source.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM ${table.name}").use { rows ->
                val meta = rows.metaData
                val columns = (1..meta.columnCount).map { meta.getColumnName(it) }
                table.reset.keys.firstOrNull { it !in columns }?.let { error("La columna ${table.name}.$it no existe.") }
                val placeholders = columns.map { column ->
                    // json, uuid, enums... travel as text and are cast back to the column type.
                    val type = meta.getColumnTypeName(columns.indexOf(column) + 1)
                    if (type.startsWith("_")) "?" else "CAST(? AS ${quoteType(type)})"
                }
                val sql = "INSERT INTO ${table.name} (${columns.joinToString { "\"$it\"" }}) VALUES (${placeholders.joinToString()})"
                target.prepareStatement(sql).use { insert ->
                    while (rows.next()) {
                        columns.forEachIndexed { index, column ->
                            val position = index + 1
                            val type = meta.getColumnTypeName(position)
                            if (column in table.reset) {
                                val value = table.reset[column]
                                if (value == null) insert.setNull(position, Types.OTHER) else insert.setObject(position, value.toString())
                            } else if (type.startsWith("_")) {
                                // Arrays keep their element type.
                                val array = rows.getArray(position)
                                if (array == null) insert.setNull(position, Types.ARRAY)
                                else insert.setArray(position, target.createArrayOf(type.removePrefix("_"), array.array as Array<*>))
                            } else {
                                val value = rows.getString(position)
                                if (value == null) insert.setNull(position, Types.OTHER) else insert.setString(position, value)
                            }
                        }
                        insert.addBatch()
                    }
                    insert.executeBatch()
                }
            }
        }
    }

    // Type names come from the database catalog; quote them so mixed-case or reserved names work.
    private fun quoteType(type: String): String = if (identifier.matches(type)) type else "\"${type.replace("\"", "\"\"")}\""
}
