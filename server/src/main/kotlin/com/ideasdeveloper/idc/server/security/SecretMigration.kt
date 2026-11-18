package com.ideasdeveloper.idc.server.security

import java.sql.Connection

/** Utilidades para cifrar en sitio secretos heredados en texto plano. */
object SecretMigration {
    private val identifier = Regex("[a-z_][a-z0-9_]*")

    /**
     * Cifra los valores no vacios de [secretColumns] que aun no empiezan por "enc:v1:". Idempotente y en una
     * sola transaccion. Ignora la tabla si no existe. Devuelve cuantos valores cifro.
     */
    fun sealColumns(tenant: Connection, box: SecretBox, table: String, idColumns: List<String>, secretColumns: List<String>): Int {
        require(idColumns.isNotEmpty() && secretColumns.isNotEmpty()) { "Faltan columnas" }
        (listOf(table) + idColumns + secretColumns).forEach { require(identifier.matches(it)) { "Nombre de SQL no valido" } }
        if (!tableExists(tenant, table)) return 0
        val previousAutoCommit = tenant.autoCommit
        tenant.autoCommit = false
        try {
            var sealed = 0
            val select = "SELECT ${(idColumns + secretColumns).joinToString { "\"$it\"" }} FROM \"$table\""
            val rows = mutableListOf<List<String?>>()
            tenant.createStatement().use { statement ->
                statement.executeQuery(select).use { result ->
                    while (result.next()) rows += (1..idColumns.size + secretColumns.size).map { result.getString(it) }
                }
            }
            val where = idColumns.joinToString(" AND ") { "\"$it\"::text = ?" }
            for (row in rows) {
                val ids = row.take(idColumns.size)
                secretColumns.forEachIndexed { index, column ->
                    val value = row[idColumns.size + index]
                    if (value.isNullOrEmpty() || box.isSealed(value)) return@forEachIndexed
                    // Solo si el valor sigue siendo el leido: un cambio concurrente no se pisa.
                    val changed = tenant.prepareStatement("UPDATE \"$table\" SET \"$column\" = ? WHERE $where AND \"$column\" = ?").use { update ->
                        update.setString(1, box.seal(tenant, value))
                        ids.forEachIndexed { i, id -> update.setString(i + 2, id) }
                        update.setString(ids.size + 2, value)
                        update.executeUpdate()
                    }
                    sealed += changed
                }
            }
            tenant.commit()
            return sealed
        } catch (failure: Throwable) {
            tenant.rollback()
            throw failure
        } finally {
            tenant.autoCommit = previousAutoCommit
        }
    }

    private fun tableExists(tenant: Connection, table: String): Boolean =
        tenant.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
            statement.setString(1, "public.\"$table\"")
            statement.executeQuery().use { it.next() && it.getBoolean(1) }
        }
}
