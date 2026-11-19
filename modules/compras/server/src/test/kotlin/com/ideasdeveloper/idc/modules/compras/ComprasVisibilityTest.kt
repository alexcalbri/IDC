package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.modules.compras.ComprasTestSupport.actor
import com.ideasdeveloper.idc.modules.compras.ComprasTestSupport.approver
import com.ideasdeveloper.idc.modules.compras.ComprasTestSupport.request
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComprasVisibilityTest {
    private val me = UUID.randomUUID()

    private fun placeholders(fragment: SqlFragment) = fragment.sql.count { it == '?' }

    @Test
    fun viewAllManageAndOwnersSeeEverythingWithoutParameters() {
        listOf(
            actor(me, ComprasModule.ViewAll),
            actor(me, ComprasModule.Manage),
            actor(me, owner = true),
            actor(me, serverOwner = true),
        ).forEach { assertEquals(SqlFragment.True, ComprasVisibility.where(it, null)) }
    }

    @Test
    fun plainUserSeesOwnAndDecidedOnly() {
        val fragment = ComprasVisibility.where(actor(me, ComprasModule.View), null)
        assertEquals("(r.requester_id = ? OR r.decided_by = ?)", fragment.sql)
        assertEquals(listOf<Any?>(me, me), fragment.params)
    }

    @Test
    fun userWithoutIdSeesNothing() {
        assertEquals(SqlFragment.False, ComprasVisibility.where(actor(null, ComprasModule.View), null))
    }

    @Test
    fun approverAlsoSeesPendingRequestsWithinTheLimit() {
        val fragment = ComprasVisibility.where(actor(me, ComprasModule.View, ComprasModule.Approve), approver(me, "1000", "USD"))
        assertTrue(fragment.sql.contains("r.status = 'pending_approval'"))
        assertTrue(fragment.sql.contains("<= ?"))
        assertTrue(fragment.sql.contains("compras_quotes"))
        assertEquals(listOf<Any?>(me, me, BigDecimal("1000"), "USD"), fragment.params)
        assertEquals(fragment.params.size, placeholders(fragment))
    }

    @Test
    fun approverWithoutLimitSeesAllPendingWithoutExtraParameters() {
        val fragment = ComprasVisibility.where(actor(me, ComprasModule.Approve), approver(me, null))
        assertTrue(fragment.sql.contains("r.status = 'pending_approval'"))
        assertEquals(listOf<Any?>(me, me), fragment.params)
    }

    @Test
    fun inactiveOrMissingApproverRowAddsNothing() {
        val user = actor(me, ComprasModule.Approve)
        val plain = ComprasVisibility.where(actor(me), null)
        assertEquals(plain, ComprasVisibility.where(user, approver(me, null, active = false)))
        assertEquals(plain, ComprasVisibility.where(user, null))
        assertEquals(SqlFragment.False, ComprasVisibility.approvable(user, null))
    }

    @Test
    fun valuesNeverAppearInTheSql() {
        val department = UUID.randomUUID()
        val fragment = ComprasVisibility.filter(
            RequestFilter(RequestStatus.Approved, Priority.High, department, mine = true, text = "x'; DROP TABLE y;--"),
            actor(me), null,
        )
        assertFalse(fragment.sql.contains("DROP"))
        assertFalse(fragment.sql.contains(department.toString()))
        assertEquals(listOf<Any?>("approved", "high", department, me), fragment.params.take(4))
        assertEquals(fragment.params.size, placeholders(fragment))
    }

    @Test
    fun textSearchEscapesLikeWildcardsAndSearchesFolio() {
        val fragment = ComprasVisibility.filter(RequestFilter(text = "50%_off"), actor(me), null)
        assertEquals(List<Any?>(4) { "%50\\%\\_off%" }, fragment.params)
        assertTrue(fragment.sql.contains("lpad(r.number::text, 5, '0')"))
    }

    @Test
    fun emptyFilterIsTrueAndHighOnlyHasFixedSql() {
        assertEquals(SqlFragment.True, ComprasVisibility.filter(RequestFilter(), actor(me), null))
        val high = ComprasVisibility.filter(RequestFilter(highOnly = true), actor(me), null)
        assertTrue(high.sql.contains("'high', 'critical'"))
        assertTrue(high.params.isEmpty())
    }

    @Test
    fun mineWithoutUserIdMatchesNothing() {
        assertEquals(SqlFragment.False, ComprasVisibility.filter(RequestFilter(mine = true), actor(null), null))
    }

    @Test
    fun pendingMyApprovalUsesTheApprovalLimit() {
        val fragment = ComprasVisibility.filter(RequestFilter(pendingMyApproval = true), actor(me, ComprasModule.Approve), approver(me, "50", "EUR"))
        assertEquals(listOf<Any?>(BigDecimal("50"), "EUR"), fragment.params)
    }

    @Test
    fun aliasMustBeAPlainIdentifier() {
        assertFailsWith<IllegalArgumentException> { ComprasVisibility.where(actor(me), null, "r; DROP TABLE x") }
        assertTrue(ComprasVisibility.where(actor(me), null, "req").sql.contains("req.requester_id"))
    }

    @Test
    fun combinedFragmentsKeepParameterOrder() {
        val combined = ComprasVisibility.where(actor(me), null) and ComprasVisibility.filter(RequestFilter(status = RequestStatus.Draft), actor(me), null)
        assertEquals(listOf<Any?>(me, me, "draft"), combined.params)
        assertEquals(combined.params.size, placeholders(combined))
    }

    @Test
    fun canSeeMatchesTheSqlRule() {
        val owner = UUID.randomUUID()
        val pending = request(requesterId = owner, status = RequestStatus.PendingApproval, amount = "800")
        val approverUser = actor(me, ComprasModule.Approve)
        assertTrue(ComprasVisibility.canSee(approverUser, approver(me, "1000"), pending))
        assertFalse(ComprasVisibility.canSee(approverUser, approver(me, "500"), pending))
        assertFalse(ComprasVisibility.canSee(approverUser, approver(me, "1000"), pending.copy(status = RequestStatus.Approved)))
        assertTrue(ComprasVisibility.canSee(actor(owner), null, pending))
        assertTrue(ComprasVisibility.canSee(actor(me), null, pending.copy(decidedBy = me)))
        assertFalse(ComprasVisibility.canSee(actor(me, ComprasModule.View), null, pending))
        assertTrue(ComprasVisibility.canSee(actor(me, ComprasModule.ViewAll), null, pending))
    }

    @Test
    fun canSeeAgreesWithApprovableForPendingRequests() {
        val pending = request(requesterId = UUID.randomUUID(), status = RequestStatus.PendingApproval, amount = "1")
        val cases = listOf(
            actor(me, ComprasModule.Approve) to approver(me, null),
            actor(me, ComprasModule.Approve) to approver(me, "100"),
            actor(me, ComprasModule.Approve) to approver(me, null, active = false),
            actor(me, ComprasModule.Approve) to null,
            actor(me, ComprasModule.View) to approver(me, null),
        )
        cases.forEach { (user, row) ->
            val approvable = ComprasVisibility.approvable(user, row)
            assertEquals(approvable != SqlFragment.False, ComprasVisibility.canSee(user, row, pending), "caso $row")
        }
    }

    @Test
    fun totalsAreForViewAllOrManageOnly() {
        assertTrue(ComprasVisibility.seesAll(actor(me, ComprasModule.ViewAll)))
        assertTrue(ComprasVisibility.seesAll(actor(me, ComprasModule.Manage)))
        assertFalse(ComprasVisibility.seesAll(actor(me, ComprasModule.View, ComprasModule.Approve)))
    }
}
