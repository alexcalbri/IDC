package com.ideasdeveloper.idc.modules.propietarios

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigDecimal
import java.util.UUID

/**
 * Herramientas para agentes de Propietarios. Cada escritura pasa por [PropietariosService] (las mismas reglas que las pantallas) y cada
 * lectura por [PropietariosRepository], de modo que un agente ve y cambia solo lo que su usuario puede. Los manejadores corren en el
 * hilo de herramientas del puente, que es donde van las llamadas JDBC. Ninguna herramienta recibe ni devuelve credenciales.
 */
object PropietariosMcpTools {
    const val Instructions =
        "Propietarios es el registro de desarrollos (urbanizaciones o proyectos), sus lotes y quienes son sus propietarios. Cada lote tiene un " +
            "número de lote (único dentro de su desarrollo), un número de registro, un área en m², un tipo de propiedad y un estado fijo (códigos): " +
            "disponible, reservado, vendido, en_construccion, construido. Los propietarios son clientes de Clientes: aquí solo se guarda quién es " +
            "titular de qué lote, con un porcentaje opcional y las fechas de inicio y fin; los datos de contacto viven en Clientes. " +
            "Un titular está vigente mientras su titularidad no tenga fecha de fin (o esta sea futura). " +
            "Para registrar una venta o traspaso: 1) search_lots o get_lot para ubicar el lote y ver sus titulares actuales; 2) obtén el customer_id de cada " +
            "nuevo titular con las herramientas de Clientes (el cliente debe existir ya; no se crean aquí); 3) register_ownership_change con la fecha del " +
            "cambio y la lista completa de nuevos titulares: cierra las titularidades vigentes en esa fecha, abre las nuevas, pasa el lote a vendido si " +
            "estaba disponible o reservado y deja el historial. Con una lista vacía solo da de baja a los titulares actuales. Muestra al usuario el " +
            "resumen del cambio y espera su confirmación antes de registrarlo. Los porcentajes son opcionales y entre todos los titulares no pueden pasar de 100. " +
            "add_lot crea un lote en un desarrollo existente (usa list_developments para elegirlo). Solo ves y cambias lo que los permisos de tu usuario permiten. " +
            "Los archivos adjuntos (escrituras, planos) se gestionan solo desde la app."

    private val statusCodes = LotStatus.entries.map { it.code }

