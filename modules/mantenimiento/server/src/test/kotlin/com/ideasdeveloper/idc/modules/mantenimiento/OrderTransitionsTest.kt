package com.ideasdeveloper.idc.modules.mantenimiento

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Transiciones de estado de las órdenes (`OrderTransitions`), puras. */
class OrderTransitionsTest {
    private val manager = TransitionRole(canWork = true, canManage = true)
    private val worker = TransitionRole(canWork = true, canManage = false)
    private val reader = TransitionRole(canWork = false, canManage = false)

    private fun allowed(kind: OrderKind, from: OrderStatus, to: OrderStatus, role: TransitionRole) = OrderTransitions.allowed(kind, from, to, role)

    @Test
    fun projectDecisionsAreForManagersOnly() {
        listOf(OrderStatus.Quoting, OrderStatus.Open, OrderStatus.Rejected).forEach { to ->
            assertTrue(allowed(OrderKind.Project, OrderStatus.Requested, to, manager), "requested -> $to")
            assertFalse(allowed(OrderKind.Project, OrderStatus.Requested, to, worker), "requested -> $to como ejecutor")
        }
        assertTrue(allowed(OrderKind.Project, OrderStatus.Quoting, OrderStatus.Open, manager))
        assertTrue(allowed(OrderKind.Project, OrderStatus.Quoting, OrderStatus.Rejected, manager))
        assertTrue(allowed(OrderKind.Project, OrderStatus.Quoting, OrderStatus.Requested, manager))
        assertFalse(allowed(OrderKind.Project, OrderStatus.Quoting, OrderStatus.Open, worker))
    }

    @Test
    fun projectCannotSkipApprovalToWorkStates() {
        assertFalse(allowed(OrderKind.Project, OrderStatus.Requested, OrderStatus.InProgress, manager))
        assertFalse(allowed(OrderKind.Project, OrderStatus.Requested, OrderStatus.Done, manager))
        assertFalse(allowed(OrderKind.Project, OrderStatus.Quoting, OrderStatus.Done, manager))
    }

    @Test
    fun workStatesMoveAmongThemselvesAndToDoneForWhoWorks() {
        val states = listOf(OrderStatus.Open, OrderStatus.InProgress, OrderStatus.OnHold)
        for (from in states) {
            for (to in states + OrderStatus.Done) {
                if (from == to) continue
                assertTrue(allowed(OrderKind.Corrective, from, to, worker), "$from -> $to")
                assertFalse(allowed(OrderKind.Corrective, from, to, reader), "$from -> $to sin permiso")
            }
        }
    }

    @Test
    fun cancelAndReopenAreForManagers() {
        assertTrue(allowed(OrderKind.Corrective, OrderStatus.InProgress, OrderStatus.Cancelled, manager))
        assertFalse(allowed(OrderKind.Corrective, OrderStatus.InProgress, OrderStatus.Cancelled, worker))
        assertTrue(allowed(OrderKind.Corrective, OrderStatus.Done, OrderStatus.InProgress, manager))
        assertFalse(allowed(OrderKind.Corrective, OrderStatus.Done, OrderStatus.InProgress, worker))
        assertFalse(allowed(OrderKind.Corrective, OrderStatus.Done, OrderStatus.Open, manager))
    }

    @Test
    fun correctiveAndPreventiveNeverUseProjectStates() {
        listOf(OrderKind.Corrective, OrderKind.Preventive).forEach { kind ->
            listOf(OrderStatus.Requested, OrderStatus.Quoting, OrderStatus.Rejected).forEach { state ->
                assertFalse(allowed(kind, OrderStatus.Open, state, manager), "$kind open -> $state")
                assertFalse(allowed(kind, state, OrderStatus.Open, manager), "$kind $state -> open")
            }
        }
    }

    @Test
    fun closedFinalStatesHaveNoExit() {
        OrderStatus.entries.forEach { to ->
            assertFalse(allowed(OrderKind.Project, OrderStatus.Rejected, to, manager), "rejected -> $to")
            assertFalse(allowed(OrderKind.Project, OrderStatus.Cancelled, to, manager), "cancelled -> $to")
        }
    }

    @Test
    fun sameStateIsNotATransition() {
        OrderStatus.entries.forEach { state -> assertFalse(allowed(OrderKind.Project, state, state, manager)) }
    }

    @Test
    fun targetsListTheButtonsInFlowOrder() {
        assertEquals(
            listOf(OrderStatus.Open, OrderStatus.Rejected, OrderStatus.Quoting, OrderStatus.Cancelled).sortedBy { it.ordinal },
            OrderTransitions.targets(OrderKind.Project, OrderStatus.Requested, manager),
        )
        assertEquals(listOf(OrderStatus.InProgress, OrderStatus.OnHold, OrderStatus.Done), OrderTransitions.targets(OrderKind.Corrective, OrderStatus.Open, worker))
        assertTrue(OrderTransitions.targets(OrderKind.Corrective, OrderStatus.Open, reader).isEmpty())
    }

    @Test
    fun approvalRejectionAndInitialState() {
        assertTrue(OrderTransitions.isApproval(OrderKind.Project, OrderStatus.Requested, OrderStatus.Open))
        assertTrue(OrderTransitions.isApproval(OrderKind.Project, OrderStatus.Quoting, OrderStatus.Open))
        assertFalse(OrderTransitions.isApproval(OrderKind.Corrective, OrderStatus.Requested, OrderStatus.Open))
        assertFalse(OrderTransitions.isApproval(OrderKind.Project, OrderStatus.OnHold, OrderStatus.Open))
        assertTrue(OrderTransitions.requiresReason(OrderStatus.Rejected))
        assertFalse(OrderTransitions.requiresReason(OrderStatus.Cancelled))
        assertEquals(OrderStatus.Requested, OrderTransitions.initial(OrderKind.Project))
        assertEquals(OrderStatus.Open, OrderTransitions.initial(OrderKind.Corrective))
        assertEquals(OrderStatus.Open, OrderTransitions.initial(OrderKind.Preventive))
    }

    @Test
    fun roleIsDerivedFromTheScopeAndTheOrder() {
        val order = MantenimientoFixtures.order(assigneeId = java.util.UUID.randomUUID())
        val executor = MantenimientoScope(java.util.UUID.randomUUID(), canExecute = true, canManage = false)
        assertEquals(TransitionRole(canWork = false, canManage = false), TransitionRole.of(executor, order))
        val manager = MantenimientoScope(java.util.UUID.randomUUID(), canExecute = false, canManage = true)
        assertEquals(TransitionRole(canWork = true, canManage = true), TransitionRole.of(manager, order))
    }
}
