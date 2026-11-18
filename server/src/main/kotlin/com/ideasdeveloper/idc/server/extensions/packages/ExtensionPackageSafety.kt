package com.ideasdeveloper.idc.server.extensions.packages

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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
