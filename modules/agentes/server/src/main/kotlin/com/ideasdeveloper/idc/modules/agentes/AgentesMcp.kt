package com.ideasdeveloper.idc.modules.agentes

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.RegisteredMcpTool
import com.ideasdeveloper.idc.server.modules.UserModuleAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

// A tool the connected agent may use, with the user's permissions in its module.
class VisibleTool(val registered: RegisteredMcpTool, val permissions: ModulePermissions) {
    val name: String get() = registered.qualifiedName
}

// Result of running a tool: its output for the agent, or an error message the agent can act on.
sealed class ToolOutcome {
    data class Success(val result: JsonElement) : ToolOutcome()
    data class Failure(val message: String) : ToolOutcome()
}

/**
 * Model Context Protocol (JSON-RPC 2.0 over Streamable HTTP, JSON responses only) for one agent
 * request. Database and permission work is injected so the protocol can be tested on its own.
 */
class AgentesMcp(
    private val instructions: String,
    private val tools: () -> List<VisibleTool>,
    private val invoke: (VisibleTool, JsonObject) -> ToolOutcome,
    // Records tools/call requests for names the agent may not use.
    private val denied: (String) -> Unit = {},
) {
    /** Answers a JSON-RPC message or batch; null when nothing must be returned (only notifications). */
    fun handle(message: JsonElement): JsonElement? = when (message) {
        is JsonArray -> message.mapNotNull { handleOne(it) }.takeIf { it.isNotEmpty() }?.let(::JsonArray)
        else -> handleOne(message)
    }

    private fun handleOne(message: JsonElement): JsonObject? {
        val request = message as? JsonObject ?: return error(JsonNull, InvalidRequest, "La solicitud no es un objeto JSON-RPC.")
        val id = request["id"]
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
        // Responses from the client (we never send requests) and notifications get no answer.
        if (method == null) return if (id == null) null else error(id, InvalidRequest, "Falta el metodo.")
        if (id == null) return null
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(tools().map(::toolJson))) })
            "tools/call" -> callTool(id, params)
            "resources/list" -> result(id, buildJsonObject { putJsonArray("resources") {} })
            "prompts/list" -> result(id, buildJsonObject { putJsonArray("prompts") {} })
            else -> error(id, MethodNotFound, "Metodo no soportado: $method")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in SupportedVersions } ?: SupportedVersions.first())
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", "ideascore")
                put("title", "IdeasCore")
                put("version", AgentesServerModule.definition.version)
            }
            put("instructions", instructions)
        }
    }

    private fun callTool(id: JsonElement, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val arguments = when (val raw = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> return error(id, InvalidParams, "Los argumentos deben ser un objeto.")
        }
        val tool = tools().firstOrNull { it.name == name } ?: run {
            denied(name)
            return error(id, InvalidParams, "Herramienta desconocida o sin permiso: $name")
        }
        val (text, isError) = when (val outcome = invoke(tool, arguments)) {
            is ToolOutcome.Success -> outcome.result.toString() to false
            is ToolOutcome.Failure -> outcome.message to true
        }
        return result(id, buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            put("isError", isError)
        })
    }

    private fun toolJson(visible: VisibleTool): JsonObject {
        val tool = visible.registered.tool
        val title = "${visible.registered.moduleName}: ${tool.title}"
        return buildJsonObject {
            put("name", visible.name)
            put("title", title)
            put("description", tool.description)
            put("inputSchema", tool.inputSchema)
            putJsonObject("annotations") {
                put("title", title)
                put("readOnlyHint", tool.readOnly)
                put("destructiveHint", tool.destructive)
                put("openWorldHint", false)
            }
        }
    }

    companion object {
        val SupportedVersions = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val ParseError = -32700
        const val InvalidRequest = -32600
        const val MethodNotFound = -32601
        const val InvalidParams = -32602

        /** Tools the agent may use now: allowed by its token and permitted to its user in an enabled module. */
        fun visibleTools(all: List<RegisteredMcpTool>, token: AgentToken, access: UserModuleAccess): List<VisibleTool> =
            all.mapNotNull { registered ->
                if (!token.allowsModule(registered.moduleId)) return@mapNotNull null
                if (token.readOnly && !registered.tool.readOnly) return@mapNotNull null
                val permissions = access.permissions(registered.moduleId) ?: return@mapNotNull null
                if (!permissions.has(registered.tool.permission)) return@mapNotNull null
                VisibleTool(registered, permissions)
            }

        fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
    }
}

/** Requests per minute per agent token, so a looping agent cannot saturate the server. */
class AgentRateLimiter(private val perMinute: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private class Window(var start: Long, var count: Int)
    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(key: String): Boolean {
        val now = clock()
        if (windows.size > 10_000) windows.entries.removeIf { now - it.value.start >= 60_000 }
        val window = windows.computeIfAbsent(key) { Window(now, 0) }
        synchronized(window) {
            if (now - window.start >= 60_000) {
                window.start = now
                window.count = 0
            }
            window.count++
            return window.count <= perMinute
        }
    }
}
