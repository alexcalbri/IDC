package com.ideasdeveloper.idc.extensions.inmobiliaria_monday

import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmImportKind
import com.ideasdeveloper.idc.modules.crm.CrmImportTarget
import com.ideasdeveloper.idc.modules.crm.CrmModuleExtensions
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.Instant

/**
 * Trabajo en segundo plano de la extension (ModuleJob "import", una vez por minuto y por empresa): lee los tableros en cola
 * (por paginas, con el cursor guardado en la importacion) y aplica las importaciones en curso en lotes de 100 a la extension
 * inmobiliaria. Cada corrida se acota con un presupuesto de tiempo: lo que no alcanza sigue en la siguiente.
 */
object InmobiliariaMondayRunner {
    private val log = LoggerFactory.getLogger("InmobiliariaMonday")

    private const val PermissionLost = "El usuario que aplico la importacion ya no tiene permiso."

    /** La extension destino (Inmobiliaria) y su contrato de importacion; null si no esta instalada o no esta activa en la empresa. */
    fun target(tenant: Connection): CrmImportTarget? =
        if (!CrmHost.extensionEnabled(tenant, InmobiliariaMondayServerExtension.TargetId)) null
        else CrmModuleExtensions.importTargets().firstOrNull { it.extensionId == InmobiliariaMondayServerExtension.TargetId }

    fun kindOf(target: CrmImportTarget, kind: String): CrmImportKind? = target.importKinds.firstOrNull { it.id == kind }

    /** Una corrida: lee y aplica dentro de [budget]; devuelve cuantas filas leyo o aplico. */
    fun run(tenant: Connection, budget: Duration): Int {
        val started = Instant.now()
        // Sin la extension destino las importaciones esperan (no fallan): se retoman cuando vuelva a estar activa.
        val target = target(tenant) ?: return 0
        val deadline = started.plus(budget)
        var done = 0
        for (job in InmobiliariaMondayStore.inState(tenant, MondayJobState.Reading)) {
            if (remainingMillis(deadline) <= 0) return done
            done += read(tenant, job, target, deadline)
        }
        for (job in InmobiliariaMondayStore.inState(tenant, MondayJobState.Applying)) {
            if (remainingMillis(deadline) <= 0) return done
            done += apply(tenant, job, target, deadline)
        }
        return done
    }

    private fun remainingMillis(deadline: Instant): Long = Duration.between(Instant.now(), deadline).toMillis()

    // ---- Lectura ----

    /** Avance de la lectura de un tablero dentro de una corrida: columnas, cursor de la pagina siguiente y si falta la primera pagina. */
    private class ReadState(var columns: List<MondayColumn>, var cursor: String, var first: Boolean)

    /** Filas nuevas de una pagina y total guardado de la importacion. */
    private class PageResult(val added: Int, val total: Int)

    private fun read(tenant: Connection, job: MondayJob, target: CrmImportTarget, deadline: Instant): Int {
        var read = 0
        try {
            val token = InmobiliariaMondaySettingsStore.loadToken(tenant) ?: throw InmobiliariaMondayException("Se borro el token de monday; guardalo de nuevo.")
            val kind = kindOf(target, job.kind) ?: throw InmobiliariaMondayException("La extension Inmobiliaria no importa ese tipo de registro.")
            val state = ReadState(job.columns, job.readCursor, !job.readStarted)
            while (true) {
                // Antes de cada consulta: sin tiempo, el cursor queda guardado y la siguiente corrida sigue desde ahi.
                if (remainingMillis(deadline) < MondayMapping.MinQueryMillis) return read
                val page = readPage(tenant, job, token, state, deadline) ?: return read
                read += page.added
                if (page.total == 0 && state.cursor.isEmpty()) throw InmobiliariaMondayException("El tablero no tiene elementos.")
                when (MondayMapping.readStep(state.cursor, remainingMillis(deadline))) {
                    ReadStep.Finish -> {
                        // Un mapeo que trajo quien la lanzo (agente) se valida contra las columnas reales; si no coincide, el sugerido y un aviso.
                        val (mapping, notice) = MondayMapping.mappingAfterRead(kind, state.columns, job.mapping)
                        InmobiliariaMondayStore.finishReading(tenant, job.id, mapping, notice)
                        return read
                    }
                    ReadStep.Pause -> return read
                    ReadStep.Continue -> Unit
                }
            }
        } catch (failure: InmobiliariaMondayTransientException) {
            // Red, 429 o 5xx: la importacion sigue leyendo y reintenta en la siguiente corrida, hasta el maximo de intentos.
            val attempts = InmobiliariaMondayStore.recordReadFailure(tenant, job.id)
            if (attempts != null && MondayMapping.giveUpReading(attempts)) {
                InmobiliariaMondayStore.transition(
                    tenant, job.id, MondayJobState.Reading, MondayJobState.Failed,
                    "No se pudo leer el tablero de monday tras ${MondayMapping.MaxReadAttempts} intentos (${failure.message.orEmpty()}).",
                )
            } else {
                log.warn("Error pasajero al leer el tablero de monday de la importacion {} (intento {}).", job.id, attempts)
            }
        } catch (failure: InmobiliariaMondayException) {
            InmobiliariaMondayStore.transition(tenant, job.id, MondayJobState.Reading, MondayJobState.Failed, failure.message.orEmpty())
        } catch (failure: Exception) {
            // Solo la clase: el mensaje de una excepcion de red o de base de datos no debe llegar a los registros.
            log.warn("No se pudo leer el tablero de monday de la importacion {} ({}).", job.id, failure.javaClass.simpleName)
            InmobiliariaMondayStore.transition(tenant, job.id, MondayJobState.Reading, MondayJobState.Failed, "No se pudo leer el tablero de monday.")
        }
        return read
    }

