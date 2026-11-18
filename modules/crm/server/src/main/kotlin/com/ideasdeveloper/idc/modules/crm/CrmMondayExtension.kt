package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ExtensionContext
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallOption
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.extensions.UninstallDefault
import com.ideasdeveloper.idc.server.extensions.UninstallItem

/**
 * Extension "monday_import": lee tableros de monday con el token de la empresa y los importa como leads. Los campos del CRM
 * que crea la importacion quedan marcados (crm_field_definitions.extension_id) para decidir su suerte al desinstalarla.
 * No tiene rutas publicas: la lectura y la importacion las hace el trabajo "import", que comprueba que siga activa.
 */
object CrmMondayExtension : ServerExtension {
    const val CreatedFieldsOption = "created_fields"
    const val HistoryOption = "import_history"

    override val manifest = CrmExtensions.manifest(
        id = CrmExtensionIds.MondayImport,
        description = "Importa tableros de monday como leads: lee los tableros con el token de la empresa, mapea sus columnas y trae notas y archivos.",
        // Los archivos de monday se bajan de enlaces que monday da en cada respuesta (host variable), no de un host fijo.
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            CrmExtensionPermissions.LeadsCreate,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.FieldsCreate,
            CrmExtensionPermissions.MessagesWrite,
        ),
        options = listOf(
            ExtensionUninstallOption(CreatedFieldsOption, "Campos del CRM creados por la importacion (con sus valores en los leads)", UninstallDefault.Ask),
            ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete),
        ),
    )

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            CrmExtensions.item(null, "Token de la API de monday (se borra)", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_settings WHERE coalesce(monday_token, '') <> ''"), "delete"),
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
        CrmExtensions.update(tenant, "UPDATE crm_settings SET monday_token = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = 1")
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
