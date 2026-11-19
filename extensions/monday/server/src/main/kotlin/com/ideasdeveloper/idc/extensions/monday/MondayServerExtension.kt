package com.ideasdeveloper.idc.extensions.monday

import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.idc.modules.crm.CrmExtensions
import com.ideasdeveloper.idc.modules.crm.CrmFields
import com.ideasdeveloper.idc.modules.crm.CrmImportSource
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

/**
 * Extension "monday": lee tableros de monday con el token de la empresa y los importa como leads. Los campos del CRM
 * que crea la importacion quedan marcados (crm_field_definitions.extension_id) para decidir su suerte al desinstalarla.
 * No tiene rutas publicas: es una fuente de importacion del CRM ([CrmImportSource], que el CRM recorre al correr el
 * trabajo "import"), y cada paso comprueba que la extension siga activa.
 */
object MondayServerExtension : ServerExtension, CrmImportSource by MondayImport {
    const val CreatedFieldsOption = "created_fields"
    const val HistoryOption = "import_history"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = CrmExtensionIds.MondayImport,
        module = "crm",
        displayName = "Importacion desde monday",
        description = "Importa tableros de monday como leads: lee los tableros con el token de la empresa, mapea sus columnas y trae notas y archivos.",
        version = "1.0.0",
        minModuleVersion = "0.27.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/monday",
        ),
        // Los archivos de monday se bajan de enlaces que monday da en cada respuesta (host variable), no de un host fijo.
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            CrmExtensionPermissions.LeadsCreate,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.FieldsCreate,
            CrmExtensionPermissions.MessagesWrite,
        ),
        // El token se guarda en ext_monday__settings y se edita en la pantalla Importar del CRM: ningun ajuste es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(
                ExtensionUninstallOption(CreatedFieldsOption, "Campos del CRM creados por la importacion (con sus valores en los leads)", UninstallDefault.Ask),
                ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete),
            ),
        ),
    )

    override val migrationPaths = listOf("extensions/monday/migrations/V001__settings.sql")

    override val runtimeTables = listOf(MondaySettingsStore.Table)

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            CrmExtensions.item(null, "Token de la API de monday (se borra)", CrmExtensions.count(tenant, "SELECT count(*) FROM ${MondaySettingsStore.Table} WHERE coalesce(token, '') <> ''"), "delete"),
            CrmExtensions.item(
                CreatedFieldsOption, "Campos del CRM creados por la importacion",
                CrmExtensions.count(tenant, "SELECT count(*) FROM crm_field_definitions WHERE extension_id = ?", CrmExtensionIds.MondayImport), UninstallDefault.Ask,
            ),
            CrmExtensions.item(HistoryOption, "Historial de importaciones de monday", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_import_jobs WHERE source = 'monday'"), UninstallDefault.Delete),
            CrmExtensions.item(null, "Leads importados (se conservan)", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_leads WHERE import_key LIKE 'monday:%'"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token vive en la tabla de la extension: se borra su fila.
        CrmExtensions.update(tenant, "DELETE FROM ${MondaySettingsStore.Table} WHERE id = 1")
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas de cada importacion se borran con ella (ON DELETE CASCADE).
            CrmExtensions.update(tenant, "DELETE FROM crm_import_jobs WHERE source = 'monday'")
        }
        if (choices[CreatedFieldsOption] == UninstallDefault.Delete) {
            val ids = createdFieldIds(context)
            // Cada campo sale con sus valores en los leads; los leads no se tocan.
            CrmFields.all(tenant).filter { it.id.toString() in ids }.forEach { CrmFields.remove(tenant, it) }
        } else {
            // Se conservan: pasan a ser campos de la empresa.
            CrmExtensions.update(tenant, "UPDATE crm_field_definitions SET extension_id = NULL WHERE extension_id = ?", CrmExtensionIds.MondayImport)
        }
    }

    private fun createdFieldIds(context: ExtensionContext): Set<String> =
        context.tenant.prepareStatement("SELECT id::text FROM crm_field_definitions WHERE extension_id = ?").use { query ->
            query.setString(1, CrmExtensionIds.MondayImport)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class MondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = MondayServerExtension
}
