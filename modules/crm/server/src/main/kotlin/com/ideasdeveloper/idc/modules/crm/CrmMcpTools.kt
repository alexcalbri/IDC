package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Agent tools of the CRM. They load the same [CrmScreens.Context] as the screens, so an agent sees
 * only the leads of its user's scope (interests, ownership, reception, "see all") and follows the
 * same rules to create, edit, move and assign leads.
 */
object CrmMcpTools {
    private val leadArguments = mapOf(
        "name" to McpSchema.string("Nombre de la persona."),
        "email" to McpSchema.string("Correo electronico.", format = "email"),
        "phone" to McpSchema.string("Telefono con codigo de pais."),
        "stage" to McpSchema.string("Nombre de la etapa del embudo (ver list_pipeline)."),
        "interest" to McpSchema.string("Nombre del interes, cuando la empresa usa intereses (ver list_pipeline)."),
        "owner" to McpSchema.string("Nombre del usuario responsable; solo si el usuario puede asignar leads."),
        "source" to McpSchema.string("Origen del lead, por ejemplo Web, WhatsApp o Referido."),
        "language" to McpSchema.string("Idioma de la persona."),
        "message" to McpSchema.string("Mensaje o consulta original."),
        "details" to McpSchema.string("Notas fijas o detalles del lead."),
        "targetStart" to McpSchema.date("Fecha de inicio de lo que busca (por ejemplo llegada)."),
        "targetEnd" to McpSchema.date("Fecha de fin de lo que busca."),
        "estimatedValue" to McpSchema.number("Valor estimado del negocio."),
        "followUpOn" to McpSchema.date("Proximo seguimiento."),
    )

    // What this module tells agents about itself (see ModuleMcp): leads, settings and integrations.
    val mcp: ModuleMcp
        get() = ModuleMcp(Instructions, tools + CrmSettingsMcpTools.tools + CrmIntegrationMcpTools.tools + CrmImportMcpTools.tools)

    private const val Instructions =
        "El CRM sigue a los interesados (leads) por un embudo de etapas; cerrar en una etapa ganada o perdida termina el seguimiento. " +
            "Cada lead es un cliente de Clientes. Con intereses activados, cada usuario ve solo los leads de sus intereses o los que tiene " +
            "asignados: veras exactamente lo que ve tu usuario. Las etapas, intereses y usuarios se nombran como los devuelve list_pipeline. " +
            "Los nuevos leads pueden traer un responsable sugerido que se aprueba o descarta. Antes de enviar un mensaje a un lead, crear " +
            "una reserva o cambiar configuracion o integraciones, muestra lo que vas a hacer y espera la confirmacion del usuario. " +
            "Las credenciales de las integraciones y del correo (API keys, tokens, secretos, contrasenas) se configuran solo en la app; " +
            "nunca las pidas ni las recibas."

