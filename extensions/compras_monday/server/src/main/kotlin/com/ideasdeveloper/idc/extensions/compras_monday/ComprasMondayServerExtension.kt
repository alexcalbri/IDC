package com.ideasdeveloper.idc.extensions.compras_monday

import com.ideasdeveloper.idc.modules.compras.ComprasExtensionPermissions
import com.ideasdeveloper.idc.modules.compras.ComprasImportSource
import com.ideasdeveloper.idc.server.extensions.ExtensionAccess
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
import io.ktor.server.routing.Route
import java.sql.Connection

/**
 * Extension "compras_monday": importa tableros de monday como solicitudes de compra. No tiene rutas publicas: es una fuente de
 * importacion de Compras ([ComprasImportSource]) que el modulo recorre en su pantalla Importar, en su trabajo "imports" y en sus
 * herramientas MCP. Las solicitudes importadas son de Compras y nunca se borran al desinstalarla.
 */
object ComprasMondayServerExtension : ServerExtension, ComprasImportSource by ComprasMondayImport {
    const val HistoryOption = "import_history"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = ComprasMondayImport.ExtensionId,
        module = "compras",
        displayName = "Importacion desde monday",
        description = "Importa tableros de monday como solicitudes de compra: eliges los grupos de cada tablero, mapeas sus columnas y traen cotizaciones y archivos.",
        version = "1.0.0",
        minModuleVersion = "0.2.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/compras_monday",
        ),
        // Los archivos de monday se bajan de enlaces que monday da en cada respuesta (host variable), no de un host fijo.
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            ComprasExtensionPermissions.RequestsImport,
        ),
        // El token se guarda en ext_compras_monday__settings y se edita en la pantalla Importar de Compras: ningun ajuste es obligatorio.
        settings = emptyList(),
        migrations = listOf("migrations/V001__staging.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(
                ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday (token, lecturas y filas preparadas)", UninstallDefault.Delete),
            ),
        ),
    )

    override val migrationPaths = listOf("extensions/compras_monday/migrations/V001__staging.sql")

    override val runtimeTables = listOf(ComprasMondayStore.SettingsTable, ComprasMondayStore.JobsTable, ComprasMondayStore.RowsTable)

    // Sin rutas publicas: solo se toma del Core el acceso a los modulos, para reconstruir los permisos vivos de quien lanza una importacion.
    override fun routes(route: Route, access: ExtensionAccess) {
        ComprasMondayImport.bind(access.modules)
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            UninstallItem(null, "Token de la API de monday (se borra)", count(tenant, "SELECT count(*) FROM ${ComprasMondayStore.SettingsTable} WHERE coalesce(token, '') <> ''"), "delete"),
            UninstallItem(HistoryOption, "Historial de importaciones de monday", count(tenant, "SELECT count(*) FROM ${ComprasMondayStore.JobsTable}"), UninstallDefault.Delete),
            UninstallItem(null, "Solicitudes importadas (se conservan)", count(tenant, "SELECT count(*) FROM compras_requests WHERE import_key LIKE 'monday:%'"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token vive en la tabla de la extension: se borra su fila.
        update(tenant, "DELETE FROM ${ComprasMondayStore.SettingsTable} WHERE id = 1")
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas preparadas de cada importacion se borran con ella (ON DELETE CASCADE). Las solicitudes importadas no se tocan.
            update(tenant, "DELETE FROM ${ComprasMondayStore.JobsTable}")
        }
    }

    private fun count(tenant: Connection, sql: String): Int =
        tenant.prepareStatement(sql).use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 } }

    private fun update(tenant: Connection, sql: String) {
        tenant.prepareStatement(sql).use { it.executeUpdate() }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class ComprasMondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = ComprasMondayServerExtension
}
