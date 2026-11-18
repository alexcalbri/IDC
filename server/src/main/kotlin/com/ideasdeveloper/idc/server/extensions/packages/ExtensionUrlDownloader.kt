package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.net.PinnedHost
import com.ideasdeveloper.idc.server.net.PinnedHostException
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Descarga el ZIP de un paquete de extension desde una URL https elegida por el dueno del servidor.
 *
 * Anti-SSRF: solo https (puerto 443), sin usuario ni clave en la URL, el host se resuelve UNA vez y se
 * rechaza si alguna IP es privada, local o de enlace local ([PinnedHost.resolveValidated]); la conexion va
 * siempre a esa IP anclada (un DNS que cambie de respuesta no sirve). No se siguen redirecciones solas: cada
 * salto se valida y se ancla de nuevo, con un maximo de [MaxRedirects]. El cuerpo no puede pasar de
 * [maxBytes] ni tardar mas del plazo total.
 */
object ExtensionUrlDownloader {
    const val MaxRedirects = 5
    const val MaxUrlLength = 2048
    const val DefaultMaxBytes = 50L * 1024 * 1024

    /**
     * Revisa la URL sin tocar la red y devuelve su URI. Rechaza lo que no sea https, sin host, con usuario o
     * clave, con un puerto distinto del 443 o demasiado larga.
     */
    fun validate(url: String): URI {
        val text = url.trim()
        if (text.isEmpty() || text.length > MaxUrlLength) throw ExtensionPackageException("La URL del paquete no es valida.")
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            throw ExtensionPackageException("La URL del paquete no es valida.")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) throw ExtensionPackageException("La URL del paquete debe ser https.")
        if (uri.host.isNullOrBlank()) throw ExtensionPackageException("La URL del paquete no tiene servidor.")
        if (uri.rawUserInfo != null) throw ExtensionPackageException("La URL del paquete no puede llevar usuario ni clave.")
        if (uri.port != -1 && uri.port != 443) throw ExtensionPackageException("La URL del paquete debe usar el puerto 443.")
        return uri
    }

    /**
     * Descarga [url] en [target] (sobrescribe). [resolve] valida y fija la IP del host; en produccion es
     * [PinnedHost.resolveValidated] y las pruebas lo sustituyen. [fetch] abre la conexion anclada.
     */
    fun download(
        url: String,
        target: Path,
        maxBytes: Long = DefaultMaxBytes,
        resolve: (String) -> InetAddress = { host -> PinnedHost.resolveValidated(host) },
        fetch: (URI, InetAddress) -> com.ideasdeveloper.idc.server.net.PinnedResponse = { uri, address ->
            PinnedHost.openPinnedHttps(
                uri = uri, validated = address, originalHost = uri.host,
                headers = mapOf("User-Agent" to "IdeasCore-Server", "Accept" to "application/zip, application/octet-stream"),
                totalMillis = 180_000, maxBodyBytes = maxBytes,
            )
        },
    ): Path {
        var current = validate(url)
        var redirects = 0
        while (true) {
            val address = try {
                resolve(current.host)
            } catch (failure: PinnedHostException) {
                throw ExtensionPackageException("No se puede descargar de ${current.host}: ${failure.message}", failure)
            }
            try {
                fetch(current, address).use { response ->
                    val location = response.header("location")
                    if (response.status in 300..399 && location != null) {
                        if (++redirects > MaxRedirects) throw ExtensionPackageException("Demasiadas redirecciones al descargar el paquete.")
                        // Cada salto se valida de nuevo (https, puerto, sin credenciales) y se resuelve y ancla otra vez.
                        current = validate(current.resolve(location).toString())
                        return@use
                    }
                    if (response.status != 200) throw ExtensionPackageException("No se pudo descargar el paquete (HTTP ${response.status}).")
                    val declared = response.header("content-length")?.toLongOrNull()
                    if (declared != null && declared > maxBytes) throw ExtensionPackageException("El paquete descargado es demasiado grande.")
                    Files.createDirectories(target.toAbsolutePath().parent)
                    try {
                        Files.copy(response.body, target, StandardCopyOption.REPLACE_EXISTING)
                    } catch (failure: IOException) {
                        runCatching { Files.deleteIfExists(target) }
                        throw ExtensionPackageException("No se pudo descargar el paquete completo: ${failure.message}", failure)
                    }
                    return target
                }
            } catch (failure: PinnedHostException) {
                throw ExtensionPackageException("No se pudo conectar para descargar el paquete: ${failure.message}", failure)
            } catch (failure: IOException) {
                throw ExtensionPackageException("No se pudo descargar el paquete: ${failure.message}", failure)
            }
        }
    }
}
