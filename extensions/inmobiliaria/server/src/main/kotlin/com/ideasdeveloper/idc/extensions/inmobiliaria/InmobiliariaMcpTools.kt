package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmRepository
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.modules.crm.CrmValidationException
import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * Herramientas MCP de la extension (los agentes las ven como crm_inmobiliaria_*). Cada una comprueba primero que la
 * extension este activa en la empresa y despues aplica los permisos en vivo del usuario con [InmobiliariaService]
 * (la misma logica que las pantallas). Nunca reciben ni devuelven credenciales.
 */
object InmobiliariaMcpTools {
    private const val Confirm = " Confirma con el usuario antes."

    private val kindIds = PropertyKind.entries.map { it.id }
    private val statusIds = PropertyStatus.entries.map { it.id }
    private val roleIds = PartyRole.entries.map { it.id }

    val tools = listOf(
        McpTool(
            name = "inmobiliaria_search_properties",
            title = "Buscar propiedades del inventario",
            description = "Busca en el inventario de propiedades de la inmobiliaria (todos los usuarios con acceso al CRM ven el inventario completo). Filtra por texto " +
                "(codigo, titulo, zona o desarrollo), zona, tipo, estado, agente del listado, rango de precio o solo los listados propios. Devuelve hasta 50 por pagina, " +
                "las mas recientes primero, con precio, precio por m2, si esta rebajada (precio actual menor que el anterior) y su agente.",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "query" to McpSchema.string("Texto a buscar en codigo, titulo, zona o desarrollo."),
                    "zone" to McpSchema.string("Zona o barrio (coincidencia parcial)."),
                    "kind" to McpSchema.string("Tipo de propiedad.", kindIds),
                    "status" to McpSchema.string("Estado: intake (captacion), for_sale (en venta), negotiation, sold, withdrawn.", statusIds),
                    "agent" to McpSchema.string("Agente del listado: nombre o id del usuario."),
                    "minPrice" to McpSchema.number("Precio minimo."),
                    "maxPrice" to McpSchema.number("Precio maximo."),
                    "mine" to McpSchema.boolean("Solo mis listados (de los que soy agente o que capte)."),
                    "page" to McpSchema.integer("Pagina (50 por pagina).", 1, 1000),
                ),
            ),
        ) { call -> gated(call) { searchProperties(call) } },
        McpTool(
            name = "inmobiliaria_get_property",
            title = "Ficha de una propiedad",
            description = "Devuelve la ficha completa de una propiedad (por id o por codigo, por ejemplo ERE-0123): datos, enlaces por portal, historial de precios y " +
                "las transacciones de la propiedad que el usuario puede ver.",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("property" to McpSchema.string("Id o codigo de la propiedad.")), required = listOf("property")),
        ) { call -> gated(call) { getProperty(call) } },
        McpTool(
            name = "inmobiliaria_update_property",
            title = "Cambiar precio o estado de una propiedad",
            description = "Cambia el precio y/o el estado de una propiedad. Solo pueden hacerlo el agente del listado, quien la capto y los usuarios que ven todos los " +
                "leads. Un precio nuevo deja el anterior en el historial (y la propiedad se ve rebajada si baja). Estados: intake, for_sale, negotiation, sold " +
                "(con fecha de venta opcional), withdrawn.$Confirm",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "property" to McpSchema.string("Id o codigo de la propiedad."),
                    "price" to McpSchema.number("Precio nuevo (hasta 2 decimales)."),
                    "priceNote" to McpSchema.string("Motivo del cambio de precio (opcional, hasta 500 caracteres)."),
                    "status" to McpSchema.string("Estado nuevo.", statusIds),
                    "soldOn" to McpSchema.date("Fecha de venta, solo con estado sold; por defecto hoy."),
                ),
                required = listOf("property"),
            ),
        ) { call -> gated(call) { updateProperty(call) } },
        McpTool(
            name = "inmobiliaria_list_deals",
            title = "Listar transacciones",
            description = "Lista las transacciones (cierres) que el usuario puede ver: las que creo, en las que es parte o de propiedades que lista; con todos los " +
                "leads a la vista, todas. Filtra por etapa, agente, propiedad, lead comprador o solo abiertas. Incluye comision total, partes con su porcentaje y monto, " +
                "y un aviso si los porcentajes no suman 100.",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "stage" to McpSchema.string("Nombre o id de la etapa."),
                    "agent" to McpSchema.string("Agente: nombre o id del usuario (es parte o lista la propiedad)."),
                    "mine" to McpSchema.boolean("Solo mis transacciones."),
                    "property" to McpSchema.string("Id o codigo de la propiedad."),
                    "lead" to McpSchema.uuid("Id del lead comprador."),
                    "openOnly" to McpSchema.boolean("Solo transacciones en una etapa abierta."),
                    "page" to McpSchema.integer("Pagina (50 por pagina).", 1, 1000),
                ),
            ),
        ) { call -> gated(call) { listDeals(call) } },
        McpTool(
            name = "inmobiliaria_create_deal",
            title = "Abrir una transaccion",
            description = "Abre una transaccion de una propiedad (en la primera etapa abierta si no se indica etapa). Sin precio acordado usa el de la propiedad y sin " +
                "porcentaje la comision por defecto de la empresa. Las partes (agentes, abogados, referidos...) llevan cada una su porcentaje SOBRE LA COMISION TOTAL; " +
                "deberian sumar 100. Si hay lead comprador, su item pasa a Elegida y la propiedad En venta pasa a En negociacion.$Confirm",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "property" to McpSchema.string("Id o codigo de la propiedad."),
                    "lead" to McpSchema.uuid("Id del lead comprador (opcional)."),
                    "customer" to McpSchema.uuid("Id del cliente comprador (opcional; por defecto el del lead)."),
                    "stage" to McpSchema.string("Nombre o id de la etapa (opcional)."),
                    "agreedPrice" to McpSchema.number("Precio acordado."),
                    "commissionPct" to McpSchema.number("Porcentaje de comision sobre el precio (0 a 100)."),
                    "commissionAmount" to McpSchema.number("Monto fijo de comision; reemplaza el calculo con el porcentaje."),
                    "estimatedCloseOn" to McpSchema.date("Fecha estimada de cierre."),
                    "comments" to McpSchema.string("Comentarios (hasta 5000 caracteres)."),
                    "parties" to partiesSchema(),
                ),
                required = listOf("property"),
            ),
        ) { call -> gated(call) { createDeal(call) } },
        McpTool(
            name = "inmobiliaria_advance_deal",
            title = "Mover o fechar una transaccion",
            description = "Mueve una transaccion a otra etapa y/o fija sus fechas. Al pasar a una etapa ganada la propiedad queda vendida (fecha de cierre real), el item del " +
                "lead pasa a vendido y, si el sitio WordPress esta activo y la propiedad esta enlazada, se marca vendida alli. Al pasar a una perdida, la propiedad en " +
                "negociacion vuelve a en venta.$Confirm",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "deal" to McpSchema.uuid("Id de la transaccion (inmobiliaria_list_deals)."),
                    "stage" to McpSchema.string("Nombre o id de la etapa nueva."),
                    "estimatedCloseOn" to McpSchema.date("Fecha estimada de cierre."),
                    "closedOn" to McpSchema.date("Fecha real de cierre (con una etapa ganada; por defecto hoy)."),
                ),
                required = listOf("deal"),
            ),
        ) { call -> gated(call) { advanceDeal(call) } },
        McpTool(
            name = "inmobiliaria_closings_by_month",
            title = "Cierres estimados por mes",
            description = "Resume las transacciones abiertas con fecha de cierre estimada, por mes: cantidad, suma de precios acordados y suma de comisiones. Solo cuenta " +
                "las transacciones que el usuario puede ver.",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "agent" to McpSchema.string("Solo las de este agente: nombre o id del usuario."),
                    "mine" to McpSchema.boolean("Solo mis transacciones."),
                ),
            ),
        ) { call -> gated(call) { closingsByMonth(call) } },
    )

    private fun partiesSchema() = kotlinx.serialization.json.buildJsonObject {
        put("type", "array")
        put("description", "Partes de la transaccion: cada una con rol, quien es (agent, customer o name) y su porcentaje sobre la comision total.")
        put(
            "items",
            McpSchema.obj(
                mapOf(
                    "role" to McpSchema.string("Rol.", roleIds),
                    "agent" to McpSchema.string("Agente interno: nombre o id del usuario."),
                    "customer" to McpSchema.uuid("Id de un cliente."),
                    "name" to McpSchema.string("Nombre de una parte externa (abogado, referido...)."),
                    "sharePct" to McpSchema.number("Porcentaje de la comision total (0 a 100)."),
                    "notes" to McpSchema.string("Notas (hasta 500 caracteres)."),
                ),
                required = listOf("role", "sharePct"),
            ),
        )
    }

    // ---- Comunes ----

    // La extension debe estar activa en la empresa; los errores de negocio llegan al agente como mensajes que puede corregir.
    private inline fun gated(call: McpToolCall, block: () -> JsonElement): JsonElement {
        CrmHost.extensionInactiveReason(call.tenant, InmobiliariaServerExtension.ExtensionId)?.let { throw McpToolException(it) }
        return try {
            block()
        } catch (failure: InmobiliariaException) {
            throw McpToolException(failure.message.orEmpty())
        } catch (failure: CrmValidationException) {
            throw McpToolException(failure.message.orEmpty())
        }
    }

    private fun session(call: McpToolCall): InmobiliariaSession {
        val scope = CrmRepository.scope(call.tenant, call.permissions)
        val actor = CrmRepository.actor(call.tenant, call.permissions, call.access.userIdentity(call.tenant, call.permissions))
        return InmobiliariaSession.of(call.tenant, call.permissions, scope, CrmActor(actor.userId, "${actor.name} (agente ${call.agentName})"))
    }

    private fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

    private fun agentId(call: McpToolCall, s: InmobiliariaSession, key: String): UUID? =
        call.string(key, 200).takeIf { it.isNotEmpty() }?.let { InmobiliariaService.findAgent(s, it).id }

    // "mine" y "agent" a la vez: gana el agente dicho.
    private fun agentOrMine(call: McpToolCall, s: InmobiliariaSession): UUID? =
        agentId(call, s, "agent") ?: (if (call.boolean("mine")) ownUserId(s) else null)

    private fun money(call: McpToolCall, key: String) = call.decimal(key)?.let { InmobiliariaInput.requireMoney(it, "El argumento '$key'") }

    private fun ownUserId(s: InmobiliariaSession): UUID = s.access.userId ?: throw McpToolException("El dueno del servidor no tiene listados propios.")

    private fun propertyJson(property: Property, agents: Map<UUID, String>): JsonObject = buildJsonObject {
        put("id", property.id.toString())
        put("code", property.code)
        put("title", property.title)
        put("kind", property.kind.id)
        put("kindLabel", property.kind.label)
        put("zone", property.zone)
        if (property.development.isNotEmpty()) put("development", property.development)
        put("status", property.status.id)
        put("statusLabel", property.status.label)
        put("price", text(property.price?.toPlainString()))
        put("currency", property.currency)
        put("reduced", property.reduced)
        if (property.reduced) put("previousPrice", text(property.previousPrice?.toPlainString()))
        put("pricePerM2Lot", text(property.pricePerLotM2?.toPlainString()))
        put("pricePerM2Built", text(property.pricePerBuiltM2?.toPlainString()))
        put("agent", text(property.listingAgentId?.let { agents[it] }))
    }

    private fun agentNames(s: InmobiliariaSession): Map<UUID, String> = InmobiliariaService.agents(s).associate { it.id to it.name }

    /**
     * Lo que el usuario puede ver de lo que cuelga de una transaccion (lead y clientes). Cada id se consulta una sola vez por respuesta,
     * asi una pagina de transacciones con el mismo cliente no repite la consulta.
     */
    private class DealVisibility(private val s: InmobiliariaSession) {
        private val leads = mutableMapOf<UUID, Boolean>()
        private val customers = mutableMapOf<UUID, Boolean>()

        fun lead(id: UUID): Boolean = leads.getOrPut(id) { CrmHost.lead(s.tenant, id)?.let { s.scope.canSee(it.interestId, it.ownerUserIds) } == true }

        fun customer(id: UUID): Boolean = customers.getOrPut(id) { CrmRepository.customerInScope(s.tenant, s.scope, id) }
    }

    // El lead y los clientes salen solo si el usuario los ve; los comentarios de la transaccion los ve quien ve la transaccion.
    private fun dealJson(deal: Deal, agents: Map<UUID, String>, visible: DealVisibility): JsonObject = buildJsonObject {
        put("id", deal.id.toString())
        put("property", deal.propertyId.toString())
        put("propertyCode", deal.propertyCode)
        put("propertyTitle", deal.propertyTitle)
        put("stage", deal.stageName)
        put("stageResult", deal.stageOutcome.id)
        put("agreedPrice", text(deal.agreedPrice?.toPlainString()))
        put("currency", deal.currency)
        put("commissionPct", InmobiliariaMath.plain(deal.commissionPct))
        put("commissionTotal", text(deal.commissionTotal?.toPlainString()))
        put("commissionIsManual", deal.commissionAmount != null)
        put("estimatedCloseOn", text(deal.estimatedCloseOn?.toString()))
        put("closedOn", text(deal.closedOn?.toString()))
        deal.buyerLeadId?.takeIf { visible.lead(it) }?.let { put("buyerLead", it.toString()) }
        deal.buyerCustomerId?.takeIf { visible.customer(it) }?.let { put("buyerCustomer", it.toString()) }
        if (deal.comments.isNotEmpty()) put("comments", deal.comments)
        putJsonArray("parties") {
            deal.parties.forEach { party ->
                add(
                    buildJsonObject {
                        put("role", party.role.id)
                        put("roleLabel", party.role.label)
                        val customer = party.customerId
                        val customerSeen = customer != null && visible.customer(customer)
                        put("who", party.userId?.let { agents[it] } ?: party.name.ifEmpty { if (customer == null) "" else if (customerSeen) customer.toString() else "Cliente" })
                        if (customerSeen) put("customer", customer.toString())
                        put("sharePct", InmobiliariaMath.plain(party.sharePct))
                        put("amount", text(deal.partyAmount(party)?.toPlainString()))
                    }
                )
            }
        }
        deal.shareWarning?.let { put("warning", it) }
    }

    // ---- Propiedades ----

    private fun searchProperties(call: McpToolCall): JsonElement {
        val s = session(call)
        val filter = PropertyFilter(
            text = call.string("query", 100),
            zone = call.string("zone", 100),
            kind = call.string("kind", 30).takeIf { it.isNotEmpty() }?.let { PropertyKind.parse(it) ?: throw McpToolException("Tipo desconocido: $it.") },
            status = call.string("status", 30).takeIf { it.isNotEmpty() }?.let { PropertyStatus.parse(it) ?: throw McpToolException("Estado desconocido: $it.") },
            agentId = agentId(call, s, "agent"),
            minPrice = money(call, "minPrice"),
            maxPrice = money(call, "maxPrice"),
            mineOf = if (call.boolean("mine")) ownUserId(s) else null,
        )
        val page = call.int("page", 1, 1..1000)
        val found = InmobiliariaService.searchProperties(s, filter, 50, (page - 1) * 50)
        val agents = agentNames(s)
        return buildJsonObject {
            put("total", found.total)
            put("page", page)
            putJsonArray("properties") { found.items.forEach { add(propertyJson(it, agents)) } }
        }
    }

    private fun getProperty(call: McpToolCall): JsonElement {
        val s = session(call)
        val property = InmobiliariaService.findPropertyByRef(s, call.requiredString("property", 100))
        val detail = InmobiliariaService.getProperty(s, property.id)
        val agents = agentNames(s)
        return buildJsonObject {
            put("property", buildJsonObject {
                propertyJson(detail.property, agents).forEach { (key, value) -> put(key, value) }
                put("lotAreaM2", text(property.lotAreaM2?.toPlainString()))
                put("builtAreaM2", text(property.builtAreaM2?.toPlainString()))
                put("bedrooms", property.bedrooms?.let { JsonPrimitive(it) } ?: JsonNull)
                put("bathrooms", text(property.bathrooms?.toPlainString()))
                put("description", property.description)
                put("notes", property.notes)
                put("publishedOn", text(property.publishedOn?.toString()))
                put("soldOn", text(property.soldOn?.toString()))
                put("wordpressListingId", property.wordpressListingId?.let { JsonPrimitive(it) } ?: JsonNull)
            })
            putJsonArray("links") {
                detail.links.forEach { add(buildJsonObject { put("label", it.label); put("url", it.url) }) }
            }
            putJsonArray("priceHistory") {
                detail.priceChanges.take(20).forEach { change ->
                    add(
                        buildJsonObject {
                            put("at", change.changedAt.toString())
                            put("oldPrice", text(change.oldPrice?.toPlainString()))
                            put("newPrice", text(change.newPrice?.toPlainString()))
                            if (change.note.isNotEmpty()) put("note", change.note)
                        }
                    )
                }
            }
            putJsonArray("deals") { val visible = DealVisibility(s)
                detail.deals.forEach { add(dealJson(it, agents, visible)) } }
        }
    }

    private fun updateProperty(call: McpToolCall): JsonElement {
        if (!call.has("price") && !call.has("status")) throw McpToolException("Envia al menos un campo a cambiar: price o status.")
        val s = session(call)
        val current = InmobiliariaService.findPropertyByRef(s, call.requiredString("property", 100))
        var property = current
        call.decimal("price")?.let { property = InmobiliariaService.changePrice(s, current.id, it, call.string("priceNote", 500)) }
        if (call.has("status")) {
            val status = PropertyStatus.parse(call.requiredString("status", 30)) ?: throw McpToolException("Estado desconocido: ${call.string("status", 30)}.")
            property = InmobiliariaService.changeStatus(s, current.id, status, call.date("soldOn"))
        }
        return propertyJson(property, agentNames(s))
    }

    // ---- Transacciones ----

    private fun listDeals(call: McpToolCall): JsonElement {
        val s = session(call)
        val agent = agentOrMine(call, s)
        val filter = DealFilter(
            stageId = call.string("stage", 100).takeIf { it.isNotEmpty() }?.let { InmobiliariaService.findStage(s, it).id },
            agentId = agent,
            propertyId = call.string("property", 100).takeIf { it.isNotEmpty() }?.let { InmobiliariaService.findPropertyByRef(s, it).id },
            buyerLeadId = call.uuid("lead"),
            openOnly = call.boolean("openOnly"),
        )
        val page = call.int("page", 1, 1..1000)
        val found = InmobiliariaService.listDeals(s, filter, 50, (page - 1) * 50)
        val agents = agentNames(s)
        return buildJsonObject {
            put("total", found.total)
            put("page", page)
            putJsonArray("deals") { val visible = DealVisibility(s)
                found.items.forEach { add(dealJson(it, agents, visible)) } }
        }
    }

    private fun parties(call: McpToolCall, s: InmobiliariaSession): List<PartyInput> {
        val raw = call.arguments["parties"] ?: return emptyList()
        val list = raw as? JsonArray ?: throw McpToolException("'parties' debe ser una lista.")
        return list.map { element ->
            val item = element as? JsonObject ?: throw McpToolException("Cada parte debe ser un objeto.")
            fun field(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val role = PartyRole.parse(field("role")) ?: throw McpToolException("Cada parte necesita un rol valido: ${roleIds.joinToString()}.")
            PartyInput(
                role = role,
                userId = field("agent").takeIf { it.isNotEmpty() }?.let { InmobiliariaService.findAgent(s, it).id },
                customerId = InmobiliariaInput.parseUuid(field("customer"), "El cliente de una parte"),
                name = field("name"),
                sharePct = InmobiliariaInput.parsePercent(field("sharePct"), "El porcentaje de una parte") ?: throw McpToolException("Cada parte necesita su 'sharePct'."),
                notes = field("notes"),
            )
        }
    }

    private fun createDeal(call: McpToolCall): JsonElement {
        val s = session(call)
        val property = InmobiliariaService.findPropertyByRef(s, call.requiredString("property", 100))
        val draft = DealDraft(
            propertyId = property.id,
            buyerLeadId = call.uuid("lead"),
            buyerCustomerId = call.uuid("customer"),
            stageId = call.string("stage", 100).takeIf { it.isNotEmpty() }?.let { InmobiliariaService.findStage(s, it).id },
            agreedPrice = money(call, "agreedPrice"),
            commissionPct = call.decimal("commissionPct")?.let { InmobiliariaInput.requirePercent(it, "El porcentaje de comision") },
            commissionAmount = money(call, "commissionAmount"),
            estimatedCloseOn = call.date("estimatedCloseOn"),
            comments = call.string("comments", 5000),
            parties = parties(call, s),
        )
        val result = InmobiliariaService.createDeal(s, draft)
        return resultJson(s, result, agentNames(s))
    }

    private fun advanceDeal(call: McpToolCall): JsonElement {
        if (!call.has("stage") && !call.has("estimatedCloseOn") && !call.has("closedOn")) throw McpToolException("Envia al menos un campo a cambiar: stage, estimatedCloseOn o closedOn.")
        val s = session(call)
        val id = call.requiredUuid("deal")
        // Se leen todos los argumentos antes de escribir: un argumento mal formado no deja la transaccion a medias.
        val estimatedCloseOn = if (call.has("estimatedCloseOn")) call.date("estimatedCloseOn") else null
        val closedOn = call.date("closedOn")
        val stage = if (call.has("stage")) InmobiliariaService.findStage(s, call.requiredString("stage", 100)) else null
        val result = InmobiliariaStore.inTransaction(s.tenant) {
            var current = DealResult(InmobiliariaService.getDeal(s, id))
            if (stage == null && closedOn != null && current.deal.stageOutcome != DealOutcome.Won) {
                // Solo la fecha real de cierre: tiene sentido en una transaccion que ya esta en una etapa ganada.
                throw McpToolException("La fecha real de cierre solo se fija en una transaccion de una etapa ganada; indica tambien 'stage'.")
            }
            if (call.has("estimatedCloseOn")) {
                val edit = DealEdit.of(current.deal).copy(estimatedCloseOn = estimatedCloseOn)
                current = DealResult(InmobiliariaService.updateDeal(s, id, edit))
            }
            if (stage != null) current = InmobiliariaService.moveDeal(s, id, stage.id, closedOn)
            else if (closedOn != null) current = InmobiliariaService.moveDeal(s, id, current.deal.stageId, closedOn)
            current
        }
        return resultJson(s, result, agentNames(s))
    }

    private fun resultJson(s: InmobiliariaSession, result: DealResult, agents: Map<UUID, String>): JsonObject = buildJsonObject {
        put("deal", dealJson(result.deal, agents, DealVisibility(s)))
        if (result.warnings.isNotEmpty()) putJsonArray("warnings") { result.warnings.forEach { add(JsonPrimitive(it)) } }
    }

    private fun closingsByMonth(call: McpToolCall): JsonElement {
        val s = session(call)
        val months = InmobiliariaService.closingsByMonth(s, agentOrMine(call, s))
        return buildJsonObject {
            putJsonArray("months") {
                months.forEach { month ->
                    add(
                        buildJsonObject {
                            put("month", month.month.toString())
                            put("currency", month.currency)
                            put("deals", month.count)
                            put("totalPrice", month.totalPrice.toPlainString())
                            put("totalCommission", month.totalCommission.toPlainString())
                        }
                    )
                }
            }
        }
    }
}
