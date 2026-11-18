package com.ideasdeveloper.idc.app.features.notifications.data

import com.ideasdeveloper.idc.app.core.session.SessionExpiry
import com.ideasdeveloper.idc.app.core.version.installAppVersionHeader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Pantalla a abrir al hacer clic en una notificacion: pantalla del modulo de origen y sus parametros.
@Serializable
data class NotificationTarget(val screen: String, val params: Map<String, String> = emptyMap())

@Serializable
data class NotificationItem(
    val id: String,
    val moduleId: String,
    val moduleName: String,
    val title: String,
    val text: String = "",
    val target: NotificationTarget? = null,
    // Segundos desde que se creo cuando el servidor respondio.
    val ageSeconds: Long = 0,
    val read: Boolean = false,
)

@Serializable
data class NotificationPage(val items: List<NotificationItem> = emptyList(), val unread: Int = 0, val hasMore: Boolean = false)

@Serializable
data class NotificationChange(val changed: Int = 0, val unread: Int = 0)

class NotificationException(message: String) : Exception(message)

// Centro de notificaciones del Core: las del usuario de la sesion, dentro de su empresa.
class NotificationApi(serverUrl: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    private val client = HttpClient {
        installAppVersionHeader()
        expectSuccess = false
        followRedirects = false
        install(SessionExpiry)
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun list(accessToken: String, companyCode: String, limit: Int = 30, offset: Int = 0): NotificationPage = call {
        client.get("$baseUrl/notifications") {
            bearerAuth(accessToken)
            header("X-Company-Code", companyCode)
            parameter("limit", limit)
            parameter("offset", offset)
        }
    }

    suspend fun markRead(accessToken: String, companyCode: String, id: String): NotificationChange = call {
        client.post("$baseUrl/notifications/$id/read") {
            bearerAuth(accessToken)
            header("X-Company-Code", companyCode)
        }
    }

    suspend fun markAllRead(accessToken: String, companyCode: String): NotificationChange = call {
        client.post("$baseUrl/notifications/read-all") {
            bearerAuth(accessToken)
            header("X-Company-Code", companyCode)
        }
    }

    private suspend inline fun <reified T> call(request: () -> HttpResponse): T {
        try {
            val response = request()
            if (response.status == HttpStatusCode.OK) return response.body()
            throw NotificationException("El servidor no pudo leer las notificaciones.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: NotificationException) {
            throw exception
        } catch (_: Exception) {
            throw NotificationException("No se pudo comunicar con el servidor.")
        }
    }

    fun close() {
        client.close()
    }
}
