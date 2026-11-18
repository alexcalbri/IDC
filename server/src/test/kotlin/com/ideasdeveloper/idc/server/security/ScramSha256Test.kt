package com.ideasdeveloper.idc.server.security

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScramSha256Test {
    @Test
    fun verifierHasPostgresFormat() {
        val verifier = ScramSha256.verifier("pencil")
        assertTrue(verifier.startsWith("SCRAM-SHA-256\$4096:"))
        val rest = verifier.removePrefix("SCRAM-SHA-256\$4096:")
        val salt = rest.substringBefore('$')
        val keys = rest.substringAfter('$').split(':')
        assertEquals(2, keys.size)
        assertEquals(16, Base64.getDecoder().decode(salt).size)
        assertEquals(32, Base64.getDecoder().decode(keys[0]).size)
        assertEquals(32, Base64.getDecoder().decode(keys[1]).size)
        assertFalse(verifier.contains("pencil"))
    }

    @Test
    fun saltIsRandomPerCall() {
        assertNotEquals(ScramSha256.verifier("pencil"), ScramSha256.verifier("pencil"))
    }

    @Test
    fun deterministicVerifierMatchesIndependentComputation() {
        // Salt del ejemplo de la RFC 7677; para una contrasena ASCII, PBKDF2WithHmacSHA256 de la JVM
        // produce el mismo SaltedPassword, asi que sirve como calculo independiente.
        val salt = Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ==")
        val salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("pencil".toCharArray(), salt, 4096, 256)).encoded
        val clientKey = hmac(salted, "Client Key")
        val storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey)
        val serverKey = hmac(salted, "Server Key")
        val encoder = Base64.getEncoder()
        val expected = "SCRAM-SHA-256\$4096:${encoder.encodeToString(salt)}" +
            "\$${encoder.encodeToString(storedKey)}:${encoder.encodeToString(serverKey)}"
        assertEquals(expected, ScramSha256.verifier("pencil", salt, 4096))
    }

    @Test
    fun differentPasswordsGiveDifferentVerifiers() {
        val salt = ByteArray(16) { it.toByte() }
        assertNotEquals(ScramSha256.verifier("uno", salt), ScramSha256.verifier("dos", salt))
    }

    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray())
}
