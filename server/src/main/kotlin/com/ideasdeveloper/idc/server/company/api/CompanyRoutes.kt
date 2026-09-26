package com.ideasdeveloper.idc.server.company.api

import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.idc.server.company.application.CompanySelfException
import com.ideasdeveloper.idc.server.company.application.CompanySelfService
import com.ideasdeveloper.idc.server.company.application.ServerOwnerAuthorizer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receive
import io.ktor.server.response.respondFile
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException

fun Route.companyRoutes(
    authorizer: ServerOwnerAuthorizer,
    provisioning: CompanyProvisioningService?,
    selfService: CompanySelfService?,
) {
    // Devuelve el perfil de la empresa asociada a la sesion business_owner.
    get("/companies/me") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        if (context == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@get
        }
        val service = selfService
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("COMPANY_SELF_NOT_CONFIGURED", "El servidor no tiene configuracion de empresa disponible."))
            return@get
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.profile(context.companyCode, context.token) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("COMPANY_PROFILE_UNAVAILABLE", exception.message ?: "No se pudo cargar la empresa."))
        }
    }

    // Actualiza los datos visuales de la empresa del business_owner autenticado.
    put("/companies/me") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        if (context == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@put
        }
        val service = selfService
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("COMPANY_SELF_NOT_CONFIGURED", "El servidor no tiene configuracion de empresa disponible."))
            return@put
        }
        val request = try {
            call.receive<UpdateCompanyProfileRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.updateProfile(context.companyCode, context.token, request) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_PROFILE_NOT_UPDATED", exception.message ?: "No se pudo actualizar la empresa."))
        }
    }

    // Lista los ZIPs de backup almacenados para la empresa actual.
    get("/companies/me/backups") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@get
        }
        call.respond(HttpStatusCode.OK, CompanyBackupListResponse(withContext(Dispatchers.IO) { service.listBackups(context.companyCode, context.token) }))
    }

    // Crea un ZIP de backup no destructivo para la empresa actual.
    post("/companies/me/backups") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@post
        }
        call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { service.createProfileBackup(context.companyCode, context.token) })
    }

    // Recibe un ZIP y lo guarda en el servidor sin restaurar la base de datos.
    post("/companies/me/backups/import") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@post
        }
        var fileName: String? = null
        var bytes: ByteArray? = null
        // Extrae el archivo enviado en multipart bajo el campo file.
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem && part.name == "file") {
                fileName = part.originalFileName
                bytes = part.provider().readRemaining().readBytes()
            }
            part.dispose()
        }
        val uploadedName = fileName
        val uploadedBytes = bytes
        if (uploadedName == null || uploadedBytes == null) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_BACKUP", "Debes subir un archivo ZIP."))
            return@post
        }
        try {
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { service.storeImportedBackup(context.companyCode, context.token, uploadedName, uploadedBytes) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("BACKUP_NOT_IMPORTED", exception.message ?: "No se pudo importar el backup."))
        }
    }

    // Descarga un ZIP previamente generado o importado para la empresa.
    get("/companies/me/backups/{fileName}/download") {
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@get
        }
        try {
            call.respondFile(withContext(Dispatchers.IO) { service.backupPath(context.companyCode, context.token, call.parameters["fileName"].orEmpty()).toFile() })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("BACKUP_NOT_FOUND", exception.message ?: "Backup no encontrado."))
        }
    }

    // Elimina un ZIP del almacenamiento de backups de la empresa.
    delete("/companies/me/backups/{fileName}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@delete
        }
        try {
            withContext(Dispatchers.IO) { service.deleteBackup(context.companyCode, context.token, call.parameters["fileName"].orEmpty()) }
            call.respond(HttpStatusCode.NoContent)
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("BACKUP_NOT_FOUND", exception.message ?: "Backup no encontrado."))
        }
    }

    get("/companies") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para consultar empresas.",
                ),
            )
            return@get
        }

        val service = provisioning
        if (service == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse(
                    code = "PROVISIONING_NOT_CONFIGURED",
                    message = "El servidor no tiene configuracion de provisioning disponible.",
                ),
            )
            return@get
        }

        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.listCompanies() })
    }

    post("/companies") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")

        val serverOwnerRole = call.serverOwnerRole(authorizer)

        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para crear empresas.",
                ),
            )
            return@post
        }

        val service = provisioning
        if (service == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse(
                    code = "PROVISIONING_NOT_CONFIGURED",
                    message = "El servidor no tiene configuracion de provisioning disponible.",
                ),
            )
            return@post
        }

        val request = try {
            call.receive<CreateCompanyRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@post
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@post
        }

        val response = try {
            withContext(Dispatchers.IO) {
                service.createCompany(request, serverOwnerRole)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("COMPANY_NOT_CREATED", exception.message ?: "No se pudo crear la empresa."),
            )
            return@post
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toProvisioningMessage()),
            )
            return@post
        }

        call.respond(HttpStatusCode.Created, response)
    }


    put("/companies/{code}/status") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para administrar empresas.",
                ),
            )
            return@put
        }

        val service = provisioning
        if (service == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse(
                    code = "PROVISIONING_NOT_CONFIGURED",
                    message = "El servidor no tiene configuracion de provisioning disponible.",
                ),
            )
            return@put
        }

        val request = try {
            call.receive<UpdateCompanyStatusRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        }

        val code = call.parameters["code"].orEmpty()
        val response = try {
            withContext(Dispatchers.IO) {
                service.setCompanyActive(code, request.active, serverOwnerRole)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("COMPANY_NOT_UPDATED", exception.message ?: "No se pudo actualizar la empresa."),
            )
            return@put
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()),
            )
            return@put
        }

        call.respond(HttpStatusCode.OK, response)
    }

    delete("/companies/{code}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para eliminar empresas.",
                ),
            )
            return@delete
        }

        val service = provisioning
        if (service == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse(
                    code = "PROVISIONING_NOT_CONFIGURED",
                    message = "El servidor no tiene configuracion de provisioning disponible.",
                ),
            )
            return@delete
        }

        val code = call.parameters["code"].orEmpty()
        try {
            withContext(Dispatchers.IO) {
                service.deleteInactiveCompany(code, serverOwnerRole)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("COMPANY_NOT_DELETED", exception.message ?: "No se pudo eliminar la empresa."),
            )
            return@delete
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()),
            )
            return@delete
        }

        call.respond(HttpStatusCode.NoContent)
    }

    put("/companies/{code}/modules/{moduleId}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para administrar modulos.",
                ),
            )
            return@put
        }

        val service = provisioning
        if (service == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse(
                    code = "PROVISIONING_NOT_CONFIGURED",
                    message = "El servidor no tiene configuracion de provisioning disponible.",
                ),
            )
            return@put
        }

        val request = try {
            call.receive<UpdateCompanyModuleRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        }

        val code = call.parameters["code"].orEmpty()
        val moduleId = call.parameters["moduleId"].orEmpty()
        val response = try {
            withContext(Dispatchers.IO) {
                service.setModuleEnabled(code, moduleId, request.enabled, serverOwnerRole)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("MODULE_NOT_UPDATED", exception.message ?: "No se pudo actualizar el modulo."),
            )
            return@put
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toModuleMessage()),
            )
            return@put
        }

        call.respond(HttpStatusCode.OK, response)
    }
}

