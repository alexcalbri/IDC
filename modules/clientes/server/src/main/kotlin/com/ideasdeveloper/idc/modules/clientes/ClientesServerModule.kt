package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.CustomerHistoryEntry
import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.CustomerHistoryWriter
import com.ideasdeveloper.idc.server.modules.CustomerChangeListener
import com.ideasdeveloper.idc.server.modules.CustomerNotesReader
import com.ideasdeveloper.idc.server.modules.ModuleActionDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import io.ktor.server.application.ApplicationCall
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.SettingsTable
import com.ideasdeveloper.idc.server.modules.ModuleFieldDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ModuleViewDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.requireModulePermission
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

object ClientesServerModule : ServerModule {
    private val log = LoggerFactory.getLogger(ClientesServerModule::class.java)

    override val migrationPaths = listOf(
        "modules/clientes/migrations/V001__create_customers.sql",
        "modules/clientes/migrations/V002__require_customer_contact_fields.sql",
        "modules/clientes/migrations/V003__add_customer_type.sql",
        "modules/clientes/migrations/V004__add_customer_code.sql",
        "modules/clientes/migrations/V005__optional_customer_contact_fields.sql",
        "modules/clientes/migrations/V006__create_customer_activity.sql",
        "modules/clientes/migrations/V007__customer_profile_sharing.sql",
        "modules/clientes/migrations/V008__company_links_and_merges.sql",
        "modules/clientes/migrations/V009__customer_accounts.sql",
    )

    override val runtimeTables = listOf("customer_activity", "customer_notes", "customer_history", "customer_link_jobs", "customer_merges", "customer_accounts")

    // Copies the company's customers to the companies linked with it, in the background.
    override val jobs: List<ModuleJob>
        get() = listOf(ClientesLinking.job)

    // A duplicated company starts with the same custom customer fields, without customers.
    override val duplicatedSettings = listOf(SettingsTable("customer_field_definitions"))

    override val definition = ModuleDefinition(
        id = ClientesModule.id,
        displayName = ClientesModule.displayName,
        description = "Gestiona la identidad compartida de clientes.",
        version = "0.10.1",
        locked = true,
        permissions = listOf(
            ModulePermissionDefinition("clientes.view", "Ver clientes", "Permite consultar la lista y ficha de clientes."),
            ModulePermissionDefinition("clientes.create", "Crear clientes", "Permite crear clientes."),
            ModulePermissionDefinition("clientes.edit", "Editar clientes", "Permite modificar clientes existentes."),
            ModulePermissionDefinition("clientes.delete", "Eliminar clientes", "Permite eliminar o desactivar clientes."),
            ModulePermissionDefinition("clientes.fields.manage", "Administrar campos de clientes", "Permite crear, editar, desactivar o eliminar campos dinamicos de clientes."),
            ModulePermissionDefinition(ClientesAccounts.LinkPermission, "Enlazar cuentas de usuario a clientes", "Permite enlazar o desenlazar la cuenta de usuario de esta empresa con la ficha de un cliente con el mismo correo. El enlace no da permisos."),
            ModulePermissionDefinition("clientes.share", "Compartir clientes entre empresas", "Habilita las herramientas de agente de empresas vinculadas; vincular y desvincular siempre lo hace el business owner, y los clientes se comparten solos entre las empresas vinculadas."),
        ),
        views = listOf(
            ModuleViewDefinition(
                id = "customers",
                title = "Clientes",
                route = "/modules/clientes/customers",
                kind = "list_create_edit_delete",
                fields = listOf(
                    ModuleFieldDefinition("displayName", "Nombre", "text", required = true),
                    ModuleFieldDefinition("primaryEmail", "Correo", "email"),
                    ModuleFieldDefinition("primaryPhone", "Telefono", "phone"),
                    ModuleFieldDefinition("customerType", "Tipo", "text", readOnly = true),
                    ModuleFieldDefinition("customerCode", "Codigo de cliente", "text", readOnly = true, help = "Se genera automaticamente y no se repite."),
                ),
                actions = listOf(
                    ModuleActionDefinition("list", "Listar clientes", "/modules/clientes/customers", "GET", "clientes.view"),
                    ModuleActionDefinition("create", "Crear cliente", "/modules/clientes/customers", "POST", "clientes.create"),
                    ModuleActionDefinition("edit", "Editar cliente", "/modules/clientes/customers/{id}", "PUT", "clientes.edit"),
                    ModuleActionDefinition("delete", "Eliminar cliente", "/modules/clientes/customers/{id}", "DELETE", "clientes.delete"),
                    ModuleActionDefinition("restore", "Restaurar cliente", "/modules/clientes/customers/{id}/restore", "POST", "clientes.delete"),
                    ModuleActionDefinition("fields", "Administrar campos", "/modules/clientes/fields", "POST", "clientes.fields.manage"),
                    ModuleActionDefinition("deleteField", "Borrar campo", "/modules/clientes/fields/{id}", "DELETE", "clientes.fields.manage"),
                ),
            )
        ),
    )

