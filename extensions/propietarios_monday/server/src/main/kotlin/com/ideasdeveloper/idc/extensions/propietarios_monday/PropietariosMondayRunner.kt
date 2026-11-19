package com.ideasdeveloper.idc.extensions.propietarios_monday

import com.ideasdeveloper.idc.modules.propietarios.LotImportOutcome
import com.ideasdeveloper.idc.modules.propietarios.PropietariosImportApply
import com.ideasdeveloper.idc.modules.propietarios.PropietariosImportJobContext
import com.ideasdeveloper.idc.modules.propietarios.PropietariosValidationException
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

/**
 * Avanza las importaciones: lee un tablero pagina por pagina (cursor guardado en el trabajo, hasta 5 000 elementos) y
 * aplica 100 filas por corrida, con sus archivos. Lo llama el modulo Propietarios con un presupuesto de tiempo estricto:
 * no se empieza una llamada o descarga con menos de 5 s por delante y cada una se corta a lo que queda. Todo paso es
 * corto e idempotente: si se repite no duplica nada. Los fallos pasajeros de monday reintentan (hasta [MaxRetries]
 * seguidos); los permanentes y todo error de una fila quedan en el trabajo o en la fila y en el registro del servidor,
 * nunca se pierden en silencio (ningun mensaje lleva el token).
 */
object PropietariosMondayRunner {
    const val BatchSize = 100
    const val MaxRetries = 5
    const val MaxCursorLength = 2000
    const val MaxPages = PropietariosMondayClient.MaxItems / PropietariosMondayClient.PageSize * 3
    private const val CancelCheckEvery = 20

    /** Tiempo restante con el que un intento de descarga se considera hecho con el presupuesto casi completo. */
    private const val FullAttemptMillis = 35_000L

    /** Clave con la que el modulo reconoce el desarrollo creado desde un tablero de monday. */
    const val DevelopmentKeyPrefix = "monday:board:"

    /** Autor que queda en el historial de cada lote importado. */
    const val Author = "Importacion de monday"

    private val log = LoggerFactory.getLogger(PropietariosMondayRunner::class.java)

    /** Devuelve las filas leidas o aplicadas en esta corrida. */
    fun run(context: PropietariosImportJobContext, budget: Duration): Int {
        val tenant = context.tenant
        if (!PropietariosMondaySettings.extensionEnabled(tenant)) return 0
        val deadline = System.nanoTime() + budget.toNanos()
        var processed = 0
        PropietariosMondayJobs.withStatus(tenant, MondayJobStatus.Reading).forEach { job ->
            if (PropietariosMondayClient.hasTime(deadline)) processed += guarded(tenant, job) { readPages(context, job, deadline) }
        }
        PropietariosMondayJobs.withStatus(tenant, MondayJobStatus.Applying).firstOrNull()?.let { job ->
            if (PropietariosMondayClient.hasTime(deadline)) processed += guarded(tenant, job) { applyBatch(context, job, deadline) }
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
        } catch (failure: PropietariosMondayException) {
            if (failure.transient) {
                log.warn("Importacion de monday {}: error temporal: {}", job.id, failure.message)
                retry(tenant, job.id, failure.message.orEmpty())
            } else {
                log.warn("Importacion de monday {} fallo: {}", job.id, failure.message)
                fail(tenant, job.id, failure.message.orEmpty())
            }
        } catch (failure: PropietariosValidationException) {
            log.warn("Importacion de monday {} fallo: {}", job.id, failure.message)
            fail(tenant, job.id, failure.message.orEmpty())
        } catch (failure: SQLException) {
            if (isTransientSql(failure)) {
                log.warn("Importacion de monday {}: error temporal de base de datos: {}", job.id, failure.sqlState)
                retry(tenant, job.id, "Error temporal de la base de datos.")
            } else {
                log.error("Importacion de monday {} fallo", job.id, failure)
                fail(tenant, job.id, "No se pudo completar la importacion; revisa el registro del servidor.")
            }
        } catch (failure: Exception) {
            log.error("Importacion de monday {} fallo", job.id, failure)
            fail(tenant, job.id, "No se pudo completar la importacion; revisa el registro del servidor.")
        }
        return 0
    }

