package com.ideasdeveloper.idc.server.modules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * A function a module offers to AI agents through the MCP endpoint (module "agentes").
 *
 * An agent acts as the user that connected it and never gets more than that user: the endpoint only
 * lists and runs a tool when the module is installed and enabled for the company and the user holds
 * [permission] at the moment of the call. Handlers must apply the same rules as the module's screens
 * (visibility scopes, ownership...) using [McpToolCall.permissions], exactly like a route would.
 */
class McpTool(
    // Unique within the module, [a-z][a-z0-9_]*; agents see it as "<moduleId>_<name>".
    val name: String,
    val title: String,
    // What the tool does and when to use it, written for the model.
    val description: String,
    val permission: String,
    val inputSchema: JsonObject = McpSchema.obj(),
    // Only reads data. Read-only agent tokens can use read-only tools only.
    val readOnly: Boolean = false,
    // Deletes or overwrites data that cannot be recovered.
    val destructive: Boolean = false,
    // Runs on an IO thread with the company's tenant database; returns the result for the agent.
    val handler: (McpToolCall) -> JsonElement,
)

// A tool of an installed module, as the registry exposes it to the MCP endpoint.
class RegisteredMcpTool(val moduleId: String, val moduleName: String, val tool: McpTool) {
    val qualifiedName: String get() = "${moduleId}_${tool.name}"
}

/** One call of a tool: the agent's arguments, the user's permissions in the module and the tenant. */
class McpToolCall(
    val arguments: JsonObject,
    val permissions: ModulePermissions,
    val tenant: Connection,
    val access: ModuleAccessService,
    // Name of the agent token, to tell in activity logs that an agent did the change.
    val agentName: String,
) {
    private fun primitive(key: String): String? = (arguments[key] as? JsonPrimitive)?.contentOrNull?.trim()

    fun string(key: String, maxLength: Int = 2_000): String = primitive(key).orEmpty().take(maxLength)

    fun requiredString(key: String, maxLength: Int = 2_000): String =
        string(key, maxLength).ifEmpty { throw McpToolException("El argumento '$key' es obligatorio.") }

    // Whether the agent sent the argument (to tell "leave as is" from "clear").
    fun has(key: String): Boolean = arguments.containsKey(key)

    fun int(key: String, default: Int, range: IntRange): Int {
        val value = primitive(key)?.takeIf { it.isNotEmpty() } ?: return default
        val number = value.toIntOrNull() ?: throw McpToolException("El argumento '$key' debe ser un numero entero.")
        return number.coerceIn(range)
    }

    fun boolean(key: String, default: Boolean = false): Boolean =
        (arguments[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() } ?: default

    fun uuid(key: String): UUID? = primitive(key)?.takeIf { it.isNotEmpty() }?.let {
        runCatching { UUID.fromString(it) }.getOrNull() ?: throw McpToolException("El argumento '$key' debe ser un id valido.")
    }

    fun requiredUuid(key: String): UUID = uuid(key) ?: throw McpToolException("El argumento '$key' es obligatorio.")

    fun date(key: String): LocalDate? = primitive(key)?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrNull() ?: throw McpToolException("El argumento '$key' debe ser una fecha AAAA-MM-DD.")
    }

    fun decimal(key: String): BigDecimal? = primitive(key)?.takeIf { it.isNotEmpty() }?.let {
        it.toBigDecimalOrNull() ?: throw McpToolException("El argumento '$key' debe ser un numero.")
    }
}

// An error the agent can fix (bad argument, record not found...). Its message is returned to the agent.
class McpToolException(message: String) : Exception(message)

/** Builders for the JSON Schema of tool arguments. */
object McpSchema {
    fun obj(properties: Map<String, JsonObject> = emptyMap(), required: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }

    fun string(description: String, enum: List<String> = emptyList(), format: String? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum.isNotEmpty()) put("enum", JsonArray(enum.map(::JsonPrimitive)))
        format?.let { put("format", it) }
    }

    fun integer(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
    }

    fun number(description: String): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    fun date(description: String): JsonObject = string("$description Formato AAAA-MM-DD.", format = "date")

    fun uuid(description: String): JsonObject = string(description, format = "uuid")
}
