package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * Agent tools of the automations. Configuring them needs crm.settings (like the screen); running one on a lead needs the same
 * rights as editing that lead. They handle no credentials: Cloudbeds actions use the integration the company already connected.
 */
object CrmAutomationMcpTools {
    private const val Confirm = " Cambia el CRM de toda la empresa: confirma con el usuario antes."

    private val triggerTypes = CrmTriggerType.entries.map { it.id }

    private val definitionArguments = mapOf(
        "name" to McpSchema.string("Nombre unico, hasta 120 caracteres."),
        "enabled" to McpSchema.boolean("true activa la regla; una regla apagada no se ejecuta. Por defecto se crea apagada."),
        "trigger" to McpSchema.string("Disparador: ${CrmTriggerType.entries.joinToString("; ") { "${it.id} (${it.label})" }}.", triggerTypes),
        "triggerConfig" to buildJsonObject {
            put("type", "object")
            put(
                "description",
                "Datos del disparador. stage_changed: {\"stage\": \"nombre o id de la etapa\"}. field_changed: {\"field\": \"nombre o clave del campo\"}. " +
                    "date: {\"field\": \"campo de fecha o 'Fecha de seguimiento'\", \"offsetDays\": -60 a 60 (negativo = antes), \"hour\": 0 a 23 en la zona horaria de la empresa}. " +
                    "Los demas disparadores no llevan datos.",
            )
        },
        "conditions" to buildJsonObject {
            put("type", "array")
            put(
                "description",
                "Condiciones (todas deben cumplirse; reemplaza la lista). Cada una: {\"kind\": \"stage_in\", \"stages\": [nombres]}, " +
                    "{\"kind\": \"interest_in\", \"interests\": [nombres], \"noInterest\": true|false}, " +
                    "{\"kind\": \"field_equals\" | \"field_not_equals\", \"field\": campo, \"value\": texto}, " +
                    "{\"kind\": \"field_empty\" | \"field_filled\", \"field\": campo}, {\"kind\": \"has_email\"}, {\"kind\": \"has_phone\"}.",
            )
            put("items", buildJsonObject { put("type", "object") })
        },
        "actions" to buildJsonObject {
            put("type", "array")
            put(
                "description",
                "Acciones en orden (reemplaza la lista; hasta ${CrmAutomationRules.MaxActions}; se detiene en la primera que falla). Cada una: " +
                    "{\"kind\": \"send_template\", \"template\": nombre} (al correo del lead, traducida si la plantilla lo pide), " +
                    "{\"kind\": \"set_stage\", \"stage\": nombre}, {\"kind\": \"set_field\", \"field\": campo, \"value\": texto (vacio borra)}, " +
                    "{\"kind\": \"notify\", \"who\": \"assigned\" | \"users\" | \"see_all\", \"users\": [nombres], \"title\": texto, \"text\": texto}, " +
                    "{\"kind\": \"cloudbeds_reserve\"} (crea la reserva con las habitaciones elegidas; necesita fechas, habitacion y correo) y " +
                    "{\"kind\": \"cloudbeds_checkin\"} (necesita una reserva). Cloudbeds solo con la integracion activa.",
            )
            put("items", buildJsonObject { put("type", "object") })
        },
        "warnBeforeMinutes" to McpSchema.integer("Solo disparador date: con esta anticipacion (minutos, 0 a ${CrmAutomationRules.MaxWarnMinutes}) se revisa si al lead le falta algo para la ejecucion y se avisa. 0 no avisa.", 0, CrmAutomationRules.MaxWarnMinutes),
        "notifyUsers" to buildJsonObject {
            put("type", "array")
            put("description", "Usuarios (nombres) que reciben el aviso de datos que faltan; vacio: los asignados del lead. Reemplaza la lista.")
            put("items", McpSchema.string("Nombre del usuario."))
        },
    )

