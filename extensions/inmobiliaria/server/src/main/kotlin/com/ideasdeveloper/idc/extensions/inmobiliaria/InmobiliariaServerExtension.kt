package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.idc.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.idc.modules.crm.CrmExtensionSection
import com.ideasdeveloper.idc.modules.crm.CrmExtensions
import com.ideasdeveloper.idc.modules.crm.CrmLead
import com.ideasdeveloper.idc.modules.crm.CrmScreenProvider
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.server.extensions.ExtensionContext
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
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

/**
 * Extension "inmobiliaria": inventario de propiedades, captaciones, transacciones con partes y comisiones, y herramientas MCP
 * para agentes. Aporta sus pantallas al CRM como [CrmScreenProvider]. Sus datos viven en las tablas ext_inmobiliaria__*.
 */
object InmobiliariaServerExtension : ServerExtension, CrmScreenProvider {
    const val ExtensionId = "inmobiliaria"
    const val PropertiesOption = "properties"
    const val DealsOption = "deals"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = ExtensionId,
        module = "crm",
        displayName = "Inmobiliaria (corretaje)",
        description = "Convierte el CRM en una herramienta de corretaje: inventario de propiedades con enlaces por portal e historial de precios, captaciones, transacciones con partes y comisiones, y herramientas para agentes de IA.",
        version = "1.0.0",
        minModuleVersion = "0.28.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/inmobiliaria",
        ),
        permissions = listOf(
            CrmExtensionPermissions.ItemsWrite,
            CrmExtensionPermissions.LeadsUpdate,
        ),
        // Los ajustes (moneda y comision por defecto) viven en ext_inmobiliaria__settings: ninguno es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__inmobiliaria.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(
                ExtensionUninstallOption(PropertiesOption, "Inventario de propiedades, enlaces e historial de precios", UninstallDefault.Keep),
                ExtensionUninstallOption(DealsOption, "Transacciones y sus partes", UninstallDefault.Keep),
            ),
        ),
    )

    override val migrationPaths = listOf("extensions/inmobiliaria/migrations/V001__inmobiliaria.sql")

    override val runtimeTables = listOf(
        InmobiliariaStore.SettingsTable,
        InmobiliariaStore.PropertiesTable,
        InmobiliariaStore.LinksTable,
        InmobiliariaStore.PriceChangesTable,
        InmobiliariaStore.StagesTable,
        InmobiliariaStore.DealsTable,
        InmobiliariaStore.PartiesTable,
    )

    /** Crea las etapas de la plantilla si la empresa aun no tiene ninguna. */
    override fun onEnable(context: ExtensionContext) {
        InmobiliariaStore.seedTemplate(context.tenant)
    }

    // ---- CrmScreenProvider: lo que el CRM invoca sin depender del codigo de la extension ----

    override val extensionId: String get() = ExtensionId

    override val sections = listOf(
        CrmExtensionSection("inmobiliaria_inventory", "Propiedades", CrmScreens.View),
        CrmExtensionSection("inmobiliaria_property", "Propiedad", CrmScreens.View, inMenu = false),
        CrmExtensionSection("inmobiliaria_intake", "Captaciones", CrmScreens.View),
        CrmExtensionSection("inmobiliaria_deals", "Cierres", CrmScreens.View),
        CrmExtensionSection("inmobiliaria_deal", "Transaccion", CrmScreens.View, inMenu = false),
        CrmExtensionSection("inmobiliaria_stages", "Etapas de cierres", CrmScreens.Settings, inMenu = false),
    )

    override val mcpTools: List<McpTool> get() = InmobiliariaMcpTools.tools

    override fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> =
        InmobiliariaScreens.screen(context, screen, params, submitted)

    override fun action(
        context: CrmScreens.Context,
        actor: CrmActor,
        screen: String,
        op: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): CrmExtensionActionResult = InmobiliariaScreens.action(context, actor, screen, op, params, values)

    override fun leadPanel(context: CrmScreens.Context, lead: CrmLead): List<UiNode> = InmobiliariaScreens.leadPanel(context, lead)

    // ---- Desinstalacion ----

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        fun count(table: String) = CrmExtensions.count(tenant, "SELECT count(*) FROM $table")
        return listOf(
            CrmExtensions.item(null, "Ajustes de la extension (moneda y comision por defecto, se borran)", count(InmobiliariaStore.SettingsTable), "delete"),
            CrmExtensions.item(null, "Etapas de las transacciones (se borran si no quedan transacciones)", count(InmobiliariaStore.StagesTable), "delete"),
            CrmExtensions.item(PropertiesOption, "Inventario de propiedades, enlaces e historial de precios (si se borran, tambien las transacciones y los intereses de leads con esas propiedades)", count(InmobiliariaStore.PropertiesTable), UninstallDefault.Keep),
            CrmExtensions.item(DealsOption, "Transacciones y sus partes", count(InmobiliariaStore.DealsTable), UninstallDefault.Keep),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        val deleteProperties = choices[PropertiesOption] == UninstallDefault.Delete
        // Las transacciones apuntan a las propiedades (restrict): borrar las propiedades obliga a borrar tambien las transacciones.
        val deleteDeals = deleteProperties || choices[DealsOption] == UninstallDefault.Delete
        // Orden por claves foraneas: partes -> transacciones -> etapas -> (enlaces e historial caen con la propiedad) -> propiedades.
        if (deleteDeals) {
            CrmExtensions.update(tenant, "DELETE FROM ${InmobiliariaStore.PartiesTable}")
            CrmExtensions.update(tenant, "DELETE FROM ${InmobiliariaStore.DealsTable}")
        }
        // Las etapas se borran siempre; si se conservan transacciones, sus etapas tambien (las referencian).
        CrmExtensions.update(
            tenant,
            "DELETE FROM ${InmobiliariaStore.StagesTable} WHERE NOT EXISTS (SELECT 1 FROM ${InmobiliariaStore.DealsTable} d WHERE d.stage_id = ${InmobiliariaStore.StagesTable}.id)",
        )
        if (deleteProperties) {
            CrmExtensions.update(tenant, "DELETE FROM ${InmobiliariaStore.PropertiesTable}")
            // Los intereses de leads con propiedades de la extension se van con las propiedades; los leads se conservan.
            CrmExtensions.update(tenant, "DELETE FROM crm_lead_items WHERE source = ?", InmobiliariaService.Source)
        }
        CrmExtensions.update(tenant, "DELETE FROM ${InmobiliariaStore.SettingsTable} WHERE id = 1")
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class InmobiliariaServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = InmobiliariaServerExtension
}
