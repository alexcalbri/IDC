package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldCondition
import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The automation form as plain values: the rule is edited in a form with a fixed number of condition and action "slots"
 * (a slot with no kind is ignored). Reading and writing it needs no database, so it is tested without one.
 */
object CrmAutomationForm {
    const val None = "(ninguna)"
    const val WhenBefore = "Antes de la fecha"
    const val WhenOn = "El dia de la fecha"
    const val WhenAfter = "Despues de la fecha"

    /** Slots shown for a rule: its own plus one to add, at least [MinSlots]. The agents can save more than the form shows. */
    const val MinSlots = 3
    const val MaxSlots = 10

    fun slots(count: Int) = (count + 1).coerceIn(MinSlots, MaxSlots)

    private fun text(values: Map<String, String>, key: String) = values[key].orEmpty().trim()

    private fun names(values: Map<String, String>, key: String) = UiFieldValues.options(values[key].orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }

    private fun wholeNumber(raw: String, what: String, default: Int): Int =
        raw.ifEmpty { default.toString() }.toIntOrNull() ?: throw CrmValidationException("$what debe ser un numero entero.")

    /** Reads the form. Throws [CrmValidationException] (Spanish) for anything the person must fix; ids are checked afterwards by [CrmAutomationRules.validate]. */
    fun parse(values: Map<String, String>, refs: CrmAutomationRefs, id: UUID, position: Int): CrmAutomation {
        val type = CrmTriggerType.byLabel(text(values, "triggerType")) ?: throw CrmValidationException("Elige el disparador de la automatizacion.")
        val trigger = when (type) {
            CrmTriggerType.StageChanged -> CrmTrigger(type, stageId = refs.stage(text(values, "triggerStage")) ?: throw CrmValidationException("Elige la etapa del disparador."))
            CrmTriggerType.FieldChanged -> CrmTrigger(type, field = refs.field(text(values, "changedField")) ?: throw CrmValidationException("Elige el campo del disparador."))
            CrmTriggerType.Date -> {
                val days = wholeNumber(text(values, "dateDays"), "Los dias", 0)
                val offset = when (text(values, "dateWhen")) {
                    WhenBefore -> -days
                    WhenAfter -> days
                    else -> 0
                }
                CrmTrigger(
                    type,
                    field = refs.field(text(values, "dateField")) ?: throw CrmValidationException("Elige el campo de fecha del disparador."),
                    offsetDays = offset,
                    hour = wholeNumber(text(values, "dateHour"), "La hora", 9),
                )
            }
            else -> CrmTrigger(type)
        }
        val conditions = (1..MaxSlots).mapNotNull { slot -> condition(values, refs, slot) }
        val actions = (1..MaxSlots).mapNotNull { slot -> action(values, refs, slot) }
        val warnHours = text(values, "warnHours").ifEmpty { "0" }.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 } ?: throw CrmValidationException("El aviso anticipado son horas: un numero de 0 en adelante.")
        return CrmAutomation(
            id = id,
            name = text(values, "name"),
            enabled = values["enabled"] == "true",
            trigger = trigger,
            conditions = conditions,
            actions = actions,
            warnBeforeMinutes = if (type == CrmTriggerType.Date) (warnHours * 60).roundToInt() else 0,
            notifyUserIds = names(values, "notifyUsers").map { refs.user(it) ?: throw CrmValidationException("El usuario $it ya no tiene acceso al CRM.") },
            position = position,
        ).normalized()
    }

    private fun condition(values: Map<String, String>, refs: CrmAutomationRefs, slot: Int): CrmCondition? {
        val label = text(values, "cond${slot}_kind")
        if (label.isEmpty() || label == None) return null
        val kind = CrmConditionKind.byLabel(label) ?: throw CrmValidationException("Condicion $slot: elige una condicion de la lista.")
        return when (kind) {
            CrmConditionKind.StageIn -> CrmCondition(kind, stageIds = names(values, "cond${slot}_stages").map { refs.stage(it) ?: throw CrmValidationException("Condicion $slot: la etapa $it ya no existe.") })
            CrmConditionKind.InterestIn -> {
                val chosen = names(values, "cond${slot}_interests")
                CrmCondition(
                    kind,
                    interestIds = chosen.filter { it != CrmForms.NoInterest }.map { refs.interest(it) ?: throw CrmValidationException("Condicion $slot: el interes $it ya no existe.") },
                    noInterest = CrmForms.NoInterest in chosen,
                )
            }
            CrmConditionKind.FieldEquals, CrmConditionKind.FieldNotEquals, CrmConditionKind.FieldEmpty, CrmConditionKind.FieldFilled -> CrmCondition(
                kind,
                field = refs.field(text(values, "cond${slot}_field")) ?: throw CrmValidationException("Condicion $slot: elige el campo."),
                value = if (kind.usesValue) text(values, "cond${slot}_value") else "",
            )
            else -> CrmCondition(kind)
        }
    }

    private fun action(values: Map<String, String>, refs: CrmAutomationRefs, slot: Int): CrmAction? {
        val label = text(values, "act${slot}_kind")
        if (label.isEmpty() || label == None) return null
        val kind = CrmActionKind.byLabel(label) ?: throw CrmValidationException("Accion $slot: elige una accion de la lista.")
        return when (kind) {
            CrmActionKind.SendTemplate -> CrmAction(kind, templateId = refs.template(text(values, "act${slot}_template")) ?: throw CrmValidationException("Accion $slot: elige la plantilla."))
            CrmActionKind.SetStage -> CrmAction(kind, stageId = refs.stage(text(values, "act${slot}_stage")) ?: throw CrmValidationException("Accion $slot: elige la etapa."))
            CrmActionKind.SetField -> CrmAction(
                kind,
                field = refs.field(text(values, "act${slot}_field")) ?: throw CrmValidationException("Accion $slot: elige el campo."),
                value = text(values, "act${slot}_value"),
            )
            CrmActionKind.Notify -> CrmAction(
                kind,
                who = CrmNotifyWho.byLabel(text(values, "act${slot}_who")) ?: CrmNotifyWho.Assigned,
                userIds = names(values, "act${slot}_users").map { refs.user(it) ?: throw CrmValidationException("Accion $slot: el usuario $it ya no tiene acceso al CRM.") },
                title = text(values, "act${slot}_title"),
                text = text(values, "act${slot}_text"),
            )
            else -> CrmAction(kind)
        }
    }

    /** Form values of [automation] (null: a new rule). */
    fun values(automation: CrmAutomation?, refs: CrmAutomationRefs): Map<String, String> {
        val values = mutableMapOf(
            "name" to automation?.name.orEmpty(),
            "enabled" to (automation?.enabled ?: false).toString(),
            "triggerType" to (automation?.trigger?.type ?: CrmTriggerType.LeadCreated).label,
            "dateWhen" to WhenBefore,
            "dateDays" to "1",
            "dateHour" to "9",
            "warnHours" to "0",
        )
        if (automation == null) return values
        val trigger = automation.trigger
        when (trigger.type) {
            CrmTriggerType.StageChanged -> values["triggerStage"] = refs.stageName(trigger.stageId)
            CrmTriggerType.FieldChanged -> values["changedField"] = refs.fieldLabel(trigger.field)
            CrmTriggerType.Date -> {
                values["dateField"] = refs.fieldLabel(trigger.field)
                values["dateWhen"] = when {
                    trigger.offsetDays < 0 -> WhenBefore
                    trigger.offsetDays > 0 -> WhenAfter
                    else -> WhenOn
                }
                values["dateDays"] = kotlin.math.abs(trigger.offsetDays).toString()
                values["dateHour"] = trigger.hour.toString()
                values["warnHours"] = hours(automation.warnBeforeMinutes)
            }
            else -> Unit
        }
        values["notifyUsers"] = UiFieldValues.options(automation.notifyUserIds.mapNotNull { id -> refs.users.firstOrNull { it.id == id }?.name })
        automation.conditions.forEachIndexed { index, condition ->
            val slot = index + 1
            values["cond${slot}_kind"] = condition.kind.label
            when (condition.kind) {
                CrmConditionKind.StageIn -> values["cond${slot}_stages"] = UiFieldValues.options(condition.stageIds.map { refs.stageName(it) })
                CrmConditionKind.InterestIn -> values["cond${slot}_interests"] = UiFieldValues.options(
                    condition.interestIds.mapNotNull { id -> refs.interests.firstOrNull { it.id == id }?.name } + listOfNotNull(CrmForms.NoInterest.takeIf { condition.noInterest }),
                )
                else -> if (condition.kind.usesField) {
                    values["cond${slot}_field"] = refs.fieldLabel(condition.field)
                    values["cond${slot}_value"] = condition.value
                }
            }
        }
        automation.actions.forEachIndexed { index, action ->
            val slot = index + 1
            values["act${slot}_kind"] = action.kind.label
            when (action.kind) {
                CrmActionKind.SendTemplate -> values["act${slot}_template"] = refs.templates.firstOrNull { it.id == action.templateId }?.name.orEmpty()
                CrmActionKind.SetStage -> values["act${slot}_stage"] = refs.stageName(action.stageId)
                CrmActionKind.SetField -> {
                    values["act${slot}_field"] = refs.fieldLabel(action.field)
                    values["act${slot}_value"] = action.value
                }
                CrmActionKind.Notify -> {
                    values["act${slot}_who"] = action.who.label
                    values["act${slot}_users"] = UiFieldValues.options(action.userIds.mapNotNull { id -> refs.users.firstOrNull { it.id == id }?.name })
                    values["act${slot}_title"] = action.title
                    values["act${slot}_text"] = action.text
                }
                else -> Unit
            }
        }
        return values
    }

    /** Minutes as hours for the form: 2880 -> "48", 90 -> "1.5". */
    fun hours(minutes: Int): String = if (minutes % 60 == 0) (minutes / 60).toString() else (minutes / 60.0).toString()
}

