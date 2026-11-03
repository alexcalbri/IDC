package com.ideasdeveloper.idc.modules.agentes

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.RegisteredMcpTool
import com.ideasdeveloper.idc.server.modules.VerifiedUser
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Duration
import java.util.Base64

/**
 * OAuth 2.1 authorization server for MCP clients that connect through OAuth, such as custom
 * connectors on claude.ai (MCP authorization spec, 2025-06-18):
 *
 * - Protected resource metadata (RFC 9728) and authorization server metadata (RFC 8414) under
 *   /.well-known/, which Nginx proxies to the backend.
 * - Dynamic client registration (RFC 7591), stateless (see [OAuthClients]).
 * - Authorization code with PKCE S256: the user signs in with company, user and password, then
 *   chooses what the agent may use, as when creating a token by hand.
 * - Tokens: every authorization is an agent connection (agent_tokens) that the user sees in
 *   "Mis agentes" and can revoke. Access tokens last one hour; refresh tokens rotate on each use.
 */
object AgentOAuth {
    const val Base = "/modules/agentes/oauth"
    const val ProtectedResourcePath = "/.well-known/oauth-protected-resource"
    const val AuthorizationServerPath = "/.well-known/oauth-authorization-server"
    const val Scope = "mcp"
    val AccessLifetime: Duration = Duration.ofHours(1)

    private val log = LoggerFactory.getLogger(AgentOAuth::class.java)
    private const val KeySetting = "agentes.oauth.signing_key"
    private val store = OAuthStore()
    private val signInLimiter = AgentRateLimiter(perMinute = 10)
    private val clientLimiter = AgentRateLimiter(perMinute = 30)

    @Volatile
    private var cachedKey: ByteArray? = null