    /** Fallos pasajeros de la base: conflicto de transaccion o interbloqueo (clase 40) y conexion (clase 08). */
    private fun isTransientSql(failure: SQLException): Boolean {
        val state = failure.sqlState.orEmpty()
        return state.startsWith("40") || state.startsWith("08")
    }

    private fun retry(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            val attempts = PropietariosMondayJobs.bumpRetries(tenant, id, "Error temporal de monday, se reintenta (intento de $MaxRetries): $message")
            if (attempts >= MaxRetries) fail(tenant, id, "monday no respondio despues de $MaxRetries intentos: $message")
        }.onFailure { log.error("No se pudo registrar el reintento de la importacion de monday {}", id, it) }
    }

    private fun fail(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            PropietariosMondayJobs.changeStatus(tenant, id, MondayJobStatus.Failed, message, from = listOf(MondayJobStatus.Reading, MondayJobStatus.Applying))
        }.onFailure { log.error("No se pudo marcar como fallida la importacion de monday {}", id, it) }
    }

    // ---- Lectura ----

    private fun readPages(context: PropietariosImportJobContext, job: MondayJob, deadline: Long): Int {
        val tenant = context.tenant
        val token = PropietariosMondaySettings.loadToken(context.access.secrets, tenant)
            ?: throw PropietariosMondayException("Falta el token de monday o no se puede leer; guardalo de nuevo.")
        var cursor = job.cursor
        var pages = job.pages
        var read = 0
        // Cursor vacio: ya se leyo todo y solo falta el resumen de la vista previa.
        if (cursor != "") {
            do {
                if (PropietariosMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Reading) return read
                if (pages >= MaxPages) throw PropietariosMondayException("La lectura del tablero no termina (demasiadas paginas); divide el tablero antes de importarlo.")
                val page = PropietariosMondayClient.itemsPage(token, job.boardId, cursor, deadline)
                if (page.cursor.length > MaxCursorLength) throw PropietariosMondayException("monday devolvio un cursor invalido; no se puede seguir leyendo el tablero.")
                if (page.items.isEmpty() && page.cursor.isNotEmpty()) throw PropietariosMondayException("monday devolvio una pagina vacia sin terminar la lectura del tablero.")
                if (job.total + read + page.items.size > PropietariosMondayClient.MaxItems) {
                    throw PropietariosMondayException("El tablero tiene mas de ${PropietariosMondayClient.MaxItems} elementos; divide el tablero antes de importarlo.")
                }
                val rows = page.items.map { item ->
                    val converted = PropietariosMondayMapping.toLot(item, job.mapping)
                    Triple(item.id, MondayRowDto.encode(converted.lot), PropietariosMondayMapping.encodeFiles(converted.files))
                }
                if (!PropietariosMondayJobs.savePage(tenant, job.id, rows, page.cursor)) return read
                read += page.items.size
                pages++
                cursor = page.cursor
            } while (cursor != "" && PropietariosMondayClient.hasTime(deadline))
        }
        if (cursor == "") finishReading(tenant, job)
        return read
    }

    /** Calcula el resumen de la vista previa una sola vez (con todas las filas, para ver los duplicados) y pasa el trabajo a revision. */
    private fun finishReading(tenant: Connection, job: MondayJob) {
        val lots = PropietariosMondayJobs.allLots(tenant, job.id)
        if (lots.isEmpty()) throw PropietariosMondayException("El tablero no tiene elementos.")
        val summary = MondaySummaryDto.of(PropietariosImportApply.summary(lots), PropietariosMondayJobs.fileCount(tenant, job.id))
        PropietariosMondayJobs.moveToPreview(tenant, job.id, summary)
    }

    // ---- Aplicacion ----

    private fun applyBatch(context: PropietariosImportJobContext, job: MondayJob, deadline: Long): Int {
        val tenant = context.tenant
        // Los permisos se leen en vivo: si quien inicio la importacion ya no puede importar, se cancela antes de escribir nada.
        if (!creatorMayImport(context, job)) {
            PropietariosMondayJobs.cancelWithMessage(tenant, job.id, "Quien inició la importación ya no tiene permiso.")
            return 0
        }
        // Idempotente: el desarrollo se busca por su clave (o es el elegido), asi cada corrida llega al mismo.
        val developmentId = PropietariosImportApply.ensureDevelopment(
            tenant, DevelopmentKeyPrefix + job.boardId, job.boardName.ifBlank { "Monday ${job.boardId}" }, job.targetDevelopmentId, job.createdBy,
        )
        val batch = PropietariosMondayJobs.pending(tenant, job.id, BatchSize)
        var processed = 0
        var token: String? = null
        for ((index, row) in batch.withIndex()) {
            if (index % CancelCheckEvery == 0 && PropietariosMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Applying) break
            if (!PropietariosMondayClient.hasTime(deadline)) break
            val files = PropietariosMondayMapping.decodeFiles(row.files)
            var lotId = row.lotId
            // Fila sin aplicar: se aplica y se guarda su resultado y su lote al instante, antes de bajar archivos.
            if (row.outcome == null) {
                var outcome: String
                var message = ""
                lotId = null
                try {
                    val lot = MondayRowDto.decode(row.payload)
                    when (val result = PropietariosImportApply.apply(tenant, context.access, developmentId, lot, Author, job.createdBy, context.today)) {
                        is LotImportOutcome.Created -> { outcome = "created"; lotId = result.lotId }
                        is LotImportOutcome.Updated -> { outcome = "updated"; lotId = result.lotId }
                        is LotImportOutcome.Skipped -> { outcome = "skipped"; message = result.reason }
                    }
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                } catch (failure: Exception) {
                    // Un fallo pasajero de la base sube al trabajo para reintentar; la fila no se marca fallida.
                    if (failure is SQLException && isTransientSql(failure)) throw failure
                    log.warn("Importacion de monday {}: la fila {} fallo", job.id, row.rowNo, failure)
                    outcome = "failed"
                    message = "No se pudo importar esta fila: " + failure.javaClass.simpleName
                }
                PropietariosMondayJobs.saveApplied(tenant, job.id, row.rowNo, outcome, message, lotId, lotId != null && files.isNotEmpty())
            }
            if (lotId != null && files.isNotEmpty()) {
                if (token == null) token = PropietariosMondaySettings.loadToken(context.access.secrets, tenant).orEmpty()
                val result = importFiles(context, job, token, lotId, files, deadline, fullBudget = processed == 0)
                // Los archivos ya guardados se cuentan siempre (la proxima vez se saltan); los fallidos solo al cerrar la fila.
                PropietariosMondayJobs.addFiles(tenant, job.id, result.done, if (result.complete) result.failed else 0)
                if (!result.complete) {
                    // Se acabo el tiempo con archivos por bajar: la fila sigue pendiente y la proxima corrida sigue con ellos.
                    processed++
                    break
                }
                val note = if (result.problems.isEmpty()) "" else "Archivos sin importar: " + result.problems.joinToString("; ")
                PropietariosMondayJobs.finishFiles(tenant, job.id, row.rowNo, note)
            }
            processed++
        }
        if (processed > 0) PropietariosMondayJobs.resetRetries(tenant, job.id)
        PropietariosMondayJobs.recount(tenant, job.id)
        if (PropietariosMondayJobs.statusOf(tenant, job.id) == MondayJobStatus.Applying && PropietariosMondayJobs.pending(tenant, job.id, 1).isEmpty()) finish(tenant, job.id)
        return processed
    }

    /** Sin usuario guardado (dueno del servidor) se permite; si no, el usuario debe seguir en la empresa con propietarios.import (o ser dueno). */
    private fun creatorMayImport(context: PropietariosImportJobContext, job: MondayJob): Boolean {
        val creator = job.createdBy ?: return true
        val access = context.access.userAccess(context.tenant, context.companyCode, creator, serverOwner = false) ?: return false
        return access.permissions("propietarios")?.has(PropietariosImportPermission) == true
    }

    private class FilesResult(val done: Int, val failed: Int, val problems: List<String>, val complete: Boolean)

    /**
     * Baja los archivos de una fila al lote. Los que ya son adjuntos (misma clave de asset) se saltan antes de
     * descargar y no se cuentan. Si se acaba el tiempo devuelve [FilesResult.complete] en false; con la corrida entera
     * por delante ([fullBudget]) un primer archivo que no cabe cuenta como fallido, para no reintentarlo sin fin.
     */
    private fun importFiles(
        context: PropietariosImportJobContext,
        job: MondayJob,
        token: String,
        lotId: UUID,
        files: List<MondayFile>,
        deadline: Long,
        fullBudget: Boolean,
    ): FilesResult {
        val problems = mutableListOf<String>()
        var done = 0
        var failed = 0
        if (token.isBlank()) return FilesResult(0, files.size, listOf("falta el token de monday"), true)
        val existing = PropietariosMondayJobs.attachedKeys(context.tenant, lotId, files.map { PropietariosMondayMapping.AssetPrefix + it.asset })
        val todo = files.filter { PropietariosMondayMapping.AssetPrefix + it.asset !in existing }
        if (todo.isEmpty()) return FilesResult(0, 0, emptyList(), true)
        val links = try {
            PropietariosMondayClient.assetLinks(token, todo.map { it.asset }, deadline)
        } catch (failure: PropietariosMondayException) {
            // Un fallo pasajero sube al trabajo (reintento); uno permanente deja los archivos como fallidos.
            if (failure.transient) throw failure
            log.warn("Importacion de monday {}: no se pidieron los archivos: {}", job.id, failure.message)
            return FilesResult(0, todo.size, listOf("no se pidieron los archivos a monday (${failure.message})"), true)
        }
        for ((index, file) in todo.withIndex()) {
            val firstWithFullTime = fullBudget && index == 0
            if (!PropietariosMondayClient.hasTime(deadline) && !firstWithFullTime) return FilesResult(done, failed, problems, false)
            val name = file.name.ifBlank { "archivo-${file.asset}" }
            val link = links[file.asset]
            if (link == null) {
                failed++
                problems += "$name: monday no lo devolvio"
                continue
            }
            if (link.size != null && link.size > PropietariosMondayClient.MaxFileBytes) {
                failed++
                problems += "$name: supera ${PropietariosMondayClient.MaxFileBytes / (1024 * 1024)} MB"
                continue
            }
            // Solo cuenta como fallido un archivo que fallo con casi todo el presupuesto por delante; si no, queda pendiente.
            val millisAtStart = (deadline - System.nanoTime()) / 1_000_000L
            try {
                PropietariosMondayClient.download(link.url, deadline) { contentType, body ->
                    val stored = PropietariosImportApply.attachFile(
                        context.tenant, context.files, lotId, PropietariosMondayMapping.AssetPrefix + file.asset, name, contentType, body, job.createdBy,
                    )
                    if (stored) done++
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: Exception) {
                // Cortado por el tiempo de la corrida: se retoma despues, salvo que haya fallado con el presupuesto casi completo.
                if (millisAtStart < FullAttemptMillis) return FilesResult(done, failed, problems, false)
                failed++
                val reason = (failure as? PropietariosMondayException)?.message ?: "no se pudo descargar"
                log.warn("Importacion de monday {}: un archivo fallo: {}", job.id, reason)
                problems += "$name: $reason"
            }
        }
        return FilesResult(done, failed, problems, true)
    }

    private fun finish(tenant: Connection, jobId: UUID) {
        val job = PropietariosMondayJobs.find(tenant, jobId) ?: return
        val message = "Importacion terminada: ${job.created} lotes creados, ${job.updated} actualizados, ${job.skipped} omitidos y ${job.failed} con error. " +
            "Archivos: ${job.filesDone} importados y ${job.filesFailed} con error."
        PropietariosMondayJobs.changeStatus(tenant, jobId, MondayJobStatus.Done, message, from = listOf(MondayJobStatus.Applying))
    }
}
