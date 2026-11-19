package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.company.api.CompanyErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
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

@Serializable
data class ExtensionUrlRequest(
    val id: String = "",
    /** URL https del ZIP del paquete. */
    val url: String = "",
)

internal const val UploadDisabledMessage =
    "La instalacion de extensiones por ZIP/URL esta deshabilitada; activala con EXTENSION_UPLOAD_ENABLED."

/**
 * Paquetes externos de extensiones, solo para el dueno del servidor.
 * [serverOwnerRole] devuelve el rol de PostgreSQL del server_owner autenticado, o null si no lo es.
 */
fun Route.extensionPackageRoutes(
    service: ExtensionPackageService,
    uploadEnabled: Boolean,
    serverOwnerRole: suspend (ApplicationCall) -> String?,
) {
    suspend fun ApplicationCall.owner(message: String): String? {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        val role = serverOwnerRole(this)
        if (role == null) respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", message))
        return role
    }

    // Rutas de ZIP/URL: ademas de server_owner exigen EXTENSION_UPLOAD_ENABLED; se corta antes de tocar el staging.
    suspend fun ApplicationCall.uploadOwner(message: String): String? {
        val role = owner(message) ?: return null
        if (!uploadEnabled) {
            respond(HttpStatusCode.Forbidden, CompanyErrorResponse("FEATURE_DISABLED", UploadDisabledMessage))
            return null
        }
        return role
    }

    suspend fun ApplicationCall.fail(failure: ExtensionPackageServiceException) {
        val status = when (failure.code) {
            "NOT_FOUND" -> HttpStatusCode.NotFound
            "PACKAGE_CHANGED" -> HttpStatusCode.Conflict
            "BUILDER_UNAVAILABLE", "CATALOG_UNAVAILABLE" -> HttpStatusCode.ServiceUnavailable
            "PACKAGE_UNAVAILABLE" -> HttpStatusCode.BadGateway
            "PAYLOAD_TOO_LARGE" -> HttpStatusCode.PayloadTooLarge
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

    // ZIP subido: revisa y guarda el archivo (no instala). Multipart con los campos id y file.
    post("/server/extensions/upload") {
        call.uploadOwner("Debes iniciar sesion como server_owner para subir paquetes de extensiones.") ?: return@post
        var id = ""
        var fileName: String? = null
        var bytes: ByteArray? = null
        var tooLarge = false
        try {
            call.receiveMultipart().forEachPart { part ->
                when {
                    part is PartData.FormItem && part.name == "id" -> id = part.value.trim()
                    part is PartData.FileItem && part.name == "file" -> {
                        fileName = part.originalFileName
                        // Se lee un byte de mas del maximo para detectar el exceso sin cargar todo el cuerpo.
                        val read = part.provider().readRemaining(ExtensionPackageService.MaxZipBytes.toLong() + 1).readBytes()
                        if (read.size > ExtensionPackageService.MaxZipBytes) tooLarge = true else bytes = read
                    }
                }
                part.dispose()
            }
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un formulario multipart con los campos id y file."))
            return@post
        }
        val uploaded = bytes
        when {
            tooLarge -> call.respond(
                HttpStatusCode.PayloadTooLarge,
                CompanyErrorResponse("PAYLOAD_TOO_LARGE", "El ZIP es demasiado grande (maximo ${ExtensionPackageService.MaxZipBytes / 1024 / 1024} MB)."),
            )
            id.isEmpty() || uploaded == null -> call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Indica el id de la extension y sube un archivo ZIP."))
            else -> try {
                call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.inspectZip(id, fileName, uploaded) })
            } catch (failure: ExtensionPackageServiceException) {
                call.fail(failure)
            }
        }
    }

    // Paquete desde una URL https: la descarga (anclada anti-SSRF), la revisa y la guarda (no instala).
    post("/server/extensions/from-url") {
        call.uploadOwner("Debes iniciar sesion como server_owner para instalar paquetes desde una URL.") ?: return@post
        val request = try {
            call.receive<ExtensionUrlRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido con id y url."))
            return@post
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.inspectUrl(request.id.trim(), request.url) })
        } catch (failure: ExtensionPackageServiceException) {
            call.fail(failure)
        }
    }

    // Autoriza por hash un paquete sin firma ya revisado (ZIP o URL).
    post("/server/extensions/staged/{token}/authorize-unsigned") {
        val role = call.uploadOwner("Debes iniciar sesion como server_owner para autorizar paquetes sin firma.") ?: return@post
        val token =call.parameters["token"].orEmpty()
        val request = try {
            call.receive<AuthorizeUnsignedRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@post
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.authorizeStaged(token, role, request.confirmationText, request.expectedPackageSha256) })
        } catch (failure: ExtensionPackageServiceException) {
            call.fail(failure)
        }
    }

    // Instala el paquete ya revisado (ZIP o URL); exige firma o hash autorizado.
    post("/server/extensions/staged/{token}/install") {
        val role = call.uploadOwner("Debes iniciar sesion como server_owner para instalar extensiones.") ?: return@post
        val token =call.parameters["token"].orEmpty()
        try {
            call.respond(HttpStatusCode.Accepted, withContext(Dispatchers.IO) { service.installStaged(token, role) })
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
