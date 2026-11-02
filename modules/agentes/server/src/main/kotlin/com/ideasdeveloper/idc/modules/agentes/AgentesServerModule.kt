package com.ideasdeveloper.idc.modules.agentes

import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.UserModuleAccess
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiTone
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

/**
 * AI agents (MCP clients) connected by each user. The endpoint authenticates the agent token, loads
 * the permissions of the token's user at that moment and exposes only the tools of enabled modules
 * that the user may use, further narrowed by the token (modules, read only).
 */
object AgentesServerModule : ServerModule {
    private val log = LoggerFactory.getLogger(AgentesServerModule::class.java)
    private val rateLimiter = AgentRateLimiter(perMinute = 120)
    private const val ActivityDays = 180
    private const val MaxBodyBytes = 1_000_000

    override val migrationPaths = listOf(
        "modules/agentes/migrations/V001__create_agent_tokens.sql",
    )

    override val runtimeTables = listOf("agent_tokens", "agent_activity")

    // Its own MCP (managing connections), offered through the same bridge as every module.
    override val mcp: ModuleMcp
        get() = AgentesMcpTools.mcp

    override val definition = ModuleDefinition(
        id = AgentesModule.id,
        displayName = AgentesModule.displayName,
        description = "Cada usuario conecta su propio modelo o agente de IA por MCP, limitado a las funciones que ese usuario tiene permitidas.",
        version = "0.1.0",
        permissions = listOf(
            ModulePermissionDefinition(
                AgentesScreens.Connect,
                "Conectar agentes IA",
                "Permite conectar su propio modelo o agente de IA. El agente solo puede usar las funciones que el usuario tiene permitidas.",
            ),
            ModulePermissionDefinition(
                AgentesScreens.Manage,
                "Administrar agentes IA",
                "Permite ver y revocar los agentes que conecto cualquier usuario de la empresa, con su actividad.",
            ),
        ),
        ui = ModuleUiEntry(type = "native", entry = AgentesScreens.UiRoute, home = "home"),
    )

