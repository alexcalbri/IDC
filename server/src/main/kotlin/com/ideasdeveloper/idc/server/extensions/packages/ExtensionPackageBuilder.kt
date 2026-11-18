package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionSignatureFile
import com.ideasdeveloper.idc.server.extensions.ExtensionSourceType
import com.ideasdeveloper.idc.server.extensions.ServerExtensionProvider
import com.ideasdeveloper.idc.server.modules.GitHubAuth
import com.ideasdeveloper.idc.server.modules.GitHubLocation
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** Paquete externo compilado y guardado; el servidor lo carga en el siguiente arranque. */
data class BuiltExtension(
    val jar: Path,
    val manifest: ExtensionManifest,
    val packageSha256: String,
    val signed: Boolean,
    val keyId: String?,
    /** https://github.com/<owner>/<repo>/tree/<commit>/extensions/<id> del codigo que se instalo. */
    val sourceUrl: String,
    /** SHA-256 del jar; el arranque lo comprueba antes de cargarlo. */
    val jarSha256: String,
    /** server_extensions.status: instalar un paquete nuevo pide reinicio. */
    val status: String = "pending_restart",
    /** Origen del paquete: github (catalogo), zip (subido) o url. Se guarda en server_extensions.source_type. */
    val sourceType: String = ExtensionSourceType.GitHub,
)

/**
 * Hash calculado, firma y origen de un paquete descargado. [signed] es true solo si extension.sig.json
 * trae una firma VERIFICADA sobre el hash calculado; [signatureProblem] explica por que no lo es cuando
 * el archivo trae una firma que no vale.
 */
data class InspectedPackage(
    val packageSha256: String,
    val signed: Boolean,
    val sourceUrl: String,
    /** Commit de 40 hex del que se descargo. */
    val commit: String,
    val keyId: String? = null,
    val signatureProblem: String? = null,
)

/** Lo que el servicio necesita del builder (se sustituye por un falso en las pruebas). */
interface ExtensionPackageBuilding {
    fun build(extensionId: String, source: GitHubLocation, progress: (String) -> Unit): BuiltExtension
    fun inspect(extensionId: String, source: GitHubLocation): InspectedPackage

    /**
     * Instala desde un ZIP ya en disco. [sourceUrl] y [sourceType] dicen de donde vino ("zip:<nombre>" y zip por
     * defecto; la URL y url cuando el ZIP se descargo antes). Pasa por el mismo verify (firma o hash autorizado).
     */
    fun buildFromZip(
        extensionId: String,
        zip: Path,
        progress: (String) -> Unit,
        sourceUrl: String = ExtensionPackageBuilder.zipLabel(zip.fileName?.toString()),
        sourceType: String = ExtensionSourceType.Zip,
    ): BuiltExtension = throw ExtensionPackageException("Este servidor no puede instalar paquetes desde un ZIP.")

    /** Descarga el ZIP de [url] (https, anclado anti-SSRF) y lo instala como [buildFromZip]. */
    fun buildFromUrl(extensionId: String, url: String, progress: (String) -> Unit): BuiltExtension =
        throw ExtensionPackageException("Este servidor no puede instalar paquetes desde una URL.")

    /** Calcula hash y firma de un ZIP ya en disco, sin exigir firma ni autorizacion. */
    fun inspectZip(
        extensionId: String,
        zip: Path,
        sourceUrl: String = ExtensionPackageBuilder.zipLabel(zip.fileName?.toString()),
    ): InspectedPackage = throw ExtensionPackageException("Este servidor no puede revisar paquetes desde un ZIP.")

    /** Descarga [url] en [target] con el cliente anclado anti-SSRF (solo https, tamano acotado). */
    fun downloadZip(url: String, target: Path): Path =
        throw ExtensionPackageException("Este servidor no puede descargar paquetes desde una URL.")
}

/** Resultado de comprobar hash y firma de una carpeta de extension. */
data class VerifiedPackage(val manifest: ExtensionManifest, val packageSha256: String, val signed: Boolean, val keyId: String?)

