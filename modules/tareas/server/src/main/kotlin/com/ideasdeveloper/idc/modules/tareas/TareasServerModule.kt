package com.ideasdeveloper.idc.modules.tareas

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
import com.ideasdeveloper.idc.server.modules.SettingsTable
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

object TareasServerModule : ServerModule {
    private val log = LoggerFactory.getLogger(TareasServerModule::class.java)

    override val migrationPaths = listOf(
        "modules/tareas/migrations/V001__create_tareas.sql",
        "modules/tareas/migrations/V002__import.sql",
    )

    override val runtimeTables = listOf(
        "tareas_boards",
        "tareas_board_members",
        "tareas_groups",
        "tareas_tasks",
        "tareas_task_assignees",
        "tareas_checklist",
        "tareas_comments",
        "tareas_attachments",
        "tareas_activity",
        "tareas_reminders",
    )

    /**
     * A duplicated company keeps the boards (without who created them nor their import key) and their groups. Members, tasks and everything
     * that hangs from a task stay behind: users are not copied.
     */
    override val duplicatedSettings = listOf(
        SettingsTable("tareas_boards", reset = mapOf("created_by" to null, "import_key" to null)),
        SettingsTable("tareas_groups"),
    )

    override val jobs = listOf(
        // Tasks due tomorrow and overdue ones: one notice per responsible and due date.
        ModuleJob("due-reminders", Duration.ofMinutes(30)) { context ->
            TareasNotify.dueReminders(context.tenant, context.notifier, TareasRepository.today())
        },
        // Stored files of removed attachments and uploads whose form was never saved.
        ModuleJob("attachments-cleanup", Duration.ofHours(1)) { context ->
            TareasRepository.cleanup(context.tenant, context.access.files(context.companyCode, TareasModule.id))
        },
        // Reads and applies the imports of the extensions (a short slice every minute).
        ModuleJob("import-runner", Duration.ofMinutes(1)) { context ->
            try {
                TareasImportSources.runPending(
                    TareasImportJobContext(context.access, context.tenant, context.companyCode, context.access.files(context.companyCode, TareasModule.id), TareasRepository.today()),
                    Duration.ofSeconds(40),
                )
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("Fallo el trabajo de importacion de Tareas ({}).", failure.javaClass.simpleName)
            }
        },
    )

    // The import extensions of the module (Monday...) offer their sources, screens and agent tools through it.
    override fun linkExtensions(extensions: List<ServerExtension>) {
        TareasImportSources.link(extensions)
    }

    override val customerTimeline: CustomerTimeline
        get() = TareasCustomerTimeline.timeline

    override val mcp: ModuleMcp
        get() = TareasMcpTools.mcp

