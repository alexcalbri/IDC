package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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

    // What this module tells agents about itself (see ModuleMcp).
    val mcp: ModuleMcp
        get() = ModuleMcp(Instructions, tools)

    private const val Instructions =
        "Clientes es la base de contactos de la empresa; otros modulos (CRM, hotspot) usan estos mismos clientes. " +
            "Busca antes de crear para no duplicar: no se permiten dos clientes con el mismo correo. Cada cliente tiene un codigo " +
            "(customerCode) que tambien sirve para identificarlo. Los campos personalizados de la empresa se envian en dynamicFields " +
            "por su clave; consultalos con list_customer_fields. Archivar no borra al cliente: deja de aparecer en la lista y en las busquedas. " +
            "get_customer_history reune lo que paso con el cliente en todos los modulos (cambios, leads, reservas, WiFi...). " +
            "Las empresas del mismo dueno se pueden vincular: al vincularlas se comparten TODOS sus clientes y los que tienen el mismo correo " +
            "se fusionan solos (queda el mas antiguo); lo que no se puede fusionar queda en 'por revisar'. Vincular y desvincular solo lo " +
            "puede el business owner: confirma siempre con el usuario antes, porque cambia lo que ve cada empresa. " +
            "Un cliente archivado se puede restaurar con restore_customer; borrar un campo personalizado lo oculta pero conserva los valores."

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
            name = "get_customer_history",
            title = "Historial del cliente",
            description = "Historial del cliente, del mas reciente al mas antiguo: quien lo creo, edito o archivo y que cambio, y lo que " +
                "registran los otros modulos instalados que el usuario puede ver (leads y reservas del CRM, conexiones WiFi del hotspot...). " +
                "Incluye clientes archivados.",
            permission = "clientes.view",
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.uuid("Id del cliente."),
                    "limit" to McpSchema.integer("Cantidad maxima de eventos.", 1, 200),
                ),
                required = listOf("id"),
            ),
        ) { call -> history(call) },
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
        McpTool(
            name = "archive_customer",
            title = "Archivar cliente",
            description = "Archiva un cliente: deja de aparecer en la lista y en las busquedas, pero conserva sus datos e historial. " +
                "Confirma con el usuario antes.",
            permission = "clientes.delete",
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del cliente.")), required = listOf("id")),
        ) { call -> archive(call) },
        McpTool(
            name = "create_customer_field",
            title = "Crear campo personalizado de clientes",
            description = "Agrega un campo personalizado a la ficha de todos los clientes de la empresa. Revisa antes list_customer_fields para no duplicarlo.",
            permission = "clientes.fields.manage",
            inputSchema = McpSchema.obj(
                mapOf(
                    "fieldKey" to McpSchema.string("Clave unica en minusculas, por ejemplo fecha_nacimiento ([a-z][a-z0-9_]*, hasta 63)."),
                    "label" to McpSchema.string("Nombre visible del campo."),
                    "fieldType" to McpSchema.string("Tipo de dato.", ClientesModule.fieldTypes.sorted()),
                    "required" to McpSchema.boolean("Si es obligatorio al crear o editar clientes."),
                    "displayOrder" to McpSchema.integer("Orden en la ficha; menor aparece antes.", 0, 10_000),
                ),
                required = listOf("fieldKey", "label", "fieldType"),
            ),
        ) { call -> createField(call) },
        McpTool(
            name = "restore_customer",
            title = "Restaurar cliente",
            description = "Restaura un cliente archivado en la empresa donde se creo; vuelve a la lista y a las empresas vinculadas. " +
                "No se puede si otro cliente activo ya usa su correo.",
            permission = "clientes.delete",
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del cliente archivado.")), required = listOf("id")),
        ) { call -> restore(call) },
        McpTool(
            name = "delete_customer_field",
            title = "Borrar campo personalizado de clientes",
            description = "Borra un campo personalizado: deja de aparecer en la ficha y en los formularios, pero los valores ya guardados " +
                "en los clientes se conservan. Confirma con el usuario antes.",
            permission = "clientes.fields.manage",
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del campo (ver list_customer_fields).")), required = listOf("id")),
        ) { call -> deleteField(call) },
        McpTool(
            name = "get_company_links",
            title = "Empresas vinculadas",
            description = "Muestra las empresas vinculadas con la actual para compartir clientes, las que se pueden vincular, el avance " +
                "de la copia y cuantas fusiones estan por revisar. Solo el business owner.",
            permission = "clientes.share",
            readOnly = true,
            handler = ownerOnly { call -> companyLinks(call) },
        ),
        McpTool(
            name = "link_company",
            title = "Vincular empresa",
            description = "Vincula otra empresa del mismo dueno con la actual: se comparten TODOS los clientes de ambas y los que tengan el " +
                "mismo correo se fusionan solos (queda el mas antiguo). La copia se hace en segundo plano. Solo el business owner; " +
                "confirma con el usuario antes.",
            permission = "clientes.share",
            inputSchema = McpSchema.obj(mapOf("companyCode" to McpSchema.string("Codigo de la empresa a vincular (ver get_company_links).")), required = listOf("companyCode")),
            handler = ownerOnly { call -> link(call) },
        ),
        McpTool(
            name = "unlink_company",
            title = "Desvincular empresa",
            description = "Desvincula una empresa del grupo: deja de ver los clientes de las demas y las demas los suyos. No borra ningun cliente. " +
                "Solo el business owner; confirma con el usuario antes.",
            permission = "clientes.share",
            inputSchema = McpSchema.obj(mapOf("companyCode" to McpSchema.string("Codigo de la empresa a desvincular.")), required = listOf("companyCode")),
            handler = ownerOnly { call -> unlink(call) },
        ),
        McpTool(
            name = "list_merge_reviews",
            title = "Fusiones por revisar",
            description = "Lista las fusiones de clientes duplicados (mismo correo) que quedaron por revisar o ya se hicieron, con el motivo. Solo el business owner.",
            permission = "clientes.share",
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("limit" to McpSchema.integer("Cantidad maxima.", 1, 200))),
            handler = ownerOnly { call -> mergeReviews(call) },
        ),
        McpTool(
            name = "separate_merge",
            title = "Separar clientes fusionados",
            description = "Deshace una fusion: el cliente fusionado vuelve a estar activo y sus registros se le devuelven; nunca se vuelven a " +
                "fusionar solos. Los campos que se completaron en el cliente que quedo no se deshacen. Solo el business owner; confirma antes.",
            permission = "clientes.share",
            inputSchema = McpSchema.obj(
                mapOf(
                    "companyCode" to McpSchema.string("Codigo de la empresa donde esta la fusion (ver list_merge_reviews)."),
                    "mergeId" to McpSchema.uuid("Id de la fusion (ver list_merge_reviews)."),
                ),
                required = listOf("companyCode", "mergeId"),
            ),
            handler = ownerOnly { call -> separate(call) },
        ),
    )

    private fun search(call: McpToolCall): JsonElement {
        val query = call.string("query", 100).lowercase()
        val limit = call.int("limit", 20, 1..100)
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val customers = call.tenant.prepareStatement(
            """
            SELECT $Columns FROM customers
            WHERE status <> 'archived' AND ${ClientesSharing.Visible}
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
        ClientesActivity.record(call.tenant, id, "created", "", author(call))
        ClientesServerModule.shareNew(call.access, call.tenant, call.permissions.companyCode, id)
        // Sharing may have merged the new customer into an older one with the same email: it is then archived.
        val created = find(call, id, "")
        if (created != null) return Json.encodeToJsonElement(created)
        val into = ClientesServerModule.mergedInto(call.tenant, id) ?: "un cliente existente con el mismo correo"
        return buildJsonObject {
            put("id", id.toString())
            put("merged", true)
            put("result", "El cliente se fusiono con $into porque tiene el mismo correo; queda el mas antiguo. Busca ese cliente para seguir.")
        }
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
        ClientesServerModule.recordUpdate(call.tenant, id, current, request, author(call))
        val failed = ClientesServerModule.propagateLogged(call.access, call.tenant, call.permissions.companyCode, id)
        return withWarning(Json.encodeToJsonElement(find(call, id, "")!!), failed)
    }

    // Adds a warning to the result when the change did not reach every linked company (the next sync fixes it).
    private fun withWarning(result: JsonElement, failed: List<String>): JsonElement {
        if (failed.isEmpty() || result !is JsonObject) return result
        return JsonObject(result + ("warning" to kotlinx.serialization.json.JsonPrimitive("El cambio no llego todavia a: ${failed.joinToString()}. Se corrige solo en el siguiente minuto.")))
    }

    private fun archive(call: McpToolCall): JsonElement {
        val id = call.requiredUuid("id")
        requireOrigin(call, id, "archiva")
        if (!ClientesServerModule.archiveCustomer(call.tenant, id)) throw McpToolException("Cliente no encontrado o ya archivado.")
        ClientesActivity.record(call.tenant, id, "archived", "", author(call))
        val failed = ClientesServerModule.propagateLogged(call.access, call.tenant, call.permissions.companyCode, id)
        return withWarning(
            buildJsonObject {
                put("id", id.toString())
                put("result", "Cliente archivado.")
            },
            failed,
        )
    }

    private fun author(call: McpToolCall) = ClientesActivity.author(call.tenant, call.permissions, call.access, call.agentName)

    private fun history(call: McpToolCall): JsonElement {
        val id = call.requiredUuid("id")
        if (!ClientesSharing.visible(call.tenant, id)) throw McpToolException("Cliente no encontrado.")
        return Json.encodeToJsonElement(CustomerHistoryResponse(call.access.customerHistory(call.tenant, call.permissions, id, call.int("limit", 50, 1..200))))
    }

    private fun createField(call: McpToolCall): JsonElement {
        val request = CreateCustomerFieldRequest(
            fieldKey = call.requiredString("fieldKey", 63),
            label = call.requiredString("label", 200),
            fieldType = call.requiredString("fieldType", 40),
            required = call.boolean("required"),
            displayOrder = call.int("displayOrder", 0, 0..10_000),
        )
        if (!ClientesServerModule.validField(request)) {
            throw McpToolException("Campo no valido: la clave debe cumplir [a-z][a-z0-9_]* y el tipo ser uno de ${ClientesModule.fieldTypes.sorted().joinToString()}.")
        }
        if (ClientesServerModule.loadFields(call.tenant).any { it.fieldKey == request.fieldKey.trim() }) {
            throw McpToolException("Ya existe un campo con la clave '${request.fieldKey.trim()}'.")
        }
        val field = ClientesServerModule.insertField(call.tenant, request)
        ClientesActivity.recordCompany(call.tenant, "field_created", ClientesServerModule.fieldCreatedBody(field), author(call))
        return Json.encodeToJsonElement(field)
    }

    private fun deleteField(call: McpToolCall): JsonElement {
        val field = ClientesServerModule.deleteField(call.tenant, call.requiredUuid("id")) ?: throw McpToolException("Campo no encontrado.")
        ClientesActivity.recordCompany(call.tenant, "field_deleted", ClientesServerModule.fieldDeletedBody(field), author(call))
        return buildJsonObject {
            put("id", field.id)
            put("result", "Campo borrado.")
        }
    }

    private fun restore(call: McpToolCall): JsonElement {
        val id = call.requiredUuid("id")
        requireOrigin(call, id, "restaura")
        when (ClientesServerModule.restoreCustomer(call.tenant, id)) {
            ClientesServerModule.RestoreResult.NotFound -> throw McpToolException("Cliente no encontrado o no esta archivado.")
            ClientesServerModule.RestoreResult.EmailTaken -> throw McpToolException("Otro cliente activo ya usa el correo de este cliente.")
            ClientesServerModule.RestoreResult.Restored -> Unit
        }
        ClientesActivity.record(call.tenant, id, "restored", "", author(call))
        val failed = ClientesServerModule.propagateLogged(call.access, call.tenant, call.permissions.companyCode, id)
        return withWarning(Json.encodeToJsonElement(find(call, id, "")!!), failed)
    }

    // Archiving and restoring belong to the company where the customer was created: a hidden copy does not exist for this company.
    private fun requireOrigin(call: McpToolCall, id: UUID, verb: String) {
        if (!ClientesSharing.visible(call.tenant, id)) throw McpToolException("Cliente no encontrado.")
        if (!ClientesSharing.isOrigin(call.tenant, id)) throw McpToolException("Este cliente se $verb desde la empresa donde se creo.")
    }

    // Linking changes what every company of the group sees: only the business owner, whatever permission the agent holds.
    fun requireBusinessOwner(permissions: com.ideasdeveloper.idc.server.modules.ModulePermissions) {
        if (!permissions.businessOwner) throw McpToolException("Solo el business owner puede administrar las empresas vinculadas.")
    }

    // Wraps a linking tool so the business owner check runs before anything else, for every tool of the group.
    private fun ownerOnly(handler: (McpToolCall) -> JsonElement): (McpToolCall) -> JsonElement = { call ->
        requireBusinessOwner(call.permissions)
        handler(call)
    }

    private fun company(company: com.ideasdeveloper.idc.server.modules.GroupCompany): JsonObject = buildJsonObject {
        put("code", company.code)
        put("name", company.name)
    }

    private fun companyLinks(call: McpToolCall): JsonElement {
        val status = ClientesLinking.status(call.access, call.permissions.companyCode)
        return buildJsonObject {
            put("linked", JsonArray(status.linked.map(::company)))
            put("candidates", JsonArray(status.candidates.map(::company)))
            put("reviewCount", status.reviewCount)
            put(
                "jobs",
                buildJsonArray {
                    status.jobs.forEach { job ->
                        add(
                            buildJsonObject {
                                put("from", job.from.name)
                                put("to", job.to.name)
                                put("status", job.status)
                                put("copied", job.copied)
                                put("merged", job.merged)
                                put("review", job.review)
                                put("total", job.total)
                                put("error", job.error)
                            }
                        )
                    }
                },
            )
        }
    }

    private fun link(call: McpToolCall): JsonElement {
        return linkResult(call) { target -> ClientesLinking.link(call.access, call.tenant, call.permissions.companyCode, target, author(call)) }
    }

    private fun unlink(call: McpToolCall): JsonElement {
        return linkResult(call) { target -> ClientesLinking.unlink(call.access, call.tenant, call.permissions.companyCode, target, author(call)) }
    }

    // Runs a link operation on the company the agent named, turning the module's messages into tool errors.
    private fun linkResult(call: McpToolCall, action: (String) -> String): JsonElement {
        val message = try {
            action(call.requiredString("companyCode", 63))
        } catch (failure: ClientesSharingException) {
            throw McpToolException(failure.message.orEmpty())
        } catch (failure: com.ideasdeveloper.idc.server.modules.CompanyLinkException) {
            throw McpToolException(failure.message.orEmpty())
        }
        return buildJsonObject { put("result", message) }
    }

    private fun mergeReviews(call: McpToolCall): JsonElement {
        val items = ClientesLinking.reviews(call.access, call.permissions.companyCode, call.int("limit", 50, 1..200))
        return buildJsonObject {
            put(
                "merges",
                buildJsonArray {
                    items.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id.toString())
                                put("companyCode", item.company.code)
                                put("company", item.company.name)
                                put("status", item.status)
                                put("reason", item.reason)
                                put("email", item.email)
                                put("keptId", item.keptId.toString())
                                put("keptName", item.keptName)
                                put("keptCompany", item.keptCompany)
                                put("mergedId", item.mergedId.toString())
                                put("mergedName", item.mergedName)
                                put("at", item.at.toString())
                            }
                        )
                    }
                },
            )
        }
    }

    private fun separate(call: McpToolCall): JsonElement {
        val message = try {
            ClientesLinking.separate(call.access, call.permissions.companyCode, call.requiredString("companyCode", 63), call.requiredUuid("mergeId"), author(call))
        } catch (failure: ClientesSharingException) {
            throw McpToolException(failure.message.orEmpty())
        }
        return buildJsonObject { put("result", message) }
    }

    private fun find(call: McpToolCall, id: UUID?, code: String): CustomerResponse? =
        call.tenant.prepareStatement(
            "SELECT $Columns FROM customers WHERE status <> 'archived' AND ${ClientesSharing.Visible} AND ${if (id != null) "id = ?" else "customer_code = ?"}"
        ).use { query ->
            if (id != null) query.setObject(1, id) else query.setString(1, code)
            query.executeQuery().use { rows -> if (rows.next()) ClientesServerModule.rowCustomer(rows) else null }
        }
}
