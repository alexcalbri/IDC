package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Resultado de comparar los archivos de un formulario con los que ya están enlazados a la orden. */
data class AttachmentChanges(val added: List<UiFileRef>, val removed: List<UUID>)

/** Lo que las pantallas necesitan para traducir las etiquetas elegidas por el usuario a ids. */
class Lookups(val users: List<MantenimientoUser>, val properties: List<Property>, val assets: AssetOptions)

/**
 * Los activos como opciones de un menú: cada uno con una etiqueta única (dos activos con el mismo nombre se distinguen por su
 * propiedad y, si aún coinciden, por un número).
 */
class AssetOptions(assets: List<Asset>) {
    private val byId = LinkedHashMap<UUID, String>()
    private val byLabel = HashMap<String, UUID>()

    init {
        val unique = assets.distinctBy { it.id }
        val repeated = unique.groupingBy { it.name.trim().lowercase() }.eachCount().filterValues { it > 1 }.keys
        unique.forEach { asset ->
            val base = asset.name.trim() + if (asset.name.trim().lowercase() in repeated) " · ${asset.propertyName ?: MantenimientoActions.NoProperty}" else ""
            var candidate = base
            var number = 2
            while (candidate.lowercase() in byLabel) candidate = "$base #${number++}"
            byId[asset.id] = candidate
            byLabel[candidate.lowercase()] = asset.id
        }
    }

    /** Las etiquetas en el orden recibido. */
    val labels: List<String> get() = byId.values.toList()

    fun label(id: UUID?): String? = id?.let { byId[it] }

    /** El activo de esa etiqueta; vacío o "Sin activo" es ninguno y una etiqueta desconocida es un error. */
    fun id(label: String?): UUID? {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(MantenimientoActions.NoAsset, ignoreCase = true)) return null
        return byLabel[clean.lowercase()] ?: throw MantenimientoValidationException("Activo no válido: $clean. Elige uno de la lista.")
    }
}

/**
 * Lectura pura de lo que envían las pantallas (etiquetas elegidas, fechas, valores JSON) hacia los tipos del módulo. Nada aquí toca la
 * base de datos: el repositorio aplica las reglas después.
 */
object MantenimientoActions {
    const val AllOption = "Todos"
    const val NoProperty = "Sin propiedad"
    const val NoAsset = "Sin activo"
    const val NoAssignee = "Sin responsable"
    const val RetiredUser = "Usuario retirado"
    const val InvalidForm = "Formulario demasiado grande o no válido."
    private const val MaxBodyChars = 1_000_000

    /** Valores de un formulario: textos tal cual y un arreglo JSON (menú múltiple) como su texto codificado. */
    fun bodyValues(body: String): Map<String, String> {
        if (body.length > MaxBodyChars) return emptyMap()
        return runCatching {
            UiJson.parseToJsonElement(body).jsonObject.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.contentOrNull.orEmpty()
                    is JsonArray -> UiFieldValues.options(value.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() })
                    else -> ""
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Como [bodyValues], pero un cuerpo demasiado grande o que no es un objeto JSON es un error para el usuario. */
    fun formValues(body: String): Map<String, String> {
        if (body.length > MaxBodyChars) throw MantenimientoValidationException(InvalidForm)
        val parsed = runCatching { UiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw MantenimientoValidationException(InvalidForm)
        return bodyValues(parsed.toString())
    }

    fun uuid(text: String?): UUID? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Una fecha ISO (aaaa-mm-dd); vacío es "sin fecha". */
    fun date(text: String?): LocalDate? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return try {
            LocalDate.parse(clean)
        } catch (_: DateTimeParseException) {
            throw MantenimientoValidationException("La fecha debe tener el formato AAAA-MM-DD.")
        }
    }

    /** Un monto con punto o coma decimal; vacío es "sin monto". */
    fun amount(text: String?, label: String): BigDecimal? {
        val clean = text?.trim().orEmpty().replace(',', '.')
        if (clean.isEmpty()) return null
        return try {
            BigDecimal(clean)
        } catch (_: NumberFormatException) {
            throw MantenimientoValidationException("$label no es un número válido.")
        }
    }

    fun int(text: String?, label: String): Int? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return clean.toIntOrNull() ?: throw MantenimientoValidationException("$label debe ser un número entero.")
    }

    /** La moneda en mayúsculas; vacío es [default]. */
    fun currency(text: String?, default: String = "USD"): String = text?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: default

    fun priority(text: String?): OrderPriority {
        if (text.isNullOrBlank()) return OrderPriority.Medium
        return OrderPriority.fromLabel(text) ?: throw MantenimientoValidationException("Prioridad no válida: $text.")
    }

    fun status(text: String?): OrderStatus =
        OrderStatus.fromId(text) ?: OrderStatus.fromLabel(text) ?: throw MantenimientoValidationException("Estado no válido: ${text.orEmpty()}.")

