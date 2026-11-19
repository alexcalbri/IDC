package com.ideasdeveloper.idc.server.auth

import com.ideasdeveloper.idc.server.auth.application.AccountException
import com.ideasdeveloper.idc.server.auth.application.EmailChangeRule
import com.ideasdeveloper.idc.server.auth.application.PasswordAttemptLimiter
import com.ideasdeveloper.idc.server.auth.application.PasswordConfirmation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailChangeRuleTest {
    private fun check(
        stored: String?,
        requested: String,
        password: String?,
        matches: Boolean = true,
        calls: MutableList<String> = mutableListOf(),
        limiter: PasswordAttemptLimiter? = null,
    ) = EmailChangeRule.check(stored, requested, password, limiter, "u") { calls += it; matches }

    @Test
    fun sameEmailIgnoringCaseAndSpacesDoesNotAskForThePassword() {
        val calls = mutableListOf<String>()
        check("ana@example.com", "  Ana@Example.COM ", null, calls = calls)
        assertTrue(calls.isEmpty())
        assertFalse(EmailChangeRule.isChange("ana@example.com", "ANA@example.com"))
    }

    @Test
    fun differentEmailWithoutPasswordRequiresIt() {
        val error = assertFailsWith<AccountException> { check("ana@example.com", "otra@example.com", null) }
        assertEquals("PASSWORD_REQUIRED", error.code)
        assertEquals("Escribe tu contrasena actual para cambiar el correo.", error.message)
        assertEquals("PASSWORD_REQUIRED", assertFailsWith<AccountException> { check("ana@example.com", "otra@example.com", "") }.code)
    }

    @Test
    fun differentEmailWithWrongPasswordIsRejected() {
        val error = assertFailsWith<AccountException> { check("ana@example.com", "otra@example.com", "mala", matches = false) }
        assertEquals("INVALID_PASSWORD", error.code)
        assertEquals("La contrasena actual no es correcta.", error.message)
    }

    @Test
    fun differentEmailWithRightPasswordPassesAndChecksThatPassword() {
        val calls = mutableListOf<String>()
        check("ana@example.com", "otra@example.com", "buena", calls = calls)
        assertEquals(listOf("buena"), calls)
    }

    @Test
    fun kelvinSignIsNotTreatedAsTheLetterK() {
        assertTrue(EmailChangeRule.isChange("ana@kmail.com", "ana@Kmail.com"))
        assertEquals("PASSWORD_REQUIRED", assertFailsWith<AccountException> { check("ana@kmail.com", "ana@Kmail.com", null) }.code)
    }

    @Test
    fun ownerNeedsThePasswordForANewPasswordEvenWithTheSameEmail() {
        assertTrue(PasswordConfirmation.ownerChangeNeedsPassword("ana@example.com", "ana@example.com", "una-contrasena-nueva"))
        assertTrue(PasswordConfirmation.ownerChangeNeedsPassword("ana@example.com", "otra@example.com", ""))
        assertFalse(PasswordConfirmation.ownerChangeNeedsPassword("ana@example.com", "ANA@example.com", ""))
    }

    @Test
    fun fifthFailureBlocksEvenTheRightPasswordAndWindowExpires() {
        var now = java.time.Instant.parse("2026-11-18T10:00:00Z")
        val limiter = PasswordAttemptLimiter(clock = { now })
        repeat(5) {
            assertEquals("INVALID_PASSWORD", assertFailsWith<AccountException> {
                check("a@x.com", "b@x.com", "mala", matches = false, limiter = limiter)
            }.code)
        }
        val blocked = assertFailsWith<AccountException> { check("a@x.com", "b@x.com", "buena", matches = true, limiter = limiter) }
        assertEquals("TOO_MANY_ATTEMPTS", blocked.code)
        assertEquals("Demasiados intentos. Espera unos minutos e intentalo de nuevo.", blocked.message)
        // Otro usuario no se ve afectado.
        EmailChangeRule.check("a@x.com", "b@x.com", "buena", limiter, "otro") { true }
        now = now.plus(PasswordAttemptLimiter.Window).plusSeconds(1)
        check("a@x.com", "b@x.com", "buena", matches = true, limiter = limiter)
    }

    @Test
    fun successfulChecksDoNotCountAsFailures() {
        val limiter = PasswordAttemptLimiter()
        repeat(20) { check("a@x.com", "b@x.com", "buena", matches = true, limiter = limiter) }
        assertFalse(limiter.isBlocked("u"))
    }

    @Test
    fun settingAnEmailWhereThereWasNoneAlsoRequiresThePassword() {
        assertEquals("PASSWORD_REQUIRED", assertFailsWith<AccountException> { check(null, "ana@example.com", null) }.code)
    }
}
