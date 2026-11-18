package com.ideasdeveloper.idc.server.auth.infrastructure.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object ApplicationPasswordHasher {
    // Cada hash guarda sus iteraciones; los antiguos (120000) se verifican con las suyas y se re-hashean al iniciar sesion.
    const val Iterations: Int = 600_000
    private const val KeyLengthBits: Int = 256
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun hash(password: String, iterations: Int = Iterations): PasswordHash {
        val salt = ByteArray(16).also(random::nextBytes)
        return PasswordHash(
            salt = encoder.encodeToString(salt),
            hash = encoder.encodeToString(derive(password, salt, iterations)),
            iterations = iterations,
        )
    }

    fun verify(password: String, salt: String, expectedHash: String, iterations: Int): Boolean {
        val calculated = derive(password, decoder.decode(salt), iterations)
        val expected = decoder.decode(expectedHash)
        if (calculated.size != expected.size) return false
        var diff = 0
        for (index in calculated.indices) {
            diff = diff or (calculated[index].toInt() xor expected[index].toInt())
        }
        return diff == 0
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KeyLengthBits))
            .encoded
}

data class PasswordHash(
    val salt: String,
    val hash: String,
    val iterations: Int,
)
