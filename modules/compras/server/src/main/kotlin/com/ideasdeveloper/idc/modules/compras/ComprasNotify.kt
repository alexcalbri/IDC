package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import com.ideasdeveloper.idc.server.modules.NotificationTarget
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Avisos del modulo (centro de notificaciones). Ninguno hace fallar la accion: cada envio va en try/catch y
 * se registra el fallo. Con [notifier] nulo no se envia nada. Bloqueante (JDBC): usar en withContext(Dispatchers.IO).
 */
object ComprasNotify {
    const val SubmittedTitlePrefix = "Compra por aprobar: "
    const val ApprovedTitlePrefix = "Compra aprobada: "
    const val RejectedTitlePrefix = "Compra rechazada: "
    const val CompletedTitlePrefix = "Compra completada: "
    private val log = LoggerFactory.getLogger(ComprasNotify::class.java)

    private fun target(request: PurchaseRequest) = NotificationTarget("request", mapOf("id" to request.id.toString()))

    private fun amountText(request: PurchaseRequest): String =
        request.approvalAmount?.let { ComprasMoney.format(it, request.approvalCurrency) } ?: "sin monto"

    private fun title(prefix: String, request: PurchaseRequest) = prefix + request.title.take(120)

    private fun send(tenant: Connection, notifier: ModuleNotifier?, userId: UUID, title: String, text: String, request: PurchaseRequest): Boolean {
        if (notifier == null) return false
        return try {
            notifier.notify(tenant, userId, title, text.take(500), target(request))
            true
        } catch (failure: Exception) {
            log.warn("No se pudo crear el aviso de compras ({}).", failure.javaClass.simpleName)
            false
        }
    }

    /**
     * Avisa de la transicion ya hecha: a pending_approval (a los aprobadores), approved/rejected (al solicitante)
     * y completed (al solicitante). Otros destinos no avisan. Nunca falla.
     */
    fun afterTransition(tenant: Connection, notifier: ModuleNotifier?, result: TransitionResult, actor: Actor) {
        try {
            when (result.request.status) {
                RequestStatus.PendingApproval -> submitted(tenant, notifier, result.request)
                RequestStatus.Approved -> decided(tenant, notifier, result.request, actor, approved = true)
                RequestStatus.Rejected -> decided(tenant, notifier, result.request, actor, approved = false)
                RequestStatus.Completed -> completed(tenant, notifier, result.request, actor)
                else -> Unit
            }
        } catch (failure: Exception) {
            log.warn("No se pudo avisar el cambio de una solicitud de compra ({}).", failure.javaClass.simpleName)
        }
    }

    /**
     * La solicitud paso a pendiente de aprobacion: avisa a cada aprobador que cubre el monto (menos al solicitante);
     * si ninguno lo cubre, a los aprobadores sin limite. Devuelve cuantos avisos se crearon (0 = nadie cubre el
     * monto: la pantalla muestra "Ningun aprobador cubre este monto").
     */
    fun submitted(tenant: Connection, notifier: ModuleNotifier?, request: PurchaseRequest): Int {
        if (notifier == null) return 0
        return try {
            val amount = request.approvalAmount ?: return 0
            var recipients = ComprasApproval.eligibleApprovers(tenant, amount, request.approvalCurrency).filter { it != request.requesterId }
            if (recipients.isEmpty()) recipients = ComprasApproval.unlimitedApprovers(tenant).filter { it != request.requesterId }
            val department = request.departmentName?.let { " Departamento: $it." }.orEmpty()
            val text = "${request.requesterName} pide ${amountText(request)}.$department"
            recipients.count { send(tenant, notifier, it, title(SubmittedTitlePrefix, request), text, request) }
        } catch (failure: Exception) {
            log.warn("No se pudo avisar a los aprobadores de compras ({}).", failure.javaClass.simpleName)
            0
        }
    }

    /** Aprobada o rechazada: al solicitante, si no fue el quien decidio. */
    fun decided(tenant: Connection, notifier: ModuleNotifier?, request: PurchaseRequest, actor: Actor, approved: Boolean) {
        if (actor.isUser(request.requesterId)) return
        val prefix = if (approved) ApprovedTitlePrefix else RejectedTitlePrefix
        val reason = request.decisionReason?.takeIf { it.isNotBlank() }?.let { " Motivo: $it" }.orEmpty()
        val text = "${request.folio}: ${amountText(request)}.${actor.name.let { " Por $it." }}$reason"
        send(tenant, notifier, request.requesterId, title(prefix, request), text, request)
    }

    /** Completada: al solicitante, si la completo otra persona. */
    fun completed(tenant: Connection, notifier: ModuleNotifier?, request: PurchaseRequest, actor: Actor) {
        if (actor.isUser(request.requesterId)) return
        send(tenant, notifier, request.requesterId, title(CompletedTitlePrefix, request), "${request.folio}: ${amountText(request)}. Completada por ${actor.name}.", request)
    }
}
