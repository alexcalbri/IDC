package com.ideasdeveloper.idc.modules.agentes

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.NotificationService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * This module's own MCP, like any other module's: managing the agent connections themselves. It
 * says nothing about other modules. Creating a connection is not offered: it returns a token, which
 * is a credential and is only shown in the app.
 */
object AgentesMcpTools {
    val mcp: ModuleMcp
        get() = ModuleMcp(Instructions, tools)

    private const val Instructions =
        "Agentes IA administra las conexiones de agentes como tu: cada una pertenece a un usuario y nunca tiene mas permisos que el. " +
            "Puedes ver las conexiones, su actividad y revocarlas; las conexiones nuevas solo se crean en la app, porque su token es una credencial. " +
            "Tambien puedes leer las notificaciones del usuario (list_notifications) y marcarlas como leidas (mark_notifications_read)."

    private val tools = listOf(
        McpTool(
            name = "list_agents",
            title = "Agentes conectados",
            description = "Lista las conexiones de agentes del usuario; con all=true (permiso de administrar agentes) las de toda la empresa.",
            permission = AgentesScreens.Connect,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("all" to McpSchema.boolean("Todas las de la empresa."))),
        ) { call -> list(call) },
        McpTool(
            name = "get_agent_activity",
            title = "Actividad de un agente",
            description = "Ultimas llamadas de una conexion (herramienta, resultado y hora). Propias, o de cualquiera con el permiso de administrar agentes.",
            permission = AgentesScreens.Connect,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf("id" to McpSchema.uuid("Id de la conexion (list_agents)."), "limit" to McpSchema.integer("Cantidad maxima.", 1, 200)),
                required = listOf("id"),
            ),
        ) { call -> activity(call) },
        McpTool(
            name = "revoke_agent",
            title = "Revocar agente",
            description = "Revoca una conexion: el agente deja de poder usar IdeasCore de inmediato. Propias, o de cualquiera con el permiso de administrar " +
                "agentes. Confirma con el usuario antes; si es tu propia conexion, perderas el acceso.",
            permission = AgentesScreens.Connect,
            destructive = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id de la conexion (list_agents).")), required = listOf("id")),
        ) { call -> revoke(call) },
        McpTool(
            name = "list_notifications",
            title = "Notificaciones",
            description = "Lista las notificaciones del centro de notificaciones del usuario (las que ve en la campana de la app), las mas nuevas primero. " +
                "Solo las del propio usuario; con unread=true solo las no leidas.",
            permission = AgentesScreens.Connect,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "unread" to McpSchema.boolean("Solo las no leidas."),
                    "limit" to McpSchema.integer("Cantidad maxima (30 por defecto).", 1, 100),
                    "offset" to McpSchema.integer("Cuantas saltar, para la pagina siguiente.", 0, 100_000),
                ),
            ),
        ) { call -> notifications(call) },
        McpTool(
            name = "mark_notifications_read",
            title = "Marcar notificaciones como leidas",
            description = "Marca como leida una notificacion del usuario (id) o todas (all=true). Solo cambia el estado de lectura; no borra nada.",
            permission = AgentesScreens.Connect,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.uuid("Id de la notificacion (list_notifications)."),
                    "all" to McpSchema.boolean("Marcar todas las del usuario."),
                ),
            ),
        ) { call -> markRead(call) },
    )

    private fun notifications(call: McpToolCall): JsonElement {
        val userId = call.permissions.userId ?: throw McpToolException("No se pudo identificar al usuario.")
        val page = call.access.notifications.list(
            call.tenant, userId,
            limit = call.int("limit", NotificationService.DefaultPageSize, 1..NotificationService.MaxPageSize),
            offset = call.int("offset", 0, 0..100_000),
            unreadOnly = call.boolean("unread"),
        )
        return buildJsonObject {
            put("unread", page.unread)
            put("hasMore", page.hasMore)
            put("notifications", JsonArray(page.items.map { item ->
                buildJsonObject {
                    put("id", item.id.toString())
                    put("module", item.moduleId)
                    put("title", item.title)
                    put("text", item.text)
                    item.target?.let { target ->
                        put("screen", target.screen)
                        if (target.params.isNotEmpty()) put("params", buildJsonObject { target.params.forEach { (key, value) -> put(key, value) } })
                    }
                    put("createdAt", item.createdAt.toString())
                    put("read", item.readAt != null)
                }
            }))
        }
    }

    private fun markRead(call: McpToolCall): JsonElement {
        val userId = call.permissions.userId ?: throw McpToolException("No se pudo identificar al usuario.")
        val notifications = call.access.notifications
        val changed = when {
            call.boolean("all") -> notifications.markAllRead(call.tenant, userId)
            call.has("id") -> if (notifications.markRead(call.tenant, userId, call.requiredUuid("id"))) 1 else 0
            else -> throw McpToolException("Indica el id de una notificacion o all=true.")
        }
        return buildJsonObject {
            put("marked", changed)
            put("unread", notifications.unreadCount(call.tenant, userId))
        }
    }

    private fun owns(call: McpToolCall, token: AgentToken) =
        token.userId == call.permissions.userId && token.serverOwner == call.permissions.serverOwner

    // Own connections, or any of the company with agentes.manage; like the "Mis agentes" and "Equipo" screens.
    private fun visible(call: McpToolCall): AgentToken {
        val token = AgentTokens.find(call.tenant, call.requiredUuid("id"))
            ?.takeIf { owns(call, it) || call.permissions.has(AgentesScreens.Manage) }
            ?: throw McpToolException("Conexion no encontrada o no visible para el usuario.")
        return token
    }

    private fun json(token: AgentToken) = buildJsonObject {
        put("id", token.id.toString())
        put("name", token.name)
        put("user", token.userName)
        put("connection", if (token.oauthClient != null) "OAuth" else "token ...${token.hint}")
        put("modules", token.allowedModules?.let { modules -> JsonArray(modules.sorted().map(::JsonPrimitive)) } ?: JsonPrimitive("todos"))
        put("readOnly", token.readOnly)
        put("active", token.isActive())
        put("createdAt", token.createdAt.toString())
        put("expiresAt", token.expiresAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("lastUsedAt", token.lastUsedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        token.revokedAt?.let { put("revokedAt", it.toString()) }
        token.revokedBy?.let { put("revokedBy", it) }
    }

    private fun list(call: McpToolCall): JsonElement {
        val all = call.boolean("all")
        if (all && !call.permissions.has(AgentesScreens.Manage)) throw McpToolException("Ver los agentes de toda la empresa necesita el permiso de administrar agentes.")
        val userId = call.permissions.userId ?: throw McpToolException("No se pudo identificar al usuario.")
        val tokens = AgentTokens.list(call.tenant, if (all) null else userId).filter { all || owns(call, it) }
        return buildJsonObject { put("agents", JsonArray(tokens.map(::json))) }
    }

    private fun activity(call: McpToolCall): JsonElement {
        val token = visible(call)
        return buildJsonObject {
            put("agent", json(token))
            put("activity", JsonArray(AgentTokens.activity(call.tenant, token.id, call.int("limit", 50, 1..200)).map { entry ->
                buildJsonObject {
                    put("tool", entry.tool)
                    put("outcome", entry.outcome)
                    if (entry.detail.isNotEmpty()) put("detail", entry.detail)
                    put("at", entry.createdAt.toString())
                }
            }))
        }
    }

    private fun revoke(call: McpToolCall): JsonElement {
        val token = visible(call)
        if (!AgentTokens.revoke(call.tenant, token.id, "agente ${call.agentName}")) throw McpToolException("Esa conexion ya estaba revocada.")
        return buildJsonObject { put("result", "Conexion ${token.name} revocada.") }
    }
}
