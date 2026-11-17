package com.ideasdeveloper.idc.server.app

import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

/** Lee el catalogo de versiones remoto con timeouts de 5 s (conexion y lectura). */
object VersionCatalogClient {
    const val TimeoutMillis = 5_000
    const val MaxBytes = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    /** Devuelve null si la URL esta vacia o no es http/https, o si el catalogo no se pudo leer o interpretar. */
    fun fetch(catalogUrl: String): RemoteVersionCatalog? {
        val url = catalogUrl.trim().takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val uri = URI.create(url)
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = TimeoutMillis
            connection.readTimeout = TimeoutMillis
            connection.instanceFollowRedirects = false
            try {
                val bytes = connection.inputStream.use { it.readNBytes(MaxBytes + 1) }
                if (bytes.size > MaxBytes) return null
                json.decodeFromString<RemoteVersionCatalog>(bytes.toString(Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
