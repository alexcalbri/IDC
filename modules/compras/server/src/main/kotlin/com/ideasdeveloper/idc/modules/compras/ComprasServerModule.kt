package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.SettingsTable
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiTone
import kotlin.coroutines.cancellation.CancellationException
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

/** Compras: solicitudes de compra con cotizaciones opcionales, aprobadores con limite de monto y adjuntos de respaldo. */
object ComprasServerModule : ServerModule {
    private val log = LoggerFactory.getLogger(ComprasServerModule::class.java)

    override val migrationPaths = listOf(
        "modules/compras/migrations/V001__create_compras.sql",
    )

    override val runtimeTables = listOf(
        "compras_settings",
        "compras_departments",
        "compras_approvers",
        "compras_requests",
        "compras_quotes",
        "compras_attachments",
        "compras_events",
    )

    /**
     * Una empresa duplicada recibe la moneda por defecto y los departamentos. Las solicitudes, cotizaciones,
     * adjuntos y el historial se quedan atras, y tambien los aprobadores (son usuarios, que no se copian).
     */
    override val duplicatedSettings = listOf(
        SettingsTable("compras_settings"),
        SettingsTable("compras_departments"),
    )

    override val definition = ModuleDefinition(
        id = ComprasModule.id,
        displayName = ComprasModule.displayName,
        description = "Solicitudes de compra con cotizaciones opcionales, aprobadores con limite de monto, adjuntos de respaldo y totales por departamento, mes y moneda.",
        version = "0.1.0",
        minCoreVersion = "0.8.0",
        minAppVersion = "0.5.0",
        permissions = listOf(
            ModulePermissionDefinition(ComprasModule.View, "Ver compras", "Permite entrar a Compras y ver sus propias solicitudes."),
            ModulePermissionDefinition(ComprasModule.Create, "Solicitar compras", "Permite crear solicitudes de compra y editar, enviar o cancelar las suyas."),
            ModulePermissionDefinition(ComprasModule.ViewAll, "Ver todas las solicitudes", "Permite ver las solicitudes de todos, el tablero completo y los totales."),
            ModulePermissionDefinition(ComprasModule.Approve, "Aprobar compras", "Permite aprobar o rechazar solicitudes dentro de su limite de monto (ademas debe estar en la lista de aprobadores)."),
            ModulePermissionDefinition(ComprasModule.Manage, "Administrar compras", "Permite configurar departamentos, aprobadores y moneda, y editar o mover cualquier solicitud."),
        ),
        ui = ModuleUiEntry(type = "native", entry = ComprasScreens.UiRoute, home = "home"),
    )

    override val mcp: ModuleMcp
        get() = ComprasMcpTools.mcp

    override val jobs = listOf(
        // Archivos de adjuntos quitados, de solicitudes borradas y de subidas que no se guardaron.
        ModuleJob("attachments-cleanup", Duration.ofHours(1)) { context ->
            try {
                ComprasAttachments.cleanup(context.tenant, context.access.files(context.companyCode, ComprasModule.id))
            } catch (failure: Exception) {
                log.warn("La limpieza de adjuntos de compras fallo en {}.", context.companyCode, failure)
            }
        },
    )

    // Permiso del usuario antes de abrir datos: el dueno del negocio y el del servidor tienen todos.
    private fun ModulePermissions.allows(permission: String): Boolean = businessOwner || serverOwner || has(permission)

    private fun actorOf(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions) =
        Actor(permissions.userId, access.userIdentity(tenant, permissions)?.name ?: "Usuario", permissions)

    private suspend fun ApplicationCall.forbidden() =
        respond(HttpStatusCode.Forbidden, ComprasErrorResponse("missing_permission", "No tienes permiso para esta accion."))

