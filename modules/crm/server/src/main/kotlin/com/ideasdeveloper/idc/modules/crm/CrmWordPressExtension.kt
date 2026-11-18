package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ExtensionAccess
import com.ideasdeveloper.idc.server.extensions.ExtensionContext
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallOption
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.extensions.UninstallDefault
import com.ideasdeveloper.idc.server.extensions.UninstallItem
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Extension "wordpress": sitio con Real Estate 7 y el plugin IDC Connector. El plugin envia los eventos a
 * /modules/crm/extensions/wordpress/{empresa}/hook y se descarga de /modules/crm/extensions/wordpress/idc-connector.zip.
 */
object CrmWordPressExtension : ServerExtension {
    const val ExtensionPath = "/modules/crm/extensions/wordpress"
    const val PluginPath = "$ExtensionPath/idc-connector.zip"

    /** Direccion que el plugin llama para una empresa. */
    fun hookPath(company: String) = "$ExtensionPath/$company/hook"

    const val LinkedPropertiesOption = "linked_properties"

    override val manifest = CrmExtensions.manifest(
        id = CrmExtensionIds.WordPress,
        description = "Conecta el sitio WordPress (Real Estate 7) con el plugin IDC Connector: formularios y consultas crean leads, y las propiedades se buscan y se vinculan a ellos.",
        // El sitio de la empresa tiene un host propio, que una lista cerrada de permisos no puede nombrar.
        permissions = listOf(
            ExtensionPermissions.SecretsWrite,
            ExtensionPermissions.WebhookPublic,
            CrmExtensionPermissions.LeadsCreate,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.ItemsWrite,
        ),
        options = listOf(
            ExtensionUninstallOption(LinkedPropertiesOption, "Propiedades del sitio vinculadas a los leads", UninstallDefault.Keep),
        ),
    )

    override fun routes(route: Route, access: ExtensionAccess) {
        // El plugin de WordPress, armado con los recursos del modulo (no lleva secretos).
        route.get("/idc-connector.zip") {
            val zip = CrmPlugin.zip(CrmWordPressExtension::class.java.classLoader)
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "idc-connector.zip").toString())
            call.respondBytes(zip, ContentType.Application.Zip)
        }
        // La empresa va en la URL y cada llamada se autentica con el secreto compartido (firma y fecha).
        route.post("/{company}/hook") {
            val company = call.parameters["company"].orEmpty()
            val body = CrmExtensions.readBody(call) ?: return@post
            val timestamp = call.request.headers["X-IDC-Timestamp"]
            val signature = call.request.headers["X-IDC-Signature"]
            val answer = CrmExtensions.withEnabledTenant(call, access, company, HttpStatusCode.Forbidden) { tenant ->
                val settings = CrmWordPress.load(tenant)
                if (!settings.enabled || !CrmWordPress.validSignature(settings.secret, timestamp, signature, body)) return@withEnabledTenant "no"
                try {
                    CrmWordPress.handle(tenant, body.decodeToString()) { leadId -> CrmNotify.newLead(tenant, leadId) }
                } catch (failure: Exception) {
                    CrmWordPress.recordError(tenant, failure.message ?: failure.javaClass.simpleName)
                    return@withEnabledTenant "error"
                }
                "yes"
            } ?: return@post
            when (answer) {
                "yes" -> call.respond(HttpStatusCode.OK)
                "no" -> call.respond(HttpStatusCode.Forbidden)
                // El plugin guarda el evento y lo reintenta.
                else -> call.respond(HttpStatusCode.ServiceUnavailable)
            }
        }
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        val properties = CrmExtensions.count(tenant, "SELECT count(*) FROM crm_lead_items WHERE source = ?", CrmWordPress.Source)
        return listOf(
            CrmExtensions.item(null, "Conexion con el sitio y secreto del plugin (se borran)", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_wordpress_settings WHERE enabled OR coalesce(secret, '') <> '' OR coalesce(site_url, '') <> ''"), "delete"),
            CrmExtensions.item(null, "Agentes del sitio y su vinculo con usuarios", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_wp_agents"), "delete"),
            CrmExtensions.item(LinkedPropertiesOption, "Propiedades del sitio vinculadas a los leads", properties, UninstallDefault.Keep),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        CrmExtensions.update(tenant, "UPDATE crm_wordpress_settings SET enabled = FALSE, site_url = NULL, secret = NULL, site_name = '', last_event_at = NULL, last_error = '', updated_at = CURRENT_TIMESTAMP WHERE id = 1")
        CrmExtensions.update(tenant, "DELETE FROM crm_wp_agents")
        if (choices[LinkedPropertiesOption] == UninstallDefault.Delete) {
            CrmExtensions.update(tenant, "DELETE FROM crm_lead_items WHERE source = ?", CrmWordPress.Source)
        }
    }
}
