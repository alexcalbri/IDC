package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// Model, validation and pure rules of the automations (CRM 0.24.0). The database lives in CrmAutomationStore and the
// execution in CrmAutomationEngine; everything here can be tested without them.

enum class CrmTriggerType(val id: String, val label: String) {
    LeadCreated("lead_created", "Lead creado"),
    StageChanged("stage_changed", "Cambio a una etapa"),
    FieldChanged("field_changed", "Cambio de un campo"),
    MessageReceived("message_received", "Mensaje nuevo entrante"),
    Date("date", "Fecha"),
    Manual("manual", "Manual (boton)");

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun byLabel(label: String) = entries.firstOrNull { it.label == label }
    }
}

/** Trigger with its settings: [stageId] (stage_changed), [field] (field_changed and date), [offsetDays] and [hour] (date). */
data class CrmTrigger(
    val type: CrmTriggerType,
    val stageId: UUID? = null,
    val field: String = "",
    val offsetDays: Int = 0,
    val hour: Int = 9,
)

enum class CrmConditionKind(val id: String, val label: String) {
    StageIn("stage_in", "La etapa es una de"),
    InterestIn("interest_in", "El interes es uno de"),
    FieldEquals("field_equals", "Un campo es igual a"),
    FieldNotEquals("field_not_equals", "Un campo es distinto de"),
    FieldEmpty("field_empty", "Un campo esta vacio"),
    FieldFilled("field_filled", "Un campo esta lleno"),
    HasEmail("has_email", "Tiene correo"),
    HasPhone("has_phone", "Tiene telefono");

    val usesField get() = this == FieldEquals || this == FieldNotEquals || this == FieldEmpty || this == FieldFilled
    val usesValue get() = this == FieldEquals || this == FieldNotEquals

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun byLabel(label: String) = entries.firstOrNull { it.label == label }
    }
}

data class CrmCondition(
    val kind: CrmConditionKind,
    val stageIds: List<UUID> = emptyList(),
    val interestIds: List<UUID> = emptyList(),
    // interest_in: also true for leads without interest.
    val noInterest: Boolean = false,
    val field: String = "",
    val value: String = "",
)

enum class CrmActionKind(val id: String, val label: String) {
    SendTemplate("send_template", "Enviar una plantilla al correo del lead"),
    SetStage("set_stage", "Cambiar la etapa"),
    SetField("set_field", "Fijar un campo"),
    Notify("notify", "Avisar a usuarios"),
    CloudbedsReserve("cloudbeds_reserve", "Cloudbeds: crear la reserva"),
    CloudbedsCheckIn("cloudbeds_checkin", "Cloudbeds: hacer el check-in");

    val isCloudbeds get() = this == CloudbedsReserve || this == CloudbedsCheckIn

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun byLabel(label: String) = entries.firstOrNull { it.label == label }
    }
}

/** Who a "notify" action tells. */
enum class CrmNotifyWho(val id: String, val label: String) {
    Assigned("assigned", "Asignados del lead"),
    Users("users", "Usuarios elegidos"),
    SeeAll("see_all", "Quienes ven todos los leads");

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun byLabel(label: String) = entries.firstOrNull { it.label == label }
    }
}

data class CrmAction(
    val kind: CrmActionKind,
    val templateId: UUID? = null,
    val stageId: UUID? = null,
    // set_field: key of the custom field (or [CrmAutomationRefs.FollowUpField]) and the value to write ("" clears it).
    val field: String = "",
    val value: String = "",
    val who: CrmNotifyWho = CrmNotifyWho.Assigned,
    val userIds: List<UUID> = emptyList(),
    val title: String = "",
    val text: String = "",
)

data class CrmAutomation(
    val id: UUID,
    val name: String,
    val enabled: Boolean,
    val trigger: CrmTrigger,
    val conditions: List<CrmCondition> = emptyList(),
    val actions: List<CrmAction> = emptyList(),
    val warnBeforeMinutes: Int = 0,
    val notifyUserIds: List<UUID> = emptyList(),
    val position: Int = 0,
) {
    /** Only date rules warn ahead. */
    fun normalized() = if (trigger.type == CrmTriggerType.Date) this else copy(warnBeforeMinutes = 0)
}

