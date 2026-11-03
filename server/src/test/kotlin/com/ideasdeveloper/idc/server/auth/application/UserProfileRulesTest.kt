package com.ideasdeveloper.idc.server.auth.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserProfileRulesTest {
    @Test
    fun everyUserNeedsANameAndAnEmail() {
        assertNull(UserProfileRules.problem("Ana Perez", "ana@empresa.com"))
        assertNull(UserProfileRules.problem("  Ana  ", " ana@empresa.com "))
        assertEquals("escribe un nombre de 2 a 120 caracteres.", UserProfileRules.problem("A", "ana@empresa.com"))
        assertEquals("escribe un correo valido.", UserProfileRules.problem("Ana", "ana@empresa"))
        assertEquals("escribe un correo valido.", UserProfileRules.problem("Ana", "ana perez@empresa.com"))
        assertFalse(UserProfileRules.validEmail("a".repeat(250) + "@x.co"))
    }

    // Users created before names were required complete them at their next sign-in.
    @Test
    fun missingDataMeansIncompleteProfile() {
        assertTrue(UserProfileRules.isComplete("Ana", "ana@empresa.com"))
        assertFalse(UserProfileRules.isComplete(null, "ana@empresa.com"))
        assertFalse(UserProfileRules.isComplete("Ana", null))
    }
}
