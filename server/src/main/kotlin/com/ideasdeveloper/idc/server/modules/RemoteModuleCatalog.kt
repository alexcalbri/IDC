package com.ideasdeveloper.idc.server.modules

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Lee modulos remotos disponibles para instalar.
 *
 * La URL puede apuntar a:
 *
 * - un JSON legado con `{ "modules": [...] }`;
 * - una URL normal de GitHub como
 *   `https://github.com/alexcalbri/IDC/tree/master/modules`;
 * - un directorio de GitHub Contents API, por ejemplo
 *   `https://api.github.com/repos/owner/repo/contents/modules?ref=main`.
 *
 * En modo GitHub, cada carpeta es instalable solo si contiene `module.json`
 * valido. Si se elimina una carpeta del repositorio remoto, deja de aparecer
 * para nuevas instalaciones; los servidores que ya la instalaron conservan su
 * registro local en `server_modules` y su paquete descargado.
 */
class RemoteModuleCatalog : AutoCloseable {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val client = HttpClient {
        expectSuccess = false
        followRedirects = false
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
        }
    }
    private val moduleIdPattern = Regex("[a-z][a-z0-9_-]{0,62}")
    private val checksumPattern = Regex("[A-Fa-f0-9]{64}")

    fun modules(catalogUrl: String): List<RemoteModulePackage> {
        val url = catalogUrl.trim().takeIf { it.isNotBlank() }?.toGitHubContentsApiUrl() ?: return emptyList()
        return runBlocking {
            try {
                modulesFrom(url)
                    .filter(::isValid)
                    .distinctBy { it.definition.id }
                    .sortedBy { it.definition.id }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private suspend fun modulesFrom(url: String): List<RemoteModulePackage> {
        val text = client.get(url).body<String>()
        return when (val element = json.parseToJsonElement(text)) {
            is JsonObject -> modulesFromObject(element)
            is JsonArray -> modulesFromGitHubDirectory(element)
            else -> emptyList()
        }
    }

    private suspend fun modulesFromObject(element: JsonObject): List<RemoteModulePackage> {
        element["modules"]?.let {
            return json.decodeFromJsonElement(RemoteModuleCatalogDocument.serializer(), element)
                .modules
                .map { definition -> definition.toPackage() }
        }

        return decodeModuleManifest(element, expectedFolder = null)?.let(::listOf).orEmpty()
    }

    private suspend fun modulesFromGitHubDirectory(entries: JsonArray): List<RemoteModulePackage> =
        entries
            .mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "dir" }
            .mapNotNull { directory ->
                val directoryName = directory.string("name") ?: return@mapNotNull null
                val directoryUrl = directory.string("url") ?: return@mapNotNull null
                loadModuleManifest(githubChildUrl(directoryUrl, "module.json"), expectedFolder = directoryName)
            }

    private suspend fun loadModuleManifest(url: String, expectedFolder: String): RemoteModulePackage? {
        val text = client.get(url).body<String>()
        return when (val element = json.parseToJsonElement(text)) {
            is JsonObject -> decodeModuleManifest(element, expectedFolder)
            else -> null
        }
    }

    private fun decodeModuleManifest(element: JsonObject, expectedFolder: String?): RemoteModulePackage? {
        val manifest = element["content"]?.jsonPrimitive?.contentOrNull
            ?.let { encoded -> String(Base64.getMimeDecoder().decode(encoded)) }
            ?.let { json.parseToJsonElement(it) as? JsonObject }
            ?: element

        val definition = json.decodeFromJsonElement(RemoteModuleDefinition.serializer(), manifest)
        if (expectedFolder != null && definition.id != expectedFolder) return null
        return definition.toPackage()
    }

    private fun RemoteModuleDefinition.toPackage(): RemoteModulePackage =
        RemoteModulePackage(
            definition = toDefinition(),
            packageUrl = packageUrl?.takeIf { value -> value.isNotBlank() },
            packageSha256 = packageSha256?.lowercase()?.takeIf { value -> value.isNotBlank() },
        )

    private fun isValid(remotePackage: RemoteModulePackage): Boolean {
        val definition = remotePackage.definition
        return moduleIdPattern.matches(definition.id) &&
            definition.displayName.isNotBlank() &&
            definition.description.isNotBlank() &&
            definition.version.isNotBlank() &&
            (remotePackage.packageUrl.isNullOrBlank() ||
                remotePackage.packageUrl.startsWith("https://") ||
                remotePackage.packageUrl.startsWith("http://")) &&
            (remotePackage.packageSha256.isNullOrBlank() || checksumPattern.matches(remotePackage.packageSha256))
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun String.toGitHubContentsApiUrl(): String {
        val regex = Regex("""^https://github\.com/([^/]+)/([^/]+)/tree/([^/]+)/(.+)$""")
        val match = regex.matchEntire(this) ?: return this
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        val ref = match.groupValues[3]
        val path = match.groupValues[4].trim('/')
        return "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$ref"
    }

    private fun githubChildUrl(directoryUrl: String, child: String): String {
        val queryIndex = directoryUrl.indexOf('?')
        return if (queryIndex >= 0) {
            directoryUrl.substring(0, queryIndex).trimEnd('/') + "/$child" + directoryUrl.substring(queryIndex)
        } else {
            directoryUrl.trimEnd('/') + "/$child"
        }
    }

    override fun close() {
        client.close()
    }
}

data class RemoteModulePackage(
    val definition: ModuleDefinition,
    val packageUrl: String?,
    val packageSha256: String?,
)

@Serializable
data class RemoteModuleCatalogDocument(
    val modules: List<RemoteModuleDefinition> = emptyList(),
)

@Serializable
data class RemoteModuleDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val version: String = "0.1.0",
    val locked: Boolean = false,
    val views: List<ModuleViewDefinition> = emptyList(),
    val permissions: List<ModulePermissionDefinition> = emptyList(),
    val packageUrl: String? = null,
    val packageSha256: String? = null,
) {
    fun toDefinition(): ModuleDefinition = ModuleDefinition(
        id = id,
        displayName = displayName,
        description = description,
        version = version,
        locked = false,
        views = views,
        permissions = permissions,
    )
}
