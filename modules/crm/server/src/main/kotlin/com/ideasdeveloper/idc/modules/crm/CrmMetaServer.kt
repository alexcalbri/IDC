package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Meta app of the server owner (stored in the server workspace). */
data class CrmMetaServerApp(
    val enabled: Boolean = false,
    val appId: String? = null,
    val appSecret: String? = null,
    val verifyToken: String? = null,
    val pagesConfigId: String? = null,
    val whatsAppConfigId: String? = null,
) {
    // Companies can connect when the app is on and has its id, secret and at least one login configuration.
    val ready get() = enabled && appId != null && !appSecret.isNullOrBlank() && (pagesConfigId != null || whatsAppConfigId != null)
}

/**
 * One Meta app for every company of the server, like the CRM services that only ask to log in: the
 * server owner creates, verifies (Tech Provider) and reviews one app; each company connects its page,
 * Instagram and WhatsApp with Facebook Login for Business and WhatsApp Embedded Signup. The app sends
 * every webhook to one URL and [dispatch] hands each entry to the company that connected the asset
 * (crm_meta_routes, in the server workspace). Messages are then handled as in the company's own app.
 */
object CrmMetaServer {
    // Routes of the "meta" extension (the Core mounts them under /modules/crm/extensions/meta); the old /modules/crm/meta/... no longer answer.
    const val ExtensionPath = "/modules/crm/extensions/meta"
    const val WebhookPath = "$ExtensionPath/webhook"
    const val DeauthorizePath = "$ExtensionPath/deauthorize"
    const val DataDeletionPath = "$ExtensionPath/data-deletion"
    const val DeletionStatusPath = "$ExtensionPath/deletion"
    const val ConnectPath = "$ExtensionPath/connect"

    /** Webhook of a company that uses its own Meta app. */
    fun companyWebhookPath(company: String) = "$ExtensionPath/$company/webhook"

    // Set when the module registers its routes; null in tests (no server app).
    @Volatile
    internal var access: ModuleAccessService? = null

    private val numericId = Regex("^[0-9]{1,64}$")
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val ticketLifetime = Duration.ofMinutes(30)

