package com.ideasdeveloper.idc.server.security

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MasterKeySecretBoxTest {
    private val masterKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    /** A connection whose only table is tenant_data_keys (one optional row), answered by a proxy. */
    private class FakeTenant {
        var wrapped: String? = null

        val connection: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            when (method.name) {
                "getCatalog" -> "tenant_db"
                "getAutoCommit" -> true
                "prepareStatement" -> statement(args[0] as String)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Connection

        private fun statement(sql: String): PreparedStatement {
            var parameter: String? = null
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, args ->
                when (method.name) {
                    "setString" -> {
                        parameter = args[1] as String
                        null
                    }
                    "executeUpdate" -> {
                        if (sql.startsWith("INSERT INTO tenant_data_keys") && wrapped == null) wrapped = parameter
                        1
                    }
                    "executeQuery" -> rows(wrapped)
                    "close" -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as PreparedStatement
        }

        private fun rows(value: String?): ResultSet {
            var consumed = false
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, method, _ ->
                when (method.name) {
                    "next" -> (value != null && !consumed).also { consumed = true }
                    "getString" -> value
                    "close" -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ResultSet
        }
    }

    @Test
    fun sealedValuesRoundTripAndMaskedValuesDoNotLeak() {
        val tenant = FakeTenant()
        val box = MasterKeySecretBox(masterKey)

        val sealed = box.seal(tenant.connection, "token-123")

        assertTrue(sealed.startsWith(SecretBox.SealedPrefix))
        assertNotEquals("token-123", sealed)
        assertEquals("token-123", box.open(tenant.connection, sealed))
        assertEquals("legacy", box.open(tenant.connection, "legacy"))
    }

    @Test
    fun sealingAValueThatOnlyLooksSealedIsRefused() {
        val tenant = FakeTenant()
        val box = MasterKeySecretBox(masterKey)
        box.seal(tenant.connection, "first")

        val failure = assertFailsWith<IllegalArgumentException> { box.seal(tenant.connection, "enc:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") }
        assertEquals("valor reservado", failure.message)
    }

    @Test
    fun sealingAValueThatOpensWithTheCurrentKeyReturnsItUnchanged() {
        val tenant = FakeTenant()
        val box = MasterKeySecretBox(masterKey)
        val sealed = box.seal(tenant.connection, "secret")

        assertEquals(sealed, box.seal(tenant.connection, sealed))
    }

    @Test
    fun cachedKeyIsCheckedAgainstTheStoredRowOnEveryUse() {
        val tenant = FakeTenant()
        val box = MasterKeySecretBox(masterKey)
        val sealed = box.seal(tenant.connection, "secret")

        // The row is replaced by another data key (for example the database was restored): the cache must not hide it.
        tenant.wrapped = SecretCrypto.wrap(Base64.getDecoder().decode(masterKey), SecretCrypto.newDataKey())
        assertFailsWith<SecretDecryptionException> { box.open(tenant.connection, sealed) }

        // The row disappears: nothing opens, and sealing creates a new one.
        tenant.wrapped = null
        assertFailsWith<SecretDecryptionException> { box.open(tenant.connection, sealed) }
        val fresh = box.seal(tenant.connection, "again")
        assertEquals("again", box.open(tenant.connection, fresh))
        assertTrue(tenant.wrapped != null)
    }

    @Test
    fun withoutMasterKeySealingIsUnavailableAndForgetIsHarmless() {
        val tenant = FakeTenant()
        val box = MasterKeySecretBox(null)

        assertFailsWith<SecretsUnavailableException> { box.seal(tenant.connection, "x") }
        box.forget("tenant_db")
    }
}
