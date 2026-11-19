package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.modules.compras.ComprasTestSupport.actor
import com.ideasdeveloper.idc.modules.compras.ComprasTestSupport.approver
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComprasApprovalTest {
    private val martin = UUID.randomUUID()
    private val approve = ComprasModule.Approve

    @Test
    fun limitCoversAmountsUpToTheMaximumInTheSameCurrency() {
        val user = actor(martin, approve)
        val row = approver(martin, "1000")
        assertTrue(ComprasApproval.canApprove(user, row, BigDecimal("1000.00"), "USD"))
        assertTrue(ComprasApproval.canApprove(user, row, BigDecimal("999.99"), "USD"))
        assertFalse(ComprasApproval.canApprove(user, row, BigDecimal("1000.01"), "USD"))
    }

    @Test
    fun otherCurrencyIsNotCoveredByALimit() {
        val user = actor(martin, approve)
        assertFalse(ComprasApproval.canApprove(user, approver(martin, "1000", "USD"), BigDecimal("10"), "EUR"))
    }

    @Test
    fun noLimitApprovesAnyAmountAndCurrency() {
        val user = actor(martin, approve)
        val row = approver(martin, null)
        assertTrue(ComprasApproval.canApprove(user, row, BigDecimal("999999999.00"), "EUR"))
    }

    @Test
    fun needsPermissionAndAnActiveRow() {
        assertFalse(ComprasApproval.canApprove(actor(martin), approver(martin, null), BigDecimal.TEN, "USD"))
        assertFalse(ComprasApproval.canApprove(actor(martin, approve), null, BigDecimal.TEN, "USD"))
        assertFalse(ComprasApproval.canApprove(actor(martin, approve), approver(martin, null, active = false), BigDecimal.TEN, "USD"))
    }

    @Test
    fun ownersApproveWithoutRowOrLimit() {
        assertTrue(ComprasApproval.canApprove(actor(martin, owner = true), null, BigDecimal("1000000"), "USD"))
        assertTrue(ComprasApproval.canApprove(actor(martin, serverOwner = true), null, BigDecimal("1000000"), "USD"))
    }

    @Test
    fun zeroOrMissingAmountCannotBeApproved() {
        val owner = actor(martin, owner = true)
        assertFalse(ComprasApproval.canApprove(owner, null, null, "USD"))
        assertFalse(ComprasApproval.canApprove(owner, null, BigDecimal.ZERO, "USD"))
    }

    @Test
    fun rejectingNeedsApproverRowButAnyLimit() {
        val user = actor(martin, approve)
        assertTrue(ComprasApproval.canReject(user, approver(martin, "10")))
        assertFalse(ComprasApproval.canReject(user, null))
        assertFalse(ComprasApproval.canReject(actor(martin), approver(martin, "10")))
        assertTrue(ComprasApproval.canReject(actor(martin, owner = true), null))
    }
}
