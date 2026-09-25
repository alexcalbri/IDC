package com.ideasdeveloper.idc.server.company.api

import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.idc.server.company.application.ServerOwnerAuthorizer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import java.sql.SQLException

fun Route.companyRoutes(
    authorizer: ServerOwnerAuthorizer,
    provisioning: CompanyProvisioningService?,
) {
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
