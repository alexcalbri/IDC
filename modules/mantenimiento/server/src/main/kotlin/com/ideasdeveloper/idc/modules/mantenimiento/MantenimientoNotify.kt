package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import com.ideasdeveloper.idc.server.modules.NotificationTarget
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Avisos en el centro de notificaciones del Core. Un aviso nunca rompe el cambio que lo causó: cada uno se crea dentro de
 * runCatching y un fallo solo se registra. Quien hace el cambio nunca recibe aviso de él.
 */
object MantenimientoNotify {
    private val log = LoggerFactory.getLogger(MantenimientoNotify::class.java)

    const val AssignedTitle = "Te asignaron una orden de mantenimiento"
    const val ProjectRequestedTitle = "Nueva solicitud de proyecto"
    const val ProjectApprovedTitle = "Proyecto aprobado"
    const val ProjectRejectedTitle = "Proyecto rechazado"
    const val BreakdownTitle = "Nueva avería reportada"
    const val CompletedTitle = "Orden de mantenimiento completada"
    const val DueSoonTitle = "Orden de mantenimiento por vencer"
    const val OverdueTitle = "Orden de mantenimiento vencida"

    fun target(orderId: UUID) = NotificationTarget("order", mapOf("id" to orderId.toString()))

    /** El texto de los avisos: "OT-0007 · título". */
    fun text(order: Order): String = "${order.displayNumber} · ${order.title}"

    // ---- destinatarios (puros) ----

    /** A quién avisar de una asignación: el responsable nuevo, nunca quien la hizo. */
    fun assignedRecipient(assigneeId: UUID?, byUserId: UUID?): UUID? = assigneeId?.takeIf { it != byUserId }

    /** Quienes gestionan y el dueño del negocio, cada uno una vez, sin el autor. */
    fun managerRecipients(managers: Collection<UUID>, byUserId: UUID?): List<UUID> = managers.distinct().filter { it != byUserId }

    /** El solicitante, si no es quien hizo el cambio. */
    fun requesterRecipient(order: Order, byUserId: UUID?): UUID? = order.requestedBy?.takeIf { it != byUserId }

    // ---- avisos ----

    /** Una orden quedó asignada (pantalla, agente, generador). */
    fun assigned(tenant: Connection, notifier: ModuleNotifier?, order: Order, byUserId: UUID?) {
        val user = assignedRecipient(order.assigneeId, byUserId) ?: return
        send(notifier, tenant, listOf(user), AssignedTitle, text(order), order.id)
    }

    /** Orden recién generada por un plan, con su responsable. */
    fun assignedByPlan(tenant: Connection, notifier: ModuleNotifier?, generated: GeneratedOrder) {
        val user = generated.assigneeId ?: return
        val shown = MantenimientoFolio.format(generated.number) + " · " + generated.title
        send(notifier, tenant, listOf(user), AssignedTitle, shown, generated.orderId)
    }

    /** Se creó una orden: un proyecto avisa a quienes gestionan, una avería sin responsable también; con responsable, a él. */
    fun created(tenant: Connection, notifier: ModuleNotifier?, order: Order, byUserId: UUID?) {
        if (notifier == null) return
        when {
            order.kind == OrderKind.Project -> {
                val managers = runCatching { MantenimientoRepository.managerIds(tenant) }.getOrDefault(emptyList())
                send(notifier, tenant, managerRecipients(managers, byUserId), ProjectRequestedTitle, text(order), order.id)
            }
            order.assigneeId == null && order.kind == OrderKind.Corrective -> {
                val managers = runCatching { MantenimientoRepository.managerIds(tenant) }.getOrDefault(emptyList())
                send(notifier, tenant, managerRecipients(managers, byUserId), BreakdownTitle, text(order), order.id)
            }
            else -> assigned(tenant, notifier, order, byUserId)
        }
    }

    /** Tras un cambio de estado: aprobado y rechazado avisan al solicitante, completada también. */
    fun statusChanged(tenant: Connection, notifier: ModuleNotifier?, before: Order, after: Order, byUserId: UUID?) {
        val requester = requesterRecipient(after, byUserId) ?: return
        val title = when {
            OrderTransitions.isApproval(before.kind, before.status, after.status) -> ProjectApprovedTitle
            after.status == OrderStatus.Rejected && before.status != OrderStatus.Rejected -> ProjectRejectedTitle
            after.status == OrderStatus.Done && before.status != OrderStatus.Done -> CompletedTitle
            else -> return
        }
        val extra = if (after.status == OrderStatus.Rejected && after.rejectedReason.isNotBlank()) " Motivo: ${after.rejectedReason}" else ""
        send(notifier, tenant, listOf(requester), title, (text(after) + extra).take(1000), after.id)
    }

    /**
     * Cuerpo del trabajo: las órdenes con responsable que vencen pronto o ya vencieron avisan una vez por fecha (se reclama primero la
     * fila del recordatorio y solo avisa quien la insertó). Devuelve cuántos avisos se crearon.
     */
    fun dueReminders(tenant: Connection, notifier: ModuleNotifier?, today: LocalDate): Int {
        if (notifier == null) return 0
        val days = MantenimientoRepository.settings(tenant).dueSoonDays
        var sent = 0
        MantenimientoRepository.reminderCandidates(tenant, today, days, 200).forEach { candidate ->
            // El aviso usa la misma conexión: si falla, el reclamo se deshace y se reintenta en la siguiente ejecución.
            safely("un aviso de vencimiento") {
                MantenimientoRepository.transaction(tenant) {
                    if (MantenimientoRepository.claimReminder(tenant, candidate.orderId, candidate.userId, candidate.kind, candidate.dueDate)) {
                        val overdue = candidate.kind == "overdue"
                        notifier.notify(
                            tenant,
                            candidate.userId,
                            if (overdue) OverdueTitle else DueSoonTitle,
                            "${MantenimientoFolio.format(candidate.number)} · ${candidate.title} · vence el ${candidate.dueDate}",
                            target(candidate.orderId),
                        )
                        sent++
                    }
                }
            }
        }
        return sent
    }

    /** Ejecuta [block] sin que un fallo del aviso rompa el cambio: se registra. Interrupciones y cancelaciones se relanzan. */
    private fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (stop: InterruptedException) {
            Thread.currentThread().interrupt()
            throw stop
        } catch (stop: java.util.concurrent.CancellationException) {
            throw stop
        } catch (failure: Exception) {
            log.warn("No se pudo crear {} de mantenimiento ({}).", what, failure.javaClass.simpleName)
        }
    }

    private fun send(notifier: ModuleNotifier?, tenant: Connection, users: Collection<UUID>, title: String, text: String, orderId: UUID) {
        if (notifier == null || users.isEmpty()) return
        users.forEach { userId -> safely("el aviso \"$title\"") { notifier.notify(tenant, userId, title, text.take(1000), target(orderId)) } }
    }
}