    override val mcp: ModuleMcp
        get() = ClientesMcpTools.mcp

    override val customerTimeline: CustomerTimeline
        get() = ClientesActivity.timeline

    // Events other modules write in a customer's history (a sale converted in the CRM...).
    override val customerHistoryWriter: CustomerHistoryWriter
        get() = ClientesProfile.writer

    // The customer's notes, shown by other modules where the customer is attended (the CRM lead).
    override val customerNotesReader: CustomerNotesReader
        get() = CustomerNotesReader { access, tenant, customerId, limit -> ClientesProfile.notesFor(access, tenant, customerId, limit) }

    // A customer changed by another module (the CRM editing a lead's contact) reaches every copy.
    override val customerChangeListener: CustomerChangeListener
        get() = CustomerChangeListener { access, tenant, customerId ->
            if (!ClientesSharing.supportsSharing(tenant)) return@CustomerChangeListener
            val company = access.companyOf(tenant) ?: return@CustomerChangeListener
            ClientesSharing.propagate(access, tenant, company.code, customerId)
        }

    override fun routes(route: Route, access: ModuleAccessService) {
        // The customer's profile with its notes and the history of each company (server-driven).
        ClientesProfile.routes(route, access)
        // Linked companies, duplicates to review and the company's log (server-driven, business owner).
        ClientesLinkScreens.routes(route, access)
        route.route("/modules/clientes") {
            get("/fields") {
                if (!call.requireModulePermission(access, ClientesModule.id, "clientes.view")) return@get
                val answer = withContext(Dispatchers.IO) {
                    tenantReply(access, call) { tenant -> json(HttpStatusCode.OK, CustomerFieldsResponse(loadFields(tenant))) }
                }
                answer(call)
            }

            post("/fields") {
                val permissions = call.requirePermission(access, "clientes.fields.manage") ?: return@post
                val request = call.receive<CreateCustomerFieldRequest>()
                if (!validField(request)) {
                    call.respond(HttpStatusCode.BadRequest, ClientesErrorResponse("INVALID_FIELD", "El campo dinamico no es valido."))
                    return@post
                }
                val answer = withContext(Dispatchers.IO) {
                    tenantReply(access, call) { tenant ->
                        if (loadFields(tenant).any { it.fieldKey == request.fieldKey.trim() }) {
                            return@tenantReply failure(HttpStatusCode.Conflict, "FIELD_DUPLICATE", "Ya existe un campo con esa clave.")
                        }
                        val created = insertField(tenant, request)
                        ClientesActivity.recordCompany(tenant, "field_created", fieldCreatedBody(created), ClientesActivity.author(tenant, permissions, access))
                        json(HttpStatusCode.Created, created)
                    }
                }
                answer(call)
            }

            delete("/fields/{fieldId}") {
                val permissions = call.requirePermission(access, "clientes.fields.manage") ?: return@delete
                val fieldId = call.parameters["fieldId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ClientesErrorResponse("INVALID_FIELD", "Campo invalido."))
                val answer = withContext(Dispatchers.IO) {
                    tenantReply(access, call) { tenant ->
                        val deleted = deleteField(tenant, fieldId)
                            ?: return@tenantReply failure(HttpStatusCode.NotFound, "FIELD_NOT_FOUND", "Campo no encontrado.")
                        ClientesActivity.recordCompany(tenant, "field_deleted", fieldDeletedBody(deleted), ClientesActivity.author(tenant, permissions, access))
                        noContent()
                    }
                }
                answer(call)
            }

            get("/customers") {
                if (!call.requireModulePermission(access, ClientesModule.id, "clientes.view")) return@get
                val answer = withContext(Dispatchers.IO) {
                    tenantReply(access, call) { tenant ->
                        val customers = tenant.prepareStatement(
                            """
                            SELECT id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes::text AS flexible_attributes
                            FROM customers
                            WHERE status <> 'archived' AND ${ClientesSharing.Visible}
                            ORDER BY lower(display_name), created_at DESC
                            LIMIT 200
                            """.trimIndent()
                        ).use { query ->
                            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rowCustomer(rows)) } }
                        }
                        json(HttpStatusCode.OK, CustomersResponse(customers))
                    }
                }
                answer(call)
            }

