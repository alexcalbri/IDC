package com.ideasdeveloper.idc.extensions.inmobiliaria_monday

import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.idc.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.idc.modules.crm.CrmExtensionSection
import com.ideasdeveloper.idc.modules.crm.CrmExtensions
import com.ideasdeveloper.idc.modules.crm.CrmImportSource
import com.ideasdeveloper.idc.modules.crm.CrmScreenProvider
import com.ideasdeveloper.idc.modules.crm.CrmScreens
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
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.sql.Connection
import java.time.Duration
import java.time.LocalDate

/**
 * Extension "inmobiliaria_monday": migra desde monday los tableros de listados y de deals a la extension Inmobiliaria y se
 * desinstala al terminar. Aporta una pantalla oculta al CRM ([CrmScreenProvider]), una tarjeta en Importar ([CrmImportSource],
 * solo para abrir esa pantalla: sus jobs no pasan por el pipeline generico) y un trabajo en segundo plano que lee y aplica.
 */
object InmobiliariaMondayServerExtension : ServerExtension, CrmScreenProvider, CrmImportSource {
    const val ExtensionId = "inmobiliaria_monday"

    /** La extension destino de los datos importados. */
    const val TargetId = "inmobiliaria"
    const val HistoryOption = "import_history"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = ExtensionId,
        module = "crm",
        displayName = "Importacion de monday para Inmobiliaria",
        description = "Migra desde monday los tableros de listados y de deals hacia la extension Inmobiliaria: lee el tablero con el token de la empresa, mapea sus columnas y aplica la importacion. Se desinstala al terminar.",
        version = "1.0.0",
        minModuleVersion = "0.28.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/inmobiliaria_monday",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            CrmExtensionPermissions.ItemsWrite,
        ),
        // El token se guarda en ext_inmobiliaria_monday__settings y se edita en la pantalla de la extension.
        settings = emptyList(),
        migrations = listOf("migrations/V001__inmobiliaria_monday.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete)),
        ),
    )

    override val migrationPaths = listOf("extensions/inmobiliaria_monday/migrations/V001__inmobiliaria_monday.sql")

    override val runtimeTables = listOf(InmobiliariaMondayStore.SettingsTable, InmobiliariaMondayStore.JobsTable, InmobiliariaMondayStore.RowsTable)

    // Cada corrida se acota a 50 s (el trabajo corre una vez por minuto en un hilo compartido con los demas).
    override val jobs = listOf(
        ModuleJob("import", Duration.ofMinutes(1)) { context -> InmobiliariaMondayRunner.run(context.tenant, Duration.ofSeconds(50)) },
    )

    // ---- CrmScreenProvider ----

    override val extensionId: String get() = ExtensionId

    override val sections = listOf(
        CrmExtensionSection(InmobiliariaMondayScreens.Import, "Importar desde monday", CrmScreens.Settings, inMenu = false),
    )

    override val mcpTools: List<McpTool> get() = InmobiliariaMondayMcpTools.tools

    override fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> =
        if (screen == InmobiliariaMondayScreens.Import) InmobiliariaMondayScreens.screen(context, params)
        else listOf(UiText("Esa pantalla no existe.", tone = UiTone.Warning))

    override fun action(
        context: CrmScreens.Context,
        actor: CrmActor,
        screen: String,
        op: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): CrmExtensionActionResult =
        if (screen == InmobiliariaMondayScreens.Import) InmobiliariaMondayScreens.action(context, actor, op, params, values)
        else CrmExtensionActionResult(screen, UiNotice("Esa accion no existe.", UiTone.Warning), params - "op")

    // ---- CrmImportSource: solo la tarjeta de Importar; esta extension no usa el pipeline generico de jobs del CRM ----

    override val id: String get() = ExtensionId
    override val label: String get() = "monday (Inmobiliaria)"
    override val readingLabel: String get() = "Leyendo monday"

    override fun importCards(context: CrmScreens.Context): List<UiNode> {
        val unavailable = InmobiliariaMondayService.unavailableReason(context.tenant)
        return listOf(
            UiCard(
                listOf(
                    UiText("Migra los tableros de listados y de deals de monday a Inmobiliaria.", UiTextStyle.Caption),
                    if (unavailable != null) UiText(unavailable, tone = UiTone.Warning)
                    else UiButton("Importar desde monday", UiNavigate(InmobiliariaMondayScreens.Import), style = "secondary"),
                ),
                title = "monday - Inmobiliaria",
            ),
        )
    }

    // Lo propio de esta extension corre en su ModuleJob; el del CRM no tiene nada que leer ni aplicar aqui.
    override fun readQueued(tenant: Connection) = Unit

    override fun runPending(tenant: Connection, limit: Int, today: LocalDate, files: com.ideasdeveloper.idc.server.modules.ModuleFiles?, budget: Duration): Int = 0

    // ---- Desinstalacion ----

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            CrmExtensions.item(null, "Token de la API de monday (se borra)", CrmExtensions.count(tenant, "SELECT count(*) FROM ${InmobiliariaMondayStore.SettingsTable} WHERE coalesce(token, '') <> ''"), "delete"),
            CrmExtensions.item(HistoryOption, "Historial de importaciones de monday y sus filas leidas (si lo conservas: contiene datos de clientes, como nombres, correos y telefonos de propietarios y compradores)", CrmExtensions.count(tenant, "SELECT count(*) FROM ${InmobiliariaMondayStore.JobsTable}"), UninstallDefault.Delete),
            CrmExtensions.item(null, "Propiedades y transacciones importadas (se conservan en Inmobiliaria)", CrmExtensions.count(tenant, "SELECT count(*) FROM ${InmobiliariaMondayStore.RowsTable} WHERE state IN ('applied', 'failed')"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token se borra siempre.
        CrmExtensions.update(tenant, "DELETE FROM ${InmobiliariaMondayStore.SettingsTable} WHERE id = 1")
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas leidas se borran con su importacion (ON DELETE CASCADE).
            CrmExtensions.update(tenant, "DELETE FROM ${InmobiliariaMondayStore.JobsTable}")
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class InmobiliariaMondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = InmobiliariaMondayServerExtension
}
