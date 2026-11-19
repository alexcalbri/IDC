package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModuleJobContext
import com.ideasdeveloper.idc.server.modules.NotificationTarget
import com.ideasdeveloper.idc.server.modules.runGuarded
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** Trabajo diario: cada paso va en su propio try/catch, con lotes acotados, y es idempotente. */
object MembresiaJobs {
    private val log = LoggerFactory.getLogger(MembresiaJobs::class.java)

    val daily = ModuleJob("membresia-diario", Duration.ofDays(1), dailyAt = LocalTime.of(7, 0)) { ctx -> run(ctx, LocalDate.now()) }

    fun run(ctx: ModuleJobContext, today: LocalDate) {
        val tenant = ctx.tenant
        // 1. programada -> activa cuando ya empezo (lotes mientras haya mas, con tope).
        runGuarded(log, "Membresías ${ctx.companyCode}: no se pudieron activar las programadas") {
            repeatWhileFull { MembresiaRepository.activateStarted(tenant, today, BatchSize) }
        }
        // 2. activa -> vencida cuando su fin paso; luego el evento de historial de las vencidas de los ultimos 7 dias
        // (idempotente: el sourceRef deduplica, asi un fallo de ayer se repara hoy).
        runGuarded(log, "Membresías ${ctx.companyCode}: no se pudieron vencer las terminadas") {
            repeatWhileFull { MembresiaRepository.expireEnded(tenant, today, BatchSize).size }
        }
        runGuarded(log, "Membresías ${ctx.companyCode}: no se pudo escribir el historial de las vencidas") {
            MembresiaRepository.recentlyExpired(tenant, today).forEach { MembresiaHistory.expired(ctx.access, tenant, it) }
        }
        // 3. aviso al equipo de las que estan por vencer.
        runGuarded(log, "Membresías ${ctx.companyCode}: no se pudieron avisar los vencimientos") {
            notifyExpiring(ctx, today)
        }
        // 4. pases WiFi del hotspot.
        runGuarded(log, "Membresías ${ctx.companyCode}: no se pudieron sincronizar los pases del hotspot") {
            MembresiaHotspot.syncAll(tenant, today)
        }
    }

    private const val BatchSize = 500
    private const val MaxBatches = 20

    // Repite el lote mientras devuelva uno completo, con un tope de iteraciones por corrida.
    private fun repeatWhileFull(batch: () -> Int) {
        repeat(MaxBatches) { if (batch() < BatchSize) return }
    }

    private fun notifyExpiring(ctx: ModuleJobContext, today: LocalDate) {
        val tenant = ctx.tenant
        val settings = MembresiaRepository.settings(tenant)
        for (membership in MembresiaRepository.expiringToNotify(tenant, today, settings.expiringDays)) {
            runGuarded(log, "Membresías ${ctx.companyCode}: no se pudo avisar el vencimiento de ${membership.id}") {
                val recipients = MembresiaRepository.notificationRecipients(tenant, membership.hostUserId)
                recipients.forEach { userId ->
                    try {
                        ctx.notifier.notify(
                            tenant,
                            userId,
                            "Membresía por vencer",
                            "${membership.customerName} · ${membership.planName} vence el ${membership.endsOn}",
                            NotificationTarget("miembro", mapOf("customer" to membership.customerId.toString())),
                        )
                    } catch (failure: Exception) {
                        log.warn("Membresías {}: no se pudo notificar a {}: {}", ctx.companyCode, userId, failure.message)
                    }
                }
                // Se marca aunque alguna notificacion haya fallado: no se repite cada dia.
                MembresiaRepository.markExpiringNotified(tenant, membership.id, today)
            }
        }
    }
}
