package com.ideasdeveloper.idc.extensions.tareas_monday

import com.ideasdeveloper.idc.modules.tareas.ImportedTask
import com.ideasdeveloper.idc.server.extensions.ExtensionStatus
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretDecryptionException
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Id de la extension (el mismo de extension.json y de TareasImportSource.sourceId). */
const val TareasMondayId = "tareas_monday"

/** Permiso del modulo Tareas que exige la pantalla Importar y las herramientas MCP de esta fuente. */
const val TareasImportPermission = "tareas.import"

/**
 * Error con un mensaje que se le puede mostrar al usuario o al agente tal cual (nunca lleva el token). [transient] marca
 * los fallos pasajeros de monday (red, tiempo agotado, 429, 5xx): el trabajo reintenta en vez de fallar.
 */
class TareasMondayException(message: String, val transient: Boolean = false) : Exception(message)

/**
 * Persistencia del token de monday en la tabla propia de la extension (ext_tareas_monday__settings, fila id = 1).
 * El token se guarda sellado con la llave de la empresa; jamas se registra ni se devuelve a una pantalla o herramienta:
 * lo unico que se muestra es si esta configurado.
 */
object TareasMondaySettings {
    const val Table = "ext_tareas_monday__settings"
    const val UnavailableMessage = "No se pueden guardar secretos: falta la llave maestra del servidor (IDC_MASTER_KEY). Avisa al dueno del servidor."

    /** La extension esta activa en la empresa (tenant_extensions.status = enabled); sin la tabla o la fila, no. */
    fun extensionEnabled(tenant: Connection): Boolean = try {
        tenant.prepareStatement("SELECT status FROM tenant_extensions WHERE extension_id = ?").use { query ->
            query.setString(1, TareasMondayId)
            query.executeQuery().use { it.next() && it.getString(1) == ExtensionStatus.Enabled }
        }
    } catch (failure: SQLException) {
        false
    }

    /** Hay un token guardado (no dice si se puede abrir). */
    fun configured(tenant: Connection): Boolean =
        tenant.prepareStatement("SELECT 1 FROM $Table WHERE id = 1 AND coalesce(token, '') <> ''").use { query ->
            query.executeQuery().use { it.next() }
        }

