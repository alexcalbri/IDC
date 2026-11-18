package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ExtensionContext
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallOption
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.extensions.UninstallDefault
import com.ideasdeveloper.idc.server.extensions.UninstallItem

/**
 * Extension "cloudbeds": habitaciones libres, reservas y check-in en el PMS, correo de bienvenida y archivo tras el check-out.
 * No tiene rutas publicas: todo lo hacen acciones y trabajos que comprueban que la extension siga activa.
 */
object CrmCloudbedsExtension : ServerExtension {
    const val ReservationsOption = "reservations"
    const val RoomsOption = "rooms"
    const val CountryOption = "default_country"

    override val manifest = CrmExtensions.manifest(
        id = CrmExtensionIds.Cloudbeds,
        description = "Busca habitaciones libres, crea reservas y hace el check-in en Cloudbeds desde el lead; envia la bienvenida y archiva al salir el huesped.",
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.cloudbeds.com",
            ExtensionPermissions.SecretsWrite,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.MessagesWrite,
            CrmExtensionPermissions.ItemsWrite,
            CrmExtensionPermissions.ReservationsWrite,
        ),
        options = listOf(
            ExtensionUninstallOption(ReservationsOption, "Reservas de Cloudbeds registradas en los leads", UninstallDefault.Keep),
            ExtensionUninstallOption(RoomsOption, "Habitaciones de Cloudbeds asignadas a los leads", UninstallDefault.Keep),
            ExtensionUninstallOption(CountryOption, "Pais por defecto del huesped", UninstallDefault.Ask),
        ),
    )

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            CrmExtensions.item(null, "Conexion con Cloudbeds y su API key (se borran)", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_cloudbeds_settings WHERE enabled OR coalesce(api_key, '') <> ''"), "delete"),
            CrmExtensions.item(ReservationsOption, "Reservas de Cloudbeds registradas en los leads", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_reservations"), UninstallDefault.Keep),
            CrmExtensions.item(RoomsOption, "Habitaciones de Cloudbeds asignadas a los leads", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_lead_items WHERE source = ?", CrmCloudbeds.Source), UninstallDefault.Keep),
            CrmExtensions.item(CountryOption, "Pais por defecto del huesped", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_cloudbeds_settings WHERE default_country <> ''"), UninstallDefault.Ask),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        CrmExtensions.update(
            tenant,
            "UPDATE crm_cloudbeds_settings SET enabled = FALSE, api_key = NULL, property_id = NULL, welcome_enabled = FALSE, welcome_template_id = NULL, last_sync = NULL, last_error = '', updated_at = CURRENT_TIMESTAMP WHERE id = 1",
        )
        if (choices[ReservationsOption] == UninstallDefault.Delete) CrmExtensions.update(tenant, "DELETE FROM crm_reservations")
        if (choices[CountryOption] == UninstallDefault.Delete) CrmExtensions.update(tenant, "UPDATE crm_cloudbeds_settings SET default_country = '' WHERE id = 1")
        if (choices[RoomsOption] == UninstallDefault.Delete) CrmExtensions.update(tenant, "DELETE FROM crm_lead_items WHERE source = ?", CrmCloudbeds.Source)
    }
}
