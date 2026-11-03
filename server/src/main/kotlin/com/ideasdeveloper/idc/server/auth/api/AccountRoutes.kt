package com.ideasdeveloper.idc.server.auth.api

import com.ideasdeveloper.idc.server.auth.application.AccountException
import com.ideasdeveloper.idc.server.auth.application.AccountService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.SQLException

@Serializable
data class UpdateAccountProfileRequest(val displayName: String, val email: String)

@Serializable
data class PasswordResetRequest(val username: String, val companyCode: String? = null)

@Serializable
data class PasswordResetConfirmation(val username: String, val companyCode: String? = null, val code: String, val newPassword: String)

@Serializable
data class AccountMessageResponse(val message: String)

/** The signed-in user's name and email. Without X-Company-Code the session is a server owner's. */
fun Route.accountProfileRoutes(service: AccountService) {
    get("/auth/profile") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val token = call.bearer() ?: return@get call.respond(HttpStatusCode.Unauthorized, AuthErrorResponse("INVALID_SESSION", "La sesion es requerida."))
        call.answer { service.profile(token, call.companyCode()) }
    }
    put("/auth/profile") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val token = call.bearer() ?: return@put call.respond(HttpStatusCode.Unauthorized, AuthErrorResponse("INVALID_SESSION", "La sesion es requerida."))
        val request = call.receiveOrNull<UpdateAccountProfileRequest>() ?: return@put
        call.answer { service.updateProfile(token, call.companyCode(), request.displayName, request.email) }
    }
}

/** Password recovery with a code emailed to the user; no session. */
fun Route.passwordResetRoutes(service: AccountService) {
    post("/auth/password-reset/request") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val request = call.receiveOrNull<PasswordResetRequest>() ?: return@post
        call.answer {
            service.requestPasswordReset(request.username, request.companyCode?.trim()?.takeIf { it.isNotEmpty() })
            AccountMessageResponse("Si el usuario existe y tiene correo registrado, enviamos un codigo que vence en 15 minutos.")
        }
    }
    post("/auth/password-reset/confirm") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val request = call.receiveOrNull<PasswordResetConfirmation>() ?: return@post
        call.answer {
            service.confirmPasswordReset(request.username, request.companyCode?.trim()?.takeIf { it.isNotEmpty() }, request.code, request.newPassword)
            AccountMessageResponse("Contrasena actualizada. Ya puedes iniciar sesion.")
        }
    }
}

private fun ApplicationCall.bearer(): String? = request.headers[HttpHeaders.Authorization]
    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
    ?.substringAfter(' ')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private fun ApplicationCall.companyCode(): String? = request.headers["X-Company-Code"]?.trim()?.takeIf { it.isNotEmpty() }

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = try {
    receive<T>()
} catch (_: BadRequestException) {
    respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
    null
} catch (_: ContentTransformationException) {
    respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
    null
}

private suspend inline fun <reified T : Any> ApplicationCall.answer(crossinline block: () -> T) {
    val result = try {
        withContext(Dispatchers.IO) { block() }
    } catch (failure: AccountException) {
        val status = when (failure.code) {
            "INVALID_SESSION" -> HttpStatusCode.Unauthorized
            "MAIL_NOT_CONFIGURED" -> HttpStatusCode.ServiceUnavailable
            else -> HttpStatusCode.BadRequest
        }
        return respond(status, AuthErrorResponse(failure.code, failure.message.orEmpty()))
    } catch (_: SQLException) {
        return respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("ACCOUNT_UNAVAILABLE", "No se pudo completar la operacion. Intentalo mas tarde."))
    }
    respond(HttpStatusCode.OK, result)
}