            post("/customers") {
                val permissions = call.requirePermission(access, "clientes.create") ?: return@post
                val request = call.receive<CreateCustomerRequest>()
                val answer = withContext(Dispatchers.IO) {
                    tenantReply(access, call) { tenant ->
                        validateCustomerRequest(tenant, request)?.let { return@tenantReply json(HttpStatusCode.BadRequest, it) }
                        if (emailExists(tenant, request.primaryEmail.trim(), null)) {
                            return@tenantReply failure(HttpStatusCode.Conflict, "CUSTOMER_DUPLICATE_EMAIL", "Ya existe un cliente con ese correo.")
                        }
                        val customerId = UUID.randomUUID()
                        upsertCustomer(tenant, customerId, request, insert = true)
                        ClientesActivity.record(tenant, customerId, "created", "", ClientesActivity.author(tenant, permissions, access))
                        shareNew(access, tenant, permissions.companyCode, customerId)
                        // The real state: sharing may have merged the new customer into an older one (it is then archived).
                        json(HttpStatusCode.Created, customerAnyStatus(tenant, customerId) ?: request.toResponse(customerId, "active", customerCode(tenant, customerId)))
                    }
                }
                answer(call)
            }

            put("/customers/{customerId}") {
                val permissions = call.requirePermission(access, "clientes.edit") ?: return@put
                val customerId = call.parameters["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ClientesErrorResponse("INVALID_CUSTOMER", "Cliente invalido."))
                val request = call.receive<CreateCustomerRequest>()
                val answer = withContext(Dispatchers.IO) {
                    tenantReply(access, call) { tenant ->
                        validateCustomerRequest(tenant, request)?.let { return@tenantReply json(HttpStatusCode.BadRequest, it) }
                        if (emailExists(tenant, request.primaryEmail.trim(), customerId)) {
                            return@tenantReply failure(HttpStatusCode.Conflict, "CUSTOMER_DUPLICATE_EMAIL", "Ya existe otro cliente con ese correo.")
                        }
                        val before = activeCustomer(tenant, customerId)?.takeIf { ClientesSharing.visible(tenant, customerId) }
                        val updated = before != null && upsertCustomer(tenant, customerId, request, insert = false)
                        if (!updated) return@tenantReply failure(HttpStatusCode.NotFound, "CUSTOMER_NOT_FOUND", "Cliente no encontrado.")
                        recordUpdate(tenant, customerId, before!!, request, ClientesActivity.author(tenant, permissions, access))
                        // One record for the companies it is shared with: the edit reaches every copy.
                        propagateLogged(access, tenant, permissions.companyCode, customerId)
                        json(HttpStatusCode.OK, request.toResponse(customerId, "active", customerCode(tenant, customerId)))
                    }
                }
                answer(call)
            }

            delete("/customers/{customerId}") {
                val permissions = call.requirePermission(access, "clientes.delete") ?: return@delete
                val customerId = call.parameters["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ClientesErrorResponse("INVALID_CUSTOMER", "Cliente invalido."))
                val answer = withContext(Dispatchers.IO) {
                    tenantReply(access, call) { tenant ->
                        originCheck(tenant, customerId, "archiva")?.let { return@tenantReply it }
                        if (!archiveCustomer(tenant, customerId)) return@tenantReply failure(HttpStatusCode.NotFound, "CUSTOMER_NOT_FOUND", "Cliente no encontrado.")
                        ClientesActivity.record(tenant, customerId, "archived", "", ClientesActivity.author(tenant, permissions, access))
                        propagateLogged(access, tenant, permissions.companyCode, customerId)
                        noContent()
                    }
                }
                answer(call)
            }

            post("/customers/{customerId}/restore") {
                val permissions = call.requirePermission(access, "clientes.delete") ?: return@post
                val customerId = call.parameters["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ClientesErrorResponse("INVALID_CUSTOMER", "Cliente invalido."))
                val answer = withContext(Dispatchers.IO) {
                    tenantReply(access, call) { tenant ->
                        // Only the company where the customer was created restores it; the copies follow.
                        originCheck(tenant, customerId, "restaura")?.let { return@tenantReply it }
                        when (restoreCustomer(tenant, customerId)) {
                            RestoreResult.NotFound -> return@tenantReply failure(HttpStatusCode.NotFound, "CUSTOMER_NOT_FOUND", "Cliente no encontrado o no esta archivado.")
                            RestoreResult.EmailTaken -> return@tenantReply failure(HttpStatusCode.Conflict, "CUSTOMER_DUPLICATE_EMAIL", "Otro cliente activo ya usa el correo de este cliente.")
                            RestoreResult.Restored -> Unit
                        }
                        ClientesActivity.record(tenant, customerId, "restored", "", ClientesActivity.author(tenant, permissions, access))
                        propagateLogged(access, tenant, permissions.companyCode, customerId)
                        json(HttpStatusCode.OK, activeCustomer(tenant, customerId)!!)
                    }
                }
                answer(call)
            }

            // The customer's history from Clientes and every installed module the user may see.
            get("/customers/{customerId}/history") {
                val permissions = call.requirePermission(access, "clientes.view") ?: return@get
                val customerId = call.parameters["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ClientesErrorResponse("INVALID_CUSTOMER", "Cliente invalido."))
                val answer = withContext(Dispatchers.IO) {
                    tenantReply(access, call) { tenant ->
                        if (!ClientesSharing.visible(tenant, customerId)) return@tenantReply failure(HttpStatusCode.NotFound, "CUSTOMER_NOT_FOUND", "Cliente no encontrado.")
                        json(HttpStatusCode.OK, CustomerHistoryResponse(access.customerHistory(tenant, permissions, customerId)))
                    }
                }
                answer(call)
            }
        }
    }

    // Blocking database work never runs in Ktor's coroutines: routes build their answer on an IO thread and send it afterwards.
    private inline fun <reified T : Any> json(status: HttpStatusCode, body: T): ClientesReply = { respond(status, body) }

    private fun noContent(): ClientesReply = { respond(HttpStatusCode.NoContent) }

    private fun failure(status: HttpStatusCode, code: String, message: String): ClientesReply = { respond(status, ClientesErrorResponse(code, message)) }

    // Opens the user's company database, runs [block] and closes it; answers 403 when it cannot be opened.
    private fun tenantReply(access: ModuleAccessService, call: ApplicationCall, block: (Connection) -> ClientesReply): ClientesReply {
        val connection = access.tenantConnection(call) ?: return failure(HttpStatusCode.Forbidden, "TENANT_UNAVAILABLE", "No se pudo abrir la empresa.")
        return connection.use(block)
    }

    /**
     * Archiving and restoring belong to the company where the customer was created. Null when this company is the
     * origin; 404 when the customer is not visible here (a hidden copy does not exist for this company); 409 for a copy.
     * Checked before writing anything.
     */
    private fun originCheck(tenant: Connection, customerId: UUID, verb: String): ClientesReply? = when {
        !ClientesSharing.visible(tenant, customerId) -> failure(HttpStatusCode.NotFound, "CUSTOMER_NOT_FOUND", "Cliente no encontrado.")
        !ClientesSharing.isOrigin(tenant, customerId) -> failure(HttpStatusCode.Conflict, "CUSTOMER_NOT_ORIGIN", "Este cliente se $verb desde la empresa donde se creo.")
        else -> null
    }

    /** Copies the change to the linked companies; the ones that could not be updated are logged and returned. */
    internal fun propagateLogged(access: ModuleAccessService, tenant: Connection, companyCode: String, customerId: UUID): List<String> =
        ClientesSharing.propagate(access, tenant, companyCode, customerId).also { failed ->
            if (failed.isNotEmpty()) log.warn("Customer {} not updated in the linked companies {}", customerId, failed.joinToString())
        }

    // The customer's row whatever its status (a merged duplicate is archived), or null when it does not exist.
    internal fun customerAnyStatus(tenant: Connection, id: UUID): CustomerResponse? = tenant.prepareStatement(
        "SELECT id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes::text AS flexible_attributes FROM customers WHERE id = ?"
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) rowCustomer(rows) else null }
    }

