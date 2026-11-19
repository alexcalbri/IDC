package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.modules.compras.ComprasTestSupport.actor
import com.ideasdeveloper.idc.modules.compras.ComprasTestSupport.approver
import com.ideasdeveloper.idc.modules.compras.RequestStatus.Approved
import com.ideasdeveloper.idc.modules.compras.RequestStatus.Cancelled
import com.ideasdeveloper.idc.modules.compras.RequestStatus.Completed
import com.ideasdeveloper.idc.modules.compras.RequestStatus.Draft
import com.ideasdeveloper.idc.modules.compras.RequestStatus.InProgress
import com.ideasdeveloper.idc.modules.compras.RequestStatus.PendingApproval
import com.ideasdeveloper.idc.modules.compras.RequestStatus.Quoting
import com.ideasdeveloper.idc.modules.compras.RequestStatus.Rejected
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComprasFlowTest {
    private val requesterId = UUID.randomUUID()
    private val deciderId = UUID.randomUUID()
    private val requester = actor(requesterId, ComprasModule.View, ComprasModule.Create)
    private val stranger = actor(UUID.randomUUID(), ComprasModule.View, ComprasModule.Create)
    private val manager = actor(UUID.randomUUID(), ComprasModule.View, ComprasModule.Manage)

    private fun context(approver: Approver? = null, amount: String? = "500.00", decidedBy: UUID? = null) =
        FlowContext(requesterId, decidedBy, approver, amount?.let { BigDecimal(it) }, "USD")

    @Test
    fun requesterMovesDraftAndQuotingButStrangerCannot() {
        listOf(Quoting, PendingApproval, Cancelled).forEach { assertTrue(ComprasFlow.canMove(Draft, it, requester, context())) }
        listOf(Draft, PendingApproval, Cancelled).forEach { assertTrue(ComprasFlow.canMove(Quoting, it, requester, context())) }
        assertFalse(ComprasFlow.canMove(Draft, PendingApproval, stranger, context()))
        assertTrue(ComprasFlow.canMove(Draft, PendingApproval, manager, context()))
    }

    @Test
    fun unlistedTransitionsAreRefused() {
        assertFalse(ComprasFlow.allowed(Draft, Approved))
        assertFalse(ComprasFlow.allowed(Completed, Draft))
        assertFalse(ComprasFlow.allowed(Cancelled, Draft))
        assertFalse(ComprasFlow.allowed(Approved, Draft))
        assertFalse(ComprasFlow.canMove(Draft, Approved, manager, context()))
        assertFalse(ComprasFlow.canMove(Approved, Completed, manager, context()))
    }

    @Test
    fun approvingFollowsTheLimit() {
        val martin = actor(UUID.randomUUID(), ComprasModule.Approve)
        val within = context(approver(martin.userId!!, "1000"), amount = "800")
        val above = context(approver(martin.userId!!, "1000"), amount = "1500")
        assertTrue(ComprasFlow.canMove(PendingApproval, Approved, martin, within))
        assertFalse(ComprasFlow.canMove(PendingApproval, Approved, martin, above))
        // Rechazar se permite a cualquier aprobador activo, sin importar el monto.
        assertTrue(ComprasFlow.canMove(PendingApproval, Rejected, martin, above))
    }

    @Test
    fun selfApprovalIsAllowedWithinTheLimit() {
        val both = actor(requesterId, ComprasModule.Create, ComprasModule.Approve)
        assertTrue(ComprasFlow.canMove(PendingApproval, Approved, both, context(approver(requesterId, "1000"))))
        assertFalse(ComprasFlow.canMove(PendingApproval, Approved, both, context(approver(requesterId, "100"))))
    }

    @Test
    fun withdrawAndReopenBelongToRequesterOrManager() {
        assertTrue(ComprasFlow.canMove(PendingApproval, Draft, requester, context()))
        assertTrue(ComprasFlow.canMove(Rejected, Draft, requester, context()))
        assertTrue(ComprasFlow.canMove(Rejected, Draft, manager, context()))
        assertFalse(ComprasFlow.canMove(Rejected, Draft, stranger, context()))
    }

    @Test
    fun afterApprovalRequesterDeciderOrManagerContinue() {
        val decider = actor(deciderId, ComprasModule.Approve)
        val ctx = context(decidedBy = deciderId)
        assertTrue(ComprasFlow.canMove(Approved, InProgress, requester, ctx))
        assertTrue(ComprasFlow.canMove(Approved, InProgress, decider, ctx))
        assertTrue(ComprasFlow.canMove(InProgress, Completed, manager, ctx))
        assertFalse(ComprasFlow.canMove(Approved, InProgress, stranger, ctx))
        assertFalse(ComprasFlow.canMove(InProgress, Completed, stranger, ctx))
    }

    @Test
    fun targetsListOnlyValidButtons() {
        assertEquals(listOf(Draft), ComprasFlow.targets(Rejected, requester, context()))
        assertEquals(emptyList(), ComprasFlow.targets(Rejected, stranger, context()))
        assertEquals(listOf(Quoting, PendingApproval, Cancelled), ComprasFlow.targets(Draft, requester, context()))
        assertEquals(emptyList(), ComprasFlow.targets(Completed, manager, context()))
    }

    @Test
    fun validateRequiresReasonToRejectAndAmountToSubmit() {
        val martin = actor(UUID.randomUUID(), ComprasModule.Approve)
        val ctx = context(approver(martin.userId!!, null))
        assertFailsWith<ComprasValidationException> { ComprasFlow.validate(PendingApproval, Rejected, martin, ctx, "  ", "T") }
        ComprasFlow.validate(PendingApproval, Rejected, martin, ctx, "Muy caro", "T")
        assertFailsWith<ComprasValidationException> { ComprasFlow.validate(Draft, PendingApproval, requester, context(amount = null), null, "T") }
        assertFailsWith<ComprasValidationException> { ComprasFlow.validate(Draft, PendingApproval, requester, context(amount = "0"), null, "T") }
        assertFailsWith<ComprasValidationException> { ComprasFlow.validate(Draft, PendingApproval, requester, context(), null, " ") }
        ComprasFlow.validate(Draft, PendingApproval, requester, context(), null, "Sabanas")
    }

    @Test
    fun validateGivesClearMessages() {
        val error = assertFailsWith<ComprasValidationException> { ComprasFlow.validate(Completed, Draft, manager, context(), null, "T") }
        assertTrue(error.message!!.contains("Completada"))
        assertFailsWith<ComprasValidationException> { ComprasFlow.validate(Draft, Quoting, stranger, context(), null, "T") }
    }

    @Test
    fun editRulesByStatus() {
        assertTrue(ComprasFlow.canEdit(Draft, requester, requesterId))
        assertTrue(ComprasFlow.canEdit(Quoting, requester, requesterId))
        assertFalse(ComprasFlow.canEdit(Draft, stranger, requesterId))
        assertFalse(ComprasFlow.canEdit(PendingApproval, requester, requesterId))
        assertFalse(ComprasFlow.canEdit(Approved, manager, requesterId))
        assertFalse(ComprasFlow.canEdit(PendingApproval, manager, requesterId))
        assertTrue(ComprasFlow.canEditText(Approved, manager))
        assertFalse(ComprasFlow.canEditText(Approved, requester))
        assertFalse(ComprasFlow.canEditText(Completed, manager))
        assertFalse(ComprasFlow.canEdit(Completed, manager, requesterId))
        assertFalse(ComprasFlow.canEdit(Cancelled, manager, requesterId))
    }

    @Test
    fun attachmentRules() {
        val ctx = context(decidedBy = deciderId)
        assertTrue(ComprasFlow.canAddAttachment(InProgress, requester, ctx))
        assertTrue(ComprasFlow.canAddAttachment(Completed, manager, ctx))
        assertFalse(ComprasFlow.canAddAttachment(Cancelled, requester, ctx))
        assertFalse(ComprasFlow.canAddAttachment(Draft, stranger, ctx))
        assertTrue(ComprasFlow.canRemoveAttachment(Draft, requester, requesterId))
        assertFalse(ComprasFlow.canRemoveAttachment(Approved, requester, requesterId))
        assertTrue(ComprasFlow.canRemoveAttachment(Approved, manager, requesterId))
    }
}
