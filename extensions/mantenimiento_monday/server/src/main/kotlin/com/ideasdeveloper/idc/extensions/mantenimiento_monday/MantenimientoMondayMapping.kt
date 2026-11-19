package com.ideasdeveloper.idc.extensions.mantenimiento_monday

import com.ideasdeveloper.idc.modules.mantenimiento.ImportSummary
import com.ideasdeveloper.idc.modules.mantenimiento.ImportedCheck
import com.ideasdeveloper.idc.modules.mantenimiento.ImportedNote
import com.ideasdeveloper.idc.modules.mantenimiento.ImportedOrder
import com.ideasdeveloper.idc.modules.mantenimiento.ImportedPlan
import com.ideasdeveloper.idc.modules.mantenimiento.OrderKind
import com.ideasdeveloper.idc.modules.mantenimiento.OrderPriority
import com.ideasdeveloper.idc.modules.mantenimiento.OrderStatus
import com.ideasdeveloper.idc.modules.mantenimiento.PlanFrequency
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Columna de un tablero de monday (su id estable, su titulo y su tipo: status, people, timeline...). */
data class MondayColumn(val id: String, val title: String, val type: String)

/** Valor de una columna en un elemento: [text] es lo que muestra monday y [value] su JSON crudo (fechas, personas, archivos). */
data class MondayCell(val columnId: String, val title: String, val type: String, val text: String, val value: String = "")

data class MondaySubitem(val id: String, val name: String, val cells: List<MondayCell>)

data class MondayAsset(val id: String, val name: String)

/** Una actualizacion (comentario) de monday; [replies] son sus respuestas (no tienen respuestas propias). */
data class MondayUpdate(
    val id: String,
    val body: String,
    val createdAt: String,
    val authorName: String,
    val authorEmail: String = "",
    val assets: List<MondayAsset> = emptyList(),
    val replies: List<MondayUpdate> = emptyList(),
)

/** Elemento de un tablero ya leido de la API, sin JSON de red: es lo unico que ve la conversion. */
data class MondayItem(
    val id: String,
    val name: String,
    val group: String,
    val cells: List<MondayCell>,
    val subitems: List<MondaySubitem> = emptyList(),
    val updates: List<MondayUpdate> = emptyList(),
)

/** Persona de monday resuelta con users(ids): el correo puede faltar (invitados). */
data class MondayPerson(val id: String, val name: String, val email: String)

/** A que dato va cada columna: objetivo -> id de columna de monday. */
@Serializable
data class MondayMapping(val columns: Map<String, String> = emptyMap())

/** Destino de la importacion: planes preventivos u ordenes y proyectos. */
object MondayDestination {
    const val Plans = "plans"
    const val Orders = "orders"
    const val KindCorrective = "corrective"
    const val KindProject = "project"

    fun isValid(target: String?) = target == Plans || target == Orders

    fun isValidKind(kind: String?) = kind == KindCorrective || kind == KindProject

    fun label(target: String) = if (target == Plans) "Plantillas preventivas" else "Órdenes / proyectos"

    fun kindOf(code: String?): OrderKind = if (code == KindProject) OrderKind.Project else OrderKind.Corrective
}

/** Datos del plan o la orden que se pueden mapear a una columna del tablero. */
object MondayTarget {
    const val Status = "status"
    const val Priority = "priority"
    const val Due = "due"
    const val Assignees = "assignees"
    const val Description = "description"
    const val Property = "property"
    const val Asset = "asset"
    const val Department = "department"
    const val Quote = "quote"
    const val QuotedBy = "quotedby"

    /** [plans] y [orders] dicen en que destinos se usa el dato. */
    class Definition(val target: String, val label: String, val plans: Boolean = true, val orders: Boolean = true)

    // En el orden en que se muestran en el formulario de mapeo.
    val all = listOf(
        Definition(Status, "Estado (en planes solo se anota en la descripción)"),
        Definition(Priority, "Prioridad"),
        Definition(Due, "Fecha límite (en planes, próxima fecha)"),
        Definition(Assignees, "Responsable"),
        Definition(Description, "Descripción / notas"),
        Definition(Property, "Propiedad"),
        Definition(Asset, "Activo / equipo"),
        Definition(Department, "Departamento", plans = false),
        Definition(Quote, "Cotización (USD)", plans = false),
        Definition(QuotedBy, "Cotizado por", plans = false),
    )

    fun forDestination(target: String): List<Definition> = all.filter { if (target == MondayDestination.Plans) it.plans else it.orders }
}

/** Archivo de monday (de una columna de archivos o de una actualizacion): solo se guarda el id, el enlace caduca. */
data class MondayFile(val asset: String, val name: String, val column: String = "", val update: String = "")