/** The "Automatizaciones" tab of Configuracion: the list of rules, the rule form and the recent runs. */
object CrmAutomationScreens {
    private const val UiRoute = CrmScreens.UiRoute
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** [message] to show after saving; [warning] when the notice carries a warning (duplicate email, for example). */
    data class Saved(val message: String, val warning: Boolean)

    fun refsOf(context: CrmScreens.Context): CrmAutomationRefs = CrmAutomationRefs(
        stages = context.stages,
        interests = context.interests,
        templates = CrmMail.templates(context.tenant),
        users = context.users,
        fields = context.fields,
        cloudbedsReady = CrmModuleExtensions.hasAction("cloudbeds_reserve") && CrmModuleExtensions.cloudbedsActive(context.tenant),
    )

    private fun welcomeActive(context: CrmScreens.Context): Boolean = CrmModuleExtensions.cloudbedsActive(context.tenant) && CrmHost.cloudbedsSettings(context.tenant).welcomeEnabled

    private fun stayKeys(context: CrmScreens.Context): List<String> =
        CrmStay.effective(context.stay, context.fields).let { listOf(it.startKey, it.endKey).filter { key -> key.isNotEmpty() } }

    // ---- Saving ----

    fun save(context: CrmScreens.Context, id: UUID?, values: Map<String, String>): Saved {
        val tenant = context.tenant
        val refs = refsOf(context)
        val existing = CrmAutomationStore.all(tenant)
        val current = id?.let { wanted -> existing.firstOrNull { it.id == wanted } }
        if (id != null && current == null) throw CrmValidationException("Esa automatizacion ya no existe.")
        val automation = CrmAutomationForm.parse(values, refs, current?.id ?: UUID.randomUUID(), current?.position ?: CrmAutomationStore.nextPosition(tenant))
        CrmAutomationRules.validate(automation, refs, existing)
        CrmAutomationStore.save(tenant, automation, exists = current != null)
        return saved(context, automation, "Automatizacion ${automation.name} guardada.")
    }