    /** HMAC key of the server, created on first use and kept in server_settings so client ids survive restarts. */
    private fun signingKey(access: ModuleAccessService): ByteArray {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val generated = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            val stored = access.centralConnection().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO server_settings (setting_key, setting_value) VALUES (?, ?) ON CONFLICT (setting_key) DO NOTHING"
                ).use { insert ->
                    insert.setString(1, KeySetting)
                    insert.setString(2, generated)
                    insert.executeUpdate()
                }
                connection.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
                    query.setString(1, KeySetting)
                    query.executeQuery().use { rows -> rows.next(); rows.getString(1) }
                }
            }
            return Base64.getDecoder().decode(stored).also { cachedKey = it }
        }
    }

    fun protectedResourceMetadata(base: String): JsonObject = buildJsonObject {
        put("resource", base + AgentesScreens.McpRoute)
        putJsonArray("authorization_servers") { add(JsonPrimitive(base)) }
        putJsonArray("scopes_supported") { add(JsonPrimitive(Scope)) }
        putJsonArray("bearer_methods_supported") { add(JsonPrimitive("header")) }
        put("resource_name", "IdeasCore")
    }

    fun authorizationServerMetadata(base: String): JsonObject = buildJsonObject {
        put("issuer", base)
        put("authorization_endpoint", "$base$Base/authorize")
        put("token_endpoint", "$base$Base/token")
        put("registration_endpoint", "$base$Base/register")
        put("revocation_endpoint", "$base$Base/revoke")
        putJsonArray("response_types_supported") { add(JsonPrimitive("code")) }
        putJsonArray("grant_types_supported") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
        putJsonArray("code_challenge_methods_supported") { add(JsonPrimitive("S256")) }
        putJsonArray("token_endpoint_auth_methods_supported") { OAuthClients.AuthMethods.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("revocation_endpoint_auth_methods_supported") { OAuthClients.AuthMethods.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("scopes_supported") { add(JsonPrimitive(Scope)) }
    }

    fun routes(route: Route, access: ModuleAccessService) {
        // Clients may append the resource path (".../oauth-protected-resource/modules/agentes/mcp").
        listOf(ProtectedResourcePath, "$ProtectedResourcePath/{path...}").forEach { path ->
            route.get(path) { call.metadata(access) { protectedResourceMetadata(it) } }
            route.options(path) { call.preflight() }
        }
        listOf(AuthorizationServerPath, "$AuthorizationServerPath/{path...}").forEach { path ->
            route.get(path) { call.metadata(access) { authorizationServerMetadata(it) } }
            route.options(path) { call.preflight() }
        }

        route.route(Base) {
            post("/register") { call.guarded(access, clientLimiter) { register(access) } }
            options("/register") { call.preflight() }
            get("/authorize") { call.guarded(access, null) { authorizeStart(access) } }
            post("/authorize") { call.guarded(access, null) { authorizeSubmit(access) } }
            post("/token") { call.guarded(access, clientLimiter) { token(access) } }
            options("/token") { call.preflight() }
            post("/revoke") { call.guarded(access, clientLimiter) { revoke(access) } }
            options("/revoke") { call.preflight() }
        }
    }

    // ---- Plumbing ----

    private suspend fun ApplicationCall.guarded(access: ModuleAccessService, limiter: AgentRateLimiter?, block: suspend ApplicationCall.() -> Unit) {
        if (!access.isInstalled(AgentesModule.id)) return respond(HttpStatusCode.NotFound)
        cors()
        if (limiter != null && !limiter.allow(clientIp())) {
            response.header(HttpHeaders.RetryAfter, "60")
            return respondOAuthError(HttpStatusCode.TooManyRequests, OAuthError("slow_down", "Demasiadas solicitudes; espera un minuto."))
        }
        try {
            block()
        } catch (failure: SQLException) {
            log.warn("OAuth request failed: {}", failure.message)
            respondOAuthError(HttpStatusCode.ServiceUnavailable, OAuthError("temporarily_unavailable", "No se pudo completar la operacion."))
        }
    }

    private suspend fun ApplicationCall.metadata(access: ModuleAccessService, build: (String) -> JsonObject) {
        if (!access.isInstalled(AgentesModule.id)) return respond(HttpStatusCode.NotFound)
        cors()
        respondText(build(publicBase()).toString(), ContentType.Application.Json)
    }

    // Browser-based MCP clients read metadata and exchange tokens cross-origin; none of these use cookies.
    private fun ApplicationCall.cors() {
        response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type, MCP-Protocol-Version")
        response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
    }

    private suspend fun ApplicationCall.preflight() {
        cors()
        respond(HttpStatusCode.NoContent)
    }

    private suspend fun ApplicationCall.respondOAuthError(status: HttpStatusCode, error: OAuthError) {
        response.header(HttpHeaders.CacheControl, "no-store")
        respondText(
            buildJsonObject {
                put("error", error.error)
                put("error_description", error.description)
            }.toString(),
            ContentType.Application.Json,
            status,
        )
    }

    // ---- Registration ----

    private suspend fun ApplicationCall.register(access: ModuleAccessService) {
        val request = runCatching { Json.parseToJsonElement(receiveText()) as JsonObject }.getOrNull()
            ?: return respondOAuthError(HttpStatusCode.BadRequest, OAuthError("invalid_client_metadata", "El cuerpo debe ser un objeto JSON."))
        val key = withContext(Dispatchers.IO) { signingKey(access) }
        val (client, secret) = try {
            OAuthClients.register(key, request)
        } catch (failure: OAuthError) {
            return respondOAuthError(HttpStatusCode.BadRequest, failure)
        }
        val body = buildJsonObject {
            put("client_id", client.id)
            put("client_id_issued_at", System.currentTimeMillis() / 1000)
            put("client_name", client.name)
            putJsonArray("redirect_uris") { client.redirectUris.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", client.authMethod)
            put("scope", Scope)
            if (secret != null) {
                put("client_secret", secret)
                put("client_secret_expires_at", 0)
            }
        }
        response.header(HttpHeaders.CacheControl, "no-store")
        respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.Created)
    }

    // ---- Authorization ----

    // Either a validated request, or how to reject it: a page when the redirect cannot be trusted, else a redirect.
    private sealed class Validation {
        data class Valid(val request: OAuthRequest) : Validation()
        data class Page(val message: String) : Validation()
        data class Redirect(val url: String) : Validation()
    }

    private fun validate(key: ByteArray, params: Parameters): Validation {
        val client = params["client_id"]?.let { OAuthClients.parse(key, it) }
            ?: return Validation.Page("El cliente no esta registrado en este servidor. Vuelve a agregar el conector.")
        val redirectUri = params["redirect_uri"] ?: client.redirectUris.singleOrNull()
            ?: return Validation.Page("Falta redirect_uri.")
        if (redirectUri !in client.redirectUris) return Validation.Page("La direccion de retorno no pertenece a este cliente.")
        val state = params["state"]
        fun fail(error: String, description: String) = Validation.Redirect(redirect(redirectUri, mapOf("error" to error, "error_description" to description, "state" to state)))
        if (params["response_type"] != "code") return fail("unsupported_response_type", "Solo se admite response_type=code.")
        val challenge = params["code_challenge"].orEmpty()
        if (params["code_challenge_method"] != "S256" || !Regex("[A-Za-z0-9_-]{43}").matches(challenge)) {
            return fail("invalid_request", "Se requiere PKCE con code_challenge_method=S256.")
        }
        // RFC 8707: tokens of this server are only for its MCP endpoint.
        params["resource"]?.let { resource ->
            val path = runCatching { URI(resource).path }.getOrNull()?.trimEnd('/')
            if (path != AgentesScreens.McpRoute) return fail("invalid_target", "Este servidor solo emite tokens para ${AgentesScreens.McpRoute}.")
        }
        return Validation.Valid(OAuthRequest(client, redirectUri, state, challenge))
    }

    private suspend fun ApplicationCall.authorizeStart(access: ModuleAccessService) {
        val key = withContext(Dispatchers.IO) { signingKey(access) }
        when (val validation = validate(key, request.queryParameters)) {
            is Validation.Page -> respondPage(HttpStatusCode.BadRequest, OAuthPages.error(validation.message))
            is Validation.Redirect -> respondRedirect(validation.url)
            is Validation.Valid -> respondPage(HttpStatusCode.OK, OAuthPages.signIn(validation.request, request.queryParameters, error = null, company = ""))
        }
    }

    private suspend fun ApplicationCall.authorizeSubmit(access: ModuleAccessService) {
        val form = receiveParameters()
        when (form["step"]) {
            "signin" -> signIn(access, form)
            "consent", "cancel" -> consent(access, form)
            else -> respondPage(HttpStatusCode.BadRequest, OAuthPages.error("Solicitud invalida."))
        }
    }

    private suspend fun ApplicationCall.signIn(access: ModuleAccessService, form: Parameters) {
        val key = withContext(Dispatchers.IO) { signingKey(access) }
        val request = when (val validation = validate(key, form)) {
            is Validation.Page -> return respondPage(HttpStatusCode.BadRequest, OAuthPages.error(validation.message))
            is Validation.Redirect -> return respondRedirect(validation.url)
            is Validation.Valid -> validation.request
        }
        val company = form["company"].orEmpty().trim().lowercase()
        fun again(message: String) = OAuthPages.signIn(request, form, message, company)
        if (!signInLimiter.allow(clientIp())) return respondPage(HttpStatusCode.TooManyRequests, again("Demasiados intentos. Espera un minuto."))
        val username = form["username"].orEmpty().trim()
        val password = form["password"].orEmpty()
        if (username.isEmpty() || password.isEmpty()) return respondPage(HttpStatusCode.OK, again("Escribe tu usuario y contrasena."))
        if (company.isNotEmpty() && !Regex("[a-z][a-z0-9_]{0,62}").matches(company)) return respondPage(HttpStatusCode.OK, again("El codigo de empresa no es valido."))

        val result = withContext(Dispatchers.IO) {
            val user = access.verifyCredentials(company.ifEmpty { null }, username, password)
                ?: return@withContext SignInResult.Rejected("Usuario, contrasena o empresa incorrectos.")
            val tools = eligibility(access, user) ?: return@withContext SignInResult.Rejected(
                "Tu usuario no puede conectar agentes en esta empresa: el modulo Agentes IA debe estar activo y necesitas el permiso \"Conectar agentes IA\".",
            )
            SignInResult.Accepted(user, tools)
        }
        when (result) {
            is SignInResult.Rejected -> respondPage(HttpStatusCode.OK, again(result.message))
            is SignInResult.Accepted -> {
                val ticket = store.newTicket(OAuthTicket(request, result.user))
                respondPage(HttpStatusCode.OK, OAuthPages.consent(ticket, request, result.user, result.tools, error = null))
            }
        }
    }

    private sealed class SignInResult {
        data class Rejected(val message: String) : SignInResult()
        data class Accepted(val user: VerifiedUser, val tools: List<RegisteredMcpTool>) : SignInResult()
    }

    /** Tools the user could give an agent, or null when the user may not connect agents in that company. */
    private fun eligibility(access: ModuleAccessService, user: VerifiedUser): List<RegisteredMcpTool>? {
        val tenant = access.tenantConnectionForCompany(user.companyCode) ?: return null
        return tenant.use { connection ->
            if (!AgentTokens.moduleEnabled(connection)) return@use null
            val userAccess = access.userAccess(connection, user.companyCode, user.userId, user.serverOwner) ?: return@use null
            if (userAccess.permissions(AgentesModule.id)?.has(AgentesScreens.Connect) != true) return@use null
            access.mcpTools().filter(userAccess::mayUse)
        }
    }

    private suspend fun ApplicationCall.consent(access: ModuleAccessService, form: Parameters) {
        val ticketId = form["ticket"].orEmpty()
        val ticket = store.ticket(ticketId) ?: return respondPage(HttpStatusCode.BadRequest, OAuthPages.error("La autorizacion vencio. Vuelve a intentarlo desde tu aplicacion."))
        val request = ticket.request
        if (form["step"] == "cancel") {
            store.dropTicket(ticketId)
            return respondRedirect(redirect(request.redirectUri, mapOf("error" to "access_denied", "error_description" to "El usuario cancelo.", "state" to request.state)))
        }
        val tools = withContext(Dispatchers.IO) { eligibility(access, ticket.user) }
            ?: return respondPage(HttpStatusCode.Forbidden, OAuthPages.error("Tu usuario ya no puede conectar agentes en esta empresa."))
        val modules = tools.map { it.moduleId }.distinct()
        val values = form.entries().associate { it.key to it.value.firstOrNull().orEmpty() } + ("name" to request.client.name.take(80))
        val consent = try {
            if (modules.isEmpty()) throw AgentesValidationException("Aun no tienes funciones que un agente pueda usar.")
            AgentesScreens.parseNewToken(values, modules)
        } catch (failure: AgentesValidationException) {
            return respondPage(HttpStatusCode.OK, OAuthPages.consent(ticketId, request, ticket.user, tools, failure.message))
        }
        store.dropTicket(ticketId)
        val code = store.newCode(OAuthGrant(request, ticket.user, consent))
        respondRedirect(redirect(request.redirectUri, mapOf("code" to code, "state" to request.state, "iss" to publicBase())))
    }

    private fun redirect(uri: String, params: Map<String, String?>): String {
        val query = params.filterValues { it != null }.entries.joinToString("&") { (key, value) -> "$key=${value!!.encodeURLParameter()}" }
        return uri + (if ('?' in uri) "&" else "?") + query
    }

    private suspend fun ApplicationCall.respondPage(status: HttpStatusCode, html: String) {
        response.header(HttpHeaders.CacheControl, "no-store")
        // The sign-in page must not be framed by another site (clickjacking).
        response.header("X-Frame-Options", "DENY")
        response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' https: http://localhost:* http://127.0.0.1:*; frame-ancestors 'none'")
        response.header("Referrer-Policy", "no-referrer")
        respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)
    }

    // ---- Tokens ----

    // The client authenticated as RFC 6749 section 2.3 requires for its registered method.
    private fun ApplicationCall.authenticatedClient(key: ByteArray, form: Parameters): OAuthClient {
        val basic = request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Basic ", ignoreCase = true) }?.let { header ->
            runCatching { String(Base64.getDecoder().decode(header.substringAfter(' ').trim())) }.getOrNull()
                ?.split(':', limit = 2)?.takeIf { it.size == 2 }
                ?.let { (id, secret) -> java.net.URLDecoder.decode(id, Charsets.UTF_8) to java.net.URLDecoder.decode(secret, Charsets.UTF_8) }
        }
        val clientId = basic?.first ?: form["client_id"] ?: throw OAuthError("invalid_client", "Falta client_id.")
        val client = OAuthClients.parse(key, clientId) ?: throw OAuthError("invalid_client", "Cliente desconocido.")
        if (client.confidential && !OAuthClients.secretMatches(key, client.id, basic?.second ?: form["client_secret"])) {
            throw OAuthError("invalid_client", "Credenciales del cliente invalidas.")
        }
        return client
    }

    private suspend fun ApplicationCall.token(access: ModuleAccessService) {
        val form = receiveParameters()
        val key = withContext(Dispatchers.IO) { signingKey(access) }
        val result = try {
            val client = authenticatedClient(key, form)
            withContext(Dispatchers.IO) {
                when (form["grant_type"]) {
                    "authorization_code" -> exchangeCode(access, client, form)
                    "refresh_token" -> refresh(access, client, form)
                    else -> throw OAuthError("unsupported_grant_type", "grant_type no soportado.")
                }
            }
        } catch (failure: OAuthError) {
            if (failure.error == "invalid_client") response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"IdeasCore\"")
            return respondOAuthError(if (failure.error == "invalid_client") HttpStatusCode.Unauthorized else HttpStatusCode.BadRequest, failure)
        }
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header(HttpHeaders.Pragma, "no-cache")
        respondText(
            buildJsonObject {
                put("access_token", result.accessToken)
                put("token_type", "Bearer")
                put("expires_in", AccessLifetime.seconds)
                put("refresh_token", result.refreshToken)
                put("scope", Scope)
            }.toString(),
            ContentType.Application.Json,
        )
    }

    private fun exchangeCode(access: ModuleAccessService, client: OAuthClient, form: Parameters): OAuthTokens {
        val grant = store.consumeCode(form["code"].orEmpty()) ?: throw OAuthError("invalid_grant", "El codigo no es valido o ya vencio.")
        val request = grant.request
        if (request.client.id != client.id) throw OAuthError("invalid_grant", "El codigo pertenece a otro cliente.")
        if (form["redirect_uri"]?.let { it != request.redirectUri } == true) throw OAuthError("invalid_grant", "redirect_uri no coincide.")
        if (!Pkce.verify(form["code_verifier"], request.codeChallenge)) throw OAuthError("invalid_grant", "code_verifier invalido.")
        val user = grant.user
        val tenant = access.tenantConnectionForCompany(user.companyCode) ?: throw OAuthError("invalid_grant", "La empresa no esta disponible.")
        return tenant.use { connection ->
            val userAccess = access.userAccess(connection, user.companyCode, user.userId, user.serverOwner)
            if (!AgentTokens.moduleEnabled(connection) || userAccess?.permissions(AgentesModule.id)?.has(AgentesScreens.Connect) != true) {
                throw OAuthError("invalid_grant", "El usuario ya no puede conectar agentes.")
            }
            val owner = AgentOwner(user.userId, user.serverOwner, if (user.serverOwner) "Dueno del servidor" else user.username)
            AgentTokens.createOAuth(
                connection, user.companyCode, owner, client.id, client.name,
                grant.consent.allowedModules, grant.consent.readOnly, grant.consent.expiresAt, AccessLifetime,
            )
        }
    }

    private fun refresh(access: ModuleAccessService, client: OAuthClient, form: Parameters): OAuthTokens {
        val refreshToken = form["refresh_token"].orEmpty()
        val company = AgentTokens.companyOf(refreshToken, AgentTokens.RefreshPrefix) ?: throw OAuthError("invalid_grant", "refresh_token invalido.")
        val tenant = access.tenantConnectionForCompany(company) ?: throw OAuthError("invalid_grant", "La empresa no esta disponible.")
        return tenant.use { connection ->
            if (!AgentTokens.moduleEnabled(connection)) throw OAuthError("invalid_grant", "El modulo Agentes IA no esta activo.")
            val tokens = AgentTokens.refresh(connection, refreshToken, client.id, company, AccessLifetime)
                ?: throw OAuthError("invalid_grant", "El refresh_token no es valido, ya se uso o la conexion fue revocada.")
            // A user that lost access does not keep the connection alive.
            val token = tokens.token
            val allowed = access.userAccess(connection, company, token.userId, token.serverOwner)?.permissions(AgentesModule.id)?.has(AgentesScreens.Connect) == true
            if (!allowed) {
                AgentTokens.revoke(connection, token.id, "Sistema (el usuario perdio el acceso)")
                throw OAuthError("invalid_grant", "El usuario ya no puede conectar agentes.")
            }
            tokens
        }
    }

    // RFC 7009: always 200, whether or not the token existed.
    private suspend fun ApplicationCall.revoke(access: ModuleAccessService) {
        val form = receiveParameters()
        val key = withContext(Dispatchers.IO) { signingKey(access) }
        val clientId = try {
            authenticatedClient(key, form).id
        } catch (failure: OAuthError) {
            return respondOAuthError(HttpStatusCode.Unauthorized, failure)
        }
        val value = form["token"].orEmpty()
        val company = AgentTokens.companyOf(value) ?: AgentTokens.companyOf(value, AgentTokens.RefreshPrefix)
        if (company != null) {
            withContext(Dispatchers.IO) {
                access.tenantConnectionForCompany(company)?.use { connection ->
                    if (AgentTokens.moduleEnabled(connection)) AgentTokens.revokeByValue(connection, value, clientId, "Cliente OAuth")
                }
            }
        }
        respond(HttpStatusCode.OK)
    }
}