/** Un elemento ya convertido: un plan o una orden, sus archivos por descargar al aplicarla y los problemas que se anotaron. */
data class MondayConverted(val plan: ImportedPlan?, val order: ImportedOrder?, val files: List<MondayFile>, val problems: List<String>)

/**
 * Funciones puras del mapeo: sugerir a que columna va cada dato por su titulo y tipo (espanol e ingles) y convertir un
 * elemento de monday en un plan o una orden a importar. Nada de red ni de base de datos.
 */
object MantenimientoMondayMapping {
    /** Clave de importacion del elemento (la que guarda el modulo en import_key). */
    const val ItemPrefix = "monday:"
    const val SubPrefix = "monday:sub:"
    const val UpdatePrefix = "monday:upd:"
    const val AssetPrefix = "monday:asset:"
    const val MaxPlanDescription = 4_800
    const val MaxOrderDescription = 19_000
    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
    private val assetId = Regex("^[0-9]{1,20}$")
    private val everyDays = Regex("""(?:cada|every) (\d{1,4}) (?:dias|dia|days|day)""")
    private val json = Json { ignoreUnknownKeys = true }

    // Columnas que nunca son un dato del plan o la orden ni van a la descripcion.
    private val ignoredTypes = setOf("name", "subitems", "board_relation", "mirror", "file", "creation_log", "last_updated", "item_id", "button", "dependency", "doc", "auto_number", "formula")

    private class Rule(val target: String, val keywords: List<String>, val allowed: Set<String> = emptySet(), val fallbackType: String? = null)

    // Orden de prioridad: lo mas especifico primero ("Cotizado por" antes que el responsable; propiedad y departamento
    // antes de que la columna de estado se lleve cualquier columna de tipo status).
    private val rules = listOf(
        Rule(MondayTarget.QuotedBy, listOf("cotizado por", "quoted by", "cotizador"), setOf("people", "text", "dropdown")),
        Rule(MondayTarget.Quote, listOf("cotizacion", "quote", "quotation", "presupuesto", "budget", "monto", "costo", "cost", "amount", "importe"), setOf("numbers", "text")),
        Rule(MondayTarget.Department, listOf("departamento", "department", "dept", "area"), setOf("text", "dropdown", "status")),
        Rule(MondayTarget.Property, listOf("propiedad", "property", "hotel", "sede", "ubicacion", "location", "edificio", "building"), setOf("text", "dropdown", "status", "location")),
        Rule(MondayTarget.Asset, listOf("activo", "asset", "equipo", "equipment", "maquina", "machine"), setOf("text", "dropdown")),
        Rule(MondayTarget.Assignees, listOf("asignado a", "asignado", "asignada", "assigned to", "assignee", "assigned", "owner", "person", "persona", "responsable", "responsables"), setOf("people"), "people"),
        Rule(MondayTarget.Priority, listOf("priority", "prioridad", "urgency", "urgencia", "urgente", "urgent"), setOf("status", "dropdown")),
        Rule(MondayTarget.Status, listOf("estado de aprobacion", "approval status", "status", "estado", "state", "etapa", "stage"), setOf("status"), "status"),
        Rule(MondayTarget.Due, listOf("due date", "due", "deadline", "fecha limite", "vencimiento", "vence", "plazo", "fecha", "date", "timeline", "cronograma"), setOf("date", "timeline"), "date"),
        Rule(MondayTarget.Description, listOf("descripcion", "description", "detail", "details", "detalle", "detalles", "notas", "notes", "nota", "note"), setOf("long_text", "text"), "long_text"),
    )

    /** Minusculas, sin tildes ni signos: "Revisión" -> "revision". */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun matches(foldedTitle: String, keyword: String): Boolean {
        if (keyword.contains(' ')) return " $foldedTitle ".contains(" $keyword ")
        return foldedTitle.split(' ').any { it == keyword || (keyword.length >= 5 && it.startsWith(keyword)) }
    }

    /** Mapeo sugerido por titulo y tipo de cada columna; una columna sirve para un solo dato. */
    fun suggest(columns: List<MondayColumn>): MondayMapping {
        val usable = columns.filter { it.type !in ignoredTypes }
        val used = mutableSetOf<String>()
        val result = linkedMapOf<String, String>()
        for (rule in rules) {
            fun free(predicate: (MondayColumn) -> Boolean) = usable.firstOrNull { it.id !in used && predicate(it) }
            val found = rule.keywords.firstNotNullOfOrNull { keyword ->
                free { (rule.allowed.isEmpty() || it.type in rule.allowed) && matches(fold(it.title), keyword) }
            } ?: rule.fallbackType?.let { type -> free { it.type == type } }
            if (found != null) {
                used += found.id
                result[rule.target] = found.id
            }
        }
        return MondayMapping(result)
    }

