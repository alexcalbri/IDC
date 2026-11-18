package com.ideasdeveloper.idc.server.net

import com.ideasdeveloper.idc.server.modules.MailHostGuard
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** El host no se pudo resolver o apunta a una red no publica. */
class PinnedHostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Socket que, al conectar, ignora el nombre del destino y usa [validated] con el puerto pedido.
 * Es publico para poder probarlo.
 */
class PinnedSocket(private val validated: InetAddress) : Socket() {
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val target = if (endpoint is InetSocketAddress) InetSocketAddress(validated, endpoint.port) else endpoint
        super.connect(target, timeout)
    }
}

/**
 * Respuesta HTTP de [PinnedHost.openPinnedHttps]. [body] impone el plazo total y el maximo de bytes [maxBodyBytes]
 * (lanza [IOException] si se pasan). Cerrarla cierra la conexion; se puede cerrar varias veces.
 */
class PinnedResponse(
    val status: Int,
    private val headers: Map<String, List<String>>,
    source: InputStream,
    private val closer: Closeable,
    private val maxBodyBytes: Long = Long.MAX_VALUE,
    private val deadlineNanos: Long = Long.MAX_VALUE,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    val body: InputStream = object : InputStream() {
        private var total = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            PinnedHost.checkDeadline(deadlineNanos)
            // Se pide como mucho un byte mas que el maximo: asi se detecta el exceso sin leer de mas.
            // Sin maximo (Long.MAX_VALUE) no se aplica el truco del +1, que desbordaria a negativo.
            val allowance = if (maxBodyBytes == Long.MAX_VALUE) Long.MAX_VALUE else maxBodyBytes - total + 1
            val allowed = minOf(length.toLong(), allowance).toInt()
            val count = source.read(buffer, offset, allowed)
            if (count > 0) {
                total += count
                if (total > maxBodyBytes) throw IOException("La respuesta supera el maximo permitido.")
            }
            return count
        }

        override fun close() = this@PinnedResponse.close()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { closer.close() }
    }
}

/** Cuerpo con Content-Length: si la conexion se cierra antes de recibirlo completo, falla en vez de truncar en silencio. */
class LimitedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        if (length == 0) return 0
        val count = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count < 0) throw IOException("Conexion cerrada antes de tiempo")
        remaining -= count
        return count
    }
}

/** Cuerpo con Transfer-Encoding: chunked. Revisa el CRLF de cada fragmento y falla si la conexion se corta. */
class ChunkedInputStream(
    private val source: InputStream,
    private val deadlineNanos: Long = Long.MAX_VALUE,
) : InputStream() {
    private var left = 0L
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (finished) return -1
        if (length == 0) return 0
        if (left == 0L) {
            val size = PinnedHost.readLine(source, deadlineNanos).substringBefore(';').trim().toLongOrNull(16)
            if (size == null || size < 0) throw IOException("Fragmento HTTP invalido.")
            if (size == 0L) {
                finished = true
                return -1
            }
            left = size
        }
        val count = source.read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (count < 0) throw IOException("Conexion cerrada antes de tiempo")
        left -= count
        // CRLF que cierra el fragmento: tiene que estar vacio.
        if (left == 0L && PinnedHost.readLine(source, deadlineNanos).isNotEmpty()) throw IOException("Fragmento HTTP invalido.")
        return count
    }
}

/**
 * Anclaje de host: se resuelve el nombre UNA vez, se valida la IP y la conexion va siempre a esa IP.
 * Asi un DNS que cambie de respuesta entre la validacion y la conexion (rebinding) no sirve para llegar a la red interna.
 * El nombre original se conserva para el SNI, la verificacion del certificado y la cabecera Host.
 */
object PinnedHost {
    private const val MaxHeaderCount = 100
    private const val MaxHeaderBytes = 64 * 1024
    private const val MaxLineBytes = 16 * 1024

    /**
     * Resuelve [host], valida todas sus IP y devuelve la primera, que es a la que hay que conectar.
     * Con [allowPrivate] se aceptan IP privadas (relay interno de confianza); solo se exige que resuelva.
     * Es bloqueante (consulta DNS): llamar desde Dispatchers.IO.
     */
    fun resolveValidated(host: String, allowPrivate: Boolean = false): InetAddress {
        val name = host.trim()
        val addresses = try {
            InetAddress.getAllByName(name).toList()
        } catch (failure: Exception) {
            throw PinnedHostException("No se encontro el servidor $name.", failure)
        }
        if (addresses.isEmpty()) throw PinnedHostException("No se encontro el servidor $name.")
        if (!allowPrivate && addresses.any(MailHostGuard::blockedAddress)) {
            throw PinnedHostException("El servidor apunta a una red privada o local: no se permite.")
        }
        return addresses.first()
    }