    override fun routes(route: Route, access: ModuleAccessService) {
        route.route(ComprasScreens.UiRoute) {
            // Archivos de los formularios: la subida queda pendiente hasta que el formulario se guarda (ver ComprasAttachments).
            post("/files") {
                val permissions = call.requireModuleAccess(access, ComprasModule.id) ?: return@post
                if (!permissions.allows(ComprasModule.View) || !(permissions.allows(ComprasModule.Create) || permissions.allows(ComprasModule.Manage))) {
                    return@post call.forbidden()
                }
                val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && length > ComprasAttachments.MaxFileBytes) {
                    return@post call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ComprasErrorResponse("too_large", "El archivo supera el maximo de ${ComprasAttachments.MaxFileBytes / (1024 * 1024)} MB."),
                    )
                }
                fun header(name: String) = call.request.headers[name]?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }.orEmpty()
                val name = header("X-File-Name")
                val path = header("X-File-Path")
                val contentType = call.request.contentType().withoutParameters().toString()
                val companyCode = access.companyCode(call)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ComprasErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
                // El cuerpo va directo al almacen de archivos: nunca se carga entero en memoria.
                val body = call.receiveChannel().toInputStream()
                val result = withContext(Dispatchers.IO) {
                    access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                        // No se traga la cancelacion de la corrutina ni los Error: solo los fallos de la subida.
                        try {
                            val actor = actorOf(tenant, access, permissions)
                            Result.success(ComprasAttachments.upload(tenant, access.files(companyCode, ComprasModule.id), actor, name, path, contentType, body))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                    }
                } ?: return@post call.respond(HttpStatusCode.Forbidden, ComprasErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                result.fold(
                    onSuccess = { ref -> call.respondText(UiJson.encodeToString(UiFileRef.serializer(), ref), ContentType.Application.Json) },
                    onFailure = { failure ->
                        if (failure is ComprasValidationException) {
                            call.respond(HttpStatusCode.BadRequest, ComprasErrorResponse("invalid_file", failure.message.orEmpty()))
                        } else {
                            log.warn("No se pudo guardar un archivo de compras.", failure)
                            call.respond(HttpStatusCode.InternalServerError, ComprasErrorResponse("DATABASE_ERROR", "No se pudo guardar el archivo."))
                        }
                    },
                )
            }

