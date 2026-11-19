package com.ideasdeveloper.idc.extensions.tareas_monday

import com.ideasdeveloper.idc.modules.tareas.ImportOutcome
import com.ideasdeveloper.idc.modules.tareas.TareasImportApply
import com.ideasdeveloper.idc.modules.tareas.TareasImportJobContext
import com.ideasdeveloper.idc.modules.tareas.TareasRepository
import com.ideasdeveloper.idc.modules.tareas.TareasValidationException
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.util.UUID

/**
 * Avanza las importaciones: lee un tablero pagina por pagina (cursor guardado en el trabajo, hasta 20 000 elementos) y
 * aplica 100 filas por corrida, con sus archivos. Lo llama el modulo Tareas desde su trabajo "import-runner" con un
 * presupuesto de tiempo estricto: no se empieza una llamada o descarga con menos de 5 s por delante y cada una se corta
 * a lo que queda. Todo paso es corto e idempotente: si se repite no duplica nada. Los fallos pasajeros de monday
 * reintentan (hasta [MaxRetries] seguidos); los permanentes y todo error de una fila quedan en el trabajo o en la fila y
 * en el registro del servidor, nunca se pierden en silencio.
 */
object TareasMondayRunner {
    const val BatchSize = 100
    const val MaxRetries = 5
    const val MaxCursorLength = 2000
    const val MaxPages = TareasMondayClient.MaxItems / TareasMondayClient.PageSize * 3
    private const val CancelCheckEvery = 20
    private const val SummaryBatch = 500
    private val log = LoggerFactory.getLogger(TareasMondayRunner::class.java)

    /** Clave con la que el modulo reconoce el tablero de Tareas creado desde un tablero de monday. */
    const val BoardKeyPrefix = "monday:board:"

    /** Devuelve las filas leidas o aplicadas en esta corrida. */
    fun run(context: TareasImportJobContext, budget: Duration): Int {
        val tenant = context.tenant
        if (!TareasMondaySettings.extensionEnabled(tenant)) return 0
        val deadline = System.nanoTime() + budget.toNanos()
        var processed = 0
        TareasMondayJobs.withStatus(tenant, MondayJobStatus.Reading).forEach { job ->
            if (TareasMondayClient.hasTime(deadline)) processed += guarded(tenant, job) { readPages(context, job, deadline) }
        }
        TareasMondayJobs.withStatus(tenant, MondayJobStatus.Applying).firstOrNull()?.let { job ->
            if (TareasMondayClient.hasTime(deadline)) processed += guarded(tenant, job) { applyBatch(context, job, deadline) }
        }
        return processed
    }

    // Un fallo pasajero de monday reintenta; el resto deja el trabajo en "failed" con un mensaje legible. Todo se registra.
    private fun guarded(tenant: Connection, job: MondayJob, block: () -> Int): Int {
        try {
            return block()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: TareasMondayException) {
            if (failure.transient) {
                log.warn("Importacion de monday {}: error temporal: {}", job.id, failure.message)
                retry(tenant, job.id, failure.message.orEmpty())
            } else {
                log.warn("Importacion de monday {} fallo: {}", job.id, failure.message)
                fail(tenant, job.id, failure.message.orEmpty())
            }
        } catch (failure: TareasValidationException) {
            log.warn("Importacion de monday {} fallo: {}", job.id, failure.message)
            fail(tenant, job.id, failure.message.orEmpty())
        } catch (failure: Exception) {
            log.error("Importacion de monday {} fallo", job.id, failure)
            fail(tenant, job.id, "No se pudo completar la importacion; revisa el registro del servidor.")
        }
        return 0
    }

