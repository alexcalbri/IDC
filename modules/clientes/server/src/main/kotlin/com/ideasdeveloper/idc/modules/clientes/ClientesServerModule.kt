package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.ModuleActionDefinition
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleFieldDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ModuleViewDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.requireModulePermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.util.UUID

object ClientesServerModule : ServerModule {
    override val migrationPaths = listOf(
        "modules/clientes/migrations/V001__create_customers.sql",
        "modules/clientes/migrations/V002__require_customer_contact_fields.sql",
    )

    override val definition = ModuleDefinition(
        id = ClientesModule.id,
        displayName = ClientesModule.displayName,
        description = "Gestiona la identidad compartida de clientes.",
        version = "0.2.0",
        locked = true,
        permissions = listOf(
            ModulePermissionDefinition("clientes.view", "Ver clientes", "Permite consultar la lista y ficha de clientes."),
            ModulePermissionDefinition("clientes.create", "Crear clientes", "Permite crear clientes."),
            ModulePermissionDefinition("clientes.edit", "Editar clientes", "Permite modificar clientes existentes."),
            ModulePermissionDefinition("clientes.delete", "Eliminar clientes", "Permite eliminar o desactivar clientes."),
            ModulePermissionDefinition("clientes.fields.manage", "Administrar campos de clientes", "Permite crear, editar, desactivar o eliminar campos dinamicos de clientes."),
        ),
        views = listOf(
            ModuleViewDefinition(
                id = "customers",
                title = "Clientes",
                route = "/modules/clientes/customers",
                kind = "list_create_edit_delete",
                fields = listOf(
                    ModuleFieldDefinition("displayName", "Nombre", "text", required = true),
                    ModuleFieldDefinition("primaryEmail", "Correo", "email", required = true),
                    ModuleFieldDefinition("primaryPhone", "Telefono", "phone", required = true),
                ),
                actions = listOf(
                    ModuleActionDefinition("list", "Listar clientes", "/modules/clientes/customers", "GET", "clientes.view"),
                    ModuleActionDefinition("create", "Crear cliente", "/modules/clientes/customers", "POST", "clientes.create"),
                    ModuleActionDefinition("edit", "Editar cliente", "/modules/clientes/customers/{id}", "PUT", "clientes.edit"),
                    ModuleActionDefinition("delete", "Eliminar cliente", "/modules/clientes/customers/{id}", "DELETE", "clientes.delete"),
                    ModuleActionDefinition("fields", "Administrar campos", "/modules/clientes/fields", "POST", "clientes.fields.manage"),
                ),
            )
        ),
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        route.route("/modules/clientes") {
            get("/fields") {
                if (!call.requireModulePermission(access, ClientesModule.id, "clientes.view")) return@get
                val connection = access.tenantConnection(call) ?: return@get call.respond(HttpStatusCode.Forbidden, ClientesErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                connection.use { tenant -> call.respond(HttpStatusCode.OK, CustomerFieldsResponse(loadFields(tenant))) }
            }

            post("/fields") {
                if (!call.requireModulePermission(access, ClientesModule.id, "clientes.fields.manage")) return@post
                val request = call.receive<CreateCustomerFieldRequest>()
                val fieldKey = request.fieldKey.trim()
                val label = request.label.trim()
                val fieldType = request.fieldType.trim()
                if (!Regex("[a-z][a-z0-9_]{0,62}").matches(fieldKey) || label.isBlank() || fieldType !in allowedFieldTypes) {
                    call.respond(HttpStatusCode.BadRequest, ClientesErrorResponse("INVALID_FIELD", "El campo dinamico no es valido."))
                    return@post
                }
                val connection = access.tenantConnection(call) ?: return@post call.respond(HttpStatusCode.Forbidden, ClientesErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                val fieldId = UUID.randomUUID()
                connection.use { tenant ->
                    tenant.prepareStatement(
                        """
                        INSERT INTO customer_field_definitions (id, field_key, label, field_type, control_type, is_required, display_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { insert ->
                        insert.setObject(1, fieldId)
                        insert.setString(2, fieldKey)
                        insert.setString(3, label)
                        insert.setString(4, fieldType)
                        insert.setString(5, request.controlType.ifBlank { fieldType })
                        insert.setBoolean(6, request.required)
                        insert.setInt(7, request.displayOrder)
                        insert.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.Created, CustomerFieldResponse(fieldId.toString(), fieldKey, label, fieldType, request.required, true, request.displayOrder))
            }

            get("/customers") {
                if (!call.requireModulePermission(access, ClientesModule.id, "clientes.view")) return@get
                val connection = access.tenantConnection(call) ?: return@get call.respond(HttpStatusCode.Forbidden, ClientesErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                connection.use { tenant ->
                    tenant.prepareStatement(
                        """
                        SELECT id, display_name, primary_email, primary_phone, status, flexible_attributes::text AS flexible_attributes
                        FROM customers
                        WHERE status <> 'archived'
                        ORDER BY lower(display_name), created_at DESC
                        LIMIT 200
                        """.trimIndent()
                    ).use { query ->
                        query.executeQuery().use { rows ->
                            val customers = buildList {
                                while (rows.next()) add(rowCustomer(rows))
                            }
                            call.respond(HttpStatusCode.OK, CustomersResponse(customers))
                        }
                    }
                }
            }

            post("/customers") {
                if (!call.requireModulePermission(access, ClientesModule.id, "clientes.create")) return@post
                val request = call.receive<CreateCustomerRequest>()
                val connection = access.tenantConnection(call) ?: return@post call.respond(HttpStatusCode.Forbidden, ClientesErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                connection.use { tenant ->
                    validateCustomerRequest(tenant, request)?.let { return@post call.respond(HttpStatusCode.BadRequest, it) }
                    if (emailExists(tenant, request.primaryEmail.trim(), null)) {
                        return@post call.respond(HttpStatusCode.Conflict, ClientesErrorResponse("CUSTOMER_DUPLICATE_EMAIL", "Ya existe un cliente con ese correo."))
                    }
                    val customerId = UUID.randomUUID()
                    upsertCustomer(tenant, customerId, request, insert = true)
                    call.respond(HttpStatusCode.Created, request.toResponse(customerId, "active"))
                }
            }

            put("/customers/{customerId}") {
                if (!call.requireModulePermission(access, ClientesModule.id, "clientes.edit")) return@put
                val customerId = call.parameters["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ClientesErrorResponse("INVALID_CUSTOMER", "Cliente invalido."))
                val request = call.receive<CreateCustomerRequest>()
                val connection = access.tenantConnection(call) ?: return@put call.respond(HttpStatusCode.Forbidden, ClientesErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                connection.use { tenant ->
                    validateCustomerRequest(tenant, request)?.let { return@put call.respond(HttpStatusCode.BadRequest, it) }
                    if (emailExists(tenant, request.primaryEmail.trim(), customerId)) {
                        return@put call.respond(HttpStatusCode.Conflict, ClientesErrorResponse("CUSTOMER_DUPLICATE_EMAIL", "Ya existe otro cliente con ese correo."))
                    }
                    val updated = upsertCustomer(tenant, customerId, request, insert = false)
                    if (!updated) return@put call.respond(HttpStatusCode.NotFound, ClientesErrorResponse("CUSTOMER_NOT_FOUND", "Cliente no encontrado."))
                    call.respond(HttpStatusCode.OK, request.toResponse(customerId, "active"))
                }
            }

            delete("/customers/{customerId}") {
                if (!call.requireModulePermission(access, ClientesModule.id, "clientes.delete")) return@delete
                val customerId = call.parameters["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ClientesErrorResponse("INVALID_CUSTOMER", "Cliente invalido."))
                val connection = access.tenantConnection(call) ?: return@delete call.respond(HttpStatusCode.Forbidden, ClientesErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                connection.use { tenant ->
                    tenant.prepareStatement("UPDATE customers SET status = 'archived', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status <> 'archived'").use { update ->
                        update.setObject(1, customerId)
                        if (update.executeUpdate() != 1) return@delete call.respond(HttpStatusCode.NotFound, ClientesErrorResponse("CUSTOMER_NOT_FOUND", "Cliente no encontrado."))
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    private val allowedFieldTypes = setOf("text", "textarea", "number", "money", "date", "datetime", "email", "phone", "boolean", "select", "multi_select")

    private fun validateCustomerRequest(connection: Connection, request: CreateCustomerRequest): ClientesErrorResponse? {
        if (request.displayName.isBlank() || request.primaryEmail.isBlank() || request.primaryPhone.isBlank()) {
            return ClientesErrorResponse("CUSTOMER_REQUIRED_FIELDS", "Nombre, correo y telefono son obligatorios.")
        }
        val fields = loadFields(connection).filter { it.required && it.active }
        val missing = fields.firstOrNull { field -> request.dynamicFields[field.fieldKey] == null }
        return missing?.let { ClientesErrorResponse("CUSTOMER_DYNAMIC_FIELD_REQUIRED", "El campo ${it.label} es obligatorio.") }
    }

    private fun loadFields(connection: Connection): List<CustomerFieldResponse> = connection.prepareStatement(
        """
        SELECT id, field_key, label, field_type, is_required, is_active, display_order
        FROM customer_field_definitions
        WHERE is_active = TRUE
        ORDER BY display_order, field_key
        """.trimIndent()
    ).use { query ->
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(CustomerFieldResponse(rows.getObject("id", UUID::class.java).toString(), rows.getString("field_key"), rows.getString("label"), rows.getString("field_type"), rows.getBoolean("is_required"), rows.getBoolean("is_active"), rows.getInt("display_order")))
                }
            }
        }
    }

    private fun emailExists(connection: Connection, email: String, excluding: UUID?): Boolean = connection.prepareStatement(
        "SELECT 1 FROM customers WHERE lower(primary_email) = lower(?) AND status <> 'archived' AND (? IS NULL OR id <> ?)"
    ).use { query ->
        query.setString(1, email)
        query.setObject(2, excluding)
        query.setObject(3, excluding)
        query.executeQuery().use { it.next() }
    }

    private fun upsertCustomer(connection: Connection, id: UUID, request: CreateCustomerRequest, insert: Boolean): Boolean {
        val sql = if (insert) {
            "INSERT INTO customers (id, display_name, primary_email, primary_phone, status, flexible_attributes) VALUES (?, ?, ?, ?, 'active', CAST(? AS jsonb))"
        } else {
            "UPDATE customers SET display_name = ?, primary_email = ?, primary_phone = ?, flexible_attributes = CAST(? AS jsonb), status = 'active', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status <> 'archived'"
        }
        connection.prepareStatement(sql).use { statement ->
            if (insert) {
                statement.setObject(1, id); statement.setString(2, request.displayName.trim()); statement.setString(3, request.primaryEmail.trim()); statement.setString(4, request.primaryPhone.trim()); statement.setString(5, request.dynamicFields.toString())
            } else {
                statement.setString(1, request.displayName.trim()); statement.setString(2, request.primaryEmail.trim()); statement.setString(3, request.primaryPhone.trim()); statement.setString(4, request.dynamicFields.toString()); statement.setObject(5, id)
            }
            return statement.executeUpdate() == 1
        }
    }

    private fun rowCustomer(rows: java.sql.ResultSet): CustomerResponse = CustomerResponse(
        id = rows.getObject("id", UUID::class.java).toString(),
        displayName = rows.getString("display_name"),
        primaryEmail = rows.getString("primary_email"),
        primaryPhone = rows.getString("primary_phone"),
        status = rows.getString("status"),
        dynamicFields = Json.parseToJsonElement(rows.getString("flexible_attributes")).jsonObject,
    )

    private fun CreateCustomerRequest.toResponse(id: UUID, status: String): CustomerResponse = CustomerResponse(id.toString(), displayName.trim(), primaryEmail.trim(), primaryPhone.trim(), status, dynamicFields)
}

@Serializable
data class CreateCustomerRequest(val displayName: String, val primaryEmail: String, val primaryPhone: String, val dynamicFields: JsonObject = JsonObject(emptyMap()))

@Serializable
data class CreateCustomerFieldRequest(val fieldKey: String, val label: String, val fieldType: String, val controlType: String = "", val required: Boolean = false, val displayOrder: Int = 0)

@Serializable
data class CustomerFieldResponse(val id: String, val fieldKey: String, val label: String, val fieldType: String, val required: Boolean, val active: Boolean, val displayOrder: Int)

@Serializable
data class CustomerFieldsResponse(val fields: List<CustomerFieldResponse>)

@Serializable
data class CustomerResponse(val id: String, val displayName: String, val primaryEmail: String, val primaryPhone: String, val status: String, val dynamicFields: JsonObject = JsonObject(emptyMap()))

@Serializable
data class CustomersResponse(val customers: List<CustomerResponse>)

@Serializable
data class ClientesErrorResponse(val code: String, val message: String)

class ClientesServerModuleProvider : ServerModuleProvider { override fun create(): ServerModule = ClientesServerModule }
