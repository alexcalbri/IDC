package com.ideasdeveloper.idc.extensions.compras_monday

import com.ideasdeveloper.idc.modules.compras.ComprasImportContext
import com.ideasdeveloper.idc.modules.compras.ComprasValidationException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Una importacion de monday (fila de ext_compras_monday__jobs). */
data class MondayJob(
    val id: UUID,
    val boardId: String,
    val boardName: String,
    val groups: List<String>,
    val mapping: Map<String, String>,
    val status: String,
    val cursor: String?,
    val attempts: Int,
    val pages: Int,
    val readCount: Int,
    val createdCount: Int,
    val existingCount: Int,
    val failedCount: Int,
    val error: String,
    val createdBy: UUID?,
    val createdByName: String,
    val appliedBy: UUID?,
    val appliedByName: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Sigue en marcha o esperando que se confirme (solo hay una a la vez por empresa). */
    val active: Boolean get() = status in ActiveStatuses

    companion object {
        val ActiveStatuses = setOf("reading", "ready", "applying")
    }
}

/** Fila preparada para mostrar: el elemento, su estado de aplicacion y el aviso. */
data class MondayRow(val itemId: String, val payload: String, val status: String, val problem: String)

/** Acceso a las tablas propias de la extension. SQL siempre parametrizado; bloqueante (JDBC). */
object ComprasMondayStore {
    const val SettingsTable = "ext_compras_monday__settings"
    const val JobsTable = "ext_compras_monday__jobs"
    const val RowsTable = "ext_compras_monday__rows"
    const val MaxProblemLength = 500

    private const val JobColumns =
        "id, board_id, board_name, groups::text AS groups, mapping::text AS mapping, status, cursor, attempts, pages, read_count, " +
            "created_count, existing_count, failed_count, error, created_by, created_by_name, applied_by, applied_by_name, created_at, updated_at"

    // ---- Token (sellado con la llave de la empresa) ----

