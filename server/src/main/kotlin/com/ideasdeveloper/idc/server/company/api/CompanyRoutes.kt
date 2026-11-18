package com.ideasdeveloper.idc.server.company.api

import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.idc.server.company.application.CoreUpdateOutcome
import com.ideasdeveloper.idc.server.company.application.CoreUpdateService
import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.idc.server.company.application.CompanyMailRejectedException
import com.ideasdeveloper.idc.server.company.application.CompanyMailStorageException
import com.ideasdeveloper.idc.server.company.application.CompanySelfException
import com.ideasdeveloper.idc.server.company.application.CompanySelfService
import com.ideasdeveloper.idc.server.company.application.ServerOwnerAuthorizer
import com.ideasdeveloper.idc.server.app.RecentServerErrors
import com.ideasdeveloper.idc.server.modules.CompanyMailInput
import com.ideasdeveloper.idc.server.app.ServerLogsResponse
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
    coreUpdates: CoreUpdateService? = null,
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

    // SMTP de la empresa (correo saliente); la contrasena nunca se devuelve, solo passwordSet.
    get("/companies/me/mail") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@get
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.mailSettings(context.companyCode, context.token) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("COMPANY_MAIL_UNAVAILABLE", exception.message ?: "No se pudo cargar el correo."))
        }
    }

    // Guarda el SMTP de la empresa; rechaza puertos no permitidos y hosts privados antes de guardar.
    put("/companies/me/mail") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@put
        }
        val request = try {
            call.receive<CompanyMailInput>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.updateMailSettings(context.companyCode, context.token, request) })
        } catch (exception: CompanyMailRejectedException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("COMPANY_MAIL_NOT_SAVED", exception.message ?: "Datos de correo no validos."))
        } catch (exception: CompanyMailStorageException) {
            call.respond(HttpStatusCode.InternalServerError, CompanyErrorResponse("COMPANY_MAIL_STORAGE_FAILED", "No se pudo guardar el correo de la empresa."))
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("COMPANY_MAIL_UNAVAILABLE", exception.message ?: "No tienes permiso para cambiar el correo."))
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

    get("/companies/me/users") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@get
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.users(context.companyCode, context.token) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("COMPANY_USERS_UNAVAILABLE", exception.message ?: "No se pudieron cargar usuarios."))
        }
    }

    post("/companies/me/users") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@post
        }
        val request = try {
            call.receive<CreateCompanyUserRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@post
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@post
        }
        try {
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { service.createUser(context.companyCode, context.token, request) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_USER_NOT_CREATED", exception.message ?: "No se pudo crear el usuario."))
        }
    }

    put("/companies/me/users/{userId}/permissions") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@put
        }
        val request = try {
            call.receive<UpdateCompanyUserPermissionsRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        }
        try {
            call.respond(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) {
                    service.updateUserPermissions(context.companyCode, context.token, call.parameters["userId"].orEmpty(), request)
                },
            )
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_USER_NOT_UPDATED", exception.message ?: "No se pudo actualizar el usuario."))
        }
    }

    // Every field of a user (user, name, email, active, permissions, password), by the owner.
    put("/companies/me/users/{userId}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", "Debes iniciar sesion como business_owner."))
            return@put
        }
        val request = runCatching { call.receive<UpdateCompanyUserRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
        try {
            call.respond(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) { service.updateUser(context.companyCode, context.token, call.parameters["userId"].orEmpty(), request) },
            )
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_USER_NOT_UPDATED", exception.message ?: "No se pudo actualizar el usuario."))
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_USER_NOT_UPDATED", exception.message ?: "No se pudo actualizar el usuario."))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()))
        }
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
        // Autoriza de verdad (sesion de business_owner) ANTES de leer el cuerpo multipart.
        try {
            withContext(Dispatchers.IO) { service.listBackups(context.companyCode, context.token) }
        } catch (exception: CompanySelfException) {
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

    get("/server/logs") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (call.serverOwnerRole(authorizer) == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner para consultar los errores."),
            )
            return@get
        }
        call.respond(HttpStatusCode.OK, ServerLogsResponse(RecentServerErrors.snapshot().asReversed()))
    }

    get("/server/modules") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para administrar modulos del servidor.",
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

        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.listServerModules() })
    }

    get("/server/modules/catalog") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner para consultar el catalogo."))
            return@get
        }
        val service = provisioning
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", "El servidor no tiene configuracion de provisioning disponible."))
            return@get
        }
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.serverModuleCatalog() })
    }

    put("/server/modules/catalog") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner para configurar el catalogo."))
            return@put
        }
        val service = provisioning
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", "El servidor no tiene configuracion de provisioning disponible."))
            return@put
        }
        val request = try {
            call.receive<UpdateServerModuleCatalogRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.updateServerModuleCatalog(request.catalogUrl) })
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("SERVER_MODULE_CATALOG_NOT_UPDATED", exception.message ?: "No se pudo actualizar el catalogo."))
        }
    }

    delete("/server/modules/{moduleId}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para eliminar modulos del servidor.",
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

        val moduleId = call.parameters["moduleId"].orEmpty()
        try {
            withContext(Dispatchers.IO) {
                service.deleteServerModule(moduleId)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("SERVER_MODULE_NOT_DELETED", exception.message ?: "No se pudo eliminar el modulo del servidor."),
            )
            return@delete
        }

        call.respond(HttpStatusCode.NoContent)
    }

    post("/server/modules/{moduleId}/install") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para instalar modulos del servidor.",
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

        val moduleId = call.parameters["moduleId"].orEmpty()
        val response = try {
            withContext(Dispatchers.IO) {
                service.installServerModule(moduleId, call.request.headers["X-App-Version"])
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse(exception.code ?: "SERVER_MODULE_NOT_INSTALLED", exception.message ?: "No se pudo instalar el modulo del servidor."),
            )
            return@post
        }

        call.respond(HttpStatusCode.OK, response)
    }

    // Creates the server company (server workspace) when it does not exist yet and returns its code.
    // Single-company installs use it as their company: the server owner is its business owner.
    post("/server/workspace") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner para crear la empresa del servidor."),
            )
            return@post
        }
        val service = provisioning
        if (service == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", "El servidor no tiene configuracion de provisioning disponible."),
            )
            return@post
        }
        val code = try {
            withContext(Dispatchers.IO) { service.ensureServerWorkspace(serverOwnerRole) }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("SERVER_WORKSPACE_NOT_CREATED", exception.message ?: "No se pudo crear la empresa del servidor."),
            )
            return@post
        }
        call.respond(HttpStatusCode.OK, ServerWorkspaceResponse(code))
    }

    // Uses a module in the server workspace (created on first use), without a company.
    put("/server/modules/{moduleId}/server-usage") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para usar modulos en el servidor.",
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
        val request = call.receive<UpdateCompanyModuleRequest>()
        val moduleId = call.parameters["moduleId"].orEmpty()
        val response = try {
            withContext(Dispatchers.IO) {
                service.setServerModuleUsage(moduleId, request.enabled, serverOwnerRole)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("SERVER_MODULE_USAGE", exception.message ?: "No se pudo cambiar el uso del modulo en el servidor."),
            )
            return@put
        }
        call.respond(HttpStatusCode.OK, response)
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


    put("/companies/{code}") {
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
            call.receive<UpdateCompanyRequest>()
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
                service.updateCompany(code, request, serverOwnerRole)
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
        // Regla core: solo server_owner decide la disponibilidad de modulos por empresa.
        // business_owner administra usuarios/permisos solo dentro de su propia base tenant,
        // pero no habilita modulos.
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

    // Solo desde la app: las contrasenas nunca pasan por el MCP de agentes.
    // Solo el dueno del servidor duplica: la copia mantiene el business owner o recibe uno nuevo (newOwner).
    post("/companies/{code}/duplicate") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
            ?: return@post call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner para duplicar empresas."))
        val service = provisioning
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", "El servidor no tiene configuracion de provisioning disponible."))
        val request = runCatching { call.receive<DuplicateCompanyRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Indica el codigo de la nueva empresa."))
        val source = call.parameters["code"].orEmpty()
        try {
            val created = withContext(Dispatchers.IO) { service.duplicateCompany(source, request.code, serverOwnerRole, request.newOwner) }
            call.respond(HttpStatusCode.Created, created)
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_NOT_DUPLICATED", exception.message ?: "No se pudo duplicar la empresa."))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toProvisioningMessage()))
        }
    }

    // El dueno del servidor vincula la empresa al grupo de otra (mismo business owner) o la desvincula.
    put("/companies/{code}/link") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
            ?: return@put call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner para vincular empresas."))
        val service = provisioning
            ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", "El servidor no tiene configuracion de provisioning disponible."))
        val request = runCatching { call.receive<LinkCompanyRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
        try {
            val companies = withContext(Dispatchers.IO) { service.linkCompany(call.parameters["code"].orEmpty(), request.targetCode, serverOwnerRole) }
            call.respond(HttpStatusCode.OK, companies)
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_NOT_LINKED", exception.message ?: "No se pudo vincular la empresa."))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()))
        }
    }

    // Estado de la actualizacion del Core y ultima solicitud hecha desde la app.
    get("/server/core-update") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.serverOwnerRole(authorizer)
            ?: return@get call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner."))
        val service = coreUpdates
            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("CORE_UPDATE_NOT_CONFIGURED", "La actualizacion del servidor no esta disponible."))
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.status() })
    }

    // Pide actualizar el Core a la ultima version del catalogo; la ejecuta el ayudante root del servidor.
    post("/server/core-update") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
            ?: return@post call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner."))
        val service = coreUpdates
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("CORE_UPDATE_NOT_CONFIGURED", "La actualizacion del servidor no esta disponible."))
        when (val outcome = withContext(Dispatchers.IO) { service.request(serverOwnerRole) }) {
            is CoreUpdateOutcome.Accepted -> call.respond(HttpStatusCode.Accepted, outcome.request)
            CoreUpdateOutcome.CatalogUnavailable -> call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("VERSION_CATALOG_UNAVAILABLE", "No se pudo leer el catalogo de versiones."))
            CoreUpdateOutcome.UpToDate -> call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("CORE_UP_TO_DATE", "El servidor ya tiene la ultima version."))
            CoreUpdateOutcome.InProgress -> call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("CORE_UPDATE_IN_PROGRESS", "Ya hay una actualizacion en curso."))
        }
    }

    // Nombre del servidor que ven los server owners en el panel y el selector de empresas.
    put("/server/name") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
            ?: return@put call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner."))
        val service = provisioning
            ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", "El servidor no tiene configuracion de provisioning disponible."))
        val request = runCatching { call.receive<ServerNameRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.updateServerName(request.name, serverOwnerRole) })
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("SERVER_NAME_INVALID", exception.message ?: "Nombre invalido."))
        }
    }

    // The business owner of a company, for the server owner to edit it.
    get("/companies/{code}/business-owner") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.serverOwnerRole(authorizer)
            ?: return@get call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner."))
        val service = provisioning
            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", "El servidor no tiene configuracion de provisioning disponible."))
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.businessOwner(call.parameters["code"].orEmpty()) })
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("BUSINESS_OWNER_NOT_FOUND", exception.message ?: "Business owner no encontrado."))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()))
        }
    }

    // User, name, email and password of a company's business owner, by the server owner.
    put("/companies/{code}/business-owner") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
            ?: return@put call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner."))
        val service = provisioning
            ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", "El servidor no tiene configuracion de provisioning disponible."))
        val request = runCatching { call.receive<UpdateBusinessOwnerRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
        try {
            call.respond(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) { service.updateBusinessOwner(call.parameters["code"].orEmpty(), request, serverOwnerRole) },
            )
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("BUSINESS_OWNER_NOT_UPDATED", exception.message ?: "No se pudo actualizar el business owner."))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()))
        }
    }

    put("/companies/{code}/owner-password") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val serverOwnerRole = call.serverOwnerRole(authorizer)
        if (serverOwnerRole == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                CompanyErrorResponse(
                    code = "SERVER_OWNER_REQUIRED",
                    message = "Debes iniciar sesion como server_owner para cambiar la contrasena del business owner.",
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
            call.receive<ResetBusinessOwnerPasswordRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))
            return@put
        }

        val code = call.parameters["code"].orEmpty()
        try {
            withContext(Dispatchers.IO) {
                service.resetBusinessOwnerPassword(code, request.password, serverOwnerRole)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("PASSWORD_NOT_UPDATED", exception.message ?: "No se pudo cambiar la contrasena."),
            )
            return@put
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()),
            )
            return@put
        }

        call.respond(HttpStatusCode.NoContent)
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