    /** Turns a rule on or off. Turning on checks it again: what it points to may be gone. */
    fun setEnabled(context: CrmScreens.Context, automation: CrmAutomation, enabled: Boolean): Saved {
        val changed = automation.copy(enabled = enabled)
        if (enabled) CrmAutomationRules.validate(changed, refsOf(context), CrmAutomationStore.all(context.tenant))
        if (!CrmAutomationStore.setEnabled(context.tenant, automation.id, enabled)) throw CrmValidationException("Esa automatizacion ya no existe.")
        return saved(context, changed, "Automatizacion ${automation.name} ${if (enabled) "activada" else "apagada"}.")
    }

    private fun saved(context: CrmScreens.Context, automation: CrmAutomation, message: String): Saved {
        val duplicate = CrmAutomationRules.duplicatesCloudbedsWelcome(automation, stayKeys(context), welcomeActive(context))
        val warnings = listOfNotNull(
            "${CrmAutomationRules.DuplicateWelcomeWarning} Puedes apagar la bienvenida de Cloudbeds en esta pantalla.".takeIf { duplicate },
            CrmAutomationRules.messageReplyNotice(automation),
        )
        return if (warnings.isEmpty()) Saved(message, false) else Saved("$message ${warnings.joinToString(" ")}", true)
    }

    // ---- Screen ----

