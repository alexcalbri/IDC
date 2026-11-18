package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ExtensionContext
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallOption
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.extensions.UninstallDefault
import com.ideasdeveloper.idc.server.extensions.UninstallItem

/** Extension "encuentra24": importa cada 5 minutos los contactos de los anuncios de la empresa como leads. No tiene rutas publicas. */
object CrmEncuentra24Extension : ServerExtension {
    const val AdsOption = "ads"

    override val manifest = CrmExtensions.manifest(
        id = CrmExtensionIds.Encuentra24,
        description = "Importa como leads los mensajes, chats y llamadas de los anuncios de Encuentra24 y deja el anuncio en los intereses del lead.",
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "www.encuentra24.com",
            ExtensionPermissions.SecretsWrite,
            CrmExtensionPermissions.LeadsCreate,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.MessagesWrite,
            CrmExtensionPermissions.ItemsWrite,
        ),
        options = listOf(
            ExtensionUninstallOption(AdsOption, "Anuncios de Encuentra24 vinculados a los leads", UninstallDefault.Keep),
        ),
    )

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            CrmExtensions.item(null, "Conexion con Encuentra24 y su API key (se borran)", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_encuentra24_settings WHERE enabled OR coalesce(api_key, '') <> ''"), "delete"),
            CrmExtensions.item(AdsOption, "Anuncios de Encuentra24 vinculados a los leads", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_lead_items WHERE source = ?", CrmEncuentra24.Source), UninstallDefault.Keep),
            CrmExtensions.item(null, "Leads y mensajes importados (se conservan)", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_messages WHERE channel = ?", CrmEncuentra24.Source), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        CrmExtensions.update(tenant, "UPDATE crm_encuentra24_settings SET enabled = FALSE, api_key = NULL, slug = NULL, last_sync = NULL, last_result = '', last_error = '', updated_at = CURRENT_TIMESTAMP WHERE id = 1")
        if (choices[AdsOption] == UninstallDefault.Delete) {
            CrmExtensions.update(tenant, "DELETE FROM crm_lead_items WHERE source = ?", CrmEncuentra24.Source)
        }
    }
}