    private fun JsonElement?.jObj() = this as? JsonObject
    private fun JsonElement?.jArr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.jStr() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonElement?.jTrue() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } == true

    // ---- Server app settings ----

    fun loadApp(tenant: Connection): CrmMetaServerApp = tenant.prepareStatement(
        "SELECT enabled, app_id, app_secret, verify_token, pages_config_id, whatsapp_config_id FROM crm_meta_server_app WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            if (!rows.next()) CrmMetaServerApp()
            else CrmMetaServerApp(rows.getBoolean(1), rows.getString(2), CrmSecrets.open(tenant, rows.getString(3)), CrmSecrets.open(tenant, rows.getString(4)), rows.getString(5), rows.getString(6))
        }
    }

    fun saveApp(tenant: Connection, app: CrmMetaServerApp) {
        tenant.prepareStatement(
            """
            INSERT INTO crm_meta_server_app (id, enabled, app_id, app_secret, verify_token, pages_config_id, whatsapp_config_id)
            VALUES (1, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, app_id = EXCLUDED.app_id, app_secret = EXCLUDED.app_secret,
                verify_token = EXCLUDED.verify_token, pages_config_id = EXCLUDED.pages_config_id,
                whatsapp_config_id = EXCLUDED.whatsapp_config_id, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { update ->
            update.setBoolean(1, app.enabled)
            update.setString(2, app.appId)
            update.setString(3, CrmSecrets.seal(tenant, app.appSecret))
            update.setString(4, CrmSecrets.seal(tenant, app.verifyToken))
            update.setString(5, app.pagesConfigId)
            update.setString(6, app.whatsAppConfigId)
            update.executeUpdate()
        }
    }

    fun appFormValues(app: CrmMetaServerApp): Map<String, String> = mapOf(
        "serverEnabled" to app.enabled.toString(),
        "serverAppId" to app.appId.orEmpty(),
        "serverAppSecret" to CrmSecrets.mask(app.appSecret),
        "serverVerifyToken" to if (CrmSecrets.isUnreadable(app.verifyToken)) CrmSecrets.UnreadableText else app.verifyToken.orEmpty(),
        "serverPagesConfigId" to app.pagesConfigId.orEmpty(),
        "serverWhatsAppConfigId" to app.whatsAppConfigId.orEmpty(),
    )

    /** Applies the server app form; a blank or masked secret keeps the saved one. */
    fun mergeApp(current: CrmMetaServerApp, values: Map<String, String>): CrmMetaServerApp {
        fun id(key: String, label: String): String? {
            val value = values[key].orEmpty().trim().ifEmpty { return null }
            if (!numericId.matches(value)) throw CrmValidationException("$label solo lleva numeros.")
            return value
        }
        val verify = values["serverVerifyToken"].orEmpty().trim().ifEmpty { null }.let { if (CrmSecrets.isPlaceholder(it)) current.verifyToken else it }
        if (verify != null && !CrmSecrets.isUnreadable(verify) && !verify.matches(Regex("^[A-Za-z0-9_.-]{8,100}$"))) {
            throw CrmValidationException("El verify token debe tener entre 8 y 100 letras, numeros, puntos, guiones o guiones bajos.")
        }
        val merged = CrmMetaServerApp(
            enabled = values["serverEnabled"] == "true",
            appId = id("serverAppId", "El App ID"),
            appSecret = values["serverAppSecret"]?.trim()?.takeIf { it.isNotEmpty() && !CrmSecrets.isPlaceholder(it) } ?: current.appSecret,
            verifyToken = verify,
            pagesConfigId = id("serverPagesConfigId", "La configuracion de Messenger e Instagram"),
            whatsAppConfigId = id("serverWhatsAppConfigId", "La configuracion de WhatsApp"),
        )
        if (merged.enabled && (merged.appId == null || merged.appSecret.isNullOrBlank() || merged.verifyToken == null)) {
            throw CrmValidationException("Para activar la app del servidor escribe el App ID, el App Secret y el verify token.")
        }
        if (merged.enabled && merged.pagesConfigId == null && merged.whatsAppConfigId == null) {
            throw CrmValidationException("Escribe al menos una configuracion de Facebook Login for Business (Messenger e Instagram, o WhatsApp).")
        }
        return merged
    }

    // ---- Server workspace ----

    /** Code of the active server workspace, or null. */
    fun serverWorkspace(): String? = access?.centralConnection()?.use { central ->
        central.prepareStatement("SELECT code FROM companies WHERE is_server_workspace = TRUE AND is_active = TRUE ORDER BY code LIMIT 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    /** Runs [block] with the server workspace tenant (where the server app and routes live); null without one or without the CRM there. */
    fun <T> withServerWorkspace(block: (Connection) -> T): T? {
        val service = access ?: return null
        val code = serverWorkspace() ?: return null
        return service.tenantConnectionForWebhook(code, CrmModule.id)?.use(block)
    }

    /** The server app, or null when the server has none (no server workspace, CRM off there, or never saved). */
    fun app(): CrmMetaServerApp? = runCatching { withServerWorkspace(::loadApp) }.getOrNull()

    // ---- Routes: which company receives each asset ----

    /** Gives [assetId] to [company]; returns the company that already has it, or null when it is now this one's. */
    fun claim(server: Connection, kind: String, assetId: String, company: String): String? {
        require(numericId.matches(assetId))
        if (kind != "user") {
            val owner = server.prepareStatement("SELECT company_code FROM crm_meta_routes WHERE kind = ? AND asset_id = ?").use { query ->
                query.setString(1, kind)
                query.setString(2, assetId)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (owner != null && owner != company) return owner
        }
        val inserted = server.prepareStatement("INSERT INTO crm_meta_routes (asset_id, kind, company_code) VALUES (?, ?, ?) ON CONFLICT DO NOTHING").use { insert ->
            insert.setString(1, assetId)
            insert.setString(2, kind)
            insert.setString(3, company)
            insert.executeUpdate()
        }
        // Two companies connecting the same asset at once: the unique index lets one win; the other learns who has it.
        if (kind != "user" && inserted == 0) companyOf(server, kind, assetId)?.takeIf { it != company }?.let { return it }
        return null
    }

    /** Removes the routes of [company] of the given kinds (all when empty). */
    fun release(server: Connection, company: String, kinds: Set<String> = emptySet()) {
        val filter = if (kinds.isEmpty()) "" else " AND kind IN (${kinds.joinToString { "?" }})"
        server.prepareStatement("DELETE FROM crm_meta_routes WHERE company_code = ?$filter").use { delete ->
            delete.setString(1, company)
            kinds.forEachIndexed { index, kind -> delete.setString(index + 2, kind) }
            delete.executeUpdate()
        }
    }

    fun companyOf(server: Connection, kind: String, assetId: String): String? =
        server.prepareStatement("SELECT company_code FROM crm_meta_routes WHERE kind = ? AND asset_id = ?").use { query ->
            query.setString(1, kind)
            query.setString(2, assetId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun companiesOfUser(server: Connection, userId: String): List<String> =
        server.prepareStatement("SELECT company_code FROM crm_meta_routes WHERE kind = 'user' AND asset_id = ?").use { query ->
            query.setString(1, userId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

    // ---- Tickets: links that open the connection page ----

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** A new link token for the connection page of this company, valid for 30 minutes. */
    fun newTicket(tenant: Connection, createdBy: String, now: Instant = Instant.now()): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tenant.prepareStatement("DELETE FROM crm_meta_tickets WHERE expires_at < ?").use { it.setTimestamp(1, Timestamp.from(now)); it.executeUpdate() }
        tenant.prepareStatement("INSERT INTO crm_meta_tickets (token_hash, created_by, expires_at) VALUES (?, ?, ?)").use { insert ->
            insert.setString(1, hash(ticket))
            insert.setString(2, createdBy.take(200))
            insert.setTimestamp(3, Timestamp.from(now.plus(ticketLifetime)))
            insert.executeUpdate()
        }
        return ticket
    }

    fun validTicket(tenant: Connection, ticket: String?, now: Instant = Instant.now()): Boolean {
        if (ticket.isNullOrBlank() || ticket.length > 100) return false
        return tenant.prepareStatement("SELECT 1 FROM crm_meta_tickets WHERE token_hash = ? AND expires_at > ?").use { query ->
            query.setString(1, hash(ticket))
            query.setTimestamp(2, Timestamp.from(now))
            query.executeQuery().use { it.next() }
        }
    }

    /** A link token is for one connection: it is deleted once a step connects (see the route). */
    fun consumeTicket(tenant: Connection, ticket: String?) {
        if (ticket.isNullOrBlank() || ticket.length > 100) return
        tenant.prepareStatement("DELETE FROM crm_meta_tickets WHERE token_hash = ?").use { it.setString(1, hash(ticket)); it.executeUpdate() }
    }

    fun connectUrl(publicBase: String, company: String, ticket: String) = "$publicBase$ConnectPath/$company?t=$ticket"

    // ---- Facebook login ----

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    // GET without bearer token (OAuth endpoints take the app credentials as parameters).
    private fun get(url: String): Pair<Int, JsonObject> {
        val response = try {
            http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (failure: Exception) {
            throw CrmMetaException("No se pudo contactar la API de Meta.")
        }
        return response.statusCode() to (runCatching { json.parseToJsonElement(response.body()) }.getOrNull().jObj() ?: JsonObject(emptyMap()))
    }

    /** Exchanges the code of the login dialog (JS SDK) for a token of the person or of the business. */
    fun exchangeCode(app: CrmMetaServerApp, code: String): String {
        if (code.isBlank() || code.length > 2000) throw CrmMetaException("Meta no devolvio el codigo de autorizacion.")
        val base = "${CrmMeta.Graph}/oauth/access_token?client_id=${app.appId}&client_secret=${encode(app.appSecret.orEmpty())}&code=${encode(code)}"
        var (status, body) = get(base)
        // Codes of the JS SDK are sometimes bound to an empty redirect_uri.
        if (status != 200 && CrmMeta.errorMessage(body).contains("redirect_uri", ignoreCase = true)) {
            val retry = get("$base&redirect_uri=")
            status = retry.first
            body = retry.second
        }
        val token = body["access_token"].jStr()
        if (status != 200 || token.isEmpty()) throw CrmMetaException("Meta no acepto la autorizacion: ${CrmMeta.graphHint(body, status)}")
        return token
    }

    // A user token becomes long-lived (its page tokens then do not expire); business tokens are kept as they are.
    private fun longLived(app: CrmMetaServerApp, token: String): String {
        val (status, body) = get(
            "${CrmMeta.Graph}/oauth/access_token?grant_type=fb_exchange_token&client_id=${app.appId}" +
                "&client_secret=${encode(app.appSecret.orEmpty())}&fb_exchange_token=${encode(token)}",
        )
        return body["access_token"].jStr().takeIf { status == 200 && it.isNotEmpty() } ?: token
    }

    data class PageChoice(val id: String, val name: String, val instagram: String)

    /** Result of a connection step for the connection page. */
    data class Outcome(val ok: Boolean, val message: String, val pages: List<PageChoice> = emptyList())

    private fun pagesOf(token: String): List<JsonObject> {
        val (status, body) = CrmMeta.request("${CrmMeta.Graph}/me/accounts?fields=id,name,access_token,instagram_business_account%7Bid,username%7D&limit=100", token)
        if (status != 200) throw CrmMetaException("Meta rechazo la lista de paginas: ${CrmMeta.graphHint(body, status)}")
        return body["data"].jArr().mapNotNull { it.jObj() }.filter { numericId.matches(it["id"].jStr()) }
    }

    /**
     * Messenger and Instagram: exchanges the login code, keeps the token and connects the only page,
     * or returns the pages to choose from.
     */
    fun connectLogin(tenant: Connection, company: String, app: CrmMetaServerApp, code: String): Outcome {
        val token = longLived(app, exchangeCode(app, code))
        val (meStatus, me) = CrmMeta.request("${CrmMeta.Graph}/me?fields=id", token)
        val userId = me["id"].jStr().takeIf { meStatus == 200 && numericId.matches(it) }
        val saved = CrmMeta.load(tenant)
        CrmMeta.save(tenant, saved.copy(connectionMode = ServerMode, oauthToken = token, metaUserId = userId ?: saved.metaUserId))
        if (userId != null) withServerWorkspace { server -> claim(server, "user", userId, company) }
        val pages = pagesOf(token)
        CrmMeta.log(tenant, "login_meta", "${pages.size} pagina(s)")
        return when (pages.size) {
            0 -> Outcome(false, "La cuenta no administra ninguna pagina o no la elegiste al iniciar sesion. Vuelve a conectar y marca la pagina.")
            1 -> choosePage(tenant, company, pages.single()["id"].jStr())
            else -> Outcome(true, "Elige la pagina de la empresa.", pages.map {
                PageChoice(it["id"].jStr(), it["name"].jStr(), it["instagram_business_account"].jObj()?.get("username").jStr())
            })
        }
    }

    private const val NoServerWorkspace = "El espacio del servidor no tiene el CRM activo: pide al dueno del servidor que lo active."

    // The message never names the other company.
    private fun takenMessage(what: String) = "$what ya esta conectada a otra empresa de este servidor."

    /** True when [assetId] already belongs to another company (read only; claiming happens after the asset was verified and subscribed). */
    private fun isTaken(server: Connection, kind: String, assetId: String, company: String) = companyOf(server, kind, assetId)?.let { it != company } == true

    /**
     * Connects [pageId] (from the saved login token) and its Instagram account to this company. The page comes from the list the token
     * manages, so the token controls it; the routes are claimed only after Meta subscribed the app to the page, and a failing step
     * leaves nothing claimed.
     */
    fun choosePage(tenant: Connection, company: String, pageId: String): Outcome {
        val current = CrmMeta.load(tenant)
        val token = current.oauthToken?.takeIf { !CrmSecrets.isUnreadable(it) } ?: return Outcome(false, "Inicia sesion con Facebook otra vez.")
        val page = pagesOf(token).firstOrNull { it["id"].jStr() == pageId } ?: return Outcome(false, "La cuenta ya no administra esa pagina.")
        val pageToken = page["access_token"].jStr().ifEmpty { return Outcome(false, "Meta no devolvio el token de la pagina; revisa el permiso pages_messaging.") }
        val instagram = page["instagram_business_account"].jObj()
        val instagramId = instagram?.get("id").jStr().takeIf { numericId.matches(it) }
        // "" when free or already this company's.
        val taken = withServerWorkspace { server ->
            when {
                isTaken(server, "page", pageId, company) -> takenMessage("Esta pagina")
                instagramId != null && isTaken(server, "instagram", instagramId, company) -> takenMessage("Esta cuenta de Instagram")
                else -> ""
            }
        } ?: NoServerWorkspace
        if (taken.isNotEmpty()) return Outcome(false, taken)
        // The page must subscribe the app: the app webhook alone does not send its messages.
        val (subStatus, subBody) = CrmMeta.request("${CrmMeta.Graph}/$pageId/subscribed_apps?subscribed_fields=messages,standby", pageToken, "POST")
        if (subStatus !in 200..299 || subBody["success"].jStr() != "true") {
            CrmMeta.log(tenant, "pagina_error", CrmMeta.graphHint(subBody, subStatus))
            return Outcome(false, "Meta no suscribio la pagina: ${CrmMeta.graphHint(subBody, subStatus)}.")
        }
        val claimed = withServerWorkspace { server ->
            if (claim(server, "page", pageId, company) != null) return@withServerWorkspace takenMessage("Esta pagina")
            // The previous page of this company stops arriving here.
            server.prepareStatement("DELETE FROM crm_meta_routes WHERE company_code = ? AND kind = 'page' AND asset_id <> ?").use { delete ->
                delete.setString(1, company)
                delete.setString(2, pageId)
                delete.executeUpdate()
            }
            ""
        } ?: NoServerWorkspace
        if (claimed.isNotEmpty()) return Outcome(false, claimed)
        var settings = current.copy(
            enabled = true, connectionMode = ServerMode, sharedTokens = true,
            pageId = pageId, pageName = page["name"].jStr(), pageToken = pageToken,
            instagramAccountId = instagramId, instagramUsername = instagram?.get("username").jStr().ifEmpty { null },
        )
        try {
            CrmMeta.save(tenant, settings)
        } catch (failure: Exception) {
            withServerWorkspace { server -> release(server, company, setOf("page")) }
            throw failure
        }
        val done = mutableListOf("Pagina ${settings.pageName} conectada")
        CrmMeta.log(tenant, "pagina_conectada", settings.pageName.orEmpty())
        if (instagramId != null) {
            var instagramOk = false
            try {
                CrmMeta.subscribeInstagram(settings)
                // Instagram routes are claimed only once the subscription worked.
                val instagramClaim = withServerWorkspace { server ->
                    if (claim(server, "instagram", instagramId, company) != null) return@withServerWorkspace takenMessage("Esta cuenta de Instagram")
                    server.prepareStatement("DELETE FROM crm_meta_routes WHERE company_code = ? AND kind = 'instagram' AND asset_id <> ?").use { delete ->
                        delete.setString(1, company)
                        delete.setString(2, instagramId)
                        delete.executeUpdate()
                    }
                    ""
                } ?: NoServerWorkspace
                if (instagramClaim.isEmpty()) {
                    instagramOk = true
                    CrmMeta.log(tenant, "instagram_suscrito", instagramId)
                    done += "Instagram ${settings.instagramUsername?.let { "@$it" } ?: instagramId} recibe mensajes"
                } else done += instagramClaim
            } catch (failure: CrmMetaException) {
                CrmMeta.log(tenant, "instagram_error", failure.message.orEmpty())
                done += failure.message.orEmpty()
            }
            if (!instagramOk) {
                // Without its route and subscription the account would not receive anything: it is not kept as connected.
                CrmMeta.save(tenant, settings.copy(instagramAccountId = null, instagramUsername = null))
                withServerWorkspace { server -> release(server, company, setOf("instagram")) }
            }
        } else {
            done += "sin Instagram vinculado a la pagina"
            withServerWorkspace { server -> release(server, company, setOf("instagram")) }
        }
        settings = CrmMeta.load(tenant)
        return Outcome(settings.ready("messenger"), done.joinToString(". ") + ".")
    }

    /**
     * WhatsApp Embedded Signup: the business token of the code, the WhatsApp Business Account and the
     * number chosen in the dialog. First the token must prove it controls that account and number (the number is in the account's list);
     * the route is claimed only after Meta accepted the subscription. Numbers already on the Cloud API (coexistence included)
     * are never registered again; a new number is registered with the two-step [pin].
     */
    fun connectWhatsApp(tenant: Connection, company: String, app: CrmMetaServerApp, code: String, wabaId: String, phoneNumberId: String, pin: String?): Outcome {
        if (!numericId.matches(wabaId) || !numericId.matches(phoneNumberId)) return Outcome(false, "Meta no devolvio la cuenta o el numero de WhatsApp; termina el registro en la ventana de Meta.")
        val token = exchangeCode(app, code)
        val (listStatus, list) = CrmMeta.request("${CrmMeta.Graph}/$wabaId/phone_numbers?fields=id&limit=100", token)
        if (listStatus != 200 || list["data"].jArr().none { it.jObj()?.get("id").jStr() == phoneNumberId }) {
            CrmMeta.log(tenant, "wa_no_verificado", "la cuenta de Meta no administra ese numero")
            return Outcome(false, "Tu cuenta de Meta no administra esa cuenta o ese numero de WhatsApp.")
        }
        val taken = withServerWorkspace { server -> if (isTaken(server, "waba", wabaId, company)) takenMessage("Esta cuenta de WhatsApp") else "" } ?: NoServerWorkspace
        if (taken.isNotEmpty()) return Outcome(false, taken)
        val (phoneStatus, phone) = CrmMeta.request(
            "${CrmMeta.Graph}/$phoneNumberId?fields=display_phone_number,verified_name,status,platform_type,is_on_biz_app", token,
        )
        if (phoneStatus != 200) return Outcome(false, "Meta rechazo el numero: ${CrmMeta.graphHint(phone, phoneStatus)}")
        val number = phone["display_phone_number"].jStr()
        val onCloud = phone["platform_type"].jStr().equals("CLOUD_API", ignoreCase = true) || phone["is_on_biz_app"].jTrue()
        if (!onCloud) {
            val digits = pin.orEmpty().trim()
            if (!digits.matches(Regex("^[0-9]{6}$"))) {
                return Outcome(false, "El numero $number es nuevo en la API: escribe un PIN de 6 digitos para la verificacion en dos pasos y conecta otra vez.")
            }
            val (regStatus, reg) = CrmMeta.request("${CrmMeta.Graph}/$phoneNumberId/register", token, "POST", buildJsonObject {
                put("messaging_product", "whatsapp")
                put("pin", digits)
            })
            if (regStatus !in 200..299 || reg["success"].jStr() != "true") return Outcome(false, "Meta no registro el numero: ${CrmMeta.graphHint(reg, regStatus)}")
            CrmMeta.log(tenant, "wa_registrado", number)
        }
        val (subStatus, sub) = CrmMeta.request("${CrmMeta.Graph}/$wabaId/subscribed_apps", token, "POST")
        if (subStatus !in 200..299 || sub["success"].jStr() != "true") return Outcome(false, "Meta rechazo la suscripcion de la app: ${CrmMeta.graphHint(sub, subStatus)}")
        val claimed = withServerWorkspace { server ->
            if (claim(server, "waba", wabaId, company) != null) return@withServerWorkspace takenMessage("Esta cuenta de WhatsApp")
            server.prepareStatement("DELETE FROM crm_meta_routes WHERE company_code = ? AND kind = 'waba' AND asset_id <> ?").use { delete ->
                delete.setString(1, company)
                delete.setString(2, wabaId)
                delete.executeUpdate()
            }
            ""
        } ?: NoServerWorkspace
        if (claimed.isNotEmpty()) return Outcome(false, claimed)
        try {
            CrmMeta.save(
                tenant,
                CrmMeta.load(tenant).copy(enabled = true, connectionMode = ServerMode, systemToken = token, wabaId = wabaId, phoneNumberId = phoneNumberId),
            )
        } catch (failure: Exception) {
            withServerWorkspace { server -> release(server, company, setOf("waba")) }
            throw failure
        }
        CrmMeta.log(tenant, "wa_conectado", "WABA $wabaId, numero $number")
        return Outcome(true, "WhatsApp conectado con el numero $number${if (phone["is_on_biz_app"].jTrue()) " (coexistencia con la app de WhatsApp Business)" else ""}.")
    }

    private fun unsubscribe(tenant: Connection, assetId: String?, token: String?) {
        if (assetId.isNullOrBlank() || token.isNullOrBlank() || CrmSecrets.isUnreadable(token) || !numericId.matches(assetId)) return
        try {
            val (status, _) = CrmMeta.request("${CrmMeta.Graph}/$assetId/subscribed_apps", token, "DELETE")
            if (status !in 200..299) CrmMeta.log(tenant, "desuscripcion_error", "HTTP $status en $assetId")
        } catch (failure: Exception) {
            CrmMeta.log(tenant, "desuscripcion_error", failure.message ?: failure.javaClass.simpleName)
        }
    }

    /** Forgets every Meta token and asset of the company and stops routing its webhooks. */
    fun disconnect(tenant: Connection, company: String) {
        val settings = CrmMeta.load(tenant)
        // Best effort: Meta stops sending this page's and account's events to the app. A failure is only logged.
        unsubscribe(tenant, settings.pageId, settings.pageToken)
        unsubscribe(tenant, settings.wabaId, settings.systemToken)
        CrmMeta.save(
            tenant,
            settings.copy(
                enabled = false, systemToken = null, pageId = null, pageName = null, pageToken = null, instagramAccountId = null,
                instagramUsername = null, wabaId = null, phoneNumberId = null, oauthToken = null, metaUserId = null,
            ),
        )
        withServerWorkspace { server -> release(server, company) }
        CrmMeta.log(tenant, "desconectado", "app del servidor")
    }

    // ---- Webhook of the server app ----

    fun verify(app: CrmMetaServerApp, mode: String?, token: String?, challenge: String?): String? =
        CrmMeta.verify(CrmMetaSettings(true, null, app.verifyToken, null, null, null, null, null, null, null, true), mode, token, challenge)

    /**
     * Entries of a webhook body grouped by the company that connected their asset: page (object page),
     * Instagram account (object instagram) or WhatsApp Business Account (object whatsapp_business_account).
     * Each group is a body of its own, so it is parsed as if it came to the company's own URL.
     */
    fun split(body: String, companyOf: (kind: String, assetId: String) -> String?): Map<String, String> {
        val payload = runCatching { json.parseToJsonElement(body) }.getOrNull().jObj() ?: return emptyMap()
        val kind = when (payload["object"].jStr()) {
            "page" -> "page"
            "instagram" -> "instagram"
            "whatsapp_business_account" -> "waba"
            else -> return emptyMap()
        }
        val groups = linkedMapOf<String, MutableList<JsonElement>>()
        payload["entry"].jArr().forEach { entry ->
            val assetId = entry.jObj()?.get("id").jStr()
            if (!numericId.matches(assetId)) return@forEach
            val company = companyOf(kind, assetId) ?: return@forEach
            groups.getOrPut(company) { mutableListOf() } += entry
        }
        return groups.mapValues { (_, entries) -> JsonObject(payload + ("entry" to JsonArray(entries))).toString() }
    }

    /** Handles one call to the server webhook; returns the HTTP status for Meta. [enabledTenant] opens a company only when its "meta" extension is enabled. */
    fun dispatch(body: ByteArray, signature: String?, enabledTenant: (company: String) -> Connection?): Int {
        val routed = withServerWorkspace { server ->
            val app = loadApp(server)
            if (!app.enabled || !CrmMeta.validSignature(app.appSecret, signature, body)) {
                CrmMeta.log(server, "firma_rechazada", "app del servidor")
                return@withServerWorkspace null
            }
            split(body.decodeToString()) { kind, assetId -> companyOf(server, kind, assetId) }
        } ?: return 403
        routed.forEach { (company, part) ->
            runCatching {
                // Only companies that have the "meta" extension enabled receive the events of their page or number.
                enabledTenant(company)?.use { tenant ->
                    val settings = CrmMeta.load(tenant)
                    if (!settings.serverMode || !settings.enabled) {
                        CrmMeta.log(tenant, "ignorado", "llego por la app del servidor sin estar conectada a ella")
                        return@use
                    }
                    try {
                        val stored = CrmMeta.handle(tenant, settings, CrmMeta.parse(part, settings.instagramAccountId)) { leadId -> CrmNotify.newLead(tenant, leadId) }
                        CrmMeta.log(tenant, "evento", "guardados=$stored (app del servidor)")
                    } catch (failure: Exception) {
                        CrmMeta.log(tenant, "error", failure.message ?: failure.javaClass.simpleName)
                    }
                }
            }
        }
        // Meta disables subscriptions that keep failing: the answer is 200 once the signature is valid.
        return 200
    }

    // ---- Deauthorize and data deletion callbacks ----

    /** Meta user id of a valid signed_request (HMAC-SHA256 with the App Secret), or null. */
    fun signedUser(appSecret: String?, signedRequest: String?): String? {
        if (appSecret.isNullOrBlank() || signedRequest.isNullOrBlank()) return null
        val parts = signedRequest.split('.')
        if (parts.size != 2) return null
        val decoder = Base64.getUrlDecoder()
        val signature = runCatching { decoder.decode(parts[0]) }.getOrNull() ?: return null
        val expected = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }.doFinal(parts[1].toByteArray())
        if (!MessageDigest.isEqual(signature, expected)) return null
        val payload = runCatching { json.parseToJsonElement(String(decoder.decode(parts[1]))) }.getOrNull().jObj() ?: return null
        return payload["user_id"].jStr().takeIf { numericId.matches(it) }
    }

    /** Disconnects every company the person connected; returns how many. */
    fun forgetUser(signedRequest: String?): Int? {
        val service = access ?: return null
        val companies = withServerWorkspace { server ->
            val userId = signedUser(loadApp(server).appSecret, signedRequest) ?: return@withServerWorkspace null
            companiesOfUser(server, userId)
        } ?: return null
        companies.forEach { company ->
            runCatching { service.tenantConnectionForWebhook(company, CrmModule.id)?.use { tenant -> disconnect(tenant, company) } }
        }
        return companies.size
    }

    fun confirmationCode(): String = ByteArray(9).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    // ---- Connection page ----

    private fun html(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun js(value: String) = buildString {
        append('"')
        value.forEach { c ->
            when {
                c == '"' || c == '\\' -> append('\\').append(c)
                c == '<' || c == '>' || c == '&' || c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Page opened in the browser from Integraciones: logs in with Meta through the JS SDK of the server app. */
    fun connectPage(app: CrmMetaServerApp, company: String, ticket: String): String = """
        <!doctype html>
        <html lang="es">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="no-referrer">
        <title>Conectar con Meta</title>
        <style>
          body { font-family: system-ui, sans-serif; margin: 0; background: #f4f5f7; color: #1d2433; }
          main { max-width: 560px; margin: 0 auto; padding: 24px 16px; }
          section { background: #fff; border: 1px solid #dde1e8; border-radius: 14px; padding: 18px; margin: 16px 0; }
          h1 { font-size: 22px; } h2 { font-size: 17px; margin: 0 0 8px; }
          p, label { font-size: 14px; line-height: 1.45; }
          button { background: #1877f2; color: #fff; border: 0; border-radius: 8px; padding: 10px 16px; font-size: 15px; cursor: pointer; }
          button[disabled] { opacity: .5; cursor: default; }
          input[type=text] { font-size: 15px; padding: 8px; width: 9em; letter-spacing: .2em; }
          .page { display: block; margin: 8px 0; background: #fff; color: #1877f2; border: 1px solid #1877f2; }
          #msg { font-weight: 600; white-space: pre-line; }
          .ok { color: #137333; } .bad { color: #b3261e; }
        </style>
        </head>
        <body>
        <main>
          <h1>Conectar ${html(company)} con Meta</h1>
          <p>Inicia sesion con la cuenta de Facebook que administra la pagina, el Instagram y el WhatsApp de la empresa. Meta te muestra que permisos das; esta pagina nunca ve tu contrasena.</p>
          <section id="pagesBox">
            <h2>Messenger e Instagram</h2>
            <p>Elige la pagina de la empresa. Su cuenta de Instagram vinculada se conecta con ella.</p>
            <button id="pages">Conectar Facebook e Instagram</button>
            <div id="pageList"></div>
          </section>
          <section id="waBox">
            <h2>WhatsApp</h2>
            <p>Elige o crea la cuenta de WhatsApp Business y el numero. Si el numero ya funciona con otro sistema o con la app de WhatsApp Business, eligelo tal cual: no se vuelve a registrar.</p>
            <p><label><input type="checkbox" id="coex"> Agregar un numero que hoy solo usa la app de WhatsApp Business (coexistencia)</label></p>
            <p><label>PIN de verificacion en dos pasos, solo para numeros nuevos en la API (6 digitos):<br><input type="text" id="pin" inputmode="numeric" maxlength="6" autocomplete="off"></label></p>
            <button id="wa">Conectar WhatsApp</button>
          </section>
          <p id="msg"></p>
          <p>Al terminar vuelve a la app y abre otra vez Integraciones. Este enlace vence en 30 minutos.</p>
        </main>
        <script>
        const cfg = { appId: ${js(app.appId.orEmpty())}, pages: ${js(app.pagesConfigId.orEmpty())}, wa: ${js(app.whatsAppConfigId.orEmpty())}, ticket: ${js(ticket)}, version: ${js(CrmMeta.GraphVersion)} };
        const msg = document.getElementById('msg');
        function show(text, ok) { msg.textContent = text; msg.className = ok ? 'ok' : 'bad'; }
        if (!cfg.pages) document.getElementById('pagesBox').style.display = 'none';
        if (!cfg.wa) document.getElementById('waBox').style.display = 'none';
        async function send(step, data) {
          show('Conectando...', true);
          try {
            const response = await fetch(location.pathname + '/' + step, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(Object.assign({ t: cfg.ticket }, data)) });
            const result = await response.json();
            show(result.message || 'Sin respuesta del servidor.', !!result.ok);
            return result;
          } catch (e) { show('No se pudo contactar el servidor.', false); return {}; }
        }
        let signup = null;
        window.addEventListener('message', (event) => {
          if (!/(^|\.)facebook\.com$/.test(new URL(event.origin).hostname)) return;
          try {
            const data = JSON.parse(event.data);
            if (data.type === 'WA_EMBEDDED_SIGNUP') {
              if (String(data.event).startsWith('FINISH')) signup = data.data || {};
              else if (data.event === 'CANCEL') show('Registro de WhatsApp cancelado' + (data.data && data.data.current_step ? ' en ' + data.data.current_step : '') + '.', false);
            }
          } catch (e) {}
        });
        window.fbAsyncInit = function () {
          FB.init({ appId: cfg.appId, autoLogAppEvents: true, xfbml: false, version: cfg.version });
        };
        document.getElementById('pages').onclick = () => {
          FB.login(async (response) => {
            const code = response.authResponse && response.authResponse.code;
            if (!code) return show('No se completo el inicio de sesion.', false);
            const result = await send('pages', { code });
            const list = document.getElementById('pageList');
            list.innerHTML = '';
            (result.pages || []).forEach((page) => {
              const button = document.createElement('button');
              button.className = 'page';
              button.textContent = page.name + (page.instagram ? ' (Instagram @' + page.instagram + ')' : '');
              button.onclick = async () => { const done = await send('page', { pageId: page.id }); if (done.ok) list.innerHTML = ''; };
              list.appendChild(button);
            });
          }, { config_id: cfg.pages, response_type: 'code', override_default_response_type: true });
        };
        document.getElementById('wa').onclick = () => {
          signup = null;
          const coex = document.getElementById('coex').checked;
          FB.login(async (response) => {
            const code = response.authResponse && response.authResponse.code;
            if (!code) return show('No se completo el registro de WhatsApp.', false);
            // The account and number arrive by message right before or after the login answer.
            for (let i = 0; i < 20 && !signup; i++) await new Promise((r) => setTimeout(r, 250));
            const data = signup || {};
            await send('whatsapp', { code, wabaId: String(data.waba_id || ''), phoneNumberId: String(data.phone_number_id || ''), pin: document.getElementById('pin').value });
          }, { config_id: cfg.wa, response_type: 'code', override_default_response_type: true,
               extras: { setup: {}, featureType: coex ? 'whatsapp_business_app_onboarding' : '', sessionInfoVersion: '3' } });
        };
        </script>
        <script async defer crossorigin="anonymous" src="https://connect.facebook.net/es_LA/sdk.js"></script>
        </body>
        </html>
    """.trimIndent()
}
