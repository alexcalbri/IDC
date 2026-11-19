package com.ideasdeveloper.idc.modules.compras

import java.lang.reflect.Proxy
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ComprasSql.transaction con una conexion falsa que registra lo que se le pide, y la limpieza de nombres de archivo. */
class ComprasSqlTest {
    private class Fake(var autoCommit: Boolean) {
        val calls = mutableListOf<String>()

        val connection: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            when (method.name) {
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> { autoCommit = args[0] as Boolean; calls += "autoCommit=$autoCommit"; null }
                "commit" -> { calls += "commit"; null }
                "rollback" -> { calls += "rollback"; null }
                else -> error("no esperado: ${method.name}")
            }
        } as Connection
    }

    @Test
    fun commitsAndRestoresAutoCommit() {
        val fake = Fake(autoCommit = true)
        val result = ComprasSql.transaction(fake.connection) { 42 }
        assertEquals(42, result)
        assertEquals(listOf("autoCommit=false", "commit", "autoCommit=true"), fake.calls)
    }

    @Test
    fun rollsBackOnFailureAndRestoresAutoCommit() {
        val fake = Fake(autoCommit = true)
        assertFailsWith<IllegalStateException> { ComprasSql.transaction(fake.connection) { error("falla") } }
        assertEquals(listOf("autoCommit=false", "rollback", "autoCommit=true"), fake.calls)
    }

    @Test
    fun joinsAnOpenTransactionWithoutCommitting() {
        val fake = Fake(autoCommit = false)
        assertEquals("x", ComprasSql.transaction(fake.connection) { "x" })
        assertTrue(fake.calls.isEmpty())
        assertFailsWith<IllegalStateException> { ComprasSql.transaction(fake.connection) { error("falla") } }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun cleanNameKeepsOnlyTheFileName() {
        assertEquals("factura.pdf", ComprasAttachments.cleanName("C:\\docs\\factura.pdf"))
        assertEquals("a.png", ComprasAttachments.cleanName("../../etc/a.png"))
        assertEquals("ab.txt", ComprasAttachments.cleanName("a\u0000b.txt"))
        assertEquals(255, ComprasAttachments.cleanName("x".repeat(400)).length)
    }

    @Test
    fun cleanPathDropsDotSegmentsAndNormalizesSeparators() {
        assertEquals("Contrato/Anexos", ComprasAttachments.cleanPath("..\\Contrato/./Anexos/"))
        assertEquals("", ComprasAttachments.cleanPath("../.."))
        assertEquals(500, ComprasAttachments.cleanPath("d".repeat(900)).length)
    }
}