            // Un archivo de una solicitud visible para el usuario, o una subida pendiente suya (ComprasAttachments.file decide).
            get("/files/{id}") {
                val permissions = call.requireModuleAccess(access, ComprasModule.id) ?: return@get
                if (!permissions.allows(ComprasModule.View)) return@get call.forbidden()
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val companyCode = access.companyCode(call)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ComprasErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
                val file = try {
                    withContext(Dispatchers.IO) {
                        access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                            ComprasAttachments.file(tenant, access.files(companyCode, ComprasModule.id), actorOf(tenant, access, permissions), id)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.warn("No se pudo leer un archivo de compras.", failure)
                    return@get call.respond(HttpStatusCode.InternalServerError, ComprasErrorResponse("DATABASE_ERROR", "No se pudo completar la operacion."))
                } ?: return@get call.respond(HttpStatusCode.NotFound, ComprasErrorResponse("not_found", "Ese archivo ya no esta disponible."))
                val content = withContext(Dispatchers.IO) { file.open() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ComprasErrorResponse("missing_content", "El archivo ya no esta en el almacenamiento del servidor."))
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.ref.name).toString(),
                )
                // Solo se sirve un tipo de la lista permitida (nunca html ni svg) y el navegador no adivina otro.
                call.response.header("X-Content-Type-Options", "nosniff")
                val type = ComprasDownloads.contentType(file.contentType)
                call.respondOutputStream(type) { content.use { it.copyTo(this) } }
            }

            get("/{screen}") {
                val permissions = call.requireModuleAccess(access, ComprasModule.id) ?: return@get
                val screen = call.parameters["screen"].orEmpty()
                if (!ComprasScreens.isScreen(screen)) {
                    return@get call.respond(HttpStatusCode.NotFound, ComprasErrorResponse("UNKNOWN_SCREEN", "Seccion no encontrada."))
                }
                if (!ComprasScreens.canOpen(screen, { permission -> permissions.allows(permission) })) return@get call.forbidden()
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.respondScreen(access) { tenant ->
                    val actor = actorOf(tenant, access, permissions)
                    homeOnInvalid(tenant, actor) { ComprasScreens.build(tenant, actor, screen, params) }
                }
            }

            post("/{screen}") {
                val permissions = call.requireModuleAccess(access, ComprasModule.id) ?: return@post
                val screen = call.parameters["screen"].orEmpty()
                if (!ComprasScreens.isScreen(screen)) {
                    return@post call.respond(HttpStatusCode.NotFound, ComprasErrorResponse("UNKNOWN_SCREEN", "Seccion no encontrada."))
                }
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                // El permiso exacto de la operacion se comprueba antes de abrir la base de datos.
                if (!ComprasActions.permitted(screen, params, { permission -> permissions.allows(permission) })) return@post call.forbidden()
                // Cuerpo acotado: los formularios son texto corto; los archivos viajan por /files.
                val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declared != null && declared > MaxBodyBytes) return@post call.tooLarge()
                val bytes = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { it.readNBytes(MaxBodyBytes + 1) } }
                if (bytes.size > MaxBodyBytes) return@post call.tooLarge()
                val body = String(bytes, Charsets.UTF_8)
                val values = runCatching { UiJson.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } }.getOrNull().orEmpty()
                call.respondScreen(access) { tenant ->
                    val actor = actorOf(tenant, access, permissions)
                    homeOnInvalid(tenant, actor) {
                        val outcome = ComprasActions.run(tenant, actor, access.notificationsFor(ComprasModule.id), screen, params, values)
                        ComprasScreens.build(tenant, actor, outcome.screen, outcome.params, outcome.notice, outcome.submitted)
                    }
                }
            }
        }
    }

    // Abre la base de la empresa autorizada, ejecuta [block] (JDBC, en el hilo de IO) y responde con su pantalla.
    private suspend fun ApplicationCall.respondScreen(access: ModuleAccessService, block: (Connection) -> UiScreen) {
        val companyCode = access.companyCode(this)
            ?: return respond(HttpStatusCode.BadRequest, ComprasErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
        try {
            // La conexion se abre, se usa y se cierra dentro de un unico withContext(IO).
            val screen = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode)?.use { tenant -> block(tenant) } }
                ?: return respond(HttpStatusCode.Forbidden, ComprasErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
            respondText(UiJson.encodeToString(UiScreen.serializer(), screen), ContentType.Application.Json)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Sin detalles para el cliente: el error queda solo en el registro del servidor.
            log.warn("Error en Compras ({}).", (failure as? SQLException)?.sqlState ?: failure.javaClass.simpleName, failure)
            respond(HttpStatusCode.InternalServerError, ComprasErrorResponse("DATABASE_ERROR", "No se pudo completar la operacion."))
        }
    }

    // Un error de validacion al armar la pantalla (p. ej. un id invalido) lleva a Inicio con el aviso en rojo.
    private fun homeOnInvalid(tenant: Connection, actor: Actor, block: () -> UiScreen): UiScreen = try {
        block()
    } catch (failure: ComprasValidationException) {
        ComprasScreens.build(tenant, actor, "home", emptyMap(), UiNotice(failure.message.orEmpty(), UiTone.Danger))
    }

    private suspend fun ApplicationCall.tooLarge() =
        respond(HttpStatusCode.PayloadTooLarge, ComprasErrorResponse("too_large", "La peticion es demasiado grande."))

    // Tope del cuerpo de los formularios (256 KB).
    private const val MaxBodyBytes = 256 * 1024
}

@Serializable
data class ComprasErrorResponse(val code: String, val message: String)

class ComprasServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = ComprasServerModule
}