    /** El token ya abierto, o null si no hay o no se puede leer (entonces no debe enviarse a monday). */
    fun loadToken(ctx: ComprasImportContext): String? =
        ctx.tenant.prepareStatement("SELECT token FROM $SettingsTable WHERE id = 1").use { query ->
            query.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1)?.takeIf { it.isNotBlank() }?.let { sealed -> runCatching { ctx.open(sealed) }.getOrNull() }?.takeIf { it.isNotBlank() }
                else null
            }
        }

    fun hasToken(tenant: Connection): Boolean =
        tenant.prepareStatement("SELECT 1 FROM $SettingsTable WHERE id = 1 AND coalesce(token, '') <> ''").use { query ->
            query.executeQuery().use { rows -> rows.next() }
        }

    /** Guarda el token sellado (crea la fila si falta); null o vacio lo borra. */
    fun saveToken(ctx: ComprasImportContext, token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        ctx.tenant.prepareStatement(
            """
            INSERT INTO $SettingsTable (id, token) VALUES (1, ?)
            ON CONFLICT (id) DO UPDATE SET token = EXCLUDED.token, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { update ->
            update.setString(1, clean?.let { ctx.seal(it) })
            update.executeUpdate()
        }
    }

    // ---- Importaciones ----

    private fun ResultSet.job() = MondayJob(
        id = getObject("id", UUID::class.java),
        boardId = getString("board_id"),
        boardName = getString("board_name"),
        groups = ComprasMondayParse.decodeList(getString("groups")),
        mapping = ComprasMondayParse.decodeMap(getString("mapping")),
        status = getString("status"),
        cursor = getString("cursor"),
        attempts = getInt("attempts"),
        pages = getInt("pages"),
        readCount = getInt("read_count"),
        createdCount = getInt("created_count"),
        existingCount = getInt("existing_count"),
        failedCount = getInt("failed_count"),
        error = getString("error"),
        createdBy = getObject("created_by", UUID::class.java),
        createdByName = getString("created_by_name"),
        appliedBy = getObject("applied_by", UUID::class.java),
        appliedByName = getString("applied_by_name"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

    fun job(tenant: Connection, id: UUID): MondayJob? =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.job() else null }
        }

    /** La importacion mas reciente (la que muestra la tarjeta). */
    fun latestJob(tenant: Connection): MondayJob? =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable ORDER BY created_at DESC LIMIT 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.job() else null }
        }

    /** La importacion en marcha, si hay (reading, ready o applying). */
    fun activeJob(tenant: Connection): MondayJob? =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable WHERE status IN ('reading', 'ready', 'applying') ORDER BY created_at LIMIT 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.job() else null }
        }

    /** Importaciones en [status], las mas viejas primero, hasta [limit]. */
    fun jobsWithStatus(tenant: Connection, status: String, limit: Int = 5): List<MondayJob> =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable WHERE status = ? ORDER BY created_at LIMIT ?").use { query ->
            query.setString(1, status)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.job()) } }
        }

    fun insertJob(
        tenant: Connection, boardId: String, boardName: String, groups: List<String>, mapping: Map<String, String>,
        createdBy: UUID?, createdByName: String,
    ): UUID {
        val id = UUID.randomUUID()
        try {
        tenant.prepareStatement(
            """
            INSERT INTO $JobsTable (id, board_id, board_name, groups, mapping, status, created_by, created_by_name)
            VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), 'reading', ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, id)
            insert.setString(2, boardId)
            insert.setString(3, boardName.take(200))
            insert.setString(4, ComprasMondayParse.encodeList(groups))
            insert.setString(5, ComprasMondayParse.encodeMap(mapping))
            insert.setObject(6, createdBy)
            insert.setString(7, createdByName.take(200))
            insert.executeUpdate()
        }
        } catch (failure: SQLException) {
            // El indice unico parcial deja una sola importacion en marcha: la segunda en llegar recibe este aviso.
            if (failure.sqlState == "23505") throw ComprasValidationException("Ya hay una importacion en curso.")
            throw failure
        }
        return id
    }

    /** Cambia el estado solo si sigue en [from] (una importacion cancelada mientras tanto se queda cancelada). */
    fun transition(tenant: Connection, id: UUID, from: Set<String>, to: String, error: String = ""): Boolean =
        tenant.prepareStatement(
            "UPDATE $JobsTable SET status = ?, error = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ANY(?)"
        ).use { update ->
            update.setString(1, to)
            update.setString(2, error.take(MaxProblemLength))
            update.setObject(3, id)
            update.setArray(4, tenant.createArrayOf("text", from.toTypedArray()))
            update.executeUpdate() > 0
        }

    /** Pasa de "lista" a "aplicando" y deja anotado quien pulso Importar (con sus permisos se crean las solicitudes). */
    fun startApplying(tenant: Connection, id: UUID, userId: UUID?, userName: String): Boolean =
        tenant.prepareStatement(
            "UPDATE $JobsTable SET status = 'applying', applied_by = ?, applied_by_name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'ready'"
        ).use { update ->
            update.setObject(1, userId)
            update.setString(2, userName.take(200))
            update.setObject(3, id)
            update.executeUpdate() > 0
        }

    /**
     * Guarda una pagina leida: las filas (idempotente por elemento), el cursor siguiente, el conteo y las paginas, todo en una
     * transaccion. Solo si el cursor guardado sigue siendo [MondayJob.cursor] (otra corrida no la leyo ya) y la importacion sigue
     * leyendo. Sin cursor siguiente la lectura termina y queda lista. Una lectura buena reinicia los intentos fallidos.
     */
    fun saveReadPage(tenant: Connection, job: MondayJob, rows: List<MondayItem>, nextCursor: String?): Boolean = inTransaction(tenant) {
        tenant.prepareStatement(
            "INSERT INTO $RowsTable (job_id, item_id, payload) VALUES (?, ?, CAST(? AS jsonb)) ON CONFLICT (job_id, item_id) DO NOTHING"
        ).use { insert ->
            rows.forEach { item ->
                insert.setObject(1, job.id)
                insert.setString(2, item.id)
                insert.setString(3, ComprasMondayParse.encode(item))
                insert.addBatch()
            }
            insert.executeBatch()
        }
        val updated = tenant.prepareStatement(
            """
            UPDATE $JobsTable SET cursor = ?, status = ?, attempts = 0, pages = pages + 1, error = '', updated_at = CURRENT_TIMESTAMP,
                read_count = (SELECT count(*) FROM $RowsTable WHERE job_id = ?)
            WHERE id = ? AND status = 'reading' AND cursor IS NOT DISTINCT FROM ?
            """.trimIndent()
        ).use { update ->
            update.setString(1, nextCursor)
            update.setString(2, if (nextCursor == null) "ready" else "reading")
            update.setObject(3, job.id)
            update.setObject(4, job.id)
            update.setString(5, job.cursor)
            update.executeUpdate() > 0
        }
        if (!updated) throw RollbackSignal()
        true
    } ?: false

    /** Anota un fallo de lectura: sube los intentos y pasa al estado [status] (decide [ComprasMondayRetry]). */
    fun recordReadFailure(tenant: Connection, job: MondayJob, message: String, status: String) {
        tenant.prepareStatement(
            "UPDATE $JobsTable SET attempts = attempts + 1, status = ?, error = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'reading'"
        ).use { update ->
            update.setString(1, status)
            update.setString(2, message.take(MaxProblemLength))
            update.setObject(3, job.id)
            update.executeUpdate()
        }
    }

    /** Borra las importaciones terminadas hace mas de [days] dias (sus filas se van en cascada). */
    fun purgeOld(tenant: Connection, days: Int = 30): Int =
        tenant.prepareStatement(
            "DELETE FROM $JobsTable WHERE status IN ('done', 'failed', 'cancelled') AND updated_at < CURRENT_TIMESTAMP - make_interval(days => ?)"
        ).use { delete ->
            delete.setInt(1, days)
            delete.executeUpdate()
        }

    // ---- Candado por importacion (dos corridas no aplican las mismas filas) ----

    private fun lockKey(jobId: UUID) = "compras_monday:$jobId"

    /** Corre [block] solo si se obtiene el candado de la importacion; devuelve null si otra corrida lo tiene. Siempre lo libera. */
    fun <T> withJobLock(tenant: Connection, jobId: UUID, block: () -> T): T? {
        val locked = tenant.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))").use { query ->
            query.setString(1, lockKey(jobId))
            query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
        if (!locked) return null
        try {
            return block()
        } finally {
            runCatching {
                tenant.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))").use { query ->
                    query.setString(1, lockKey(jobId))
                    query.executeQuery().use { it.next() }
                }
            }
        }
    }

    // ---- Filas ----

    fun pendingRows(tenant: Connection, jobId: UUID, limit: Int): List<MondayRow> =
        tenant.prepareStatement(
            "SELECT item_id, payload::text AS payload, status, problem FROM $RowsTable WHERE job_id = ? AND status = 'pending' ORDER BY item_id LIMIT ?"
        ).use { query ->
            query.setObject(1, jobId)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
        }

    /** Filas para la vista previa: las primeras por elemento, con su estado. */
    fun previewRows(tenant: Connection, jobId: UUID, limit: Int): List<MondayRow> =
        tenant.prepareStatement(
            "SELECT item_id, payload::text AS payload, status, problem FROM $RowsTable WHERE job_id = ? ORDER BY item_id LIMIT ?"
        ).use { query ->
            query.setObject(1, jobId)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
        }

    /** Filas con error o con aviso (para mostrar los problemas). */
    fun problemRows(tenant: Connection, jobId: UUID, limit: Int): List<MondayRow> =
        tenant.prepareStatement(
            "SELECT item_id, payload::text AS payload, status, problem FROM $RowsTable WHERE job_id = ? AND (status = 'failed' OR problem <> '') ORDER BY item_id LIMIT ?"
        ).use { query ->
            query.setObject(1, jobId)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
        }

    private fun row(rows: ResultSet) = MondayRow(rows.getString("item_id"), rows.getString("payload"), rows.getString("status"), rows.getString("problem"))

    /** Marca una fila; solo cambia las que siguen pendientes (idempotente tras un reinicio). */
    fun markRow(tenant: Connection, jobId: UUID, itemId: String, status: String, problem: String) {
        tenant.prepareStatement("UPDATE $RowsTable SET status = ?, problem = ? WHERE job_id = ? AND item_id = ? AND status = 'pending'").use { update ->
            update.setString(1, status)
            update.setString(2, problem.take(MaxProblemLength))
            update.setObject(3, jobId)
            update.setString(4, itemId)
            update.executeUpdate()
        }
    }

    /** Recalcula los conteos de la importacion desde sus filas. */
    fun refreshCounts(tenant: Connection, jobId: UUID) {
        tenant.prepareStatement(
            """
            UPDATE $JobsTable SET updated_at = CURRENT_TIMESTAMP,
                created_count = (SELECT count(*) FROM $RowsTable WHERE job_id = ? AND status = 'applied'),
                existing_count = (SELECT count(*) FROM $RowsTable WHERE job_id = ? AND status = 'existing'),
                failed_count = (SELECT count(*) FROM $RowsTable WHERE job_id = ? AND status = 'failed')
            WHERE id = ?
            """.trimIndent()
        ).use { update ->
            update.setObject(1, jobId)
            update.setObject(2, jobId)
            update.setObject(3, jobId)
            update.setObject(4, jobId)
            update.executeUpdate()
        }
    }

    fun pendingCount(tenant: Connection, jobId: UUID): Int =
        tenant.prepareStatement("SELECT count(*) FROM $RowsTable WHERE job_id = ? AND status = 'pending'").use { query ->
            query.setObject(1, jobId)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    // ---- Transaccion ----

    private class RollbackSignal : RuntimeException()

    /** Ejecuta [block] en una transaccion y deja el modo de confirmacion como estaba; devuelve null si el bloque pidio deshacer. */
    private fun <T> inTransaction(tenant: Connection, block: () -> T): T? {
        val previous = tenant.autoCommit
        tenant.autoCommit = false
        return try {
            val result = block()
            tenant.commit()
            result
        } catch (signal: RollbackSignal) {
            tenant.rollback()
            null
        } catch (failure: Exception) {
            runCatching { tenant.rollback() }
            throw failure
        } finally {
            tenant.autoCommit = previous
        }
    }
}
