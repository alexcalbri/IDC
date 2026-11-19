package com.ideasdeveloper.idc.extensions.meta

import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.idc.modules.crm.CrmExtensions
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmMetaException
import com.ideasdeveloper.idc.modules.crm.CrmMetaServerApp
import com.ideasdeveloper.idc.modules.crm.CrmMetaSettings
import com.ideasdeveloper.idc.modules.crm.CrmValidationException
import com.ideasdeveloper.idc.modules.crm.MetaLogEntry
import com.ideasdeveloper.idc.modules.crm.MetaNameFill
import com.ideasdeveloper.idc.modules.crm.MetaProvider
import com.ideasdeveloper.idc.modules.crm.MetaSetup
import com.ideasdeveloper.idc.server.extensions.ExtensionAccess
import com.ideasdeveloper.idc.server.extensions.ExtensionContext
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionSource
import com.ideasdeveloper.idc.server.extensions.ExtensionSourceType
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallDefinition
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.extensions.ServerExtensionProvider
import com.ideasdeveloper.idc.server.extensions.UninstallItem
import com.ideasdeveloper.idc.ui.UiJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.sql.Connection
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Extension "meta": WhatsApp, Messenger e Instagram (app propia de la empresa o app del servidor). Sus rutas cuelgan de
 * /modules/crm/extensions/meta: el webhook de la app del servidor (una sola URL para todas las empresas, que reparte cada evento
 * a la empresa que conecto la pagina o el numero), el webhook de la app propia de una empresa y la pagina de conexion.
 * Sus datos viven en las tablas ext_meta__* de cada empresa.
 */
