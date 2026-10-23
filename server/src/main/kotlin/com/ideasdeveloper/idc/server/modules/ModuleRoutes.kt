package com.ideasdeveloper.idc.server.modules

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.defaultForFilePath
import io.ktor.server.response.respondBytes
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

fun Route.moduleRoutes(registry: ModuleRegistry, access: ModuleAccessService) {
    route("/modules") {
        get {
            call.respond(HttpStatusCode.OK, registry.definitions)
        }

        get("/{moduleId}/metadata") {
            val moduleId = call.parameters["moduleId"].orEmpty()
            val definition = registry.definition(moduleId)
            if (definition == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respond(HttpStatusCode.OK, definition)
        }

        // Web-UI modules: the app exchanges its session for a one-time ticket and opens the
        // returned URL in a WebView or browser, so the token never appears in a URL.
        post("/{moduleId}/web-session") {
            val moduleId = call.parameters["moduleId"].orEmpty()
            val ui = registry.definition(moduleId)?.ui?.takeIf { it.type == "web" }
                ?: return@post call.respond(HttpStatusCode.NotFound, ModuleAccessErrorResponse("no_web_ui", "El modulo no tiene interfaz web."))
            val permissions = call.requireModuleAccess(access, moduleId) ?: return@post
            val ticket = ModuleWebTickets.issue(moduleId, permissions.companyCode, permissions.accessToken)
            call.respond(HttpStatusCode.OK, ModuleWebSessionResponse("/modules/$moduleId/web-entry?ticket=$ticket", ui.entry))
        }

        get("/{moduleId}/web-entry") {
            val moduleId = call.parameters["moduleId"].orEmpty()
            val ui = registry.definition(moduleId)?.ui?.takeIf { it.type == "web" && it.entry.startsWith("/modules/$moduleId/") }
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val session = ModuleWebTickets.consume(call.request.queryParameters["ticket"].orEmpty(), moduleId)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ModuleAccessErrorResponse("invalid_ticket", "El enlace vencio. Abre el modulo de nuevo desde la app."))
            val secure = call.request.headers["X-Forwarded-Proto"] == "https" || call.request.origin.scheme == "https"
            listOf(
                ModuleAccessService.TokenCookie to session.accessToken,
                ModuleAccessService.CompanyCookie to session.companyCode,
            ).forEach { (name, value) ->
                call.response.cookies.append(
                    Cookie(
                        name = name,
                        value = value,
                        path = "/modules/$moduleId/",
                        httpOnly = true,
                        secure = secure,
                        extensions = mapOf("SameSite" to "Strict"),
                    )
                )
            }
            call.respondRedirect(ui.entry)
        }
    }

    registry.installRoutes(this, access)
}

@Serializable
data class ModuleWebSessionResponse(val url: String, val entry: String)

internal object ModuleWebTickets {
    private const val LifetimeMillis = 60_000L
    private val random = SecureRandom()
    private val tickets = ConcurrentHashMap<String, Ticket>()

    data class Ticket(val moduleId: String, val companyCode: String, val accessToken: String, val expiresAt: Long)

    fun issue(moduleId: String, companyCode: String, accessToken: String): String {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { it.value.expiresAt < now }
        val bytes = ByteArray(32).also(random::nextBytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tickets[ticket] = Ticket(moduleId, companyCode, accessToken, now + LifetimeMillis)
        return ticket
    }

    // Single use: the ticket is removed even when it belongs to another module or expired.
    fun consume(ticket: String, moduleId: String): Ticket? =
        tickets.remove(ticket)?.takeIf { it.moduleId == moduleId && it.expiresAt >= System.currentTimeMillis() }
}

/**
 * Serves a web-UI module's static pages from modules/{moduleId}/server/src/main/resources/modules/{moduleId}/web/
 * at /modules/{moduleId}/web/. Call it from ServerModule.routes as
 * `route.moduleWebResources("<id>", javaClass.classLoader)` so pages are found inside the module's
 * package, and declare ModuleUiEntry(type = "web", entry = "/modules/{moduleId}/web/"). The pages'
 * data calls stay protected by requireModulePermission through the module cookies.
 */
fun Route.moduleWebResources(moduleId: String, classLoader: ClassLoader) {
    get("/modules/$moduleId/web/{path...}") {
        val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
        if (segments.any { it == ".." || it.contains('\\') }) return@get call.respond(HttpStatusCode.BadRequest)
        val path = segments.joinToString("/").ifEmpty { "index.html" }
        val bytes = classLoader.getResourceAsStream("modules/$moduleId/web/$path")?.use { it.readAllBytes() }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, ContentType.defaultForFilePath(path))
    }
}
