package com.ideasdeveloper.idc.server.security

import java.sql.Connection
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretCryptoTest {
    private val dataKey = SecretCrypto.newDataKey()

    @Test
    fun sealAndOpenRoundTrip() {
        val sealed = SecretCrypto.sealWith(dataKey, "contraseña-ñ")
        assertTrue(sealed.startsWith(SecretBox.SealedPrefix))
        assertFalse(sealed.contains("contraseña"))
        assertEquals("contraseña-ñ", SecretCrypto.openWith(dataKey, sealed))
    }

    @Test
    fun nonceDiffersEachTime() {
        assertNotEquals(SecretCrypto.sealWith(dataKey, "x"), SecretCrypto.sealWith(dataKey, "x"))
    }

    @Test
    fun tamperedValueFailsWithoutLeakingIt() {
        val sealed = SecretCrypto.sealWith(dataKey, "secreto-super")
        val bytes = Base64.getDecoder().decode(sealed.removePrefix(SecretBox.SealedPrefix))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val tampered = SecretBox.SealedPrefix + Base64.getEncoder().encodeToString(bytes)
        val failure = assertFailsWith<SecretDecryptionException> { SecretCrypto.openWith(dataKey, tampered) }
        assertFalse(failure.message.orEmpty().contains("secreto-super"))
        assertFailsWith<SecretDecryptionException> { SecretCrypto.openWith(dataKey, SecretBox.SealedPrefix + "no-es-base64!!") }
    }

    @Test
    fun wrongKeyFails() {
        val sealed = SecretCrypto.sealWith(dataKey, "x")
        assertFailsWith<SecretDecryptionException> { SecretCrypto.openWith(SecretCrypto.newDataKey(), sealed) }
    }

    @Test
    fun wrapAndUnwrapDataKey() {
        val master = SecretCrypto.newDataKey()
        val wrapped = SecretCrypto.wrap(master, dataKey)
        assertTrue(dataKey.contentEquals(SecretCrypto.unwrap(master, wrapped)))
        assertFailsWith<SecretDecryptionException> { SecretCrypto.unwrap(SecretCrypto.newDataKey(), wrapped) }
    }

    @Test
    fun legacyPlaintextPassesThrough() {
        assertEquals("texto plano", SecretCrypto.openWith(dataKey, "texto plano"))
    }

    @Test
    fun masterKeyParsing() {
        assertNull(SecretCrypto.parseMasterKey(null))
        assertNull(SecretCrypto.parseMasterKey(" "))
        assertNull(SecretCrypto.parseMasterKey("no base64 !!"))
        assertNull(SecretCrypto.parseMasterKey(Base64.getEncoder().encodeToString(ByteArray(16))))
        assertEquals(32, SecretCrypto.parseMasterKey(Base64.getEncoder().encodeToString(ByteArray(32)))?.size)
    }

    @Test
    fun missingMasterKeyMakesBoxUnavailable() {
        val box = MasterKeySecretBox(null)
        assertFalse(box.available)
        // La conexion no se usa: falla antes de tocar la base.
        assertFailsWith<SecretsUnavailableException> { box.seal(unusedConnection(), "x") }
        assertEquals("legado", box.open(unusedConnection(), "legado"))
        assertTrue(MasterKeySecretBox(Base64.getEncoder().encodeToString(ByteArray(32))).available)
    }

    private fun unusedConnection(): Connection = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Connection::class.java),
    ) { _, _, _ -> throw AssertionError("no debe usar la conexion") } as Connection
}
