package com.ideasdeveloper.idc.extensions.mantenimiento_monday

import com.ideasdeveloper.idc.modules.mantenimiento.ImportOutcome
import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoImportApply
import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoImportJobContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

/**
 * Avanza las importaciones: lee un tablero pagina por pagina (cursor guardado en el trabajo, hasta 20 000 elementos) y
 * aplica 100 filas por corrida, con sus archivos (solo las ordenes llevan archivos). Lo llama el modulo Mantenimiento
 * desde su trabajo "import-runner" con un presupuesto de tiempo estricto: no se empieza una llamada o descarga con
 * menos de 5 s por delante y cada una se corta a lo que queda. Todo paso es corto e idempotente: si se repite no
 * duplica nada (el modulo reconoce cada fila por su clave monday:<id>). Los fallos pasajeros de monday reintentan
 * (hasta [MaxRetries] seguidos); los permanentes y todo error de una fila quedan en el trabajo o en la fila y en el
 * registro del servidor, nunca se pierden en silencio.
 */
object MantenimientoMondayRunner {
    const val BatchSize = 100
    const val MaxRetries = 5
    const val MaxCursorLength = 2000
    const val MaxPages = MantenimientoMondayClient.MaxItems / MantenimientoMondayClient.PageSize * 3
    private const val CancelCheckEvery = 20
    private const val SummaryBatch = 500
    private val log = LoggerFactory.getLogger(MantenimientoMondayRunner::class.java)

    /** Devuelve las filas leidas o aplicadas en esta corrida. */
    fun run(context: MantenimientoImportJobContext, budget: Duration): Int {
        val tenant = context.tenant
        if (!MantenimientoMondaySettings.extensionEnabled(tenant)) return 0
        val deadline = System.nanoTime() + budget.toNanos()
        var processed = 0
        MantenimientoMondayJobs.withStatus(tenant, MondayJobStatus.Reading).forEach { job ->
            if (MantenimientoMondayClient.hasTime(deadline)) processed += guarded(tenant, job) { readPages(context, job, deadline) }
        }
        MantenimientoMondayJobs.withStatus(tenant, MondayJobStatus.Applying).firstOrNull()?.let { job ->
            if (MantenimientoMondayClient.hasTime(deadline)) processed += guarded(tenant, job) { applyBatch(context, job, deadline) }
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
        } catch (failure: MantenimientoMondayException) {
            if (failure.transient) {
                log.warn("Importacion de monday {}: error temporal: {}", job.id, failure.message)
                retry(tenant, job.id, failure.message.orEmpty())
            } else {
                log.warn("Importacion de monday {} fallo: {}", job.id, failure.message)
                fail(tenant, job.id, failure.message.orEmpty())
            }
        } catch (failure: SQLException) {
            // Base de datos no disponible o bloqueo: pasajero, se reintenta (hasta MaxRetries).
            log.warn("Importacion de monday {}: error temporal de base de datos: {}", job.id, failure.javaClass.simpleName)
            retry(tenant, job.id, "error temporal de la base de datos")
        } catch (failure: Exception) {
            log.error("Importacion de monday {} fallo", job.id, failure)
            fail(tenant, job.id, "No se pudo completar la importacion; revisa el registro del servidor.")
        }
        return 0
    }

