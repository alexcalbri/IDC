package com.ideasdeveloper.idc.modules.propietarios

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissionLookup
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiSuggestion
import com.ideasdeveloper.idc.ui.UiSuggestions
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

object PropietariosServerModule : ServerModule {
    private val log = LoggerFactory.getLogger(PropietariosServerModule::class.java)
    private val SuggestField = Regex("^[a-z][a-z0-9_]{0,19}$")

    override val migrationPaths = listOf(
        "modules/propietarios/migrations/V001__create_propietarios.sql",
    )

    override val runtimeTables = listOf(
        "propietarios_developments",
        "propietarios_lots",
        "propietarios_ownerships",
        "propietarios_lot_history",
        "propietarios_attachments",
    )

    // Los desarrollos, lotes y titulares son datos de la empresa, no configuracion: una empresa duplicada empieza vacia.
    override val duplicatedSettings = emptyList<com.ideasdeveloper.idc.server.modules.SettingsTable>()

    override val jobs = listOf(
        // Archivos de adjuntos quitados y subidas cuyo formulario nunca se guardo.
        ModuleJob("attachments-cleanup", Duration.ofHours(1)) { context ->
            PropietariosRepository.cleanup(context.tenant, context.access.files(context.companyCode, PropietariosModule.id))
        },
        // Lee y aplica las importaciones de las extensiones (una rebanada corta cada minuto).
        ModuleJob("import-runner", Duration.ofMinutes(1)) { context ->
            try {
                PropietariosImportSources.runPending(
                    PropietariosImportJobContext(
                        context.access, context.tenant, context.companyCode, context.access.files(context.companyCode, PropietariosModule.id), PropietariosRepository.today(),
                    ),
                    Duration.ofSeconds(40),
                )
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("Fallo el trabajo de importacion de Propietarios ({}).", failure.javaClass.simpleName)
            }
        },
    )

    // Las extensiones de importacion del modulo (Monday...) ofrecen por aqui sus fuentes, pantallas y herramientas para agentes.
    override fun linkExtensions(extensions: List<ServerExtension>) {
        PropietariosImportSources.link(extensions)
    }

    override val mcp: ModuleMcp
        get() = PropietariosMcpTools.mcp

    override val customerTimeline: CustomerTimeline
        get() = PropietariosCustomerTimeline.timeline

