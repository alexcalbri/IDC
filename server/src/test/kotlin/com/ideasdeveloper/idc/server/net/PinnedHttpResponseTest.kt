package com.ideasdeveloper.idc.server.net

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Sin red real: la respuesta se lee de un ByteArrayInputStream (y un ServerSocket local para el socket anclado).
class PinnedHttpResponseTest {
    private val noClose = Closeable { }

    private fun parse(raw: String, method: String = "GET", maxBody: Long = Long.MAX_VALUE) =
        PinnedHost.readResponse(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)), noClose, method, maxBodyBytes = maxBody)

    @Test
    fun `lee un cuerpo con Content-Length completo`() {
        val response = parse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: text/csv\r\n\r\nhola!")
        assertEquals(200, response.status)
        assertEquals("text/csv", response.header("content-type"))
        assertEquals("hola!", response.body.readBytes().decodeToString())
    }

    @Test
    fun `lee un cuerpo chunked`() {
        val response = parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nhola\r\n3\r\n, m\r\n0\r\n\r\n")
        assertEquals("hola, m", response.body.readBytes().decodeToString())
    }

    @Test
    fun `un chunk con el CRLF final mal formado falla`() {
        val response = parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nholaXX\r\n0\r\n\r\n")
        assertFailsWith<IOException> { response.body.readBytes() }
    }

    @Test
    fun `un cierre truncado con Content-Length lanza en vez de truncar`() {
        val response = parse("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nhola")
        assertFailsWith<IOException> { response.body.readBytes() }
    }

    @Test
    fun `un cierre truncado en chunked lanza`() {
        val response = parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n a\r\nhola")
        assertFailsWith<IOException> { response.body.readBytes() }
    }

    @Test
    fun `una cabecera demasiado larga se corta`() {
        assertFailsWith<IOException> { parse("HTTP/1.1 200 OK\r\nX: ${"a".repeat(20_000)}\r\n\r\n") }
    }

    @Test
    fun `un flujo infinito de CR no se queda leyendo`() {
        assertFailsWith<IOException> { parse("HTTP/1.1 200 OK\r\nX: a" + "\r".repeat(40_000)) }
    }

    @Test
    fun `demasiadas cabeceras se cortan`() {
        val headers = (1..150).joinToString("") { "H$it: v\r\n" }
        assertFailsWith<IOException> { parse("HTTP/1.1 200 OK\r\n$headers\r\nhola") }
    }

    @Test
    fun `el cuerpo no puede pasar del maximo`() {
        val response = parse("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789", maxBody = 4)
        assertFailsWith<IOException> { response.body.readBytes() }
    }

    @Test
    fun `HEAD, 204 y 304 no tienen cuerpo`() {
        assertEquals(0, parse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n", method = "HEAD").body.readBytes().size)
        assertEquals(0, parse("HTTP/1.1 204 No Content\r\n\r\n").body.readBytes().size)
        assertEquals(0, parse("HTTP/1.1 304 Not Modified\r\nContent-Length: 5\r\n\r\n").body.readBytes().size)
    }

    @Test
    fun `cerrar la respuesta es idempotente y cierra la conexion una vez`() {
        var closed = 0
        val response = PinnedHost.readResponse(ByteArrayInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray()), Closeable { closed++ })
        response.close()
        response.close()
        response.body.close()
        assertEquals(1, closed)
    }

    @Test
    fun `PinnedSocket ignora el host del endpoint y conecta a la IP anclada`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            // El nombre del endpoint no resuelve: si se usara, la conexion fallaria.
            val endpoint = InetSocketAddress.createUnresolved("no-existe.invalid", server.localPort)
            PinnedSocket(InetAddress.getByName("127.0.0.1")).use { socket ->
                socket.connect(endpoint, 2_000)
                assertTrue(socket.isConnected)
                assertEquals(server.localPort, socket.port)
                assertEquals(InetAddress.getByName("127.0.0.1"), socket.inetAddress)
            }
        }
    }
}
