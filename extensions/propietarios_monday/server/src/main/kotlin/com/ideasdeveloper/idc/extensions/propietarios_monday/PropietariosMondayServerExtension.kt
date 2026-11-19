package com.ideasdeveloper.idc.extensions.propietarios_monday

import com.ideasdeveloper.idc.modules.propietarios.PropietariosImportContext
import com.ideasdeveloper.idc.modules.propietarios.PropietariosImportJobContext
import com.ideasdeveloper.idc.modules.propietarios.PropietariosImportReply
import com.ideasdeveloper.idc.modules.propietarios.PropietariosImportSource
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
 * Extension "propietarios_monday": importa un tablero de monday como lotes y titulares del modulo Propietarios (desarrollo,
 * lotes, propietarios por correo y archivos como adjuntos). No tiene rutas publicas: es una fuente de importacion del
 * modulo ([PropietariosImportSource]), que la muestra en su pantalla Importar, suma sus herramientas MCP y llama a
 * [runPending] desde su trabajo de importacion (las "jobs" de una extension no las programa el Core).
 */
object PropietariosMondayServerExtension : ServerExtension, PropietariosImportSource {
    const val HistoryOption = "import_history"
    const val ImportWritePermission = "propietarios.import.write"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = PropietariosMondayId,
        module = "propietarios",
        displayName = "Importacion de propietarios desde monday",
        description = "Importa un tablero de monday (lotes y sus propietarios) al modulo Propietarios: lee el tablero con el token de la empresa, mapea sus columnas y trae desarrollos, lotes, titulares y archivos.",
        version = "1.0.0",
        minModuleVersion = "0.1.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/propietarios_monday",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            ImportWritePermission,
        ),
        // El token se guarda en ext_propietarios_monday__settings y se edita en la pantalla Importar: ningun ajuste es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete)),
        ),
    )

    override val migrationPaths = listOf("extensions/propietarios_monday/migrations/V001__settings.sql")

    override val runtimeTables = listOf(
        PropietariosMondaySettings.Table,
        PropietariosMondayJobs.JobsTable,
        PropietariosMondayJobs.RowsTable,
    )

    // ---- PropietariosImportSource ----

    override val sourceId: String = PropietariosMondayId
    override val title: String = "Monday"

    override fun enabled(tenant: Connection, companyCode: String): Boolean = PropietariosMondaySettings.extensionEnabled(tenant)

    override fun screen(context: PropietariosImportContext, params: Map<String, String>): List<UiNode> =
        PropietariosMondayScreens.screen(context, params)

    override fun action(context: PropietariosImportContext, op: String, values: Map<String, String>): PropietariosImportReply =
        PropietariosMondayScreens.action(context, op, values)

    override val mcpTools: List<McpTool> get() = PropietariosMondayMcpTools.tools

    override fun runPending(context: PropietariosImportJobContext, budget: Duration): Int = PropietariosMondayRunner.run(context, budget)

    // ---- Desinstalacion ----

    private fun count(tenant: Connection, sql: String): Int = try {
        tenant.prepareStatement(sql).use { query -> query.executeQuery().use { if (it.next()) it.getInt(1) else 0 } }
    } catch (failure: SQLException) {
        0
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            UninstallItem(null, "Token de la API de monday (se borra)", count(tenant, "SELECT count(*) FROM ${PropietariosMondaySettings.Table} WHERE coalesce(token, '') <> ''"), "delete"),
            UninstallItem(HistoryOption, "Importaciones de monday (trabajos)", count(tenant, "SELECT count(*) FROM ${PropietariosMondayJobs.JobsTable}"), UninstallDefault.Delete),
            UninstallItem(HistoryOption, "Filas leidas de monday", count(tenant, "SELECT count(*) FROM ${PropietariosMondayJobs.RowsTable}"), UninstallDefault.Delete),
            UninstallItem(null, "Lotes importados (se conservan)", count(tenant, "SELECT count(*) FROM propietarios_lots WHERE import_key LIKE 'monday:%'"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token vive en la tabla de la extension: se borra su fila.
        tenant.prepareStatement("DELETE FROM ${PropietariosMondaySettings.Table} WHERE id = 1").use { it.executeUpdate() }
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas de cada importacion se borran con ella (ON DELETE CASCADE). Nunca se tocan desarrollos, lotes, titulares, clientes ni adjuntos.
            tenant.prepareStatement("DELETE FROM ${PropietariosMondayJobs.JobsTable}").use { it.executeUpdate() }
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class PropietariosMondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = PropietariosMondayServerExtension
}
