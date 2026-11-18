package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.extensions.ServerExtensionProvider
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.ServiceLoader
import java.util.jar.JarFile

/** Un paquete instalado que el arranque quiere cargar, con lo que server_extensions dice de el. */
data class LoadCandidate(
    val jar: Path,
    val id: String,
    val module: String,
    val version: String,
    /** SHA-256 del jar guardado al compilarlo; sin el no se carga. */
    val jarSha256: String?,
)

/**
 * Carga los paquetes de extensiones externas al arrancar. Cada jar va en su propio class loader,
 * cuyo padre es el class loader del modulo del que depende, para que la extension vea sus clases.
 * Antes de cargar se comprueba que el jar es el que se compilo (ruta bajo packagesRoot y SHA-256) y
 * despues que la extension creada coincide con lo registrado.
 */
object ExtensionPackageLoader {
    private val log = LoggerFactory.getLogger(ExtensionPackageLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** extension.json guardado dentro del jar (extensions/<id>/extension.json), o null si no esta. */
    fun readManifest(jar: Path): ExtensionManifest? = runCatching {
        JarFile(jar.toFile()).use { file ->
            val entry = file.entries().asSequence().firstOrNull { it.name.startsWith("extensions/") && it.name.endsWith("/extension.json") }
                ?: return@use null
            json.decodeFromString(ExtensionManifest.serializer(), file.getInputStream(entry).readBytes().decodeToString())
        }
    }.getOrNull()

    /** Motivo por el que el jar no debe cargarse (ruta, hash, manifiesto del jar), o null si esta bien. */
    fun checkJar(candidate: LoadCandidate, packagesRoot: Path): String? {
        val root = packagesRoot.toAbsolutePath().normalize()
        val jar = candidate.jar.toAbsolutePath().normalize()
        if (!jar.startsWith(root)) return "la ruta del jar esta fuera de $root"
        if (!Files.isRegularFile(jar)) return "el jar no existe"
        val expected = candidate.jarSha256?.lowercase() ?: return "no hay SHA-256 guardado del jar"
        val actual = sha256(jar)
        if (actual != expected) return "el SHA-256 del jar ($actual) no coincide con el guardado ($expected)"
        val manifest = readManifest(jar) ?: return "el jar no trae un extension.json valido"
        if (manifest.id != candidate.id || manifest.module != candidate.module || manifest.version != candidate.version) {
            return "el extension.json del jar (${manifest.id}/${manifest.module}/${manifest.version}) no coincide con el registro (${candidate.id}/${candidate.module}/${candidate.version})"
        }
        val missing = JarFile(jar.toFile()).use { file -> manifest.migrations.map { jarMigrationPath(manifest.id, it) }.filter { file.getJarEntry(it) == null } }
        if (missing.isNotEmpty()) return "faltan migraciones en el jar: $missing"
        return null
    }

    /** Motivo por el que la extension creada por el codigo del paquete no coincide con lo registrado, o null. */
    fun checkExtension(candidate: LoadCandidate, extension: ServerExtension): String? {
        val manifest = extension.manifest
        if (manifest.id != candidate.id || manifest.module != candidate.module || manifest.version != candidate.version) {
            return "el manifiesto del codigo (${manifest.id}/${manifest.module}/${manifest.version}) no coincide con el registro (${candidate.id}/${candidate.module}/${candidate.version})"
        }
        val expectedMigrations = manifest.migrations.map { jarMigrationPath(manifest.id, it) }
        if (extension.migrationPaths != expectedMigrations) {
            return "migrationPaths ${extension.migrationPaths} debe ser exactamente ${expectedMigrations}"
        }
        val prefix = MigrationTableValidator.tablePrefix(manifest.id)
        extension.runtimeTables.firstOrNull { !it.startsWith(prefix) }?.let { return "runtimeTables '$it' debe empezar con $prefix" }
        return null
    }

    /** Ruta de una migracion dentro del jar. */
    fun jarMigrationPath(extensionId: String, migration: String) = "extensions/$extensionId/$migration"

    /**
     * [parentFor] da el class loader del modulo instalado (null si el modulo no esta cargado: el
     * paquete se omite). Un paquete que falla o no cumple no impide arrancar ni cargar los demas.
     */
    fun load(
        candidates: List<LoadCandidate>,
        packagesRoot: Path,
        parentFor: (moduleId: String) -> ClassLoader?,
    ): List<ServerExtension> = candidates.flatMap { candidate ->
        try {
            checkJar(candidate, packagesRoot)?.let { problem ->
                log.error("Paquete de extension {} omitido: {}", candidate.id, problem)
                return@flatMap emptyList<ServerExtension>()
            }
            val parent = parentFor(candidate.module) ?: error("el modulo '${candidate.module}' no esta cargado")
            val loader = URLClassLoader(arrayOf(candidate.jar.toUri().toURL()), parent)
            // El loader hijo tambien ve los proveedores del servidor y del modulo; se queda solo con los del paquete.
            ServiceLoader.load(ServerExtensionProvider::class.java, loader).stream()
                .filter { it.type().classLoader == loader }
                .map { it.get().create() }
                .toList()
                .filter { extension ->
                    val problem = checkExtension(candidate, extension)
                    if (problem != null) log.error("Paquete de extension {} omitido: {}", candidate.id, problem)
                    problem == null
                }
                .also { extensions -> log.info("Loaded extension package {}: {}", candidate.jar, extensions.map { it.manifest.id }) }
        } catch (failure: Exception) {
            log.error("No se pudo cargar el paquete de extension {}: {}", candidate.id, failure.message)
            emptyList<ServerExtension>()
        }
    }

    private fun sha256(file: Path): String = Files.newInputStream(file).use { input ->
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