    /** What a new customer was merged into, when sharing found the same email in a linked company: "name (company)"; null if not merged. */
    internal fun mergedInto(tenant: Connection, id: UUID): String? = tenant.prepareStatement(
        "SELECT kept_name, kept_company_code FROM customer_merges WHERE merged_id = ? AND status = 'merged' ORDER BY created_at DESC LIMIT 1"
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) "${rows.getString(1)} (${rows.getString(2)})" else null }
    }

    // The user's permissions in Clientes when they hold [permissionId]; otherwise answers 401/403 and returns null.
    private suspend fun ApplicationCall.requirePermission(access: ModuleAccessService, permissionId: String): ModulePermissions? {
        val permissions = requireModuleAccess(access, ClientesModule.id) ?: return null
        if (!permissions.has(permissionId)) {
            respond(HttpStatusCode.Forbidden, ClientesErrorResponse("missing_permission", "No tienes permiso para usar esta funcion."))
            return null
        }
        return permissions
    }

    internal fun activeCustomer(tenant: Connection, id: UUID): CustomerResponse? = tenant.prepareStatement(
        "SELECT id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes::text AS flexible_attributes FROM customers WHERE id = ? AND status <> 'archived'"
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) rowCustomer(rows) else null }
    }

    // Archived customers keep their history.
    internal fun customerExists(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("SELECT 1 FROM customers WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { it.next() }
        }

    // Records what changed in an edit; an edit that changed nothing is not recorded.
    internal fun recordUpdate(tenant: Connection, id: UUID, before: CustomerResponse, request: CreateCustomerRequest, author: Pair<UUID?, String>) {
        val labels = loadFields(tenant).associate { it.fieldKey to it.label }
        ClientesActivity.changes(before, request, labels)?.let { ClientesActivity.record(tenant, id, "updated", it, author) }
    }

    private val fieldKeyPattern = Regex("[a-z][a-z0-9_]{0,62}")

    internal fun validField(request: CreateCustomerFieldRequest): Boolean =
        fieldKeyPattern.matches(request.fieldKey.trim()) && request.label.isNotBlank() && request.fieldType.trim() in ClientesModule.fieldTypes

    // A field deleted earlier keeps its key (it is unique): creating it again brings the same row back with the new definition.
    internal fun insertField(tenant: Connection, request: CreateCustomerFieldRequest): CustomerFieldResponse {
        val fieldKey = request.fieldKey.trim()
        val label = request.label.trim()
        val fieldType = request.fieldType.trim()
        val fieldId = tenant.prepareStatement(
            """
            INSERT INTO customer_field_definitions (id, field_key, label, field_type, control_type, is_required, display_order)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (field_key) DO UPDATE SET label = EXCLUDED.label, field_type = EXCLUDED.field_type,
                control_type = EXCLUDED.control_type, is_required = EXCLUDED.is_required,
                display_order = EXCLUDED.display_order, is_active = TRUE
            WHERE customer_field_definitions.is_active = FALSE
            RETURNING id
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, fieldKey)
            insert.setString(3, label)
            insert.setString(4, fieldType)
            insert.setString(5, request.controlType.ifBlank { fieldType })
            insert.setBoolean(6, request.required)
            insert.setInt(7, request.displayOrder)
            insert.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: throw ClientesSharingException("Ya existe un campo con la clave '$fieldKey'.")
        return CustomerFieldResponse(fieldId.toString(), fieldKey, label, fieldType, request.required, true, request.displayOrder)
    }

    /** Deactivates a custom field (its values stay in the customers). Null when it does not exist or is already inactive. */
    internal fun deleteField(tenant: Connection, fieldId: UUID): CustomerFieldResponse? = tenant.prepareStatement(
        """
        UPDATE customer_field_definitions SET is_active = FALSE
        WHERE id = ? AND is_active = TRUE
        RETURNING id, field_key, label, field_type, is_required, is_active, display_order
        """.trimIndent()
    ).use { update ->
        update.setObject(1, fieldId)
        update.executeQuery().use { rows ->
            if (rows.next()) {
                CustomerFieldResponse(rows.getObject("id", UUID::class.java).toString(), rows.getString("field_key"), rows.getString("label"), rows.getString("field_type"), rows.getBoolean("is_required"), false, rows.getInt("display_order"))
            } else {
                null
            }
        }
    }

    internal fun fieldCreatedBody(field: CustomerFieldResponse): String =
        "Campo ${field.label} (${field.fieldKey}), tipo ${field.fieldType}${if (field.required) ", obligatorio" else ""}"

    internal fun fieldDeletedBody(field: CustomerFieldResponse): String = "Campo ${field.label} (${field.fieldKey})"

    enum class RestoreResult { Restored, NotFound, EmailTaken }

    // Brings an archived customer back; not when another active customer already uses its email (they would be duplicates).
    internal fun restoreCustomer(tenant: Connection, customerId: UUID): RestoreResult {
        val email = tenant.prepareStatement("SELECT primary_email FROM customers WHERE id = ? AND status = 'archived'").use { query ->
            query.setObject(1, customerId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: return RestoreResult.NotFound
        if (emailExists(tenant, email, customerId)) return RestoreResult.EmailTaken
        val restored = tenant.prepareStatement("UPDATE customers SET status = 'active', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'archived'").use { update ->
            update.setObject(1, customerId)
            update.executeUpdate() == 1
        }
        return if (restored) RestoreResult.Restored else RestoreResult.NotFound
    }

    // After creating a customer: copies it to the linked companies at once. Never fails the creation; the background job copies it anyway.
    internal fun shareNew(access: ModuleAccessService, tenant: Connection, companyCode: String, customerId: UUID) {
        try {
            ClientesLinking.shareNew(access, tenant, companyCode, customerId)
        } catch (failure: Exception) {
            log.warn("New customer {} not copied to the linked companies yet: {}", customerId, failure.message)
        }
    }

    // Customers are archived, never deleted. False when the customer does not exist or is already archived.
    internal fun archiveCustomer(tenant: Connection, customerId: UUID): Boolean =
        tenant.prepareStatement("UPDATE customers SET status = 'archived', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status <> 'archived'").use { update ->
            update.setObject(1, customerId)
            update.executeUpdate() == 1
        }

    internal fun validateCustomerRequest(connection: Connection, request: CreateCustomerRequest): ClientesErrorResponse? {
        // Email and phone are optional since V005; only the name is required.
        if (request.displayName.isBlank()) {
            return ClientesErrorResponse("CUSTOMER_REQUIRED_FIELDS", "El nombre es obligatorio.")
        }
        val fields = loadFields(connection).filter { it.required && it.active }
        val missing = fields.firstOrNull { field -> request.dynamicFields[field.fieldKey] == null }
        return missing?.let { ClientesErrorResponse("CUSTOMER_DYNAMIC_FIELD_REQUIRED", "El campo ${it.label} es obligatorio.") }
    }

    internal fun loadFields(connection: Connection): List<CustomerFieldResponse> = connection.prepareStatement(
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

    // Customers without email never collide with each other.
    internal fun emailExists(connection: Connection, email: String, excluding: UUID?): Boolean = email.isNotBlank() && connection.prepareStatement(
        "SELECT 1 FROM customers WHERE lower(primary_email) = lower(?) AND status <> 'archived' AND (CAST(? AS uuid) IS NULL OR id <> ?)"
    ).use { query ->
        query.setString(1, email)
        query.setObject(2, excluding)
        query.setObject(3, excluding)
        query.executeQuery().use { it.next() }
    }

    internal fun upsertCustomer(connection: Connection, id: UUID, request: CreateCustomerRequest, insert: Boolean): Boolean {
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

    // The database generates the code on insert (customers_generate_code) and it never changes.
    internal fun customerCode(connection: Connection, id: UUID): String = connection.prepareStatement(
        "SELECT customer_code FROM customers WHERE id = ?"
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "" }
    }

    internal fun rowCustomer(rows: java.sql.ResultSet): CustomerResponse = CustomerResponse(
        id = rows.getObject("id", UUID::class.java).toString(),
        displayName = rows.getString("display_name"),
        primaryEmail = rows.getString("primary_email"),
        primaryPhone = rows.getString("primary_phone"),
        status = rows.getString("status"),
        customerType = rows.getString("customer_type"),
        customerCode = rows.getString("customer_code"),
        dynamicFields = Json.parseToJsonElement(rows.getString("flexible_attributes")).jsonObject,
    )

    private fun CreateCustomerRequest.toResponse(id: UUID, status: String, customerCode: String): CustomerResponse =
        CustomerResponse(id.toString(), displayName.trim(), primaryEmail.trim(), primaryPhone.trim(), status, dynamicFields, customerCode = customerCode)
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
data class CustomerResponse(val id: String, val displayName: String, val primaryEmail: String, val primaryPhone: String, val status: String, val dynamicFields: JsonObject = JsonObject(emptyMap()), val customerType: String = "general", val customerCode: String = "")

@Serializable
data class CustomersResponse(val customers: List<CustomerResponse>)

@Serializable
data class CustomerHistoryResponse(val entries: List<CustomerHistoryEntry>)

@Serializable
data class ClientesErrorResponse(val code: String, val message: String)

// What a route answers, built on an IO thread and sent afterwards.
private typealias ClientesReply = suspend ApplicationCall.() -> Unit

class ClientesServerModuleProvider : ServerModuleProvider { override fun create(): ServerModule = ClientesServerModule }
