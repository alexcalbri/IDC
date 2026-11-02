package com.ideasdeveloper.idc.modules.agentes

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.RegisteredMcpTool
import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiNavItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNavigation
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTimeline
import com.ideasdeveloper.idc.ui.UiTimelineEntry
import com.ideasdeveloper.idc.ui.UiTone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object AgentesScreens {
    const val UiRoute = "/modules/agentes/ui"
    const val McpRoute = "/modules/agentes/mcp"

    const val Connect = "agentes.connect"
    const val Manage = "agentes.manage"

    private const val ModuleFieldPrefix = "m_"
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val prettyJson = Json { prettyPrint = true }

    // Expiration choices of a new token.
    val expirations = linkedMapOf<String, Duration?>(
        "30 dias" to Duration.ofDays(30),
        "90 dias" to Duration.ofDays(90),
        "1 año" to Duration.ofDays(365),
        "Sin vencimiento" to null,
    )

    private data class Section(val id: String, val label: String, val permission: String)

    private val sections = listOf(
        Section("mine", "Mis agentes", Connect),
        Section("team", "Agentes de la empresa", Manage),
    )

    /**
     * What a screen needs: the signed-in user, the tools that user could give an agent right now
     * (their permissions in enabled modules) and the public URL of the MCP endpoint.
     */
    class Context(
        val tenant: Connection,
        val permissions: ModulePermissions,
        val owner: AgentOwner,
        val userTools: List<RegisteredMcpTool>,
        val endpoint: String,
    ) {
        val canManage get() = permissions.has(Manage)

        // Modules with at least one tool for the user, in name order.
        val toolModules get() = userTools.groupBy { it.moduleId }.map { (id, tools) -> id to tools.first().moduleName }.sortedBy { it.second }

        fun owns(token: AgentToken) = token.userId == owner.userId && token.serverOwner == owner.serverOwner
    }

    fun home(permissions: ModulePermissions): String = if (permissions.has(Connect)) "mine" else "team"

    fun isScreen(screen: String) = screen in setOf("mine", "team", "new", "token")

    fun permissionFor(screen: String): String = if (screen == "team") Manage else if (screen == "token") "" else Connect

    fun build(screen: String, context: Context, params: Map<String, String>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val required = permissionFor(screen)
        val allowed = if (screen == "token") context.permissions.has(Connect) || context.canManage else context.permissions.has(required)
        if (!allowed) return frame(context, screen, "Agentes IA", UiNotice("No tienes permiso para ver esta seccion.", UiTone.Danger), emptyList())
        return when (screen) {
            "mine" -> mine(context, notice)
            "team" -> team(context, notice)
            "new" -> newToken(context, notice, submitted)
            else -> token(context, params["id"], notice)
        }
    }

    private fun mine(context: Context, notice: UiNotice?): UiScreen {
        val tokens = AgentTokens.list(context.tenant, context.owner.userId).filter { it.serverOwner == context.owner.serverOwner }
        val body = listOf(
            UiText(
                "Conecta tu propio modelo o agente de IA (Claude, ChatGPT, Cursor, un agente propio...) por MCP. " +
                    "El agente actua en tu nombre y solo puede usar las funciones que tienes permitidas; si te quitan un permiso, tu agente lo pierde de inmediato.",
                UiTextStyle.Body,
            ),
            UiCard(
                title = "Conectar desde claude.ai u otra app con OAuth",
                children = listOf(
                    UiText(
                        "Agrega un conector personalizado con esta URL. La app te pedira iniciar sesion en IdeasCore y elegir que puede usar; no necesitas copiar ningun token.",
                        UiTextStyle.Body,
                    ),
                    UiForm(id = "oauth", fields = listOf(UiField("url", "URL del conector")), values = mapOf("url" to context.endpoint), minFieldWidth = 420),
                ),
            ),
            UiCard(
                title = "Mis agentes conectados",
                children = listOf(tokenList(tokens, showUser = false, emptyText = "Aun no conectaste ningun agente.")),
                actions = listOf(UiButton("Conectar un agente", UiNavigate("new"))),
            ),
            UiCard(
                title = "Lo que tu agente puede hacer hoy",
                children = listOf(toolList(context.userTools)),
            ),
        )
        return frame(context, "mine", "Mis agentes", notice, body)
    }

    private fun team(context: Context, notice: UiNotice?): UiScreen {
        val tokens = AgentTokens.list(context.tenant, null)
        val active = tokens.count { it.isActive() }
        val body = listOf(
            UiText("Agentes que los usuarios de la empresa conectaron. Cada uno usa los permisos de su usuario. Activos: $active.", UiTextStyle.Body),
            tokenList(tokens, showUser = true, emptyText = "Ningun usuario conecto un agente."),
        )
        return frame(context, "team", "Agentes de la empresa", notice, body)
    }

    private fun newToken(context: Context, notice: UiNotice?, submitted: Map<String, String>?): UiScreen {
        if (context.userTools.isEmpty()) {
            return frame(
                context, "mine", "Conectar un agente",
                notice ?: UiNotice("Aun no tienes funciones que un agente pueda usar. Pide al administrador permisos en los modulos que necesitas.", UiTone.Warning),
                emptyList(),
            )
        }
        val moduleFields = context.toolModules.map { (id, name) ->
            UiField("$ModuleFieldPrefix$id", name, "boolean", section = "Modulos que el agente puede usar")
        }
        val defaults = mapOf("expires" to "90 dias", "readOnly" to "false") + context.toolModules.associate { "$ModuleFieldPrefix${it.first}" to "true" }
        val form = UiForm(
            id = "token",
            fields = listOf(
                UiField("name", "Nombre del agente", required = true, placeholder = "Ej. Claude de ventas", help = "Para reconocerlo en la lista y en la actividad."),
            ) + moduleFields + listOf(
                UiField("readOnly", "Solo lectura: puede consultar, pero no crear ni modificar", "boolean", section = "Limites"),
                UiField("expires", "Vence en", "select", options = expirations.keys.toList(), section = "Limites"),
            ),
            values = defaults + submitted.orEmpty(),
            submit = UiButton("Crear token", UiRequest("$UiRoute/new", params = mapOf("op" to "create"), formId = "token")),
        )
        val body = listOf(
            UiText(
                "Si marcas todos los modulos, el agente tambien podra usar los que se activen despues. " +
                    "En ningun caso podra hacer algo que tu usuario no tenga permitido.",
                UiTextStyle.Caption,
            ),
            form,
        )
        return frame(context, "mine", "Conectar un agente", notice, body)
    }

    /** Shown once after creating a token: the only time its value is visible. */
    fun created(context: Context, token: AgentToken, value: String): UiScreen {
        val config = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject("ideascore") {
                    put("type", "http")
                    put("url", context.endpoint)
                    putJsonObject("headers") { put("Authorization", "Bearer $value") }
                }
            }
        }
        val form = UiForm(
            id = "connection",
            fields = listOf(
                UiField("url", "URL del servidor MCP"),
                UiField("token", "Token (cabecera Authorization: Bearer ...)"),
                UiField("claude", "Claude Code", "textarea"),
                UiField("json", "Configuracion JSON (Cursor, VS Code, Claude Desktop con mcp-remote y otros)", "textarea"),
            ),
            values = mapOf(
                "url" to context.endpoint,
                "token" to value,
                "claude" to "claude mcp add --transport http ideascore ${context.endpoint} --header \"Authorization: Bearer $value\"",
                "json" to prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), config),
            ),
            minFieldWidth = 420,
        )
        val body = listOf(
            UiText("Copia el token ahora: por seguridad no se vuelve a mostrar. Si lo pierdes, revoca este agente y crea otro.", UiTextStyle.Body, UiTone.Warning),
            form,
            UiButton("Ver el agente", UiNavigate("token", mapOf("id" to token.id.toString())), style = "secondary"),
        )
        return frame(context, "mine", "Agente \"${token.name}\" creado", UiNotice("Token creado.", UiTone.Success), body)
    }

    private fun token(context: Context, rawId: String?, notice: UiNotice?): UiScreen {
        val token = rawId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { AgentTokens.find(context.tenant, it) }
            ?.takeIf { context.owns(it) || context.canManage }
            ?: return frame(context, home(context.permissions), "Agente", UiNotice("Ese agente no existe o no es tuyo.", UiTone.Warning), emptyList())
        val details = UiList(
            listOf(
                UiListItem("Usuario", trailing = token.userName),
                UiListItem("Modulos", trailing = token.allowedModules?.sorted()?.joinToString().orEmpty().ifEmpty { "Todos los que el usuario puede usar" }),
                UiListItem("Acceso", trailing = if (token.readOnly) "Solo lectura" else "Lectura y escritura"),
                UiListItem("Conexion", trailing = if (token.oauthClient != null) "OAuth (el token se renueva solo)" else "Token ${AgentTokens.Prefix}...${token.hint}..."),
                UiListItem("Creado", trailing = format(token.createdAt)),
                UiListItem("Vence", trailing = token.expiresAt?.let(::format) ?: "Nunca"),
                UiListItem("Ultimo uso", trailing = token.lastUsedAt?.let(::format) ?: "Sin uso"),
            ) + listOfNotNull(token.revokedAt?.let { UiListItem("Revocado", trailing = "${format(it)} por ${token.revokedBy.orEmpty()}") }),
        )
        val activity = UiTimeline(
            AgentTokens.activity(context.tenant, token.id, 50).map { entry ->
                UiTimelineEntry(
                    title = entry.tool,
                    body = entry.detail,
                    caption = format(entry.createdAt),
                    tone = when (entry.outcome) {
                        "ok" -> UiTone.Success
                        "denied" -> UiTone.Danger
                        else -> UiTone.Warning
                    },
                )
            },
            emptyText = "El agente aun no uso ninguna funcion.",
        )
        val revoke = UiButton(
            "Revocar acceso",
            UiRequest("$UiRoute/token", params = mapOf("op" to "revoke", "id" to token.id.toString()), confirm = "El agente deja de funcionar de inmediato. Continuar?"),
            style = "danger",
        ).takeIf { token.revokedAt == null }
        val body = listOf(
            UiCard(title = "Detalles", children = listOf(status(token), details), actions = listOfNotNull(revoke)),
            UiCard(title = "Actividad reciente", children = listOf(activity)),
        )
        return frame(context, if (context.owns(token)) "mine" else "team", token.name, notice, body)
    }

    private fun tokenList(tokens: List<AgentToken>, showUser: Boolean, emptyText: String) = UiList(
        tokens.map { token ->
            val scope = listOf(
                token.allowedModules?.sorted()?.joinToString() ?: "Todos los modulos",
                if (token.readOnly) "solo lectura" else "lectura y escritura",
                if (token.oauthClient != null) "OAuth" else "token",
                "ultimo uso: ${token.lastUsedAt?.let(::format) ?: "nunca"}",
            ).joinToString(" · ")
            UiListItem(
                title = token.name,
                subtitle = if (showUser) "${token.userName} · $scope" else scope,
                badges = listOf(statusBadge(token)),
                action = UiNavigate("token", mapOf("id" to token.id.toString())),
            )
        },
        emptyText = emptyText,
    )

    private fun toolList(tools: List<RegisteredMcpTool>) = UiList(
        tools.sortedWith(compareBy({ it.moduleName }, { it.tool.title })).map { registered ->
            UiListItem(
                title = "${registered.moduleName}: ${registered.tool.title}",
                subtitle = registered.tool.description.substringBefore(". ").take(140),
                badges = listOf(if (registered.tool.readOnly) UiBadge("Consulta", UiTone.Info) else UiBadge("Modifica", UiTone.Warning)),
            )
        },
        emptyText = "Ninguna: no tienes permisos en modulos que ofrezcan funciones para agentes.",
    )

    private fun status(token: AgentToken): UiNode = UiText(
        when {
            token.revokedAt != null -> "Revocado"
            !token.isActive() -> "Vencido"
            else -> "Activo"
        },
        UiTextStyle.Subtitle,
        if (token.isActive()) UiTone.Success else UiTone.Danger,
    )

    private fun statusBadge(token: AgentToken) = when {
        token.revokedAt != null -> UiBadge("Revocado", UiTone.Danger)
        !token.isActive() -> UiBadge("Vencido", UiTone.Warning)
        else -> UiBadge("Activo", UiTone.Success)
    }

    private fun format(instant: Instant): String = timeFormat.format(instant)

    fun frame(context: Context, selected: String, title: String, notice: UiNotice?, body: List<UiNode>) = UiScreen(
        title = title,
        navigation = UiNavigation(
            title = AgentesModule.displayName,
            items = sections.filter { context.permissions.has(it.permission) }.map { UiNavItem(it.id, it.label, UiNavigate(it.id)) },
            selected = selected,
        ),
        notice = notice,
        body = listOf<UiNode>(UiText(title, UiTextStyle.Title)) + body,
    )

    /** Reads the "new agent" form: name, allowed modules (null = all), read-only and expiration. */
    fun parseNewToken(values: Map<String, String>, toolModules: List<String>, now: Instant = Instant.now()): NewTokenRequest {
        val name = values["name"].orEmpty().trim()
        if (name.isEmpty() || name.length > 80) throw AgentesValidationException("Escribe un nombre de hasta 80 caracteres.")
        val chosen = toolModules.filter { values["$ModuleFieldPrefix$it"] == "true" }.toSet()
        if (chosen.isEmpty()) throw AgentesValidationException("Elige al menos un modulo.")
        val expiration = values["expires"].orEmpty()
        if (expiration !in expirations) throw AgentesValidationException("Elige cuando vence el token.")
        return NewTokenRequest(
            name = name,
            allowedModules = chosen.takeIf { it.size < toolModules.size },
            readOnly = values["readOnly"] == "true",
            expiresAt = expirations[expiration]?.let { now.plus(it) },
        )
    }
}

data class NewTokenRequest(val name: String, val allowedModules: Set<String>?, val readOnly: Boolean, val expiresAt: Instant?)

class AgentesValidationException(message: String) : Exception(message)
