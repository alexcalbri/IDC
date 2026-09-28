package com.ideasdeveloper.idc.app.core.version

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val ClientVersion = "0.1-rc"

class VersionApi(serverUrl: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')
    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 5_000
        }
    }

    suspend fun version(): VersionResponse? {
        return try {
            val response = client.get("$baseUrl/version")
            if (response.status == HttpStatusCode.OK) response.body() else null
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        client.close()
    }
}

@Serializable
data class VersionResponse(
    val coreVersion: String = ClientVersion,
    val appVersion: String = ClientVersion,
    val latestCoreVersion: String? = null,
    val latestAppVersion: String? = null,
    val moduleUpdates: List<ModuleVersionUpdate> = emptyList(),
)

@Serializable
data class ModuleVersionUpdate(
    val moduleId: String,
    val displayName: String,
    val currentVersion: String,
    val latestVersion: String,
)
