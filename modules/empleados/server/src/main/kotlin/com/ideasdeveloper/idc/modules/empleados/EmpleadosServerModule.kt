package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.SettingsTable
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration

object EmpleadosServerModule : ServerModule {
    private val log = LoggerFactory.getLogger(EmpleadosServerModule::class.java)

    override val migrationPaths = listOf(
        "modules/empleados/migrations/V001__create_empleados.sql",
    )

    override val runtimeTables = listOf(
        "empleados_sites",
        "empleados_departments",
        "empleados_employees",
        "empleados_weekly",
        "empleados_shifts",
        "empleados_absences",
    )

    /**
     * Una empresa duplicada conserva las sedes (sin la clave de importacion) y los departamentos. Las fichas, horarios,
     * turnos y ausencias se quedan atras: son personas y datos de la empresa de origen.
     */
    override val duplicatedSettings = listOf(
        SettingsTable("empleados_sites", reset = mapOf("import_key" to null)),
        SettingsTable("empleados_departments"),
    )

    override val jobs = listOf(
        // Lee y aplica las importaciones de las extensiones (un tramo corto cada minuto).
        ModuleJob("import-runner", Duration.ofMinutes(1)) { context ->
            try {
                EmpleadosImportSources.runPending(
                    EmpleadosImportJobContext(context.access, context.tenant, context.companyCode, EmpleadosRepository.today()),
                    Duration.ofSeconds(40),
                )
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("Fallo el trabajo de importacion de Empleados ({}).", failure.javaClass.simpleName)
            }
        },
    )

    // Las extensiones de importacion del modulo (Monday...) ofrecen sus fuentes, pantallas y herramientas por aqui.
    override fun linkExtensions(extensions: List<ServerExtension>) {
        EmpleadosImportSources.link(extensions)
    }

    override val mcp: ModuleMcp
        get() = EmpleadosMcpTools.mcp

    override val definition = ModuleDefinition(
        id = EmpleadosModule.id,
        displayName = EmpleadosModule.displayName,
        description = "Fichas de empleados, sedes y departamentos, horario habitual con turnos concretos, vista semanal por sede, solicitudes de ausencia con aprobacion y avisos de cambios de turno.",
        version = "0.1.0",
        minCoreVersion = "0.8.0",
        minAppVersion = "0.7.0",
        permissions = listOf(
            ModulePermissionDefinition(
                EmpleadosModule.View,
                "Ver empleados",
                "Permite abrir el modulo, ver su propio horario, pedir y cancelar sus ausencias pendientes y ver el directorio basico de empleados activos (nombre, puesto, departamento y sede).",
            ),
            ModulePermissionDefinition(
                EmpleadosModule.Shifts,
                "Gestionar turnos",
                "Permite ver la semana de todos, cambiar el horario habitual y los turnos de cualquiera, aprobar, rechazar y registrar ausencias, y ver telefono, correo y disponibilidad de los empleados. Se recomienda darlo junto con Ver empleados.",
            ),
            ModulePermissionDefinition(
                EmpleadosModule.Manage,
                "Administrar fichas",
                "Permite crear, editar y dar de baja fichas, enlazarlas con usuarios, ver todos sus datos (notas, fecha de ingreso y bajas) y administrar sedes y departamentos. Incluye gestionar turnos.",
            ),
            ModulePermissionDefinition(
                EmpleadosModule.Import,
                "Importar datos",
                "Permite importar empleados, sedes y horarios desde otros sistemas (por ejemplo Monday) con las extensiones de importacion instaladas.",
            ),
        ),
        supportsExtensions = true,
        extensionPermissions = listOf("empleados.import.write"),
        ui = ModuleUiEntry(type = "native", entry = EmpleadosModule.UiRoute, home = "mine"),
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        route.route(EmpleadosModule.UiRoute) {
            // Pantallas: EmpleadosRoutes.screens(this, access)
            EmpleadosRoutes.screens(this, access)
        }
    }
}

@Serializable
data class EmpleadosErrorResponse(val code: String, val message: String)

/**
 * Abre la base de datos de la empresa autorizada, ejecuta [block] en el dispatcher de IO y responde con su resultado en
 * JSON. Una regla del modulo que falla responde 400 con su mensaje; un error de base de datos responde 500.
 */
suspend fun <T> ApplicationCall.respondEmpleados(access: ModuleAccessService, serializer: KSerializer<T>, block: (Connection) -> T) {
    val companyCode = access.companyCode(this)
        ?: return respond(HttpStatusCode.BadRequest, EmpleadosErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
    val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode) }
        ?: return respond(HttpStatusCode.Forbidden, EmpleadosErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
    try {
        val result = withContext(Dispatchers.IO) { connection.use { tenant -> block(tenant) } }
        respondText(UiJson.encodeToString(serializer, result), ContentType.Application.Json)
    } catch (failure: EmpleadosValidationException) {
        respond(HttpStatusCode.BadRequest, EmpleadosErrorResponse("invalid_request", failure.message.orEmpty()))
    } catch (failure: SQLException) {
        respond(HttpStatusCode.InternalServerError, EmpleadosErrorResponse("DATABASE_ERROR", "No se pudo completar la operacion."))
    }
}

class EmpleadosServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = EmpleadosServerModule
}
