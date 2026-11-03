package com.ideasdeveloper.idc.server.auth.api

import com.ideasdeveloper.idc.server.auth.application.ScopedLoginService
import com.ideasdeveloper.idc.server.auth.domain.LoginCredentials
import com.ideasdeveloper.idc.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.idc.server.auth.domain.SwitchTargetsResponse
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException

// companyCode null: back to the server (server owners only).
@Serializable
data class SwitchCompanyRequest(val companyCode: String? = null)

fun Route.authRoutes(
    loginService: ScopedLoginService,
    // Company switcher (LoginDatabases.switchTargets / switchTo); fromCode null means the server.
    switchTargets: ((fromCode: String?, token: String) -> SwitchTargetsResponse?)? = null,
    switchTo: ((fromCode: String?, token: String, toCode: String?) -> LoginSuccessResponse?)? = null,
) {
    val codePattern = Regex("[a-z][a-z0-9_]{0,62}")
    fun io.ktor.server.application.ApplicationCall.switchContext(): Pair<String?, String>? {
        val fromCode = request.headers["X-Company-Code"]?.trim()?.takeIf { it.isNotEmpty() }
        val token = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
        if (token.isEmpty() || (fromCode != null && !codePattern.matches(fromCode))) return null
        return fromCode to token
    }

    get("/auth/switch-targets") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val targets = switchTargets ?: return@get call.respond(HttpStatusCode.NotFound)
        val (fromCode, token) = call.switchContext()
            ?: return@get call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", "Falta la sesion."))
        val response = try {
            withContext(Dispatchers.IO) { targets(fromCode, token) }
        } catch (_: SQLException) {
            return@get call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", "No se pudieron cargar las empresas."))
        } ?: return@get call.respond(HttpStatusCode.Forbidden, AuthErrorResponse("SWITCH_REJECTED", "Tu sesion no puede cambiar de empresa."))
        call.respond(HttpStatusCode.OK, response)
    }

    post("/auth/switch-company") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val switch = switchTo ?: return@post call.respond(HttpStatusCode.NotFound)
        val (fromCode, token) = call.switchContext()
            ?: return@post call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", "Falta la sesion."))
        val target = runCatching { call.receive<SwitchCompanyRequest>() }.getOrNull()?.companyCode?.trim()?.takeIf { it.isNotEmpty() }
        if (target != null && !codePattern.matches(target)) {
            return@post call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", "Codigo de empresa invalido."))
        }
        val response = try {
            withContext(Dispatchers.IO) { switch(fromCode, token, target) }
        } catch (_: SQLException) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", "No se pudo cambiar de empresa. Intentalo mas tarde."))
        } ?: return@post call.respond(HttpStatusCode.Forbidden, AuthErrorResponse("SWITCH_REJECTED", "No puedes entrar ahi con tu sesion."))
        call.respond(HttpStatusCode.OK, response)
    }

    post("/auth/login") {
        call.response.headers.append(
            HttpHeaders.CacheControl,
            "no-store",
        )

        val credentials = try {
            call.receive<LoginCredentials>()
        } catch (_: BadRequestException) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthErrorResponse(
                    code = "INVALID_REQUEST",
                    message = "Debes enviar un JSON válido con username y password.",
                ),
            )
            return@post
        } catch (_: ContentTransformationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthErrorResponse(
                    code = "INVALID_REQUEST",
                    message = "Debes enviar un JSON válido con username y password.",
                ),
            )
            return@post
        }

        val response = try {
            withContext(Dispatchers.IO) {
                loginService.login(credentials)
            }
        } catch (_: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                AuthErrorResponse(
                    code = "AUTH_UNAVAILABLE",
                    message = "No se pudo procesar el inicio de sesión. Inténtalo más tarde.",
                ),
            )
            return@post
        }

        if (response == null) {
            call.respond(
                HttpStatusCode.Unauthorized,
                AuthErrorResponse(
                    code = "LOGIN_REJECTED",
                    message = "No se pudo iniciar sesión con las credenciales proporcionadas.",
                ),
            )
            return@post
        }

        call.respond(HttpStatusCode.OK, response)
    }
}
