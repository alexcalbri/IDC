package com.ideasdeveloper.idc.server.modules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.ServiceLoader
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** Folder of a GitHub repository that holds the module catalog, e.g. .../tree/develop/modules. */
data class GitHubLocation(val owner: String, val repo: String, val ref: String, val path: String) {
    // One request downloads the whole branch; only the module's folder is extracted.
    val archiveUrl: String
        get() = "https://codeload.github.com/$owner/$repo/zip/$ref"

    companion object {
        private val treeUrl = Regex("""^https://github\.com/([^/]+)/([^/]+)/tree/([^/]+)/(.+?)/?$""")
        private val contentsUrl = Regex("""^https://api\.github\.com/repos/([^/]+)/([^/]+)/contents/([^?]+?)/?(\?(.*))?$""")

        fun parse(url: String): GitHubLocation? {
            val value = url.trim()
            treeUrl.matchEntire(value)?.let { match ->
                val (owner, repo, ref, path) = match.destructured
                return GitHubLocation(owner, repo.removeSuffix(".git"), ref, path.trim('/'))
            }
            contentsUrl.matchEntire(value)?.let { match ->
                val ref = match.groupValues[5].split('&')
                    .firstOrNull { it.startsWith("ref=") }?.removePrefix("ref=")?.takeIf { it.isNotBlank() } ?: "HEAD"
                return GitHubLocation(match.groupValues[1], match.groupValues[2], ref, match.groupValues[3].trim('/'))
            }
            return null
        }
    }
}

class ModuleBuildException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class BuiltModule(val jar: Path, val version: String, val sha256: String)

/**
 * Builds an optional module from source into a package (.jar) that the server loads at startup.
 *
 * The module is compiled with the Kotlin compiler shipped in the distribution (compiler/) against
 * the classpath of the running server, so it uses exactly the installed core API. A module that
 * does not fit the installed core fails to compile and nothing is changed.
 */