    /**
     * Lee una pagina (la primera trae nombre y columnas) y la guarda con el avance en una sola transaccion. null si la importacion se
     * cancelo mientras tanto (no se guarda nada). El plazo HTTP de la consulta no pasa del tiempo que queda a la corrida.
     */
    private fun readPage(tenant: Connection, job: MondayJob, token: String, state: ReadState, deadline: Instant): PageResult? {
        val remaining = remainingMillis(deadline)
        val boardName: String?
        val newColumns: List<MondayColumn>?
        val items: List<MondayItem>
        if (state.first) {
            val page = InmobiliariaMondayClient.firstPage(token, job.boardId, remaining)
            state.columns = page.columns
            boardName = page.boardName
            newColumns = page.columns
            items = page.items
            state.cursor = page.cursor
        } else {
            val page = InmobiliariaMondayClient.nextPage(token, state.columns, state.cursor, remaining)
            boardName = null
            newColumns = null
            items = page.items
            state.cursor = page.cursor
        }
        val result = transaction(tenant) {
            if (!InmobiliariaMondayStore.saveReadProgress(tenant, job.id, boardName, newColumns, state.cursor)) return@transaction null
            val before = InmobiliariaMondayStore.syncRowsRead(tenant, job.id)
            InmobiliariaMondayStore.insertRows(tenant, job.id, items)
            // rows_read cuenta filas realmente nuevas: un elemento que monday repite en otra pagina reemplaza su fila y no suma.
            val total = InmobiliariaMondayStore.syncRowsRead(tenant, job.id)
            if (total > MondayMapping.MaxRows) {
                throw InmobiliariaMondayException("El tablero tiene mas de ${MondayMapping.MaxRows} elementos; divide la importacion o filtra el tablero en monday.")
            }
            PageResult(total - before, total)
        }
        state.first = false
        return result
    }

    // ---- Aplicacion ----

    private fun apply(tenant: Connection, job: MondayJob, target: CrmImportTarget, deadline: Instant): Int {
        val kind = kindOf(target, job.kind)
        if (kind == null) {
            InmobiliariaMondayStore.transition(tenant, job.id, MondayJobState.Applying, MondayJobState.Failed, "La extension Inmobiliaria no importa ese tipo de registro.")
            return 0
        }
        // Quien pulso Aplicar es quien actua en Inmobiliaria, y debe seguir siendo usuario activo con crm.settings.
        val applier = job.appliedBy
        if (applier == null || !InmobiliariaMondayStore.userMayImport(tenant, applier)) {
            InmobiliariaMondayStore.transition(tenant, job.id, MondayJobState.Applying, MondayJobState.Failed, PermissionLost)
            return 0
        }
        val actor = CrmActor(applier, job.appliedByName)
        var applied = 0
        while (Instant.now().isBefore(deadline)) {
            // Si se cancelo mientras corria, el lote siguiente ni se lee.
            if (InmobiliariaMondayStore.find(tenant, job.id)?.state != MondayJobState.Applying) return applied
            val rows = InmobiliariaMondayStore.rows(tenant, job.id, MondayRowState.Pending, MondayMapping.BatchSize)
            if (rows.isEmpty()) {
                InmobiliariaMondayStore.transition(tenant, job.id, MondayJobState.Applying, MondayJobState.Done)
                return applied
            }
            applyBatch(tenant, job, kind, target, actor, rows)
            applied += rows.size
        }
        return applied
    }

    private fun applyBatch(tenant: Connection, job: MondayJob, kind: CrmImportKind, target: CrmImportTarget, actor: CrmActor, rows: List<MondayRow>) {
        val records = rows.map { MondayMapping.record(it, job.mapping, kind, job.newListings) }
        val outcome = try {
            target.importRecords(tenant, actor, job.kind, records)
        } catch (failure: Exception) {
            // Todo el lote falla (por ejemplo, la base): sus filas quedan con problema y la importacion sigue con las demas.
            log.warn("Fallo un lote de la importacion {} ({}).", job.id, failure.javaClass.simpleName)
            val message = "No se pudo aplicar el lote."
            rows.forEach { InmobiliariaMondayStore.markRow(tenant, it.id, MondayRowState.Failed, message) }
            InmobiliariaMondayStore.addResult(tenant, job.id, 0, 0, 0, rows.size, listOf("${rows.first().importKey}: $message (${rows.size} filas)"))
            return
        }
        // Fallan solo los registros no guardados (failedKeys); el resto queda aplicado, con su aviso si lo hay. Sin duplicar conteos.
        MondayMapping.verdicts(rows, outcome.failedKeys, outcome.problems, outcome.warnings).forEach { verdict ->
            InmobiliariaMondayStore.markRow(tenant, verdict.row.id, verdict.state, verdict.text)
        }
        InmobiliariaMondayStore.addResult(tenant, job.id, outcome.created, outcome.updated, outcome.skipped, outcome.failedKeys.size, outcome.problems + outcome.warnings)
    }

    /** Transaccion sobre la conexion de la empresa; la devuelve al modo anterior al terminar. */
    private fun <T> transaction(tenant: Connection, block: () -> T): T {
        val previous = tenant.autoCommit
        tenant.autoCommit = false
        try {
            val result = block()
            tenant.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { tenant.rollback() }
            throw failure
        } finally {
            tenant.autoCommit = previous
        }
    }
}
