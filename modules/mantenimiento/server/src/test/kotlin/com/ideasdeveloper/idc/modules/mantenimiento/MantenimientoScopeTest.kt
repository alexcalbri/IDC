package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoFixtures.order
import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoFixtures.permissions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Visibilidad y permisos de las órdenes (`MantenimientoScope`), para cada tipo de usuario. */
class MantenimientoScopeTest {
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun scope(execute: Boolean = false, manage: Boolean = false, userId: UUID? = me) = MantenimientoScope(userId, execute, manage)

    @Test
    fun viewerSeesOnlyOrdersHeReportedOrIsAssigned() {
        val viewer = scope()
        assertTrue(viewer.seesOrder(order(requestedBy = me)))
        assertTrue(viewer.seesOrder(order(assigneeId = me)))
        assertFalse(viewer.seesOrder(order(requestedBy = other, assigneeId = other)))
        assertFalse(viewer.seesOrder(order()))
    }

    @Test
    fun executorAndManagerSeeEveryOrder() {
        val stranger = order(requestedBy = other, assigneeId = other)
        assertTrue(scope(execute = true).seesOrder(stranger))
        assertTrue(scope(manage = true).seesOrder(stranger))
    }

    @Test
    fun withoutUserNothingMatchesByNullEquality() {
        // Un dueño del servidor no tiene id de usuario: una orden sin solicitante ni responsable no es suya.
        val central = scope(userId = null)
        assertFalse(central.seesOrder(order(requestedBy = null, assigneeId = null)))
    }

    @Test
    fun executorWorksOnlyOnOrdersAssignedToHimOrWithoutOwner() {
        val executor = scope(execute = true)
        assertTrue(executor.canWork(order(assigneeId = null)))
        assertTrue(executor.canWork(order(assigneeId = me)))
        assertFalse(executor.canWork(order(assigneeId = other)))
    }

    @Test
    fun managerWorksOnAnyOrderAndOnlyManagerEdits() {
        val manager = scope(manage = true)
        assertTrue(manager.canWork(order(assigneeId = other)))
        assertTrue(manager.canEdit(order()))
        assertFalse(scope(execute = true).canEdit(order()))
        assertFalse(scope().canWork(order(assigneeId = me)))
    }

    @Test
    fun takeNeedsAUserAnOpenOrderWithoutOwnerAndExecutePermission() {
        val executor = scope(execute = true)
        assertTrue(executor.canTake(order(assigneeId = null)))
        assertFalse(executor.canTake(order(assigneeId = other)))
        assertFalse(executor.canTake(order(status = OrderStatus.Done)))
        assertFalse(scope().canTake(order()))
        assertFalse(scope(execute = true, userId = null).canTake(order()))
    }

    @Test
    fun fromPermissionsGivesOwnersEverythingAndKeepsServerOwnerWithoutUserId() {
        val businessOwner = MantenimientoScope.from(permissions(me, businessOwner = true))
        assertTrue(businessOwner.canManage && businessOwner.canExecute)
        assertEquals(me, businessOwner.userId)

        val serverOwner = MantenimientoScope.from(permissions(me, serverOwner = true))
        assertTrue(serverOwner.canManage)
        assertNull(serverOwner.userId)

        val executor = MantenimientoScope.from(permissions(me, MantenimientoModule.Execute))
        assertTrue(executor.canExecute)
        assertFalse(executor.canManage)

        val viewer = MantenimientoScope.from(permissions(me, MantenimientoModule.View))
        assertFalse(viewer.seesAll)
    }

    @Test
    fun sqlClauseFollowsTheSameRule() {
        assertEquals("TRUE", MantenimientoRepository.visibleClause(scope(execute = true)).first)
        assertEquals("TRUE", MantenimientoRepository.visibleClause(scope(manage = true)).first)
        val (sql, params) = MantenimientoRepository.visibleClause(scope(), "x")
        assertEquals("(x.requested_by = ? OR x.assignee_id = ?)", sql)
        assertEquals(listOf<Any?>(me, me), params)
        val (none, noParams) = MantenimientoRepository.visibleClause(scope(userId = null))
        assertEquals("FALSE", none)
        assertTrue(noParams.isEmpty())
    }
}