    fun tab(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val tenant = context.tenant
        val refs = refsOf(context)
        val automations = CrmAutomationStore.all(tenant)
        val editing = params["automation"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id -> automations.firstOrNull { it.id == id } }
        val runsOf = params["runs"]?.takeIf { it.isNotEmpty() }
        val runsAutomation = runsOf?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val recent = CrmAutomationStore.recent(tenant, runsAutomation, if (runsOf != null) 100 else 30)
        val byAutomation = CrmAutomationStore.recent(tenant, null, 200).groupBy { it.automationId }
        val zone = CrmZone.of(tenant)
        val duplicates = automations.any { CrmAutomationRules.duplicatesCloudbedsWelcome(it, stayKeys(context), welcomeActive(context)) }
        return listOfNotNull(
            UiText("Cada regla tiene un disparador, condiciones opcionales y acciones en orden. Quedan registradas en Ejecuciones recientes y en la actividad del lead.", UiTextStyle.Caption),
            if (duplicates) UiCard(
                listOf(
                    UiText(CrmAutomationRules.DuplicateWelcomeWarning, tone = UiTone.Warning),
                    UiButton(
                        "Apagar la bienvenida de Cloudbeds",
                        UiRequest(UiRoute + "/automations", params = mapOf("op" to "welcomeOff"), confirm = "Cloudbeds dejara de enviar su correo de bienvenida. Continuar?"),
                        style = "secondary",
                    ),
                ),
                title = "Correos duplicados",
            ) else null,
            listCard(automations, refs, byAutomation),
            formCard(context, refs, editing, submitted),
            runsCard(recent, runsOf != null, runsAutomation?.let { id -> automations.firstOrNull { it.id == id }?.name }, zone),
        )
    }

    private fun listCard(automations: List<CrmAutomation>, refs: CrmAutomationRefs, runs: Map<UUID, List<CrmRunRow>>): UiNode {
        val rows: List<UiNode> = automations.map { automation ->
            val last = runs[automation.id].orEmpty().take(3).joinToString(", ") { CrmAutomationStore.statusLabels[it.status] ?: it.status }
            val summary = CrmAutomationSummary.trigger(automation.trigger, refs) + ": " + automation.actions.joinToString(", ") { CrmAutomationSummary.action(it, refs) }
            UiRow(
                listOf(
                    UiText("${automation.name} - $summary"),
                    if (automation.enabled) UiStatus("Activa", UiTone.Success) else UiStatus("Apagada", UiTone.Neutral),
                    UiText(if (last.isEmpty()) "Sin ejecuciones" else "Ultimas: $last", UiTextStyle.Caption),
                    UiButton(
                        if (automation.enabled) "Apagar" else "Activar",
                        UiRequest(UiRoute + "/automations", params = mapOf("op" to "toggle", "automation" to automation.id.toString(), "enabled" to (!automation.enabled).toString())),
                        style = "secondary",
                    ),
                    UiButton("Editar", UiNavigate("automations", mapOf("automation" to automation.id.toString())), style = "secondary"),
                    UiButton("Ejecuciones", UiNavigate("automations", mapOf("runs" to automation.id.toString())), style = "secondary"),
                    UiButton(
                        "Eliminar",
                        UiRequest(UiRoute + "/automations", params = mapOf("op" to "delete", "automation" to automation.id.toString()), confirm = "Eliminar la automatizacion ${automation.name} y su registro de ejecuciones?"),
                        style = "danger",
                    ),
                ),
            )
        }
        return UiCard(rows.ifEmpty { listOf(UiText("Aun no hay automatizaciones.", tone = UiTone.Neutral)) }, title = "Automatizaciones")
    }

