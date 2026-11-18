package com.ideasdeveloper.idc.server.extensions.packages

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo

/** Limites y revisiones de lo que se extrae y se empaqueta de un paquete externo. */
object ExtensionPackageSafety {
    const val MaxEntryBytes = 5L * 1024 * 1024
    const val MaxPackageBytes = 20L * 1024 * 1024
    const val MaxEntries = 500
    const val MaxScannedEntries = 100_000

    /** Extensiones permitidas en server/src/main/resources. */
    val AllowedResourceExtensions = setOf("json", "txt", "md", "png", "jpg", "svg", "css", "html", "sql", "properties")

    /**
     * Extrae solo <packagePath>/ de un zip de GitHub (carpeta raiz <repo>-<commit>/). Copia por bloques
     * contando bytes y entradas, y aborta al pasar un limite. Rechaza rutas fuera de [A-Za-z0-9._/-],
     * con '..', '.' o segmentos vacios.
     */
    fun extract(
        archive: Path,
        packagePath: String,
        target: Path,
        maxEntryBytes: Long = MaxEntryBytes,
        maxTotalBytes: Long = MaxPackageBytes,
        maxEntries: Int = MaxEntries,
    ): Path {
        val prefix = packagePath.trim('/') + "/"
        val root = Files.createDirectories(target).toAbsolutePath().normalize()
        var total = 0L
        var entries = 0
        var scanned = 0
        var found = false
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++scanned > MaxScannedEntries) throw ExtensionPackageException("El repositorio tiene demasiados archivos.")
                val relative = entry.name.substringAfter('/', "")
                if (!relative.startsWith(prefix)) continue
                found = true
                val inner = relative.removePrefix(prefix).removeSuffix(if (entry.isDirectory) "/" else "")
                if (inner.isEmpty()) continue
                if (!ExtensionTreeHash.isValidPath(inner)) throw ExtensionPackageException("El paquete contiene una ruta no permitida: '$inner' (solo A-Za-z0-9._/-).")
                if (++entries > maxEntries) throw ExtensionPackageException("La extension tiene demasiados archivos (maximo $maxEntries).")
                val destination = root.resolve(inner).normalize()
                if (!destination.startsWith(root)) throw ExtensionPackageException("El paquete contiene rutas invalidas.")
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                    continue
                }
                Files.createDirectories(destination.parent)
                var size = 0L
                Files.newOutputStream(destination).use { output ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size += read
                        total += read
                        if (size > maxEntryBytes) throw ExtensionPackageException("'$inner' es demasiado grande.")
                        if (total > maxTotalBytes) throw ExtensionPackageException("La extension es demasiado grande.")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        if (!found) throw ExtensionPackageException("El commit no contiene la carpeta $packagePath.")
        return root
    }

    /**
     * Extrae de un ZIP cualquiera (subido o descargado de una URL) la carpeta que contiene el extension.json
     * de [extensionId]. Tolera que venga en la raiz, en una carpeta unica, en `<id>/`, en `extensions/<id>/`
     * o dentro de una carpeta raiz (`<repo>-<commit>/extensions/<id>/`). Usa el directorio central del ZIP
     * (ZipFile): las entradas que no se extraen nunca se descomprimen, asi que una bomba de compresion
     * fuera de la carpeta elegida no cuesta nada. Aplica las mismas protecciones que [extract]: solo
     * [A-Za-z0-9._/-], sin '..', '.' ni segmentos vacios, nada fuera de [target], y limites de bytes por
     * archivo, de bytes totales y de numero de archivos.
     */
    fun extractGeneric(
        archive: Path,
        extensionId: String,
        target: Path,
        maxEntryBytes: Long = MaxEntryBytes,
        maxTotalBytes: Long = MaxPackageBytes,
        maxEntries: Int = MaxEntries,
    ): Path {
        val root = Files.createDirectories(target).toAbsolutePath().normalize()
        var total = 0L
        var entries = 0
        val buffer = ByteArray(64 * 1024)
        ZipFile(archive.toFile()).use { zip ->
            if (zip.size() > MaxScannedEntries) throw ExtensionPackageException("El ZIP tiene demasiados archivos.")
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            val prefix = locateExtensionFolder(names, extensionId)
            for (entry in zip.entries()) {
                if (!entry.name.startsWith(prefix)) continue
                val inner = entry.name.removePrefix(prefix).removeSuffix(if (entry.isDirectory) "/" else "")
                if (inner.isEmpty()) continue
                if (!ExtensionTreeHash.isValidPath(inner)) throw ExtensionPackageException("El paquete contiene una ruta no permitida: '$inner' (solo A-Za-z0-9._/-).")
                if (++entries > maxEntries) throw ExtensionPackageException("La extension tiene demasiados archivos (maximo $maxEntries).")
                val destination = root.resolve(inner).normalize()
                if (!destination.startsWith(root)) throw ExtensionPackageException("El paquete contiene rutas invalidas.")
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                    continue
                }
                Files.createDirectories(destination.parent)
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(destination).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            size += read
                            total += read
                            if (size > maxEntryBytes) throw ExtensionPackageException("'$inner' es demasiado grande.")
                            if (total > maxTotalBytes) throw ExtensionPackageException("La extension es demasiado grande.")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
        return root
    }

    /**
     * Prefijo ("" o "a/b/") de la carpeta que tiene el extension.json de [extensionId]. Cuenta como carpeta
     * valida la raiz, una carpeta de primer nivel (cualquier nombre) o una cuyo ultimo segmento sea el id,
     * hasta 3 niveles. Gana la mas superficial; si hay dos al mismo nivel el ZIP es ambiguo y se rechaza.
     */
    internal fun locateExtensionFolder(names: List<String>, extensionId: String): String {
        val candidates = names.filter { it == "extension.json" || it.endsWith("/extension.json") }
            .map { it.removeSuffix("extension.json") }
            .filter { folder ->
                val segments = folder.split('/').filter { it.isNotEmpty() }
                segments.size <= 1 || (segments.size <= 3 && segments.last() == extensionId)
            }
        if (candidates.isEmpty()) throw ExtensionPackageException("El ZIP no contiene el extension.json de '$extensionId' (en la raiz o en extensions/$extensionId/).")
        fun depth(folder: String) = folder.split('/').count { it.isNotEmpty() }
        val shallowest = candidates.minOf(::depth)
        val best = candidates.filter { depth(it) == shallowest }.distinct()
        if (best.size > 1) throw ExtensionPackageException("El ZIP es ambiguo: tiene varios extension.json al mismo nivel (${best.joinToString { "'$it'" }}).")
        return best.single()
    }

    /** Problemas de contenido del paquete ya extraido: binarios, recursos fuera de la lista blanca, META-INF. */
    fun contentProblems(packageDir: Path): List<String> {
        val problems = mutableListOf<String>()
        val root = packageDir.toAbsolutePath().normalize()
        val files = Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList() }
        files.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPathString
            val extension = file.extension.lowercase()
            when {
                extension == "class" || extension == "jar" -> problems += "'$relative': no se permiten archivos .class ni .jar."
                relative.startsWith("server/src/main/kotlin/") && extension != "kt" -> problems += "'$relative': en server/src/main/kotlin solo se permiten archivos .kt."
                relative.startsWith("server/src/main/resources/") -> {
                    val resource = relative.removePrefix("server/src/main/resources/")
                    if (resource.lowercase().startsWith("meta-inf/")) {
                        problems += "'$relative': no se permite META-INF en los recursos."
                    } else if (extension !in AllowedResourceExtensions) {
                        problems += "'$relative': extension de recurso no permitida (${AllowedResourceExtensions.sorted().joinToString(", ")})."
                    }
                }
            }
        }
        return problems
    }
}
