package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.extensions.ExtensionIdPattern
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionSignatureFile
import com.ideasdeveloper.idc.server.modules.GitHubAuth
import com.ideasdeveloper.idc.server.modules.GitHubLocation
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Extension disponible en el catalogo, ya fijada a un commit. */
data class RemoteExtensionPackage(
    val manifest: ExtensionManifest,
    /** Carpeta extensions/ del commit; el paquete es <source.path>/<id>. */
    val source: GitHubLocation,
    /** Hash que declara extension.sig.json (null si no trae). */
    val packageSha256: String?,
    /** true solo si la firma de extension.sig.json es valida sobre el hash declarado (el hash real se comprueba al instalar). */
    val signed: Boolean,
    val keyId: String?,
) {
    /** Commit de 40 hex del que se leyo el catalogo. */
    val commit: String get() = source.ref

    /** URL de la carpeta de la extension en ese commit. */
    val commitUrl: String get() = "https://github.com/${source.owner}/${source.repo}/tree/${source.ref}/${source.path.trim('/')}/${manifest.id}"
}

/**
 * Catalogo de extensiones externas: la carpeta extensions/ de un repositorio de GitHub. Cada carpeta
 * es una extension si trae un extension.json valido cuyo id es el nombre de la carpeta. La lectura se
 * fija a un commit (si la URL trae una rama se resuelve a su commit) para que lo que se lista sea
 * exactamente lo que se instala. Cache de 5 minutos, con el mismo token de GitHub de los modulos.
 */
class RemoteExtensionCatalog(
    private val githubToken: String? = null,
    private val verifier: ExtensionSignatureVerifier = ExtensionSignatureVerifier(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val client: HttpClient = HttpClient {
        expectSuccess = false
        followRedirects = false
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
        }
    },
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }

    private class Cached(val readAt: Long, val packages: List<RemoteExtensionPackage>)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    fun extensions(catalogUrl: String): List<RemoteExtensionPackage> {
        val key = catalogUrl.trim()
        val location = GitHubLocation.parse(key) ?: return emptyList()
        val now = clock()
        cache[key]?.takeIf { now - it.readAt < CacheMillis }?.let { return it.packages }
        return runBlocking {
            try {
                val commit = if (CommitPattern.matches(location.ref)) location.ref else resolveCommit(location)
                val pinned = location.copy(ref = commit)
                read(pinned).sortedBy { it.manifest.id }.also { cache[key] = Cached(now, it) }
            } catch (_: Exception) {
                // GitHub no responde o limita: se sigue ofreciendo la ultima lectura buena.
                cache[key]?.packages ?: emptyList()
            }
        }
    }

    fun forget(catalogUrl: String) {
        cache.remove(catalogUrl.trim())
    }

    private suspend fun resolveCommit(location: GitHubLocation): String {
        val url = "https://api.github.com/repos/${location.owner}/${location.repo}/commits/${location.ref}"
        val response = client.get(url) {
            header(HttpHeaders.Accept, "application/vnd.github.sha")
            GitHubAuth.header(githubToken, url)?.let { header(HttpHeaders.Authorization, it) }
        }
        val sha = response.bodyAsText().trim().lowercase()
        check(response.status.isSuccess() && CommitPattern.matches(sha)) { "No se pudo resolver ${location.ref}" }
        return sha
    }

    private suspend fun read(location: GitHubLocation): List<RemoteExtensionPackage> {
        val listing = json.parseToJsonElement(get(contentsUrl(location, location.path)) ?: return emptyList()) as? JsonArray ?: return emptyList()
        return listing.mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "dir" }
            .mapNotNull { directory ->
                val folder = directory.string("name") ?: return@mapNotNull null
                if (!ExtensionIdPattern.matches(folder)) return@mapNotNull null
                val manifest = file(location, folder, "extension.json")
                    ?.let { runCatching { json.decodeFromString(ExtensionManifest.serializer(), it) }.getOrNull() }
                    ?: return@mapNotNull null
                if (manifest.id != folder) return@mapNotNull null
                val signature = file(location, folder, ExtensionTreeHash.SignatureFileName)
                    ?.let { runCatching { json.decodeFromString(ExtensionSignatureFile.serializer(), it) }.getOrNull() }
                RemoteExtensionPackage(
                    manifest = manifest,
                    source = location,
                    packageSha256 = signature?.packageSha256?.lowercase(),
                    signed = signature?.let { file -> file.signature?.let { verifier.verify(file.packageSha256.lowercase(), it) == null } } == true,
                    keyId = signature?.signature?.keyId,
                )
            }
    }

    // Contenido de un archivo de la carpeta de una extension, o null si no existe.
    private suspend fun file(location: GitHubLocation, folder: String, name: String): String? {
        val element = json.parseToJsonElement(get(contentsUrl(location, "${location.path}/$folder/$name")) ?: return null) as? JsonObject
        return element?.get("content")?.jsonPrimitive?.contentOrNull?.let { String(Base64.getMimeDecoder().decode(it)) }
    }

    private suspend fun get(url: String): String? {
        val response = client.get(url) { GitHubAuth.header(githubToken, url)?.let { header(HttpHeaders.Authorization, it) } }
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }

    private fun contentsUrl(location: GitHubLocation, path: String) =
        "https://api.github.com/repos/${location.owner}/${location.repo}/contents/${path.trim('/')}?ref=${location.ref}"

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    override fun close() {
        client.close()
    }

    companion object {
        private const val CacheMillis = 5 * 60_000L
        private val CommitPattern = Regex("[0-9a-f]{40}")

        /**
         * URL efectiva del catalogo: EXTENSION_CATALOG_URL si esta, o la carpeta extensions/ del mismo
         * repositorio y rama del catalogo de modulos (.../tree/<ref>/modules -> .../tree/<ref>/extensions).
         */
        fun effectiveUrl(extensionCatalogUrl: String, moduleCatalogUrl: String): String {
            extensionCatalogUrl.trim().takeIf { it.isNotEmpty() }?.let { return it }
            val modules = GitHubLocation.parse(moduleCatalogUrl) ?: return ""
            return "https://github.com/${modules.owner}/${modules.repo}/tree/${modules.ref}/extensions"
        }
    }
}
