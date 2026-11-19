package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiJson
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.util.UUID

/**
 * Subida y descarga de los adjuntos de las órdenes. Una subida espera (sin orden) a que se guarde su formulario; la descarga solo
 * entrega archivos de órdenes que el usuario ve y las subidas pendientes a quien las subió (el filtro va en SQL, en el repositorio).
 * Lo que un navegador podría ejecutar dentro del origen de la app se sirve siempre como descarga simple, con `nosniff`.
 */
object MantenimientoFiles {
    private val log = LoggerFactory.getLogger(MantenimientoFiles::class.java)

    /** Tipos que se respetan al servir; cualquier otro (en especial image/svg+xml y text/html) se sirve como application/octet-stream. */
    val AllowedTypes: Set<String> = setOf(
        "application/pdf",
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp",
        "text/plain",
        "text/csv",
        "application/vnd.ms-excel",
        "application/msword",
    )

    private const val OpenXmlPrefix = "application/vnd.openxmlformats-officedocument."

    /** El tipo a servir para el [stored] guardado: el mismo si está permitido, si no octet-stream. */
    fun safeType(stored: String?): String {
        val type = stored?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val allowed = type in AllowedTypes || (type.startsWith(OpenXmlPrefix) && type.length > OpenXmlPrefix.length && type.none { it.isWhitespace() })
        return if (allowed) type else "application/octet-stream"
    }

    fun contentType(stored: String?): ContentType = runCatching { ContentType.parse(safeType(stored)) }.getOrDefault(ContentType.Application.OctetStream)

    /** Registra `POST /files` y `GET /files/{id}` dentro de la ruta del módulo [route]. */
    fun install(route: Route, access: ModuleAccessService) {
        route.post("/files") {
            val permissions = call.requireModuleAccess(access, MantenimientoModule.id) ?: return@post
            if (!permissions.has(MantenimientoModule.View)) {
                return@post call.respond(HttpStatusCode.Forbidden, MantenimientoErrorResponse("missing_permission", "No tienes permiso para esta acción."))
            }
            val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (length != null && length > MantenimientoRepository.MaxFileBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, MantenimientoErrorResponse("too_large", "El archivo supera el máximo de ${MantenimientoRepository.MaxFileBytes / (1024 * 1024)} MB."))
            }
            fun header(name: String) = call.request.headers[name]?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }.orEmpty()
            val name = header("X-File-Name")
            val path = header("X-File-Path")
            val contentType = call.request.contentType().withoutParameters().toString()
            val companyCode = access.companyCode(call)
                ?: return@post call.respond(HttpStatusCode.BadRequest, MantenimientoErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
            // El cuerpo va directo al almacenamiento de archivos: nunca se guarda entero en memoria.
            val result = withContext(Dispatchers.IO) {
                call.receiveChannel().toInputStream().use { body ->
                    access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                        try {
                            Result.success(
                                MantenimientoRepository.upload(tenant, access.files(companyCode, MantenimientoModule.id), name, path, contentType, body, permissions.userId),
                            )
                        } catch (stop: CancellationException) {
                            throw stop
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                    }
                }
            } ?: return@post call.respond(HttpStatusCode.Forbidden, MantenimientoErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
            result.fold(
                onSuccess = { ref: UiFileRef -> call.respondText(UiJson.encodeToString(UiFileRef.serializer(), ref), ContentType.Application.Json) },
                onFailure = { failure ->
                    if (failure is MantenimientoValidationException) {
                        call.respond(HttpStatusCode.BadRequest, MantenimientoErrorResponse("invalid_file", failure.message.orEmpty()))
                    } else {
                        log.warn("No se pudo guardar un archivo de mantenimiento ({}).", failure.javaClass.simpleName)
                        call.respond(HttpStatusCode.InternalServerError, MantenimientoErrorResponse("DATABASE_ERROR", "No se pudo guardar el archivo."))
                    }
                },
            )
        }

        route.get("/files/{id}") {
            val permissions = call.requireModuleAccess(access, MantenimientoModule.id) ?: return@get
            if (!permissions.has(MantenimientoModule.View)) {
                return@get call.respond(HttpStatusCode.Forbidden, MantenimientoErrorResponse("missing_permission", "No tienes permiso para esta acción."))
            }
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val companyCode = access.companyCode(call)
                ?: return@get call.respond(HttpStatusCode.BadRequest, MantenimientoErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
            val scope = MantenimientoScope.from(permissions)
            val file = withContext(Dispatchers.IO) {
                access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                    MantenimientoRepository.file(tenant, access.files(companyCode, MantenimientoModule.id), scope, id)
                }
            } ?: return@get call.respond(HttpStatusCode.NotFound, MantenimientoErrorResponse("not_found", "Ese archivo ya no está disponible."))
            val content = withContext(Dispatchers.IO) { file.open() }
                ?: return@get call.respond(HttpStatusCode.NotFound, MantenimientoErrorResponse("missing_content", "El archivo ya no está en el almacenamiento del servidor."))
            // Los encabezados solo salen cuando el contenido de verdad se envía.
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.attachment.name).toString(),
            )
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondOutputStream(contentType(file.attachment.contentType)) { content.use { it.copyTo(this) } }
        }
    }
}
