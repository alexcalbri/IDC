package com.ideasdeveloper.idc.app.features.auth.data

import com.ideasdeveloper.idc.app.core.version.installAppVersionHeader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// Cliente HTTP encargado solo del inicio de sesion.
class LoginApi(serverUrl: String) {

    // Normaliza la URL base para construir endpoints sin dobles barras.
    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        // Acepta solo URLs raiz http/https para evitar rutas inesperadas en la configuracion.
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

    // Cliente Ktor con JSON tolerante a campos nuevos enviados por el servidor.
    private val client = HttpClient {
        installAppVersionHeader()
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

    // Envia credenciales al endpoint de login y traduce estados HTTP a mensajes de UI.
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

    // Cierra la sesion en el servidor para invalidar el token de inmediato (no solo en el cliente).
    // Best-effort: si falla la red, el cierre local ocurre igual y no bloqueamos al usuario.
    suspend fun logout(companyCode: String?, token: String) {
        if (token.isBlank()) return
        try {
            client.post("$baseUrl/auth/logout") {
                header(HttpHeaders.Authorization, "Bearer $token")
                if (!companyCode.isNullOrBlank()) header("X-Company-Code", companyCode)
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: Exception) {
            // Ignorado a proposito: el token vencera por tiempo y la sesion local ya se limpio.
        }
    }

    // Libera recursos del cliente cuando termina el flujo de login.
    fun close() {
        client.close()
    }
}

class LoginException(message: String) : Exception(message)