    /** Destino sugerido por el nombre del tablero: Solicitud/Proyecto -> ordenes de proyecto; Mantenimiento/Tareas -> planes. */
    fun suggestTarget(boardName: String): Pair<String, String> {
        val folded = fold(boardName)
        return when {
            hasPrefix(folded, "solicitud") || hasPrefix(folded, "proyecto") || hasPrefix(folded, "project") || hasPrefix(folded, "request") ->
                MondayDestination.Orders to MondayDestination.KindProject
            hasPrefix(folded, "mantenimiento") || hasPrefix(folded, "maintenance") || hasPrefix(folded, "tarea") || hasWord(folded, "tasks", "task") ->
                MondayDestination.Plans to MondayDestination.KindCorrective
            else -> MondayDestination.Orders to MondayDestination.KindCorrective
        }
    }

    // ---- Texto ----

    private fun words(folded: String) = folded.split(' ').filter { it.isNotEmpty() }
    private fun hasWord(folded: String, vararg candidates: String) = words(folded).any { it in candidates }
    private fun hasPrefix(folded: String, prefix: String) = words(folded).any { it.startsWith(prefix) }
    private fun hasPhrase(folded: String, phrase: String) = " $folded ".contains(" $phrase ")

    private fun truncate(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"

    // ---- Frecuencia, estado y prioridad ----

    /** Frecuencia y dias segun el nombre del grupo ("Anuales", "Trimestrales", "Cada 45 dias"...); null si no se reconoce. */
    fun frequencyOf(group: String?): Pair<PlanFrequency, Int?>? {
        val folded = fold(group.orEmpty())
        if (folded.isEmpty()) return null
        everyDays.find(folded)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..3650 }?.let { return PlanFrequency.EveryNDays to it }
        return when {
            hasPrefix(folded, "anual") || hasPrefix(folded, "annual") || hasWord(folded, "yearly", "year", "anualmente") -> PlanFrequency.Yearly to null
            hasPrefix(folded, "trimestr") || hasPrefix(folded, "quarter") -> PlanFrequency.Quarterly to null
            hasPrefix(folded, "mensual") || hasPrefix(folded, "monthly") || hasWord(folded, "month") -> PlanFrequency.Monthly to null
            hasPrefix(folded, "semanal") || hasPrefix(folded, "weekly") || hasWord(folded, "week") -> PlanFrequency.EveryNDays to 7
            hasPrefix(folded, "quincenal") || hasPrefix(folded, "biweekly") -> PlanFrequency.EveryNDays to 14
            hasPrefix(folded, "diari") || hasWord(folded, "daily") -> PlanFrequency.EveryNDays to 1
            else -> null
        }
    }

    /**
     * Estado de la orden segun la etiqueta de la columna de estado (o del grupo): Pendiente de aprobacion, En cotizacion,
     * Aprobado, Aprobado - En progreso, Completado, Rechazado, Stuck/Detenido... Vacio o desconocido es null.
     */
    fun orderStatusOf(text: String?): OrderStatus? {
        val folded = fold(text.orEmpty())
        return when {
            folded.isEmpty() -> null
            hasPrefix(folded, "cancel") -> OrderStatus.Cancelled
            hasPrefix(folded, "rechaz") || hasPrefix(folded, "reject") || hasPrefix(folded, "denegad") || hasWord(folded, "denied") -> OrderStatus.Rejected
            hasPrefix(folded, "complet") || hasWord(folded, "done", "listo", "lista", "hecho", "hecha", "terminado", "terminada", "finalizado", "finalizada", "cerrado", "cerrada", "closed") -> OrderStatus.Done
            hasWord(folded, "stuck", "detenido", "detenida", "bloqueado", "bloqueada", "atascado") || hasPhrase(folded, "on hold") || hasPhrase(folded, "en espera") || hasPrefix(folded, "paus") -> OrderStatus.OnHold
            hasPhrase(folded, "in progress") || hasPhrase(folded, "working on it") || hasPhrase(folded, "en curso") ||
                hasPhrase(folded, "en progreso") || hasPhrase(folded, "en proceso") -> OrderStatus.InProgress
            hasPrefix(folded, "cotiz") || hasPrefix(folded, "quot") -> OrderStatus.Quoting
            hasPrefix(folded, "pendiente") || hasWord(folded, "pending", "requested", "solicitado", "solicitada", "nuevo", "nueva", "new") || hasPhrase(folded, "por aprobar") -> OrderStatus.Requested
            hasPrefix(folded, "aprobad") || hasPrefix(folded, "approv") || hasWord(folded, "open", "abierto", "abierta") -> OrderStatus.Open
            else -> null
        }
    }

