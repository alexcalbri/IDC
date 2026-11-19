package com.ideasdeveloper.idc.extensions.membresia_monday

import com.ideasdeveloper.idc.modules.membresia.ImportOutcome
import com.ideasdeveloper.idc.modules.membresia.ImportedMember
import com.ideasdeveloper.idc.modules.membresia.MembresiaImportApply
import com.ideasdeveloper.idc.modules.membresia.MembresiaRepository
import com.ideasdeveloper.idc.server.modules.ModuleJobContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

object MondayJobStatus {
    const val Reading = "reading"
    const val Preview = "preview"
    const val Applying = "applying"
    const val Done = "done"
    const val Failed = "failed"
    const val Cancelled = "cancelled"

    val terminal = setOf(Done, Failed, Cancelled)
}

/** Una importacion de un tablero. [applied] cuenta las filas ya procesadas (con cualquier resultado). */
data class MondayJob(
    val id: UUID,
    val boardId: String,
    val boardName: String,
    val status: String,
    val mapping: MondayMapping,
    val cursor: String?,
    val pageSize: Int,
    val pagesRead: Int,
    val itemsRead: Int,
    val attempts: Int,
    val restarts: Int,
    val summary: MondaySummaryDto?,
    val total: Int,
    val applied: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    val message: String,
    val createdBy: String,
    val createdAt: Instant,
) {
    val active: Boolean get() = status == MondayJobStatus.Reading || status == MondayJobStatus.Applying
}

/** Fila que no se pudo importar (omitida o con error); sin datos personales: el payload ya no existe al terminar. */
data class MondayRowProblem(val rowNo: Int, val outcome: String, val message: String)

/**
 * Cola y trabajo de la importacion. El modulo llama [runPending] cada minuto con un presupuesto de tiempo: la lectura del
 * tablero (hasta 20 000 elementos) va por paginas y se reanuda donde quedo, y la aplicacion procesa filas hasta que se acaba
 * el tiempo. Todo error se registra (sin datos personales) y el trabajo pasa a "failed" con un mensaje que se puede leer.
 */