    /** El token ya abierto, o null si no hay o no se puede abrir (llave maestra ausente o distinta): no debe enviarse a monday. */
    fun loadToken(secrets: SecretBox, tenant: Connection): String? {
        val stored = tenant.prepareStatement("SELECT token FROM $Table WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }?.takeIf { it.isNotBlank() } ?: return null
        return try {
            secrets.open(tenant, stored).takeIf { it.isNotBlank() }
        } catch (failure: SecretsUnavailableException) {
            null
        } catch (failure: SecretDecryptionException) {
            null
        }
    }

    /** Guarda el token sellado (upsert); null o vacio lo borra. Sin llave maestra no guarda nada en claro. */
    fun saveToken(secrets: SecretBox, tenant: Connection, token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        val sealed = clean?.let {
            try {
                secrets.seal(tenant, it)
            } catch (failure: SecretsUnavailableException) {
                throw TareasMondayException(UnavailableMessage)
            }
        }
        tenant.prepareStatement(
            """
            INSERT INTO $Table (id, token) VALUES (1, ?)
            ON CONFLICT (id) DO UPDATE SET token = EXCLUDED.token, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).use { update ->
            update.setString(1, sealed)
            update.executeUpdate()
        }
    }
}

object MondayJobStatus {
    const val Reading = "reading"
    const val Preview = "preview"
    const val Applying = "applying"
    const val Done = "done"
    const val Failed = "failed"
    const val Cancelled = "cancelled"
}

/** Una importacion de un tablero. [applied] cuenta las filas ya procesadas (con cualquier resultado). */
data class MondayJob(
    val id: UUID,
    val boardId: String,
    val boardName: String,
    val targetBoardId: UUID?,
    val status: String,
    val cursor: String?,
    val pages: Int,
    val retries: Int,
    val summary: MondaySummaryDto?,
    val mapping: MondayMapping,
    val total: Int,
    val applied: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    val filesDone: Int,
    val filesFailed: Int,
    val message: String,
    val createdBy: UUID?,
    val createdAt: Instant,
) {
    val active: Boolean get() = status == MondayJobStatus.Reading || status == MondayJobStatus.Applying

    /** Abierta: ocupa el unico lugar por empresa (tambien la que espera revision). */
    val open: Boolean get() = active || status == MondayJobStatus.Preview
}

/** Fila que no se pudo importar del todo (omitida, con error o con archivos que fallaron), para el resumen final. */
data class MondayRowProblem(val rowNo: Int, val title: String, val outcome: String, val message: String)

/** Fila pendiente: sin aplicar ([outcome] nulo) o ya aplicada con archivos por bajar ([taskId]). */
class MondayPendingRow(val rowNo: Int, val payload: String, val files: String, val outcome: String?, val taskId: UUID?)

/** Tablas y consultas de los trabajos de importacion (todo con parametros; los textos de monday nunca van en el SQL). */
object TareasMondayJobs {
    const val JobsTable = "ext_tareas_monday__jobs"
    const val RowsTable = "ext_tareas_monday__rows"
    const val PeopleTable = "ext_tareas_monday__people"
    const val OpenJobMessage = "Ya hay una importacion en curso o por revisar; aplicala, cancelala o espera a que termine."
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Cola ----

    /** Pone en cola la lectura de un tablero. Solo hay una importacion en curso a la vez. */
    fun queueRead(tenant: Connection, boardId: String, boardName: String, targetBoardId: UUID?, mapping: MondayMapping, createdBy: UUID?): UUID {
        if (!TareasMondayClient.isBoardId(boardId)) throw TareasMondayException("El id del tablero debe ser un numero.")
        if (recent(tenant, 20).any { it.open }) throw TareasMondayException(OpenJobMessage)
        val id = UUID.randomUUID()
        try {
            tenant.prepareStatement(
                "INSERT INTO $JobsTable (id, board_id, board_name, target_board_id, status, mapping, created_by) VALUES (?, ?, ?, ?, 'reading', CAST(? AS jsonb), ?)",
            ).use { insert ->
                insert.setObject(1, id)
                insert.setString(2, boardId)
                insert.setString(3, boardName.take(200))
                insert.setObject(4, targetBoardId)
                insert.setString(5, json.encodeToString(MondayMapping.serializer(), mapping))
                insert.setObject(6, createdBy)
                insert.executeUpdate()
            }
        } catch (failure: SQLException) {
            // El indice unico parcial deja una sola importacion abierta por empresa (dos peticiones a la vez).
            if (failure.sqlState == "23505") throw TareasMondayException(OpenJobMessage)
            throw failure
        }
        return id
    }

    /** Pasa de "preview" a "applying"; false si el trabajo ya no esta en revision. */
    fun requestApply(tenant: Connection, id: UUID): Boolean = changeStatus(tenant, id, MondayJobStatus.Applying, "", from = listOf(MondayJobStatus.Preview))

    /** Cancela un trabajo que aun no termino; false si ya habia terminado. */
    fun cancel(tenant: Connection, id: UUID): Boolean =
        changeStatus(tenant, id, MondayJobStatus.Cancelled, "Cancelada por el usuario.", from = listOf(MondayJobStatus.Reading, MondayJobStatus.Preview, MondayJobStatus.Applying))

    fun changeStatus(tenant: Connection, id: UUID, status: String, message: String, from: List<String>): Boolean =
        tenant.prepareStatement(
            "UPDATE $JobsTable SET status = ?, message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ANY (?)",
        ).use { update ->
            update.setString(1, status)
            update.setString(2, message.take(500))
            update.setObject(3, id)
            update.setArray(4, tenant.createArrayOf("varchar", from.toTypedArray()))
            update.executeUpdate() > 0
        }

    // ---- Consultas ----

    private const val JobColumns =
        "id, board_id, board_name, target_board_id, status, cursor, pages, retries, summary::text, mapping::text, total, applied, created, updated, skipped, failed, " +
            "files_done, files_failed, message, created_by, created_at"

    private fun toJob(rows: ResultSet) = MondayJob(
        id = rows.getObject(1, UUID::class.java),
        boardId = rows.getString(2),
        boardName = rows.getString(3),
        targetBoardId = rows.getObject(4, UUID::class.java),
        status = rows.getString(5),
        cursor = rows.getString(6),
        pages = rows.getInt(7),
        retries = rows.getInt(8),
        summary = rows.getString(9)?.let { runCatching { json.decodeFromString(MondaySummaryDto.serializer(), it) }.getOrNull() },
        mapping = runCatching { json.decodeFromString(MondayMapping.serializer(), rows.getString(10)) }.getOrDefault(MondayMapping()),
        total = rows.getInt(11),
        applied = rows.getInt(12),
        created = rows.getInt(13),
        updated = rows.getInt(14),
        skipped = rows.getInt(15),
        failed = rows.getInt(16),
        filesDone = rows.getInt(17),
        filesFailed = rows.getInt(18),
        message = rows.getString(19).orEmpty(),
        createdBy = rows.getObject(20, UUID::class.java),
        createdAt = rows.getTimestamp(21).toInstant(),
    )

    fun find(tenant: Connection, id: UUID): MondayJob? =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { if (it.next()) toJob(it) else null }
        }

    fun recent(tenant: Connection, limit: Int): List<MondayJob> =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable ORDER BY created_at DESC LIMIT ?").use { query ->
            query.setInt(1, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(toJob(rows)) } }
        }

    fun withStatus(tenant: Connection, status: String): List<MondayJob> =
        tenant.prepareStatement("SELECT $JobColumns FROM $JobsTable WHERE status = ? ORDER BY created_at").use { query ->
            query.setString(1, status)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(toJob(rows)) } }
        }

    fun statusOf(tenant: Connection, id: UUID): String? =
        tenant.prepareStatement("SELECT status FROM $JobsTable WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    /** Un lote de filas como tareas (despues de [afterRowNo]) para calcular el resumen sin cargar todo el tablero. */
    fun taskBatch(tenant: Connection, jobId: UUID, afterRowNo: Int, limit: Int): List<Pair<Int, ImportedTask>> =
        tenant.prepareStatement("SELECT row_no, payload::text FROM $RowsTable WHERE job_id = ? AND row_no > ? ORDER BY row_no LIMIT ?").use { query ->
            query.setObject(1, jobId)
            query.setInt(2, afterRowNo)
            query.setInt(3, limit)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) runCatching { MondayRowDto.decode(rows.getString(2)) }.getOrNull()?.let { add(rows.getInt(1) to it) } }
            }
        }

    /** Numero de la ultima fila del lote que sigue a [afterRowNo] (para avanzar aunque alguna fila no se pueda leer); null si no hay mas. */
    fun lastRowOfBatch(tenant: Connection, jobId: UUID, afterRowNo: Int, limit: Int): Int? =
        tenant.prepareStatement("SELECT max(row_no) FROM (SELECT row_no FROM $RowsTable WHERE job_id = ? AND row_no > ? ORDER BY row_no LIMIT ?) t").use { query ->
            query.setObject(1, jobId)
            query.setInt(2, afterRowNo)
            query.setInt(3, limit)
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1).takeIf { !rows.wasNull() } else null }
        }

    /** Cuantos archivos hay por descargar en las filas del trabajo. */
    fun fileCount(tenant: Connection, jobId: UUID): Int =
        tenant.prepareStatement("SELECT coalesce(sum(jsonb_array_length(files)), 0) FROM $RowsTable WHERE job_id = ?").use { query ->
            query.setObject(1, jobId)
            query.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }

    /** Filas omitidas, con error o con algun mensaje (archivos que fallaron): las primeras [limit]. */
    fun problems(tenant: Connection, jobId: UUID, limit: Int): List<MondayRowProblem> =
        tenant.prepareStatement(
            "SELECT row_no, coalesce(payload->>'title', ''), coalesce(outcome, ''), message FROM $RowsTable " +
                "WHERE job_id = ? AND (outcome IN ('skipped', 'failed') OR message <> '') ORDER BY row_no LIMIT ?",
        ).use { query ->
            query.setObject(1, jobId)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(MondayRowProblem(rows.getInt(1), rows.getString(2), rows.getString(3), rows.getString(4).orEmpty())) } }
        }

    /** Filas por trabajar: las sin aplicar y las aplicadas a las que aun les faltan archivos. */
    fun pending(tenant: Connection, jobId: UUID, limit: Int): List<MondayPendingRow> =
        tenant.prepareStatement(
            "SELECT row_no, payload::text, files::text, outcome, task_id FROM $RowsTable WHERE job_id = ? AND (outcome IS NULL OR files_pending) ORDER BY row_no LIMIT ?",
        ).use { query ->
            query.setObject(1, jobId)
            query.setInt(2, limit)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(MondayPendingRow(rows.getInt(1), rows.getString(2), rows.getString(3).orEmpty(), rows.getString(4), rows.getObject(5, UUID::class.java))) }
            }
        }

    // ---- Personas ----

    fun people(tenant: Connection, jobId: UUID): Map<String, MondayPerson> =
        tenant.prepareStatement("SELECT monday_id, name, email FROM $PeopleTable WHERE job_id = ?").use { query ->
            query.setObject(1, jobId)
            query.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), MondayPerson(rows.getString(1), rows.getString(2), rows.getString(3))) }
            }
        }

    fun savePeople(tenant: Connection, jobId: UUID, people: Collection<MondayPerson>) {
        if (people.isEmpty()) return
        tenant.prepareStatement(
            "INSERT INTO $PeopleTable (job_id, monday_id, name, email) VALUES (?, ?, ?, ?) ON CONFLICT (job_id, monday_id) DO NOTHING",
        ).use { insert ->
            people.forEach { person ->
                insert.setObject(1, jobId)
                insert.setString(2, person.id)
                insert.setString(3, person.name.take(200))
                insert.setString(4, person.email.take(320))
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    // ---- Lectura: una pagina por paso ----

    /**
     * Guarda las filas de una pagina, el cursor y el contador de paginas, juntos. Repetir la pagina no duplica filas
     * (indice por elemento). Un trabajo cancelado mientras se leia no se toca. Al leer la ultima pagina el cursor queda
     * vacio y el trabajo sigue en "reading" hasta que se calcule el resumen ([moveToPreview]).
     */
    fun savePage(tenant: Connection, jobId: UUID, rows: List<Triple<String, String, String>>, nextCursor: String): Boolean =
        inTransaction(tenant) {
            if (statusOf(tenant, jobId) != MondayJobStatus.Reading) return@inTransaction false
            tenant.prepareStatement(
                "INSERT INTO $RowsTable (job_id, row_no, item_id, payload, files) " +
                    "VALUES (?, (SELECT coalesce(max(row_no), 0) + 1 FROM $RowsTable WHERE job_id = ?), ?, CAST(? AS jsonb), CAST(? AS jsonb)) " +
                    "ON CONFLICT (job_id, item_id) DO NOTHING",
            ).use { insert ->
                // Una por una: cada fila toma el siguiente numero libre (las repetidas se descartan por elemento).
                rows.forEach { (itemId, payload, files) ->
                    insert.setObject(1, jobId)
                    insert.setObject(2, jobId)
                    insert.setString(3, itemId)
                    insert.setString(4, payload)
                    insert.setString(5, files)
                    insert.executeUpdate()
                }
            }
            tenant.prepareStatement(
                "UPDATE $JobsTable SET cursor = ?, total = (SELECT count(*) FROM $RowsTable WHERE job_id = ?), pages = pages + 1, retries = 0, " +
                    "message = '', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'reading'",
            ).use { update ->
                update.setString(1, nextCursor)
                update.setObject(2, jobId)
                update.setObject(3, jobId)
                update.executeUpdate()
            }
            true
        }

    /** Pasa un trabajo que termino de leer a revision, con su resumen guardado; false si ya no esta leyendo. */
    fun moveToPreview(tenant: Connection, jobId: UUID, summary: MondaySummaryDto): Boolean =
        tenant.prepareStatement(
            "UPDATE $JobsTable SET status = 'preview', summary = CAST(? AS jsonb), message = '', retries = 0, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'reading'",
        ).use { update ->
            update.setString(1, json.encodeToString(MondaySummaryDto.serializer(), summary))
            update.setObject(2, jobId)
            update.executeUpdate() > 0
        }

    /** Suma un reintento por un error temporal y deja el mensaje; devuelve los reintentos seguidos. */
    fun bumpRetries(tenant: Connection, jobId: UUID, message: String): Int =
        tenant.prepareStatement("UPDATE $JobsTable SET retries = retries + 1, message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? RETURNING retries").use { update ->
            update.setString(1, message.take(500))
            update.setObject(2, jobId)
            update.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }

    fun resetRetries(tenant: Connection, jobId: UUID) {
        tenant.prepareStatement("UPDATE $JobsTable SET retries = 0 WHERE id = ? AND retries <> 0").use { update ->
            update.setObject(1, jobId)
            update.executeUpdate()
        }
    }

    // ---- Aplicacion ----

    /** Guarda el resultado de aplicar la tarea de una fila, con su tarea; [filesPending] deja los archivos para despues. */
    fun saveApplied(tenant: Connection, jobId: UUID, rowNo: Int, outcome: String, message: String, taskId: UUID?, filesPending: Boolean) {
        tenant.prepareStatement("UPDATE $RowsTable SET outcome = ?, message = ?, task_id = ?, files_pending = ? WHERE job_id = ? AND row_no = ?").use { update ->
            update.setString(1, outcome)
            update.setString(2, message.take(500))
            update.setObject(3, taskId)
            update.setBoolean(4, filesPending)
            update.setObject(5, jobId)
            update.setInt(6, rowNo)
            update.executeUpdate()
        }
    }

    /** Cierra los archivos de una fila (con el aviso de los que fallaron, si los hay). */
    fun finishFiles(tenant: Connection, jobId: UUID, rowNo: Int, message: String) {
        tenant.prepareStatement("UPDATE $RowsTable SET files_pending = false, message = CASE WHEN ? = '' THEN message ELSE ? END WHERE job_id = ? AND row_no = ?").use { update ->
            update.setString(1, message)
            update.setString(2, message.take(500))
            update.setObject(3, jobId)
            update.setInt(4, rowNo)
            update.executeUpdate()
        }
    }

    /** Claves de importacion ya guardadas como adjuntos de la tarea, para no volver a bajar esos archivos. */
    fun attachedKeys(tenant: Connection, taskId: UUID, keys: Collection<String>): Set<String> =
        tenant.prepareStatement("SELECT import_key FROM tareas_attachments WHERE task_id = ? AND import_key = ANY (?)").use { query ->
            query.setObject(1, taskId)
            query.setArray(2, tenant.createArrayOf("varchar", keys.toTypedArray()))
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }

    fun addFiles(tenant: Connection, jobId: UUID, done: Int, failed: Int) {
        if (done == 0 && failed == 0) return
        tenant.prepareStatement("UPDATE $JobsTable SET files_done = files_done + ?, files_failed = files_failed + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setInt(1, done)
            update.setInt(2, failed)
            update.setObject(3, jobId)
            update.executeUpdate()
        }
    }

    /** Recuenta los resultados de las filas del trabajo (idempotente: siempre sale de las filas, no de sumas). */
    fun recount(tenant: Connection, jobId: UUID) {
        fun count(outcome: String?) =
            "(SELECT count(*) FROM $RowsTable r WHERE r.job_id = j.id AND " + (if (outcome == null) "r.outcome IS NOT NULL)" else "r.outcome = '$outcome')")
        tenant.prepareStatement(
            "UPDATE $JobsTable j SET applied = ${count(null)}, created = ${count("created")}, updated = ${count("updated")}, " +
                "skipped = ${count("skipped")}, failed = ${count("failed")}, updated_at = CURRENT_TIMESTAMP WHERE j.id = ?",
        ).use { update ->
            update.setObject(1, jobId)
            update.executeUpdate()
        }
    }

    fun <T> inTransaction(tenant: Connection, block: () -> T): T {
        val previous = tenant.autoCommit
        tenant.autoCommit = false
        try {
            val result = block()
            tenant.commit()
            return result
        } catch (failure: Throwable) {
            tenant.rollback()
            throw failure
        } finally {
            tenant.autoCommit = previous
        }
    }
}