    /** Conecta [socket] con plazo y lo cierra si falla, para no filtrar el descriptor. */
    private fun <T : Socket> connectOrClose(socket: T, address: InetAddress, port: Int, timeoutMillis: Int = 10_000): T {
        try {
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            return socket
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }

    private fun bindThenConnect(socket: PinnedSocket, localAddress: InetAddress?, localPort: Int, address: InetAddress, port: Int): PinnedSocket {
        try {
            socket.bind(InetSocketAddress(localAddress, localPort))
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
        return connectOrClose(socket, address, port)
    }

    /** Fabrica de sockets en claro que siempre conectan a [validated], sea cual sea el nombre que pida quien la usa. */
    fun pinnedSocketFactory(validated: InetAddress): SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = PinnedSocket(validated)
        override fun createSocket(host: String?, port: Int): Socket = connectOrClose(PinnedSocket(validated), validated, port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            bindThenConnect(PinnedSocket(validated), localHost, localPort, validated, port)
        override fun createSocket(host: InetAddress?, port: Int): Socket = connectOrClose(PinnedSocket(validated), validated, port)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            bindThenConnect(PinnedSocket(validated), localAddress, localPort, validated, port)
    }

    /**
     * Fabrica TLS que conecta el TCP a [validated] pero fija SNI y verificacion de identidad contra [originalHost],
     * de modo que el certificado se valida contra el nombre real y no contra la IP.
     */
    fun pinnedSslSocketFactory(validated: InetAddress, originalHost: String): SSLSocketFactory {
        val delegate = SSLSocketFactory.getDefault() as SSLSocketFactory
        // Sobre un socket ya conectado (el de JavaMail tras STARTTLS o el anclado): el nombre que ve TLS es el original.
        fun layer(socket: Socket, port: Int, autoClose: Boolean): Socket {
            val secured = try {
                delegate.createSocket(socket, originalHost, port, autoClose) as SSLSocket
            } catch (failure: Exception) {
                runCatching { socket.close() }
                throw failure
            }
            try {
                val parameters = secured.sslParameters
                parameters.serverNames = listOf(SNIHostName(originalHost))
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                secured.sslParameters = parameters
                return secured
            } catch (failure: Exception) {
                runCatching { secured.close() }
                runCatching { socket.close() }
                throw failure
            }
        }
        return object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
            override fun createSocket(socket: Socket, host: String?, port: Int, autoClose: Boolean): Socket = layer(socket, port, autoClose)
            override fun createSocket(host: String?, port: Int): Socket = layer(connectOrClose(PinnedSocket(validated), validated, port), port, true)
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                layer(bindThenConnect(PinnedSocket(validated), localHost, localPort, validated, port), port, true)
            override fun createSocket(host: InetAddress?, port: Int): Socket = layer(connectOrClose(PinnedSocket(validated), validated, port), port, true)
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                layer(bindThenConnect(PinnedSocket(validated), localAddress, localPort, validated, port), port, true)
            override fun createSocket(): Socket = throw IOException("Se necesita un socket ya conectado a la IP anclada.")
        }
    }

    /**
     * Peticion HTTP/1.1 por TLS a [validated] (solo https), con SNI, certificado y cabecera Host del [originalHost].
     * No sigue redirecciones: el llamador valida y ancla cada salto. Las cabeceras de [headers] se envian tal cual
     * (Host, Connection y Content-Length los pone esta funcion). [timeoutMillis] es el plazo de cada lectura y
     * [totalMillis] el plazo absoluto de toda la peticion y su cuerpo; el cuerpo no puede pasar de [maxBodyBytes].
     * Cerrar la respuesta cierra la conexion. Es bloqueante: llamar desde Dispatchers.IO.
     */
    fun openPinnedHttps(
        uri: URI,
        validated: InetAddress,
        originalHost: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMillis: Int = 30_000,
        connectTimeoutMillis: Int = 10_000,
        totalMillis: Long = 60_000,
        maxBodyBytes: Long = 64L * 1024 * 1024,
    ): PinnedResponse {
        if (uri.scheme != "https") throw PinnedHostException("Solo se permite https.")
        val deadline = System.nanoTime() + totalMillis * 1_000_000
        val port = if (uri.port > 0) uri.port else 443
        val plain = PinnedSocket(validated)
        var secured: Socket? = null
        try {
            plain.connect(InetSocketAddress(validated, port), connectTimeoutMillis)
            plain.soTimeout = timeoutMillis
            val socket = pinnedSslSocketFactory(validated, originalHost).createSocket(plain, originalHost, port, true) as SSLSocket
            secured = socket
            socket.soTimeout = timeoutMillis
            socket.startHandshake()
            val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val hostHeader = if (uri.port > 0 && uri.port != 443) "$originalHost:${uri.port}" else originalHost
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("Connection: close\r\n")
                append("Accept-Encoding: identity\r\n")
                headers.forEach { (name, value) ->
                    // Sin saltos de linea en las cabeceras: evita inyectar otras.
                    append("${name.replace(Regex("[\\r\\n:]"), "")}: ${value.replace(Regex("[\\r\\n]"), " ")}\r\n")
                }
                if (body != null) append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }
            val output = socket.outputStream
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            if (body != null) output.write(body)
            output.flush()
            return readResponse(socket.inputStream.buffered(), socket, method, deadline, maxBodyBytes)
        } catch (failure: Exception) {
            runCatching { secured?.close() }
            runCatching { plain.close() }
            throw failure
        }
    }

