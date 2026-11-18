package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.company.api.CompanyErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class AuthorizeUnsignedRequest(
    val confirmationText: String = "",
    /** Hash del arbol que el usuario reviso (GET .../inspect). */
    val expectedPackageSha256: String = "",
    /** Commit de 40 hex que el usuario reviso. */
    val expectedCommit: String = "",
)

/**
 * Paquetes externos de extensiones, solo para el dueno del servidor.
 * [serverOwnerRole] devuelve el rol de PostgreSQL del server_owner autenticado, o null si no lo es.
 */
fun Route.extensionPackageRoutes(
    service: ExtensionPackageService,
    serverOwnerRole: suspend (ApplicationCall) -> String?,
) {
    suspend fun ApplicationCall.owner(message: String): String? {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        val role = serverOwnerRole(this)
        if (role == null) respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", message))
        return role
    }

    suspend fun ApplicationCall.fail(failure: ExtensionPackageServiceException) {
        val status = when (failure.code) {
            "NOT_FOUND" -> HttpStatusCode.NotFound
            "PACKAGE_CHANGED" -> HttpStatusCode.Conflict
            "BUILDER_UNAVAILABLE" -> HttpStatusCode.ServiceUnavailable
            "PACKAGE_UNAVAILABLE" -> HttpStatusCode.BadGateway
            else -> HttpStatusCode.BadRequest
        }
        respond(status, CompanyErrorResponse(failure.code, failure.message ?: "No se pudo completar la operacion."))
    }

    get("/server/extensions/catalog") {
        call.owner("Debes iniciar sesion como server_owner para consultar el catalogo de extensiones.") ?: return@get
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.catalog() })
    }

    get("/server/extensions/{id}/status") {
        call.owner("Debes iniciar sesion como server_owner para consultar extensiones.") ?: return@get
        val id = call.parameters["id"].orEmpty()
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.status(id) })
    }

    get("/server/extensions/{id}/inspect") {
        call.owner("Debes iniciar sesion como server_owner para revisar paquetes de extensiones.") ?: return@get
        val id = call.parameters["id"].orEmpty()
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.inspect(id) })
        } catch (failure: ExtensionPackageServiceException) {
            call.fail(failure)
        }
    }

    post("/server/extensions/{id}/install") {
        val role = call.owner("Debes iniciar sesion como server_owner para instalar extensiones.") ?: return@post
        val id = call.parameters["id"].orEmpty()
        try {
            call.respond(HttpStatusCode.Accepted, withContext(Dispatchers.IO) { service.install(id, role) })
        } catch (failure: ExtensionPackageServiceException) {
            call.fail(failure)
        }
    }

    post("/server/extensions/{id}/authorize-unsigned") {
        val role = call.owner("Debes iniciar sesion como server_owner para autorizar paquetes sin firma.") ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = try {
            call.receive<AuthorizeUnsignedRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@post
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.authorizeUnsigned(id, role, request.confirmationText, request.expectedPackageSha256, request.expectedCommit) })
        } catch (failure: ExtensionPackageServiceException) {
            call.fail(failure)
        }
    }

    delete("/server/extensions/{id}") {
        val role = call.owner("Debes iniciar sesion como server_owner para quitar extensiones.") ?: return@delete
        val id = call.parameters["id"].orEmpty()
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.remove(id, role) })
        } catch (failure: ExtensionPackageServiceException) {
            call.fail(failure)
        }
    }
}