    private fun retry(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            val attempts = TareasMondayJobs.bumpRetries(tenant, id, "Error temporal de monday, se reintenta (intento de $MaxRetries): $message")
            if (attempts >= MaxRetries) fail(tenant, id, "monday no respondio despues de $MaxRetries intentos: $message")
        }.onFailure { log.error("No se pudo registrar el reintento de la importacion de monday {}", id, it) }
    }

    private fun fail(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            TareasMondayJobs.changeStatus(tenant, id, MondayJobStatus.Failed, message, from = listOf(MondayJobStatus.Reading, MondayJobStatus.Applying))
        }.onFailure { log.error("No se pudo marcar como fallida la importacion de monday {}", id, it) }
    }

    // ---- Lectura ----

    private fun readPages(context: TareasImportJobContext, job: MondayJob, deadline: Long): Int {
        val tenant = context.tenant
        val token = TareasMondaySettings.loadToken(context.access.secrets, tenant)
            ?: throw TareasMondayException("Falta el token de monday o no se puede leer; guardalo de nuevo.")
        var cursor = job.cursor
        var pages = job.pages
        var read = 0
        // Cursor vacio: ya se leyo todo y solo falta el resumen de la vista previa.
        if (cursor != "") {
            do {
                if (TareasMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Reading) return read
                if (pages >= MaxPages) throw TareasMondayException("La lectura del tablero no termina (demasiadas paginas); divide el tablero antes de importarlo.")
                val page = TareasMondayClient.itemsPage(token, job.boardId, cursor, deadline)
                if (page.cursor.length > MaxCursorLength) throw TareasMondayException("monday devolvio un cursor invalido; no se puede seguir leyendo el tablero.")
                if (page.items.isEmpty() && page.cursor.isNotEmpty()) throw TareasMondayException("monday devolvio una pagina vacia sin terminar la lectura del tablero.")
                if (job.total + read + page.items.size > TareasMondayClient.MaxItems) {
                    throw TareasMondayException("El tablero tiene mas de ${TareasMondayClient.MaxItems} elementos; divide el tablero antes de importarlo.")
                }
                // Las personas de la pagina se piden a monday una sola vez por trabajo (users(ids) trae el correo).
                val known = TareasMondayJobs.people(tenant, job.id)
                val missing = page.items.flatMap { TareasMondayMapping.personIds(it.cells) }.toSet() - known.keys
                val fetched = if (missing.isEmpty()) emptyList() else TareasMondayClient.users(token, missing, deadline)
                TareasMondayJobs.savePeople(tenant, job.id, fetched)
                val people = known + fetched.associateBy { it.id }
                val rows = page.items.map { item ->
                    val converted = TareasMondayMapping.toTask(item, job.mapping, people)
                    Triple(item.id, MondayRowDto.encode(converted.task), TareasMondayMapping.encodeFiles(converted.files))
                }
                if (!TareasMondayJobs.savePage(tenant, job.id, rows, page.cursor)) return read
                read += page.items.size
                pages++
                cursor = page.cursor
            } while (cursor != "" && TareasMondayClient.hasTime(deadline))
        }
        if (cursor == "") finishReading(tenant, job)
        return read
    }

    /** Calcula el resumen de la vista previa una sola vez, por lotes, y pasa el trabajo a revision. */
    private fun finishReading(tenant: Connection, job: MondayJob) {
        val users = TareasRepository.users(tenant)
        val parts = mutableListOf<MondaySummaryDto>()
        var after = 0
        while (true) {
            val last = TareasMondayJobs.lastRowOfBatch(tenant, job.id, after, SummaryBatch) ?: break
            val tasks = TareasMondayJobs.taskBatch(tenant, job.id, after, SummaryBatch).map { it.second }
            parts += MondaySummaryDto.of(TareasImportApply.summary(tasks, users, 0))
            after = last
        }
        if (parts.isEmpty()) throw TareasMondayException("El tablero no tiene elementos.")
        val summary = MondaySummaryDto.merge(parts).copy(files = TareasMondayJobs.fileCount(tenant, job.id))
        TareasMondayJobs.moveToPreview(tenant, job.id, summary)
    }

    // ---- Aplicacion ----

    private fun applyBatch(context: TareasImportJobContext, job: MondayJob, deadline: Long): Int {
        val tenant = context.tenant
        val users = TareasRepository.users(tenant)
        // Idempotente: el tablero se busca por su clave (o es el elegido), asi cada corrida llega al mismo.
        val boardId = TareasImportApply.ensureBoard(
            tenant, BoardKeyPrefix + job.boardId, job.boardName.ifBlank { "Monday ${job.boardId}" }, job.targetBoardId, job.createdBy,
        )
        val batch = TareasMondayJobs.pending(tenant, job.id, BatchSize)
        var processed = 0
        var token: String? = null
        for ((index, row) in batch.withIndex()) {
            if (index % CancelCheckEvery == 0 && TareasMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Applying) break
            if (!TareasMondayClient.hasTime(deadline)) break
            val files = TareasMondayMapping.decodeFiles(row.files)
            var taskId = row.taskId
            // Fila sin aplicar: se aplica y se guarda su resultado y su tarea al instante, antes de bajar archivos.
            if (row.outcome == null) {
                var outcome: String
                var message = ""
                taskId = null
                try {
                    val task = MondayRowDto.decode(row.payload)
                    when (val result = TareasImportApply.apply(tenant, "monday", boardId, task, users, job.createdBy, context.today)) {
                        is ImportOutcome.Created -> { outcome = "created"; taskId = result.taskId }
                        is ImportOutcome.Updated -> { outcome = "updated"; taskId = result.taskId }
                        is ImportOutcome.Skipped -> { outcome = "skipped"; message = result.reason }
                    }
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                } catch (failure: Exception) {
                    log.warn("Importacion de monday {}: la fila {} fallo", job.id, row.rowNo, failure)
                    outcome = "failed"
                    message = "No se pudo importar esta fila: " + (failure as? TareasValidationException)?.message.orEmpty().ifEmpty { failure.javaClass.simpleName }.take(200)
                }
                TareasMondayJobs.saveApplied(tenant, job.id, row.rowNo, outcome, message, taskId, taskId != null && files.isNotEmpty())
            }
            if (taskId != null && files.isNotEmpty()) {
                if (token == null) token = TareasMondaySettings.loadToken(context.access.secrets, tenant).orEmpty()
                val result = importFiles(context, job, token, taskId, files, deadline, fullBudget = processed == 0)
                // Los archivos ya guardados se cuentan siempre (la proxima vez se saltan); los fallidos solo al cerrar la fila.
                TareasMondayJobs.addFiles(tenant, job.id, result.done, if (result.complete) result.failed else 0)
                if (!result.complete) {
                    // Se acabo el tiempo con archivos por bajar: la fila sigue pendiente y la proxima corrida sigue con ellos.
                    processed++
                    break
                }
                val note = if (result.problems.isEmpty()) "" else "Archivos sin importar: " + result.problems.joinToString("; ")
                TareasMondayJobs.finishFiles(tenant, job.id, row.rowNo, note)
            }
            processed++
        }
        if (processed > 0) TareasMondayJobs.resetRetries(tenant, job.id)
        TareasMondayJobs.recount(tenant, job.id)
        if (TareasMondayJobs.statusOf(tenant, job.id) == MondayJobStatus.Applying && TareasMondayJobs.pending(tenant, job.id, 1).isEmpty()) finish(tenant, job.id)
        return processed
    }

    private class FilesResult(val done: Int, val failed: Int, val problems: List<String>, val complete: Boolean)

    /**
     * Baja los archivos de una fila a la tarea. Los que ya son adjuntos (misma clave de asset) se saltan antes de
     * descargar y no se cuentan. Si se acaba el tiempo devuelve [FilesResult.complete] en false; con la corrida entera
     * por delante ([fullBudget]) un primer archivo que no cabe cuenta como fallido, para no reintentarlo sin fin.
     */
    private fun importFiles(
        context: TareasImportJobContext,
        job: MondayJob,
        token: String,
        taskId: UUID,
        files: List<MondayFile>,
        deadline: Long,
        fullBudget: Boolean,
    ): FilesResult {
        val problems = mutableListOf<String>()
        var done = 0
        var failed = 0
        if (token.isBlank()) return FilesResult(0, files.size, listOf("falta el token de monday"), true)
        val existing = TareasMondayJobs.attachedKeys(context.tenant, taskId, files.map { TareasMondayMapping.AssetPrefix + it.asset })
        val todo = files.filter { TareasMondayMapping.AssetPrefix + it.asset !in existing }
        if (todo.isEmpty()) return FilesResult(0, 0, emptyList(), true)
        val links = try {
            TareasMondayClient.assetLinks(token, todo.map { it.asset }, deadline)
        } catch (failure: TareasMondayException) {
            // Un fallo pasajero sube al trabajo (reintento); uno permanente deja los archivos como fallidos.
            if (failure.transient) throw failure
            log.warn("Importacion de monday {}: no se pidieron los archivos: {}", job.id, failure.message)
            return FilesResult(0, todo.size, listOf("no se pidieron los archivos a monday (${failure.message})"), true)
        }
        for ((index, file) in todo.withIndex()) {
            val firstWithFullTime = fullBudget && index == 0
            if (!TareasMondayClient.hasTime(deadline) && !firstWithFullTime) return FilesResult(done, failed, problems, false)
            val name = file.name.ifBlank { "archivo-${file.asset}" }
            val link = links[file.asset]
            if (link == null) {
                failed++
                problems += "$name: monday no lo devolvio"
                continue
            }
            if (link.size != null && link.size > TareasMondayClient.MaxFileBytes) {
                failed++
                problems += "$name: supera ${TareasMondayClient.MaxFileBytes / (1024 * 1024)} MB"
                continue
            }
            try {
                TareasMondayClient.download(link.url, deadline) { contentType, body ->
                    val stored = TareasImportApply.attachFile(
                        context.tenant, context.files, taskId, TareasMondayMapping.AssetPrefix + file.asset, name, contentType, body, job.createdBy,
                    )
                    if (stored) done++
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: Exception) {
                // Cortado por el tiempo de la corrida: se retoma despues (salvo el primero con todo el tiempo, que no cabe).
                if (!TareasMondayClient.hasTime(deadline) && !firstWithFullTime) return FilesResult(done, failed, problems, false)
                failed++
                val reason = (failure as? TareasMondayException ?: failure as? TareasValidationException)?.message ?: "no se pudo descargar"
                log.warn("Importacion de monday {}: un archivo fallo: {}", job.id, reason)
                problems += "$name: $reason"
            }
        }
        return FilesResult(done, failed, problems, true)
    }

    private fun finish(tenant: Connection, jobId: UUID) {
        val job = TareasMondayJobs.find(tenant, jobId) ?: return
        val message = "Importacion terminada: ${job.created} creadas, ${job.updated} actualizadas, ${job.skipped} omitidas y ${job.failed} con error. " +
            "Archivos: ${job.filesDone} importados y ${job.filesFailed} con error."
        TareasMondayJobs.changeStatus(tenant, jobId, MondayJobStatus.Done, message, from = listOf(MondayJobStatus.Applying))
    }
}
