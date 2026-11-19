package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.util.UUID

/**
 * Herramientas para agentes de Mantenimiento. Cada una declara su permiso y además aplica por orden las mismas reglas que las
 * pantallas ([MantenimientoScope] y [OrderTransitions]) con los permisos vivos del usuario: el agente nunca ve ni cambia más que él.
 * Los filtros de visibilidad van en SQL (`listOrders`). Ninguna herramienta maneja credenciales.
 */
object MantenimientoMcpTools {
    const val Instructions =
        "Mantenimiento organiza las órdenes de trabajo de la empresa por propiedad y activo. Cada orden tiene un folio (OT-0007), un tipo " +
            "(preventive la genera un plan, corrective una avería, project un proyecto con aprobación), una prioridad (urgent, high, medium, low) y un estado fijo: " +
            "requested Pendiente de aprobación, quoting En cotización, open Abierta, in_progress En curso, on_hold En espera, done Completada, rejected Rechazada, cancelled Cancelada. " +
            "Los proyectos nacen requested y solo quien gestiona los aprueba (open) o rechaza (rejected, con reason); el resto nace open. " +
            "Solo ves las órdenes que tu usuario puede ver (quien no ejecuta ni gestiona ve las que reportó o tiene asignadas) y solo cambias lo que sus permisos permiten. " +
            "Para reportar una avería: list_assets para elegir el activo y create_order con kind=corrective; con external_ref estable no se duplica (created=false). " +
            "Responsable y fecha límite al crear, y los datos de la orden al editar, son de quien gestiona; quien trabaja la orden cambia su estado, costo real y resolución. " +
            "list_people muestra a quienes pueden ser responsables. upcoming resume vencidas y próximas. " +
            "Antes de crear varias órdenes muestra al usuario el resumen y espera su confirmación. Los archivos adjuntos se gestionan solo desde la app."
    private const val MaxListedByAgent = 100
    private const val MaxExternalRef = 190

    private val priorityCodes = OrderPriority.entries.map { it.id }
    private val statusCodes = OrderStatus.entries.map { it.id }
    private val kindCodes = OrderKind.entries.map { it.id }
    private val creatableKinds = listOf(OrderKind.Corrective.id, OrderKind.Project.id)

    /** Argumentos de datos de la orden compartidos por create_order y update_order. */
    private val orderFields = mapOf(
        "title" to McpSchema.string("Título de la orden (hasta ${OrderInput.MaxTitle} caracteres)."),
        "description" to McpSchema.string("Descripción (hasta ${OrderInput.MaxDescription} caracteres)."),
        "property" to McpSchema.string("Propiedad, por nombre o id (ver list_assets). Si indicas asset_id, la propiedad es la del activo."),
        "asset_id" to McpSchema.uuid("Id del activo (ver list_assets)."),
        "priority" to McpSchema.string("Prioridad; por defecto medium.", priorityCodes),
        "department" to McpSchema.string("Departamento que solicita (hasta ${OrderInput.MaxDepartment} caracteres)."),
    )

    /** Datos que solo cambia quien gestiona: fecha, responsable y cotización. */
    private val managerFields = mapOf(
        "due_date" to McpSchema.date("Fecha límite. Solo quien gestiona."),
        "assignee" to McpSchema.string("Responsable, por nombre o correo tal como lo devuelve list_people. Solo quien gestiona."),
        "quote_amount" to McpSchema.number("Monto cotizado (proyectos). Solo quien gestiona."),
        "quote_currency" to McpSchema.string("Moneda de la cotización, 3 letras mayúsculas; por defecto USD."),
        "quoted_by" to McpSchema.string("Quién cotizó (hasta ${OrderInput.MaxPerson} caracteres)."),
    )

