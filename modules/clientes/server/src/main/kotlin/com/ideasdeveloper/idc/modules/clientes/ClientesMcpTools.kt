package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

/** Agent tools of Clientes; the same permissions and validations as the customer routes. */
object ClientesMcpTools {
    private const val Columns =
        "id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes::text AS flexible_attributes"

    private val customerArguments = mapOf(
        "displayName" to McpSchema.string("Nombre del cliente."),
        "primaryEmail" to McpSchema.string("Correo electronico.", format = "email"),
        "primaryPhone" to McpSchema.string("Telefono con codigo de pais, por ejemplo +50760000000."),
        "dynamicFields" to buildJsonObject {
            put("type", "object")
            put("description", "Campos personalizados de la empresa por su clave (ver list_customer_fields).")
        },
    )

    val tools = listOf(
        McpTool(
            name = "search_customers",
            title = "Buscar clientes",
            description = "Busca clientes por nombre, correo, telefono o codigo de cliente. Sin texto devuelve los mas recientes.",
            permission = "clientes.view",
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "query" to McpSchema.string("Texto a buscar (opcional)."),
                    "limit" to McpSchema.integer("Cantidad maxima de resultados.", 1, 100),
                ),
            ),
        ) { call -> search(call) },
        McpTool(
            name = "get_customer",
            title = "Ver cliente",
            description = "Devuelve la ficha completa de un cliente por su id o por su codigo de cliente.",
            permission = "clientes.view",
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.uuid("Id del cliente."),
                    "customerCode" to McpSchema.string("Codigo de cliente, si no se tiene el id."),
                ),
            ),
        ) { call -> customer(call) },
        McpTool(
            name = "list_customer_fields",
            title = "Campos personalizados de clientes",
            description = "Lista los campos personalizados que la empresa definio para sus clientes, con su clave, tipo y si son obligatorios.",
            permission = "clientes.view",
            readOnly = true,
        ) { call -> Json.encodeToJsonElement(CustomerFieldsResponse(ClientesServerModule.loadFields(call.tenant))) },
        McpTool(
            name = "create_customer",
            title = "Crear cliente",
            description = "Crea un cliente. El nombre es obligatorio; no se permite un correo que ya use otro cliente. Antes de crear, busca si ya existe.",
            permission = "clientes.create",
            inputSchema = McpSchema.obj(customerArguments, required = listOf("displayName")),
        ) { call -> create(call) },
        McpTool(
            name = "update_customer",
            title = "Editar cliente",
            description = "Modifica un cliente existente. Solo cambia los datos enviados; los campos personalizados enviados se combinan con los actuales.",
            permission = "clientes.edit",
            inputSchema = McpSchema.obj(customerArguments + ("id" to McpSchema.uuid("Id del cliente.")), required = listOf("id")),
        ) { call -> update(call) },
    )

    private fun search(call: McpToolCall): JsonElement {
        val query = call.string("query", 100).lowercase()
        val limit = call.int("limit", 20, 1..100)
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val customers = call.tenant.prepareStatement(
            """
            SELECT $Columns FROM customers
            WHERE status <> 'archived'
              AND (? = '' OR lower(display_name) LIKE ? OR lower(primary_email) LIKE ? OR primary_phone LIKE ? OR lower(customer_code) LIKE ?)
            ORDER BY CASE WHEN ? = '' THEN created_at END DESC, lower(display_name)
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, query)
            (2..5).forEach { statement.setString(it, pattern) }
            statement.setString(6, query)
            statement.setInt(7, limit)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(ClientesServerModule.rowCustomer(rows)) } }
        }
        return Json.encodeToJsonElement(CustomersResponse(customers))
    }

    private fun customer(call: McpToolCall): JsonElement {
        val id = call.uuid("id")
        val code = call.string("customerCode", 64)
        if (id == null && code.isEmpty()) throw McpToolException("Envia el id o el codigo del cliente.")
        return Json.encodeToJsonElement(find(call, id, code) ?: throw McpToolException("Cliente no encontrado."))
    }

    private fun create(call: McpToolCall): JsonElement {
        val request = CreateCustomerRequest(
            displayName = call.requiredString("displayName", 200),
            primaryEmail = call.string("primaryEmail", 200),
            primaryPhone = call.string("primaryPhone", 50),
            dynamicFields = call.arguments["dynamicFields"] as? JsonObject ?: JsonObject(emptyMap()),
        )
        ClientesServerModule.validateCustomerRequest(call.tenant, request)?.let { throw McpToolException(it.message) }
        if (ClientesServerModule.emailExists(call.tenant, request.primaryEmail, null)) throw McpToolException("Ya existe un cliente con ese correo.")
        val id = UUID.randomUUID()
        ClientesServerModule.upsertCustomer(call.tenant, id, request, insert = true)
        return Json.encodeToJsonElement(find(call, id, "")!!)
    }

    private fun update(call: McpToolCall): JsonElement {
        val id = call.requiredUuid("id")
        val current = find(call, id, "") ?: throw McpToolException("Cliente no encontrado.")
        val dynamic = call.arguments["dynamicFields"] as? JsonObject
        val request = CreateCustomerRequest(
            displayName = if (call.has("displayName")) call.requiredString("displayName", 200) else current.displayName,
            primaryEmail = if (call.has("primaryEmail")) call.string("primaryEmail", 200) else current.primaryEmail,
            primaryPhone = if (call.has("primaryPhone")) call.string("primaryPhone", 50) else current.primaryPhone,
            dynamicFields = if (dynamic != null) JsonObject(current.dynamicFields + dynamic) else current.dynamicFields,
        )
        ClientesServerModule.validateCustomerRequest(call.tenant, request)?.let { throw McpToolException(it.message) }
        if (ClientesServerModule.emailExists(call.tenant, request.primaryEmail, id)) throw McpToolException("Ya existe otro cliente con ese correo.")
        if (!ClientesServerModule.upsertCustomer(call.tenant, id, request, insert = false)) throw McpToolException("Cliente no encontrado.")
        return Json.encodeToJsonElement(find(call, id, "")!!)
    }

    private fun find(call: McpToolCall, id: UUID?, code: String): CustomerResponse? =
        call.tenant.prepareStatement(
            "SELECT $Columns FROM customers WHERE status <> 'archived' AND ${if (id != null) "id = ?" else "customer_code = ?"}"
        ).use { query ->
            if (id != null) query.setObject(1, id) else query.setString(1, code)
            query.executeQuery().use { rows -> if (rows.next()) ClientesServerModule.rowCustomer(rows) else null }
        }
}
