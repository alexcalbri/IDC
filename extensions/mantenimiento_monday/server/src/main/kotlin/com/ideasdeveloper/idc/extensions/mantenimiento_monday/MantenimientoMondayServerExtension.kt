package com.ideasdeveloper.idc.extensions.mantenimiento_monday

import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoImportContext
import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoImportJobContext
import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoImportReply
import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoImportSource
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
 * Extension "mantenimiento_monday": importa un tablero de monday como plantillas preventivas (planes) o como ordenes y
 * proyectos de Mantenimiento (subelementos como lista de comprobacion, actualizaciones como notas y archivos como
 * adjuntos). No tiene rutas publicas: es una fuente de importacion del modulo Mantenimiento
 * ([MantenimientoImportSource]), que la muestra en su pantalla Importar, suma sus herramientas MCP y llama a
 * [runPending] desde su trabajo "import-runner" (las "jobs" de una extension no las programa el Core).
 */
object MantenimientoMondayServerExtension : ServerExtension, MantenimientoImportSource {
    const val HistoryOption = "import_history"
    const val ImportWritePermission = "mantenimiento.import.write"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = MantenimientoMondayId,
        module = "mantenimiento",
        displayName = "Importacion de mantenimiento desde monday",
        description = "Importa tableros de monday al modulo Mantenimiento: como planes preventivos o como ordenes y proyectos. Lee el tablero con el token de la empresa, mapea sus columnas y trae subelementos, actualizaciones y archivos.",
        version = "1.0.0",
        minModuleVersion = "0.1.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/mantenimiento_monday",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            ImportWritePermission,
        ),
        // El token se guarda en ext_mantenimiento_monday__settings y se edita en la pantalla Importar: ningun ajuste es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete)),
        ),
    )

    override val migrationPaths = listOf("extensions/mantenimiento_monday/migrations/V001__settings.sql")

    override val runtimeTables = listOf(
        MantenimientoMondaySettings.Table,
        MantenimientoMondayJobs.JobsTable,
        MantenimientoMondayJobs.RowsTable,
        MantenimientoMondayJobs.PeopleTable,
    )

    // ---- MantenimientoImportSource ----

    override val sourceId: String = MantenimientoMondayId
    override val title: String = "Monday"

    override fun enabled(tenant: Connection, companyCode: String): Boolean = MantenimientoMondaySettings.extensionEnabled(tenant)

    override fun screen(context: MantenimientoImportContext, params: Map<String, String>): List<UiNode> =
        MantenimientoMondayScreens.screen(context, params)

    override fun action(context: MantenimientoImportContext, op: String, values: Map<String, String>): MantenimientoImportReply =
        MantenimientoMondayScreens.action(context, op, values)

    override val mcpTools: List<McpTool> get() = MantenimientoMondayMcpTools.tools

    override fun runPending(context: MantenimientoImportJobContext, budget: Duration): Int = MantenimientoMondayRunner.run(context, budget)

    // ---- Desinstalacion ----

    private fun count(tenant: Connection, sql: String): Int = try {
        tenant.prepareStatement(sql).use { query -> query.executeQuery().use { if (it.next()) it.getInt(1) else 0 } }
    } catch (failure: SQLException) {
        0
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            UninstallItem(null, "Token de la API de monday (se borra)", count(tenant, "SELECT count(*) FROM ${MantenimientoMondaySettings.Table} WHERE coalesce(token, '') <> ''"), "delete"),
            UninstallItem(HistoryOption, "Importaciones de monday (trabajos)", count(tenant, "SELECT count(*) FROM ${MantenimientoMondayJobs.JobsTable}"), UninstallDefault.Delete),
            UninstallItem(HistoryOption, "Filas leidas de monday", count(tenant, "SELECT count(*) FROM ${MantenimientoMondayJobs.RowsTable}"), UninstallDefault.Delete),
            UninstallItem(null, "Planes importados (se conservan)", count(tenant, "SELECT count(*) FROM mantenimiento_plans WHERE import_key LIKE 'monday:%'"), "keep"),
            UninstallItem(null, "Ordenes importadas (se conservan)", count(tenant, "SELECT count(*) FROM mantenimiento_orders WHERE import_key LIKE 'monday:%'"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token vive en la tabla de la extension: se borra su fila.
        tenant.prepareStatement("DELETE FROM ${MantenimientoMondaySettings.Table} WHERE id = 1").use { it.executeUpdate() }
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas y personas de cada importacion se borran con ella (ON DELETE CASCADE). Nunca se tocan planes, ordenes, notas ni adjuntos.
            tenant.prepareStatement("DELETE FROM ${MantenimientoMondayJobs.JobsTable}").use { it.executeUpdate() }
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class MantenimientoMondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = MantenimientoMondayServerExtension
}