/** What the ids of an automation must point to; also resolves names (agents and forms) to ids. */
class CrmAutomationRefs(
    val stages: List<CrmStage> = emptyList(),
    val interests: List<CrmInterest> = emptyList(),
    val templates: List<CrmTemplate> = emptyList(),
    val users: List<CrmUser> = emptyList(),
    val fields: List<CrmField> = emptyList(),
    val cloudbedsReady: Boolean = false,
) {
    companion object {
        /** The follow-up date of the lead, usable as the date of a rule and as a field to set. */
        const val FollowUpField = "follow_up_on"
        const val FollowUpLabel = "Fecha de seguimiento"
    }

    fun stage(text: String): UUID? = stages.firstOrNull { it.id.toString() == text || it.name.equals(text.trim(), ignoreCase = true) }?.id
    fun interest(text: String): UUID? = interests.firstOrNull { it.id.toString() == text || it.name.equals(text.trim(), ignoreCase = true) }?.id
    fun template(text: String): UUID? = templates.firstOrNull { it.id.toString() == text || it.name.equals(text.trim(), ignoreCase = true) }?.id
    fun user(text: String): UUID? = users.firstOrNull { it.id.toString() == text || it.name.equals(text.trim(), ignoreCase = true) }?.id

    /** Key of a custom field by key or label; the follow-up date by its key or label. */
    fun field(text: String): String? {
        val clean = text.trim()
        if (clean == FollowUpField || clean.equals(FollowUpLabel, ignoreCase = true)) return FollowUpField
        return fields.firstOrNull { it.key == clean || it.label.equals(clean, ignoreCase = true) }?.key
    }

    fun fieldLabel(key: String): String =
        if (key == FollowUpField) FollowUpLabel else fields.firstOrNull { it.key == key }?.label ?: key

    fun stageName(id: UUID?): String = stages.firstOrNull { it.id == id }?.name ?: "etapa eliminada"
}

/** JSON of the columns trigger_config, conditions, actions and notify_user_ids. */
object CrmAutomationJson {
    // ---- To JSON ----

    fun trigger(trigger: CrmTrigger): JsonObject = buildJsonObject {
        when (trigger.type) {
            CrmTriggerType.StageChanged -> trigger.stageId?.let { put("stage", it.toString()) }
            CrmTriggerType.FieldChanged -> put("field", trigger.field)
            CrmTriggerType.Date -> {
                put("field", trigger.field)
                put("offsetDays", trigger.offsetDays)
                put("hour", trigger.hour)
            }
            else -> Unit
        }
    }

    fun condition(condition: CrmCondition): JsonObject = buildJsonObject {
        put("kind", condition.kind.id)
        when (condition.kind) {
            CrmConditionKind.StageIn -> put("stages", JsonArray(condition.stageIds.map { JsonPrimitive(it.toString()) }))
            CrmConditionKind.InterestIn -> {
                put("interests", JsonArray(condition.interestIds.map { JsonPrimitive(it.toString()) }))
                put("noInterest", condition.noInterest)
            }
            CrmConditionKind.FieldEquals, CrmConditionKind.FieldNotEquals -> {
                put("field", condition.field)
                put("value", condition.value)
            }
            CrmConditionKind.FieldEmpty, CrmConditionKind.FieldFilled -> put("field", condition.field)
            else -> Unit
        }
    }

    fun action(action: CrmAction): JsonObject = buildJsonObject {
        put("kind", action.kind.id)
        when (action.kind) {
            CrmActionKind.SendTemplate -> action.templateId?.let { put("template", it.toString()) }
            CrmActionKind.SetStage -> action.stageId?.let { put("stage", it.toString()) }
            CrmActionKind.SetField -> {
                put("field", action.field)
                put("value", action.value)
            }
            CrmActionKind.Notify -> {
                put("who", action.who.id)
                put("users", JsonArray(action.userIds.map { JsonPrimitive(it.toString()) }))
                put("title", action.title)
                put("text", action.text)
            }
            else -> Unit
        }
    }

    fun conditions(list: List<CrmCondition>) = JsonArray(list.map { condition(it) })
    fun actions(list: List<CrmAction>) = JsonArray(list.map { action(it) })
    fun ids(list: List<UUID>) = JsonArray(list.map { JsonPrimitive(it.toString()) })

    // ---- From JSON (strict: a wrong entry is a CrmValidationException; names resolve through [refs] when given) ----

