package com.ideasdeveloper.idc.server.modules

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Serializable
data class NotificationResponse(
    val id: String,
    val moduleId: String,
    // Nombre del modulo para mostrarlo; el id si el modulo ya no esta disponible.
    val moduleName: String,
    val title: String,
    val text: String,
    val target: NotificationTarget? = null,
    val createdAt: String,
    // Segundos desde que se creo, para mostrar "hace 5 min" sin depender del reloj del cliente.
    val ageSeconds: Long,
    val read: Boolean,
)

@Serializable
data class NotificationPageResponse(val items: List<NotificationResponse>, val unread: Int, val hasMore: Boolean)

@Serializable
data class NotificationUnreadResponse(val unread: Int)

@Serializable
data class NotificationChangeResponse(val changed: Int, val unread: Int)

fun notificationResponse(
    notification: StoredNotification,
    moduleName: (String) -> String?,
    now: Instant = Instant.now(),
) = NotificationResponse(
    id = notification.id.toString(),
    moduleId = notification.moduleId,
    moduleName = moduleName(notification.moduleId) ?: notification.moduleId,
    title = notification.title,
    text = notification.text,
    target = notification.target,
    createdAt = notification.createdAt.toString(),
    ageSeconds = Duration.between(notification.createdAt, now).seconds.coerceAtLeast(0),
    read = notification.readAt != null,
)

/**
 * Notificaciones del usuario que tiene la sesion: cada usuario solo ve las suyas, dentro de la
 * empresa del encabezado X-Company-Code.
 *
 *  - GET  /notifications?limit=30&offset=0&unread=false   pagina (mas nuevas primero) con el contador
 *  - GET  /notifications/unread-count                     solo el contador
 *  - POST /notifications/{id}/read                        marca una como leida
 *  - POST /notifications/read-all                         marca todas como leidas
 */
fun Route.notificationRoutes(registry: ModuleRegistry, access: ModuleAccessService) {
    val notifications = access.notifications
    fun moduleName(moduleId: String): String? = registry.availableDefinition(moduleId)?.displayName

    route("/notifications") {
        get {
            val query = call.request.queryParameters
            call.withNotifications(access) { tenant, userId ->
                val page = notifications.list(
                    tenant, userId,
                    limit = query["limit"]?.toIntOrNull() ?: NotificationService.DefaultPageSize,
                    offset = query["offset"]?.toIntOrNull() ?: 0,
                    unreadOnly = query["unread"] == "true",
                )
                val now = Instant.now()
                NotificationPageResponse(page.items.map { notificationResponse(it, ::moduleName, now) }, page.unread, page.hasMore)
            }
        }
        get("/unread-count") {
            call.withNotifications(access) { tenant, userId -> NotificationUnreadResponse(notifications.unreadCount(tenant, userId)) }
        }
        post("/read-all") {
            call.withNotifications(access) { tenant, userId ->
                NotificationChangeResponse(notifications.markAllRead(tenant, userId), notifications.unreadCount(tenant, userId))
            }
        }
        post("/{id}/read") {
            val id = runCatching { UUID.fromString(call.parameters["id"].orEmpty()) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ModuleAccessErrorResponse("invalid_id", "La notificacion no es valida."))
            call.withNotifications(access) { tenant, userId ->
                val changed = if (notifications.markRead(tenant, userId, id)) 1 else 0
                NotificationChangeResponse(changed, notifications.unreadCount(tenant, userId))
            }
        }
    }
}

// Valida la sesion, abre la base de la empresa y responde con el resultado de [block].
private suspend inline fun <reified T : Any> ApplicationCall.withNotifications(
    access: ModuleAccessService,
    crossinline block: (Connection, UUID) -> T,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val result: Any = withContext(Dispatchers.IO) {
            when (val session = access.userSession(this@withNotifications)) {
                is UserSessionLookup.Denied -> session.decision
                is UserSessionLookup.Granted -> {
                    val tenant = access.tenantConnectionForCompany(session.companyCode) ?: return@withContext ModuleAccessDecision.Forbidden("company_not_found", "La empresa no esta activa.")
                    tenant.use { block(it, session.userId) }
                }
            }
        }
        when (result) {
            is ModuleAccessDecision.Unauthorized -> respond(HttpStatusCode.Unauthorized, ModuleAccessErrorResponse(result.code, result.message))
            is ModuleAccessDecision.Forbidden -> respond(HttpStatusCode.Forbidden, ModuleAccessErrorResponse(result.code, result.message))
            is ModuleAccessDecision.Allowed -> Unit
            is T -> respond(HttpStatusCode.OK, result)
            else -> respond(HttpStatusCode.InternalServerError)
        }
    } catch (_: SQLException) {
        respond(HttpStatusCode.ServiceUnavailable, ModuleAccessErrorResponse("notifications_unavailable", "No se pudieron leer las notificaciones."))
    }
}