    /** La persona de esa etiqueta; vacío o "Sin responsable" es nadie. [current] se conserva si la etiqueta es la de un usuario retirado. */
    fun userId(label: String?, users: List<MantenimientoUser>, current: UUID? = null): UUID? {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(NoAssignee, ignoreCase = true)) return null
        users.firstOrNull { it.label.equals(clean, ignoreCase = true) }?.let { return it.id }
        if (current != null && clean.equals(RetiredUser, ignoreCase = true)) return current
        throw MantenimientoValidationException("Persona no válida: $clean. Elige solo personas de la lista.")
    }

    /** La propiedad de esa etiqueta; [current] (la de la orden, aunque esté archivada) también vale. */
    fun propertyId(label: String?, properties: List<Property>, current: Pair<UUID?, String?> = null to null): UUID? {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(NoProperty, ignoreCase = true)) return null
        properties.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it.id }
        if (current.first != null && current.second?.equals(clean, ignoreCase = true) == true) return current.first
        throw MantenimientoValidationException("Propiedad no válida: $clean. Elige una de la lista.")
    }

    /** La propiedad que filtra una pantalla; "Todos" y las etiquetas desconocidas son sin filtro. */
    fun propertyFilter(label: String?, properties: List<Property>): Property? {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(AllOption, ignoreCase = true)) return null
        return properties.firstOrNull { it.name.equals(clean, ignoreCase = true) }
    }

    /**
     * El formulario de una orden. Un campo que el formulario no mostró se conserva de [existing]; responsable, fecha límite y "cotizado por"
     * solo los cambia quien gestiona ([canManage]).
     */
    fun orderInput(kind: OrderKind, values: Map<String, String>, lookups: Lookups, canManage: Boolean, existing: Order? = null): OrderInput {
        fun text(key: String, current: String) = values[key] ?: current
        return OrderInput(
            kind = kind,
            title = text("title", existing?.title.orEmpty()).trim(),
            description = text("description", existing?.description.orEmpty()),
            propertyId = if ("property" in values) {
                propertyId(values["property"], lookups.properties, existing?.propertyId to existing?.propertyName)
            } else {
                existing?.propertyId
            },
            assetId = if ("asset" in values) lookups.assets.id(values["asset"]) else existing?.assetId,
            priority = if ("priority" in values) priority(values["priority"]) else existing?.priority ?: OrderPriority.Medium,
            dueDate = if (canManage && "due" in values) date(values["due"]) else existing?.dueDate,
            assigneeId = if (canManage && "assignee" in values) userId(values["assignee"], lookups.users, existing?.assigneeId) else existing?.assigneeId,
            department = text("department", existing?.department.orEmpty()),
            quoteAmount = if ("quote" in values) amount(values["quote"], "La cotización") else existing?.quoteAmount,
            quoteCurrency = if ("quote_currency" in values) currency(values["quote_currency"]) else existing?.quoteCurrency ?: "USD",
            quotedBy = if (canManage) text("quoted_by", existing?.quotedBy.orEmpty()) else existing?.quotedBy.orEmpty(),
            costAmount = if ("cost" in values) amount(values["cost"], "El costo") else existing?.costAmount,
            costCurrency = if ("cost_currency" in values) currency(values["cost_currency"]) else existing?.costCurrency ?: "USD",
            resolution = text("resolution", existing?.resolution.orEmpty()),
        )
    }

    /** Costo real y resolución que envía quien trabaja la orden: lo que el formulario no mostró se conserva. */
    data class WorkValues(val costAmount: BigDecimal?, val costCurrency: String, val resolution: String)

    fun workValues(values: Map<String, String>, existing: Order): WorkValues = WorkValues(
        costAmount = if ("cost" in values) amount(values["cost"], "El costo") else existing.costAmount,
        costCurrency = if ("cost_currency" in values) currency(values["cost_currency"]) else existing.costCurrency,
        resolution = values["resolution"] ?: existing.resolution,
    )

    fun planInput(values: Map<String, String>, lookups: Lookups, checklist: List<String>, existing: Plan? = null): PlanInput {
        val frequency = values["frequency"]?.takeIf { it.isNotBlank() }?.let {
            PlanFrequency.fromLabel(it) ?: throw MantenimientoValidationException("Frecuencia no válida: $it.")
        } ?: existing?.frequency ?: PlanFrequency.Monthly
        val mode = values["schedule_mode"]?.takeIf { it.isNotBlank() }?.let {
            ScheduleMode.fromLabel(it) ?: throw MantenimientoValidationException("Modo de calendario no válido: $it.")
        } ?: existing?.scheduleMode ?: ScheduleMode.Fixed
        return PlanInput(
            title = values["title"].orEmpty().trim(),
            description = values["description"].orEmpty(),
            propertyId = propertyId(values["property"], lookups.properties, existing?.propertyId to existing?.propertyName),
            assetId = lookups.assets.id(values["asset"]),
            frequency = frequency,
            intervalDays = if (frequency == PlanFrequency.EveryNDays) int(values["interval"], "Los días de la frecuencia") else null,
            scheduleMode = mode,
            nextDue = date(values["next_due"]),
            leadDays = int(values["lead_days"], "Los días de anticipación") ?: 7,
            defaultAssignee = userId(values["assignee"], lookups.users, existing?.defaultAssignee),
            priority = priority(values["priority"]),
            checklist = checklist,
        )
    }

    /** El plan tal como está, con otra lista de comprobación (para añadir, quitar o mover elementos). */
    fun planInputOf(plan: Plan, checklist: List<String>): PlanInput = PlanInput(
        title = plan.title,
        description = plan.description,
        propertyId = plan.propertyId,
        assetId = plan.assetId,
        frequency = plan.frequency,
        intervalDays = plan.intervalDays,
        scheduleMode = plan.scheduleMode,
        nextDue = plan.nextDue,
        leadDays = plan.leadDays,
        defaultAssignee = plan.defaultAssignee,
        priority = plan.priority,
        checklist = checklist,
    )

    /** Mueve el elemento [index] un lugar arriba o abajo; sin cambios si no se puede. */
    fun moved(items: List<String>, index: Int, up: Boolean): List<String> {
        val target = if (up) index - 1 else index + 1
        if (index !in items.indices || target !in items.indices) return items
        return items.toMutableList().also { it[index] = items[target]; it[target] = items[index] }
    }

    fun assetInput(values: Map<String, String>, properties: List<Property>, existing: Asset? = null) = AssetInput(
        propertyId = propertyId(values["property"], properties, existing?.propertyId to existing?.propertyName),
        name = values["name"].orEmpty().trim(),
        category = values["category"].orEmpty(),
        location = values["location"].orEmpty(),
        notes = values["notes"].orEmpty(),
    )

    /** Filtros de la pantalla de órdenes (etiquetas del formulario de filtros); "Todos" y lo desconocido no filtran. */
    fun orderFilter(params: Map<String, String>, users: List<MantenimientoUser>, properties: List<Property>, board: Boolean, limit: Int): OrderFilter {
        fun chosen(key: String) = params[key]?.trim()?.takeIf { it.isNotEmpty() && !it.equals(AllOption, ignoreCase = true) }
        return OrderFilter(
            status = if (board) null else chosen("status")?.let { OrderStatus.fromLabel(it) },
            kind = chosen("kind")?.let { OrderKind.fromLabel(it) },
            propertyId = propertyFilter(params["property"], properties)?.id,
            assigneeId = chosen("assignee")?.let { label -> users.firstOrNull { it.label.equals(label, ignoreCase = true) }?.id },
            query = params["q"]?.trim()?.takeIf { it.isNotEmpty() },
            limit = limit,
        )
    }

    /** Los filtros de [params] que se conservan al cambiar de vista o después de una acción. */
    fun filterParams(params: Map<String, String>): Map<String, String> =
        listOf("q", "status", "kind", "property", "assignee").mapNotNull { key ->
            params[key]?.trim()?.takeIf { it.isNotEmpty() && !it.equals(AllOption, ignoreCase = true) }?.let { key to it }
        }.toMap()

    /** Archivos del formulario frente a los enlazados hoy: los nuevos se enlazan y los que faltan se quitan. Sin la clave "files" no cambia nada. */
    fun fileChanges(values: Map<String, String>, current: List<Attachment>): AttachmentChanges {
        val raw = values["files"] ?: return AttachmentChanges(emptyList(), emptyList())
        val sent = UiFieldValues.files(raw)
        val currentIds = current.map { it.id.toString() }.toSet()
        val sentIds = sent.map { it.id }.toSet()
        return AttachmentChanges(
            added = sent.filter { it.id !in currentIds },
            removed = current.filter { it.id.toString() !in sentIds }.map { it.id },
        )
    }

    /** Puede trabajar la orden ahora: quien gestiona siempre; quien ejecuta solo mientras la orden está en trabajo. */
    fun canWorkNow(scope: MantenimientoScope, order: Order): Boolean = scope.canWork(order) && (order.status.isWorking || scope.canManage)

    /** Quien escribe notas: quien trabaja la orden y quien la reportó. */
    fun canNote(scope: MantenimientoScope, order: Order): Boolean = scope.canWork(order) || (isRequester(scope, order) && order.status.isActive)

    /** Quien adjunta archivos: quien trabaja la orden y quien la reportó. */
    fun canAttach(scope: MantenimientoScope, order: Order): Boolean = canNote(scope, order)

    fun isRequester(scope: MantenimientoScope, order: Order): Boolean = scope.userId != null && order.requestedBy == scope.userId

    /** La lista de comprobación se trabaja mientras la orden sigue abierta (quien gestiona siempre). */
    fun canWorkChecklist(scope: MantenimientoScope, order: Order): Boolean = scope.canWork(order) && (order.status.isActive || scope.canManage)

    /**
     * Permiso mínimo que la ruta pide antes de abrir la conexión: gestionar para ajustes, planes y activos; importar para la importación;
     * el resto pide ver mantenimiento (el servicio aplica después las reglas finas por orden).
     */
    fun requiredPermission(screen: String, op: String?): String = when (screen) {
        "admin", "plan" -> if (screen == "admin" || op != null) MantenimientoModule.Manage else MantenimientoModule.View
        "asset" -> if (op != null) MantenimientoModule.Manage else MantenimientoModule.View
        "importar" -> MantenimientoModule.Import
        else -> MantenimientoModule.View
    }
}