// Contexto minimo extraido de headers para validar operaciones business_owner.
private data class BusinessOwnerRouteContext(
    val companyCode: String,
    val token: String,
)

// Lee token Bearer y codigo de empresa; la autorizacion real ocurre en el servicio.
private fun io.ktor.server.application.ApplicationCall.businessOwnerContext(): BusinessOwnerRouteContext? {
    val companyCode = request.headers["X-Company-Code"]?.takeIf { it.isNotBlank() } ?: return null
    val token = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?: return null
    return BusinessOwnerRouteContext(companyCode, token)
}

private suspend fun io.ktor.server.application.ApplicationCall.serverOwnerRole(
    authorizer: ServerOwnerAuthorizer,
): String? {
    val authorization = request.headers[HttpHeaders.Authorization]
    val token = authorization
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?: return null
    return withContext(Dispatchers.IO) { authorizer.serverOwnerRole(token) }
}


private fun SQLException.toProvisioningMessage(): String =
    "No se pudo completar la provision de la empresa. PostgreSQL ${sqlState.orEmpty()}: ${message.orEmpty()}"

private fun SQLException.toModuleMessage(): String =
    "No se pudo actualizar el modulo. PostgreSQL ${sqlState.orEmpty()}: ${message.orEmpty()}"

private fun SQLException.toCompanyMessage(): String =
    "No se pudo actualizar la empresa. PostgreSQL ${sqlState.orEmpty()}: ${message.orEmpty()}"