object MembresiaMondayJobs {
    private const val Jobs = "ext_membresia_monday__jobs"
    private const val Rows = "ext_membresia_monday__rows"
    const val JobsTable = Jobs
    const val RowsTable = Rows
    private const val InsertChunk = 500
    private const val ApplyChunk = 50
    private const val UniqueViolation = "23505"
    const val RowFailedMessage = "No se pudo importar esta fila."
    const val RowUnreadableMessage = "No se pudo leer esta fila."
    private val log = LoggerFactory.getLogger(MembresiaMondayJobs::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Cola ----

    private const val BusyMessage = "Ya hay una importacion en curso; espera a que termine o cancelala."

    /** Pone en cola la lectura de un tablero. Solo hay una importacion activa a la vez (indice unico en la base). */
    fun queueRead(tenant: Connection, boardId: String, boardName: String, mapping: MondayMapping, author: String): UUID {
        if (!MembresiaMondayClient.isBoardId(boardId)) throw MembresiaMondayException("El id del tablero debe ser un numero.")
        if (recent(tenant, 20).any { it.active }) throw MembresiaMondayException(BusyMessage)
        val id = UUID.randomUUID()
        try {
            tenant.prepareStatement(
                "INSERT INTO $Jobs (id, board_id, board_name, status, mapping, created_by) VALUES (?, ?, ?, 'reading', CAST(? AS jsonb), ?)",
            ).use { insert ->
                insert.setObject(1, id)
                insert.setString(2, boardId)
                insert.setString(3, boardName.take(200))
                insert.setString(4, json.encodeToString(MondayMapping.serializer(), mapping))
                insert.setString(5, author.take(200))
                insert.executeUpdate()
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == UniqueViolation) throw MembresiaMondayException(BusyMessage)
            throw failure
        }
        return id
    }

    /** Pasa de "preview" a "applying"; false si el trabajo ya no esta en revision. */
    fun requestApply(tenant: Connection, id: UUID): Boolean = try {
        changeStatus(tenant, id, MondayJobStatus.Applying, "", from = listOf(MondayJobStatus.Preview))
    } catch (failure: SQLException) {
        if (failure.sqlState == UniqueViolation) throw MembresiaMondayException(BusyMessage)
        throw failure
    }

    /** Cancela un trabajo que aun no termino; false si ya habia terminado. */
    fun cancel(tenant: Connection, id: UUID): Boolean =
        changeStatus(tenant, id, MondayJobStatus.Cancelled, "Cancelada por el usuario.", from = listOf(MondayJobStatus.Reading, MondayJobStatus.Preview, MondayJobStatus.Applying))

    /** Cambia el estado; al llegar a uno final borra los datos personales guardados (payload y problemas con nombres). */
    private fun changeStatus(tenant: Connection, id: UUID, status: String, message: String, from: List<String>): Boolean {
        val changed = tenant.prepareStatement(
            "UPDATE $Jobs SET status = ?, message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ANY (?)",
        ).use { update ->
            update.setString(1, status)
            update.setString(2, message)
            update.setObject(3, id)
            update.setArray(4, tenant.createArrayOf("varchar", from.toTypedArray()))
            update.executeUpdate() > 0
        }
        if (changed && status in MondayJobStatus.terminal) wipePersonalData(tenant, id)
        return changed
    }

    /** Vacia el payload de las filas (quedan outcome y mensaje) y quita del resumen los problemas, que llevan nombres. */
    fun wipePersonalData(tenant: Connection, id: UUID) {
        tenant.prepareStatement("UPDATE $Rows SET payload = '{}'::jsonb WHERE job_id = ? AND payload <> '{}'::jsonb").use { update ->
            update.setObject(1, id)
            update.executeUpdate()
        }
        tenant.prepareStatement("UPDATE $Jobs SET summary = summary - 'problems' WHERE id = ? AND summary IS NOT NULL").use { update ->
            update.setObject(1, id)
            update.executeUpdate()
        }
    }

    // ---- Consultas ----

    private const val JobColumns =
        "id, board_id, board_name, status, mapping::text, cursor, page_size, pages_read, items_read, attempts, restarts, summary::text, " +
            "total, applied, created, updated, skipped, failed, message, created_by, created_at"

    private fun toJob(rows: ResultSet) = MondayJob(
        id = rows.getObject(1, UUID::class.java),
        boardId = rows.getString(2),
        boardName = rows.getString(3),
        status = rows.getString(4),
        mapping = runCatching { json.decodeFromString(MondayMapping.serializer(), rows.getString(5)) }.getOrDefault(MondayMapping()),
        cursor = rows.getString(6),
        pageSize = rows.getInt(7),
        pagesRead = rows.getInt(8),
        itemsRead = rows.getInt(9),
        attempts = rows.getInt(10),
        restarts = rows.getInt(11),
        summary = rows.getString(12)?.let(MondaySummaryDto::decode),
        total = rows.getInt(13),
        applied = rows.getInt(14),
        created = rows.getInt(15),
        updated = rows.getInt(16),
        skipped = rows.getInt(17),
        failed = rows.getInt(18),
        message = rows.getString(19).orEmpty(),
        createdBy = rows.getString(20).orEmpty(),
        createdAt = rows.getTimestamp(21).toInstant(),
    )

    fun find(tenant: Connection, id: UUID): MondayJob? =
        tenant.prepareStatement("SELECT $JobColumns FROM $Jobs WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { if (it.next()) toJob(it) else null }
        }

    fun recent(tenant: Connection, limit: Int): List<MondayJob> =
        tenant.prepareStatement("SELECT $JobColumns FROM $Jobs ORDER BY created_at DESC LIMIT ?").use { query ->
            query.setInt(1, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(toJob(rows)) } }
        }

