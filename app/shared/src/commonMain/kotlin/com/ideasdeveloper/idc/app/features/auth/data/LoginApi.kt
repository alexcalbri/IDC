package com.ideasdeveloper.idc.app.features.auth.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class LoginApi(serverUrl: String) {

    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        val url = Url(baseUrl)
        require(
            url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) &&
                    url.host.isNotBlank() &&
                    url.user == null &&
                    url.password == null &&
                    url.parameters.isEmpty() &&
                    url.fragment.isEmpty() &&
                    url.encodedPath in listOf("", "/")
        ) {
            "Introduce la URL del servidor con http:// o https://, sin rutas."
        }
    }

    private val client = HttpClient {
        expectSuccess = false
        followRedirects = false

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun login(request: LoginRequest): LoginResponse {
        try {
            val response = client.post("$baseUrl/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status == HttpStatusCode.OK) {
                return response.body<LoginResponse>()
            }

            if (response.status == HttpStatusCode.TooManyRequests) {
                throw LoginException(
                    "Demasiados intentos. Espera un minuto."
                )
            }

            if (
                response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                val error = response.body<LoginErrorResponse>()
                throw LoginException(error.message)
            }

            throw LoginException(
                "El servidor no pudo completar el inicio de sesión."
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LoginException) {
            throw exception
        } catch (_: Exception) {
            throw LoginException(
                "No se pudo comunicar con el servidor o su respuesta no es válida."
            )
        }
    }

    fun close() {
        client.close()
    }
}

class LoginException(message: String) : Exception(message)