    val tools = listOf(
        McpTool(
            name = "list_pipeline",
            title = "Etapas, intereses y responsables",
            description = "Devuelve las etapas del embudo (en orden), los intereses que el usuario puede usar y los posibles responsables. Usalo antes de crear o mover leads.",
            permission = CrmScreens.View,
            readOnly = true,
        ) { call -> pipeline(call) },
        McpTool(
            name = "search_leads",
            title = "Buscar leads",
            description = "Busca leads visibles para el usuario por nombre, correo, telefono, origen o mensaje, con filtros por etapa y responsable. Los mas recientes primero.",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "query" to McpSchema.string("Texto a buscar (opcional)."),
                    "stage" to McpSchema.string("Solo leads de esta etapa."),
                    "mine" to McpSchema.boolean("Solo leads cuyo responsable es el usuario."),
                    "openOnly" to McpSchema.boolean("Solo leads abiertos (no ganados ni perdidos)."),
                    "limit" to McpSchema.integer("Cantidad maxima de resultados.", 1, 100),
                    "offset" to McpSchema.integer("Resultados a saltar, para paginar.", 0),
                ),
            ),
        ) { call -> search(call) },
        McpTool(
            name = "get_lead",
            title = "Ver lead",
            description = "Devuelve un lead con sus datos y su actividad reciente (notas, cambios de etapa, mensajes).",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del lead.")), required = listOf("id")),
        ) { call -> lead(call) },
        McpTool(
            name = "list_follow_ups",
            title = "Seguimientos pendientes",
            description = "Leads abiertos con seguimiento hasta una fecha (incluye los vencidos), el mas proximo primero.",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "until" to McpSchema.date("Fecha limite; por defecto dentro de 7 dias."),
                    "mine" to McpSchema.boolean("Solo leads cuyo responsable es el usuario."),
                ),
            ),
        ) { call -> followUps(call) },
        McpTool(
            name = "pipeline_summary",
            title = "Resumen del embudo",
            description = "Totales de leads visibles: abiertos, creados y ganados en el periodo, seguimientos vencidos y cantidad por etapa.",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("days" to McpSchema.integer("Dias hacia atras del periodo; por defecto 30.", 1, 365))),
        ) { call -> summary(call) },
        McpTool(
            name = "create_lead",
            title = "Crear lead",
            description = "Crea un lead. Si ya existe un cliente con el mismo correo (o telefono) se usa ese cliente; si no, se crea un prospecto en Clientes. Busca antes para no duplicar.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(leadArguments, required = listOf("name")),
        ) { call -> create(call) },
        McpTool(
            name = "update_lead",
            title = "Editar lead",
            description = "Modifica un lead visible. Solo cambia los datos enviados; enviar un texto vacio borra ese dato. Los cambios de contacto se guardan en el cliente.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(leadArguments + ("id" to McpSchema.uuid("Id del lead.")), required = listOf("id")),
        ) { call -> update(call) },
        McpTool(
            name = "move_lead",
            title = "Mover lead de etapa",
            description = "Pasa un lead visible a otra etapa del embudo. Pasarlo a una etapa ganada o perdida lo cierra.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf("id" to McpSchema.uuid("Id del lead."), "stage" to McpSchema.string("Nombre de la etapa destino.")),
                required = listOf("id", "stage"),
            ),
        ) { call -> move(call) },
        McpTool(
            name = "add_lead_note",
            title = "Agregar nota a un lead",
            description = "Agrega una nota a la actividad de un lead visible.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf("id" to McpSchema.uuid("Id del lead."), "note" to McpSchema.string("Texto de la nota.")),
                required = listOf("id", "note"),
            ),
        ) { call -> note(call) },
        McpTool(
            name = "get_lead_messages",
            title = "Mensajes de un lead",
            description = "Devuelve los mensajes enviados y recibidos con un lead visible (correo, WhatsApp, Messenger, Instagram), del mas reciente al mas antiguo.",
            permission = CrmScreens.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf("id" to McpSchema.uuid("Id del lead."), "limit" to McpSchema.integer("Cantidad maxima de mensajes.", 1, 100)),
                required = listOf("id"),
            ),
        ) { call -> messages(call) },
        McpTool(
            name = "list_message_templates",
            title = "Plantillas y canales de mensajes",
            description = "Lista las plantillas de mensajes de la empresa y los canales por los que se puede escribir a los leads.",
            permission = CrmScreens.View,
            readOnly = true,
        ) { call -> templates(call) },
        McpTool(
            name = "send_message",
            title = "Enviar mensaje a un lead",
            description = "Envia un mensaje a un lead visible por un canal (correo, WhatsApp...), con texto propio o una plantilla; los marcadores de la plantilla " +
                "se llenan con los datos del lead. Le llega de inmediato a la persona: muestra el mensaje al usuario y espera su confirmacion antes de enviarlo.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.uuid("Id del lead."),
                    "channel" to McpSchema.string("Canal: id o nombre de list_message_templates, por ejemplo email o WhatsApp."),
                    "text" to McpSchema.string("Texto del mensaje; si se omite se usa la plantilla."),
                    "subject" to McpSchema.string("Asunto (correo); si se omite se usa el de la plantilla."),
                    "template" to McpSchema.string("Nombre de la plantilla (list_message_templates)."),
                ),
                required = listOf("id", "channel"),
            ),
        ) { call -> send(call) },
        McpTool(
            name = "mark_lead_reviewed",
            title = "Marcar lead como revisado",
            description = "Quita el aviso de revision de un lead visible (por ejemplo un lead que volvio a escribir o un posible duplicado).",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del lead.")), required = listOf("id")),
        ) { call -> reviewed(call) },
        McpTool(
            name = "list_pending_assignments",
            title = "Asignaciones pendientes",
            description = "Lista los leads con un responsable sugerido que falta aprobar o descartar.",
            permission = CrmScreens.Assign,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("limit" to McpSchema.integer("Cantidad maxima de leads.", 1, 100))),
        ) { call -> pendingAssignments(call) },
        McpTool(
            name = "resolve_assignment",
            title = "Aprobar o descartar asignacion",
            description = "Aprueba (el sugerido pasa a ser el responsable) o descarta la sugerencia de responsable de un lead visible.",
            permission = CrmScreens.Assign,
            inputSchema = McpSchema.obj(
                mapOf("id" to McpSchema.uuid("Id del lead."), "approve" to McpSchema.boolean("true aprueba, false descarta.")),
                required = listOf("id", "approve"),
            ),
        ) { call -> resolveAssignment(call) },
        McpTool(
            name = "delete_lead",
            title = "Eliminar lead",
            description = "Elimina un lead visible con su actividad; el cliente queda en Clientes. No se puede deshacer: confirma con el usuario antes.",
            permission = CrmScreens.Delete,
            destructive = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del lead.")), required = listOf("id")),
        ) { call -> delete(call) },
    )

    private fun context(call: McpToolCall) = CrmScreens.Context.load(call.tenant, call.permissions)

    // The user, marked as acting through an agent in the lead's activity.
    private fun actor(call: McpToolCall): CrmActor {
        val actor = CrmRepository.actor(call.tenant, call.permissions, call.access.userIdentity(call.tenant, call.permissions))
        return actor.copy(name = "${actor.name} (agente ${call.agentName})")
    }

    private fun visibleLead(call: McpToolCall, context: CrmScreens.Context): CrmLead =
        CrmRepository.lead(call.tenant, call.requiredUuid("id"))?.takeIf { context.scope.canSee(it.interestId, it.ownerUserId) }
            ?: throw McpToolException("Lead no encontrado o no visible para el usuario.")

    private fun stage(context: CrmScreens.Context, name: String): CrmStage =
        context.stages.firstOrNull { it.name.equals(name, ignoreCase = true) || it.id.toString() == name }
            ?: throw McpToolException("No existe la etapa '$name'. Etapas: ${context.stages.joinToString { it.name }}.")

    private fun pipeline(call: McpToolCall): JsonElement {
        val context = context(call)
        return buildJsonObject {
            putJsonArray("stages") {
                context.stages.forEach { stage ->
                    add(buildJsonObject {
                        put("id", stage.id.toString())
                        put("name", stage.name)
                        put("kind", stage.kind.id)
                    })
                }
            }
            put("interestsEnabled", context.interestsEnabled)
            if (context.interestsEnabled) {
                putJsonArray("interests") { context.usableInterests.forEach { add(JsonPrimitive(it.name)) } }
                put("canUseNoInterest", context.scope.seesAll || context.scope.reception)
            }
            put("canAssign", context.canAssign)
            putJsonArray("owners") { context.users.forEach { add(JsonPrimitive(it.name)) } }
        }
    }

    private fun filter(call: McpToolCall, context: CrmScreens.Context) = CrmLeadFilter(
        ownerUserId = if (call.boolean("mine")) context.scope.userId ?: throw McpToolException("El usuario no puede tener leads propios.") else null,
        stageId = call.string("stage", 60).takeIf { it.isNotEmpty() }?.let { stage(context, it).id },
        search = call.string("query", 100),
        openOnly = call.boolean("openOnly"),
    )

    private fun search(call: McpToolCall): JsonElement {
        val context = context(call)
        val filter = filter(call, context)
        val limit = call.int("limit", 20, 1..100)
        val offset = call.int("offset", 0, 0..100_000)
        val leads = CrmRepository.leads(call.tenant, context.scope, filter, limit, offset)
        return buildJsonObject {
            put("total", CrmRepository.count(call.tenant, context.scope, filter))
            putJsonArray("leads") { leads.forEach { add(json(it, context, brief = true)) } }
        }
    }

    private fun lead(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        return JsonObject(json(lead, context, brief = false) + buildJsonObject {
            putJsonArray("activity") {
                CrmRepository.activity(call.tenant, lead.id, 30).forEach { entry ->
                    add(buildJsonObject {
                        put("kind", entry.kind)
                        put("body", entry.body.take(2_000))
                        entry.fromStage?.let { put("fromStage", it) }
                        entry.toStage?.let { put("toStage", it) }
                        put("author", entry.author)
                        put("at", entry.createdAt.toString())
                    })
                }
            }
        })
    }

    private fun followUps(call: McpToolCall): JsonElement {
        val context = context(call)
        val until = call.date("until") ?: context.today.plusDays(7)
        val filter = CrmLeadFilter(ownerUserId = if (call.boolean("mine")) context.scope.userId else null)
        val leads = CrmRepository.followUps(call.tenant, context.scope, filter, until, 100)
        return buildJsonObject { putJsonArray("leads") { leads.forEach { add(json(it, context, brief = true)) } } }
    }

    private fun summary(call: McpToolCall): JsonElement {
        val context = context(call)
        val days = call.int("days", 30, 1..365)
        val totals = CrmRepository.summary(call.tenant, context.scope, context.today.minusDays(days.toLong()), context.today)
        val perStage = CrmRepository.board(call.tenant, context.scope, CrmLeadFilter(), 0).second
        return buildJsonObject {
            put("days", days)
            put("open", totals.open)
            put("createdInPeriod", totals.createdSince)
            put("wonInPeriod", totals.wonSince)
            put("overdueFollowUps", totals.overdue)
            putJsonArray("stages") {
                context.stages.forEach { stage ->
                    add(buildJsonObject {
                        put("name", stage.name)
                        put("kind", stage.kind.id)
                        put("leads", perStage[stage.id] ?: 0)
                    })
                }
            }
        }
    }

    private fun create(call: McpToolCall): JsonElement {
        val context = context(call)
        val values = leadValues(call, emptyMap()).toMutableMap()
        values["stage"] = values["stage"]?.takeIf { it.isNotEmpty() }?.let { stage(context, it).name }
            ?: context.stages.firstOrNull { it.kind == StageKind.Open }?.name.orEmpty()
        // Like the form: users limited to their interests start with the first one.
        if (values["interest"].isNullOrEmpty() && context.interestsEnabled && !context.scope.seesAll && !context.scope.reception) {
            values["interest"] = context.usableInterests.firstOrNull()?.name.orEmpty()
        }
        if (!context.canAssign && !values["owner"].isNullOrEmpty()) throw McpToolException("El usuario no puede elegir el responsable del lead.")
        val input = parse(values, context)
        if (!context.scope.canUseInterest(input.interestId)) throw McpToolException("El usuario no puede crear leads de ese interes.")
        val owned = if (context.canAssign && call.has("owner")) input else input.copy(ownerUserId = context.scope.userId?.takeIf { id -> context.users.any { it.id == id } })
        val stage = context.stage(input.stageId)!!
        val id = CrmRepository.createLead(call.tenant, owned, stage, actor(call))
        CrmNotify.newLead(call.tenant, id)
        owned.ownerUserId?.takeIf { it != context.scope.userId }?.let { CrmNotify.assigned(call.tenant, id, it) }
        return json(CrmRepository.lead(call.tenant, id)!!, context, brief = false)
    }

    private fun update(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val current = mapOf(
            "name" to lead.name,
            "email" to lead.email,
            "phone" to lead.phone,
            "stage" to (context.stage(lead.stageId)?.name.orEmpty()),
            "interest" to (lead.interestId?.let { context.names.interest(it) }.orEmpty()),
            // The responsible user only changes when the agent sends one (see below).
            "owner" to "",
            "source" to lead.source,
            "language" to lead.language,
            "message" to lead.message,
            "details" to lead.details,
            "targetStart" to lead.targetStart?.toString().orEmpty(),
            "targetEnd" to lead.targetEnd?.toString().orEmpty(),
            "estimatedValue" to lead.estimatedValue?.toPlainString().orEmpty(),
            "followUpOn" to lead.followUpOn?.toString().orEmpty(),
        )
        if (call.has("owner") && !context.canAssign) throw McpToolException("El usuario no puede cambiar el responsable del lead.")
        val values = leadValues(call, current).toMutableMap()
        if (call.has("stage")) values["stage"] = stage(context, call.string("stage", 60)).name
        val parsed = parse(values, context)
        // A new stage is applied afterwards like move_lead, which records the stage history.
        val input = parsed.copy(stageId = lead.stageId, ownerUserId = if (call.has("owner")) parsed.ownerUserId else lead.ownerUserId)
        if (input.interestId != lead.interestId && !context.scope.canUseInterest(input.interestId)) throw McpToolException("El usuario no puede asignar ese interes.")
        CrmRepository.updateLead(call.tenant, lead, input, context.stages, context.names, actor(call))
        input.ownerUserId?.takeIf { it != lead.ownerUserId && it != context.scope.userId }?.let { CrmNotify.assigned(call.tenant, lead.id, it) }
        if (call.has("stage") && parsed.stageId != lead.stageId) {
            CrmRepository.lead(call.tenant, lead.id)?.let { moveChecked(call, context, it, context.stage(parsed.stageId)!!) }
        }
        val updated = CrmRepository.lead(call.tenant, lead.id)!!
        return if (context.scope.canSee(updated.interestId, updated.ownerUserId)) {
            json(updated, context, brief = false)
        } else {
            buildJsonObject {
                put("id", lead.id.toString())
                put("result", "Cambios guardados. El lead paso a un interes que el usuario no ve.")
            }
        }
    }

    private fun move(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val target = stage(context, call.requiredString("stage", 60))
        moveChecked(call, context, lead, target)
        return json(CrmRepository.lead(call.tenant, lead.id)!!, context, brief = true)
    }

    // Same rule as dragging on the board: with rooms chosen in Cloudbeds the lead is won by confirming the reservation.
    private fun moveChecked(call: McpToolCall, context: CrmScreens.Context, lead: CrmLead, stage: CrmStage) {
        val pendingRooms = CrmItems.list(call.tenant, lead.id, CrmCloudbeds.Source).any { it.status == "chosen" }
        val reserved = CrmCloudbeds.forLead(call.tenant, lead.id).any { it.status !in setOf("canceled", "no_show") }
        if (stage.kind == StageKind.Won && pendingRooms && !reserved) {
            throw McpToolException("${lead.name} tiene habitaciones asignadas: la reserva debe confirmarse desde su ficha en el CRM.")
        }
        CrmRepository.moveLead(call.tenant, lead, stage, context.stages, actor(call))
    }

    private fun note(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        val body = try {
            CrmForms.note(mapOf("note" to call.string("note", 10_000)))
        } catch (failure: CrmValidationException) {
            throw McpToolException(failure.message.orEmpty())
        }
        CrmRepository.addNote(call.tenant, lead.id, body, actor(call))
        return buildJsonObject {
            put("id", lead.id.toString())
            put("result", "Nota agregada.")
        }
    }

    // The agent's arguments over [base], as the lead form would send them.
    private fun messages(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        return buildJsonObject {
            put("id", lead.id.toString())
            putJsonArray("messages") {
                CrmInbox.messages(call.tenant, lead.id, call.int("limit", 20, 1..100)).forEach { message ->
                    add(buildJsonObject {
                        put("direction", if (message.direction == "out") "enviado" else "recibido")
                        put("channel", CrmChannels.find(message.channel)?.label ?: message.channel)
                        if (message.subject.isNotEmpty()) put("subject", message.subject)
                        put("body", message.body.take(5_000))
                        put("status", message.status)
                        if (message.error.isNotEmpty()) put("error", message.error)
                        put("author", message.author)
                        put("at", message.createdAt.toString())
                    })
                }
            }
        }
    }

    private fun templates(call: McpToolCall): JsonElement = buildJsonObject {
        putJsonArray("channels") {
            CrmChannels.all.forEach { channel ->
                add(buildJsonObject {
                    put("id", channel.id)
                    put("name", channel.label)
                })
            }
        }
        putJsonArray("templates") {
            CrmMail.templates(call.tenant).forEach { template ->
                add(buildJsonObject {
                    put("name", template.name)
                    put("subject", template.subject)
                    put("body", template.body.take(5_000))
                })
            }
        }
    }

    // Same composition and channel checks as the reply form of the lead.
    private fun send(call: McpToolCall): JsonElement {
        val context = CrmScreens.Context.load(call.tenant, call.permissions, call.access.serverMail)
        val lead = visibleLead(call, context)
        val wanted = call.requiredString("channel", 40)
        val channel = CrmChannels.all.firstOrNull { it.id.equals(wanted, ignoreCase = true) || it.label.equals(wanted, ignoreCase = true) }
            ?: throw McpToolException("No existe el canal '$wanted'. Canales: ${CrmChannels.all.joinToString { it.label }}.")
        val values = mapOf(
            "channel" to channel.label,
            "template" to call.string("template", 200),
            "text" to call.string("text", 20_001),
            "subject" to call.string("subject", 500),
        )
        val (_, email) = try {
            CrmAdminScreens.composeReply(context, lead, values)
        } catch (failure: CrmValidationException) {
            throw McpToolException(failure.message.orEmpty())
        }
        val actor = actor(call)
        try {
            val externalId = channel.send(CrmChannelContext(call.tenant, call.access.serverMail), lead, email)
            CrmInbox.addMessage(call.tenant, lead.id, "out", channel.id, email.subject, email.text, externalId, "sent", "", actor.userId, actor.name)
        } catch (failure: CrmChannelException) {
            CrmInbox.addMessage(call.tenant, lead.id, "out", channel.id, email.subject, email.text, null, "failed", failure.message.orEmpty(), actor.userId, actor.name)
            throw McpToolException("No se pudo enviar por ${channel.label}: ${failure.message.orEmpty()}")
        }
        return buildJsonObject {
            put("id", lead.id.toString())
            put("result", "Mensaje enviado por ${channel.label}.")
        }
    }

    private fun reviewed(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        if (lead.reviewReason.isEmpty()) throw McpToolException("Ese lead no tiene nada pendiente de revisar.")
        CrmRepository.clearReview(call.tenant, lead, actor(call))
        return buildJsonObject {
            put("id", lead.id.toString())
            put("result", "Lead marcado como revisado.")
        }
    }

    private fun pendingAssignments(call: McpToolCall): JsonElement {
        val context = context(call)
        return buildJsonObject {
            putJsonArray("leads") {
                CrmRepository.pendingSuggestions(call.tenant, context.scope, call.int("limit", 50, 1..100)).forEach { add(json(it, context, brief = true)) }
            }
        }
    }

    private fun resolveAssignment(call: McpToolCall): JsonElement {
        val context = context(call)
        if (!context.canAssign) throw McpToolException("El usuario no tiene permiso para asignar leads.")
        if (!call.has("approve")) throw McpToolException("El argumento 'approve' es obligatorio.")
        val lead = visibleLead(call, context)
        if (lead.suggestedOwnerUserId == null) throw McpToolException("Ese lead no tiene una sugerencia de responsable pendiente.")
        val approve = call.boolean("approve")
        val owner = CrmRepository.resolveSuggestion(call.tenant, lead, approve, context.names, actor(call))
        owner?.takeIf { it != context.scope.userId }?.let { CrmNotify.assigned(call.tenant, lead.id, it) }
        return buildJsonObject {
            put("id", lead.id.toString())
            put("result", if (approve) "Responsable asignado: ${context.names.user(owner)}." else "Sugerencia descartada.")
        }
    }

    private fun delete(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = visibleLead(call, context)
        if (!CrmRepository.deleteLead(call.tenant, lead.id)) throw McpToolException("Ese lead ya no existe.")
        return buildJsonObject {
            put("id", lead.id.toString())
            put("result", "Lead ${lead.name} eliminado; el cliente sigue en Clientes.")
        }
    }

    private fun leadValues(call: McpToolCall, base: Map<String, String>): Map<String, String> =
        base + leadArguments.keys.filter { call.has(it) }.associateWith { call.string(it, 10_000) }

    private fun parse(values: Map<String, String>, context: CrmScreens.Context): CrmLeadInput = try {
        CrmForms.parseLead(values, context.stages, context.interests, context.interestsEnabled, context.users)
    } catch (failure: CrmValidationException) {
        throw McpToolException(failure.message.orEmpty())
    }

    private fun json(lead: CrmLead, context: CrmScreens.Context, brief: Boolean): JsonObject = buildJsonObject {
        put("id", lead.id.toString())
        put("customerId", lead.customerId.toString())
        put("name", lead.name)
        put("email", lead.email)
        put("phone", lead.phone)
        val stage = context.stage(lead.stageId)
        put("stage", stage?.name.orEmpty())
        put("stageKind", stage?.kind?.id.orEmpty())
        if (context.interestsEnabled) put("interest", lead.interestId?.let { context.names.interest(it) } ?: CrmForms.NoInterest)
        put("owner", context.names.user(lead.ownerUserId))
        put("source", lead.source)
        lead.followUpOn?.let { put("followUpOn", it.toString()) }
        lead.estimatedValue?.let { put("estimatedValue", it.toPlainString()) }
        put("createdAt", lead.createdAt.toString())
        lead.closedAt?.let { put("closedAt", it.toString()) }
        if (lead.reviewReason.isNotEmpty()) put("needsReview", lead.reviewReason)
        if (brief) {
            put("message", lead.message.take(300))
        } else {
            put("language", lead.language)
            put("message", lead.message)
            put("details", lead.details)
            lead.targetStart?.let { put("targetStart", it.toString()) }
            lead.targetEnd?.let { put("targetEnd", it.toString()) }
            lead.suggestedOwnerUserId?.let { put("suggestedOwner", context.names.user(it)) }
        }
    }
}
