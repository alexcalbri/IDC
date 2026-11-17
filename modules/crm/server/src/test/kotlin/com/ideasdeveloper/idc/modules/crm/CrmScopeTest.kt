package com.ideasdeveloper.idc.modules.crm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrmScopeTest {
    private val surf = UUID.randomUUID()
    private val yoga = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun scope(enabled: Boolean = true, all: Boolean = false, reception: Boolean = false, interests: Set<UUID> = setOf(surf)) =
        CrmScope(me, all, reception, enabled, interests)

    @Test
    fun withInterestsOffEverybodySeesEverything() {
        val scope = scope(enabled = false, interests = emptySet())
        assertTrue(scope.canSee(yoga, listOf(other)))
        assertTrue(scope.canSee(null, emptyList()))
        assertEquals("TRUE" to emptyList(), scope.sqlCondition())
    }

    @Test
    fun coordinatorSeesOnlyTheirInterestsAndOwnLeads() {
        val surfCoordinator = scope()
        assertTrue(surfCoordinator.canSee(surf, listOf(other)))
        assertFalse(surfCoordinator.canSee(yoga, listOf(other)))
        // A lead assigned to them stays visible whatever its interest.
        assertTrue(surfCoordinator.canSee(yoga, listOf(me)))
        // Uncategorized leads belong to reception.
        assertFalse(surfCoordinator.canSee(null, listOf(other)))
        assertTrue(surfCoordinator.canUseInterest(surf))
        assertFalse(surfCoordinator.canUseInterest(yoga))
        assertFalse(surfCoordinator.canUseInterest(null))
    }

    @Test
    fun receptionSeesUncategorizedLeads() {
        val receptionist = scope(reception = true, interests = emptySet())
        assertTrue(receptionist.canSee(null, listOf(other)))
        assertFalse(receptionist.canSee(surf, listOf(other)))
        assertTrue(receptionist.canUseInterest(null))
    }

    @Test
    fun seeAllSeesEveryInterest() {
        val admin = scope(all = true, interests = emptySet())
        assertTrue(admin.canSee(yoga, listOf(other)))
        assertTrue(admin.canSee(null, listOf(other)))
        assertTrue(admin.canUseInterest(yoga))
        assertEquals("TRUE", admin.sqlCondition().first)
    }

    @Test
    fun sqlConditionMatchesCanSee() {
        val (condition, params) = scope(reception = true).sqlCondition()
        assertEquals("(${CrmAssignees.OfUserSql} OR l.interest_id IS NULL OR l.interest_id = ANY(?))", condition)
        assertEquals(listOf<Any>(me, listOf(surf)), params)
        // Nothing assigned and no reception: the user sees no lead (but their own ones).
        assertEquals("(${CrmAssignees.OfUserSql})", scope(interests = emptySet()).sqlCondition().first)
        assertEquals("FALSE", CrmScope(null, false, false, true, emptySet()).sqlCondition().first)
    }

    @Test
    fun everyAssignedUserSeesTheLeadWhateverItsInterest() {
        val third = UUID.randomUUID()
        val coordinator = scope()
        // Two assigned users: both see the lead even though it is outside their interests.
        assertTrue(coordinator.canSee(yoga, listOf(other, me)))
        assertTrue(coordinator.canSee(null, listOf(me, third)))
        // Not one of the assigned: only the interests count.
        assertFalse(coordinator.canSee(yoga, listOf(other, third)))
        assertFalse(coordinator.canSee(yoga, emptyList()))
        // The same rule in SQL: the user is looked up among all the assigned users of the lead.
        assertTrue(CrmAssignees.OfUserSql.contains("crm_lead_owners"))
        assertFalse(coordinator.sqlCondition().first.contains("owner_user_id"))
    }
}
