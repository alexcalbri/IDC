package com.ideasdeveloper.idc.extensions.wordpress

import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.idc.modules.crm.CrmExtensions
import com.ideasdeveloper.idc.modules.crm.CrmImportSource
import com.ideasdeveloper.idc.modules.crm.CrmUser
import com.ideasdeveloper.idc.modules.crm.CrmWordPressSettings
import com.ideasdeveloper.idc.modules.crm.WordPressProvider
import com.ideasdeveloper.idc.modules.crm.WpAgent
import com.ideasdeveloper.idc.modules.crm.WpListing
import com.ideasdeveloper.idc.modules.crm.WpSearchResult
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
import com.ideasdeveloper.idc.modules.crm.CrmHost
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
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Extension "wordpress": sitio con Real Estate 7 y el plugin IDC Connector. El plugin envia los eventos a
 * /modules/crm/extensions/wordpress/{empresa}/hook y se descarga de /modules/crm/extensions/wordpress/idc-connector.zip.
 * Tambien es la fuente de importacion "leadspro" (CT Leads Pro, que se lee por el mismo plugin y el mismo secreto): ver [WordPressImport].
 */
object CrmWordPressExtension : ServerExtension, WordPressProvider, CrmImportSource by WordPressImport {
    const val ExtensionPath = "/modules/crm/extensions/wordpress"
    const val PluginPath = "$ExtensionPath/idc-connector.zip"

    /** Direccion que el plugin llama para una empresa. */
    fun hookPath(company: String) = "$ExtensionPath/$company/hook"

    const val LinkedPropertiesOption = "linked_properties"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = CrmExtensionIds.WordPress,
        module = "crm",
        displayName = "WordPress (Real Estate 7)",
        description = "Conecta el sitio WordPress (Real Estate 7) con el plugin IDC Connector: formularios y consultas crean leads, y las propiedades se buscan y se vinculan a ellos.",
        version = "1.0.0",
        minModuleVersion = "0.27.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/wordpress",
        ),
        // El sitio de la empresa tiene un host propio, que una lista cerrada de permisos no puede nombrar.
        permissions = listOf(
            ExtensionPermissions.SecretsWrite,
            ExtensionPermissions.WebhookPublic,
            CrmExtensionPermissions.LeadsCreate,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.ItemsWrite,
        ),
        // Los ajustes se guardan en ext_wordpress__settings y se editan en las pantallas del CRM: ninguno es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(
                ExtensionUninstallOption(LinkedPropertiesOption, "Propiedades del sitio vinculadas a los leads", UninstallDefault.Keep),
            ),
        ),
    )

    override val migrationPaths = listOf("extensions/wordpress/migrations/V001__settings.sql")

    override val runtimeTables = listOf(WordPressSettingsStore.SettingsTable, WordPressSettingsStore.AgentsTable)

    override fun routes(route: Route, access: ExtensionAccess) {
        // El plugin de WordPress, armado con los recursos del paquete (no lleva secretos).
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
                    CrmWordPress.handle(tenant, body.decodeToString()) { leadId -> CrmHost.notifyNewLead(tenant, leadId) }
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

    // ---- WordPressProvider: lo que el CRM invoca sin depender del código de WordPress ----

    override fun active(host: CrmHost, tenant: Connection): Boolean =
        WordPressSettingsStore.load(host, tenant).ready && host.wordpressEnabled(tenant)

    override fun settings(host: CrmHost, tenant: Connection): CrmWordPressSettings = WordPressSettingsStore.load(host, tenant)

    override fun saveForm(host: CrmHost, tenant: Connection, values: Map<String, String>) = WordPressSettingsStore.saveForm(host, tenant, values)

    override fun newSecret(host: CrmHost, tenant: Connection): String = WordPressSettingsStore.newSecret(host, tenant)

    override fun ping(host: CrmHost, tenant: Connection): String = CrmWordPress.ping(tenant)

    override fun search(host: CrmHost, tenant: Connection, text: String, filters: Map<String, String>, minPrice: String, maxPrice: String, page: Int): WpSearchResult =
        CrmWordPress.search(tenant, text, filters, minPrice, maxPrice, page)

    override fun listing(host: CrmHost, tenant: Connection, id: Long): WpListing = CrmWordPress.listing(tenant, id)

    override fun taxonomies(host: CrmHost, tenant: Connection): Map<String, List<String>> = CrmWordPress.taxonomies(tenant)

    override fun markSold(host: CrmHost, tenant: Connection, id: Long, price: BigDecimal?, date: LocalDate) = CrmWordPress.markSold(tenant, id, price, date)

    override fun syncAgents(host: CrmHost, tenant: Connection, users: List<CrmUser>, userEmails: Map<UUID, String>): Int = CrmWordPress.syncAgents(tenant, users, userEmails)

    override fun agents(host: CrmHost, tenant: Connection): List<WpAgent> = WordPressSettingsStore.agents(tenant)

    override fun mapAgent(host: CrmHost, tenant: Connection, wpUserId: Long, userId: UUID?) = WordPressSettingsStore.mapAgent(tenant, wpUserId, userId)

    override fun linkListing(host: CrmHost, tenant: Connection, leadId: UUID, listing: WpListing, activity: String?) =
        CrmWordPress.linkListing(tenant, leadId, listing, activity)

    override fun recordError(host: CrmHost, tenant: Connection, error: String) = WordPressSettingsStore.recordError(tenant, error)

    // ---- Desinstalación ----

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        val properties = CrmExtensions.count(tenant, "SELECT count(*) FROM crm_lead_items WHERE source = ?", CrmWordPress.Source)
        return listOf(
            CrmExtensions.item(null, "Conexion con el sitio y secreto del plugin (se borran)", CrmExtensions.count(tenant, "SELECT count(*) FROM ${WordPressSettingsStore.SettingsTable} WHERE enabled OR coalesce(secret, '') <> '' OR coalesce(site_url, '') <> ''"), "delete"),
            CrmExtensions.item(null, "Agentes del sitio y su vinculo con usuarios", CrmExtensions.count(tenant, "SELECT count(*) FROM ${WordPressSettingsStore.AgentsTable}"), "delete"),
            CrmExtensions.item(LinkedPropertiesOption, "Propiedades del sitio vinculadas a los leads", properties, UninstallDefault.Keep),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // Los ajustes y los agentes viven en las tablas de la extension: se borran sus filas.
        CrmExtensions.update(tenant, "DELETE FROM ${WordPressSettingsStore.SettingsTable} WHERE id = 1")
        CrmExtensions.update(tenant, "DELETE FROM ${WordPressSettingsStore.AgentsTable}")
        if (choices[LinkedPropertiesOption] == UninstallDefault.Delete) {
            CrmExtensions.update(tenant, "DELETE FROM crm_lead_items WHERE source = ?", CrmWordPress.Source)
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class WordPressServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = CrmWordPressExtension
}
