package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.util.UUID

/**
 * Lo que el usuario puede hacer, calculado una vez por petición. [canManage] incluye al dueño del negocio y al del servidor.
 * Las mismas reglas se aplican en SQL en [MantenimientoRepository.listOrders] (pantallas, agentes, sugerencias e historial).
 */
data class MantenimientoScope(val userId: UUID?, val canExecute: Boolean, val canManage: Boolean) {
    /** Ve todas las órdenes; el resto solo las que reportó o tiene asignadas. */
    val seesAll: Boolean get() = canExecute || canManage

    fun seesOrder(order: Order): Boolean = seesOrder(order.requestedBy, order.assigneeId)

    fun seesOrder(requestedBy: UUID?, assigneeId: UUID?): Boolean =
        seesAll || (userId != null && (requestedBy == userId || assigneeId == userId))

    /** Cambiar estado de trabajo, lista, notas, adjuntos, costo real y resolución. */
    fun canWork(order: Order): Boolean = canWork(order.assigneeId)

    fun canWork(assigneeId: UUID?): Boolean = canManage || (canExecute && (assigneeId == null || (userId != null && assigneeId == userId)))

    /**
     * Crear, editar, asignar, cancelar y decidir proyectos. Hoy depende solo del permiso; [order] es el punto de extensión para reglas
     * por orden (todas las pantallas, rutas y herramientas ya lo llaman con la orden).
     */
    fun canEdit(@Suppress("UNUSED_PARAMETER") order: Order): Boolean = canManage

    /** Tomar la orden: ejecutar sin responsable y con un usuario de la empresa (el dueño del servidor no lo es). */
    fun canTake(order: Order): Boolean = userId != null && order.assigneeId == null && order.status.isWorking && (canExecute || canManage)

    companion object {
        fun from(permissions: ModulePermissions): MantenimientoScope {
            val owner = permissions.serverOwner || permissions.businessOwner
            return MantenimientoScope(
                // El dueño del servidor es un usuario central: nunca es responsable ni solicitante de una orden de la empresa.
                userId = permissions.userId?.takeUnless { permissions.serverOwner },
                canExecute = owner || permissions.has(MantenimientoModule.Execute),
                canManage = owner || permissions.has(MantenimientoModule.Manage),
            )
        }
    }
}

/** Quién pide el cambio de estado: [canWork] ya incluye a quien gestiona. */
data class TransitionRole(val canWork: Boolean, val canManage: Boolean) {
    companion object {
        fun of(scope: MantenimientoScope, order: Order) = TransitionRole(scope.canWork(order), scope.canManage)
    }
}

/** Transiciones de estado permitidas, puras. Se comprueban en pantallas, tablero y agentes con la misma función. */
object OrderTransitions {
    private val ProjectOnly = setOf(OrderStatus.Requested, OrderStatus.Quoting, OrderStatus.Rejected)

    private val FromRequested = setOf(OrderStatus.Quoting, OrderStatus.Open, OrderStatus.Rejected, OrderStatus.Cancelled)
    private val FromQuoting = setOf(OrderStatus.Open, OrderStatus.Rejected, OrderStatus.Requested, OrderStatus.Cancelled)
    private val WorkTargets = setOf(OrderStatus.Open, OrderStatus.InProgress, OrderStatus.OnHold, OrderStatus.Done)

    fun allowed(kind: OrderKind, from: OrderStatus, to: OrderStatus, role: TransitionRole): Boolean {
        if (from == to) return false
        if (kind != OrderKind.Project && (from in ProjectOnly || to in ProjectOnly)) return false
        return when (from) {
            OrderStatus.Requested -> role.canManage && to in FromRequested
            OrderStatus.Quoting -> role.canManage && to in FromQuoting
            OrderStatus.Open, OrderStatus.InProgress, OrderStatus.OnHold -> when (to) {
                in WorkTargets -> role.canWork
                OrderStatus.Cancelled -> role.canManage
                else -> false
            }
            // Reabrir una orden completada.
            OrderStatus.Done -> to == OrderStatus.InProgress && role.canManage
            OrderStatus.Rejected, OrderStatus.Cancelled -> false
        }
    }

    /** Los estados a los que la orden puede pasar, en el orden del flujo (para los botones y el tablero). */
    fun targets(kind: OrderKind, from: OrderStatus, role: TransitionRole): List<OrderStatus> =
        OrderStatus.entries.filter { allowed(kind, from, it, role) }

    /** Aprobar un proyecto: sale de las etapas de decisión hacia Abierta. */
    fun isApproval(kind: OrderKind, from: OrderStatus, to: OrderStatus): Boolean =
        kind == OrderKind.Project && (from == OrderStatus.Requested || from == OrderStatus.Quoting) && to == OrderStatus.Open

    /** Rechazar exige un motivo. */
    fun requiresReason(to: OrderStatus): Boolean = to == OrderStatus.Rejected

    const val MaxReason = 1000

    /** El estado inicial: los proyectos esperan aprobación, el resto nace abierto. */
    fun initial(kind: OrderKind): OrderStatus = if (kind == OrderKind.Project) OrderStatus.Requested else OrderStatus.Open
}
