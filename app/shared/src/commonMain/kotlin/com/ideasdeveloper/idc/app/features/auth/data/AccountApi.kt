package com.ideasdeveloper.idc.app.features.auth.data

import com.ideasdeveloper.idc.app.core.session.SessionExpiry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AccountProfile(val username: String, val displayName: String? = null, val email: String? = null, val profileComplete: Boolean = false)

@Serializable
private data class UpdateAccountProfileRequest(val displayName: String, val email: String)

@Serializable
private data class PasswordResetRequest(val username: String, val companyCode: String? = null)

@Serializable
private data class PasswordResetConfirmation(val username: String, val companyCode: String? = null, val code: String, val newPassword: String)

@Serializable
private data class AccountMessage(val message: String)

// Cuenta propia: nombre y correo del usuario, y recuperacion de contrasena con un codigo enviado por correo.
class AccountApi(serverUrl: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    private val client = HttpClient {
        expectSuccess = false
        followRedirects = false
        install(SessionExpiry)
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    // Sin codigo de empresa la sesion es del dueno del servidor.
    suspend fun updateProfile(accessToken: String, companyCode: String?, displayName: String, email: String): AccountProfile = call {
        client.put("$baseUrl/auth/profile") {
            bearerAuth(accessToken)
            companyCode?.let { header("X-Company-Code", it) }
            contentType(ContentType.Application.Json)
            setBody(UpdateAccountProfileRequest(displayName, email))
        }
    }

    // Devuelve el mensaje del servidor; nunca dice si el usuario existe.
    suspend fun requestPasswordReset(username: String, companyCode: String?): String = call<AccountMessage> {
        client.post("$baseUrl/auth/password-reset/request") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetRequest(username.trim(), companyCode))
        }
    }.message

    suspend fun confirmPasswordReset(username: String, companyCode: String?, code: String, newPassword: String): String = call<AccountMessage> {
        client.post("$baseUrl/auth/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetConfirmation(username.trim(), companyCode, code.trim(), newPassword))
        }
    }.message

    private suspend inline fun <reified T> call(request: () -> HttpResponse): T {
        try {
            val response = request()
            if (response.status == HttpStatusCode.OK) return response.body()
            if (response.status == HttpStatusCode.TooManyRequests) throw LoginException("Demasiados intentos. Espera un minuto.")
            val error = runCatching { response.body<LoginErrorResponse>() }.getOrNull()
            throw LoginException(error?.message ?: "El servidor no pudo completar la operacion.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LoginException) {
            throw exception
        } catch (_: Exception) {
            throw LoginException("No se pudo comunicar con el servidor.")
        }
    }

    fun close() {
        client.close()
    }
}

suspend inline fun <T> AccountApi.use(block: (AccountApi) -> T): T = try {
    block(this)
} finally {
    close()
}
