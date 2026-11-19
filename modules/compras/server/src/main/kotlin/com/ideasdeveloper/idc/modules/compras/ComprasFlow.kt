package com.ideasdeveloper.idc.modules.compras

import java.math.BigDecimal
import java.util.UUID

/**
 * Lo que la regla de transicion necesita saber de una solicitud: quien la pidio, quien decidio, la fila
 * de aprobador del actor y el monto a aprobar.
 */
data class FlowContext(
    val requesterId: UUID?,
    val decidedBy: UUID?,
    val approver: Approver?,
    val amount: BigDecimal?,
    val currency: String,
)

/** Estados a los que se puede mover una solicitud y quien puede hacerlo. Todo puro: sin base de datos. */
object ComprasFlow {
    private val Transitions: Map<RequestStatus, Set<RequestStatus>> = mapOf(
        RequestStatus.Draft to setOf(RequestStatus.Quoting, RequestStatus.PendingApproval, RequestStatus.Cancelled),
        RequestStatus.Quoting to setOf(RequestStatus.Draft, RequestStatus.PendingApproval, RequestStatus.Cancelled),
        RequestStatus.PendingApproval to setOf(RequestStatus.Approved, RequestStatus.Rejected, RequestStatus.Draft),
        RequestStatus.Rejected to setOf(RequestStatus.Draft),
        RequestStatus.Approved to setOf(RequestStatus.InProgress, RequestStatus.Cancelled),
        RequestStatus.InProgress to setOf(RequestStatus.Completed),
    )

    /** El contexto de [request] para el actor cuya fila de aprobador es [approver]. */
    fun contextOf(request: PurchaseRequest, approver: Approver?) =
        FlowContext(request.requesterId, request.decidedBy, approver, request.approvalAmount, request.approvalCurrency)

    /** La transicion existe en el flujo (sin mirar permisos). */
    fun allowed(from: RequestStatus, to: RequestStatus): Boolean = to in Transitions[from].orEmpty()

    /** [actor] puede mover de [from] a [to]: la transicion existe y el actor cumple la regla de esa transicion. */
    fun canMove(from: RequestStatus, to: RequestStatus, actor: Actor, context: FlowContext): Boolean {
        if (!allowed(from, to)) return false
        val requester = actor.isUser(context.requesterId)
        val manage = actor.can(ComprasModule.Manage)
        return when (from to to) {
            RequestStatus.PendingApproval to RequestStatus.Approved ->
                ComprasApproval.canApprove(actor, context.approver, context.amount, context.currency)
            RequestStatus.PendingApproval to RequestStatus.Rejected -> ComprasApproval.canReject(actor, context.approver)
            RequestStatus.Approved to RequestStatus.InProgress,
            RequestStatus.Approved to RequestStatus.Cancelled,
            RequestStatus.InProgress to RequestStatus.Completed,
            -> requester || actor.isUser(context.decidedBy) || manage
            else -> requester || manage
        }
    }

    /** Destinos validos para [actor] desde [from], en el orden del flujo (los botones del detalle). */
    fun targets(from: RequestStatus, actor: Actor, context: FlowContext): List<RequestStatus> =
        Transitions[from].orEmpty().filter { canMove(from, it, actor, context) }.sortedBy { it.ordinal }

    /**
     * Comprueba una transicion completa: flujo, permiso, motivo (obligatorio al rechazar), y titulo y monto
     * (> 0) para pedir aprobacion. Lanza [ComprasValidationException] con un mensaje claro.
     */
    fun validate(from: RequestStatus, to: RequestStatus, actor: Actor, context: FlowContext, reason: String?, title: String) {
        if (!allowed(from, to)) throw ComprasValidationException("No se puede pasar de ${from.label} a ${to.label}.")
        if (!canMove(from, to, actor, context)) throw ComprasValidationException(denial(from, to, context))
        if (to == RequestStatus.Rejected && reason.isNullOrBlank()) throw ComprasValidationException("El motivo del rechazo es obligatorio.")
        if (to == RequestStatus.PendingApproval) {
            if (title.isBlank()) throw ComprasValidationException("La solicitud necesita un titulo.")
            val amount = context.amount
            if (amount == null || amount.signum() <= 0) {
                throw ComprasValidationException("Indica un monto mayor a cero (estimado o de una cotizacion elegida) antes de pedir aprobacion.")
            }
        }
    }

    private fun denial(from: RequestStatus, to: RequestStatus, context: FlowContext): String = when {
        from == RequestStatus.PendingApproval && to == RequestStatus.Approved ->
            if (context.approver?.maxAmount != null) "Tu limite de aprobacion no cubre este monto." else "No puedes aprobar esta solicitud."
        from == RequestStatus.PendingApproval && to == RequestStatus.Rejected -> "No puedes rechazar esta solicitud."
        else -> "No tienes permiso para pasar la solicitud a ${to.label}."
    }

    private fun ownsOrManages(actor: Actor, requesterId: UUID?) = actor.isUser(requesterId) || actor.can(ComprasModule.Manage)

    /**
     * Editar monto, moneda y cotizaciones (incluida la elegida): SOLO en borrador o cotizacion, el solicitante o manage.
     * Fuera de esos estados nadie los cambia (ni manage): para corregir, se retira la solicitud a borrador.
     */
    fun canEdit(status: RequestStatus, actor: Actor, requesterId: UUID?): Boolean =
        (status == RequestStatus.Draft || status == RequestStatus.Quoting) && ownsOrManages(actor, requesterId)

    /** Editar solo texto, departamento, fecha y prioridad fuera de borrador/cotizacion: manage mientras no sea terminal. */
    fun canEditText(status: RequestStatus, actor: Actor): Boolean = actor.can(ComprasModule.Manage) && !status.terminal

    /** Anadir adjuntos: en cualquier estado menos cancelada (las facturas llegan despues de comprar). */
    fun canAddAttachment(status: RequestStatus, actor: Actor, context: FlowContext): Boolean =
        status != RequestStatus.Cancelled && (ownsOrManages(actor, context.requesterId) || actor.isUser(context.decidedBy))

    /** Quitar adjuntos: solo en borrador o cotizacion (solicitante) o con manage. */
    fun canRemoveAttachment(status: RequestStatus, actor: Actor, requesterId: UUID?): Boolean =
        actor.can(ComprasModule.Manage) || ((status == RequestStatus.Draft || status == RequestStatus.Quoting) && actor.isUser(requesterId))
}