    /**
     * Lee estado, cabeceras y prepara el cuerpo de una respuesta HTTP/1.1. Limita a 100 cabeceras y 64 KB de cabeceras,
     * respeta el plazo absoluto [deadlineNanos] (de [System.nanoTime]) y el maximo [maxBodyBytes] del cuerpo.
     * HEAD, 1xx, 204 y 304 no tienen cuerpo. [closer] se cierra con la respuesta.
     */
    fun readResponse(
        input: InputStream,
        closer: Closeable,
        method: String = "GET",
        deadlineNanos: Long = System.nanoTime() + 60_000_000_000L,
        maxBodyBytes: Long = Long.MAX_VALUE,
    ): PinnedResponse {
        val statusLine = readLine(input, deadlineNanos)
        var used = statusLine.length + 2
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Respuesta HTTP invalida.")
        val headers = linkedMapOf<String, MutableList<String>>()
        var count = 0
        while (true) {
            val line = readLine(input, deadlineNanos)
            if (line.isEmpty()) break
            used += line.length + 2
            if (++count > MaxHeaderCount || used > MaxHeaderBytes) throw IOException("Demasiadas cabeceras HTTP.")
            val colon = line.indexOf(':')
            if (colon > 0) headers.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() } += line.substring(colon + 1).trim()
        }
        val bodyless = method.equals("HEAD", ignoreCase = true) || status in 100..199 || status == 204 || status == 304
        val declared = headers["content-length"]?.firstOrNull()?.toLongOrNull()
        val raw: InputStream = when {
            bodyless -> ByteArrayInputStream(ByteArray(0))
            headers["transfer-encoding"]?.any { it.contains("chunked", ignoreCase = true) } == true -> ChunkedInputStream(input, deadlineNanos)
            declared != null && declared >= 0 -> LimitedInputStream(input, declared)
            else -> input
        }
        return PinnedResponse(status, headers, raw, closer, maxBodyBytes, deadlineNanos)
    }

    /** Falla si ya paso el plazo absoluto. */
    fun checkDeadline(deadlineNanos: Long) {
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos > 0) throw IOException("Se agoto el tiempo de la peticion.")
    }

    /** Lee una linea terminada en LF. Cuenta todos los bytes (tambien los CR) y respeta el plazo absoluto. */
    fun readLine(input: InputStream, deadlineNanos: Long = Long.MAX_VALUE): String {
        val line = StringBuilder()
        var bytes = 0
        while (true) {
            checkDeadline(deadlineNanos)
            val next = input.read()
            if (next < 0) throw IOException("Conexion cerrada antes de tiempo")
            if (++bytes > MaxLineBytes) throw IOException("Cabecera HTTP demasiado larga.")
            if (next == '\n'.code) break
            if (next != '\r'.code) line.append(next.toChar())
        }
        return line.toString()
    }
}
