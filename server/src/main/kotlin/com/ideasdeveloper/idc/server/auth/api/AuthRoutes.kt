package com.ideasdeveloper.idc.server.auth.api

import com.ideasdeveloper.idc.server.auth.application.ScopedLoginService
import com.ideasdeveloper.idc.server.auth.domain.LoginCredentials
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

fun Route.authRoutes(
    loginService: ScopedLoginService,
) {
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
