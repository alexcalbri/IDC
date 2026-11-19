package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.SettingsTable
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiSuggestion
import com.ideasdeveloper.idc.ui.UiSuggestions
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration

object EventosServerModule : ServerModule {
    private val log = LoggerFactory.getLogger(EventosServerModule::class.java)

    override val migrationPaths = listOf(
        "modules/eventos/migrations/V001__create_eventos.sql",
    )

    override val runtimeTables = listOf(
        "eventos_settings",
        "eventos_events",
        "eventos_registrations",
        "eventos_payments",
    )

    /** A duplicated company keeps the settings (currency, spaces, categories); events, registrations and payments stay behind. */
    override val duplicatedSettings = listOf(
        SettingsTable("eventos_settings"),
    )

    override val jobs = listOf(
        // Published or full events that ended more than 6 hours ago become "realizado" (batch of 500, idempotent).
        ModuleJob("cierre", Duration.ofHours(1)) { context ->
            try {
                EventosRepository.closeFinished(context.tenant, 500)
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("Fallo el cierre automatico de eventos ({}).", failure.javaClass.simpleName)
            }
        },
        // Reads and applies the imports of the extensions (a short slice every minute).
        ModuleJob("import-runner", Duration.ofMinutes(1)) { context ->
            try {
                EventosImportSources.runPending(
                    EventosImportJobContext(context.access, context.tenant, context.companyCode, EventosRepository.today()),
                    Duration.ofSeconds(40),
                )
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("Fallo el trabajo de importacion de Eventos ({}).", failure.javaClass.simpleName)
            }
        },
    )

    // The import extensions of the module (Monday...) offer their sources, screens and agent tools through it.
    override fun linkExtensions(extensions: List<ServerExtension>) {
        EventosImportSources.link(extensions)
    }

    override val mcp: ModuleMcp
        get() = EventosMcpTools.mcp

    override val customerTimeline: CustomerTimeline
        get() = EventosCustomerTimeline.timeline

    override val definition = ModuleDefinition(
        id = EventosModule.id,
        displayName = EventosModule.displayName,
        description = "Eventos publicos con cupo, lista de espera, inscripciones, asistencia y precio de miembro, y eventos privados con contratante, monto acordado y abonos.",
        version = "0.2.0",
        minCoreVersion = "0.8.0",
        minAppVersion = "0.7.0",
        permissions = listOf(
            ModulePermissionDefinition(
                EventosModule.View,
                "Ver eventos",
                "Permite abrir el modulo y ver los eventos proximos y pasados, su ficha, inscritos y cupo.",
            ),
            ModulePermissionDefinition(
                EventosModule.Register,
                "Inscribir y registrar asistencia",
                "Permite inscribir clientes, cancelar inscripciones, pasar personas de la lista de espera a inscritas, marcar pagos y registrar la asistencia.",
            ),
            ModulePermissionDefinition(
                EventosModule.Manage,
                "Gestionar eventos",
                "Permite crear, editar, duplicar, publicar y cancelar eventos, fijar precios, llevar eventos privados con sus abonos y cambiar los ajustes del modulo.",
            ),
            ModulePermissionDefinition(
                EventosModule.Import,
                "Importar datos",
                "Permite importar eventos desde otros sistemas (por ejemplo Monday) con las extensiones de importacion instaladas.",
            ),
        ),
        supportsExtensions = true,
        extensionPermissions = listOf("eventos.import.write"),
        ui = ModuleUiEntry(type = "native", entry = EventosModule.UiRoute, home = "proximos"),
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        route.route(EventosModule.UiRoute) {
            // Customers typed in the registration form (UiField.suggest): needs eventos.register or eventos.manage.
            // Only customers visible to the company are offered (same filter as Clientes).
            get("/suggest/customers") {
                val permissions = call.requireModuleAccess(access, EventosModule.id) ?: return@get
                if (!permissions.has(EventosModule.Register) && !permissions.has(EventosModule.Manage)) {
                    return@get call.respond(HttpStatusCode.Forbidden, EventosErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                val typed = call.request.queryParameters["q"].orEmpty().trim().take(100)
                call.respondEventos(access, UiSuggestions.serializer()) { tenant ->
                    UiSuggestions(
                        EventosRepository.searchCustomers(tenant, typed, 10).map { customer ->
                            UiSuggestion(customer.name, customer.email, mapOf("customer" to customer.name, "customer_id" to customer.id.toString()))
                        },
                    )
                }
            }

            // screens: EventosRoutes.install(this, access)
            EventosRoutes.install(this, access)
        }
    }
}

@Serializable
data class EventosErrorResponse(val code: String, val message: String)

/**
 * Opens the tenant database of the authorized company, runs [block] on the IO dispatcher and answers with its result as
 * JSON. A rule of the module that fails answers 400 with its message; a database error answers 500.
 */
suspend fun <T> ApplicationCall.respondEventos(access: ModuleAccessService, serializer: KSerializer<T>, block: (Connection) -> T) {
    val companyCode = access.companyCode(this)
        ?: return respond(HttpStatusCode.BadRequest, EventosErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
    val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode) }
        ?: return respond(HttpStatusCode.Forbidden, EventosErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
    try {
        val result = withContext(Dispatchers.IO) { connection.use { tenant -> block(tenant) } }
        respondText(UiJson.encodeToString(serializer, result), ContentType.Application.Json)
    } catch (failure: EventosValidationException) {
        respond(HttpStatusCode.BadRequest, EventosErrorResponse("invalid_request", failure.message.orEmpty()))
    } catch (failure: SQLException) {
        respond(HttpStatusCode.InternalServerError, EventosErrorResponse("DATABASE_ERROR", "No se pudo completar la operacion."))
    }
}

class EventosServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = EventosServerModule
}