    private fun withStatus(tenant: Connection, status: String): List<MondayJob> =
        tenant.prepareStatement("SELECT $JobColumns FROM $Jobs WHERE status = ? ORDER BY created_at").use { query ->
            query.setString(1, status)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(toJob(rows)) } }
        }

    /** Filas omitidas o con error, las primeras [limit]. */
    fun problems(tenant: Connection, jobId: UUID, limit: Int): List<MondayRowProblem> =
        tenant.prepareStatement(
            "SELECT row_no, outcome, message FROM $Rows WHERE job_id = ? AND outcome IN ('skipped', 'failed') ORDER BY row_no LIMIT ?",
        ).use { query ->
            query.setObject(1, jobId)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(MondayRowProblem(rows.getInt(1), rows.getString(2), rows.getString(3).orEmpty())) } }
        }

    private fun pending(tenant: Connection, jobId: UUID, limit: Int): List<Pair<Int, String>> =
        tenant.prepareStatement("SELECT row_no, payload::text FROM $Rows WHERE job_id = ? AND outcome IS NULL ORDER BY row_no LIMIT ?").use { query ->
            query.setObject(1, jobId)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1) to rows.getString(2)) } }
        }

    private fun statusOf(tenant: Connection, id: UUID): String? =
        tenant.prepareStatement("SELECT status FROM $Jobs WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    // ---- El trabajo periodico ----

    /**
     * Lo llama el modulo cada minuto. Con la extension inactiva no hace nada; si no, lee paginas del tablero o aplica filas
     * hasta que se acaba el [budget].
     */
    fun runPending(context: ModuleJobContext, budget: Duration) {
        val tenant = context.tenant
        if (!MembresiaMondaySettings.extensionEnabled(tenant)) return
        val deadline = System.nanoTime() + budget.toNanos()
        withStatus(tenant, MondayJobStatus.Reading).firstOrNull()?.let { job ->
            guarded(tenant, job) { readPages(context, job.id, deadline) }
        }
        if (System.nanoTime() < deadline) {
            withStatus(tenant, MondayJobStatus.Applying).firstOrNull()?.let { job ->
                guarded(tenant, job) { applyRows(context, job, deadline) }
            }
        }
    }

    // Todo error se registra (solo tipo y ids, sin datos personales) y deja el trabajo en "failed" con un mensaje legible.
    private fun guarded(tenant: Connection, job: MondayJob, block: () -> Unit) {
        try {
            block()
        } catch (failure: MembresiaMondayException) {
            log.warn("Importacion de monday {} fallo: {}", job.id, failure.message)
            fail(tenant, job.id, failure.message.orEmpty())
        } catch (failure: Exception) {
            log.error("Importacion de monday {} fallo ({})", job.id, failure.javaClass.simpleName, failure)
            fail(tenant, job.id, "No se pudo completar la importacion; revisa el registro del servidor.")
        }
    }

    private fun fail(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            changeStatus(tenant, id, MondayJobStatus.Failed, message.take(500), from = listOf(MondayJobStatus.Reading, MondayJobStatus.Applying))
        }.onFailure { log.error("No se pudo marcar como fallida la importacion de monday {}", id, it) }
    }

    // ---- Lectura paginada y reanudable ----

    private fun readPages(context: ModuleJobContext, jobId: UUID, deadline: Long) {
        val tenant = context.tenant
        val token = MembresiaMondaySettings.loadToken(context.access.secrets, tenant)
            ?: throw MembresiaMondayException("Falta el token de monday o no se puede leer; guardalo de nuevo.")
        while (System.nanoTime() < deadline) {
            // Se vuelve a leer el estado en cada pagina: asi una cancelacion corta la lectura.
            val job = find(tenant, jobId)?.takeIf { it.status == MondayJobStatus.Reading } ?: return
            // Ya se guardo la ultima pagina (cursor nulo) pero el cierre no llego a correr (reinicio): solo falta cerrarla.
            if (job.pagesRead > 0 && job.cursor.isNullOrEmpty()) {
                completeRead(context, job.id)
                return
            }
            val page = try {
                MembresiaMondayClient.itemsPage(token, job.boardId, job.cursor, job.pageSize)
            } catch (failure: MembresiaMondayException) {
                recoverRead(tenant, job, failure)
                return
            }
            if (MondayReading.repeatedCursor(job.cursor, page.cursor)) {
                throw MembresiaMondayException("monday devolvio el mismo cursor dos veces; la lectura se detuvo para no repetirse.")
            }
            if (job.pagesRead + 1 > MondayReading.MaxPages) {
                throw MembresiaMondayException("El tablero necesita mas de ${MondayReading.MaxPages} paginas; divide el tablero antes de importarlo.")
            }
            if (job.itemsRead + page.items.size > MembresiaMondayClient.MaxItems) {
                throw MembresiaMondayException("El tablero tiene mas de ${MembresiaMondayClient.MaxItems} elementos; divide el tablero antes de importarlo.")
            }
            val members = page.items.map { MembresiaMondayMapping.toMember(it, job.mapping) }
            if (!storePage(tenant, job, members, page.cursor)) return
            if (page.cursor.isEmpty()) {
                completeRead(context, job.id)
                return
            }
        }
    }

    // Guarda las filas de la pagina (row_no continuo) y el cursor en una sola transaccion; false si el trabajo ya no esta leyendo.
    private fun storePage(tenant: Connection, job: MondayJob, members: List<ImportedMember>, cursor: String): Boolean = inTransaction(tenant) {
        val moved = tenant.prepareStatement(
            "UPDATE $Jobs SET cursor = ?, pages_read = pages_read + 1, items_read = items_read + ?, attempts = 0, message = '', updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = ? AND status = 'reading'",
        ).use { update ->
            update.setString(1, cursor.ifEmpty { null })
            update.setInt(2, members.size)
            update.setObject(3, job.id)
            update.executeUpdate()
        }
        if (moved > 0) insertRows(tenant, job.id, job.itemsRead, members)
        moved > 0
    }

    private fun insertRows(tenant: Connection, jobId: UUID, offset: Int, members: List<ImportedMember>) {
        if (members.isEmpty()) return
        tenant.prepareStatement("INSERT INTO $Rows (job_id, row_no, payload) VALUES (?, ?, CAST(? AS jsonb))").use { insert ->
            members.forEachIndexed { index, member ->
                insert.setObject(1, jobId)
                insert.setInt(2, offset + index + 1)
                insert.setString(3, MondayRowDto.encode(member))
                insert.addBatch()
                if ((index + 1) % InsertChunk == 0) insert.executeBatch()
            }
            insert.executeBatch()
        }
    }

    // Fallo de monday al leer: transitorio = reintentar luego; cursor vencido = releer desde cero (una vez); lo demas = failed.
    private fun recoverRead(tenant: Connection, job: MondayJob, failure: MembresiaMondayException) {
        when (val step = MondayReading.recover(failure.kind, failure.message.orEmpty(), job.attempts, job.pageSize, job.restarts)) {
            is MondayReading.Step.Fail -> throw MembresiaMondayException(step.message)
            is MondayReading.Step.Retry -> {
                log.warn("Importacion de monday {}: fallo transitorio ({}), intento {}", job.id, failure.kind, step.attempts)
                tenant.prepareStatement(
                    "UPDATE $Jobs SET attempts = ?, page_size = ?, message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'reading'",
                ).use { update ->
                    update.setInt(1, step.attempts)
                    update.setInt(2, step.pageSize)
                    update.setString(3, "monday limito la lectura; se reintenta (intento ${step.attempts} de ${MondayReading.MaxAttempts}).")
                    update.setObject(4, job.id)
                    update.executeUpdate()
                }
            }
            MondayReading.Step.Restart -> {
                log.warn("Importacion de monday {}: el cursor vencio, se relee desde cero", job.id)
                inTransaction(tenant) {
                    tenant.prepareStatement("DELETE FROM $Rows WHERE job_id = ?").use { delete ->
                        delete.setObject(1, job.id)
                        delete.executeUpdate()
                    }
                    tenant.prepareStatement(
                        "UPDATE $Jobs SET cursor = NULL, pages_read = 0, items_read = 0, attempts = 0, restarts = restarts + 1, " +
                            "message = 'El cursor de monday vencio; se vuelve a leer el tablero desde el inicio.', updated_at = CURRENT_TIMESTAMP " +
                            "WHERE id = ? AND status = 'reading'",
                    ).use { update ->
                        update.setObject(1, job.id)
                        update.executeUpdate()
                    }
                }
            }
        }
    }

    // Termina la lectura: el resumen se calcula una sola vez y queda guardado; el trabajo pasa a "preview".
    private fun completeRead(context: ModuleJobContext, jobId: UUID) {
        val tenant = context.tenant
        val job = find(tenant, jobId)?.takeIf { it.status == MondayJobStatus.Reading } ?: return
        if (job.itemsRead == 0) throw MembresiaMondayException("El tablero no tiene elementos.")
        var unreadable = 0
        val members = ArrayList<ImportedMember>(job.itemsRead)
        tenant.prepareStatement("SELECT payload::text FROM $Rows WHERE job_id = ? ORDER BY row_no").use { query ->
            query.setObject(1, jobId)
            query.executeQuery().use { rows ->
                while (rows.next()) runCatching { MondayRowDto.decode(rows.getString(1)) }.onSuccess { members += it }.onFailure { unreadable++ }
            }
        }
        val summary = MondaySummaryDto.from(
            MembresiaImportApply.summary(members, MembresiaRepository.plans(tenant, true)),
            MondayReading.problemCodes(members, unreadable), unreadable,
        )
        tenant.prepareStatement(
            "UPDATE $Jobs SET status = 'preview', total = ?, summary = CAST(? AS jsonb), message = '', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'reading'",
        ).use { update ->
            update.setInt(1, job.itemsRead)
            update.setString(2, MondaySummaryDto.encode(summary))
            update.setObject(3, jobId)
            update.executeUpdate()
        }
    }

    // ---- Aplicacion ----

    private fun applyRows(context: ModuleJobContext, job: MondayJob, deadline: Long) {
        val tenant = context.tenant
        val summary = job.summary ?: throw MembresiaMondayException("La importacion no tiene resumen; vuelve a leer el tablero.")
        // Los planes y campos de Clientes que faltan se crean con lo que dijo el resumen (idempotente: lo que ya existe se reutiliza).
        val plans = MembresiaImportApply.ensurePlans(tenant, summary.newPlans.map { it.toProposal() })
        val fields = MembresiaImportApply.ensureCustomerFields(tenant, summary.customerFields)
        if (fields.skipped.isNotEmpty() && job.message.isBlank()) {
            val note = fields.skipped.joinToString(" ") { "Campo de Clientes $it no compatible: no se importa." }.take(500)
            tenant.prepareStatement("UPDATE $Jobs SET message = ? WHERE id = ? AND status = 'applying'").use { update ->
                update.setString(1, note)
                update.setObject(2, job.id)
                update.executeUpdate()
            }
        }
        val today = LocalDate.now()
        try {
            while (System.nanoTime() < deadline) {
                val batch = pending(tenant, job.id, ApplyChunk)
                if (batch.isEmpty()) {
                    finish(tenant, job.id)
                    return
                }
                for ((rowNo, payload) in batch) {
                    if (System.nanoTime() >= deadline) return
                    // Una cancelacion entre filas corta la aplicacion; lo ya importado se conserva.
                    if (statusOf(tenant, job.id) != MondayJobStatus.Applying) return
                    val (outcome, message) = try {
                        val member = MondayRowDto.decode(payload)
                        when (val result = MembresiaImportApply.apply(context.access, tenant, member, plans, fields.keys, job.createdBy, today)) {
                            is ImportOutcome.Created -> "created" to ""
                            is ImportOutcome.Updated -> "updated" to ""
                            is ImportOutcome.Skipped -> "skipped" to result.reason
                        }
                    } catch (failure: kotlinx.serialization.SerializationException) {
                        log.warn("Importacion de monday {}: la fila {} no se pudo leer ({})", job.id, rowNo, failure.javaClass.simpleName)
                        "failed" to RowUnreadableMessage
                    } catch (failure: Exception) {
                        // Solo el tipo y el numero de fila: el mensaje de la excepcion podria llevar datos personales.
                        log.warn("Importacion de monday {}: la fila {} fallo ({})", job.id, rowNo, failure.javaClass.simpleName)
                        "failed" to RowFailedMessage
                    }
                    saveRow(tenant, job.id, rowNo, outcome, message)
                }
            }
        } finally {
            recount(tenant, job.id)
        }
    }

    private fun saveRow(tenant: Connection, jobId: UUID, rowNo: Int, outcome: String, message: String) {
        tenant.prepareStatement("UPDATE $Rows SET outcome = ?, message = ? WHERE job_id = ? AND row_no = ? AND outcome IS NULL").use { update ->
            update.setString(1, outcome)
            update.setString(2, message.take(500))
            update.setObject(3, jobId)
            update.setInt(4, rowNo)
            update.executeUpdate()
        }
    }

    private fun recount(tenant: Connection, jobId: UUID) {
        fun count(outcome: String?) =
            "(SELECT count(*) FROM $Rows r WHERE r.job_id = j.id AND " + (if (outcome == null) "r.outcome IS NOT NULL)" else "r.outcome = '$outcome')")
        tenant.prepareStatement(
            "UPDATE $Jobs j SET applied = ${count(null)}, created = ${count("created")}, updated = ${count("updated")}, " +
                "skipped = ${count("skipped")}, failed = ${count("failed")}, updated_at = CURRENT_TIMESTAMP WHERE j.id = ?",
        ).use { update ->
            update.setObject(1, jobId)
            update.executeUpdate()
        }
    }

    private fun finish(tenant: Connection, jobId: UUID) {
        recount(tenant, jobId)
        val job = find(tenant, jobId) ?: return
        val message = "Importacion terminada: ${job.created} creadas, ${job.updated} actualizadas, ${job.skipped} omitidas y ${job.failed} con error." +
            job.message.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        changeStatus(tenant, jobId, MondayJobStatus.Done, message, from = listOf(MondayJobStatus.Applying))
    }

    private fun <T> inTransaction(tenant: Connection, block: () -> T): T {
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