    override val definition = ModuleDefinition(
        id = PropietariosModule.id,
        displayName = PropietariosModule.displayName,
        description = "Registro de desarrollos, lotes y propietarios: area, numero de registro y estado de cada lote, titulares vigentes con su porcentaje, historial de cambios de titularidad y adjuntos como escrituras y planos.",
        version = "0.2.0",
        minCoreVersion = "0.8.0",
        minAppVersion = "0.7.0",
        permissions = listOf(
            ModulePermissionDefinition(
                PropietariosModule.View,
                "Ver propietarios",
                "Permite abrir el modulo y ver todos los desarrollos, lotes, titulares, historial y adjuntos.",
            ),
            ModulePermissionDefinition(
                PropietariosModule.Manage,
                "Gestionar lotes y titularidad",
                "Permite crear y editar lotes, registrar cambios de titularidad (altas, traspasos y bajas) y adjuntar archivos.",
            ),
            ModulePermissionDefinition(
                PropietariosModule.Admin,
                "Administrar desarrollos",
                "Permite crear, editar y archivar desarrollos, y archivar lotes.",
            ),
            ModulePermissionDefinition(
                PropietariosModule.Import,
                "Importar datos",
                "Permite importar desarrollos, lotes y propietarios desde otros sistemas (por ejemplo Monday) con las extensiones de importacion instaladas.",
            ),
        ),
        supportsExtensions = true,
        extensionPermissions = listOf("propietarios.import.write"),
        ui = ModuleUiEntry(type = "native", entry = PropietariosModule.UiRoute, home = "desarrollos"),
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        route.route(PropietariosModule.UiRoute) {
            // Archivos del formulario de un lote: una subida espera a que se guarde el formulario (ver PropietariosRepository.attach).
            post("/files") {
                val permissions = call.requireModuleAccess(access, PropietariosModule.id) ?: return@post
                // Subir archivos es parte de editar el lote.
                if (!permissions.has(PropietariosModule.Manage)) {
                    return@post call.respond(HttpStatusCode.Forbidden, PropietariosErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && length > PropietariosRepository.MaxFileBytes) {
                    return@post call.respond(HttpStatusCode.PayloadTooLarge, PropietariosErrorResponse("too_large", "El archivo supera el maximo de ${PropietariosRepository.MaxFileBytes / (1024 * 1024)} MB."))
                }
                fun header(name: String) = call.request.headers[name]?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }.orEmpty()
                val name = header("X-File-Name")
                val path = header("X-File-Path")
                val contentType = call.request.contentType().withoutParameters().toString()
                val companyCode = access.companyCode(call)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, PropietariosErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
                // El cuerpo va directo al almacenamiento de archivos; nunca se guarda entero en memoria.
                val body = call.receiveChannel().toInputStream()
                val result = withContext(Dispatchers.IO) {
                    access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                        try {
                            Result.success(PropietariosRepository.upload(tenant, access.files(companyCode, PropietariosModule.id), name, path, contentType, body, permissions.userId))
                        } catch (stop: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw stop
                        } catch (stop: CancellationException) {
                            throw stop
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                    }
                } ?: return@post call.respond(HttpStatusCode.Forbidden, PropietariosErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                result.fold(
                    onSuccess = { ref -> call.respondText(UiJson.encodeToString(UiFileRef.serializer(), ref), ContentType.Application.Json) },
                    onFailure = { failure ->
                        if (failure is PropietariosValidationException) {
                            call.respond(HttpStatusCode.BadRequest, PropietariosErrorResponse("invalid_file", failure.message.orEmpty()))
                        } else {
                            log.warn("No se pudo guardar un archivo de propietarios ({}).", failure.javaClass.simpleName)
                            call.respond(HttpStatusCode.InternalServerError, PropietariosErrorResponse("DATABASE_ERROR", "No se pudo guardar el archivo."))
                        }
                    },
                )
            }

            // Un archivo de un lote (todos los lotes los ve quien tiene propietarios.view); una subida pendiente solo llega a quien la subio.
            get("/files/{id}") {
                val permissions = call.requireModuleAccess(access, PropietariosModule.id) ?: return@get
                if (!permissions.has(PropietariosModule.View)) {
                    return@get call.respond(HttpStatusCode.Forbidden, PropietariosErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val companyCode = access.companyCode(call)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, PropietariosErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
                val file = withContext(Dispatchers.IO) {
                    access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                        PropietariosRepository.file(tenant, access.files(companyCode, PropietariosModule.id), id)?.takeIf { stored ->
                            val lotId = stored.attachment.lotId
                                ?: return@takeIf permissions.userId != null && stored.attachment.uploadedBy == permissions.userId
                            PropietariosRepository.getLot(tenant, lotId) != null
                        }
                    }
                } ?: return@get call.respond(HttpStatusCode.NotFound, PropietariosErrorResponse("not_found", "Ese archivo ya no esta disponible."))
                val type = runCatching { ContentType.parse(safeContentType(file.attachment.contentType)) }.getOrDefault(ContentType.Application.OctetStream)
                val content = withContext(Dispatchers.IO) { file.open() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, PropietariosErrorResponse("missing_content", "El archivo ya no esta en el almacenamiento del servidor."))
                // Las cabeceras solo cuando el contenido de verdad se va a enviar (una respuesta de error no debe llevarlas).
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.attachment.name).toString(),
                )
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respondOutputStream(type) { content.use { it.copyTo(this) } }
            }

            // Clientes que se escriben en los formularios (UiField.suggest): pide propietarios.manage y el derecho a ver clientes.
            get("/suggest/customers") {
                val permissions = call.requireModuleAccess(access, PropietariosModule.id) ?: return@get
                if (!permissions.has(PropietariosModule.Manage)) {
                    return@get call.respond(HttpStatusCode.Forbidden, PropietariosErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                val customers = withContext(Dispatchers.IO) {
                    try {
                        access.permissions(call, "clientes")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        log.warn("No se pudieron leer los permisos de Clientes ({}).", failure.javaClass.simpleName)
                        null
                    }
                }
                if (customers !is ModulePermissionLookup.Granted || !customers.permissions.has("clientes.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, PropietariosErrorResponse("missing_permission", "No tienes permiso para ver clientes."))
                }
                val typed = call.request.queryParameters["q"].orEmpty().take(200)
                // El campo del formulario que pide la sugerencia (owner, owner1...): recibe el nombre y su id oculto.
                val field = call.request.queryParameters["field"]?.takeIf { SuggestField.matches(it) } ?: "owner"
                call.respondPropietarios(access, UiSuggestions.serializer()) { tenant ->
                    UiSuggestions(
                        PropietariosRepository.customerSuggestions(tenant, typed, 6).map { customer ->
                            UiSuggestion(customer.name, customer.email, mapOf(field to customer.name, "${field}_id" to customer.id.toString()))
                        },
                    )
                }
            }

            // pantallas: PropietariosRoutes.screens(this, access)
            PropietariosRoutes.screens(this, access)
        }
    }
}

// Lo que un navegador podria ejecutar si pintara el archivo dentro del origen de la app se sirve siempre como descarga simple.
private val PlainTextTypes = setOf("text/plain", "text/csv")

/** El tipo de contenido a enviar: marcado, scripts y todo tipo de texto salvo texto plano y CSV pasan a application/octet-stream. */
fun safeContentType(stored: String): String {
    val base = stored.substringBefore(';').trim().lowercase()
    val risky = base.isEmpty() || (base.startsWith("text/") && base !in PlainTextTypes) || "html" in base || "xml" in base || "javascript" in base || "ecmascript" in base
    return if (risky) "application/octet-stream" else base
}

@Serializable
data class PropietariosErrorResponse(val code: String, val message: String)

/**
 * Abre la base de datos de la empresa autorizada, corre [block] en el dispatcher IO y responde con su resultado en JSON. Una regla
 * del modulo que falla responde 400 con su mensaje; un error de base de datos responde 500.
 */
suspend fun <T> ApplicationCall.respondPropietarios(access: ModuleAccessService, serializer: KSerializer<T>, block: (Connection) -> T) {
    val companyCode = access.companyCode(this)
        ?: return respond(HttpStatusCode.BadRequest, PropietariosErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
    val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode) }
        ?: return respond(HttpStatusCode.Forbidden, PropietariosErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
    try {
        val result = withContext(Dispatchers.IO) { connection.use { tenant -> block(tenant) } }
        respondText(UiJson.encodeToString(serializer, result), ContentType.Application.Json)
    } catch (failure: PropietariosValidationException) {
        respond(HttpStatusCode.BadRequest, PropietariosErrorResponse("invalid_request", failure.message.orEmpty()))
    } catch (failure: SQLException) {
        respond(HttpStatusCode.InternalServerError, PropietariosErrorResponse("DATABASE_ERROR", "No se pudo completar la operacion."))
    }
}

class PropietariosServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = PropietariosServerModule
}
