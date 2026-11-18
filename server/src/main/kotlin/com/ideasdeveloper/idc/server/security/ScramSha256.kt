package com.ideasdeveloper.idc.server.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Calcula el verificador SCRAM-SHA-256 que PostgreSQL guarda para un rol (RFC 5802 / RFC 7677).
 *
 * Enviar `ALTER ROLE ... PASSWORD 'SCRAM-SHA-256$...'` hace que el servidor almacene el valor tal cual,
 * de modo que la contrasena en texto plano nunca viaja en el DDL ni queda en el log de PostgreSQL.
 *
 * Formato: `SCRAM-SHA-256$<iteraciones>:<salt base64>$<StoredKey base64>:<ServerKey base64>`.
 */
object ScramSha256 {
    const val Prefix = "SCRAM-SHA-256"
    const val DefaultIterations = 4096
    private const val SaltBytes = 16
    private const val HmacAlgorithm = "HmacSHA256"
    private val random = SecureRandom()

    fun verifier(password: String, iterations: Int = DefaultIterations): String {
        val salt = ByteArray(SaltBytes).also { random.nextBytes(it) }
        return verifier(password, salt, iterations)
    }

    /** Version determinista (salt dado); la usan las pruebas. */
    fun verifier(password: String, salt: ByteArray, iterations: Int = DefaultIterations): String {
        require(iterations > 0) { "Las iteraciones deben ser positivas" }
        // SASLprep aproximado: NFKC es lo que aplica pg_SASLprep en el caso comun.
        val passwordBytes = Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        val saltedPassword = pbkdf2(passwordBytes, salt, iterations)
        val clientKey = hmac(saltedPassword, "Client Key".toByteArray(Charsets.UTF_8))
        val storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey)
        val serverKey = hmac(saltedPassword, "Server Key".toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "$Prefix\$$iterations:${encoder.encodeToString(salt)}\$${encoder.encodeToString(storedKey)}:${encoder.encodeToString(serverKey)}"
    }

    // PBKDF2-HMAC-SHA-256 con un solo bloque de 32 bytes, sobre los bytes crudos de la contrasena
    // (PBEKeySpec trataria la clave como caracteres y no coincidiria byte a byte).
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance(HmacAlgorithm).apply { init(SecretKeySpec(password, HmacAlgorithm)) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return result
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HmacAlgorithm)
        mac.init(SecretKeySpec(key, HmacAlgorithm))
        return mac.doFinal(data)
    }
}
