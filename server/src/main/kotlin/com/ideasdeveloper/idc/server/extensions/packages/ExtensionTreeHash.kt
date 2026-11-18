package com.ideasdeveloper.idc.server.extensions.packages

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Error de un paquete de extension (descarga, hash, firma, validacion o compilacion). */
class ExtensionPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Hash del arbol de una carpeta de extension (packageSha256). Mismo algoritmo que
 * scripts/extensions/sign.sh:
 *
 * 1. Todos los archivos de la carpeta, excepto extension.sig.json de la raiz.
 * 2. Ordenados por ruta relativa con '/' (orden de bytes). Las rutas solo pueden usar
 *    [A-Za-z0-9._/-], sin segmentos vacios, '.' ni '..'; si una no cumple el hash falla.
 * 3. Por archivo se acumula `ruta\n` + sha256hex(contenido) + `\n`.
 * 4. El hash es el SHA-256 de lo acumulado, en hex (64 caracteres).
 */
object ExtensionTreeHash {
    const val SignatureFileName = "extension.sig.json"
    private val allowedPath = Regex("[A-Za-z0-9._/-]+")

    /** true si [path] (relativa, con '/') usa solo [A-Za-z0-9._/-] y no tiene segmentos vacios, '.' ni '..'. */
    fun isValidPath(path: String): Boolean =
        allowedPath.matches(path) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun compute(directory: Path): String {
        val root = directory.toAbsolutePath().normalize()
        val digests = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { file -> root.relativize(file).joinToString("/") to sha256Hex(file) }
                .toList()
        }
        return fromDigests(digests)
    }

    /** Mismo hash a partir de rutas relativas y contenidos ya en memoria (pruebas). */
    fun ofEntries(entries: Map<String, ByteArray>): String =
        fromDigests(entries.map { (path, content) -> path to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)) })

    private fun fromDigests(files: List<Pair<String, String>>): String {
        val total = MessageDigest.getInstance("SHA-256")
        files.filter { it.first != SignatureFileName }
            .onEach { if (!isValidPath(it.first)) throw ExtensionPackageException("Ruta no permitida en el paquete: '${it.first}' (solo A-Za-z0-9._/-).") }
            .sortedWith { a, b -> compareBytes(a.first, b.first) }
            .forEach { (path, digest) -> total.update("$path\n$digest\n".toByteArray(Charsets.UTF_8)) }
        return HexFormat.of().formatHex(total.digest())
    }

    private fun compareBytes(a: String, b: String): Int = java.util.Arrays.compareUnsigned(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(file: Path): String = Files.newInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        HexFormat.of().formatHex(digest.digest())
    }
}
