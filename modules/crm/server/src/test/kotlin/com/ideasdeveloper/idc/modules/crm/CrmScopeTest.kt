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
        assertTrue(scope.canSee(yoga, other))
        assertTrue(scope.canSee(null, null))
        assertEquals("TRUE" to emptyList(), scope.sqlCondition())
    }

    @Test
    fun coordinatorSeesOnlyTheirInterestsAndOwnLeads() {
        val surfCoordinator = scope()
        assertTrue(surfCoordinator.canSee(surf, other))
        assertFalse(surfCoordinator.canSee(yoga, other))
        // A lead assigned to them stays visible whatever its interest.
        assertTrue(surfCoordinator.canSee(yoga, me))
        // Uncategorized leads belong to reception.
        assertFalse(surfCoordinator.canSee(null, other))
        assertTrue(surfCoordinator.canUseInterest(surf))
        assertFalse(surfCoordinator.canUseInterest(yoga))
        assertFalse(surfCoordinator.canUseInterest(null))
    }

    @Test
    fun receptionSeesUncategorizedLeads() {
        val receptionist = scope(reception = true, interests = emptySet())
        assertTrue(receptionist.canSee(null, other))
        assertFalse(receptionist.canSee(surf, other))
        assertTrue(receptionist.canUseInterest(null))
    }

    @Test
    fun seeAllSeesEveryInterest() {
        val admin = scope(all = true, interests = emptySet())
        assertTrue(admin.canSee(yoga, other))
        assertTrue(admin.canSee(null, other))
        assertTrue(admin.canUseInterest(yoga))
        assertEquals("TRUE", admin.sqlCondition().first)
    }

    @Test
    fun sqlConditionMatchesCanSee() {
        val (condition, params) = scope(reception = true).sqlCondition()
        assertEquals("(l.owner_user_id = ? OR l.interest_id IS NULL OR l.interest_id = ANY(?))", condition)
        assertEquals(listOf<Any>(me, listOf(surf)), params)
        // Nothing assigned and no reception: the user sees no lead (but their own ones).
        assertEquals("(l.owner_user_id = ?)", scope(interests = emptySet()).sqlCondition().first)
        assertEquals("FALSE", CrmScope(null, false, false, true, emptySet()).sqlCondition().first)
    }
}