    override val jobs = listOf(
        ModuleJob("purge-activity", Duration.ofDays(1)) { context -> AgentTokens.purgeActivity(context.tenant, ActivityDays) },
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        AgentOAuth.routes(route, access)

        route.route(AgentesScreens.McpRoute) {
            post { call.handleMcp(access) }
            // No server-initiated stream: this server answers every request with JSON.
            get { call.methodNotAllowed() }
            delete { call.methodNotAllowed() }
        }

        route.route(AgentesScreens.UiRoute) {
            get("/{screen}") {
                val permissions = call.requireModuleAccess(access, AgentesModule.id) ?: return@get
                val screen = call.screenId(permissions) ?: return@get
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.respondScreen(access, permissions) { context -> AgentesScreens.build(screen, context, params) }
            }

            post("/{screen}") {
                val permissions = call.requireModuleAccess(access, AgentesModule.id) ?: return@post
                val screen = call.screenId(permissions) ?: return@post
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                val values = runCatching {
                    UiJson.parseToJsonElement(call.receiveText()).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
                }.getOrNull().orEmpty()
                if (!permissions.has(AgentesScreens.Connect) && !permissions.has(AgentesScreens.Manage)) {
                    return@post call.respond(HttpStatusCode.Forbidden, AgentesErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                call.respondScreen(access, permissions) { context -> action(context, screen, params, values) }
            }
        }
    }

    private fun action(context: AgentesScreens.Context, screen: String, params: Map<String, String>, values: Map<String, String>): UiScreen {
        fun show(target: String, notice: UiNotice, targetParams: Map<String, String> = params - "op", submitted: Map<String, String>? = null) =
            AgentesScreens.build(target, context, targetParams, notice, submitted)

        return when ("$screen:${params["op"]}") {
            "new:create" -> {
                if (!context.permissions.has(AgentesScreens.Connect)) return show("team", UiNotice("No tienes permiso para conectar agentes.", UiTone.Danger), emptyMap())
                val request = try {
                    AgentesScreens.parseNewToken(values, context.toolModules.map { it.first })
                } catch (failure: AgentesValidationException) {
                    return show("new", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                }
                val (token, value) = AgentTokens.create(
                    context.tenant, context.permissions.companyCode, context.owner,
                    request.name, request.allowedModules, request.readOnly, request.expiresAt,
                )
                AgentesScreens.created(context, token, value)
            }
            "token:revoke" -> {
                val token = params["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { AgentTokens.find(context.tenant, it) }
                    ?.takeIf { context.owns(it) && context.permissions.has(AgentesScreens.Connect) || context.canManage }
                    ?: return show(AgentesScreens.home(context.permissions), UiNotice("Ese agente no existe o no es tuyo.", UiTone.Warning), emptyMap())
                AgentTokens.revoke(context.tenant, token.id, context.owner.name)
                show("token", UiNotice("Acceso revocado. El agente ya no puede usar IdeasCore.", UiTone.Success))
            }
            else -> show(screen, UiNotice("Accion no reconocida.", UiTone.Warning))
        }
    }

    // ---- MCP endpoint ----

    private suspend fun ApplicationCall.handleMcp(access: ModuleAccessService) {
        if (!access.isInstalled(AgentesModule.id)) return respond(HttpStatusCode.NotFound)
        if (!sameOrigin()) return respond(HttpStatusCode.Forbidden, AgentesErrorResponse("invalid_origin", "Origen no permitido."))
        val token = request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            .orEmpty()
        val companyCode = AgentTokens.companyOf(token) ?: return unauthorized("Falta el token del agente o no es valido.")
        if (!rateLimiter.allow(AgentTokens.hash(token))) {
            response.header(HttpHeaders.RetryAfter, "60")
            return respond(HttpStatusCode.TooManyRequests, AgentesErrorResponse("rate_limited", "Demasiadas solicitudes; espera un minuto."))
        }
        val body = receiveText()
        if (body.length > MaxBodyBytes) return respond(HttpStatusCode.PayloadTooLarge)
        val message = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: return respondJson(HttpStatusCode.BadRequest, AgentesMcp.error(JsonNull, AgentesMcp.ParseError, "JSON invalido."))

        val outcome = withContext(Dispatchers.IO) {
            val tenant = access.tenantConnectionForCompany(companyCode) ?: return@withContext null
            tenant.use { connection ->
                try {
                    val session = authenticate(connection, access, companyCode, token) ?: return@use null
                    AgentTokens.touch(connection, session.token.id)
                    Result.success(session.protocol(connection, access).handle(message))
                } catch (failure: SQLException) {
                    log.warn("Agent request for company {} failed: {}", companyCode, failure.message)
                    Result.failure(failure)
                }
            }
        } ?: return unauthorized("El token no es valido, vencio, fue revocado o el usuario ya no tiene acceso.")

        val answer = outcome.getOrElse { return respond(HttpStatusCode.ServiceUnavailable, AgentesErrorResponse("tenant_error", "No se pudo abrir la empresa.")) }
        if (answer == null) respond(HttpStatusCode.Accepted) else respondJson(HttpStatusCode.OK, answer)
    }

    private class AgentSession(val token: AgentToken, val access: UserModuleAccess)

    /** The token's session, when the token is active, its user still active and allowed to connect agents. */
    private fun authenticate(tenant: Connection, access: ModuleAccessService, companyCode: String, value: String): AgentSession? {
        // Companies that never enabled the module have no token tables.
        if (!AgentTokens.moduleEnabled(tenant)) return null
        val token = AgentTokens.resolve(tenant, value) ?: return null
        val userAccess = access.userAccess(tenant, companyCode, token.userId, token.serverOwner) ?: return null
        val own = userAccess.permissions(AgentesModule.id) ?: return null
        if (!own.has(AgentesScreens.Connect)) return null
        return AgentSession(token, userAccess)
    }

    private fun AgentSession.protocol(tenant: Connection, access: ModuleAccessService): AgentesMcp {
        // Each module's own MCP (instructions and tools), filtered to what this agent may use.
        val modules = access.moduleMcp()
        val visible = AgentesMcp.visibleTools(modules.flatMap { it.tools }, token, this.access)
        return AgentesMcp(
            instructions = AgentesMcp.instructions(instructions(token, this.access.companyCode), modules, visible),
            tools = { visible },
            invoke = { tool, arguments -> runTool(tenant, access, token, tool, arguments) },
            denied = { name -> AgentTokens.log(tenant, token.id, name.ifBlank { "(sin nombre)" }, "denied", "Herramienta desconocida o sin permiso.") },
        )
    }

    private fun runTool(tenant: Connection, access: ModuleAccessService, token: AgentToken, tool: VisibleTool, arguments: JsonObject): ToolOutcome {
        val call = McpToolCall(arguments, tool.permissions, tenant, access, token.name)
        return try {
            val result: JsonElement = tool.registered.tool.handler(call)
            AgentTokens.log(tenant, token.id, tool.name, "ok")
            ToolOutcome.Success(result)
        } catch (failure: McpToolException) {
            AgentTokens.log(tenant, token.id, tool.name, "error", failure.message.orEmpty())
            ToolOutcome.Failure(failure.message.orEmpty())
        } catch (failure: Exception) {
            log.error("Agent tool {} failed for company {}", tool.name, tool.permissions.companyCode, failure)
            runCatching { AgentTokens.log(tenant, token.id, tool.name, "error", "Error interno") }
            ToolOutcome.Failure("No se pudo completar la operacion.")
        }
    }

    private fun instructions(token: AgentToken, companyCode: String): String =
        "Estas conectado a IdeasCore en nombre de ${token.userName} (empresa $companyCode). " +
            "Solo tienes las herramientas que ese usuario tiene permitidas" +
            (if (token.readOnly) ", en modo solo lectura" else "") +
            ". Si necesitas algo que no aparece en tus herramientas, pide al usuario que solicite el permiso a su administrador. " +
            "Todo lo que hagas queda registrado a nombre del usuario; confirma con el antes de crear, modificar o eliminar datos."

    // Browsers send Origin; a page on another site must not reach the endpoint with a user's token.
    private fun ApplicationCall.sameOrigin(): Boolean {
        val origin = request.header(HttpHeaders.Origin)?.takeIf { it.isNotBlank() && it != "null" } ?: return true
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        val host = (request.header("X-Forwarded-Host") ?: request.header(HttpHeaders.Host) ?: request.origin.serverHost)
            .substringBefore(',').trim().substringBefore(':')
        return originHost.equals(host, ignoreCase = true)
    }

    // Points OAuth clients (claude.ai) to the metadata that tells them how to get a token.
    private suspend fun ApplicationCall.unauthorized(message: String) {
        val metadata = publicBase() + AgentOAuth.ProtectedResourcePath + AgentesScreens.McpRoute
        response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"IdeasCore\", resource_metadata=\"$metadata\"")
        respond(HttpStatusCode.Unauthorized, AgentesErrorResponse("invalid_token", message))
    }

    private suspend fun ApplicationCall.methodNotAllowed() {
        response.header(HttpHeaders.Allow, "POST")
        respond(HttpStatusCode.MethodNotAllowed)
    }

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, element: JsonElement) =
        respondText(element.toString(), ContentType.Application.Json, status)

    // ---- Screens ----

    private suspend fun ApplicationCall.screenId(permissions: ModulePermissions): String? {
        val requested = parameters["screen"].orEmpty()
        val screen = if (requested == "home") AgentesScreens.home(permissions) else requested.takeIf { AgentesScreens.isScreen(it) }
        if (screen == null) respond(HttpStatusCode.NotFound, AgentesErrorResponse("UNKNOWN_SCREEN", "Seccion no encontrada."))
        return screen
    }

    private suspend fun ApplicationCall.respondScreen(access: ModuleAccessService, permissions: ModulePermissions, block: (AgentesScreens.Context) -> UiScreen) {
        val endpoint = publicBase() + AgentesScreens.McpRoute
        val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(permissions.companyCode) }
            ?: return respond(HttpStatusCode.Forbidden, AgentesErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
        try {
            val screen = connection.use { tenant ->
                withContext(Dispatchers.IO) {
                    val owner = owner(tenant, permissions)
                    val userAccess = access.userAccess(tenant, permissions.companyCode, owner.userId, owner.serverOwner)
                    val userTools = userAccess?.let { user ->
                        access.mcpTools().filter { registered -> user.permissions(registered.moduleId)?.has(registered.tool.permission) == true }
                    }.orEmpty()
                    block(AgentesScreens.Context(tenant, permissions, owner, userTools, endpoint))
                }
            }
            respondText(UiJson.encodeToString(UiScreen.serializer(), screen), ContentType.Application.Json)
        } catch (failure: SQLException) {
            log.warn("Agentes screen failed: {}", failure.message)
            respond(HttpStatusCode.InternalServerError, AgentesErrorResponse("DATABASE_ERROR", "No se pudo completar la operacion."))
        }
    }

    private fun owner(tenant: Connection, permissions: ModulePermissions): AgentOwner {
        val userId = permissions.userId ?: throw SQLException("Session without user")
        if (permissions.serverOwner) return AgentOwner(userId, serverOwner = true, name = "Dueno del servidor")
        val name = tenant.prepareStatement("SELECT postgres_role FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        return AgentOwner(userId, serverOwner = false, name = name ?: "Usuario")
    }
}

@Serializable
data class AgentesErrorResponse(val code: String, val message: String)

class AgentesServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = AgentesServerModule
}