    private val idOrNumber = mapOf(
        "id" to McpSchema.uuid("Id de la orden."),
        "number" to McpSchema.string("Folio de la orden, por ejemplo OT-0007 o 7. Alternativa a id."),
    )

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "list_orders",
            title = "Buscar órdenes",
            description = "Lista las órdenes que el usuario puede ver, por fecha límite y prioridad. Por defecto solo las abiertas (ni completadas, ni rechazadas, ni canceladas). " +
                "Filtra por estado, tipo, propiedad, activo, responsable (\"me\" para las del usuario), fecha límite o texto en título, descripción y folio.",
            permission = MantenimientoModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "status" to McpSchema.string("Solo órdenes en este estado (incluye cerradas si se pide).", statusCodes),
                    "kind" to McpSchema.string("Solo este tipo de orden.", kindCodes),
                    "property" to McpSchema.string("Propiedad, por nombre o id."),
                    "asset_id" to McpSchema.uuid("Solo órdenes de este activo."),
                    "assignee" to McpSchema.string("Responsable: \"me\" para el usuario, o un id, nombre o correo."),
                    "due_before" to McpSchema.date("Solo órdenes con fecha límite igual o anterior."),
                    "q" to McpSchema.string("Texto a buscar en título, descripción o folio."),
                    "include_closed" to McpSchema.boolean("Incluir completadas, rechazadas y canceladas (por defecto no)."),
                    "limit" to McpSchema.integer("Cantidad máxima; por defecto 50.", 1, MaxListedByAgent),
                ),
            ),
        ) { call -> guarded { listOrders(call) } },
        McpTool(
            name = "get_order",
            title = "Ver orden",
            description = "Devuelve una orden visible con su detalle, lista de comprobación, notas recientes, nombres de adjuntos y lo que el usuario puede hacer con ella (can). Indica id o number.",
            permission = MantenimientoModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(idOrNumber),
        ) { call -> guarded { getOrder(call) } },
        McpTool(
            name = "list_plans",
            title = "Planes preventivos",
            description = "Lista los planes preventivos con su frecuencia, modo (fixed calendario fijo, floating desde el último cierre), próxima fecha y responsable por defecto.",
            permission = MantenimientoModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "active" to McpSchema.boolean("true solo los activos, false solo los desactivados; sin enviar, todos."),
                    "property" to McpSchema.string("Propiedad, por nombre o id."),
                ),
            ),
        ) { call -> guarded { listPlans(call) } },
        McpTool(
            name = "get_plan",
            title = "Ver plan",
            description = "Devuelve un plan preventivo con su lista de comprobación y las últimas órdenes que generó (las que el usuario puede ver).",
            permission = MantenimientoModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del plan.")), required = listOf("id")),
        ) { call -> guarded { getPlan(call) } },
        McpTool(
            name = "list_assets",
            title = "Activos",
            description = "Lista los activos (equipos, instalaciones) con su propiedad, categoría y ubicación. Úsalo para elegir asset_id al reportar una avería.",
            permission = MantenimientoModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "property" to McpSchema.string("Solo activos de esta propiedad, por nombre o id."),
                    "q" to McpSchema.string("Texto a buscar en nombre, categoría o ubicación."),
                ),
            ),
        ) { call -> guarded { listAssets(call) } },
        McpTool(
            name = "list_people",
            title = "Personas asignables",
            description = "Lista los usuarios que pueden ser responsables de órdenes (nombre y, para quien gestiona, correo). Usa esos nombres o correos en assignee.",
            permission = MantenimientoModule.View,
            readOnly = true,
        ) { call -> guarded { listPeople(call) } },
        McpTool(
            name = "upcoming",
            title = "Vencidas y próximas",
            description = "Resume lo que el usuario debe atender: contadores, órdenes vencidas, órdenes con fecha límite en los próximos días y planes que generarán orden pronto.",
            permission = MantenimientoModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("days" to McpSchema.integer("Días hacia adelante; por defecto 30.", 1, 90))),
        ) { call -> guarded { upcoming(call) } },
        McpTool(
            name = "create_order",
            title = "Crear orden",
            description = "Reporta una avería (kind=corrective, por defecto) o solicita un proyecto (kind=project, queda Pendiente de aprobación). Cualquier usuario con acceso puede; " +
                "responsable, fecha límite y cotización solo quien gestiona. Con external_ref la operación es idempotente: si ya existe una orden creada por agentes con esa referencia no se duplica y se devuelve created=false. " +
                "Avisa a quienes gestionan (proyecto o avería sin responsable) o al responsable asignado.",
            permission = MantenimientoModule.View,
            inputSchema = McpSchema.obj(
                orderFields + managerFields + mapOf(
                    "kind" to McpSchema.string("corrective (avería, por defecto) o project.", creatableKinds),
                    "external_ref" to McpSchema.string("Referencia estable (hasta $MaxExternalRef caracteres) para no duplicar al repetir la carga."),
                ),
                required = listOf("title"),
            ),
        ) { call -> guarded { createOrder(call) } },
        McpTool(
            name = "update_order",
            title = "Editar orden",
            description = "Cambia solo lo que envías (due_date, assignee, asset_id o property vacíos lo quitan). Datos de la orden, responsable, fecha y cotización: solo quien gestiona. " +
                "cost_amount, cost_currency, resolution y status de trabajo (open, in_progress, on_hold, done): quien puede trabajar la orden (la asignada a él, sin responsable si ejecuta, o cualquiera si gestiona). " +
                "Aprobar o rechazar proyectos y cancelar: solo quien gestiona; rechazar exige reason. Las transiciones siguen el flujo de estados; una no permitida devuelve un error.",
            permission = MantenimientoModule.View,
            inputSchema = McpSchema.obj(
                idOrNumber + orderFields + managerFields + mapOf(
                    "status" to McpSchema.string("Nuevo estado.", statusCodes),
                    "reason" to McpSchema.string("Motivo (obligatorio al rechazar, hasta ${OrderTransitions.MaxReason} caracteres)."),
                    "cost_amount" to McpSchema.number("Costo real."),
                    "cost_currency" to McpSchema.string("Moneda del costo real, 3 letras mayúsculas; por defecto USD."),
                    "resolution" to McpSchema.string("Qué se hizo para resolverla (hasta ${OrderInput.MaxResolution} caracteres)."),
                ),
            ),
        ) { call -> guarded { updateOrder(call) } },
        McpTool(
            name = "close_order",
            title = "Completar orden",
            description = "Completa una orden que el usuario puede trabajar (open, in_progress u on_hold), guardando antes la resolución y el costo real si se envían. Avisa al solicitante.",
            permission = MantenimientoModule.View,
            inputSchema = McpSchema.obj(
                idOrNumber + mapOf(
                    "resolution" to McpSchema.string("Qué se hizo (hasta ${OrderInput.MaxResolution} caracteres)."),
                    "cost_amount" to McpSchema.number("Costo real en la moneda de la orden."),
                ),
            ),
        ) { call -> guarded { closeOrder(call) } },
        McpTool(
            name = "add_note",
            title = "Anotar en una orden",
            description = "Agrega una nota a una orden que el usuario puede trabajar o que él mismo reportó.",
            permission = MantenimientoModule.View,
            inputSchema = McpSchema.obj(
                idOrNumber + mapOf("body" to McpSchema.string("Texto de la nota (hasta ${MantenimientoRepository.MaxNoteLength} caracteres).")),
                required = listOf("body"),
            ),
        ) { call -> guarded { addNote(call) } },
    )

    // Un getter: las herramientas de las extensiones de importación se leen en cada petición (se enlazan después de cargar este objeto).
    val mcp: ModuleMcp get() = ModuleMcp(Instructions, tools + MantenimientoImportSources.all.flatMap { it.mcpTools })

    // ---- ayudas (públicas y sin base de datos para poder probarlas) ----

    /** Ejecuta un manejador convirtiendo los errores de reglas del módulo en errores de herramienta. */
    private fun guarded(block: () -> JsonElement): JsonElement = try {
        block()
    } catch (failure: MantenimientoValidationException) {
        throw McpToolException(failure.message.orEmpty())
    }

    /** Un usuario por id, correo o nombre (sin mayúsculas). Uno desconocido es un error que lista a las personas. */
    fun resolveUser(ref: String, users: List<MantenimientoUser>): UUID {
        val text = ref.trim()
        val user = users.firstOrNull { it.id.toString().equals(text, ignoreCase = true) }
            ?: users.firstOrNull { !it.email.isNullOrEmpty() && it.email.equals(text, ignoreCase = true) }
            ?: users.firstOrNull { it.label.equals(text, ignoreCase = true) }
        return user?.id ?: throw McpToolException("No puede ser responsable de mantenimiento: $text. Personas: ${users.joinToString { it.label }.ifEmpty { "ninguna" }}.")
    }

    /** Una propiedad activa por id o nombre (sin mayúsculas). */
    fun resolveProperty(ref: String, properties: List<Property>): UUID {
        val text = ref.trim()
        val property = properties.firstOrNull { !it.archived && it.id.toString().equals(text, ignoreCase = true) }
            ?: properties.firstOrNull { !it.archived && it.name.equals(text, ignoreCase = true) }
        return property?.id ?: throw McpToolException("La propiedad \"$text\" no existe. Propiedades: ${properties.filter { !it.archived }.joinToString { it.name }.ifEmpty { "ninguna" }}.")
    }

    fun statusOf(text: String): OrderStatus =
        OrderStatus.fromId(text) ?: throw McpToolException("'status' debe ser ${statusCodes.joinToString(", ")} (recibido '$text').")

    fun priorityOf(text: String): OrderPriority =
        OrderPriority.fromId(text) ?: throw McpToolException("'priority' debe ser ${priorityCodes.joinToString(", ")} (recibido '$text').")

    fun kindOf(text: String): OrderKind =
        OrderKind.fromId(text)?.takeIf { it != OrderKind.Preventive }
            ?: throw McpToolException("'kind' debe ser ${creatableKinds.joinToString(", ")} (las preventivas las genera un plan; recibido '$text').")

    /** La clave de importación que usan los agentes para no duplicar: "agent:<ref>". */
    fun importKeyOf(ref: String): String = "agent:" + ref.trim().take(MaxExternalRef)

    /** Argumentos que cambian los datos de la orden: exigen poder editarla (gestionar). */
    private val editArguments = listOf(
        "title", "description", "property", "asset_id", "priority", "department", "due_date", "assignee", "quote_amount", "quote_currency", "quoted_by",
    )

    /** Argumentos del trabajo: exigen poder trabajar la orden. */
    private val workArguments = listOf("cost_amount", "cost_currency", "resolution")

    fun changesData(call: McpToolCall): Boolean = editArguments.any { call.has(it) }

    fun changesWork(call: McpToolCall): Boolean = workArguments.any { call.has(it) }

    // ---- contexto del usuario ----

    private fun scopeOf(call: McpToolCall) = MantenimientoScope.from(call.permissions)

    private fun actorOf(call: McpToolCall, scope: MantenimientoScope) =
        MantenimientoActor(scope.userId, call.access.userIdentity(call.tenant, call.permissions)?.name ?: "Usuario")

    /** La orden por id o folio, solo si el usuario la ve (y no está archivada); si no, "no encontrada" sin distinguir. */
    private fun visibleOrder(call: McpToolCall, scope: MantenimientoScope): Order {
        val id = call.uuid("id")
        val folio = call.string("number", 20)
        if (id == null && folio.isEmpty()) throw McpToolException("Indica id o number.")
        val order = if (id != null) {
            MantenimientoRepository.getOrderVisible(call.tenant, scope, id)
        } else {
            val number = MantenimientoFolio.parse(folio) ?: throw McpToolException("'number' debe ser un folio como OT-0007 o 7.")
            MantenimientoRepository.getOrderVisibleByNumber(call.tenant, scope, number)
        }
        return order ?: throw McpToolException("Orden no encontrada.")
    }

    private fun propertyArgument(call: McpToolCall): UUID? =
        call.string("property", 200).takeIf { it.isNotEmpty() }?.let { resolveProperty(it, MantenimientoRepository.listProperties(call.tenant)) }

    // ---- JSON ----

    private fun names(users: List<MantenimientoUser>): Map<UUID, String> = users.associate { it.id to it.label }

    private fun person(id: UUID, names: Map<UUID, String>): JsonObject = buildJsonObject {
        put("id", id.toString())
        put("name", names[id] ?: "Usuario sin acceso a Mantenimiento")
    }

    /** La orden como la ve el agente: ids, folio, estado, fechas, propiedad, activo, responsable y cuenta de la lista. Nunca una referencia de archivo. */
    fun orderJson(order: Order, names: Map<UUID, String>, today: LocalDate = MantenimientoRepository.today()): JsonObject = buildJsonObject {
        put("id", order.id.toString())
        put("number", order.displayNumber)
        put("title", order.title)
        put("kind", order.kind.id)
        put("status", order.status.id)
        put("status_label", order.status.label)
        put("priority", order.priority.id)
        put("due_date", order.dueDate?.toString())
        put("overdue", order.isOverdue(today))
        put("property", order.propertyName)
        order.assetId?.let { put("asset_id", it.toString()) }
        put("asset", order.assetName)
        order.assigneeId?.let { put("assignee", person(it, names)) }
        put("requested_by_name", order.requestedByName)
        if (order.department.isNotEmpty()) put("department", order.department)
        order.planId?.let { put("plan_id", it.toString()) }
        put("checklist", "${order.checklistDone}/${order.checklistTotal}")
    }

    private fun orderList(orders: List<Order>, users: List<MantenimientoUser>): JsonArray {
        val labels = names(users)
        val today = MantenimientoRepository.today()
        return buildJsonArray { orders.forEach { add(orderJson(it, labels, today)) } }
    }

    private fun planJson(plan: Plan, names: Map<UUID, String>): JsonObject = buildJsonObject {
        put("id", plan.id.toString())
        put("title", plan.title)
        put("frequency", plan.frequency.id)
        plan.intervalDays?.let { put("interval_days", it) }
        put("schedule_mode", plan.scheduleMode.id)
        put("next_due", plan.nextDue?.toString())
        put("lead_days", plan.leadDays)
        put("priority", plan.priority.id)
        put("active", plan.active)
        put("property", plan.propertyName)
        plan.assetId?.let { put("asset_id", it.toString()) }
        put("asset", plan.assetName)
        plan.defaultAssignee?.let { put("default_assignee", person(it, names)) }
    }

    // ---- lecturas ----

    private fun listOrders(call: McpToolCall): JsonElement {
        val scope = scopeOf(call)
        val users = MantenimientoRepository.assignableUsers(call.tenant)
        val assignee = call.string("assignee", 200).takeIf { it.isNotEmpty() }?.let { text ->
            if (text.equals("me", ignoreCase = true)) scope.userId ?: throw McpToolException("Este usuario no puede tener órdenes asignadas.")
            else resolveUser(text, users)
        }
        val status = call.string("status", 20).takeIf { it.isNotEmpty() }?.let { statusOf(it) }
        val kind = call.string("kind", 20).takeIf { it.isNotEmpty() }?.let { OrderKind.fromId(it) ?: throw McpToolException("'kind' debe ser ${kindCodes.joinToString(", ")} (recibido '$it').") }
        val limit = call.int("limit", 50, 1..MaxListedByAgent)
        val filter = OrderFilter(
            status = status,
            // Con un estado explícito se respeta tal cual; sin él, solo lo abierto salvo que se pidan las cerradas.
            activeOnly = status == null && !call.boolean("include_closed", false),
            kind = kind,
            propertyId = propertyArgument(call),
            assetId = call.uuid("asset_id"),
            assigneeId = assignee,
            dueOnOrBefore = call.date("due_before"),
            query = call.string("q", 200).takeIf { it.isNotEmpty() },
            limit = limit,
        )
        val orders = MantenimientoRepository.listOrders(call.tenant, scope, filter)
        return buildJsonObject {
            put("count", orders.size)
            if (orders.size >= limit) put("note", "Puede haber más órdenes: afina los filtros o sube limit.")
            put("orders", orderList(orders, users))
        }
    }

    private fun getOrder(call: McpToolCall): JsonElement {
        val scope = scopeOf(call)
        val order = visibleOrder(call, scope)
        val labels = names(MantenimientoRepository.assignableUsers(call.tenant))
        val role = TransitionRole.of(scope, order)
        return buildJsonObject {
            put("order", orderJson(order, labels))
            put("description", order.description)
            put("created_at", order.createdAt.toString())
            order.completedAt?.let { put("completed_at", it.toString()) }
            order.approvedAt?.let { put("approved_at", it.toString()) }
            if (order.rejectedReason.isNotEmpty()) put("rejected_reason", order.rejectedReason)
            put("quote_amount", JsonPrimitive(order.quoteAmount))
            put("quote_currency", order.quoteCurrency)
            if (order.quotedBy.isNotEmpty()) put("quoted_by", order.quotedBy)
            put("cost_amount", JsonPrimitive(order.costAmount))
            put("cost_currency", order.costCurrency)
            put("resolution", order.resolution)
            if (order.purchaseFolio.isNotEmpty()) put("purchase_folio", order.purchaseFolio)
            put(
                "can",
                buildJsonObject {
                    put("edit", scope.canEdit(order))
                    put("work", MantenimientoActions.canWorkNow(scope, order))
                    putJsonArray("next_statuses") { OrderTransitions.targets(order.kind, order.status, role).forEach { add(JsonPrimitive(it.id)) } }
                },
            )
            putJsonArray("checklist_items") {
                MantenimientoRepository.listChecklist(call.tenant, scope, order.id).forEach { item ->
                    add(buildJsonObject { put("text", item.text); put("done", item.done) })
                }
            }
            putJsonArray("notes") {
                MantenimientoRepository.listNotes(call.tenant, scope, order.id, 20).forEach { note ->
                    add(buildJsonObject { put("kind", note.kind); put("author", note.authorName); put("body", note.body); put("at", note.createdAt.toString()) })
                }
            }
            // Solo nombre y tamaño: la ubicación guardada de un archivo nunca se muestra.
            putJsonArray("attachments") {
                MantenimientoRepository.listAttachments(call.tenant, scope, order.id).forEach { file ->
                    add(buildJsonObject { put("name", file.name); put("size", file.size); put("content_type", file.contentType) })
                }
            }
        }
    }

    private fun listPlans(call: McpToolCall): JsonElement {
        val labels = names(MantenimientoRepository.assignableUsers(call.tenant))
        val active = if (call.has("active")) call.boolean("active") else null
        val plans = MantenimientoRepository.listPlans(call.tenant, active = active, propertyId = propertyArgument(call), limit = 200)
        return buildJsonObject {
            put("count", plans.size)
            putJsonArray("plans") { plans.forEach { add(planJson(it, labels)) } }
        }
    }

    private fun getPlan(call: McpToolCall): JsonElement {
        val scope = scopeOf(call)
        val plan = MantenimientoRepository.getPlan(call.tenant, call.requiredUuid("id")) ?: throw McpToolException("Plan no encontrado.")
        val users = MantenimientoRepository.assignableUsers(call.tenant)
        return buildJsonObject {
            put("plan", planJson(plan, names(users)))
            put("description", plan.description)
            putJsonArray("checklist") { MantenimientoRepository.planChecklist(call.tenant, plan.id).forEach { add(JsonPrimitive(it.text)) } }
            // Las órdenes generadas pasan por el mismo filtro de visibilidad que cualquier lista.
            put("recent_orders", orderList(MantenimientoRepository.listOrders(call.tenant, scope, OrderFilter(planId = plan.id, sort = OrderSort.Newest, limit = 10)), users))
        }
    }

    private fun listAssets(call: McpToolCall): JsonElement {
        val assets = MantenimientoRepository.listAssets(call.tenant, propertyId = propertyArgument(call), query = call.string("q", 200).takeIf { it.isNotEmpty() }, limit = 200)
        return buildJsonObject {
            put("count", assets.size)
            putJsonArray("assets") {
                assets.forEach { asset ->
                    add(
                        buildJsonObject {
                            put("id", asset.id.toString())
                            put("name", asset.name)
                            put("property", asset.propertyName)
                            put("category", asset.category)
                            put("location", asset.location)
                        },
                    )
                }
            }
        }
    }

    private fun listPeople(call: McpToolCall): JsonElement {
        val users = MantenimientoRepository.assignableUsers(call.tenant)
        // Correos solo para quien gestiona (asigna por correo); el resto ve nombres.
        val withEmail = scopeOf(call).canManage
        return buildJsonObject {
            putJsonArray("people") {
                users.forEach { user ->
                    add(buildJsonObject { put("id", user.id.toString()); put("name", user.label); if (withEmail) put("email", user.email) })
                }
            }
        }
    }

    private fun upcoming(call: McpToolCall): JsonElement {
        val scope = scopeOf(call)
        val days = call.int("days", 30, 1..90)
        val today = MantenimientoRepository.today()
        val horizon = today.plusDays(days.toLong())
        val users = MantenimientoRepository.assignableUsers(call.tenant)
        val metrics = MantenimientoRepository.orderMetrics(call.tenant, scope, today)
        // Una sola consulta filtrada en SQL por visibilidad: vencidas y con fecha dentro del horizonte.
        val due = MantenimientoRepository.listOrders(call.tenant, scope, OrderFilter(activeOnly = true, dueOnOrBefore = horizon, limit = MaxListedByAgent))
        val (overdue, soon) = due.partition { it.isOverdue(today) }
        val plans = MantenimientoRepository.listPlans(call.tenant, active = true, nextDueOnOrBefore = horizon, limit = 50)
        return buildJsonObject {
            put(
                "metrics",
                buildJsonObject {
                    put("open", metrics.open)
                    put("overdue", metrics.overdue)
                    put("pending_approval", metrics.pendingApproval)
                    put("completed_this_month", metrics.completedThisMonth)
                },
            )
            put("today", today.toString())
            put("horizon", horizon.toString())
            put("overdue", orderList(overdue, users))
            put("upcoming", orderList(soon, users))
            put("plans_due_soon", buildJsonArray { plans.forEach { add(planJson(it, names(users))) } })
            if (due.size >= MaxListedByAgent) put("note", "Hay más órdenes con fecha: usa list_orders con due_before para verlas.")
        }
    }

    // ---- escrituras ----

    private fun moneyOrNull(call: McpToolCall, key: String) = if (call.has(key)) call.decimal(key) else null

    private fun createOrder(call: McpToolCall): JsonElement {
        val scope = scopeOf(call)
        val manage = scope.canManage
        // Responsable, fecha y cotización son de quien gestiona: un error claro en vez de ignorarlos en silencio.
        if (!manage) {
            listOf("due_date", "assignee", "quote_amount", "quote_currency", "quoted_by").firstOrNull { call.has(it) }?.let {
                throw McpToolException("'$it' solo lo puede indicar quien gestiona mantenimiento.")
            }
        }
        val kind = call.string("kind", 20).takeIf { it.isNotEmpty() }?.let { kindOf(it) } ?: OrderKind.Corrective
        val users = MantenimientoRepository.assignableUsers(call.tenant)
        val input = OrderInput(
            kind = kind,
            title = call.requiredString("title", OrderInput.MaxTitle + 1),
            description = call.string("description", OrderInput.MaxDescription + 1),
            propertyId = propertyArgument(call),
            assetId = call.uuid("asset_id"),
            priority = call.string("priority", 20).takeIf { it.isNotEmpty() }?.let { priorityOf(it) } ?: OrderPriority.Medium,
            dueDate = call.date("due_date"),
            assigneeId = call.string("assignee", 200).takeIf { it.isNotEmpty() }?.let { resolveUser(it, users) },
            department = call.string("department", OrderInput.MaxDepartment + 1),
            quoteAmount = moneyOrNull(call, "quote_amount"),
            quoteCurrency = call.string("quote_currency", 3).ifEmpty { "USD" },
            quotedBy = call.string("quoted_by", OrderInput.MaxPerson + 1),
        )
        val ref = call.string("external_ref", MaxExternalRef + 1).takeIf { it.isNotEmpty() }
        if (ref != null && ref.length > MaxExternalRef) throw McpToolException("'external_ref' admite hasta $MaxExternalRef caracteres.")
        val actor = actorOf(call, scope)
        val created = MantenimientoRepository.createOrder(call.tenant, input, actor, ref?.let { importKeyOf(it) })
        // Con una referencia repetida puede devolverse una orden que el usuario no ve: no se revela.
        val order = MantenimientoRepository.getOrderVisible(call.tenant, scope, created.id)
            ?: throw McpToolException("Ya existe una orden con esa external_ref.")
        if (created.created) MantenimientoNotify.created(call.tenant, call.notifier, order, scope.userId)
        return buildJsonObject {
            put("created", created.created)
            if (!created.created) put("note", "Ya existía una orden con esa external_ref: no se creó otra ni se cambió.")
            put("order", orderJson(order, names(users)))
        }
    }

    private fun updateOrder(call: McpToolCall): JsonElement {
        val scope = scopeOf(call)
        val before = visibleOrder(call, scope)
        val data = changesData(call)
        val work = changesWork(call)
        val status = if (call.has("status")) statusOf(call.requiredString("status", 20)) else null
        if (!data && !work && status == null) {
            throw McpToolException("Indica qué cambiar: status, ${editArguments.joinToString(", ")}, ${workArguments.joinToString(", ")}.")
        }
        if (data && !scope.canEdit(before)) throw McpToolException("El usuario no puede editar los datos de esta orden; solo su estado, costo real y resolución si la trabaja.")
        if (work && !MantenimientoActions.canWorkNow(scope, before)) throw McpToolException("El usuario no puede trabajar esta orden.")
        // Se valida el cambio de estado antes de escribir nada.
        val reason = call.string("reason", OrderTransitions.MaxReason + 1)
        if (status != null) {
            if (!OrderTransitions.allowed(before.kind, before.status, status, TransitionRole.of(scope, before))) {
                throw McpToolException("No puedes pasar la orden de ${before.status.label} a ${status.label}.")
            }
            if (OrderTransitions.requiresReason(status) && reason.isEmpty()) throw McpToolException("Indica el motivo del rechazo en 'reason'.")
        }
        val users = MantenimientoRepository.assignableUsers(call.tenant)
        val actor = actorOf(call, scope)
        val updated = MantenimientoRepository.transaction(call.tenant) {
            var current = before
            if (data) {
                val input = OrderInput(
                    kind = before.kind,
                    title = if (call.has("title")) call.requiredString("title", OrderInput.MaxTitle + 1) else before.title,
                    description = if (call.has("description")) call.string("description", OrderInput.MaxDescription + 1) else before.description,
                    propertyId = if (call.has("property")) propertyArgument(call) else before.propertyId,
                    assetId = if (call.has("asset_id")) call.uuid("asset_id") else before.assetId,
                    priority = if (call.has("priority")) priorityOf(call.requiredString("priority", 20)) else before.priority,
                    dueDate = if (call.has("due_date")) call.date("due_date") else before.dueDate,
                    assigneeId = if (call.has("assignee")) call.string("assignee", 200).takeIf { it.isNotEmpty() }?.let { resolveUser(it, users) } else before.assigneeId,
                    department = if (call.has("department")) call.string("department", OrderInput.MaxDepartment + 1) else before.department,
                    quoteAmount = if (call.has("quote_amount")) call.decimal("quote_amount") else before.quoteAmount,
                    quoteCurrency = if (call.has("quote_currency")) call.string("quote_currency", 3) else before.quoteCurrency,
                    quotedBy = if (call.has("quoted_by")) call.string("quoted_by", OrderInput.MaxPerson + 1) else before.quotedBy,
                    costAmount = if (call.has("cost_amount")) call.decimal("cost_amount") else before.costAmount,
                    costCurrency = if (call.has("cost_currency")) call.string("cost_currency", 3) else before.costCurrency,
                    resolution = if (call.has("resolution")) call.string("resolution", OrderInput.MaxResolution + 1) else before.resolution,
                )
                current = MantenimientoRepository.updateOrder(call.tenant, before.id, input, actor) ?: throw McpToolException("Orden no encontrada.")
            } else if (work) {
                MantenimientoRepository.updateWork(
                    call.tenant,
                    before.id,
                    if (call.has("cost_amount")) call.decimal("cost_amount") else before.costAmount,
                    if (call.has("cost_currency")) call.string("cost_currency", 3) else before.costCurrency,
                    if (call.has("resolution")) call.string("resolution", OrderInput.MaxResolution + 1) else before.resolution,
                )
            }
            if (status != null) {
                MantenimientoRepository.changeStatus(call.tenant, before.id, status, actor, scope, reason)
            } else {
                MantenimientoRepository.getOrder(call.tenant, before.id) ?: current
            }
        }
        // Avisos tras confirmar: nunca rompen el cambio.
        if (updated.assigneeId != before.assigneeId) MantenimientoNotify.assigned(call.tenant, call.notifier, updated, scope.userId)
        MantenimientoNotify.statusChanged(call.tenant, call.notifier, before, updated, scope.userId)
        return buildJsonObject { put("order", orderJson(updated, names(users))) }
    }

    private fun closeOrder(call: McpToolCall): JsonElement {
        val scope = scopeOf(call)
        val before = visibleOrder(call, scope)
        if (!MantenimientoActions.canWorkNow(scope, before)) throw McpToolException("El usuario no puede trabajar esta orden.")
        val resolution = if (call.has("resolution")) call.string("resolution", OrderInput.MaxResolution + 1) else null
        val cost = if (call.has("cost_amount")) call.decimal("cost_amount") else null
        val updated = MantenimientoRepository.closeOrder(call.tenant, before.id, resolution, cost, actorOf(call, scope), scope)
        MantenimientoNotify.statusChanged(call.tenant, call.notifier, before, updated, scope.userId)
        return buildJsonObject { put("order", orderJson(updated, names(MantenimientoRepository.assignableUsers(call.tenant)))) }
    }

    private fun addNote(call: McpToolCall): JsonElement {
        val scope = scopeOf(call)
        val order = visibleOrder(call, scope)
        // Anota quien trabaja la orden o quien la reportó.
        if (!MantenimientoActions.canNote(scope, order)) throw McpToolException("El usuario no puede anotar en esta orden.")
        val noteId = MantenimientoRepository.addNote(call.tenant, order.id, call.requiredString("body", MantenimientoRepository.MaxNoteLength + 1), actorOf(call, scope))
        return buildJsonObject {
            put("order_id", order.id.toString())
            put("note_id", noteId.toString())
            put("result", "Nota agregada.")
        }
    }
}
