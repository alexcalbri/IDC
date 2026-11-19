package com.ideasdeveloper.idc.extensions.tareas_monday

import com.ideasdeveloper.idc.modules.tareas.TareasImportContext
import com.ideasdeveloper.idc.modules.tareas.TareasImportJobContext
import com.ideasdeveloper.idc.modules.tareas.TareasImportReply
import com.ideasdeveloper.idc.modules.tareas.TareasImportSource
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
 * Extension "tareas_monday": importa un tablero de monday como un tablero de Tareas (grupos, tareas, subelementos como pasos,
 * actualizaciones como comentarios y archivos como adjuntos). No tiene rutas publicas: es una fuente de importacion del
 * modulo Tareas ([TareasImportSource]), que la muestra en su pantalla Importar, suma sus herramientas MCP y llama a
 * [runPending] desde su trabajo "import-runner" (las "jobs" de una extension no las programa el Core).
 */
object TareasMondayServerExtension : ServerExtension, TareasImportSource {
    const val HistoryOption = "import_history"
    const val ImportWritePermission = "tareas.import.write"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = TareasMondayId,
        module = "tareas",
        displayName = "Importacion de tareas desde monday",
        description = "Importa tableros de monday como tableros de Tareas: lee el tablero con el token de la empresa, mapea sus columnas y trae grupos, tareas, subelementos, comentarios y archivos.",
        version = "1.0.0",
        minModuleVersion = "0.2.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/tareas_monday",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            ImportWritePermission,
        ),
        // El token se guarda en ext_tareas_monday__settings y se edita en la pantalla Importar: ningun ajuste es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete)),
        ),
    )

    override val migrationPaths = listOf("extensions/tareas_monday/migrations/V001__settings.sql")

    override val runtimeTables = listOf(
        TareasMondaySettings.Table,
        TareasMondayJobs.JobsTable,
        TareasMondayJobs.RowsTable,
        TareasMondayJobs.PeopleTable,
    )

    // ---- TareasImportSource ----

    override val sourceId: String = TareasMondayId
    override val title: String = "Monday"

    override fun enabled(tenant: Connection, companyCode: String): Boolean = TareasMondaySettings.extensionEnabled(tenant)

    override fun screen(context: TareasImportContext, params: Map<String, String>): List<UiNode> =
        TareasMondayScreens.screen(context, params)

    override fun action(context: TareasImportContext, op: String, values: Map<String, String>): TareasImportReply =
        TareasMondayScreens.action(context, op, values)

    override val mcpTools: List<McpTool> get() = TareasMondayMcpTools.tools

    override fun runPending(context: TareasImportJobContext, budget: Duration): Int = TareasMondayRunner.run(context, budget)

    // ---- Desinstalacion ----

    private fun count(tenant: Connection, sql: String): Int = try {
        tenant.prepareStatement(sql).use { query -> query.executeQuery().use { if (it.next()) it.getInt(1) else 0 } }
    } catch (failure: SQLException) {
        0
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            UninstallItem(null, "Token de la API de monday (se borra)", count(tenant, "SELECT count(*) FROM ${TareasMondaySettings.Table} WHERE coalesce(token, '') <> ''"), "delete"),
            UninstallItem(HistoryOption, "Importaciones de monday (trabajos)", count(tenant, "SELECT count(*) FROM ${TareasMondayJobs.JobsTable}"), UninstallDefault.Delete),
            UninstallItem(HistoryOption, "Filas leidas de monday", count(tenant, "SELECT count(*) FROM ${TareasMondayJobs.RowsTable}"), UninstallDefault.Delete),
            UninstallItem(null, "Tareas importadas (se conservan)", count(tenant, "SELECT count(*) FROM tareas_tasks WHERE source = 'monday'"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token vive en la tabla de la extension: se borra su fila.
        tenant.prepareStatement("DELETE FROM ${TareasMondaySettings.Table} WHERE id = 1").use { it.executeUpdate() }
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas y personas de cada importacion se borran con ella (ON DELETE CASCADE). Nunca se tocan tableros, tareas, comentarios ni adjuntos.
            tenant.prepareStatement("DELETE FROM ${TareasMondayJobs.JobsTable}").use { it.executeUpdate() }
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class TareasMondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = TareasMondayServerExtension
}