    val tools = listOf(
        McpTool(
            name = "list_automations",
            title = "Automatizaciones del CRM",
            description = "Lista las automatizaciones de la empresa (disparador, condiciones, acciones, avisos y ultimas ejecuciones) y lo que se puede usar para crearlas: " +
                "disparadores, condiciones, acciones, plantillas, etapas, campos de fecha y si Cloudbeds esta activo. Las automatizaciones manuales se ejecutan con run_automation o con un boton del lead.",
            permission = CrmScreens.Settings,
            readOnly = true,
        ) { call -> list(call) },
        McpTool(
            name = "get_automation",
            title = "Detalle de una automatizacion",
            description = "Devuelve una automatizacion con sus ultimas 20 ejecuciones (lead, resultado y detalle).",
            permission = CrmScreens.Settings,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id de la automatizacion (list_automations).")), required = listOf("id")),
        ) { call -> get(call) },
        McpTool(
            name = "save_automation",
            title = "Crear o editar automatizacion",
            description = "Crea una automatizacion (sin id; name, trigger y actions obligatorios) o edita una (con id; solo cambia lo enviado). " +
                "Una de fecha corre una sola vez por lead y por valor de la fecha, a la hora elegida; las de evento corren al guardar el lead o al llegar el mensaje. " +
                "Los ids y nombres de etapas, plantillas, campos y usuarios salen de list_automations.$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(definitionArguments + ("id" to McpSchema.uuid("Id de la automatizacion a editar."))),
        ) { call -> save(call) },
        McpTool(
            name = "delete_automation",
            title = "Eliminar automatizacion",
            description = "Elimina una automatizacion y su registro de ejecuciones. Los botones que la usaban dejan de encontrarla.$Confirm",
            permission = CrmScreens.Settings,
            destructive = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id de la automatizacion.")), required = listOf("id")),
        ) { call -> delete(call) },
        McpTool(
            name = "list_automation_runs",
            title = "Ejecuciones de automatizaciones",
            description = "Ultimas ejecuciones y avisos de las automatizaciones, de la mas reciente a la mas antigua: lead, estado (done, failed, missing_data, warned, skipped) y detalle.",
            permission = CrmScreens.Settings,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "automation" to McpSchema.uuid("Solo las de esta automatizacion."),
                    "limit" to McpSchema.integer("Cuantas devolver (1 a 100, por defecto 30).", 1, 100),
                ),
            ),
        ) { call -> runs(call) },
        McpTool(
            name = "run_automation",
            title = "Ejecutar automatizacion manual en un lead",
            description = "Ejecuta una automatizacion con disparador manual (y activa) en un lead que el usuario puede editar, como lo haria un boton: " +
                "comprueba las condiciones y los datos que necesitan las acciones, y devuelve el resultado o lo que falta. Puede enviar correos o crear reservas: muestra lo que hara y confirma con el usuario antes.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf("id" to McpSchema.uuid("Id de la automatizacion manual."), "lead" to McpSchema.uuid("Id del lead.")),
                required = listOf("id", "lead"),
            ),
        ) { call -> runManual(call) },
        McpTool(
            name = "press_lead_button",
            title = "Pulsar un boton del lead",
            description = "Pulsa un campo de tipo boton de la ficha del lead (envia su plantilla o ejecuta sus automatizaciones manuales en orden). " +
                "Los botones salen de list_pipeline (customFields de tipo Boton). Puede enviar correos o crear reservas: confirma con el usuario antes.",
            permission = CrmScreens.Manage,
            inputSchema = McpSchema.obj(
                mapOf("lead" to McpSchema.uuid("Id del lead."), "button" to McpSchema.string("Clave o nombre del boton.")),
                required = listOf("lead", "button"),
            ),
        ) { call -> press(call) },
    )

    private fun context(call: McpToolCall) = CrmScreens.Context.load(call.tenant, call.permissions)

    private fun refs(call: McpToolCall, context: CrmScreens.Context) = CrmAutomationScreens.refsOf(context)

    private inline fun <T> validated(block: () -> T): T = try {
        block()
    } catch (failure: CrmValidationException) {
        throw McpToolException(failure.message.orEmpty())
    }

    // The user, marked as acting through an agent in the lead's activity.
    private fun actor(call: McpToolCall): CrmActor {
        val actor = CrmRepository.actor(call.tenant, call.permissions, call.access.userIdentity(call.tenant, call.permissions))
        return actor.copy(name = "${actor.name} (agente ${call.agentName})")
    }

    private fun automation(call: McpToolCall, key: String = "id"): CrmAutomation =
        CrmAutomationStore.find(call.tenant, call.requiredUuid(key)) ?: throw McpToolException("No existe esa automatizacion (ver list_automations).")

    // ---- JSON of an automation, with names so the agent can send them back ----

    fun json(automation: CrmAutomation, refs: CrmAutomationRefs): JsonObject = buildJsonObject {
        put("id", automation.id.toString())
        put("name", automation.name)
        put("enabled", automation.enabled)
        put("trigger", automation.trigger.type.id)
        put("triggerSummary", CrmAutomationSummary.trigger(automation.trigger, refs))
        put(
            "triggerConfig",
            buildJsonObject {
                val trigger = automation.trigger
                when (trigger.type) {
                    CrmTriggerType.StageChanged -> put("stage", refs.stageName(trigger.stageId))
                    CrmTriggerType.FieldChanged -> put("field", refs.fieldLabel(trigger.field))
                    CrmTriggerType.Date -> {
                        put("field", refs.fieldLabel(trigger.field))
                        put("offsetDays", trigger.offsetDays)
                        put("hour", trigger.hour)
                    }
                    else -> Unit
                }
            },
        )
        putJsonArray("conditions") {
            automation.conditions.forEach { condition ->
                add(
                    buildJsonObject {
                        put("kind", condition.kind.id)
                        when (condition.kind) {
                            CrmConditionKind.StageIn -> put("stages", JsonArray(condition.stageIds.map { JsonPrimitive(refs.stageName(it)) }))
                            CrmConditionKind.InterestIn -> {
                                put("interests", JsonArray(condition.interestIds.map { id -> JsonPrimitive(refs.interests.firstOrNull { it.id == id }?.name ?: "interes eliminado") }))
                                put("noInterest", condition.noInterest)
                            }
                            else -> if (condition.kind.usesField) {
                                put("field", refs.fieldLabel(condition.field))
                                if (condition.kind.usesValue) put("value", condition.value)
                            }
                        }
                    },
                )
            }
        }
        putJsonArray("actions") {
            automation.actions.forEach { action ->
                add(
                    buildJsonObject {
                        put("kind", action.kind.id)
                        when (action.kind) {
                            CrmActionKind.SendTemplate -> put("template", refs.templates.firstOrNull { it.id == action.templateId }?.name ?: "plantilla eliminada")
                            CrmActionKind.SetStage -> put("stage", refs.stageName(action.stageId))
                            CrmActionKind.SetField -> {
                                put("field", refs.fieldLabel(action.field))
                                put("value", action.value)
                            }
                            CrmActionKind.Notify -> {
                                put("who", action.who.id)
                                put("users", JsonArray(action.userIds.map { id -> JsonPrimitive(refs.users.firstOrNull { it.id == id }?.name ?: "usuario sin acceso") }))
                                put("title", action.title)
                                put("text", action.text)
                            }
                            else -> Unit
                        }
                    },
                )
            }
        }
        put("warnBeforeMinutes", automation.warnBeforeMinutes)
        put("notifyUsers", JsonArray(automation.notifyUserIds.map { id -> JsonPrimitive(refs.users.firstOrNull { it.id == id }?.name ?: "usuario sin acceso") }))
    }

    private fun runJson(run: CrmRunRow): JsonObject = buildJsonObject {
        put("automation", run.automationName)
        put("automationId", run.automationId.toString())
        put("lead", run.leadName)
        put("leadId", run.leadId.toString())
        put("status", run.status)
        put("detail", run.detail)
        put("at", run.createdAt.toString())
    }

    // ---- Tools ----

    private fun list(call: McpToolCall): JsonElement {
        val context = context(call)
        val refs = refs(call, context)
        val automations = CrmAutomationStore.all(call.tenant)
        val recent = CrmAutomationStore.recent(call.tenant, null, 200).groupBy { it.automationId }
        return buildJsonObject {
            putJsonArray("automations") {
                automations.forEach { automation ->
                    add(
                        buildJsonObject {
                            json(automation, refs).forEach { (key, value) -> put(key, value) }
                            putJsonArray("lastRuns") { recent[automation.id].orEmpty().take(3).forEach { add(runJson(it)) } }
                        },
                    )
                }
            }
            put("cloudbedsActive", refs.cloudbedsReady)
            put("timeZone", CrmZone.of(call.tenant).id)
            putJsonArray("triggers") { CrmTriggerType.entries.forEach { add(buildJsonObject { put("id", it.id); put("label", it.label) }) } }
            putJsonArray("conditionKinds") { CrmConditionKind.entries.forEach { add(buildJsonObject { put("id", it.id); put("label", it.label) }) } }
            putJsonArray("actionKinds") {
                CrmActionKind.entries.filter { refs.cloudbedsReady || !it.isCloudbeds }.forEach { add(buildJsonObject { put("id", it.id); put("label", it.label) }) }
            }
            putJsonArray("templates") { refs.templates.forEach { add(buildJsonObject { put("id", it.id.toString()); put("name", it.name) }) } }
            putJsonArray("stages") { refs.stages.forEach { add(JsonPrimitive(it.name)) } }
            putJsonArray("users") { refs.users.forEach { add(JsonPrimitive(it.name)) } }
            putJsonArray("dateFields") {
                add(JsonPrimitive(CrmAutomationRefs.FollowUpLabel))
                refs.fields.filter { it.type == CrmFieldType.Date }.forEach { add(JsonPrimitive(it.label)) }
            }
            putJsonArray("fields") {
                refs.fields.filter { it.type != CrmFieldType.Files && it.type != CrmFieldType.Button }.forEach { add(buildJsonObject { put("key", it.key); put("name", it.label); put("type", it.type.label) }) }
            }
        }
    }

    private fun get(call: McpToolCall): JsonElement {
        val refs = refs(call, context(call))
        val automation = automation(call)
        return buildJsonObject {
            json(automation, refs).forEach { (key, value) -> put(key, value) }
            putJsonArray("runs") { CrmAutomationStore.recent(call.tenant, automation.id, 20).forEach { add(runJson(it)) } }
        }
    }

    private fun save(call: McpToolCall): JsonElement {
        val context = context(call)
        val refs = refs(call, context)
        val existing = CrmAutomationStore.all(call.tenant)
        val current = call.uuid("id")?.let { id -> existing.firstOrNull { it.id == id } ?: throw McpToolException("No existe esa automatizacion (ver list_automations).") }
        val saved = validated {
            val type = if (call.has("trigger")) {
                CrmTriggerType.byId(call.string("trigger", 40)) ?: throw McpToolException("'trigger' debe ser uno de: ${triggerTypes.joinToString()}.")
            } else current?.trigger?.type ?: throw McpToolException("El argumento 'trigger' es obligatorio.")
            val trigger = when {
                call.has("triggerConfig") -> CrmAutomationJson.parseTrigger(type, call.arguments["triggerConfig"] as? JsonObject ?: throw McpToolException("'triggerConfig' debe ser un objeto."), refs)
                current != null && current.trigger.type == type -> current.trigger
                else -> CrmAutomationJson.parseTrigger(type, JsonObject(emptyMap()), refs)
            }
            val name = if (call.has("name")) call.string("name", 200) else current?.name ?: throw McpToolException("El argumento 'name' es obligatorio.")
            val actions = if (call.has("actions")) CrmAutomationJson.parseActions(call.arguments["actions"], refs) else current?.actions ?: throw McpToolException("El argumento 'actions' es obligatorio.")
            val notify = if (call.has("notifyUsers")) {
                strings(call, "notifyUsers").map { refs.user(it) ?: throw McpToolException("No es un usuario con acceso al CRM: $it. Usuarios: ${refs.users.joinToString { u -> u.name }}.") }
            } else current?.notifyUserIds.orEmpty()
            val automation = CrmAutomation(
                id = current?.id ?: UUID.randomUUID(),
                name = name,
                enabled = if (call.has("enabled")) call.boolean("enabled") else current?.enabled ?: false,
                trigger = trigger,
                conditions = if (call.has("conditions")) CrmAutomationJson.parseConditions(call.arguments["conditions"], refs) else current?.conditions.orEmpty(),
                actions = actions,
                warnBeforeMinutes = if (call.has("warnBeforeMinutes")) call.int("warnBeforeMinutes", 0, 0..CrmAutomationRules.MaxWarnMinutes) else current?.warnBeforeMinutes ?: 0,
                notifyUserIds = notify,
                position = current?.position ?: CrmAutomationStore.nextPosition(call.tenant),
            ).normalized()
            CrmAutomationRules.validate(automation, refs, existing)
            CrmAutomationStore.save(call.tenant, automation, exists = current != null)
            automation
        }
        val duplicate = CrmAutomationRules.duplicatesCloudbedsWelcome(
            saved,
            CrmStay.effective(context.stay, context.fields).let { listOf(it.startKey, it.endKey).filter { key -> key.isNotEmpty() } },
            CrmCloudbeds.active(call.tenant) && CrmCloudbeds.load(call.tenant).welcomeEnabled,
        )
        return buildJsonObject {
            put("id", saved.id.toString())
            put("result", "Automatizacion ${saved.name} guardada${if (saved.enabled) " y activa" else " (apagada)"}.")
            val warnings = listOfNotNull(
                (CrmAutomationRules.DuplicateWelcomeWarning + " La bienvenida de Cloudbeds se apaga en la app: Configuracion > Automatizaciones.").takeIf { duplicate },
                CrmAutomationRules.messageReplyNotice(saved),
            )
            if (warnings.isNotEmpty()) put("warning", warnings.joinToString(" "))
        }
    }

    private fun strings(call: McpToolCall, key: String): List<String> =
        (call.arguments[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull?.trim() ?: throw McpToolException("'$key' debe ser una lista de textos.") }
            ?: throw McpToolException("'$key' debe ser una lista.")

    private fun delete(call: McpToolCall): JsonElement {
        val automation = automation(call)
        CrmAutomationStore.delete(call.tenant, automation.id)
        return buildJsonObject {
            put("id", automation.id.toString())
            put("result", "Automatizacion ${automation.name} eliminada.")
        }
    }

    private fun runs(call: McpToolCall): JsonElement {
        val runs = CrmAutomationStore.recent(call.tenant, call.uuid("automation"), call.int("limit", 30, 1..100))
        return buildJsonObject { putJsonArray("runs") { runs.forEach { add(runJson(it)) } } }
    }

    // The lead the user can edit: inside their scope and not only through a mention.
    private fun editableLead(call: McpToolCall, context: CrmScreens.Context): CrmLead =
        CrmRepository.lead(call.tenant, call.requiredUuid("lead"))?.takeIf { context.scope.canSee(it.interestId, it.ownerUserIds) && context.readAccess(it).canWrite }
            ?: throw McpToolException("Lead no encontrado o no editable para el usuario.")

    private fun runManual(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = editableLead(call, context)
        val automation = automation(call)
        if (lead.archivedAt != null) throw McpToolException("El lead esta archivado: restauralo antes de ejecutar automatizaciones.")
        if (automation.trigger.type != CrmTriggerType.Manual) throw McpToolException("${automation.name} no es manual: solo las de disparador manual se ejecutan a pedido.")
        if (!automation.enabled) throw McpToolException("${automation.name} esta apagada.")
        val outcome = CrmAutomationEngine.run(call.tenant, automation, lead, CrmAutomationKeys.button(UUID.randomUUID()), actor(call), call.notifier)
        return buildJsonObject {
            put("id", automation.id.toString())
            put("leadId", lead.id.toString())
            put("status", outcome.status)
            put("result", outcome.detail)
        }
    }

    private fun press(call: McpToolCall): JsonElement {
        val context = context(call)
        val lead = editableLead(call, context)
        val wanted = call.requiredString("button", 200)
        val field = context.fields.firstOrNull { it.type == CrmFieldType.Button && (it.key == wanted || it.label.equals(wanted, ignoreCase = true)) }
            ?: throw McpToolException("No existe el boton '$wanted'. Botones: ${context.fields.filter { it.type == CrmFieldType.Button }.joinToString { it.label }}.")
        val result = CrmButtons.press(call.tenant, lead, field, actor(call), call.notifier)
        return buildJsonObject {
            put("leadId", lead.id.toString())
            put("button", field.label)
            put("tone", result.tone)
            put("result", result.text)
        }
    }
}
