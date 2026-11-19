package com.ideasdeveloper.idc.extensions.inmobiliaria_monday

import com.ideasdeveloper.idc.modules.crm.CrmScreens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/** Acceso a las tablas de la extension (importaciones y filas leidas). Todo el SQL es parametrizado. */
object InmobiliariaMondayStore {
    const val SettingsTable = InmobiliariaMondaySettingsStore.Table
    const val JobsTable = "ext_inmobiliaria_monday__jobs"
    const val RowsTable = "ext_inmobiliaria_monday__rows"

    private val json = Json { ignoreUnknownKeys = true }

    private const val JobColumns =
        "id, board_id, board_name, kind, new_listings, mapping::text, columns::text, state, read_started, read_cursor, rows_read, " +
            "created_count, updated_count, skipped_count, failed_count, problems::text, error, created_by, created_by_name, " +
            "applied_by, applied_by_name, read_attempts"

    // ---- JSON de las columnas jsonb ----

    fun mappingJson(mapping: Map<String, String>): String =
        buildJsonObject { mapping.forEach { (column, field) -> put(column, field) } }.toString()

    fun columnsJson(columns: List<MondayColumn>): String =
        buildJsonArray { columns.forEach { add(buildJsonObject { put("id", it.id); put("title", it.title); put("type", it.type) }) } }.toString()

    fun problemsJson(problems: List<String>): String = JsonArray(problems.map(::JsonPrimitive)).toString()

    fun stringMap(text: String?): Map<String, String> =
        (runCatching { json.parseToJsonElement(text.orEmpty()) }.getOrNull() as? JsonObject)
            ?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()

