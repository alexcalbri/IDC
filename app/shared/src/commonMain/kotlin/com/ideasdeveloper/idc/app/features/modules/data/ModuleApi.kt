package com.ideasdeveloper.idc.app.features.modules.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// Cliente HTTP para consultar metadata de modulos server-driven.
class ModuleApi(serverUrl: String) {
    // Normaliza la URL base configurada durante el login.
    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        // Valida que la URL sea parseable antes de construir endpoints.
        Url(baseUrl)
    }

    // Cliente Ktor ligero para obtener definiciones de modulo.
    private val client = HttpClient {
        expectSuccess = false
        followRedirects = false

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    // Carga la definicion que la UI generica usara para presentar el modulo.
    suspend fun metadata(moduleId: String): ModuleDefinition {
        try {
            val response = client.get("$baseUrl/modules/$moduleId/metadata")
            if (response.status == HttpStatusCode.OK) {
                return response.body()
            }
            throw ModuleException("El servidor no encontro el modulo.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo cargar el modulo desde el servidor.")
        }
    }

    // Libera recursos del cliente HTTP.
    fun close() {
        client.close()
    }
}

class ModuleException(message: String) : Exception(message)
