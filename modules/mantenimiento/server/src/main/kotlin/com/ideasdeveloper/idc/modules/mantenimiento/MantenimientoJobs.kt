package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModuleJobContext
import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.LocalDate

/**
 * Trabajos periódicos del módulo. Cada uno es corto, idempotente y registra el error de cada elemento sin detener el lote: lo que
 * falla se reintenta en la siguiente ejecución.
 */
object MantenimientoJobs {
    private val log = LoggerFactory.getLogger(MantenimientoJobs::class.java)
    const val PlanBatch = 200
    const val ImportBudgetSeconds = 40L

    /**
     * Cuerpo de plan-generator: crea la orden de cada plan al que ya le toca (una transacción por plan) y avisa al responsable.
     * Devuelve cuántas órdenes se crearon.
     */
    fun generatePlans(tenant: Connection, notifier: ModuleNotifier?, today: LocalDate, batch: Int = PlanBatch): Int {
        var created = 0
        MantenimientoRepository.plansDueForGeneration(tenant, today, batch).forEach { plan ->
            try {
                val generated = MantenimientoRepository.generateOrder(tenant, plan, today)
                if (generated != null) {
                    created++
                    MantenimientoNotify.assignedByPlan(tenant, notifier, generated)
                }
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("No se pudo generar la orden del plan {} ({}).", plan.id, failure.javaClass.simpleName)
                // Se excluye del lote 6 horas para que un plan que siempre falla no bloquee a los demás.
                runCatching { MantenimientoRepository.markPlanError(tenant, plan.id) }
                    .onFailure { log.warn("No se pudo registrar el error del plan {} ({}).", plan.id, it.javaClass.simpleName) }
            }
        }
        return created
    }

    val jobs: List<ModuleJob> = listOf(
        // Órdenes preventivas de los planes a los que ya les toca (anticipación cumplida y sin orden abierta).
        ModuleJob("plan-generator", Duration.ofHours(1)) { context ->
            generatePlans(context.tenant, context.notifier, MantenimientoRepository.today())
        },
        // Órdenes que vencen pronto o ya vencieron: un aviso por responsable y fecha.
        ModuleJob("due-reminders", Duration.ofMinutes(30)) { context ->
            MantenimientoNotify.dueReminders(context.tenant, context.notifier, MantenimientoRepository.today())
        },
        // Archivos de adjuntos quitados y subidas cuyo formulario nunca se guardó.
        ModuleJob("attachments-cleanup", Duration.ofHours(1)) { context ->
            MantenimientoRepository.cleanup(context.tenant, context.access.files(context.companyCode, MantenimientoModule.id))
        },
        // Refleja en Tareas las órdenes asignadas (solo si hay tablero configurado).
        ModuleJob("tareas-sync", Duration.ofMinutes(2)) { context -> tareasSync(context) },
        // Lee y aplica las importaciones de las extensiones (un tramo corto cada minuto).
        ModuleJob("import-runner", Duration.ofMinutes(1)) { context ->
            try {
                MantenimientoImportSources.runPending(
                    MantenimientoImportJobContext(
                        context.access,
                        context.tenant,
                        context.companyCode,
                        context.access.files(context.companyCode, MantenimientoModule.id),
                        MantenimientoRepository.today(),
                    ),
                    Duration.ofSeconds(ImportBudgetSeconds),
                )
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("Fallo el trabajo de importacion de Mantenimiento ({}).", failure.javaClass.simpleName)
            }
        },
    )

    private fun tareasSync(context: ModuleJobContext) {
        try {
            MantenimientoTareasSync.run(context.access, context.tenant, context.companyCode)
        } catch (stop: InterruptedException) {
            throw stop
        } catch (stop: java.util.concurrent.CancellationException) {
            throw stop
        } catch (failure: Exception) {
            log.warn("Fallo el trabajo tareas-sync de Mantenimiento ({}).", failure.javaClass.simpleName)
        }
    }
}