    fun columns(text: String?): List<MondayColumn> =
        (runCatching { json.parseToJsonElement(text.orEmpty()) }.getOrNull() as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun field(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            field("title").takeIf { it.isNotEmpty() }?.let { MondayColumn(field("id"), it, field("type")) }
        }

    fun problems(text: String?): List<String> =
        (runCatching { json.parseToJsonElement(text.orEmpty()) }.getOrNull() as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    // ---- Importaciones ----

    private fun job(rows: ResultSet) = MondayJob(
        id = rows.getObject(1, UUID::class.java),
        boardId = rows.getString(2),
        boardName = rows.getString(3).orEmpty(),
        kind = rows.getString(4),
        newListings = rows.getBoolean(5),
        mapping = stringMap(rows.getString(6)),
        columns = columns(rows.getString(7)),
        state = rows.getString(8),
        readStarted = rows.getBoolean(9),
        readCursor = rows.getString(10).orEmpty(),
        rowsRead = rows.getInt(11),
        created = rows.getInt(12),
        updated = rows.getInt(13),
        skipped = rows.getInt(14),
        failed = rows.getInt(15),
        problems = problems(rows.getString(16)),
        error = rows.getString(17).orEmpty(),
        createdBy = rows.getObject(18, UUID::class.java),
        createdByName = rows.getString(19).orEmpty(),
        appliedBy = rows.getObject(20, UUID::class.java),
        appliedByName = rows.getString(21).orEmpty(),
        readAttempts = rows.getInt(22),
    )

    fun find(tenant: Connection, id: UUID): MondayJob? =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) job(rows) else null }
        }

    /** Importaciones recientes, la mas nueva primero. */
    fun recent(tenant: Connection, limit: Int = 10): List<MondayJob> =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable ORDER BY created_at DESC LIMIT ?").use { query ->
            query.setInt(1, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(job(rows)) } }
        }

    /** Importaciones en un estado, la mas antigua primero (para que el trabajo en segundo plano las atienda en orden). */
    fun inState(tenant: Connection, state: String): List<MondayJob> =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable WHERE state = ? ORDER BY created_at, id").use { query ->
            query.setString(1, state)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(job(rows)) } }
        }

    /** Hay una importacion leyendo o aplicando: se hace una a la vez para no repartir el tiempo del trabajo en segundo plano. */
    fun busy(tenant: Connection): Boolean =
        tenant.prepareStatement("SELECT 1 FROM $JobsTable WHERE state IN ('reading', 'applying') LIMIT 1").use { query ->
            query.executeQuery().use { it.next() }
        }

    /** Mensaje cuando el indice unico de importaciones activas rechaza una segunda lectura o aplicacion del mismo tablero. */
    const val AlreadyActive = "Ya hay una importacion leyendo o aplicando ese tablero; espera a que termine o cancelala."

    private fun <T> guardActive(block: () -> T): T = try {
        block()
    } catch (failure: SQLException) {
        // 23505 = violacion de unicidad (ext_inmobiliaria_monday__jobs_active_uq).
        if (failure.sqlState == "23505") throw InmobiliariaMondayException(AlreadyActive)
        throw failure
    }

    fun create(
        tenant: Connection,
        boardId: String,
        boardName: String,
        kind: String,
        newListings: Boolean,
        mapping: Map<String, String>,
        createdBy: UUID?,
        createdByName: String,
    ): UUID {
        val id = UUID.randomUUID()
        guardActive {
            tenant.prepareStatement(
                "INSERT INTO $JobsTable (id, board_id, board_name, kind, new_listings, mapping, created_by, created_by_name) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
            ).use { insert ->
                insert.setObject(1, id)
                insert.setString(2, boardId)
                insert.setString(3, boardName.take(300))
                insert.setString(4, kind)
                insert.setBoolean(5, newListings)
                insert.setString(6, mappingJson(mapping))
                insert.setObject(7, createdBy)
                insert.setString(8, createdByName.take(200))
                insert.executeUpdate()
            }
        }
        return id
    }

    /** Borra la importacion con sus filas (ON DELETE CASCADE). */
    fun delete(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("DELETE FROM $JobsTable WHERE id = ?").use { delete ->
            delete.setObject(1, id)
            delete.executeUpdate() > 0
        }

    /** Cambia de estado solo si sigue en [from] (asi una cancelacion no se pisa con el avance del trabajo). */
    fun transition(tenant: Connection, id: UUID, from: String, to: String, error: String = ""): Boolean =
        tenant.prepareStatement("UPDATE $JobsTable SET state = ?, error = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = ?").use { update ->
            update.setString(1, to)
            update.setString(2, error.take(1000))
            update.setObject(3, id)
            update.setString(4, from)
            update.executeUpdate() > 0
        }

    /** Guarda el mapeo solo si la importacion sigue lista para revisar; si cambio de estado mientras tanto, falla. */
    fun setMapping(tenant: Connection, id: UUID, mapping: Map<String, String>) {
        val updated = tenant.prepareStatement("UPDATE $JobsTable SET mapping = ?::jsonb, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'ready'").use { update ->
            update.setString(1, mappingJson(mapping))
            update.setObject(2, id)
            update.executeUpdate()
        }
        if (updated == 0) throw InmobiliariaMondayException("La importacion cambio de estado; el mapeo solo se cambia antes de aplicar.")
    }

    /** Pasa de lista a aplicando y guarda quien pulso Aplicar (es quien actua en Inmobiliaria); false si ya no estaba lista. */
    fun startApplying(tenant: Connection, id: UUID, by: UUID?, byName: String): Boolean = guardActive {
        tenant.prepareStatement(
            "UPDATE $JobsTable SET state = 'applying', error = '', applied_by = ?, applied_by_name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'ready'",
        ).use { update ->
            update.setObject(1, by)
            update.setString(2, byName.take(200))
            update.setObject(3, id)
            update.executeUpdate() > 0
        }
    }

    /** Anota un intento de lectura fallido por un error pasajero y devuelve los intentos acumulados; null si la importacion ya no esta leyendo. */
    fun recordReadFailure(tenant: Connection, id: UUID): Int? =
        tenant.prepareStatement("UPDATE $JobsTable SET read_attempts = read_attempts + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'reading' RETURNING read_attempts").use { update ->
            update.setObject(1, id)
            update.executeQuery().use { if (it.next()) it.getInt(1) else null }
        }

    /** Hay una importacion terminada de ese tipo (las transacciones se importan despues de las propiedades). */
    fun hasDoneJob(tenant: Connection, kind: String): Boolean =
        tenant.prepareStatement("SELECT 1 FROM $JobsTable WHERE kind = ? AND state = 'done' LIMIT 1").use { query ->
            query.setString(1, kind)
            query.executeQuery().use { it.next() }
        }

    /** Hay propiedades en Inmobiliaria que vinieron de monday (su clave de importacion empieza por monday:), aunque se haya descartado el historial. */
    fun hasImportedProperties(tenant: Connection): Boolean {
        val exists = tenant.prepareStatement("SELECT to_regclass('ext_inmobiliaria__properties') IS NOT NULL").use { query -> query.executeQuery().use { it.next() && it.getBoolean(1) } }
        if (!exists) return false
        return tenant.prepareStatement("SELECT 1 FROM ext_inmobiliaria__properties WHERE import_key LIKE 'monday:%' LIMIT 1").use { query -> query.executeQuery().use { it.next() } }
    }

    /**
     * Si [userId] sigue pudiendo importar: usuario activo y dueno del negocio o con crm.settings. Es la misma regla con la que el CRM
     * resuelve permisos por usuario fuera de una sesion (application_users, business_owner y application_user_permissions).
     */
    fun userMayImport(tenant: Connection, userId: UUID): Boolean =
        tenant.prepareStatement(
            """
            SELECT 1 FROM application_users users
            WHERE users.id = ? AND users.is_active
              AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
                   OR EXISTS (SELECT 1 FROM application_user_permissions granted WHERE granted.user_id = users.id AND granted.permission_id = ?))
            """.trimIndent(),
        ).use { query ->
            query.setObject(1, userId)
            query.setString(2, CrmScreens.Settings)
            query.executeQuery().use { it.next() }
        }

    /** Guarda el avance de la lectura (cursor y columnas) si la importacion sigue leyendo; false si ya no (cancelada). Una pagina leida borra los intentos fallidos. */
    fun saveReadProgress(tenant: Connection, id: UUID, boardName: String?, columns: List<MondayColumn>?, cursor: String): Boolean =
        tenant.prepareStatement(
            """
            UPDATE $JobsTable SET read_started = TRUE, read_cursor = ?, read_attempts = 0,
                board_name = COALESCE(?, board_name), columns = COALESCE(?::jsonb, columns), updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND state = 'reading'
            """.trimIndent(),
        ).use { update ->
            update.setString(1, cursor)
            update.setString(2, boardName?.take(300))
            update.setString(3, columns?.let(::columnsJson))
            update.setObject(4, id)
            update.executeUpdate() > 0
        }

    /** rows_read = filas realmente guardadas (un elemento repetido por la paginacion de monday reemplaza su fila y no suma). Devuelve el total. */
    fun syncRowsRead(tenant: Connection, id: UUID): Int =
        tenant.prepareStatement("UPDATE $JobsTable SET rows_read = (SELECT count(*) FROM $RowsTable WHERE job_id = ?) WHERE id = ? RETURNING rows_read").use { update ->
            update.setObject(1, id)
            update.setObject(2, id)
            update.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }

    /** Termina la lectura: queda lista para revisar el mapeo (con el sugerido si no traia uno); [notice] es un aviso para quien revisa. */
    fun finishReading(tenant: Connection, id: UUID, mapping: Map<String, String>, notice: String = ""): Boolean =
        tenant.prepareStatement("UPDATE $JobsTable SET state = 'ready', mapping = ?::jsonb, error = ?, read_cursor = '', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'reading'").use { update ->
            update.setString(1, mappingJson(mapping))
            update.setString(2, notice.take(1000))
            update.setObject(3, id)
            update.executeUpdate() > 0
        }

    /** Suma los conteos de un lote, los problemas (con tope) y deja el estado final si ya no quedan filas. */
    fun addResult(tenant: Connection, id: UUID, created: Int, updated: Int, skipped: Int, failed: Int, problems: List<String>) {
        val current = find(tenant, id)?.problems.orEmpty()
        tenant.prepareStatement(
            """
            UPDATE $JobsTable SET created_count = created_count + ?, updated_count = updated_count + ?, skipped_count = skipped_count + ?,
                failed_count = failed_count + ?, problems = ?::jsonb, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
        ).use { update ->
            update.setInt(1, created)
            update.setInt(2, updated)
            update.setInt(3, skipped)
            update.setInt(4, failed)
            update.setString(5, problemsJson(MondayMapping.capProblems(current, problems)))
            update.setObject(6, id)
            update.executeUpdate()
        }
    }

    // ---- Filas ----

    /** Guarda las filas leidas; un elemento ya guardado en la importacion se reemplaza (la paginacion de monday puede repetirlo). */
    fun insertRows(tenant: Connection, jobId: UUID, items: List<MondayItem>) {
        if (items.isEmpty()) return
        tenant.prepareStatement(
            """
            INSERT INTO $RowsTable (job_id, item_id, import_key, data) VALUES (?, ?, ?, ?::jsonb)
            ON CONFLICT (job_id, item_id) DO UPDATE SET data = EXCLUDED.data
            """.trimIndent(),
        ).use { insert ->
            items.forEach { item ->
                insert.setObject(1, jobId)
                insert.setString(2, item.id)
                insert.setString(3, MondayMapping.importKey(item.id))
                insert.setString(4, mappingJson(item.values))
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private fun row(rows: ResultSet) = MondayRow(rows.getLong(1), rows.getString(2), rows.getString(3), stringMap(rows.getString(4)), rows.getString(5), rows.getString(6).orEmpty())

    private const val RowColumns = "id, item_id, import_key, data::text, state, problem"

    /** Filas en un estado, en el orden en que se leyeron. */
    fun rows(tenant: Connection, jobId: UUID, state: String?, limit: Int): List<MondayRow> {
        val where = if (state == null) "job_id = ?" else "job_id = ? AND state = ?"
        return tenant.prepareStatement("SELECT $RowColumns FROM $RowsTable WHERE $where ORDER BY id LIMIT ?").use { query ->
            query.setObject(1, jobId)
            var next = 2
            if (state != null) query.setString(next++, state)
            query.setInt(next, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
        }
    }

    fun markRow(tenant: Connection, rowId: Long, state: String, problem: String) {
        tenant.prepareStatement("UPDATE $RowsTable SET state = ?, problem = ? WHERE id = ?").use { update ->
            update.setString(1, state)
            update.setString(2, problem.take(1000))
            update.setLong(3, rowId)
            update.executeUpdate()
        }
    }

    /** Filas de la importacion por estado. */
    fun rowCounts(tenant: Connection, jobId: UUID): Map<String, Int> =
        tenant.prepareStatement("SELECT state, count(*) FROM $RowsTable WHERE job_id = ? GROUP BY state").use { query ->
            query.setObject(1, jobId)
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) } }
        }

    fun count(tenant: Connection, table: String): Int =
        tenant.prepareStatement("SELECT count(*) FROM $table").use { query -> query.executeQuery().use { if (it.next()) it.getInt(1) else 0 } }

    /** Texto de un valor JSON para mostrar. */
    fun text(element: JsonElement?): String = (element as? JsonPrimitive)?.contentOrNull.orEmpty()
}