object CrmMetaExtension : ServerExtension, MetaProvider {
    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = CrmExtensionIds.Meta,
        module = "crm",
        displayName = "Meta (WhatsApp, Messenger e Instagram)",
        description = "Recibe y responde mensajes de WhatsApp, Messenger e Instagram en el CRM, con la app de Meta de la empresa o la del servidor.",
        version = "1.0.0",
        minModuleVersion = "0.27.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/meta",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "graph.facebook.com",
            ExtensionPermissions.SecretsWrite,
            ExtensionPermissions.WebhookPublic,
            CrmExtensionPermissions.LeadsCreate,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.MessagesWrite,
        ),
        // Las conexiones se guardan en ext_meta__settings y se editan en las pantallas del CRM: ninguno es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        // Las conexiones se borran siempre y las conversaciones se conservan siempre: no hay nada que preguntar.
        uninstall = ExtensionUninstallDefinition(emptyList()),
    )

    override val migrationPaths = listOf("extensions/meta/migrations/V001__settings.sql")

    override val runtimeTables = listOf(
        CrmMeta.SettingsTable, CrmMeta.LogTable, CrmMetaServer.ServerAppTable, CrmMetaServer.RoutesTable, CrmMetaServer.TicketsTable,
    )

    override fun routes(route: Route, access: ExtensionAccess) {
        // El espacio y las rutas del servidor se abren con el servicio de modulos del Core (antes lo ponia el modulo CRM).
        CrmMetaServer.access = access.modules
        // ---- App de Meta del servidor: una URL para todas las empresas ----
        route.get("/webhook") {
            val query = call.request.queryParameters
            val challenge = withContext(Dispatchers.IO) {
                runCatching {
                    CrmMetaServer.withServerWorkspace { server ->
                        CrmMetaServer.verify(CrmMetaServer.loadApp(server), query["hub.mode"], query["hub.verify_token"], query["hub.challenge"]).also {
                            CrmMeta.log(server, if (it != null) "verificacion_ok" else "verificacion_rechazada", "app del servidor")
                        }
                    }
                }.getOrNull()
            } ?: return@get call.respond(HttpStatusCode.Forbidden)
            call.respondText(challenge, ContentType.Text.Plain)
        }
        route.post("/webhook") {
            val body = CrmExtensions.readBody(call) ?: return@post
            val signature = call.request.headers["X-Hub-Signature-256"]
            // Cada evento llega solo a la empresa de su pagina o numero, y solo si tiene la extension activa.
            val status = withContext(Dispatchers.IO) { runCatching { CrmMetaServer.dispatch(body, signature) { company -> access.enabledTenant(company) } }.getOrDefault(500) }
            call.respond(HttpStatusCode.fromValue(status))
        }
        // Meta llama a estas dos con un signed_request cuando alguien quita la app o pide borrar sus datos.
        route.post("/deauthorize") {
            val signed = call.receiveParameters()["signed_request"]
            val forgotten = withContext(Dispatchers.IO) { runCatching { CrmMetaServer.forgetUser(signed) }.getOrNull() }
            call.respond(if (forgotten == null) HttpStatusCode.BadRequest else HttpStatusCode.OK)
        }
        route.post("/data-deletion") {
            val signed = call.receiveParameters()["signed_request"]
            withContext(Dispatchers.IO) { runCatching { CrmMetaServer.forgetUser(signed) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val code = CrmMetaServer.confirmationCode()
            val answer = buildJsonObject {
                put("url", "${CrmExtensions.publicBase(call)}${CrmMetaServer.DeletionStatusPath}?id=$code")
                put("confirmation_code", code)
            }
            call.respondText(answer.toString(), ContentType.Application.Json)
        }
        route.get("/deletion") {
            call.respondText(
                "Los tokens y la conexion con Meta de esta persona se eliminaron del CRM. Codigo: " +
                    call.request.queryParameters["id"].orEmpty().filter { it.isLetterOrDigit() }.take(40),
                ContentType.Text.Plain,
            )
        }

        // ---- Pagina de conexion de la empresa con la app del servidor ----
        route.route("/connect/{company}") {
            get {
                val company = call.parameters["company"].orEmpty()
                val ticket = call.request.queryParameters["t"].orEmpty()
                val page = call.connectTenant(access, company, ticket) { _, app -> CrmMetaServer.connectPage(app, company, ticket) } ?: return@get
                call.response.header("Cache-Control", "no-store")
                call.respondText(page, ContentType.Text.Html)
            }
            post("/{step}") {
                val company = call.parameters["company"].orEmpty()
                val values = runCatching { UiJson.parseToJsonElement(call.receiveText()).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } }
                    .getOrNull().orEmpty()
                val outcome = call.connectTenant(access, company, values["t"]) { tenant, app ->
                    val step = call.parameters["step"]
                    val result = try {
                        when (step) {
                            "pages" -> if (app.pagesConfigId == null) CrmMetaServer.Outcome(false, "Messenger e Instagram no estan habilitados en la app del servidor.")
                            else CrmMetaServer.connectLogin(tenant, company, app, values["code"].orEmpty())
                            "page" -> CrmMetaServer.choosePage(tenant, company, values["pageId"].orEmpty())
                            "whatsapp" -> if (app.whatsAppConfigId == null) CrmMetaServer.Outcome(false, "WhatsApp no esta habilitado en la app del servidor.")
                            else CrmMetaServer.connectWhatsApp(
                                tenant, company, app, values["code"].orEmpty(), values["wabaId"].orEmpty(),
                                values["phoneNumberId"].orEmpty(), values["pin"],
                            )
                            else -> CrmMetaServer.Outcome(false, "Paso desconocido.")
                        }
                    } catch (failure: CrmMetaException) {
                        CrmMeta.log(tenant, "conexion_error", failure.message.orEmpty())
                        CrmMetaServer.Outcome(false, failure.message.orEmpty())
                    } catch (failure: CrmValidationException) {
                        // Por ejemplo, no se pueden guardar secretos sin la llave maestra del servidor.
                        CrmMetaServer.Outcome(false, failure.message.orEmpty())
                    }
                    // El enlace es de una sola conexion: se gasta cuando un paso conecta (si solo devolvio paginas para elegir, sigue valido).
                    if (result.ok && result.pages.isEmpty() && step in setOf("pages", "page", "whatsapp")) CrmMetaServer.consumeTicket(tenant, values["t"])
                    result
                } ?: return@post
                val answer = buildJsonObject {
                    put("ok", outcome.ok)
                    put("message", outcome.message)
                    put("pages", JsonArray(outcome.pages.map { page ->
                        buildJsonObject {
                            put("id", page.id)
                            put("name", page.name)
                            put("instagram", page.instagram)
                        }
                    }))
                }
                call.respondText(answer.toString(), ContentType.Application.Json)
            }
        }

        // ---- App de Meta propia de una empresa: la empresa va en la URL y cada llamada se autentica con su App Secret ----
        route.get("/{company}/webhook") {
            val company = call.parameters["company"].orEmpty()
            val query = call.request.queryParameters
            val challenge = CrmExtensions.withEnabledTenant(call, access, company, HttpStatusCode.Forbidden) { tenant ->
                val settings = CrmMeta.load(tenant)
                CrmMeta.verify(settings, query["hub.mode"], query["hub.verify_token"], query["hub.challenge"]).also {
                    CrmMeta.log(tenant, if (it != null) "verificacion_ok" else "verificacion_rechazada")
                }
            } ?: return@get
            if (challenge.isEmpty()) return@get call.respond(HttpStatusCode.Forbidden)
            call.respondText(challenge, ContentType.Text.Plain)
        }
        route.post("/{company}/webhook") {
            val company = call.parameters["company"].orEmpty()
            val body = CrmExtensions.readBody(call) ?: return@post
            val signature = call.request.headers["X-Hub-Signature-256"]
            val accepted = CrmExtensions.withEnabledTenant(call, access, company, HttpStatusCode.Forbidden) { tenant ->
                val settings = CrmMeta.load(tenant)
                if (!settings.enabled || !CrmMeta.validSignature(settings.appSecret, signature, body)) {
                    CrmMeta.log(tenant, "firma_rechazada", if (settings.appSecret.isNullOrBlank()) "App Secret vacio" else "firma invalida o ausente")
                    return@withEnabledTenant false
                }
                // Meta disables subscriptions that keep failing: errors of one message are logged, the answer stays 200.
                try {
                    val stored = CrmMeta.handle(tenant, settings, CrmMeta.parse(body.decodeToString(), settings.instagramAccountId)) { leadId -> CrmHost.notifyNewLead(tenant, leadId) }
                    CrmMeta.log(tenant, "evento", "guardados=$stored")
                } catch (failure: Exception) {
                    CrmMeta.log(tenant, "error", failure.message ?: failure.javaClass.simpleName)
                }
                true
            } ?: return@post
            if (accepted) call.respond(HttpStatusCode.OK) else call.respond(HttpStatusCode.Forbidden)
        }
    }

    // ---- MetaProvider: lo que el CRM invoca sin depender del codigo de Meta ----

    // La extension activa primero (tabla tenant_extensions del core): recien ahi se lee ext_meta__settings, que solo existe si se instalo.
    override fun active(host: CrmHost, tenant: Connection): Boolean = host.metaEnabled(tenant) && CrmMeta.load(tenant).enabled

    override fun settings(host: CrmHost, tenant: Connection): CrmMetaSettings = CrmMeta.load(tenant)

    override fun save(host: CrmHost, tenant: Connection, settings: CrmMetaSettings) = CrmMeta.save(tenant, settings)

    override fun merge(current: CrmMetaSettings, values: Map<String, String>): CrmMetaSettings = CrmMeta.merge(current, values)

    override fun toFormValues(settings: CrmMetaSettings): Map<String, String> = CrmMeta.toFormValues(settings)

    override fun setup(host: CrmHost, tenant: Connection, settings: CrmMetaSettings, page: Boolean, whatsApp: Boolean): MetaSetup =
        CrmMeta.setup(tenant, settings, page, whatsApp)

    override fun log(host: CrmHost, tenant: Connection, event: String, detail: String) = CrmMeta.log(tenant, event, detail)

    override fun recent(host: CrmHost, tenant: Connection): List<MetaLogEntry> = CrmMeta.recent(tenant)

    override fun fillNames(host: CrmHost, tenant: Connection, settings: CrmMetaSettings, limit: Int): MetaNameFill = CrmMeta.fillNames(tenant, settings, limit)

    override fun sendWhatsApp(host: CrmHost, settings: CrmMetaSettings, toDigits: String, text: String): String = CrmMeta.sendWhatsApp(settings, toDigits, text)

    override fun sendPage(host: CrmHost, settings: CrmMetaSettings, recipient: String, text: String, platform: String): String =
        CrmMeta.sendPage(settings, recipient, text, platform)

    override fun disconnect(host: CrmHost, tenant: Connection, company: String) = CrmMetaServer.disconnect(tenant, company)

    override fun serverApp(host: CrmHost): CrmMetaServerApp? = CrmMetaServer.app()

    override fun serverWorkspace(host: CrmHost): String? = CrmMetaServer.serverWorkspace()

    override fun loadApp(host: CrmHost, tenant: Connection): CrmMetaServerApp = CrmMetaServer.loadApp(tenant)

    override fun saveApp(host: CrmHost, tenant: Connection, app: CrmMetaServerApp) = CrmMetaServer.saveApp(tenant, app)

    override fun mergeApp(current: CrmMetaServerApp, values: Map<String, String>): CrmMetaServerApp = CrmMetaServer.mergeApp(current, values)

    override fun appFormValues(app: CrmMetaServerApp): Map<String, String> = CrmMetaServer.appFormValues(app)

    override fun newTicket(host: CrmHost, tenant: Connection, createdBy: String): String = CrmMetaServer.newTicket(tenant, createdBy)

    // Tenant de una empresa para la pagina de conexion, tras revisar su enlace y la app del servidor.
    private suspend fun <T> ApplicationCall.connectTenant(
        access: ExtensionAccess,
        company: String,
        ticket: String?,
        block: (Connection, CrmMetaServerApp) -> T,
    ): T? {
        val app = withContext(Dispatchers.IO) { CrmMetaServer.app() }?.takeIf { it.ready }
        if (app == null) {
            respondText("La app de Meta del servidor no esta activa.", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return null
        }
        val connection = withContext(Dispatchers.IO) { access.enabledTenant(company) }
        if (connection == null) {
            respond(HttpStatusCode.NotFound)
            return null
        }
        return try {
            connection.use { tenant ->
                withContext(Dispatchers.IO) {
                    if (!CrmMetaServer.validTicket(tenant, ticket)) null else block(tenant, app)
                }
            } ?: run {
                respondText("El enlace vencio o no es valido: abre otra vez la conexion desde Integraciones.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                null
            }
        } catch (failure: SQLException) {
            respond(HttpStatusCode.InternalServerError)
            null
        }
    }

    // ---- Desinstalar ----

    private val messageChannels = "('whatsapp', 'messenger', 'instagram')"

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        val connections = CrmExtensions.count(
            tenant,
            "SELECT (CASE WHEN coalesce(page_id, '') <> '' THEN 1 ELSE 0 END) + (CASE WHEN coalesce(instagram_account_id, '') <> '' THEN 1 ELSE 0 END) + " +
                "(CASE WHEN coalesce(phone_number_id, '') <> '' THEN 1 ELSE 0 END) FROM ${CrmMeta.SettingsTable} WHERE id = 1",
        )
        return listOf(
            CrmExtensions.item(null, "Conexiones de pagina, Instagram y numero de WhatsApp (se borran con sus tokens y secretos)", connections, "delete"),
            CrmExtensions.item(null, "Registro de eventos de Meta", CrmExtensions.count(tenant, "SELECT count(*) FROM ${CrmMeta.LogTable}"), "delete"),
            CrmExtensions.item(null, "Mensajes de WhatsApp, Messenger e Instagram de los leads (se conservan)", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_messages WHERE channel IN $messageChannels"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // Las conexiones y sus secretos se borran: la fila de ajustes se elimina (sin fila, Meta se ve apagado).
        CrmExtensions.update(tenant, "DELETE FROM ${CrmMeta.SettingsTable} WHERE id = 1")
        // Por lotes, para no tener una transaccion larga ni bloqueos grandes.
        do {
            val deleted = CrmExtensions.update(tenant, "DELETE FROM ${CrmMeta.LogTable} WHERE ctid IN (SELECT ctid FROM ${CrmMeta.LogTable} LIMIT 5000)")
        } while (deleted >= 5000)
        CrmExtensions.update(tenant, "DELETE FROM ${CrmMetaServer.TicketsTable}")
    }

    // Despues del commit y fuera de la transaccion de la empresa: la pagina y el numero dejan de llegar a ella (rutas del espacio del
    // servidor). Si falla, el webhook los ignora igual porque la extension ya no esta activa aqui.
    override fun afterUninstall(companyCode: String) {
        try {
            CrmMetaServer.withServerWorkspace { server -> CrmMetaServer.release(server, companyCode) }
        } catch (failure: Exception) {
            org.slf4j.LoggerFactory.getLogger("MetaServerExtension").warn("No se liberaron las rutas de Meta de {}: {}", companyCode, failure.message)
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class MetaServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = CrmMetaExtension
}
