package com.ideasdeveloper.idc.modules.tareas

import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Contract of the extension point for other modules: source and external_ref. */
class ExternalTaskRequestTest {
    private fun request(
        source: String = "mantenimiento",
        externalRef: String = "order:123",
        title: String = "Cambiar filtro",
        groupName: String? = null,
    ) = ExternalTaskRequest(source, externalRef, UUID.randomUUID(), groupName, title)

    @Test
    fun validRequestHasNoMessages() {
        assertTrue(request().validate().isEmpty())
        assertTrue(request(source = "a".repeat(40)).validate().isEmpty())
    }

    @Test
    fun sourceMustMatchThePattern() {
        listOf("", "Mantenimiento", "1abc", "has-dash", "con espacio", "a".repeat(41)).forEach {
            assertTrue(request(source = it).validate().isNotEmpty(), "source '$it' should be invalid")
        }
    }

    @Test
    fun reservedSourcesAreRejected() {
        assertEquals(1, request(source = "manual").validate().size)
        assertEquals(1, request(source = "agent").validate().size)
    }

    @Test
    fun externalRefMustHaveOneToTwoHundredCharacters() {
        assertTrue(request(externalRef = "x").validate().isEmpty())
        assertTrue(request(externalRef = "x".repeat(ExternalTaskRequest.MaxExternalRef)).validate().isEmpty())
        assertEquals(1, request(externalRef = "").validate().size)
        assertEquals(1, request(externalRef = "   ").validate().size)
        assertEquals(1, request(externalRef = "x".repeat(ExternalTaskRequest.MaxExternalRef + 1)).validate().size)
    }

    @Test
    fun groupNameAndTaskDataAreValidatedToo() {
        assertEquals(1, request(groupName = "g".repeat(81)).validate().size)
        assertTrue(request(groupName = "g".repeat(80)).validate().isEmpty())
        assertEquals(1, request(title = " ").validate().size)
    }

    @Test
    fun asInputCarriesTheFields() {
        val request = request(title = "Tarea")
        val groupId = UUID.randomUUID()
        val input = request.asInput(groupId)
        assertEquals(request.boardId, input.boardId)
        assertEquals(groupId, input.groupId)
        assertEquals("Tarea", input.title)
    }

    @Test
    fun upsertRejectsAnInvalidRequestBeforeUsingTheDatabase() {
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ ->
            error("The connection must not be used")
        } as Connection
        assertFailsWith<TareasValidationException> { TareasExternal.upsertTask(connection, request(source = "manual")) }
    }
}
