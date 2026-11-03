package com.ideasdeveloper.idc.server.auth.api

import com.ideasdeveloper.idc.server.auth.application.ScopedLoginService
import com.ideasdeveloper.idc.server.auth.domain.LoginCredentials
import com.ideasdeveloper.idc.server.auth.domain.LoginSuccessResponse
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

@Serializable
data class SwitchCompanyRequest(val companyCode: String)

fun Route.authRoutes(
    loginService: ScopedLoginService,
    // Business owners move to another company of their owner group without a password (LoginDatabases.switchCompany).
    switchCompany: ((fromCode: String, token: String, toCode: String) -> LoginSuccessResponse?)? = null,
) {
    post("/auth/switch-company") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val switch = switchCompany ?: return@post call.respond(HttpStatusCode.NotFound)
        val fromCode = call.request.headers["X-Company-Code"]?.trim().orEmpty()
        val token = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
        val target = runCatching { call.receive<SwitchCompanyRequest>() }.getOrNull()?.companyCode?.trim().orEmpty()
        val codePattern = Regex("[a-z][a-z0-9_]{0,62}")
        if (!codePattern.matches(fromCode) || !codePattern.matches(target) || token.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", "Indica la empresa a la que quieres cambiar."))
        }
        val response = try {
            withContext(Dispatchers.IO) { switch(fromCode, token, target) }
        } catch (_: SQLException) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", "No se pudo cambiar de empresa. Intentalo mas tarde."))
        }
        if (response == null) {
            return@post call.respond(HttpStatusCode.Forbidden, AuthErrorResponse("SWITCH_REJECTED", "No puedes entrar a esa empresa con tu sesion."))
        }
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