    override val definition = ModuleDefinition(
        id = TareasModule.id,
        displayName = TareasModule.displayName,
        description = "Tableros de tareas con grupos, varios responsables, revisor, lista de comprobacion, comentarios, adjuntos, avisos de vencimiento y vistas de lista, kanban, agenda y carga de trabajo.",
        version = "0.3.0",
        minCoreVersion = "0.8.0",
        minAppVersion = "0.7.0",
        permissions = listOf(
            ModulePermissionDefinition(
                TareasModule.View,
                "Ver tareas",
                "Permite abrir el modulo, ver los tableros visibles y Mis tareas. Quien es responsable o revisor de una tarea puede cambiar su estado, marcar su lista, comentar y adjuntar archivos en ella solo con este permiso.",
            ),
            ModulePermissionDefinition(
                TareasModule.Manage,
                "Gestionar tareas",
                "Permite crear, editar, asignar y archivar tareas en los tableros que ve.",
            ),
            ModulePermissionDefinition(
                TareasModule.Boards,
                "Administrar tableros",
                "Permite crear, editar y archivar tableros, grupos y miembros, y ver todos los tableros, tambien los privados.",
            ),
            ModulePermissionDefinition(
                TareasModule.Import,
                "Importar datos",
                "Permite importar tableros y tareas desde otros sistemas (por ejemplo Monday) con las extensiones de importacion instaladas.",
            ),
        ),
        supportsExtensions = true,
        extensionPermissions = listOf("tareas.import.write"),
        ui = ModuleUiEntry(type = "native", entry = TareasModule.UiRoute, home = "mine"),
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        route.route(TareasModule.UiRoute) {
            // Files of the task forms: an upload waits for the form to be saved (see TareasRepository.attach).
            post("/files") {
                val permissions = call.requireModuleAccess(access, TareasModule.id) ?: return@post
                if (!permissions.has(TareasModule.View)) {
                    return@post call.respond(HttpStatusCode.Forbidden, TareasErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && length > TareasRepository.MaxFileBytes) {
                    return@post call.respond(HttpStatusCode.PayloadTooLarge, TareasErrorResponse("too_large", "El archivo supera el maximo de ${TareasRepository.MaxFileBytes / (1024 * 1024)} MB."))
                }
                fun header(name: String) = call.request.headers[name]?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }.orEmpty()
                val name = header("X-File-Name")
                val path = header("X-File-Path")
                val contentType = call.request.contentType().withoutParameters().toString()
                val companyCode = access.companyCode(call)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, TareasErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
                // The body goes straight to the file storage; it is never held in memory whole.
                val body = call.receiveChannel().toInputStream()
                val result = withContext(Dispatchers.IO) {
                    access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                        runCatching {
                            TareasRepository.upload(tenant, access.files(companyCode, TareasModule.id), name, path, contentType, body, permissions.userId)
                        }
                    }
                } ?: return@post call.respond(HttpStatusCode.Forbidden, TareasErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                result.fold(
                    onSuccess = { ref -> call.respondText(UiJson.encodeToString(UiFileRef.serializer(), ref), ContentType.Application.Json) },
                    onFailure = { failure ->
                        if (failure is TareasValidationException) {
                            call.respond(HttpStatusCode.BadRequest, TareasErrorResponse("invalid_file", failure.message.orEmpty()))
                        } else {
                            log.warn("No se pudo guardar un archivo de tareas ({}).", failure.javaClass.simpleName)
                            call.respond(HttpStatusCode.InternalServerError, TareasErrorResponse("DATABASE_ERROR", "No se pudo guardar el archivo."))
                        }
                    },
                )
            }

            // A file of a task the user sees; a pending upload only reaches the user who uploaded it.
            get("/files/{id}") {
                val permissions = call.requireModuleAccess(access, TareasModule.id) ?: return@get
                if (!permissions.has(TareasModule.View)) {
                    return@get call.respond(HttpStatusCode.Forbidden, TareasErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val companyCode = access.companyCode(call)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, TareasErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
                val file = withContext(Dispatchers.IO) {
                    access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                        TareasRepository.file(tenant, access.files(companyCode, TareasModule.id), id)?.takeIf { stored ->
                            val taskId = stored.attachment.taskId
                                ?: return@takeIf permissions.userId != null && stored.attachment.uploadedBy == permissions.userId
                            val task = TareasRepository.get(tenant, taskId) ?: return@takeIf false
                            val scope = TareasService.scope(tenant, permissions)
                            scope.seesTask(task) && (task.archivedAt == null || scope.admin)
                        }
                    }
                } ?: return@get call.respond(HttpStatusCode.NotFound, TareasErrorResponse("not_found", "Ese archivo ya no esta disponible."))
                val type = runCatching { ContentType.parse(safeContentType(file.attachment.contentType)) }.getOrDefault(ContentType.Application.OctetStream)
                val content = withContext(Dispatchers.IO) { file.open() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, TareasErrorResponse("missing_content", "El archivo ya no esta en el almacenamiento del servidor."))
                // Headers only when the content is really going to be sent (an error answer must not carry them).
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.attachment.name).toString(),
                )
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respondOutputStream(type) { content.use { it.copyTo(this) } }
            }

            // Customers typed in the task form (UiField.suggest): needs tareas.manage and the right to see customers.
            get("/suggest/customers") {
                val permissions = call.requireModuleAccess(access, TareasModule.id) ?: return@get
                if (!permissions.has(TareasModule.Manage)) {
                    return@get call.respond(HttpStatusCode.Forbidden, TareasErrorResponse("missing_permission", "No tienes permiso para esta accion."))
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
                    return@get call.respond(HttpStatusCode.Forbidden, TareasErrorResponse("missing_permission", "No tienes permiso para ver clientes."))
                }
                val typed = call.request.queryParameters["q"].orEmpty().take(200)
                call.respondTareas(access, UiSuggestions.serializer()) { tenant ->
                    UiSuggestions(
                        TareasRepository.customerSuggestions(tenant, typed, 6).map { customer ->
                            UiSuggestion(customer.name, customer.email, mapOf("customer" to customer.name, "customer_id" to customer.id.toString()))
                        },
                    )
                }
            }

            // screens: TareasRoutes.screens(this, access)
            TareasRoutes.screens(this, access)
        }
    }
}

// What a browser could run if it rendered the file inside the app's origin is always served as a plain download.
private val PlainTextTypes = setOf("text/plain", "text/csv")

/** The content type to send: markup, scripts and every text type but plain text and CSV become application/octet-stream. */
fun safeContentType(stored: String): String {
    val base = stored.substringBefore(';').trim().lowercase()
    val risky = base.isEmpty() || (base.startsWith("text/") && base !in PlainTextTypes) || "html" in base || "xml" in base || "javascript" in base || "ecmascript" in base
    return if (risky) "application/octet-stream" else base
}

@Serializable
data class TareasErrorResponse(val code: String, val message: String)

/**
 * Opens the tenant database of the authorized company, runs [block] on the IO dispatcher and answers with its result as
 * JSON. A rule of the module that fails answers 400 with its message; a database error answers 500.
 */
suspend fun <T> ApplicationCall.respondTareas(access: ModuleAccessService, serializer: KSerializer<T>, block: (Connection) -> T) {
    val companyCode = access.companyCode(this)
        ?: return respond(HttpStatusCode.BadRequest, TareasErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
    val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode) }
        ?: return respond(HttpStatusCode.Forbidden, TareasErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
    try {
        val result = withContext(Dispatchers.IO) { connection.use { tenant -> block(tenant) } }
        respondText(UiJson.encodeToString(serializer, result), ContentType.Application.Json)
    } catch (failure: TareasValidationException) {
        respond(HttpStatusCode.BadRequest, TareasErrorResponse("invalid_request", failure.message.orEmpty()))
    } catch (failure: SQLException) {
        respond(HttpStatusCode.InternalServerError, TareasErrorResponse("DATABASE_ERROR", "No se pudo completar la operacion."))
    }
}

class TareasServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = TareasServerModule
}