    /** Solo los proyectos usan Pendiente de aprobacion, En cotizacion y Rechazada: en una avería u otro tipo pasan a Abierta o Cancelada. */
    fun statusForKind(kind: OrderKind, status: OrderStatus): OrderStatus = if (kind == OrderKind.Project) status else when (status) {
        OrderStatus.Requested, OrderStatus.Quoting -> OrderStatus.Open
        OrderStatus.Rejected -> OrderStatus.Cancelled
        else -> status
    }

    /** Prioridad segun la etiqueta de la columna de prioridad o urgencia; el resto es Media. */
    fun priorityOf(text: String?): OrderPriority {
        val folded = fold(text.orEmpty())
        return when {
            hasWord(folded, "critical", "critico", "critica", "urgent", "urgente") -> OrderPriority.Urgent
            hasWord(folded, "high", "alta", "alto") -> OrderPriority.High
            hasWord(folded, "low", "baja", "bajo") -> OrderPriority.Low
            else -> OrderPriority.Medium
        }
    }

    // ---- Cotizacion ----

    /**
     * Monto de un texto con coma o punto ("1,234.50", "1.234,50", "$2,500", "1234,5", "1 500"). Con las dos marcas, la
     * ultima es la decimal; con una sola, es decimal salvo que sea de miles (varias, o tres cifras despues y una parte
     * entera de 1 a 3 cifras que no sea 0). Negativo, vacio o fuera de NUMERIC(14,2) es null.
     */
    fun amountOf(text: String?): BigDecimal? {
        val raw = text.orEmpty().filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
        if (raw.none { it.isDigit() } || '-' in raw) return null
        val lastDot = raw.lastIndexOf('.')
        val lastComma = raw.lastIndexOf(',')
        val normalized = when {
            lastDot >= 0 && lastComma >= 0 -> {
                val at = maxOf(lastDot, lastComma)
                raw.substring(0, at).replace(".", "").replace(",", "") + "." + raw.substring(at + 1).replace(".", "").replace(",", "")
            }
            lastComma >= 0 -> singleMark(raw, ',')
            lastDot >= 0 -> singleMark(raw, '.')
            else -> raw
        }
        val amount = normalized.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP) ?: return null
        return amount.takeIf { it.signum() >= 0 && it < BigDecimal("1000000000000") }
    }

    private fun singleMark(raw: String, mark: Char): String {
        val at = raw.lastIndexOf(mark)
        val integer = raw.substring(0, at)
        val fraction = raw.substring(at + 1)
        val thousands = raw.count { it == mark } > 1 || (fraction.length == 3 && integer.length in 1..3 && integer != "0")
        return if (thousands) raw.replace(mark.toString(), "") else "$integer.$fraction"
    }

    // ---- Lectura de valores ----

    private fun valueObject(cell: MondayCell?): JsonObject? =
        cell?.value?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun iso(text: String?): LocalDate? =
        text?.let { isoDate.find(it)?.value }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Fecha de una celda "date" (JSON {"date":...}) o, si no, la primera fecha ISO de su texto. */
    fun dateOf(cell: MondayCell?): LocalDate? = cell?.let { iso(valueObject(it)?.string("date")) ?: iso(it.text) }

    /** Fecha de fin de una celda "timeline" (JSON {"from":...,"to":...}); sin fin, la de inicio; sin JSON, las fechas del texto. */
    fun timelineEndOf(cell: MondayCell?): LocalDate? {
        if (cell == null) return null
        val obj = valueObject(cell)
        val to = iso(obj?.string("to"))
        val from = iso(obj?.string("from"))
        if (to != null || from != null) return to ?: from
        val dates = isoDate.findAll(cell.text).mapNotNull { runCatching { LocalDate.parse(it.value) }.getOrNull() }.toList()
        return dates.getOrNull(1) ?: dates.getOrNull(0)
    }

    /** Fecha de la celda mapeada: el fin de una linea de tiempo o la fecha de una columna de fecha. */
    fun dueOf(cell: MondayCell?): LocalDate? = if (cell?.type == "timeline") timelineEndOf(cell) else dateOf(cell)

    /** Ids de monday de una celda de personas ({"personsAndTeams":[{"id":1,"kind":"person"}]}), con su clase. */
    fun personEntries(cell: MondayCell?): List<Pair<String, String>> {
        val array = valueObject(cell)?.get("personsAndTeams") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry.string("id").takeIf { assetId.matches(it) } ?: return@mapNotNull null
            id to entry.string("kind").ifEmpty { "person" }
        }
    }

    /** Todos los ids de personas (no equipos) de varias celdas, para pedirlos a monday con users(ids). */
    fun personIds(cells: List<MondayCell>): Set<String> =
        cells.filter { it.type == "people" }.flatMap { personEntries(it) }.filter { it.second == "person" }.map { it.first }.toSet()

    /**
     * Correos y nombres de una celda de personas: con correo conocido va el correo (para emparejarla con un usuario); sin
     * correo, el nombre (queda anotado en la descripcion). Sin JSON valido se usan los nombres del texto de la celda.
     */
    fun peopleOf(cell: MondayCell?, people: Map<String, MondayPerson>): Pair<List<String>, List<String>> {
        if (cell == null) return emptyList<String>() to emptyList()
        val texts = cell.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val entries = personEntries(cell)
        if (entries.isEmpty()) return emptyList<String>() to texts.distinct()
        val emails = mutableListOf<String>()
        val names = mutableListOf<String>()
        entries.forEachIndexed { index, (id, kind) ->
            val person = people[id]
            val email = person?.email?.trim()?.lowercase().orEmpty()
            if (kind == "person" && email.isNotEmpty()) {
                emails += email
            } else {
                names += person?.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: texts.takeIf { it.size == entries.size }?.get(index)
                    ?: "Persona $id"
            }
        }
        return emails.distinct() to names.distinct()
    }

    /** Nombres (no correos) de una celda de personas o de texto, para "Cotizado por". */
    fun namesOf(cell: MondayCell?, people: Map<String, MondayPerson>): String {
        if (cell == null) return ""
        if (cell.type != "people") return cell.text.trim()
        val entries = personEntries(cell)
        val texts = cell.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return texts.joinToString(", ")
        return entries.mapIndexed { index, (id, _) ->
            people[id]?.name?.trim()?.takeIf { it.isNotEmpty() } ?: texts.takeIf { it.size == entries.size }?.get(index) ?: "Persona $id"
        }.distinct().joinToString(", ")
    }

    // ---- Elemento -> plan u orden ----

    private fun cell(item: MondayItem, mapping: MondayMapping, target: String): MondayCell? =
        mapping.columns[target]?.let { id -> item.cells.firstOrNull { it.columnId == id } }

    private fun commentDate(createdAt: String): Pair<Instant?, LocalDate?> {
        val instant = runCatching { Instant.parse(createdAt.trim()) }.getOrNull()
        return instant to (instant?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: iso(createdAt))
    }

    private fun authorOf(update: MondayUpdate) = update.authorName.trim().ifEmpty { update.authorEmail.trim() }.ifEmpty { "Monday" }

    /** Nota con la clave monday:upd:<id>; sin texto ni archivos no hay nota. El modulo antepone "[Autor · fecha]" al aplicar. */
    fun noteOf(update: MondayUpdate): ImportedNote? {
        val text = update.body.trim().ifEmpty { if (update.assets.isNotEmpty()) "(archivos adjuntos)" else return null }
        return ImportedNote(key = UpdatePrefix + update.id, author = authorOf(update), at = commentDate(update.createdAt).first, body = text)
    }

    /** Linea "[Autor · yyyy-MM-dd] texto" de una actualizacion, para el final de la descripcion de un plan. */
    fun updateLine(update: MondayUpdate): String? {
        val text = update.body.trim().ifEmpty { return null }
        val date = commentDate(update.createdAt).second
        return "[${authorOf(update)}${date?.let { " · $it" }.orEmpty()}] $text"
    }

    private fun filesOf(update: MondayUpdate): List<MondayFile> =
        update.assets.filter { assetId.matches(it.id) }.map { MondayFile(it.id, it.name, update = update.id) }

    private fun allUpdates(item: MondayItem) = item.updates.flatMap { listOf(it) + it.replies }

    private fun extrasOf(item: MondayItem, mapping: MondayMapping): List<String> {
        val mappedIds = mapping.columns.values.toSet()
        // Las columnas sin destino con valor van a la descripcion como "Columna: valor".
        return item.cells
            .filter { it.columnId !in mappedIds && it.type !in ignoredTypes && it.text.isNotBlank() }
            .map { "${it.title.ifBlank { it.columnId }}: ${it.text.trim()}" }
    }

    private fun checklistOf(item: MondayItem): List<ImportedCheck> = item.subitems.filter { it.name.isNotBlank() }.map { sub ->
        val done = sub.cells.firstOrNull { it.type == "status" }?.let { orderStatusOf(it.text) == OrderStatus.Done } ?: false
        ImportedCheck(key = SubPrefix + sub.id, text = sub.name.trim().take(300), done = done)
    }.distinctBy { it.key }

    private class Responsible(val email: String?, val name: String?, val others: String)

    private fun responsibleOf(cell: MondayCell?, people: Map<String, MondayPerson>): Responsible {
        val (emails, names) = peopleOf(cell, people)
        val others = (emails.drop(1) + (if (emails.isEmpty()) names.drop(1) else names)).joinToString(", ")
        return Responsible(emails.firstOrNull(), if (emails.isEmpty()) names.firstOrNull() else null, others)
    }

    private fun text(item: MondayItem, mapping: MondayMapping, target: String) = cell(item, mapping, target)?.text?.trim().orEmpty()

    /** Plan preventivo: la frecuencia sale del grupo; la proxima fecha, de la fecha limite. */
    fun toPlan(item: MondayItem, mapping: MondayMapping, people: Map<String, MondayPerson> = emptyMap()): MondayConverted {
        val problems = mutableListOf<String>()
        val frequency = frequencyOf(item.group) ?: run {
            problems += "El grupo '${item.group.ifBlank { "(sin grupo)" }}' no indica la frecuencia; se usa mensual."
            PlanFrequency.Monthly to null
        }
        val responsible = responsibleOf(cell(item, mapping, MondayTarget.Assignees), people)
        val statusText = text(item, mapping, MondayTarget.Status)
        val lines = listOf(text(item, mapping, MondayTarget.Description)) +
            listOfNotNull(statusText.takeIf { it.isNotEmpty() }?.let { "Estado en Monday: $it" }) +
            extrasOf(item, mapping) +
            listOfNotNull(responsible.others.takeIf { it.isNotEmpty() }?.let { "Otros responsables en Monday: $it" })
        val updates = allUpdates(item).mapNotNull(::updateLine)
        val body = lines.filter { it.isNotEmpty() }.joinToString("\n")
        val description = if (updates.isEmpty()) body else listOf(body, "Actualizaciones de Monday:\n" + updates.joinToString("\n")).filter { it.isNotEmpty() }.joinToString("\n\n")
        val plan = ImportedPlan(
            key = ItemPrefix + item.id,
            title = item.name.trim(),
            description = truncate(description, MaxPlanDescription),
            propertyName = text(item, mapping, MondayTarget.Property).ifEmpty { null },
            assetName = text(item, mapping, MondayTarget.Asset).ifEmpty { null },
            frequency = frequency.first,
            intervalDays = frequency.second,
            nextDue = dueOf(cell(item, mapping, MondayTarget.Due)),
            priority = cell(item, mapping, MondayTarget.Priority)?.let { priorityOf(it.text) } ?: OrderPriority.Medium,
            assigneeEmail = responsible.email,
            assigneeName = responsible.name,
            checklist = checklistOf(item),
        )
        return MondayConverted(plan, null, emptyList(), problems)
    }

    /** Orden o proyecto: el estado sale de la columna de estado y, si no sirve, del grupo. */
    fun toOrder(item: MondayItem, mapping: MondayMapping, kind: OrderKind, people: Map<String, MondayPerson> = emptyMap()): MondayConverted {
        val problems = mutableListOf<String>()
        val statusText = text(item, mapping, MondayTarget.Status)
        val fromColumn = orderStatusOf(statusText)
        if (statusText.isNotEmpty() && fromColumn == null) problems += "El estado '$statusText' no se reconoce."
        val fromGroup = if (fromColumn == null) orderStatusOf(item.group) else null
        val status = statusForKind(kind, fromColumn ?: fromGroup ?: if (kind == OrderKind.Project) OrderStatus.Requested else OrderStatus.Open)
        val responsible = responsibleOf(cell(item, mapping, MondayTarget.Assignees), people)
        val quoteCell = cell(item, mapping, MondayTarget.Quote)
        val quote = amountOf(quoteCell?.text)
        if (quoteCell != null && quoteCell.text.isNotBlank() && quote == null) problems += "La cotización '${quoteCell.text.trim()}' no es un monto válido."
        val lines = listOf(text(item, mapping, MondayTarget.Description)) +
            listOfNotNull(item.group.trim().takeIf { it.isNotEmpty() && fromColumn != null }?.let { "Grupo en Monday: $it" }) +
            extrasOf(item, mapping) +
            listOfNotNull(responsible.others.takeIf { it.isNotEmpty() }?.let { "Otros responsables en Monday: $it" })
        val order = ImportedOrder(
            key = ItemPrefix + item.id,
            kind = kind,
            status = status,
            title = item.name.trim(),
            description = truncate(lines.filter { it.isNotEmpty() }.joinToString("\n"), MaxOrderDescription),
            propertyName = text(item, mapping, MondayTarget.Property).ifEmpty { null },
            assetName = text(item, mapping, MondayTarget.Asset).ifEmpty { null },
            department = text(item, mapping, MondayTarget.Department).take(80),
            priority = cell(item, mapping, MondayTarget.Priority)?.let { priorityOf(it.text) } ?: OrderPriority.Medium,
            dueDate = dueOf(cell(item, mapping, MondayTarget.Due)),
            assigneeEmail = responsible.email,
            assigneeName = responsible.name,
            quoteAmount = quote,
            quoteCurrency = "USD",
            quotedBy = namesOf(cell(item, mapping, MondayTarget.QuotedBy), people).take(120),
            requestedByName = "Monday",
            checklist = checklistOf(item),
            notes = allUpdates(item).mapNotNull(::noteOf).distinctBy { it.key },
        )
        val files = item.cells.filter { it.type == "file" }.flatMap { fileColumn(it) } + allUpdates(item).flatMap(::filesOf)
        return MondayConverted(null, order, files.distinctBy { it.asset }, problems)
    }

    // ---- Archivos ----

    /** Assets de una columna de archivos ({"files":[{"fileType":"ASSET","assetId":1,"name":"a.pdf"}]}); enlaces a Drive o Dropbox no son archivos. */
    fun fileColumn(cell: MondayCell): List<MondayFile> {
        val files = (valueObject(cell)?.get("files") as? JsonArray).orEmpty()
        return files.mapNotNull { element ->
            val file = element as? JsonObject ?: return@mapNotNull null
            if (file.string("fileType").ifEmpty { "ASSET" } != "ASSET") return@mapNotNull null
            MondayFile(file.string("assetId"), file.string("name"), column = cell.title).takeIf { assetId.matches(it.asset) }
        }
    }

    fun encodeFiles(files: List<MondayFile>): String = JsonArray(
        files.filter { assetId.matches(it.asset) }.map { file ->
            buildJsonObject {
                put("asset", file.asset)
                put("name", file.name.take(255))
                if (file.column.isNotEmpty()) put("column", file.column)
                if (file.update.isNotEmpty()) put("update", file.update)
            }
        },
    ).toString()

    fun decodeFiles(raw: String?): List<MondayFile> =
        runCatching { json.parseToJsonElement(raw.orEmpty()) as JsonArray }.getOrNull().orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            MondayFile(item.string("asset"), item.string("name"), item.string("column"), item.string("update")).takeIf { assetId.matches(it.asset) }
        }
}