    private fun formCard(context: CrmScreens.Context, refs: CrmAutomationRefs, editing: CrmAutomation?, submitted: Map<String, String>?): UiNode {
        val values = CrmAutomationForm.values(editing, refs) + submitted.orEmpty()
        val conditionSlots = CrmAutomationForm.slots(editing?.conditions?.size ?: 0)
        val actionSlots = CrmAutomationForm.slots(editing?.actions?.size ?: 0)
        val stageNames = refs.stages.map { it.name }
        val userNames = refs.users.map { it.name }
        val valueFields = refs.fields.filter { it.type != CrmFieldType.Files && it.type != CrmFieldType.Button }
        val dateFields = listOf(CrmAutomationRefs.FollowUpLabel) + refs.fields.filter { it.type == CrmFieldType.Date }.map { it.label }
        val settableFields = listOf(CrmAutomationRefs.FollowUpLabel) + valueFields.map { it.label }
        val cloudbeds = refs.cloudbedsReady
        val actionKinds = CrmActionKind.entries.filter { cloudbeds || !it.isCloudbeds || editing?.actions?.any { action -> action.kind == it } == true }

        fun on(key: String, vararg labels: String) = UiFieldCondition(key, labels.toList())
        val none = CrmAutomationForm.None
        val trigger = listOf(
            UiField("name", "Nombre", required = true),
            UiField("enabled", "Activa", type = "boolean", help = "Una regla apagada no se ejecuta. Las de fecha solo corren para las fechas de hoy en adelante."),
            UiField("triggerType", "Disparador", type = "dropdown", required = true, options = CrmTriggerType.entries.map { it.label }, help = "Manual: solo se ejecuta desde un boton del lead (campo de tipo Boton)."),
            UiField("triggerStage", "Etapa", type = "dropdown", options = stageNames, visibleWhen = on("triggerType", CrmTriggerType.StageChanged.label)),
            UiField("changedField", "Campo que cambia", type = "dropdown", options = listOf(CrmAutomationRefs.FollowUpLabel) + valueFields.map { it.label }, visibleWhen = on("triggerType", CrmTriggerType.FieldChanged.label)),
            UiField("dateField", "Fecha", type = "dropdown", options = dateFields, visibleWhen = on("triggerType", CrmTriggerType.Date.label), help = "Un campo de fecha de la ficha (ingreso, salida...) o la fecha de seguimiento."),
            UiField("dateWhen", "Cuando", type = "dropdown", options = listOf(CrmAutomationForm.WhenBefore, CrmAutomationForm.WhenOn, CrmAutomationForm.WhenAfter), visibleWhen = on("triggerType", CrmTriggerType.Date.label)),
            UiField("dateDays", "Dias (0 a 60)", type = "number", visibleWhen = on("dateWhen", CrmAutomationForm.WhenBefore, CrmAutomationForm.WhenAfter)),
            UiField("dateHour", "Hora del dia (0 a 23, zona horaria de la empresa)", type = "number", visibleWhen = on("triggerType", CrmTriggerType.Date.label)),
        )
        val notices = listOf(
            UiField(
                "warnHours", "Avisar con anticipacion (horas; 0 no avisa)", type = "number", section = "Datos que faltan", visibleWhen = on("triggerType", CrmTriggerType.Date.label),
                help = "Reglas de fecha: con esta anticipacion se revisa si al lead le falta algo para la ejecucion (48 = 2 dias antes) y se avisa que falta.",
            ),
            UiField(
                "notifyUsers", "Avisar a", type = "dropdown", multiple = true, options = userNames, section = "Datos que faltan",
                help = "Quienes reciben el aviso de datos que faltan. Vacio: los asignados del lead.",
            ),
        )
        val conditions = (1..conditionSlots).flatMap { slot ->
            val section = "Condicion $slot"
            val kind = "cond${slot}_kind"
            listOf(
                UiField(kind, "Condicion", type = "dropdown", options = listOf(none) + CrmConditionKind.entries.map { it.label }, section = section, help = if (slot == 1) "Todas las condiciones deben cumplirse. Sin condiciones, siempre corre." else ""),
                UiField("cond${slot}_stages", "Etapas", type = "dropdown", multiple = true, options = stageNames, section = section, visibleWhen = on(kind, CrmConditionKind.StageIn.label)),
                UiField(
                    "cond${slot}_interests", "Intereses", type = "dropdown", multiple = true, options = listOf(CrmForms.NoInterest) + refs.interests.map { it.name }, section = section,
                    visibleWhen = on(kind, CrmConditionKind.InterestIn.label),
                ),
                UiField(
                    "cond${slot}_field", "Campo", type = "dropdown", options = valueFields.map { it.label }, section = section,
                    visibleWhen = on(kind, CrmConditionKind.FieldEquals.label, CrmConditionKind.FieldNotEquals.label, CrmConditionKind.FieldEmpty.label, CrmConditionKind.FieldFilled.label),
                ),
                UiField(
                    "cond${slot}_value", "Valor", section = section, visibleWhen = on(kind, CrmConditionKind.FieldEquals.label, CrmConditionKind.FieldNotEquals.label),
                    help = "En campos Si / No escribe Si o No.",
                ),
            )
        }
        val actions = (1..actionSlots).flatMap { slot ->
            val section = "Accion $slot"
            val kind = "act${slot}_kind"
            listOf(
                UiField(kind, "Accion", type = "dropdown", options = listOf(none) + actionKinds.map { it.label }, section = section, help = if (slot == 1) "Se ejecutan en orden y se detienen en la primera que falle. Cloudbeds aparece con la integracion activa." else ""),
                UiField("act${slot}_template", "Plantilla", type = "dropdown", options = refs.templates.map { it.name }, section = section, visibleWhen = on(kind, CrmActionKind.SendTemplate.label), help = "Se envia al correo del lead (traducida si la plantilla lo pide)."),
                UiField("act${slot}_stage", "Etapa", type = "dropdown", options = stageNames, section = section, visibleWhen = on(kind, CrmActionKind.SetStage.label)),
                UiField("act${slot}_field", "Campo", type = "dropdown", options = settableFields, section = section, visibleWhen = on(kind, CrmActionKind.SetField.label)),
                UiField(
                    "act${slot}_value", "Valor", section = section, visibleWhen = on(kind, CrmActionKind.SetField.label),
                    help = "Vacio borra el campo. Fechas AAAA-MM-DD; Si / No como true o false; listas con una opcion exacta.",
                ),
                UiField("act${slot}_who", "Avisar a", type = "dropdown", options = CrmNotifyWho.entries.map { it.label }, section = section, visibleWhen = on(kind, CrmActionKind.Notify.label)),
                UiField("act${slot}_users", "Usuarios", type = "dropdown", multiple = true, options = userNames, section = section, visibleWhen = on("act${slot}_who", CrmNotifyWho.Users.label)),
                UiField("act${slot}_title", "Titulo del aviso", section = section, visibleWhen = on(kind, CrmActionKind.Notify.label)),
                UiField("act${slot}_text", "Texto del aviso", type = "textarea", section = section, visibleWhen = on(kind, CrmActionKind.Notify.label), help = "Llega a la app y al correo de cada usuario."),
            )
        }
        val open = buildList {
            if (values["warnHours"].orEmpty() != "0" || values["notifyUsers"].orEmpty().isNotEmpty()) add("Datos que faltan")
            (1..conditionSlots).forEach { slot -> if (values["cond${slot}_kind"].orEmpty().let { it.isNotEmpty() && it != none }) add("Condicion $slot") }
            (1..actionSlots).forEach { slot -> if (slot == 1 || values["act${slot}_kind"].orEmpty().let { it.isNotEmpty() && it != none }) add("Accion $slot") }
        }
        val saveParams = mapOf("op" to "save") + listOfNotNull(editing?.let { "automation" to it.id.toString() })
        return UiCard(
            listOf(
                UiForm(
                    "automation", trigger + notices + conditions + actions, values,
                    submit = UiButton(if (editing == null) "Crear automatizacion" else "Guardar automatizacion", UiRequest(UiRoute + "/automations", params = saveParams, formId = "automation")),
                    minFieldWidth = 220,
                    collapsibleSections = true,
                    openSections = open,
                ),
            ),
            title = if (editing == null) "Nueva automatizacion" else "Editar ${editing.name}",
            actions = listOfNotNull(editing?.let { UiButton("Nueva", UiNavigate("automations"), style = "secondary") }),
        )
    }

