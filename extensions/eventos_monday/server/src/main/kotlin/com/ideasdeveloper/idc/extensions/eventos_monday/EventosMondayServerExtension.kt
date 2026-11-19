package com.ideasdeveloper.idc.extensions.eventos_monday

import com.ideasdeveloper.idc.modules.eventos.EventosImportContext
import com.ideasdeveloper.idc.modules.eventos.EventosImportJobContext
import com.ideasdeveloper.idc.modules.eventos.EventosImportReply
import com.ideasdeveloper.idc.modules.eventos.EventosImportSource
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
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration

/**
 * Extension "eventos_monday": importa un tablero de monday (por ejemplo "La Jolla Eventos") como eventos privados, cada
 * elemento con su cliente. No tiene rutas publicas: es una fuente de importacion del modulo Eventos
 * ([EventosImportSource]), que la muestra en su pantalla Importar, suma sus herramientas MCP y llama a [runPending]
 * desde su trabajo "import-runner" (las "jobs" de una extension no las programa el Core).
 */
object EventosMondayServerExtension : ServerExtension, EventosImportSource {
    const val HistoryOption = "import_history"
    const val ImportWritePermission = "eventos.import.write"
    private val log = LoggerFactory.getLogger(EventosMondayServerExtension::class.java)

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = EventosMondayId,
        module = "eventos",
        displayName = "Importacion de eventos desde monday",
        description = "Importa un tablero de monday (por ejemplo La Jolla Eventos) como eventos privados: lee el tablero con el token de la empresa, mapea sus columnas y crea el evento y su cliente por cada elemento.",
        version = "1.0.0",
        minModuleVersion = "0.1.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/eventos_monday",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            ImportWritePermission,
        ),
        // El token se guarda en ext_eventos_monday__settings y se edita en la pantalla Importar: ningun ajuste es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete)),
        ),
    )

    override val migrationPaths = listOf("extensions/eventos_monday/migrations/V001__settings.sql")

    override val runtimeTables = listOf(
        EventosMondaySettings.Table,
        EventosMondayJobs.JobsTable,
        EventosMondayJobs.RowsTable,
        EventosMondayJobs.PeopleTable,
    )

    // ---- EventosImportSource ----

    override val sourceId: String = EventosMondayId
    override val title: String = "Monday"

    override fun enabled(tenant: Connection, companyCode: String): Boolean = EventosMondaySettings.extensionEnabled(tenant)

    override fun screen(context: EventosImportContext, params: Map<String, String>): List<UiNode> =
        EventosMondayScreens.screen(context, params)

    override fun action(context: EventosImportContext, op: String, values: Map<String, String>): EventosImportReply =
        EventosMondayScreens.action(context, op, values)

    override val mcpTools: List<McpTool> get() = EventosMondayMcpTools.tools

    override fun runPending(context: EventosImportJobContext, budget: Duration): Int = EventosMondayRunner.run(context, budget)

    // ---- Desinstalacion ----

    private fun count(tenant: Connection, sql: String): Int = try {
        tenant.prepareStatement(sql).use { query -> query.executeQuery().use { if (it.next()) it.getInt(1) else 0 } }
    } catch (failure: SQLException) {
        log.warn("No se pudo contar para la desinstalacion de eventos_monday: {}", failure.message)
        0
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            UninstallItem(null, "Token de la API de monday (se borra)", count(tenant, "SELECT count(*) FROM ${EventosMondaySettings.Table} WHERE coalesce(token, '') <> ''"), "delete"),
            UninstallItem(HistoryOption, "Importaciones de monday (trabajos)", count(tenant, "SELECT count(*) FROM ${EventosMondayJobs.JobsTable}"), UninstallDefault.Delete),
            UninstallItem(HistoryOption, "Filas leidas de monday", count(tenant, "SELECT count(*) FROM ${EventosMondayJobs.RowsTable}"), UninstallDefault.Delete),
            UninstallItem(null, "Eventos importados (se conservan, con sus clientes y abonos)", count(tenant, "SELECT count(*) FROM eventos_events WHERE import_key LIKE 'monday:%'"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token vive en la tabla de la extension: se borra su fila.
        tenant.prepareStatement("DELETE FROM ${EventosMondaySettings.Table} WHERE id = 1").use { it.executeUpdate() }
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas y personas de cada importacion se borran con ella (ON DELETE CASCADE). Nunca se tocan eventos, clientes ni abonos.
            tenant.prepareStatement("DELETE FROM ${EventosMondayJobs.JobsTable}").use { it.executeUpdate() }
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class EventosMondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = EventosMondayServerExtension
}