    private fun retry(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            val attempts = MantenimientoMondayJobs.bumpRetries(tenant, id, "Error temporal de monday, se reintenta (intento de $MaxRetries): $message")
            if (attempts >= MaxRetries) fail(tenant, id, "monday no respondio despues de $MaxRetries intentos: $message")
        }.onFailure { log.error("No se pudo registrar el reintento de la importacion de monday {}", id, it) }
    }

    private fun fail(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            MantenimientoMondayJobs.changeStatus(tenant, id, MondayJobStatus.Failed, message, from = listOf(MondayJobStatus.Reading, MondayJobStatus.Applying))
        }.onFailure { log.error("No se pudo marcar como fallida la importacion de monday {}", id, it) }
    }

    // ---- Lectura ----

    private fun readPages(context: MantenimientoImportJobContext, job: MondayJob, deadline: Long): Int {
        val tenant = context.tenant
        val token = MantenimientoMondaySettings.loadToken(context.access.secrets, tenant)
            ?: throw MantenimientoMondayException("Falta el token de monday o no se puede leer; guardalo de nuevo.")
        var cursor = job.cursor
        var pages = job.pages
        var read = 0
        val kind = MondayDestination.kindOf(job.defaultKind)
        // Cursor vacio: ya se leyo todo y solo falta el resumen de la vista previa.
        if (cursor != "") {
            do {
                if (MantenimientoMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Reading) return read
                if (pages >= MaxPages) throw MantenimientoMondayException("La lectura del tablero no termina (demasiadas paginas); divide el tablero antes de importarlo.")
                val page = MantenimientoMondayClient.itemsPage(token, job.boardId, cursor, deadline)
                if (page.cursor.length > MaxCursorLength) throw MantenimientoMondayException("monday devolvio un cursor invalido; no se puede seguir leyendo el tablero.")
                if (page.items.isEmpty() && page.cursor.isNotEmpty()) throw MantenimientoMondayException("monday devolvio una pagina vacia sin terminar la lectura del tablero.")
                if (job.total + read + page.items.size > MantenimientoMondayClient.MaxItems) {
                    throw MantenimientoMondayException("El tablero tiene mas de ${MantenimientoMondayClient.MaxItems} elementos; divide el tablero antes de importarlo.")
                }
                // Las personas de la pagina se piden a monday una sola vez por trabajo (users(ids) trae el correo).
                val known = MantenimientoMondayJobs.people(tenant, job.id)
                val missing = page.items.flatMap { MantenimientoMondayMapping.personIds(it.cells) }.toSet() - known.keys
                val fetched = if (missing.isEmpty()) emptyList() else MantenimientoMondayClient.users(token, missing, deadline)
                MantenimientoMondayJobs.savePeople(tenant, job.id, fetched)
                val people = known + fetched.associateBy { it.id }
                val rows = page.items.map { item ->
                    val converted = if (job.target == MondayDestination.Plans) {
                        MantenimientoMondayMapping.toPlan(item, job.mapping, people)
                    } else {
                        MantenimientoMondayMapping.toOrder(item, job.mapping, kind, people)
                    }
                    Triple(item.id, MondayRowDto.encode(converted), MantenimientoMondayMapping.encodeFiles(converted.files))
                }
                if (!MantenimientoMondayJobs.savePage(tenant, job.id, rows, page.cursor)) return read
                read += page.items.size
                pages++
                cursor = page.cursor
            } while (cursor != "" && MantenimientoMondayClient.hasTime(deadline))
        }
        if (cursor == "") finishReading(tenant, job)
        return read
    }

    /** Calcula el resumen de la vista previa una sola vez, por lotes, y pasa el trabajo a revision. */
    private fun finishReading(tenant: Connection, job: MondayJob) {
        val users = MantenimientoImportApply.assignableUsers(tenant)
        val parts = mutableListOf<MondaySummaryDto>()
        var after = 0
        while (true) {
            val last = MantenimientoMondayJobs.lastRowOfBatch(tenant, job.id, after, SummaryBatch) ?: break
            val rows = MantenimientoMondayJobs.rowBatch(tenant, job.id, after, SummaryBatch).map { it.row }
            val extra = rows.flatMap { row -> row.problems.map { "${row.title.ifBlank { row.key }}: $it" } }
            val summary = if (job.target == MondayDestination.Plans) {
                MantenimientoImportApply.summarizePlans(rows.map { it.toPlan() }, users, 0)
            } else {
                MantenimientoImportApply.summarizeOrders(rows.map { it.toOrder() }, users, 0)
            }
            parts += MondaySummaryDto.of(summary, extra)
            after = last
        }
        if (parts.isEmpty()) throw MantenimientoMondayException("El tablero no tiene elementos.")
        val summary = MondaySummaryDto.merge(parts).copy(files = MantenimientoMondayJobs.fileCount(tenant, job.id))
        MantenimientoMondayJobs.moveToPreview(tenant, job.id, summary)
    }

    // ---- Aplicacion ----

    private fun applyBatch(context: MantenimientoImportJobContext, job: MondayJob, deadline: Long): Int {
        val tenant = context.tenant
        val users = MantenimientoImportApply.assignableUsers(tenant)
        val batch = MantenimientoMondayJobs.pending(tenant, job.id, BatchSize)
        var processed = 0
        var token: String? = null
        for ((index, row) in batch.withIndex()) {
            if (index % CancelCheckEvery == 0 && MantenimientoMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Applying) break
            if (!MantenimientoMondayClient.hasTime(deadline)) break
            val files = MantenimientoMondayMapping.decodeFiles(row.files)
            var entityId = row.entityId
            // Fila sin aplicar: se aplica y se guarda su resultado y su id al instante, antes de bajar archivos.
            if (row.outcome == null) {
                var outcome: String
                var message = ""
                entityId = null
                var isOrder = false
                try {
                    val dto = MondayRowDto.decode(row.payload)
                    isOrder = dto.target == MondayDestination.Orders
                    val result = if (isOrder) {
                        MantenimientoImportApply.applyOrder(tenant, dto.toOrder(), users, job.createdBy, context.today)
                    } else {
                        MantenimientoImportApply.applyPlan(tenant, dto.toPlan(), users, job.createdBy, context.today)
                    }
                    when (result) {
                        is ImportOutcome.Created -> { outcome = "created"; entityId = result.id }
                        is ImportOutcome.Updated -> { outcome = "updated"; entityId = result.id }
                        is ImportOutcome.Skipped -> { outcome = "skipped"; message = result.reason }
                    }
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                } catch (failure: SQLException) {
                    // Un fallo de datos (clase 22 o 23) es de la fila; cualquier otro (conexion, bloqueo, tiempo) es pasajero y reintenta.
                    val state = failure.sqlState.orEmpty()
                    if (!state.startsWith("22") && !state.startsWith("23")) throw failure
                    log.warn("Importacion de monday {}: la fila {} fallo", job.id, row.rowNo, failure)
                    outcome = "failed"
                    message = "No se pudo importar esta fila: datos no validos (${failure.javaClass.simpleName})."
                    entityId = null
                } catch (failure: Exception) {
                    log.warn("Importacion de monday {}: la fila {} fallo", job.id, row.rowNo, failure)
                    outcome = "failed"
                    // Solo el mensaje de una validacion del modulo es apto para mostrar; de lo demas, el tipo de fallo.
                    val shown = failure.message.takeIf { failure.javaClass.simpleName.endsWith("ValidationException") }.orEmpty()
                    message = "No se pudo importar esta fila: " + shown.ifEmpty { failure.javaClass.simpleName }.take(200)
                    entityId = null
                }
                MantenimientoMondayJobs.saveApplied(tenant, job.id, row.rowNo, outcome, message, entityId, isOrder && entityId != null && files.isNotEmpty())
            }
            if (entityId != null && files.isNotEmpty() && job.target == MondayDestination.Orders) {
                if (token == null) token = MantenimientoMondaySettings.loadToken(context.access.secrets, tenant).orEmpty()
                val result = importFiles(context, job, token, entityId, files, deadline, fullBudget = processed == 0)
                // Los archivos ya guardados se cuentan siempre (la proxima vez se saltan); los fallidos solo al cerrar la fila.
                MantenimientoMondayJobs.addFiles(tenant, job.id, result.done, if (result.complete) result.failed else 0)
                if (!result.complete) {
                    // Se acabo el tiempo con archivos por bajar: la fila sigue pendiente y la proxima corrida sigue con ellos.
                    processed++
                    break
                }
                val note = if (result.problems.isEmpty()) "" else "Archivos sin importar: " + result.problems.joinToString("; ")
                MantenimientoMondayJobs.finishFiles(tenant, job.id, row.rowNo, note)
            }
            processed++
        }
        if (processed > 0) MantenimientoMondayJobs.resetRetries(tenant, job.id)
        MantenimientoMondayJobs.recount(tenant, job.id)
        if (MantenimientoMondayJobs.statusOf(tenant, job.id) == MondayJobStatus.Applying && MantenimientoMondayJobs.pending(tenant, job.id, 1).isEmpty()) finish(tenant, job.id)
        return processed
    }

    private class FilesResult(val done: Int, val failed: Int, val problems: List<String>, val complete: Boolean)

    /**
     * Baja los archivos de una fila a la orden. Los que ya son adjuntos (misma clave de asset) se saltan antes de
     * descargar y no se cuentan. Si se acaba el tiempo devuelve [FilesResult.complete] en false; con la corrida entera
     * por delante ([fullBudget]) un primer archivo que no cabe cuenta como fallido, para no reintentarlo sin fin.
     */
    private fun importFiles(
        context: MantenimientoImportJobContext,
        job: MondayJob,
        token: String,
        orderId: UUID,
        files: List<MondayFile>,
        deadline: Long,
        fullBudget: Boolean,
    ): FilesResult {
        val problems = mutableListOf<String>()
        var done = 0
        var failed = 0
        if (token.isBlank()) return FilesResult(0, files.size, listOf("falta el token de monday"), true)
        val existing = MantenimientoMondayJobs.attachedKeys(context.tenant, orderId, files.map { MantenimientoMondayMapping.AssetPrefix + it.asset })
        val todo = files.filter { MantenimientoMondayMapping.AssetPrefix + it.asset !in existing }
        if (todo.isEmpty()) return FilesResult(0, 0, emptyList(), true)
        val links = try {
            MantenimientoMondayClient.assetLinks(token, todo.map { it.asset }, deadline)
        } catch (failure: MantenimientoMondayException) {
            // Un fallo pasajero sube al trabajo (reintento); uno permanente deja los archivos como fallidos.
            if (failure.transient) throw failure
            log.warn("Importacion de monday {}: no se pidieron los archivos: {}", job.id, failure.message)
            return FilesResult(0, todo.size, listOf("no se pidieron los archivos a monday (${failure.message})"), true)
        }
        for ((index, file) in todo.withIndex()) {
            val firstWithFullTime = fullBudget && index == 0
            if (!MantenimientoMondayClient.hasTime(deadline) && !firstWithFullTime) return FilesResult(done, failed, problems, false)
            val name = file.name.ifBlank { "archivo-${file.asset}" }
            val link = links[file.asset]
            if (link == null) {
                failed++
                problems += "$name: monday no lo devolvio"
                continue
            }
            if (link.size != null && link.size > MantenimientoMondayClient.MaxFileBytes) {
                failed++
                problems += "$name: supera ${MantenimientoMondayClient.MaxFileBytes / (1024 * 1024)} MB"
                continue
            }
            try {
                MantenimientoMondayClient.download(link.url, deadline) { contentType, body ->
                    val stored = MantenimientoImportApply.attachFile(
                        context.tenant, context.files, orderId, MantenimientoMondayMapping.AssetPrefix + file.asset, name, contentType, body, job.createdBy,
                    )
                    if (stored) done++
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: Exception) {
                // Cortado por el tiempo de la corrida: se retoma despues (salvo el primero con todo el tiempo, que no cabe).
                if (!MantenimientoMondayClient.hasTime(deadline) && !firstWithFullTime) return FilesResult(done, failed, problems, false)
                failed++
                val reason = (failure as? MantenimientoMondayException)?.message ?: "no se pudo descargar"
                log.warn("Importacion de monday {}: un archivo fallo: {}", job.id, reason)
                problems += "$name: $reason"
            }
        }
        return FilesResult(done, failed, problems, true)
    }

    private fun finish(tenant: Connection, jobId: UUID) {
        val job = MantenimientoMondayJobs.find(tenant, jobId) ?: return
        val message = "Importacion terminada: ${job.created} creadas, ${job.updated} actualizadas, ${job.skipped} omitidas y ${job.failed} con error. " +
            "Archivos: ${job.filesDone} importados y ${job.filesFailed} con error."
        MantenimientoMondayJobs.changeStatus(tenant, jobId, MondayJobStatus.Done, message, from = listOf(MondayJobStatus.Applying))
    }
}