    private fun runsCard(runs: List<CrmRunRow>, asked: Boolean, automationName: String?, zone: java.time.ZoneId): UiNode {
        val items = runs.map { run ->
            UiListItem(
                title = "${run.automationName} - ${run.leadName}",
                subtitle = run.detail,
                trailing = timeFormat.format(run.createdAt.atZone(zone)),
                badges = listOf(UiBadge(CrmAutomationStore.statusLabels[run.status] ?: run.status, tone(run.status))),
                action = UiNavigate("lead", mapOf("id" to run.leadId.toString())),
            )
        }
        return UiCard(
            listOf<UiNode>(UiList(items, emptyText = "Aun no hay ejecuciones.")) +
                listOfNotNull(UiButton("Ver todas", UiNavigate("automations", mapOf("runs" to "all")), style = "secondary").takeIf { automationName != null }),
            title = if (automationName != null) "Ejecuciones recientes: $automationName" else "Ejecuciones recientes",
            collapsible = true,
            expanded = asked,
        )
    }

    private fun tone(status: String) = when (status) {
        CrmRunOutcome.Done -> UiTone.Success
        CrmRunOutcome.Failed -> UiTone.Danger
        CrmRunOutcome.MissingData, CrmRunOutcome.Warned -> UiTone.Warning
        else -> UiTone.Neutral
    }
}
