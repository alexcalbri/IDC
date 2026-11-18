package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.ModuleVersion
import com.ideasdeveloper.idc.server.extensions.ExtensionSourceType
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class ExtensionPackageServiceException(message: String, val code: String = "EXTENSION_PACKAGE_ERROR") : Exception(message)

/** Una extension del catalogo con su estado en este servidor. Sin secretos. */
@Serializable
data class ExtensionPackageEntry(
    val id: String,
    val module: String,
    val displayName: String,
    val description: String,
    val version: String,
    val minModuleVersion: String? = null,
    val permissions: List<String> = emptyList(),
    val signed: Boolean,
    val keyId: String? = null,
    val packageSha256: String? = null,
    /** Commit de 40 hex del catalogo y su URL en GitHub. */
    val commit: String,
    val commitUrl: String,
    val status: String,
    val installedVersion: String? = null,
    val message: String? = null,
    /** El paquete requiere reiniciar el servidor para cargarse. */
    val restartRequired: Boolean = false,
)

@Serializable
data class ExtensionPackageStatus(
    val id: String,
    /** not_installed | installing | error | pending_restart | installed | removed */
    val status: String,
    val installedVersion: String? = null,
    val message: String? = null,
    val restartRequired: Boolean = false,
)

/** Lo que se descarga hoy de un paquete: commit, URL y hash del arbol calculado. Antes de autorizar sin firma. */
@Serializable
data class ExtensionPackageInspection(
    val id: String,
    val commit: String,
    val commitUrl: String,
    /** Hash del arbol calculado sobre lo descargado (no el declarado en extension.sig.json). */
    val packageSha256: String,
    /** true solo con una firma verificada sobre ese hash. */
    val signed: Boolean,
    val keyId: String? = null,
    /** Por que una firma presente no vale (hash distinto, llave desconocida...). */
    val signatureProblem: String? = null,
)

/**
 * Un paquete (ZIP subido o descargado de una URL) guardado en preparacion, ya revisado. El dueno ve el hash,
 * lo autoriza si no esta firmado ([installable] false) y despues instala con [stagingToken]. Sin secretos.
 */
@Serializable
data class ExtensionPackageStagedInspection(
    val id: String,
    /** zip | url */
    val source: String,
    /** "zip:<nombre>" o la URL de descarga. */
    val sourceUrl: String,
    /** Identifica el archivo ya guardado en el servidor; caduca a los [ExtensionPackageService.StagingMinutes] minutos. */
    val stagingToken: String,
    /** Hash del arbol calculado sobre lo recibido (el que hay que autorizar si no esta firmado). */
    val packageSha256: String,
    /** true solo con una firma verificada sobre ese hash. */
    val signed: Boolean,
    val keyId: String? = null,
    val signatureProblem: String? = null,
    /** El paquete ya se puede instalar: esta firmado o su hash ya esta autorizado. */
    val installable: Boolean,
)

@Serializable
data class UnsignedAuthorizationResponse(val id: String, val packageSha256: String, val sourceUrl: String)

/** Acciones de auditoria (provisioning_audit_log). */
object ExtensionPackageAudit {
    const val Install = "extension.package.install"
    const val AuthorizeUnsigned = "extension.package.authorize_unsigned"
    const val Remove = "extension.package.remove"
}

/**
 * Instala, autoriza y quita paquetes externos de extensiones (solo el dueno del servidor). El estado
 * de una instalacion en curso o fallida vive en memoria, como el de los modulos; lo instalado, en
 * server_extensions. Instalar un paquete nuevo pide reinicio ([requestRestart]) para cargarlo.
 */
