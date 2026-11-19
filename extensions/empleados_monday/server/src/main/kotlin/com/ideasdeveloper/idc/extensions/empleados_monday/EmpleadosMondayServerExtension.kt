package com.ideasdeveloper.idc.extensions.empleados_monday

import com.ideasdeveloper.idc.modules.empleados.EmpleadosImportContext
import com.ideasdeveloper.idc.modules.empleados.EmpleadosImportJobContext
import com.ideasdeveloper.idc.modules.empleados.EmpleadosImportReply
import com.ideasdeveloper.idc.modules.empleados.EmpleadosImportSource
import com.ideasdeveloper.idc.server.extensions.ExtensionContext
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionSource
import com.ideasdeveloper.idc.server.extensions.ExtensionSourceType
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallDefinition
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallOption
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.extensions.ServerExtensionProvider
import com.ideasdeveloper.idc.server.extensions.UninstallDefault
import com.ideasdeveloper.idc.server.extensions.UninstallItem
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.ui.UiNode
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration

/**
 * Extension "empleados_monday": importa un tablero de monday (por ejemplo "Horario del Equipo de Mantenimiento") como
 * empleados, sedes y horarios habituales del modulo Empleados. No tiene rutas publicas: es una fuente de importacion del
 * modulo ([EmpleadosImportSource]), que la muestra en su pantalla Importar, suma sus herramientas MCP y llama a
 * [runPending] desde su trabajo "import-runner" (las "jobs" de una extension no las programa el Core).
 */
object EmpleadosMondayServerExtension : ServerExtension, EmpleadosImportSource {
    const val HistoryOption = "import_history"
    const val ImportWritePermission = "empleados.import.write"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = EmpleadosMondayId,
        module = "empleados",
        displayName = "Importacion de empleados desde monday",
        description = "Importa tableros de monday (por ejemplo un horario del equipo) como empleados, sedes y horarios habituales del modulo Empleados: lee el tablero con el token de la empresa y mapea sus columnas.",
        version = "1.0.0",
        minModuleVersion = "0.1.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/empleados_monday",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            ImportWritePermission,
        ),
        // El token se guarda en ext_empleados_monday__settings y se edita en la pantalla Importar: ningun ajuste es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete)),
        ),
    )

    override val migrationPaths = listOf("extensions/empleados_monday/migrations/V001__settings.sql")

    override val runtimeTables = listOf(
        EmpleadosMondaySettings.Table,
        EmpleadosMondayJobs.JobsTable,
        EmpleadosMondayJobs.RowsTable,
    )

    // ---- EmpleadosImportSource ----

    override val sourceId: String = EmpleadosMondayId
    override val title: String = "Monday"

    override fun enabled(tenant: Connection, companyCode: String): Boolean = EmpleadosMondaySettings.extensionEnabled(tenant)

    override fun screen(context: EmpleadosImportContext, params: Map<String, String>): List<UiNode> =
        EmpleadosMondayScreens.screen(context, params)

    override fun action(context: EmpleadosImportContext, op: String, values: Map<String, String>): EmpleadosImportReply =
        EmpleadosMondayScreens.action(context, op, values)

    override val mcpTools: List<McpTool> get() = EmpleadosMondayMcpTools.tools

    override fun runPending(context: EmpleadosImportJobContext, budget: Duration): Int = EmpleadosMondayRunner.run(context, budget)

    // ---- Desinstalacion ----

    private fun count(tenant: Connection, sql: String): Int = try {
        tenant.prepareStatement(sql).use { query -> query.executeQuery().use { if (it.next()) it.getInt(1) else 0 } }
    } catch (failure: SQLException) {
        0
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            UninstallItem(null, "Token de la API de monday (se borra)", count(tenant, "SELECT count(*) FROM ${EmpleadosMondaySettings.Table} WHERE coalesce(token, '') <> ''"), "delete"),
            UninstallItem(HistoryOption, "Importaciones de monday (trabajos)", count(tenant, "SELECT count(*) FROM ${EmpleadosMondayJobs.JobsTable}"), UninstallDefault.Delete),
            UninstallItem(HistoryOption, "Filas leidas de monday", count(tenant, "SELECT count(*) FROM ${EmpleadosMondayJobs.RowsTable}"), UninstallDefault.Delete),
            UninstallItem(null, "Empleados importados (se conservan)", count(tenant, "SELECT count(*) FROM empleados_employees WHERE import_key LIKE 'monday:%'"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token vive en la tabla de la extension: se borra su fila.
        tenant.prepareStatement("DELETE FROM ${EmpleadosMondaySettings.Table} WHERE id = 1").use { it.executeUpdate() }
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas de cada importacion se borran con ella (ON DELETE CASCADE). Nunca se tocan empleados, sedes ni horarios.
            tenant.prepareStatement("DELETE FROM ${EmpleadosMondayJobs.JobsTable}").use { it.executeUpdate() }
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class EmpleadosMondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = EmpleadosMondayServerExtension
}