/**
 * Descarga, verifica, valida, compila y empaqueta una extension externa. Sigue el mismo camino que
 * ModulePackageBuilder (kotlinc de compiler/ contra el classpath del servidor), pero:
 *
 * - solo se extrae extensions/<id>/ del zip de un commit;
 * - antes de compilar se comprueba el hash del arbol contra extension.sig.json y la firma Ed25519; sin
 *   firma, solo sigue si el dueno del servidor autorizo ese hash ([authorizations]);
 * - el classpath incluye el jar del modulo del que depende ([moduleJar]; null si el modulo es base y ya
 *   esta en el classpath del servidor).
 *
 * Estructura del paquete: extension.json, extension.sig.json, migrations/, server/src/main/kotlin/
 * com/ideasdeveloper/idc/extensions/<id>/ con <Id>ServerExtensionProvider, y opcionalmente
 * server/src/main/resources. En el jar, extension.json y las migraciones quedan bajo
 * extensions/<id>/ (los migrationPaths de la extension deben apuntar a extensions/<id>/migrations/...).
 */
class ExtensionPackageBuilder(
    private val packagesRoot: Path,
    private val compilerDir: Path,
    private val compileClasspath: String,
    private val javaExecutable: String,
    private val validator: ExtensionManifestValidator,
    private val verifier: ExtensionSignatureVerifier,
    private val authorizations: UnsignedAuthorizationStore,
    private val moduleJar: (moduleId: String) -> Path?,
    private val githubToken: String? = null,
) : ExtensionPackageBuilding {
    private val log = LoggerFactory.getLogger(ExtensionPackageBuilder::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        // Las redirecciones se siguen a mano para no enviar el token a un host que no sea GitHub.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Descarga el paquete y calcula su hash sin exigir firma ni autorizacion: sirve para saber que
     * hash tendria que autorizar el dueno del servidor.
     */
    override fun inspect(extensionId: String, source: GitHubLocation): InspectedPackage {
        checkSource(extensionId, source)
        val work = Files.createDirectories(packagesRoot.resolve(extensionId)).resolve("inspect-${System.currentTimeMillis()}")
        Files.createDirectories(work)
        try {
            val archive = download("https://codeload.github.com/${source.owner}/${source.repo}/zip/${source.ref}", work.resolve("source.zip"))
            val packageDir = extract(archive, "${source.path.trim('/')}/$extensionId", work.resolve("source"))
            return inspectDirectory(packageDir, "https://github.com/${source.owner}/${source.repo}/tree/${source.ref}/${source.path.trim('/')}/$extensionId", source.ref)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /** Hash del arbol y estado de la firma de una carpeta ya extraida. */
    private fun inspectDirectory(packageDir: Path, sourceUrl: String, commit: String): InspectedPackage {
        val hash = ExtensionTreeHash.compute(packageDir)
        val signature = packageDir.resolve(ExtensionTreeHash.SignatureFileName).takeIf { Files.isRegularFile(it) }
            ?.let { runCatching { json.decodeFromString(ExtensionSignatureFile.serializer(), it.readText()) }.getOrNull() }
            ?.takeIf { it.signature != null }
        val problem = when {
            signature == null -> null
            !signature.packageSha256.equals(hash, ignoreCase = true) -> "el hash de extension.sig.json no coincide con el contenido"
            else -> verifier.verify(hash, signature.signature!!)
        }
        return InspectedPackage(
            packageSha256 = hash,
            signed = signature != null && problem == null,
            sourceUrl = sourceUrl,
            commit = commit,
            keyId = signature?.signature?.keyId,
            signatureProblem = problem,
        )
    }

    private fun checkId(extensionId: String) {
        if (!com.ideasdeveloper.idc.server.extensions.ExtensionIdPattern.matches(extensionId)) {
            throw ExtensionPackageException("Identificador de extension invalido.")
        }
    }

    /** Extrae el ZIP con las protecciones de [ExtensionPackageSafety] y convierte los errores de ZIP en un mensaje claro. */
    private fun extractZip(zip: Path, extensionId: String, target: Path): Path = try {
        ExtensionPackageSafety.extractGeneric(zip, extensionId, target)
    } catch (failure: java.io.IOException) {
        throw ExtensionPackageException("El archivo no es un ZIP valido o esta danado.", failure)
    }

    override fun inspectZip(extensionId: String, zip: Path, sourceUrl: String): InspectedPackage {
        checkId(extensionId)
        val work = Files.createDirectories(packagesRoot.resolve(extensionId)).resolve("inspect-${System.currentTimeMillis()}")
        Files.createDirectories(work)
        try {
            return inspectDirectory(extractZip(zip, extensionId, work.resolve("source")), sourceUrl, "")
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    override fun downloadZip(url: String, target: Path): Path =
        ExtensionUrlDownloader.download(url, target, ExtensionUrlDownloader.DefaultMaxBytes)

    override fun buildFromZip(extensionId: String, zip: Path, progress: (String) -> Unit, sourceUrl: String, sourceType: String): BuiltExtension {
        checkId(extensionId)
        // No hay commit que comprobar: la garantia real es verify (firma Ed25519 o autorizacion del dueno por hash).
        val work = Files.createDirectories(packagesRoot.resolve(extensionId)).resolve("build-${System.currentTimeMillis()}")
        Files.createDirectories(work)
        try {
            progress("Extrayendo el ZIP")
            val packageDir = extractZip(zip, extensionId, work.resolve("source"))
            return buildFromDirectory(extensionId, packageDir, work, sourceUrl, progress).copy(sourceType = sourceType)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    override fun buildFromUrl(extensionId: String, url: String, progress: (String) -> Unit): BuiltExtension {
        checkId(extensionId)
        ExtensionUrlDownloader.validate(url)
        val work = Files.createDirectories(packagesRoot.resolve(extensionId)).resolve("download-${System.currentTimeMillis()}")
        Files.createDirectories(work)
        try {
            progress("Descargando el paquete")
            val archive = downloadZip(url.trim(), work.resolve("package.zip"))
            return buildFromZip(extensionId, archive, progress, url.trim(), ExtensionSourceType.Url)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private fun checkSource(extensionId: String, source: GitHubLocation) {
        if (!com.ideasdeveloper.idc.server.extensions.ExtensionIdPattern.matches(extensionId)) {
            throw ExtensionPackageException("Identificador de extension invalido.")
        }
        if (!CommitPattern.matches(source.ref)) throw ExtensionPackageException("El paquete debe instalarse desde un commit, no desde una rama.")
    }

    /** Descarga [extensionId] del commit de [source] (carpeta extensions/ fijada a un commit) y lo compila. */
    override fun build(extensionId: String, source: GitHubLocation, progress: (String) -> Unit): BuiltExtension {
        checkSource(extensionId, source)
        val work = Files.createDirectories(packagesRoot.resolve(extensionId)).resolve("build-${System.currentTimeMillis()}")
        Files.createDirectories(work)
        try {
            progress("Descargando el codigo del commit ${source.ref.take(12)}")
            val archive = download("https://codeload.github.com/${source.owner}/${source.repo}/zip/${source.ref}", work.resolve("source.zip"))
            val packageDir = extract(archive, "${source.path.trim('/')}/$extensionId", work.resolve("source"))
            val sourceUrl = "https://github.com/${source.owner}/${source.repo}/tree/${source.ref}/${source.path.trim('/')}/$extensionId"
            return buildFromDirectory(extensionId, packageDir, work, sourceUrl, progress)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /** Verifica, valida, compila y empaqueta una carpeta ya extraida. */
    fun buildFromDirectory(extensionId: String, packageDir: Path, work: Path, sourceUrl: String, progress: (String) -> Unit): BuiltExtension {
        progress("Verificando hash y firma")
        val verified = verify(extensionId, packageDir, sourceUrl)

        progress("Validando el manifiesto y las migraciones")
        val problems = validator.validate(verified.manifest, extensionId, packageDir) + structureProblems(extensionId, packageDir)
        if (problems.isNotEmpty()) throw ExtensionPackageException("La extension no cumple las reglas: " + problems.joinToString(" "))

        progress("Compilando contra el nucleo y el modulo ${verified.manifest.module}")
        val classes = Files.createDirectories(work.resolve("classes"))
        compile(packageDir.resolve("server/src/main/kotlin"), classes, work, verified.manifest.module)

        progress("Empaquetando")
        val folder = Files.createDirectories(packagesRoot.resolve(extensionId))
        val temporary = work.resolve("$extensionId.jar")
        writeJar(verified.manifest, packageDir, classes, temporary)
        // El nombre lleva parte del hash del arbol: reinstalar otro contenido nunca pisa el jar que esta cargado.
        // El jar anterior lo borra el servicio cuando el registro nuevo ya se guardo.
        val target = folder.resolve("$extensionId-${verified.manifest.version}-${verified.packageSha256.take(12)}.jar")
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        return BuiltExtension(target, verified.manifest, verified.packageSha256, verified.signed, verified.keyId, sourceUrl, sha256(target))
    }

    /**
     * Comprueba el hash del arbol contra extension.sig.json y la firma. Sin firma exige una
     * autorizacion del dueno del servidor para ese hash. Lanza [ExtensionPackageException] si falla.
     */
    fun verify(extensionId: String, packageDir: Path, sourceUrl: String = ""): VerifiedPackage {
        val manifestFile = packageDir.resolve("extension.json")
        val manifest = runCatching { json.decodeFromString(ExtensionManifest.serializer(), manifestFile.readText()) }.getOrNull()
            ?: throw ExtensionPackageException("La extension no tiene un extension.json valido.")
        val actual = ExtensionTreeHash.compute(packageDir)
        val signatureFile = packageDir.resolve(ExtensionTreeHash.SignatureFileName).takeIf { Files.isRegularFile(it) }
            ?.let { runCatching { json.decodeFromString(ExtensionSignatureFile.serializer(), it.readText()) }.getOrNull() ?: throw ExtensionPackageException("extension.sig.json no es valido.") }

        if (signatureFile?.signature != null) {
            if (!signatureFile.packageSha256.equals(actual, ignoreCase = true)) {
                throw ExtensionPackageException("El hash del paquete no coincide con extension.sig.json: el contenido cambio despues de firmarlo.")
            }
            verifier.verify(actual, signatureFile.signature!!)?.let { throw ExtensionPackageException("Firma rechazada: $it.") }
            return VerifiedPackage(manifest, actual, true, signatureFile.signature!!.keyId)
        }
        if (signatureFile != null && !signatureFile.packageSha256.equals(actual, ignoreCase = true)) {
            throw ExtensionPackageException("El hash del paquete no coincide con extension.sig.json.")
        }
        if (!authorizations.isAuthorized(actual)) {
            throw ExtensionPackageException(
                "La extension '$extensionId' no esta firmada. El dueno del servidor debe autorizar el hash $actual antes de instalarla.",
            )
        }
        log.warn("Instalando la extension sin firma {} (hash {}, origen {}) por autorizacion del dueno del servidor.", extensionId, actual, sourceUrl)
        return VerifiedPackage(manifest, actual, false, null)
    }

    private fun structureProblems(extensionId: String, packageDir: Path): List<String> {
        val problems = ExtensionPackageSafety.contentProblems(packageDir).toMutableList()
        val kotlinRoot = packageDir.resolve("server/src/main/kotlin")
        val packagePath = "com/ideasdeveloper/idc/extensions/$extensionId/"
        val sources = if (Files.isDirectory(kotlinRoot)) kotlinRoot.walk().filter { it.isRegularFile() && it.extension == "kt" }.toList() else emptyList()
        if (sources.isEmpty()) problems += "no tiene codigo en server/src/main/kotlin."
        sources.forEach { file ->
            if (!file.relativeTo(kotlinRoot).invariantSeparatorsPathString.startsWith(packagePath)) {
                problems += "${file.name} debe estar en server/src/main/kotlin/$packagePath."
            }
        }
        val provider = providerClassName(extensionId).substringAfterLast('.')
        if (sources.none { it.readText().contains("class $provider") }) problems += "no declara $provider."
        return problems
    }

    private fun download(url: String, target: Path): Path {
        var current = url
        var response: HttpResponse<Path>
        var redirects = 0
        while (true) {
            val request = HttpRequest.newBuilder(URI.create(current)).timeout(Duration.ofMinutes(5)).GET()
            GitHubAuth.header(githubToken, current)?.let { request.header("Authorization", it) }
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofFile(target))
            val location = response.headers().firstValue("Location").orElse(null)
            if (response.statusCode() !in 300..399 || location == null) break
            if (++redirects > 5) throw ExtensionPackageException("Demasiadas redirecciones al descargar la extension.")
            current = URI.create(current).resolve(location).toString()
        }
        if (response.statusCode() != 200) throw ExtensionPackageException("No se pudo descargar el codigo de la extension (HTTP ${response.statusCode()}).")
        if (Files.size(target) > MaxArchiveBytes) throw ExtensionPackageException("El repositorio descargado es demasiado grande.")
        return target
    }

    // El zip de GitHub trae una carpeta raiz <repo>-<commit>/; solo se extrae <path>/<id>/ (con limites).
    private fun extract(archive: Path, packagePath: String, target: Path): Path =
        ExtensionPackageSafety.extract(archive, packagePath, target)

    private fun compile(sources: Path, classes: Path, work: Path, moduleId: String) {
        val compilerJars = Files.list(compilerDir).use { files -> files.filter { it.extension == "jar" }.toList() }
        val serializationPlugin = compilerJars.firstOrNull { it.name.startsWith("kotlin-serialization-compiler-plugin") }
            ?: throw ExtensionPackageException("Falta el plugin de serializacion en $compilerDir.")
        // La extension se compila tambien contra el jar del modulo del que depende.
        val classpath = listOfNotNull(compileClasspath, moduleJar(moduleId)?.toString()).joinToString(File.pathSeparator)
        val arguments = work.resolve("kotlinc.args")
        Files.write(
            arguments,
            listOf(
                "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                "-Xplugin=${quote(serializationPlugin.toString())}",
                "-classpath", quote(classpath),
                "-d", quote(classes.toString()),
                quote(sources.toString()),
            ),
        )
        val output = work.resolve("kotlinc.log").toFile()
        val process = ProcessBuilder(
            javaExecutable, "-Xmx1g", "-Duser.home=$work", "-Djava.io.tmpdir=$work",
            "-cp", compilerJars.joinToString(File.pathSeparator),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "@$arguments",
        ).redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw ExtensionPackageException("La compilacion tardo demasiado.")
        }
        if (process.exitValue() != 0) {
            val errors = output.readLines().filter { it.contains("error:") }.take(5)
                .joinToString(" | ") { it.replace(sources.toString() + File.separator, "").replace('\\', '/') }
            log.warn("Extension compilation failed:\n{}", output.readText())
            throw ExtensionPackageException("La extension no es compatible con este servidor: ${errors.ifBlank { "error de compilacion" }}")
        }
    }

    private fun writeJar(manifest: ExtensionManifest, packageDir: Path, classes: Path, target: Path) {
        val jarManifest = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }
        JarOutputStream(Files.newOutputStream(target), jarManifest).use { jar ->
            fun add(name: String, file: Path) {
                jar.putNextEntry(JarEntry(name))
                Files.copy(file, jar)
                jar.closeEntry()
            }
            classes.walk().filter { it.isRegularFile() }.forEach { add(it.relativeTo(classes).invariantSeparatorsPathString, it) }
            packageDir.resolve("server/src/main/resources").takeIf { Files.isDirectory(it) }?.let { resources ->
                resources.walk().filter { it.isRegularFile() }.forEach { add(it.relativeTo(resources).invariantSeparatorsPathString, it) }
            }
            // Solo las migraciones que el manifiesto declara y el validador reviso.
            manifest.migrations.forEach { path -> add("extensions/${manifest.id}/$path", packageDir.resolve(path)) }
            add("extensions/${manifest.id}/extension.json", packageDir.resolve("extension.json"))
            jar.putNextEntry(JarEntry("META-INF/services/${ServerExtensionProvider::class.java.name}"))
            jar.write((providerClassName(manifest.id) + "\n").toByteArray())
            jar.closeEntry()
        }
    }

    private fun sha256(file: Path): String = Files.newInputStream(file).use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        java.util.HexFormat.of().formatHex(digest.digest())
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val MaxArchiveBytes = 300L * 1024 * 1024
        private val CommitPattern = Regex("[0-9a-f]{40}")
        private val UnsafeLabelChars = Regex("[^A-Za-z0-9._-]")

        /** Etiqueta de origen de un ZIP subido: "zip:<nombre>" solo con A-Za-z0-9._- y hasta 80 caracteres. */
        fun zipLabel(fileName: String?): String {
            val name = fileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            return "zip:" + name.replace(UnsafeLabelChars, "_").take(80).ifBlank { "paquete.zip" }
        }

        fun providerClassName(extensionId: String): String =
            "com.ideasdeveloper.idc.extensions.$extensionId." +
                extensionId.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } } + "ServerExtensionProvider"

        /** Igual que ModulePackageBuilder.forRunningServer: compiler/ junto a lib/ y el classpath del servidor en marcha. */
        fun forRunningServer(
            packagesRoot: Path,
            validator: ExtensionManifestValidator,
            verifier: ExtensionSignatureVerifier,
            authorizations: UnsignedAuthorizationStore,
            moduleJar: (String) -> Path?,
            githubToken: String? = null,
        ): ExtensionPackageBuilder? {
            val location = runCatching { Path.of(ExtensionPackageBuilder::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
                ?: return null
            val compilerDir = location.parent?.parent?.resolve("compiler")?.takeIf { Files.isDirectory(it) } ?: return null
            val java = ProcessHandle.current().info().command().orElse("java")
            return ExtensionPackageBuilder(
                packagesRoot, compilerDir, System.getProperty("java.class.path"), java,
                validator, verifier, authorizations, moduleJar, githubToken,
            )
        }
    }
}