class ExtensionPackageService(
    private val store: ExtensionPackageStore,
    /** Carpeta donde se guardan los jars; el arranque no carga nada fuera de ella. */
    private val packagesRoot: Path,
    /** Ids de las extensiones incluidas en sus modulos: un paquete externo no puede usarlos. */
    private val bundledIds: () -> Set<String>,
    private val catalogSource: () -> List<RemoteExtensionPackage>,
    private val builder: ExtensionPackageBuilding?,
    private val authorizations: UnsignedAuthorizationStore,
    private val requestRestart: () -> Unit,
    /** (actorRole, action, companyCode, moduleId, detailsJson) -> Unit */
    private val audit: (String, String, String?, String?, String) -> Unit,
    private val executor: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "extension-package-build").apply { isDaemon = true } },
) {
    private val log = LoggerFactory.getLogger(ExtensionPackageService::class.java)

    private class InstallState(val status: String, val message: String)

    private val states = ConcurrentHashMap<String, InstallState>()

    fun catalog(): List<ExtensionPackageEntry> {
        val stored = store.all().associateBy { it.id }
        return catalogSource().map { remote ->
            val manifest = remote.manifest
            val status = status(manifest.id, stored[manifest.id])
            ExtensionPackageEntry(
                id = manifest.id,
                module = manifest.module,
                displayName = manifest.displayName,
                description = manifest.description,
                version = manifest.version,
                minModuleVersion = manifest.minModuleVersion,
                permissions = manifest.permissions,
                signed = remote.signed,
                keyId = remote.keyId,
                packageSha256 = remote.packageSha256,
                commit = remote.commit,
                commitUrl = remote.commitUrl,
                status = status.status,
                installedVersion = status.installedVersion,
                message = status.message,
                restartRequired = status.restartRequired,
            )
        }
    }

    fun status(id: String): ExtensionPackageStatus = status(id, store.find(id))

    private fun status(id: String, stored: StoredExtensionPackage?): ExtensionPackageStatus {
        states[id]?.let { state ->
            return ExtensionPackageStatus(id, state.status, stored?.version.takeIf { stored?.status != "removed" }, state.message)
        }
        if (stored == null) return ExtensionPackageStatus(id, "not_installed")
        return ExtensionPackageStatus(
            id = id,
            status = stored.status,
            installedVersion = stored.version.takeIf { stored.status != "removed" },
            restartRequired = stored.status == "pending_restart",
        )
    }

    /** Empieza a compilar el paquete en un hilo aparte y devuelve el estado inicial. */
    fun install(id: String, actorRole: String): ExtensionPackageStatus {
        val remote = catalogSource().firstOrNull { it.manifest.id == id }
            ?: throw ExtensionPackageServiceException("La extension '$id' no esta en el catalogo configurado.", "NOT_FOUND")
        val stored = store.find(id)
        if (id in bundledIds() || stored?.sourceType == ExtensionSourceType.Bundled) {
            throw ExtensionPackageServiceException("La extension '$id' ya viene incluida en su modulo.", "ALREADY_BUNDLED")
        }
        checkNoDowngrade(id, remote.manifest.version, stored)
        val activeBuilder = builder
            ?: throw ExtensionPackageServiceException(
                "Este servidor no puede compilar extensiones: falta la carpeta compiler/ de la instalacion. Actualiza el servidor con update.sh.",
                "BUILDER_UNAVAILABLE",
            )
        val queued = InstallState("installing", "En cola para compilar")
        if (states.compute(id) { _, current -> if (current?.status == "installing") current else queued } !== queued) return status(id)
        executor.execute { buildAndStore(id, activeBuilder, actorRole) { active, progress -> active.build(id, remote.source, progress) } }
        return status(id)
    }

    // ---- Paquetes desde un ZIP subido o una URL ----

    /** Un ZIP guardado en [stagingDir] a la espera de autorizar/instalar. */
    private class Staged(val id: String, val kind: String, val file: Path, val sourceUrl: String, val sha256: String, val createdAt: Long)

    private val staged = ConcurrentHashMap<String, Staged>()
    private val stagingDir: Path get() = packagesRoot.resolve(StagingFolder)
    private val random = SecureRandom()

    private fun checkExternalId(id: String) {
        if (!com.ideasdeveloper.idc.server.extensions.ExtensionIdPattern.matches(id)) {
            throw ExtensionPackageServiceException("Identificador de extension invalido.", "INVALID_ID")
        }
        if (id in bundledIds() || store.find(id)?.sourceType == ExtensionSourceType.Bundled) {
            throw ExtensionPackageServiceException("La extension '$id' ya viene incluida en su modulo.", "ALREADY_BUNDLED")
        }
    }

    private fun requireBuilder(): ExtensionPackageBuilding = builder
        ?: throw ExtensionPackageServiceException(
            "Este servidor no puede compilar extensiones: falta la carpeta compiler/ de la instalacion. Actualiza el servidor con update.sh.",
            "BUILDER_UNAVAILABLE",
        )

    /** Borra los paquetes en preparacion que caducaron (y los archivos sueltos viejos de la carpeta). */
    private fun purgeStaging() {
        val limit = System.currentTimeMillis() - StagingMinutes * 60_000L
        staged.entries.removeIf { (_, item) ->
            (item.createdAt < limit).also { expired -> if (expired) runCatching { Files.deleteIfExists(item.file) } }
        }
        runCatching {
            if (Files.isDirectory(stagingDir)) {
                Files.list(stagingDir).use { files ->
                    files.filter { Files.getLastModifiedTime(it).toMillis() < limit }.toList()
                }.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun newToken(): String = ByteArray(24).also(random::nextBytes).let { java.util.HexFormat.of().formatHex(it) }

    /** Guarda [file] en preparacion, calcula su hash/firma y devuelve lo que el dueno debe revisar. */
    private fun stage(id: String, kind: String, file: Path, sourceUrl: String, active: ExtensionPackageBuilding): ExtensionPackageStagedInspection {
        val inspected = try {
            active.inspectZip(id, file, sourceUrl)
        } catch (failure: ExtensionPackageException) {
            runCatching { Files.deleteIfExists(file) }
            throw ExtensionPackageServiceException(failure.message ?: "El paquete no es valido.", "PACKAGE_REJECTED")
        }
        val token = file.fileName.toString().removeSuffix(".zip")
        staged[token] = Staged(id, kind, file, sourceUrl, inspected.packageSha256, System.currentTimeMillis())
        return ExtensionPackageStagedInspection(
            id = id, source = kind, sourceUrl = sourceUrl, stagingToken = token,
            packageSha256 = inspected.packageSha256, signed = inspected.signed, keyId = inspected.keyId,
            signatureProblem = inspected.signatureProblem,
            installable = inspected.signed || authorizations.isAuthorized(inspected.packageSha256),
        )
    }

    /** Guarda los bytes de un ZIP subido por el dueno y lo revisa (hash y firma). No instala nada. */
    fun inspectZip(id: String, fileName: String?, bytes: ByteArray): ExtensionPackageStagedInspection {
        checkExternalId(id)
        val active = requireBuilder()
        if (bytes.isEmpty()) throw ExtensionPackageServiceException("El archivo esta vacio.", "INVALID_ZIP")
        if (bytes.size > MaxZipBytes) throw ExtensionPackageServiceException("El ZIP es demasiado grande (maximo ${MaxZipBytes / 1024 / 1024} MB).", "PAYLOAD_TOO_LARGE")
        purgeStaging()
        val file = Files.createDirectories(stagingDir).resolve("${newToken()}.zip")
        Files.write(file, bytes)
        return stage(id, ExtensionSourceType.Zip, file, ExtensionPackageBuilder.zipLabel(fileName), active)
    }

    /** Descarga el ZIP de [url] (https, anclado anti-SSRF, tamano acotado) y lo revisa. No instala nada. */
    fun inspectUrl(id: String, url: String): ExtensionPackageStagedInspection {
        checkExternalId(id)
        val active = requireBuilder()
        val clean = url.trim()
        try {
            ExtensionUrlDownloader.validate(clean)
        } catch (failure: ExtensionPackageException) {
            throw ExtensionPackageServiceException(failure.message ?: "La URL no es valida.", "URL_REJECTED")
        }
        purgeStaging()
        val file = Files.createDirectories(stagingDir).resolve("${newToken()}.zip")
        try {
            active.downloadZip(clean, file)
        } catch (failure: ExtensionPackageException) {
            runCatching { Files.deleteIfExists(file) }
            throw ExtensionPackageServiceException(failure.message ?: "No se pudo descargar el paquete.", "PACKAGE_UNAVAILABLE")
        }
        return stage(id, ExtensionSourceType.Url, file, clean, active)
    }

    private fun stagedFor(token: String): Staged {
        val item = staged[token.trim()]
        if (item == null || !Files.isRegularFile(item.file) || item.createdAt < System.currentTimeMillis() - StagingMinutes * 60_000L) {
            throw ExtensionPackageServiceException("El paquete en preparacion no existe o caduco. Subelo otra vez.", "NOT_FOUND")
        }
        return item
    }

    /**
     * Autoriza por hash un paquete sin firma ya revisado ([inspectZip]/[inspectUrl]). Igual que [authorizeUnsigned]:
     * confirmacion escrita y el hash que el dueno reviso ([expectedPackageSha256]); lo guardado se vuelve a revisar.
     */
    fun authorizeStaged(token: String, actorRole: String, confirmationText: String, expectedPackageSha256: String): UnsignedAuthorizationResponse {
        val text = confirmationText.trim()
        if (text.length < MinConfirmationLength) {
            throw ExtensionPackageServiceException("Escribe la confirmacion con tus palabras (al menos $MinConfirmationLength caracteres).", "CONFIRMATION_REQUIRED")
        }
        if (!Sha256Pattern.matches(expectedPackageSha256.trim())) {
            throw ExtensionPackageServiceException("Indica el hash (64 hex) que revisaste.", "EXPECTED_REQUIRED")
        }
        // La confirmacion debe nombrar el hash revisado (primeros 8 hex), como hace la app: "autorizar {id} {sha8}".
        val shortHash = expectedPackageSha256.trim().take(ShortHashLength)
        if (!text.contains(shortHash, ignoreCase = true)) {
            throw ExtensionPackageServiceException(
                "La confirmacion debe incluir los primeros $ShortHashLength caracteres del hash del paquete (${shortHash.lowercase()}).", "CONFIRMATION_HASH_REQUIRED",
            )
        }
        val item = stagedFor(token)
        checkExternalId(item.id)
        val inspected = try {
            requireBuilder().inspectZip(item.id, item.file, item.sourceUrl)
        } catch (failure: ExtensionPackageException) {
            throw ExtensionPackageServiceException(failure.message ?: "El paquete no es valido.", "PACKAGE_REJECTED")
        }
        if (!inspected.packageSha256.equals(expectedPackageSha256.trim(), ignoreCase = true)) {
            throw ExtensionPackageServiceException(
                "El paquete cambio desde que lo revisaste (ahora: hash ${inspected.packageSha256}). Revisalo otra vez.", "PACKAGE_CHANGED",
            )
        }
        if (inspected.signed) {
            throw ExtensionPackageServiceException("La extension '${item.id}' esta firmada; no necesita autorizacion.", "ALREADY_SIGNED")
        }
        inspected.signatureProblem?.let {
            throw ExtensionPackageServiceException("La extension '${item.id}' trae una firma que no vale ($it); no se puede autorizar.", "SIGNATURE_INVALID")
        }
        authorizations.authorize(inspected.packageSha256, item.id, item.sourceUrl, actorRole, text)
        audit(
            actorRole, ExtensionPackageAudit.AuthorizeUnsigned, null, null,
            buildJsonObject {
                put("extensionId", item.id)
                put("packageSha256", inspected.packageSha256)
                put("source", item.kind)
                put("sourceUrl", item.sourceUrl)
                put("confirmationText", text)
            }.toString(),
        )
        return UnsignedAuthorizationResponse(item.id, inspected.packageSha256, item.sourceUrl)
    }

    /** Instala el paquete en preparacion. Sin firma ni hash autorizado, la compilacion falla en verify (igual que con GitHub). */
    fun installStaged(token: String, actorRole: String): ExtensionPackageStatus {
        val item = stagedFor(token)
        return enqueueExternal(item.id, actorRole, onStored = { staged.remove(token.trim()); runCatching { Files.deleteIfExists(item.file) } }) { active, progress ->
            active.buildFromZip(item.id, item.file, progress, item.sourceUrl, item.kind)
        }
    }

    /** Instala un ZIP que ya esta en disco (sin pasar por preparacion). Exige firma o hash autorizado, como [install]. */
    fun installFromZip(id: String, zip: Path, actorRole: String): ExtensionPackageStatus =
        enqueueExternal(id, actorRole) { active, progress -> active.buildFromZip(id, zip, progress) }

    /** Descarga el ZIP de [url] y lo instala. Exige firma o hash autorizado, como [install]. */
    fun installFromUrl(id: String, url: String, actorRole: String): ExtensionPackageStatus {
        try {
            ExtensionUrlDownloader.validate(url)
        } catch (failure: ExtensionPackageException) {
            throw ExtensionPackageServiceException(failure.message ?: "La URL no es valida.", "URL_REJECTED")
        }
        return enqueueExternal(id, actorRole) { active, progress -> active.buildFromUrl(id, url.trim(), progress) }
    }

    private fun enqueueExternal(
        id: String,
        actorRole: String,
        onStored: () -> Unit = {},
        produce: (ExtensionPackageBuilding, (String) -> Unit) -> BuiltExtension,
    ): ExtensionPackageStatus {
        checkExternalId(id)
        val activeBuilder = requireBuilder()
        val queued = InstallState("installing", "En cola para compilar")
        if (states.compute(id) { _, current -> if (current?.status == "installing") current else queued } !== queued) return status(id)
        executor.execute { buildAndStore(id, activeBuilder, actorRole, onStored, produce) }
        return status(id)
    }

    private fun checkNoDowngrade(id: String, version: String, stored: StoredExtensionPackage?) {
        if (stored != null && stored.status != "removed" && ModuleVersion.compare(version, stored.version) < 0) {
            throw ExtensionPackageServiceException(
                "La extension '$id' ya esta en la version ${stored.version}; no se instala una anterior ($version).", "DOWNGRADE",
            )
        }
    }

    private fun deleteOldJar(previous: StoredExtensionPackage?, built: BuiltExtension) {
        val old = previous?.installedPackagePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return
        if (old == built.jar.toAbsolutePath().normalize() || !old.startsWith(packagesRoot.toAbsolutePath().normalize())) return
        runCatching { Files.deleteIfExists(old) }.onFailure { log.warn("No se pudo borrar el jar anterior {}: {}", old, it.message) }
    }

    private fun buildAndStore(
        id: String,
        builder: ExtensionPackageBuilding,
        actorRole: String,
        onStored: () -> Unit = {},
        produce: (ExtensionPackageBuilding, (String) -> Unit) -> BuiltExtension,
    ) {
        try {
            val built = produce(builder) { step -> states[id] = InstallState("installing", step) }
            val previous = store.find(id)
            check(built.manifest.id == id) { "El paquete compilado declara otro id." }
            checkNoDowngrade(id, built.manifest.version, previous)
            store.upsert(built)
            states.remove(id)
            onStored()
            // El jar anterior se borra solo despues de que el registro nuevo quedo guardado.
            deleteOldJar(previous, built)
            audit(
                actorRole, ExtensionPackageAudit.Install, null, built.manifest.module,
                buildJsonObject {
                    put("extensionId", id)
                    put("version", built.manifest.version)
                    put("packageSha256", built.packageSha256)
                    put("signed", built.signed)
                    put("sourceUrl", built.sourceUrl)
                    put("jarSha256", built.jarSha256)
                }.toString(),
            )
            log.info("Extension package {} {} built; restarting to load it.", id, built.manifest.version)
            requestRestart()
        } catch (failure: Exception) {
            log.warn("Extension package {} could not be installed: {}", id, failure.message)
            states[id] = InstallState("error", failure.message ?: "No se pudo instalar la extension.")
        }
    }

    /** Descarga el paquete y devuelve commit, URL y hash del arbol calculado, para revisarlo antes de autorizar. */
    fun inspect(id: String): ExtensionPackageInspection {
        val remote = catalogSource().firstOrNull { it.manifest.id == id }
            ?: throw ExtensionPackageServiceException("La extension '$id' no esta en el catalogo configurado.", "NOT_FOUND")
        val activeBuilder = builder
            ?: throw ExtensionPackageServiceException("Este servidor no puede revisar paquetes: falta la carpeta compiler/ de la instalacion.", "BUILDER_UNAVAILABLE")
        val inspected = try {
            activeBuilder.inspect(id, remote.source)
        } catch (failure: ExtensionPackageException) {
            throw ExtensionPackageServiceException(failure.message ?: "No se pudo descargar el paquete.", "PACKAGE_UNAVAILABLE")
        }
        return ExtensionPackageInspection(
            id = id,
            commit = inspected.commit,
            commitUrl = inspected.sourceUrl,
            packageSha256 = inspected.packageSha256,
            signed = inspected.signed,
            keyId = inspected.keyId,
            signatureProblem = inspected.signatureProblem,
        )
    }

    /**
     * El dueno del servidor autoriza un paquete sin firma, identificado por su hash, con una
     * confirmacion escrita. Debe indicar el hash y el commit que reviso ([expectedPackageSha256],
     * [expectedCommit]); si lo descargado ahora es otra cosa, se rechaza. Un paquete firmado no
     * necesita autorizacion, y uno con una firma que no vale tampoco se puede autorizar.
     */
    fun authorizeUnsigned(
        id: String,
        actorRole: String,
        confirmationText: String,
        expectedPackageSha256: String,
        expectedCommit: String,
    ): UnsignedAuthorizationResponse {
        val text = confirmationText.trim()
        if (text.length < MinConfirmationLength) {
            throw ExtensionPackageServiceException("Escribe la confirmacion con tus palabras (al menos $MinConfirmationLength caracteres).", "CONFIRMATION_REQUIRED")
        }
        if (!Sha256Pattern.matches(expectedPackageSha256.trim()) || !CommitPattern.matches(expectedCommit.trim().lowercase())) {
            throw ExtensionPackageServiceException("Indica el hash (64 hex) y el commit (40 hex) que revisaste.", "EXPECTED_REQUIRED")
        }
        val inspection = inspect(id)
        if (!inspection.packageSha256.equals(expectedPackageSha256.trim(), ignoreCase = true) ||
            !inspection.commit.equals(expectedCommit.trim(), ignoreCase = true)
        ) {
            throw ExtensionPackageServiceException(
                "El paquete cambio desde que lo revisaste (ahora: commit ${inspection.commit}, hash ${inspection.packageSha256}). Revisalo otra vez.",
                "PACKAGE_CHANGED",
            )
        }
        if (inspection.signed) {
            throw ExtensionPackageServiceException("La extension '$id' esta firmada; no necesita autorizacion.", "ALREADY_SIGNED")
        }
        inspection.signatureProblem?.let {
            throw ExtensionPackageServiceException("La extension '$id' trae una firma que no vale ($it); no se puede autorizar.", "SIGNATURE_INVALID")
        }
        val module = catalogSource().firstOrNull { it.manifest.id == id }?.manifest?.module
        authorizations.authorize(inspection.packageSha256, id, inspection.commitUrl, actorRole, text)
        audit(
            actorRole, ExtensionPackageAudit.AuthorizeUnsigned, null, module,
            buildJsonObject {
                put("extensionId", id)
                put("packageSha256", inspection.packageSha256)
                put("commit", inspection.commit)
                put("sourceUrl", inspection.commitUrl)
                put("confirmationText", text)
            }.toString(),
        )
        return UnsignedAuthorizationResponse(id, inspection.packageSha256, inspection.commitUrl)
    }

    /** Marca el paquete como removed. No toca los datos de las empresas; deja de cargarse al reiniciar. */
    fun remove(id: String, actorRole: String): ExtensionPackageStatus {
        val stored = store.find(id)
        if (stored == null || stored.sourceType !in ExtensionSourceType.External || stored.status == "removed") {
            throw ExtensionPackageServiceException("La extension '$id' no esta instalada como paquete externo.", "NOT_FOUND")
        }
        store.markRemoved(id)
        states.remove(id)
        audit(
            actorRole, ExtensionPackageAudit.Remove, null, stored.moduleId,
            buildJsonObject {
                put("extensionId", id)
                put("version", stored.version)
            }.toString(),
        )
        return status(id)
    }

    /**
     * Al arrancar: carga los paquetes instalados con el class loader de su modulo, solo si el jar esta
     * bajo packagesRoot y su SHA-256 coincide con el guardado (ver [ExtensionPackageLoader]), y deja en
     * 'installed' los que estaban en 'pending_restart' y cargaron.
     */
    fun loadInstalled(
        moduleClassLoader: (moduleId: String) -> ClassLoader?,
        load: (List<LoadCandidate>, Path, (String) -> ClassLoader?) -> List<ServerExtension> =
            { candidates, root, parent -> ExtensionPackageLoader.load(candidates, root, parent) },
    ): List<ServerExtension> {
        val candidates = store.loadable().mapNotNull { row ->
            row.installedPackagePath?.let { LoadCandidate(Path.of(it), row.id, row.moduleId, row.version, row.jarSha256) }
        }
        if (candidates.isEmpty()) return emptyList()
        val extensions = load(candidates, packagesRoot, moduleClassLoader)
        val loaded = extensions.map { it.manifest.id }.toSet()
        store.markInstalled(store.loadable().filter { it.status == "pending_restart" && it.id in loaded }.map { it.id })
        return extensions
    }

    companion object {
        /** Minutos que un ZIP subido o descargado espera en el servidor antes de caducar. */
        const val StagingMinutes = 30

        /** Maximo de un ZIP subido o descargado (comprimido). */
        const val MaxZipBytes = 50 * 1024 * 1024

        /** Carpeta bajo packagesRoot para los ZIP en preparacion; un id de extension no puede empezar por '_'. */
        private const val StagingFolder = "_staging"
        private const val MinConfirmationLength = 10
        private const val ShortHashLength = 8
        private val Sha256Pattern = Regex("[A-Fa-f0-9]{64}")
        private val CommitPattern = Regex("[0-9a-f]{40}")
    }
}