    private fun text(json: JsonObject, key: String): String = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    private fun texts(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { value -> value.isNotEmpty() } }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
        else -> emptyList()
    }

    private fun uuid(value: String, lookup: ((String) -> UUID?)?, what: String): UUID =
        lookup?.invoke(value) ?: runCatching { UUID.fromString(value) }.getOrNull() ?: throw CrmValidationException("No existe $what '$value'.")

    fun uuids(element: JsonElement?, lookup: ((String) -> UUID?)?, what: String): List<UUID> =
        texts(element).map { uuid(it, lookup, what) }.distinct()

    fun parseTrigger(type: CrmTriggerType, config: JsonObject, refs: CrmAutomationRefs?): CrmTrigger {
        fun fieldKey(): String {
            val raw = text(config, "field")
            if (raw.isEmpty()) return ""
            return refs?.field(raw) ?: if (refs == null) raw else throw CrmValidationException("No existe el campo '$raw'.")
        }
        return when (type) {
            CrmTriggerType.StageChanged -> {
                val raw = text(config, "stage")
                CrmTrigger(type, stageId = raw.takeIf { it.isNotEmpty() }?.let { uuid(it, refs?.let { r -> r::stage }, "la etapa") })
            }
            CrmTriggerType.FieldChanged -> CrmTrigger(type, field = fieldKey())
            CrmTriggerType.Date -> CrmTrigger(
                type,
                field = fieldKey(),
                offsetDays = text(config, "offsetDays").ifEmpty { "0" }.toIntOrNull() ?: throw CrmValidationException("Los dias deben ser un numero entero."),
                hour = text(config, "hour").ifEmpty { "9" }.toIntOrNull() ?: throw CrmValidationException("La hora debe ser un numero entero."),
            )
            else -> CrmTrigger(type)
        }
    }

    fun parseCondition(json: JsonObject, refs: CrmAutomationRefs?): CrmCondition {
        val kind = CrmConditionKind.byId(text(json, "kind")) ?: throw CrmValidationException("Condicion desconocida '${text(json, "kind")}'. Condiciones: ${CrmConditionKind.entries.joinToString { it.id }}.")
        fun fieldKey(): String {
            val raw = text(json, "field")
            if (raw.isEmpty()) throw CrmValidationException("La condicion ${kind.id} necesita el campo (field).")
            return refs?.field(raw)?.takeIf { it != CrmAutomationRefs.FollowUpField } ?: if (refs == null) raw else throw CrmValidationException("No existe el campo '$raw'.")
        }
        return when (kind) {
            CrmConditionKind.StageIn -> CrmCondition(kind, stageIds = uuids(json["stages"], refs?.let { r -> r::stage }, "la etapa"))
            CrmConditionKind.InterestIn -> CrmCondition(
                kind,
                interestIds = uuids(json["interests"], refs?.let { r -> r::interest }, "el interes"),
                noInterest = (json["noInterest"] as? JsonPrimitive)?.contentOrNull == "true",
            )
            CrmConditionKind.FieldEquals, CrmConditionKind.FieldNotEquals -> CrmCondition(kind, field = fieldKey(), value = text(json, "value"))
            CrmConditionKind.FieldEmpty, CrmConditionKind.FieldFilled -> CrmCondition(kind, field = fieldKey())
            else -> CrmCondition(kind)
        }
    }

    fun parseAction(json: JsonObject, refs: CrmAutomationRefs?): CrmAction {
        val kind = CrmActionKind.byId(text(json, "kind")) ?: throw CrmValidationException("Accion desconocida '${text(json, "kind")}'. Acciones: ${CrmActionKind.entries.joinToString { it.id }}.")
        return when (kind) {
            CrmActionKind.SendTemplate -> CrmAction(kind, templateId = text(json, "template").takeIf { it.isNotEmpty() }?.let { uuid(it, refs?.let { r -> r::template }, "la plantilla") })
            CrmActionKind.SetStage -> CrmAction(kind, stageId = text(json, "stage").takeIf { it.isNotEmpty() }?.let { uuid(it, refs?.let { r -> r::stage }, "la etapa") })
            CrmActionKind.SetField -> {
                val raw = text(json, "field")
                val key = if (raw.isEmpty()) "" else refs?.field(raw) ?: if (refs == null) raw else throw CrmValidationException("No existe el campo '$raw'.")
                CrmAction(kind, field = key, value = (json["value"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim())
            }
            CrmActionKind.Notify -> CrmAction(
                kind,
                who = CrmNotifyWho.byId(text(json, "who").ifEmpty { CrmNotifyWho.Assigned.id }) ?: throw CrmValidationException("'who' debe ser assigned, users o see_all."),
                userIds = uuids(json["users"], refs?.let { r -> r::user }, "el usuario"),
                title = text(json, "title"),
                text = text(json, "text"),
            )
            else -> CrmAction(kind)
        }
    }

    fun parseConditions(element: JsonElement?, refs: CrmAutomationRefs?): List<CrmCondition> = when (element) {
        null -> emptyList()
        is JsonArray -> element.map { parseCondition(it as? JsonObject ?: throw CrmValidationException("Cada condicion es un objeto con 'kind'."), refs) }
        else -> throw CrmValidationException("'conditions' debe ser una lista.")
    }

    fun parseActions(element: JsonElement?, refs: CrmAutomationRefs?): List<CrmAction> = when (element) {
        null -> emptyList()
        is JsonArray -> element.map { parseAction(it as? JsonObject ?: throw CrmValidationException("Cada accion es un objeto con 'kind'."), refs) }
        else -> throw CrmValidationException("'actions' debe ser una lista.")
    }
}

/** Saving rules of an automation: every id must exist and every number be in range. */
object CrmAutomationRules {
    const val MaxActions = 10
    const val MaxConditions = 10
    const val MaxWarnMinutes = 43200

    /** Throws [CrmValidationException] in Spanish. [others] are the saved automations (names are unique). */
    fun validate(automation: CrmAutomation, refs: CrmAutomationRefs, others: List<CrmAutomation> = emptyList()) {
        val name = automation.name.trim()
        if (name.isEmpty() || name.length > 120) throw CrmValidationException("El nombre debe tener entre 1 y 120 caracteres.")
        if (others.any { it.id != automation.id && it.name.trim().equals(name, ignoreCase = true) }) throw CrmValidationException("Ya existe la automatizacion $name.")
        validateTrigger(automation.trigger, refs)
        if (automation.conditions.size > MaxConditions) throw CrmValidationException("Una automatizacion admite hasta $MaxConditions condiciones.")
        automation.conditions.forEach { validateCondition(it, refs) }
        if (automation.actions.isEmpty()) throw CrmValidationException("Agrega al menos una accion.")
        if (automation.actions.size > MaxActions) throw CrmValidationException("Una automatizacion admite hasta $MaxActions acciones.")
        automation.actions.forEachIndexed { index, action -> validateAction(action, index + 1, refs) }
        if (automation.warnBeforeMinutes !in 0..MaxWarnMinutes) throw CrmValidationException("El aviso anticipado va de 0 a $MaxWarnMinutes minutos (30 dias).")
        if (automation.warnBeforeMinutes > 0 && automation.trigger.type != CrmTriggerType.Date) {
            throw CrmValidationException("El aviso anticipado solo existe en las reglas de fecha.")
        }
        automation.notifyUserIds.firstOrNull { id -> refs.users.none { it.id == id } }?.let { throw CrmValidationException("Uno de los usuarios a avisar ya no tiene acceso al CRM.") }
    }

    private fun validateTrigger(trigger: CrmTrigger, refs: CrmAutomationRefs) {
        when (trigger.type) {
            CrmTriggerType.StageChanged -> {
                val stage = trigger.stageId ?: throw CrmValidationException("Elige la etapa del disparador.")
                if (refs.stages.none { it.id == stage }) throw CrmValidationException("La etapa del disparador ya no existe.")
            }
            CrmTriggerType.FieldChanged -> {
                if (trigger.field.isEmpty()) throw CrmValidationException("Elige el campo del disparador.")
                if (trigger.field != CrmAutomationRefs.FollowUpField && refs.fields.none { it.key == trigger.field && it.type != CrmFieldType.Files && it.type != CrmFieldType.Button }) {
                    throw CrmValidationException("El campo del disparador ya no existe.")
                }
            }
            CrmTriggerType.Date -> {
                if (trigger.field.isEmpty()) throw CrmValidationException("Elige el campo de fecha del disparador.")
                if (trigger.field != CrmAutomationRefs.FollowUpField && refs.fields.none { it.key == trigger.field && it.type == CrmFieldType.Date }) {
                    throw CrmValidationException("El disparador de fecha necesita un campo de tipo fecha o la fecha de seguimiento.")
                }
                if (trigger.offsetDays !in -60..60) throw CrmValidationException("Los dias antes o despues van de -60 a 60.")
                if (trigger.hour !in 0..23) throw CrmValidationException("La hora debe ser un numero de 0 a 23.")
            }
            else -> Unit
        }
    }

    private fun validateCondition(condition: CrmCondition, refs: CrmAutomationRefs) {
        when (condition.kind) {
            CrmConditionKind.StageIn -> {
                if (condition.stageIds.isEmpty()) throw CrmValidationException("Elige al menos una etapa en la condicion.")
                if (condition.stageIds.any { id -> refs.stages.none { it.id == id } }) throw CrmValidationException("Una etapa de las condiciones ya no existe.")
            }
            CrmConditionKind.InterestIn -> {
                if (condition.interestIds.isEmpty() && !condition.noInterest) throw CrmValidationException("Elige al menos un interes en la condicion.")
                if (condition.interestIds.any { id -> refs.interests.none { it.id == id } }) throw CrmValidationException("Un interes de las condiciones ya no existe.")
            }
            else -> if (condition.kind.usesField) {
                if (refs.fields.none { it.key == condition.field && it.type != CrmFieldType.Files && it.type != CrmFieldType.Button }) {
                    throw CrmValidationException("El campo de una condicion ya no existe.")
                }
                if (condition.kind.usesValue && condition.value.isEmpty()) throw CrmValidationException("Escribe el valor de la condicion.")
            }
        }
    }

    private fun validateAction(action: CrmAction, number: Int, refs: CrmAutomationRefs) {
        val where = "Accion $number"
        when (action.kind) {
            CrmActionKind.SendTemplate -> {
                val template = action.templateId ?: throw CrmValidationException("$where: elige la plantilla.")
                if (refs.templates.none { it.id == template }) throw CrmValidationException("$where: la plantilla ya no existe.")
            }
            CrmActionKind.SetStage -> {
                val stage = action.stageId ?: throw CrmValidationException("$where: elige la etapa.")
                if (refs.stages.none { it.id == stage }) throw CrmValidationException("$where: la etapa ya no existe.")
            }
            CrmActionKind.SetField -> {
                if (action.field.isEmpty()) throw CrmValidationException("$where: elige el campo.")
                if (action.field == CrmAutomationRefs.FollowUpField) {
                    if (action.value.isNotEmpty()) runCatching { LocalDate.parse(action.value) }.getOrElse { throw CrmValidationException("$where: la fecha de seguimiento usa el formato AAAA-MM-DD.") }
                } else {
                    val field = refs.fields.firstOrNull { it.key == action.field && it.type != CrmFieldType.Files && it.type != CrmFieldType.Button }
                        ?: throw CrmValidationException("$where: el campo ya no existe.")
                    try {
                        CrmFields.parseValues(mapOf(field.key to action.value), listOf(field.copy(required = false)))
                    } catch (failure: CrmValidationException) {
                        throw CrmValidationException("$where: ${failure.message}")
                    }
                }
            }
            CrmActionKind.Notify -> {
                if (action.who == CrmNotifyWho.Users && action.userIds.isEmpty()) throw CrmValidationException("$where: elige a quien avisar.")
                if (action.userIds.any { id -> refs.users.none { it.id == id } }) throw CrmValidationException("$where: un usuario ya no tiene acceso al CRM.")
                if (action.title.length > 120) throw CrmValidationException("$where: el titulo admite hasta 120 caracteres.")
                if (action.text.length > 500) throw CrmValidationException("$where: el texto admite hasta 500 caracteres.")
            }
            CrmActionKind.CloudbedsReserve, CrmActionKind.CloudbedsCheckIn ->
                if (!refs.cloudbedsReady) throw CrmValidationException("$where: activa Cloudbeds en Configuracion > Integraciones para usar esta accion.")
        }
    }

    /**
     * Whether an enabled rule that sends a template on a stay date can send a second email next to the welcome of Cloudbeds
     * ([welcomeActive]: Cloudbeds ready with its welcome on). [stayKeys]: keys of the fields mapped as check-in and check-out.
     */
    fun duplicatesCloudbedsWelcome(automation: CrmAutomation, stayKeys: Collection<String>, welcomeActive: Boolean): Boolean =
        welcomeActive && automation.enabled && automation.trigger.type == CrmTriggerType.Date &&
            automation.trigger.field.isNotEmpty() && automation.trigger.field in stayKeys &&
            automation.actions.any { it.kind == CrmActionKind.SendTemplate }

    /** The notice for a rule that answers every incoming message: it can reply only once a day per lead. Null for the rest. */
    fun messageReplyNotice(automation: CrmAutomation): String? =
        if (automation.trigger.type == CrmTriggerType.MessageReceived && automation.actions.any { it.kind == CrmActionKind.SendTemplate }) {
            "Con \"mensaje nuevo\" y \"enviar plantilla\" la regla responde como maximo una vez al dia por lead (y automatizaciones en total, 3 correos por hora por lead), para no cruzarse con respuestas automaticas."
        } else null

    const val DuplicateWelcomeWarning = "Esta regla envia un correo por una fecha de la estadia y la bienvenida de Cloudbeds tambien esta activa: " +
        "el huesped puede recibir dos correos."
}

/** Conditions of an automation against a lead. */
object CrmAutomationConditions {
    fun matches(condition: CrmCondition, lead: CrmLead): Boolean = when (condition.kind) {
        CrmConditionKind.StageIn -> lead.stageId in condition.stageIds
        CrmConditionKind.InterestIn -> if (lead.interestId == null) condition.noInterest else lead.interestId in condition.interestIds
        CrmConditionKind.FieldEquals -> fieldMatches(lead.customFields[condition.field], condition.value)
        CrmConditionKind.FieldNotEquals -> !fieldMatches(lead.customFields[condition.field], condition.value)
        CrmConditionKind.FieldEmpty -> isEmpty(lead.customFields[condition.field])
        CrmConditionKind.FieldFilled -> !isEmpty(lead.customFields[condition.field])
        CrmConditionKind.HasEmail -> CrmContacts.isEmail(lead.email)
        CrmConditionKind.HasPhone -> lead.phone.isNotBlank()
    }

    /** The first condition the lead does not meet; null when it meets all of them (or there are none). */
    fun failing(conditions: List<CrmCondition>, lead: CrmLead): CrmCondition? = conditions.firstOrNull { !matches(it, lead) }

    fun isEmpty(value: JsonElement?): Boolean = when (value) {
        null -> true
        is JsonArray -> value.isEmpty()
        is JsonPrimitive -> value.contentOrNull.isNullOrBlank()
        else -> false
    }

    /** Equal when any of the values of the field (a list has several) equals [expected], ignoring case; Si / No and true / false match. */
    fun fieldMatches(value: JsonElement?, expected: String): Boolean {
        val wanted = normalize(expected)
        val shown = when (value) {
            null -> emptyList()
            is JsonArray -> value.map { CrmFields.display(it) }
            else -> listOf(CrmFields.display(value))
        }
        return shown.any { normalize(it) == wanted } || (shown.size > 1 && normalize(shown.joinToString(", ")) == wanted)
    }

    private fun normalize(text: String): String {
        val clean = text.trim().lowercase()
        return when (clean) {
            "true", "si", "sí", "yes" -> "si"
            "false", "no" -> "no"
            else -> clean
        }
    }

    fun label(condition: CrmCondition, refs: CrmAutomationRefs): String = when (condition.kind) {
        CrmConditionKind.StageIn -> "etapa en ${condition.stageIds.joinToString { refs.stageName(it) }}"
        CrmConditionKind.InterestIn -> "interes en ${(condition.interestIds.map { id -> refs.interests.firstOrNull { it.id == id }?.name ?: "interes eliminado" } + listOfNotNull(CrmForms.NoInterest.takeIf { condition.noInterest })).joinToString()}"
        CrmConditionKind.FieldEquals -> "${refs.fieldLabel(condition.field)} = ${condition.value}"
        CrmConditionKind.FieldNotEquals -> "${refs.fieldLabel(condition.field)} distinto de ${condition.value}"
        CrmConditionKind.FieldEmpty -> "${refs.fieldLabel(condition.field)} vacio"
        CrmConditionKind.FieldFilled -> "${refs.fieldLabel(condition.field)} lleno"
        CrmConditionKind.HasEmail -> "tiene correo"
        CrmConditionKind.HasPhone -> "tiene telefono"
    }
}

/** What a lead has that the actions need (see [CrmAutomationRequirements]). */
data class CrmLeadFacts(
    val email: String,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    // Dates (start, end) of each room chosen in Cloudbeds that is not reserved yet.
    val chosenRooms: List<Pair<LocalDate?, LocalDate?>> = emptyList(),
    // Statuses of the Cloudbeds reservations of the lead.
    val reservationStatuses: List<String> = emptyList(),
    val cloudbedsReady: Boolean = true,
)

/** Names of the stay dates for the messages ("Fecha de ingreso", the label of the mapped field). */
data class CrmStayLabels(val start: String = "fecha de ingreso", val end: String = "fecha de salida")

/** The data each action needs before it can run; a missing item is a sentence part in Spanish. */
object CrmAutomationRequirements {
    /** Reservation statuses that can still check in. */
    val CheckInStatuses = setOf("confirmed", "not_confirmed")

    fun missing(action: CrmAction, facts: CrmLeadFacts, labels: CrmStayLabels = CrmStayLabels(), reservedBefore: Boolean = false): List<String> = when (action.kind) {
        CrmActionKind.SendTemplate -> if (CrmContacts.isEmail(facts.email)) emptyList() else listOf("correo del lead")
        CrmActionKind.CloudbedsReserve -> buildList {
            if (!facts.cloudbedsReady) add("integracion de Cloudbeds activa")
            if (facts.startDate == null) add(labels.start)
            if (facts.endDate == null) add(labels.end)
            if (facts.chosenRooms.isEmpty()) {
                add("habitacion elegida en Cloudbeds")
            } else if (facts.startDate != null && facts.endDate != null && facts.chosenRooms.any { it.first != facts.startDate || it.second != facts.endDate }) {
                add("habitacion elegida para las fechas del lead (${facts.startDate} a ${facts.endDate})")
            }
            if (!CrmContacts.isEmail(facts.email)) add("correo del lead")
        }
        CrmActionKind.CloudbedsCheckIn -> buildList {
            if (!facts.cloudbedsReady) add("integracion de Cloudbeds activa")
            // A reserve earlier in the same chain creates the reservation the check-in needs.
            if (!reservedBefore && facts.reservationStatuses.none { it in CheckInStatuses }) add("reserva de Cloudbeds pendiente de check-in")
        }
        CrmActionKind.SetStage, CrmActionKind.SetField, CrmActionKind.Notify -> emptyList()
    }

    /**
     * Everything missing for the whole chain, without repeats, in the order the actions run. What an earlier action provides
     * counts: a reserve covers the check-in after it, and a set_field on the mapped check-in or check-out date ([stay]) gives that date.
     */
    fun missing(actions: List<CrmAction>, facts: CrmLeadFacts, labels: CrmStayLabels = CrmStayLabels(), stay: CrmStayMapping = CrmStayMapping.None): List<String> {
        var reserved = false
        var current = facts
        val all = mutableListOf<String>()
        actions.forEach { action ->
            all += missing(action, current, labels, reserved)
            if (action.kind == CrmActionKind.CloudbedsReserve) reserved = true
            if (action.kind == CrmActionKind.SetField && action.field.isNotEmpty()) {
                val date = action.value.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (action.field == stay.startKey) current = current.copy(startDate = date)
                if (action.field == stay.endKey) current = current.copy(endDate = date)
            }
        }
        return all.distinct()
    }

    /** "falta el correo del lead y la fecha de ingreso" style list. */
    fun sentence(missing: List<String>): String = when (missing.size) {
        0 -> ""
        1 -> missing.single()
        else -> missing.dropLast(1).joinToString(", ") + " y " + missing.last()
    }
}

/** Dates of the date rules: when they run, when they warn and which leads to look at. Pure. */
object CrmAutomationSchedule {
    /** A rule that missed its time by less than this still runs (turning a rule on does not fire for old dates). */
    val CatchUp: Duration = Duration.ofDays(1)

    private val whenFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun runAt(value: LocalDate, trigger: CrmTrigger, zone: ZoneId): ZonedDateTime =
        value.plusDays(trigger.offsetDays.toLong()).atTime(trigger.hour, 0).atZone(zone)

    /** The run time has come and the catch-up window has not closed. */
    fun isDue(value: LocalDate, trigger: CrmTrigger, zone: ZoneId, now: Instant): Boolean {
        val at = runAt(value, trigger, zone).toInstant()
        return !now.isBefore(at) && now.isBefore(at.plus(CatchUp))
    }

    fun warnAt(value: LocalDate, trigger: CrmTrigger, warnBeforeMinutes: Int, zone: ZoneId): ZonedDateTime =
        runAt(value, trigger, zone).minusMinutes(warnBeforeMinutes.toLong())

    /** The warning time has come and the run time has not: the data can still be completed. */
    fun isWarnDue(value: LocalDate, trigger: CrmTrigger, warnBeforeMinutes: Int, zone: ZoneId, now: Instant): Boolean {
        if (warnBeforeMinutes <= 0) return false
        val at = runAt(value, trigger, zone).toInstant()
        return !now.isBefore(warnAt(value, trigger, warnBeforeMinutes, zone).toInstant()) && now.isBefore(at)
    }

    /** Values of the date field (inclusive) worth reading in a pass: the rules that can be due or warn now. */
    fun window(trigger: CrmTrigger, warnBeforeMinutes: Int, today: LocalDate): Pair<LocalDate, LocalDate> {
        val warnDays = (warnBeforeMinutes + 1439) / 1440
        val offset = trigger.offsetDays.toLong()
        return today.minusDays(1 + offset) to today.plusDays(warnDays + 1L - offset)
    }

    /** Value of the date field of the lead: the follow-up date or a custom date field; null when empty or not a date. */
    fun dateValue(lead: CrmLead, field: String): LocalDate? =
        if (field == CrmAutomationRefs.FollowUpField) lead.followUpOn
        else (lead.customFields[field] as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

    /** SQL and parameters (in order) of the leads whose date is in [from]..[to] and whose date run is not recorded yet. */
    data class DueQuery(val sql: String, val params: List<Any>)

    /**
     * Leads (id, date) a date rule may run or warn for: not archived, date inside the window, no `date:<value>` run for [automationId] yet
     * (so the ones already done never take a place in the limit), earliest date first.
     */
    fun dueQuery(field: String, automationId: UUID, from: LocalDate, to: LocalDate, limit: Int): DueQuery {
        val followUp = field == CrmAutomationRefs.FollowUpField
        val column = if (followUp) "l.follow_up_on" else "crm_safe_date(l.custom_fields ->> CAST(? AS text))"
        val sql = "SELECT l.id, $column AS d FROM crm_leads l WHERE l.archived_at IS NULL AND $column BETWEEN ? AND ? " +
            "AND NOT EXISTS (SELECT 1 FROM crm_automation_runs r WHERE r.automation_id = ? AND r.lead_id = l.id " +
            "AND r.run_key = 'date:' || to_char($column, 'YYYY-MM-DD')) ORDER BY d, l.created_at LIMIT ?"
        val params = if (followUp) listOf<Any>(from, to, automationId, limit) else listOf<Any>(field, field, from, to, automationId, field, limit)
        return DueQuery(sql, params)
    }

    fun whenText(at: ZonedDateTime): String = "el ${whenFormat.format(at)} (${at.zone})"
}

/** run_key of crm_automation_runs: which executions are the same one. */
object CrmAutomationKeys {
    fun date(value: LocalDate) = "date:$value"
    fun warn(value: LocalDate) = "warn:$value"
    fun event(now: Instant, nonce: String) = "evt:${now.toEpochMilli()}-$nonce"
    fun button(press: UUID) = "btn:$press"

    /** message_received runs at most once per rule, lead and day (so a rule that answers cannot ping-pong with an autoresponder). */
    fun message(day: LocalDate) = "msg:$day"

    /** The missing-data notice of an event rule goes out once per rule, lead and day, not once per event. */
    fun missing(day: LocalDate) = "missing:$day"

    /** An execution runs only when its key was never recorded for that rule and lead. */
    fun isNew(recorded: Set<String>, key: String) = key !in recorded
}

/**
 * Life of a row of crm_automation_runs: events queue a "pending" row, the job claims it ("running") and finishes it; a
 * claim that never finishes is marked failed. Limits that keep automations from flooding a guest or looping.
 */
object CrmRunStates {
    const val Pending = "pending"
    const val Running = "running"
    val StaleAfter: Duration = Duration.ofMinutes(15)
    const val MaxTemplateMailsPerHour = 3
    const val MaxEventRunsPerHour = 5
    const val Interrupted = "Interrumpida"

    private val finals = setOf("done", "failed", "missing_data", "skipped")

    fun canMove(from: String, to: String): Boolean = when (from) {
        Pending -> to == Running || to == "skipped" || to == "failed"
        Running -> to in finals
        else -> false
    }

    fun isStale(startedAt: Instant?, now: Instant) = startedAt != null && now.isAfter(startedAt.plus(StaleAfter))

    /** Automations send at most [MaxTemplateMailsPerHour] template emails to one lead per hour. */
    fun templateLimitReached(sentLastHour: Int) = sentLastHour >= MaxTemplateMailsPerHour

    /** An event rule queues at most [MaxEventRunsPerHour] runs per lead and hour: it cuts loops between rules that change each other's trigger. */
    fun eventLoopLimitReached(runsLastHour: Int) = runsLastHour >= MaxEventRunsPerHour
}

/** One line per rule for the screens and agents. */
object CrmAutomationSummary {
    fun trigger(trigger: CrmTrigger, refs: CrmAutomationRefs): String = when (trigger.type) {
        CrmTriggerType.StageChanged -> "Cuando un lead pasa a ${refs.stageName(trigger.stageId)}"
        CrmTriggerType.FieldChanged -> "Cuando cambia ${refs.fieldLabel(trigger.field)}"
        CrmTriggerType.Date -> {
            val days = trigger.offsetDays
            val offset = when {
                days == 0 -> "El dia de"
                days < 0 -> "${-days} dia(s) antes de"
                else -> "$days dia(s) despues de"
            }
            "$offset ${refs.fieldLabel(trigger.field)}, a las ${trigger.hour.toString().padStart(2, '0')}:00"
        }
        else -> trigger.type.label
    }

    fun action(action: CrmAction, refs: CrmAutomationRefs): String = when (action.kind) {
        CrmActionKind.SendTemplate -> "Enviar ${refs.templates.firstOrNull { it.id == action.templateId }?.name ?: "plantilla eliminada"}"
        CrmActionKind.SetStage -> "Pasar a ${refs.stageName(action.stageId)}"
        CrmActionKind.SetField -> "Fijar ${refs.fieldLabel(action.field)} = ${action.value.ifEmpty { "(vacio)" }}"
        CrmActionKind.Notify -> "Avisar a ${action.who.label.lowercase()}"
        else -> action.kind.label
    }
}