class ModulePackageBuilder(
    private val packagesRoot: Path,
    private val compilerDir: Path,
    private val compileClasspath: String,
    private val javaExecutable: String,
) {
    private val log = LoggerFactory.getLogger(ModulePackageBuilder::class.java)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Downloads [moduleId] from the catalog [source], builds it and returns the installed package. */
    fun build(moduleId: String, source: GitHubLocation, progress: (String) -> Unit): BuiltModule {
        val work = Files.createDirectories(packagesRoot.resolve(moduleId)).resolve("build-${System.currentTimeMillis()}")
        Files.createDirectories(work)
        try {
            progress("Descargando el codigo de ${source.ref}")
            val archive = download(source.archiveUrl, work.resolve("source.zip"))
            val moduleDir = extractModule(archive, "${source.path}/$moduleId", work.resolve("source"))
            return buildFromDirectory(moduleId, moduleDir, work, progress)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /** Validates, compiles and packages a module folder that follows the modules/<id>/ layout. */
    fun buildFromDirectory(moduleId: String, moduleDir: Path, work: Path, progress: (String) -> Unit): BuiltModule {
        progress("Validando la estructura del modulo")
        val manifest = validate(moduleId, moduleDir)
        val version = manifest["version"]!!.jsonPrimitive.content

        progress("Compilando contra el nucleo instalado")
        val classes = Files.createDirectories(work.resolve("classes"))
        compile(moduleDir.resolve("server/src/main/kotlin"), classes, work)

        progress("Empaquetando")
        val moduleFolder = Files.createDirectories(packagesRoot.resolve(moduleId))
        val temporary = work.resolve("$moduleId.jar")
        writeJar(moduleId, moduleDir, classes, temporary)
        val target = moduleFolder.resolve("$moduleId-$version.jar")
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        // Keep only the package that will be loaded.
        Files.list(moduleFolder).use { files ->
            files.filter { it.isRegularFile() && it.extension == "jar" && it != target }.forEach(Files::deleteIfExists)
        }
        return BuiltModule(target, version, sha256(target))
    }

    private fun download(url: String, target: Path): Path {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
            HttpResponse.BodyHandlers.ofFile(target),
        )
        if (response.statusCode() != 200) throw ModuleBuildException("No se pudo descargar el codigo del modulo (HTTP ${response.statusCode()}).")
        if (Files.size(target) > MaxArchiveBytes) throw ModuleBuildException("El repositorio descargado es demasiado grande.")
        return target
    }

    // GitHub archives contain a single top folder (<repo>-<ref>/); only <path>/<id>/ is extracted.
    private fun extractModule(archive: Path, modulePath: String, target: Path): Path {
        val prefix = modulePath.trim('/') + "/"
        val root = Files.createDirectories(target).toAbsolutePath().normalize()
        var extracted = 0L
        var found = false
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val relative = entry.name.substringAfter('/', "")
                if (!relative.startsWith(prefix)) return@forEach
                found = true
                val destination = root.resolve(relative.removePrefix(prefix)).normalize()
                // Rejects "../" entries that would write outside the build folder.
                if (!destination.startsWith(root)) throw ModuleBuildException("El paquete contiene rutas invalidas.")
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    extracted += Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING)
                    if (extracted > MaxModuleBytes) throw ModuleBuildException("El modulo es demasiado grande.")
                }
            }
        }
        if (!found) throw ModuleBuildException("La rama no contiene la carpeta $modulePath.")
        return root
    }

    private fun validate(moduleId: String, moduleDir: Path): JsonObject {
        val problems = mutableListOf<String>()
        val manifestFile = moduleDir.resolve("module.json")
        val manifest = runCatching { Json.parseToJsonElement(manifestFile.readText()).jsonObject }.getOrNull()
            ?: throw ModuleBuildException("El modulo no tiene un module.json valido.")
        if (manifest["id"]?.jsonPrimitive?.contentOrNull != moduleId) problems += "module.json debe tener id '$moduleId'."
        if (manifest["version"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) problems += "module.json requiere version."
        if (manifest["bundled"]?.jsonPrimitive?.booleanOrNull == true) problems += "es un modulo base; ya viene con el servidor."

        val kotlinRoot = moduleDir.resolve("server/src/main/kotlin")
        val packagePath = "com/ideasdeveloper/idc/modules/$moduleId/"
        val sources = if (Files.isDirectory(kotlinRoot)) kotlinRoot.walk().filter { it.isRegularFile() && it.extension == "kt" }.toList() else emptyList()
        if (sources.isEmpty()) problems += "no tiene codigo en server/src/main/kotlin."
        sources.forEach { file ->
            if (!file.relativeTo(kotlinRoot).invariantSeparatorsPathString.startsWith(packagePath)) {
                problems += "${file.name} debe estar en server/src/main/kotlin/$packagePath."
            }
        }
        val provider = providerClassName(moduleId)
        if (sources.none { it.readText().contains("class ${provider.substringAfterLast('.')}") }) {
            problems += "no declara ${provider.substringAfterLast('.')}."
        }
        moduleDir.resolve("migrations").takeIf { Files.isDirectory(it) }?.let { migrations ->
            Files.list(migrations).use { files ->
                files.filter { it.name != "README.md" }.forEach { file ->
                    val sql = file.readText().trim()
                    if (!MigrationName.matches(file.name)) problems += "migrations/${file.name} debe llamarse VNNN__descripcion.sql."
                    else if (!sql.startsWith("BEGIN;") || !sql.endsWith("COMMIT;")) problems += "migrations/${file.name} debe ir entre BEGIN; y COMMIT;."
                }
            }
        }
        if (problems.isNotEmpty()) throw ModuleBuildException("El modulo no cumple la estructura: " + problems.joinToString(" "))
        return manifest
    }

    private fun compile(sources: Path, classes: Path, work: Path) {
        val compilerJars = Files.list(compilerDir).use { files -> files.filter { it.extension == "jar" }.toList() }
        val serializationPlugin = compilerJars.firstOrNull { it.name.startsWith("kotlin-serialization-compiler-plugin") }
            ?: throw ModuleBuildException("Falta el plugin de serializacion en ${compilerDir}.")
        // An argument file keeps the long server classpath out of the command line.
        val arguments = work.resolve("kotlinc.args")
        Files.write(
            arguments,
            listOf(
                "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                "-Xplugin=${quote(serializationPlugin.toString())}",
                "-classpath", quote(compileClasspath),
                "-d", quote(classes.toString()),
                quote(sources.toString()),
            ),
        )
        val output = work.resolve("kotlinc.log").toFile()
        // The service runs with ProtectSystem=strict: keep the compiler's home and temp files in the build folder.
        val process = ProcessBuilder(
            javaExecutable, "-Xmx1g", "-Duser.home=$work", "-Djava.io.tmpdir=$work",
            "-cp", compilerJars.joinToString(File.pathSeparator),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "@$arguments",
        ).redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw ModuleBuildException("La compilacion tardo demasiado.")
        }
        if (process.exitValue() != 0) {
            // Paths relative to the module's Kotlin folder keep the message readable.
            val errors = output.readLines().filter { it.contains("error:") }.take(5)
                .joinToString(" | ") { it.replace(sources.toString() + File.separator, "").replace('\\', '/') }
            log.warn("Module compilation failed:\n{}", output.readText())
            throw ModuleBuildException("El modulo no es compatible con este servidor: ${errors.ifBlank { "error de compilacion" }}")
        }
    }

    private fun writeJar(moduleId: String, moduleDir: Path, classes: Path, target: Path) {
        val manifest = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }
        JarOutputStream(Files.newOutputStream(target), manifest).use { jar ->
            fun add(name: String, file: Path) {
                jar.putNextEntry(JarEntry(name))
                Files.copy(file, jar)
                jar.closeEntry()
            }
            classes.walk().filter { it.isRegularFile() }.forEach { add(it.relativeTo(classes).invariantSeparatorsPathString, it) }
            moduleDir.resolve("server/src/main/resources").takeIf { Files.isDirectory(it) }?.let { resources ->
                resources.walk().filter { it.isRegularFile() }.forEach { add(it.relativeTo(resources).invariantSeparatorsPathString, it) }
            }
            // Migrations keep the path used in migrationPaths so the server finds them in the package.
            moduleDir.resolve("migrations").takeIf { Files.isDirectory(it) }?.let { migrations ->
                Files.list(migrations).use { files ->
                    files.filter { it.extension == "sql" }.forEach { add("modules/$moduleId/migrations/${it.name}", it) }
                }
            }
            add("modules/$moduleId/module.json", moduleDir.resolve("module.json"))
            jar.putNextEntry(JarEntry("META-INF/services/${ServerModuleProvider::class.java.name}"))
            jar.write((providerClassName(moduleId) + "\n").toByteArray())
            jar.closeEntry()
        }
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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

    companion object {
        private const val MaxArchiveBytes = 300L * 1024 * 1024
        private const val MaxModuleBytes = 50L * 1024 * 1024
        private val MigrationName = Regex("V[0-9]{3}__[a-z0-9_]+\\.sql")

        fun providerClassName(moduleId: String): String =
            "com.ideasdeveloper.idc.modules.$moduleId." +
                moduleId.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } } + "ServerModuleProvider"

        /**
         * Builder for the installed server: compiler/ next to lib/ in the distribution and the
         * running server's classpath. Returns null outside an installed distribution (e.g. :server:run).
         */
        fun forRunningServer(packagesRoot: Path): ModulePackageBuilder? {
            val location = runCatching { Path.of(ModulePackageBuilder::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
                ?: return null
            val compilerDir = location.parent?.parent?.resolve("compiler")?.takeIf { Files.isDirectory(it) } ?: return null
            val java = ProcessHandle.current().info().command().orElse("java")
            return ModulePackageBuilder(packagesRoot, compilerDir, System.getProperty("java.class.path"), java)
        }
    }
}

/** Loads module packages, each in its own class loader on top of the server's. */
object ModulePackageLoader {
    private val log = LoggerFactory.getLogger(ModulePackageLoader::class.java)

    fun load(jar: Path, parent: ClassLoader = ModulePackageLoader::class.java.classLoader): List<ServerModule> {
        val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), parent)
        // The child loader also sees the server's own providers; keep only the package's.
        return ServiceLoader.load(ServerModuleProvider::class.java, loader).stream()
            .filter { it.type().classLoader == loader }
            .map { it.get().create() }
            .toList()
            .also { modules -> log.info("Loaded module package {}: {}", jar, modules.map { it.definition.id }) }
    }
}