    private fun ownersSchema(): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", "Nuevos titulares del lote. Lista vacía = solo dar de baja a los titulares vigentes.")
        put(
            "items",
            McpSchema.obj(
                mapOf(
                    "customer_id" to McpSchema.uuid("Id del cliente (de Clientes) que pasa a ser titular."),
                    "share_percent" to McpSchema.number("Porcentaje de propiedad, mayor que 0 y hasta 100 (opcional)."),
                ),
                required = listOf("customer_id"),
            ),
        )
    }

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "list_developments",
            title = "Desarrollos",
            description = "Lista los desarrollos con su ubicación, cuántos lotes tienen, su área total en m² y cuántos titulares vigentes. Úsalo para obtener el development_id.",
            permission = PropietariosModule.View,
            readOnly = true,
        ) { call -> guarded { listDevelopments(call) } },
        McpTool(
            name = "search_lots",
            title = "Buscar lotes",
            description = "Busca lotes por número de lote, número de registro o nombre de la propiedad (texto parcial, sin distinguir mayúsculas), con filtros opcionales por desarrollo y estado. " +
                "Los que coinciden exactamente con el número van primero.",
            permission = PropietariosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "q" to McpSchema.string("Texto a buscar en el número de lote, el número de registro y el nombre de la propiedad."),
                    "development_id" to McpSchema.uuid("Solo lotes de este desarrollo."),
                    "status" to McpSchema.string("Solo lotes en este estado.", statusCodes),
                    "limit" to McpSchema.integer("Cantidad máxima de lotes; por defecto 20.", 1, 50),
                ),
            ),
        ) { call -> guarded { searchLots(call) } },
        McpTool(
            name = "get_lot",
            title = "Ver lote",
            description = "Devuelve un lote con sus datos, sus titulares vigentes (con porcentaje y fechas), los anteriores, el historial reciente de cambios y los nombres de sus adjuntos.",
            permission = PropietariosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("lot_id" to McpSchema.uuid("Id del lote.")), required = listOf("lot_id")),
        ) { call -> guarded { getLot(call) } },
        McpTool(
            name = "search_owners",
            title = "Buscar propietarios",
            description = "Busca propietarios (clientes con lotes vigentes) por nombre o correo y devuelve cuántos lotes tienen y su área total en m².",
            permission = PropietariosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("q" to McpSchema.string("Texto a buscar en el nombre o el correo (al menos 2 caracteres).")), required = listOf("q")),
        ) { call -> guarded { searchOwners(call) } },
        McpTool(
            name = "get_owner",
            title = "Ver propietario",
            description = "Devuelve un propietario con sus lotes vigentes y los que tuvo antes (desarrollo, número de lote, registro, área, porcentaje y fechas).",
            permission = PropietariosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("customer_id" to McpSchema.uuid("Id del cliente propietario.")), required = listOf("customer_id")),
        ) { call -> guarded { getOwner(call) } },
        McpTool(
            name = "register_ownership_change",
            title = "Registrar cambio de titularidad",
            description = "Registra un cambio de titularidad de un lote en la fecha dada: cierra las titularidades vigentes, abre las de los nuevos titulares, pasa el lote a vendido si estaba " +
                "disponible o reservado y deja el historial (y el del cliente). Los clientes deben existir y ser visibles; sin repetidos; los porcentajes suman 100 como máximo. " +
                "Con owners vacío solo da de baja a los titulares vigentes. Confirma con el usuario antes de usarla.",
            permission = PropietariosModule.Manage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "lot_id" to McpSchema.uuid("Id del lote."),
                    "date" to McpSchema.date("Fecha del cambio (fin de los anteriores e inicio de los nuevos)."),
                    "owners" to ownersSchema(),
                    "notes" to McpSchema.string("Notas del cambio (por ejemplo el número de escritura)."),
                ),
                required = listOf("lot_id", "date", "owners"),
            ),
        ) { call -> guarded { registerOwnershipChange(call) } },
        McpTool(
            name = "add_lot",
            title = "Crear lote",
            description = "Crea un lote en un desarrollo existente. El número de lote no puede repetirse dentro del desarrollo. No asigna titulares: usa register_ownership_change para eso.",
            permission = PropietariosModule.Manage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "development_id" to McpSchema.uuid("Id del desarrollo (ver list_developments)."),
                    "lot_number" to McpSchema.string("Número de lote (hasta ${PropietariosRules.MaxLotNumber} caracteres)."),
                    "register_number" to McpSchema.string("Número de registro (hasta ${PropietariosRules.MaxRegister} caracteres)."),
                    "property_name" to McpSchema.string("Nombre de la propiedad."),
                    "property_type" to McpSchema.string("Tipo de propiedad."),
                    "status" to McpSchema.string("Estado; por defecto disponible.", statusCodes),
                    "area_m2" to McpSchema.number("Área en metros cuadrados."),
                    "latitude" to McpSchema.number("Latitud (junto con longitude)."),
                    "longitude" to McpSchema.number("Longitud (junto con latitude)."),
                    "map_url" to McpSchema.string("Enlace al mapa; debe empezar por https://."),
                    "notes" to McpSchema.string("Notas del lote."),
                ),
                required = listOf("development_id", "lot_number"),
            ),
        ) { call -> guarded { addLot(call) } },
    )

    // Un getter: las herramientas de las extensiones de importacion se leen en cada peticion del puente (las extensiones se enlazan despues de cargar este objeto).
    val mcp: ModuleMcp get() = ModuleMcp(Instructions, tools + PropietariosImportSources.all.flatMap { it.mcpTools })

    // ---- ayudas ----

    /** Corre un manejador convirtiendo los errores de regla del modulo en errores de herramienta para el agente. */
    private fun guarded(block: () -> JsonElement): JsonElement = try {
        block()
    } catch (failure: PropietariosValidationException) {
        throw McpToolException(failure.message.orEmpty())
    }

    /** Si el usuario puede ver clientes (clientes.view; los duenos siempre). Se lee en vivo en cada llamada. */
    private fun canSeeCustomers(call: McpToolCall): Boolean {
        val permissions = call.permissions
        if (permissions.serverOwner || permissions.businessOwner) return true
        val userId = permissions.userId ?: return false
        return call.access.userAccess(call.tenant, permissions.companyCode, userId, serverOwner = false)?.permissions("clientes")?.has("clientes.view") == true
    }

    private fun author(call: McpToolCall): String {
        val name = call.access.userIdentity(call.tenant, call.permissions)?.name ?: "Usuario"
        return PropietariosRules.clip("$name (agente ${call.agentName})", 160)
    }

    private fun statusOf(text: String): LotStatus =
        LotStatus.fromLabel(text) ?: throw McpToolException("'status' debe ser ${statusCodes.joinToString(", ")} (recibido '$text').")

    // ---- JSON ----

    /** El lote como lo ve el agente: ids, numeros, estado, area y el enlace al mapa. */
    fun lotJson(lot: Lot): JsonObject = buildJsonObject {
        put("id", lot.id.toString())
        put("development_id", lot.developmentId.toString())
        put("development", lot.developmentName)
        put("lot_number", lot.lotNumber)
        put("register_number", lot.registerNumber)
        put("property_name", lot.propertyName)
        put("property_type", lot.propertyType)
        put("status", lot.status.code)
        put("status_label", lot.status.label)
        put("area_m2", lot.areaM2)
        put("latitude", lot.latitude)
        put("longitude", lot.longitude)
        put("map_link", PropietariosRules.mapLink(lot.mapUrl, lot.latitude, lot.longitude))
        put("notes", lot.notes)
        put("archived", lot.archived)
    }

    private fun ownershipJson(ownership: Ownership, withEmail: Boolean): JsonObject = buildJsonObject {
        put("ownership_id", ownership.id.toString())
        put("customer_id", ownership.customerId.toString())
        put("name", ownership.customerName)
        if (withEmail) put("email", ownership.customerEmail)
        put("share_percent", ownership.sharePercent)
        put("starts_on", ownership.startsOn?.toString())
        put("ends_on", ownership.endsOn?.toString())
        put("current", ownership.current)
    }

    // ---- lecturas ----

    private fun listDevelopments(call: McpToolCall): JsonElement {
        val developments = PropietariosRepository.listDevelopments(call.tenant, PropietariosRepository.today())
        return buildJsonObject {
            put("count", developments.size)
            putJsonArray("developments") {
                developments.forEach { item ->
                    add(
                        buildJsonObject {
                            put("id", item.development.id.toString())
                            put("name", item.development.name)
                            put("location", item.development.location)
                            put("lots", item.lots)
                            put("total_area_m2", item.totalAreaM2)
                            put("current_owners", item.owners)
                        },
                    )
                }
            }
        }
    }

    private fun searchLots(call: McpToolCall): JsonElement {
        val limit = call.int("limit", 20, 1..50)
        val filter = LotFilter(
            developmentId = call.uuid("development_id"),
            status = call.string("status", 30).takeIf { it.isNotEmpty() }?.let { statusOf(it) },
            query = call.string("q", 200).trim().takeIf { it.isNotEmpty() },
            limit = limit,
        )
        val lots = PropietariosRepository.listLots(call.tenant, filter)
        return buildJsonObject {
            put("count", lots.size)
            if (lots.size >= limit) put("note", "Puede haber más lotes: afina los filtros o sube limit.")
            put("lots", buildJsonArray { lots.forEach { add(lotJson(it)) } })
        }
    }

    private fun getLot(call: McpToolCall): JsonElement {
        val lot = PropietariosRepository.getLot(call.tenant, call.requiredUuid("lot_id")) ?: throw McpToolException("Lote no encontrado.")
        val withEmail = canSeeCustomers(call)
        val ownerships = PropietariosRepository.listOwnerships(call.tenant, lot.id, PropietariosRepository.today())
        return buildJsonObject {
            put("lot", lotJson(lot))
            put("current_owners", buildJsonArray { ownerships.filter { it.current }.forEach { add(ownershipJson(it, withEmail)) } })
            put("past_owners", buildJsonArray { ownerships.filter { !it.current }.take(20).forEach { add(ownershipJson(it, withEmail)) } })
            putJsonArray("history") {
                PropietariosRepository.listHistory(call.tenant, lot.id, 10).forEach { entry ->
                    add(
                        buildJsonObject {
                            put("kind", entry.kind)
                            put("date", entry.happenedOn.toString())
                            put("detail", entry.detail)
                            put("author", entry.author)
                        },
                    )
                }
            }
            // Solo nombres y tamanos: la ubicacion almacenada de un archivo nunca se muestra.
            putJsonArray("attachments") {
                PropietariosRepository.listAttachments(call.tenant, lot.id).forEach { file ->
                    add(buildJsonObject { put("name", file.name); put("size", file.size); put("content_type", file.contentType) })
                }
            }
        }
    }

    private fun searchOwners(call: McpToolCall): JsonElement {
        val text = call.requiredString("q", 200).trim()
        if (text.length < 2) throw McpToolException("Escribe al menos 2 caracteres para buscar.")
        val withEmail = canSeeCustomers(call)
        val owners = PropietariosRepository.listOwners(call.tenant, text, PropietariosRepository.today(), 50)
        return buildJsonObject {
            put("count", owners.size)
            putJsonArray("owners") {
                owners.forEach { owner ->
                    add(
                        buildJsonObject {
                            put("customer_id", owner.customerId.toString())
                            put("name", owner.name)
                            if (withEmail) put("email", owner.email)
                            put("lots", owner.lots)
                            put("total_area_m2", owner.totalAreaM2)
                        },
                    )
                }
            }
        }
    }

    private fun getOwner(call: McpToolCall): JsonElement {
        val customer = PropietariosRepository.visibleCustomer(call.tenant, call.requiredUuid("customer_id")) ?: throw McpToolException("Propietario no encontrado.")
        val withEmail = canSeeCustomers(call)
        val owned = PropietariosRepository.ownedLots(call.tenant, customer.id, PropietariosRepository.today())
        fun entry(item: OwnedLot) = buildJsonObject {
            put("lot", lotJson(item.lot))
            put("share_percent", item.ownership.sharePercent)
            put("starts_on", item.ownership.startsOn?.toString())
            put("ends_on", item.ownership.endsOn?.toString())
        }
        return buildJsonObject {
            put("customer_id", customer.id.toString())
            put("name", customer.name)
            if (withEmail) put("email", customer.email)
            put("current_lots", buildJsonArray { owned.filter { it.ownership.current }.forEach { add(entry(it)) } })
            put("past_lots", buildJsonArray { owned.filter { !it.ownership.current }.take(50).forEach { add(entry(it)) } })
        }
    }

    // ---- escrituras ----

    /** Los titulares nuevos del argumento owners: una lista de objetos con customer_id y share_percent opcional. */
    fun parseOwners(arguments: JsonObject): List<NewOwner> {
        val sent = arguments["owners"] ?: throw McpToolException("El argumento 'owners' es obligatorio (puede ser una lista vacía).")
        val array = sent as? JsonArray ?: throw McpToolException("'owners' debe ser una lista de objetos con customer_id y share_percent.")
        if (array.size > PropietariosRules.MaxNewOwners) throw McpToolException("Un cambio admite hasta ${PropietariosRules.MaxNewOwners} titulares nuevos.")
        return array.map { element ->
            val item = element as? JsonObject ?: throw McpToolException("Cada titular debe ser un objeto con customer_id y share_percent.")
            val id = (item["customer_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw McpToolException("Cada titular necesita un customer_id válido.")
            val share = (item["share_percent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toBigDecimalOrNull() ?: throw McpToolException("share_percent debe ser un número.")
            }
            NewOwner(id, share)
        }
    }

    private fun registerOwnershipChange(call: McpToolCall): JsonElement {
        PropietariosService.require(call.permissions, PropietariosModule.Manage)
        val lotId = call.requiredUuid("lot_id")
        val date = call.date("date") ?: throw McpToolException("El argumento 'date' es obligatorio.")
        val owners = parseOwners(call.arguments)
        // Los titulares son clientes: hace falta poder ver Clientes, igual que en la pantalla.
        if (owners.isNotEmpty() && !canSeeCustomers(call)) throw McpToolException("El usuario no puede ver Clientes, así que no puede elegir titulares.")
        val who = author(call)
        val result = PropietariosService.transfer(call.tenant, lotId, date, owners, call.string("notes", PropietariosRules.MaxNotes + 1), who, call.permissions.userId)
        val lot = PropietariosRepository.getLot(call.tenant, lotId) ?: throw McpToolException("Lote no encontrado.")
        return buildJsonObject {
            put("kind", result.kind)
            put("ended_ownerships", result.ended.size)
            put("started_ownerships", result.started.size)
            put("lot", lotJson(lot))
        }
    }

    private fun decimalOf(call: McpToolCall, key: String): BigDecimal? = call.decimal(key)

    private fun addLot(call: McpToolCall): JsonElement {
        PropietariosService.require(call.permissions, PropietariosModule.Manage)
        val input = LotInput(
            developmentId = call.requiredUuid("development_id"),
            lotNumber = call.requiredString("lot_number", PropietariosRules.MaxLotNumber + 1).trim(),
            registerNumber = call.string("register_number", PropietariosRules.MaxRegister + 1).trim(),
            propertyName = call.string("property_name", PropietariosRules.MaxName + 1).trim(),
            propertyType = call.string("property_type", PropietariosRules.MaxPropertyType + 1).trim(),
            status = call.string("status", 30).takeIf { it.isNotEmpty() }?.let { statusOf(it) } ?: LotStatus.Disponible,
            areaM2 = decimalOf(call, "area_m2"),
            latitude = decimalOf(call, "latitude"),
            longitude = decimalOf(call, "longitude"),
            mapUrl = call.string("map_url", PropietariosRules.MaxMapUrl + 1).trim(),
            notes = call.string("notes", PropietariosRules.MaxNotes + 1).trim(),
        )
        val problems = input.validate()
        if (problems.isNotEmpty()) throw McpToolException(problems.joinToString(" "))
        val id = PropietariosService.createLot(call.tenant, input, author(call), call.permissions.userId)
        val lot = PropietariosRepository.getLot(call.tenant, id) ?: throw McpToolException("Lote no encontrado.")
        return buildJsonObject {
            put("created", true)
            put("lot", lotJson(lot))
        }
    }
}