@Serializable
data class MondayCheckDto(val key: String, val text: String, val done: Boolean = false)

@Serializable
data class MondayNoteDto(val key: String, val author: String, val at: String? = null, val body: String)

/**
 * Fila guardada en ext_mantenimiento_monday__rows.payload: el plan o la orden importada (segun [target]) con enums,
 * fechas y montos como texto, mas los problemas que se anotaron al convertirla.
 */
@Serializable
data class MondayRowDto(
    val target: String,
    val key: String,
    val title: String,
    val description: String = "",
    val propertyName: String? = null,
    val assetName: String? = null,
    val priority: String = OrderPriority.Medium.name,
    val assigneeEmail: String? = null,
    val assigneeName: String? = null,
    val checklist: List<MondayCheckDto> = emptyList(),
    // Planes
    val frequency: String = PlanFrequency.Monthly.name,
    val intervalDays: Int? = null,
    val nextDue: String? = null,
    // Ordenes
    val kind: String = OrderKind.Corrective.name,
    val status: String = OrderStatus.Open.name,
    val department: String = "",
    val dueDate: String? = null,
    val quoteAmount: String? = null,
    val quoteCurrency: String = "USD",
    val quotedBy: String = "",
    val requestedByName: String = "",
    val notes: List<MondayNoteDto> = emptyList(),
    val problems: List<String> = emptyList(),
) {
    fun toPlan(): ImportedPlan = ImportedPlan(
        key = key,
        title = title,
        description = description,
        propertyName = propertyName,
        assetName = assetName,
        frequency = enumOf(frequency, PlanFrequency.Monthly),
        intervalDays = intervalDays,
        nextDue = nextDue?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        priority = enumOf(priority, OrderPriority.Medium),
        assigneeEmail = assigneeEmail,
        assigneeName = assigneeName,
        checklist = checklist.map { ImportedCheck(it.key, it.text, it.done) },
    )

    fun toOrder(): ImportedOrder = ImportedOrder(
        key = key,
        kind = enumOf(kind, OrderKind.Corrective),
        status = enumOf(status, OrderStatus.Open),
        title = title,
        description = description,
        propertyName = propertyName,
        assetName = assetName,
        department = department,
        priority = enumOf(priority, OrderPriority.Medium),
        dueDate = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        assigneeEmail = assigneeEmail,
        assigneeName = assigneeName,
        quoteAmount = quoteAmount?.toBigDecimalOrNull(),
        quoteCurrency = quoteCurrency,
        quotedBy = quotedBy,
        requestedByName = requestedByName,
        checklist = checklist.map { ImportedCheck(it.key, it.text, it.done) },
        notes = notes.map { ImportedNote(it.key, it.author, it.at?.let { at -> runCatching { Instant.parse(at) }.getOrNull() }, it.body) },
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private inline fun <reified E : Enum<E>> enumOf(name: String, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

        fun of(converted: MondayConverted): MondayRowDto {
            val plan = converted.plan
            val order = converted.order
            return when {
                plan != null -> MondayRowDto(
                    target = MondayDestination.Plans, key = plan.key, title = plan.title, description = plan.description,
                    propertyName = plan.propertyName, assetName = plan.assetName, priority = plan.priority.name,
                    assigneeEmail = plan.assigneeEmail, assigneeName = plan.assigneeName,
                    checklist = plan.checklist.map { MondayCheckDto(it.key, it.text, it.done) },
                    frequency = plan.frequency.name, intervalDays = plan.intervalDays, nextDue = plan.nextDue?.toString(),
                    problems = converted.problems,
                )
                order != null -> MondayRowDto(
                    target = MondayDestination.Orders, key = order.key, title = order.title, description = order.description,
                    propertyName = order.propertyName, assetName = order.assetName, priority = order.priority.name,
                    assigneeEmail = order.assigneeEmail, assigneeName = order.assigneeName,
                    checklist = order.checklist.map { MondayCheckDto(it.key, it.text, it.done) },
                    kind = order.kind.name, status = order.status.name, department = order.department, dueDate = order.dueDate?.toString(),
                    quoteAmount = order.quoteAmount?.toPlainString(), quoteCurrency = order.quoteCurrency, quotedBy = order.quotedBy,
                    requestedByName = order.requestedByName,
                    notes = order.notes.map { MondayNoteDto(it.key, it.author, it.at?.toString(), it.body) },
                    problems = converted.problems,
                )
                else -> error("Una fila convertida lleva un plan o una orden.")
            }
        }

        fun encode(converted: MondayConverted): String = json.encodeToString(serializer(), of(converted))

        fun decode(payload: String): MondayRowDto = json.decodeFromString(serializer(), payload)
    }
}

/**
 * Resumen de la vista previa guardado en el trabajo (jsonb). Se calcula una vez, por lotes, al terminar de leer, y las
 * listas se acotan para que un tablero enorme no llene la base ni la pantalla.
 */
@Serializable
data class MondaySummaryDto(
    val plans: Int = 0,
    val orders: Int = 0,
    val properties: List<String> = emptyList(),
    val assets: List<String> = emptyList(),
    val checklistItems: Int = 0,
    val notes: Int = 0,
    val files: Int = 0,
    val matchedPeople: List<String> = emptyList(),
    val unmatchedPeople: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val problemCount: Int = 0,
) {
    fun toSummary() = ImportSummary(
        plans = plans, orders = orders, properties = properties, assets = assets, checklistItems = checklistItems, notes = notes,
        files = files, matchedPeople = matchedPeople, unmatchedPeople = unmatchedPeople, problems = problems,
    )

    companion object {
        const val MaxNames = 500
        const val MaxProblems = 200

        /** Resumen del modulo mas los problemas que anoto la conversion (ya con el titulo de la fila). */
        fun of(summary: ImportSummary, extraProblems: List<String> = emptyList()): MondaySummaryDto {
            val problems = (summary.problems + extraProblems).take(MaxProblems)
            return MondaySummaryDto(
                summary.plans, summary.orders, summary.properties, summary.assets, summary.checklistItems, summary.notes, summary.files,
                summary.matchedPeople, summary.unmatchedPeople, problems, summary.problems.size + extraProblems.size,
            )
        }

        /** Suma los resumenes de varios lotes: conteos sumados, nombres sin repetir y problemas acotados. */
        fun merge(parts: List<MondaySummaryDto>): MondaySummaryDto = MondaySummaryDto(
            plans = parts.sumOf { it.plans },
            orders = parts.sumOf { it.orders },
            properties = parts.flatMap { it.properties }.distinct().take(MaxNames),
            assets = parts.flatMap { it.assets }.distinct().take(MaxNames),
            checklistItems = parts.sumOf { it.checklistItems },
            notes = parts.sumOf { it.notes },
            files = parts.sumOf { it.files },
            matchedPeople = parts.flatMap { it.matchedPeople }.distinct().take(MaxNames),
            unmatchedPeople = parts.flatMap { it.unmatchedPeople }.distinct().take(MaxNames),
            problems = parts.flatMap { it.problems }.take(MaxProblems),
            problemCount = parts.sumOf { it.problemCount },
        )
    }
}
