package com.ideasdeveloper.idc.extensions.tareas_monday

import com.ideasdeveloper.idc.modules.tareas.ImportedCheck
import com.ideasdeveloper.idc.modules.tareas.ImportedComment
import com.ideasdeveloper.idc.modules.tareas.ImportSummary
import com.ideasdeveloper.idc.modules.tareas.ImportedTask
import com.ideasdeveloper.idc.modules.tareas.TaskPriority
import com.ideasdeveloper.idc.modules.tareas.TaskStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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

/** A que dato de la tarea va cada columna: objetivo -> id de columna de monday. */
@Serializable
data class MondayMapping(val columns: Map<String, String> = emptyMap())

/** Datos de la tarea que se pueden mapear a una columna del tablero. */
object MondayTarget {
    const val Status = "status"
    const val Priority = "priority"
    const val Due = "due"
    const val Assignees = "assignees"
    const val Collaborators = "collaborators"
    const val Reviewer = "reviewer"
    const val Detail = "detail"

    class Definition(val target: String, val label: String)

    // En el orden en que se muestran en el formulario de mapeo.
    val all = listOf(
        Definition(Status, "Estado"),
        Definition(Priority, "Prioridad"),
        Definition(Due, "Fecha limite"),
        Definition(Assignees, "Responsables"),
        Definition(Collaborators, "Colaboradores (tambien responsables)"),
        Definition(Reviewer, "Revisor"),
        Definition(Detail, "Detalle"),
    )
}

/** Archivo de monday (de una columna de archivos o de una actualizacion): solo se guarda el id, el enlace caduca. */
data class MondayFile(val asset: String, val name: String, val column: String = "", val update: String = "")

/** Una tarea ya convertida y sus archivos por descargar al aplicarla. */
data class MondayConverted(val task: ImportedTask, val files: List<MondayFile>)

/**
 * Funciones puras del mapeo: sugerir a que columna va cada dato por su titulo y tipo (espanol e ingles) y convertir un
 * elemento de monday en una tarea a importar. Nada de red ni de base de datos.
 */
object TareasMondayMapping {
    const val SubPrefix = "monday:sub:"
    const val UpdatePrefix = "monday:upd:"
    const val AssetPrefix = "monday:asset:"
    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
    private val assetId = Regex("^[0-9]{1,20}$")
    private val json = Json { ignoreUnknownKeys = true }

    // Columnas que nunca son un dato de la tarea ni van al detalle.
    private val ignoredTypes = setOf("name", "subitems", "board_relation", "mirror", "file", "creation_log", "last_updated", "item_id", "button", "dependency", "doc", "auto_number", "formula")

    private class Rule(val target: String, val keywords: List<String>, val allowed: Set<String> = emptySet(), val fallbackType: String? = null)

    // Orden de prioridad: lo mas especifico primero para que "Reviewer" no se lleve la columna de responsables.
    private val rules = listOf(
        Rule(MondayTarget.Reviewer, listOf("reviewer", "revisor", "revisado por", "aprobador", "approver"), setOf("people")),
        Rule(MondayTarget.Collaborators, listOf("collaborator", "collaborators", "colaborador", "colaboradores", "involved", "involucrados"), setOf("people")),
        Rule(MondayTarget.Assignees, listOf("owner", "person", "persona", "responsable", "responsables", "assignee", "assigned", "asignado", "asignada"), setOf("people"), "people"),
        Rule(MondayTarget.Priority, listOf("priority", "prioridad", "urgency", "urgencia", "urgente", "urgent"), setOf("status", "dropdown")),
        Rule(MondayTarget.Status, listOf("status", "estado", "state", "etapa", "stage"), setOf("status"), "status"),
        Rule(MondayTarget.Due, listOf("due date", "due", "deadline", "fecha limite", "vencimiento", "vence", "plazo", "fecha", "date", "timeline", "cronograma"), setOf("date", "timeline"), "date"),
        Rule(MondayTarget.Detail, listOf("detail", "details", "detalle", "detalles", "description", "descripcion", "notes", "notas", "note", "nota"), setOf("long_text", "text"), "long_text"),
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

    // ---- Estado y prioridad ----

    private fun words(folded: String) = folded.split(' ').filter { it.isNotEmpty() }
    private fun hasWord(folded: String, vararg candidates: String) = words(folded).any { it in candidates }
    private fun hasPrefix(folded: String, prefix: String) = words(folded).any { it.startsWith(prefix) }
    private fun hasPhrase(folded: String, phrase: String) = " $folded ".contains(" $phrase ")

    /** Estado de Tareas segun la etiqueta de la columna de estado; vacio o desconocido es Pendiente. */
    fun statusOf(text: String?): TaskStatus {
        val folded = fold(text.orEmpty())
        return when {
            folded.isEmpty() -> TaskStatus.Pending
            hasPrefix(folded, "cancel") -> TaskStatus.Cancelled
            hasPrefix(folded, "complet") || hasWord(folded, "done", "listo", "lista", "hecho", "hecha", "terminado", "terminada", "finalizado", "finalizada") -> TaskStatus.Done
            hasPrefix(folded, "review") || hasPrefix(folded, "revision") -> TaskStatus.Review
            hasPhrase(folded, "in progress") || hasPhrase(folded, "working on it") || hasPhrase(folded, "en curso") ||
                hasPhrase(folded, "en progreso") || hasWord(folded, "stuck", "bloqueado", "bloqueada", "atascado") -> TaskStatus.InProgress
            else -> TaskStatus.Pending
        }
    }

    /** Prioridad segun la etiqueta de la columna de prioridad o urgencia; el resto es Media. */
    fun priorityOf(text: String?): TaskPriority {
        val folded = fold(text.orEmpty())
        return when {
            hasWord(folded, "critical", "critico", "critica", "urgent", "urgente") -> TaskPriority.Urgent
            hasWord(folded, "high", "alta", "alto") -> TaskPriority.High
            hasWord(folded, "low", "baja", "bajo") -> TaskPriority.Low
            else -> TaskPriority.Medium
        }
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

    /** Fecha limite de la celda mapeada: el fin de una linea de tiempo o la fecha de una columna de fecha. */
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
     * correo, el nombre (queda anotado en el detalle). Sin JSON valido se usan los nombres del texto de la celda.
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

    // ---- Elemento -> tarea ----

    private fun cell(item: MondayItem, mapping: MondayMapping, target: String): MondayCell? =
        mapping.columns[target]?.let { id -> item.cells.firstOrNull { it.columnId == id } }

    private fun commentDate(createdAt: String): Pair<Instant?, LocalDate?> {
        val instant = runCatching { Instant.parse(createdAt.trim()) }.getOrNull()
        return instant to (instant?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: iso(createdAt))
    }

    /** Comentario "[Autor · yyyy-MM-dd] texto" con la clave monday:upd:<id>; sin texto ni archivos no hay comentario. */
    fun commentOf(update: MondayUpdate): ImportedComment? {
        val text = update.body.trim().ifEmpty { if (update.assets.isNotEmpty()) "(archivos adjuntos)" else return null }
        val author = update.authorName.trim().ifEmpty { update.authorEmail.trim() }.ifEmpty { "Monday" }
        // Solo el texto: el modulo antepone "[Autor · fecha]" al aplicar.
        val (at, _) = commentDate(update.createdAt)
        return ImportedComment(key = UpdatePrefix + update.id, author = author, at = at, body = text)
    }

    private fun filesOf(update: MondayUpdate): List<MondayFile> =
        update.assets.filter { assetId.matches(it.id) }.map { MondayFile(it.id, it.name, update = update.id) }

    fun toTask(item: MondayItem, mapping: MondayMapping, people: Map<String, MondayPerson> = emptyMap()): MondayConverted {
        val assigneeCells = listOfNotNull(cell(item, mapping, MondayTarget.Assignees), cell(item, mapping, MondayTarget.Collaborators)).map { peopleOf(it, people) }
        val reviewer = peopleOf(cell(item, mapping, MondayTarget.Reviewer), people)
        val mappedIds = mapping.columns.values.toSet()
        val detailText = cell(item, mapping, MondayTarget.Detail)?.text?.trim().orEmpty()
        // Las columnas sin destino con valor van al detalle como "Columna: valor".
        val extras = item.cells
            .filter { it.columnId !in mappedIds && it.type !in ignoredTypes && it.text.isNotBlank() }
            .map { "${it.title.ifBlank { it.columnId }}: ${it.text.trim()}" }
        val checklist = item.subitems.filter { it.name.isNotBlank() }.map { sub ->
            val done = sub.cells.firstOrNull { it.type == "status" }?.let { statusOf(it.text) == TaskStatus.Done } ?: false
            ImportedCheck(key = SubPrefix + sub.id, text = sub.name.trim(), done = done)
        }
        val comments = item.updates.flatMap { listOf(it) + it.replies }.mapNotNull(::commentOf)
        val files = item.cells.filter { it.type == "file" }.flatMap { fileColumn(it) } +
            item.updates.flatMap { listOf(it) + it.replies }.flatMap(::filesOf)
        val task = ImportedTask(
            key = item.id,
            title = item.name.trim(),
            detail = (listOf(detailText) + extras).filter { it.isNotEmpty() }.joinToString("\n"),
            groupName = item.group.trim().ifEmpty { null },
            status = cell(item, mapping, MondayTarget.Status)?.let { statusOf(it.text) } ?: TaskStatus.Pending,
            priority = cell(item, mapping, MondayTarget.Priority)?.let { priorityOf(it.text) } ?: TaskPriority.Medium,
            dueDate = dueOf(cell(item, mapping, MondayTarget.Due)),
            assigneeEmails = assigneeCells.flatMap { it.first }.distinct(),
            assigneeNames = assigneeCells.flatMap { it.second }.distinct(),
            reviewerEmail = reviewer.first.firstOrNull(),
            reviewerName = if (reviewer.first.isEmpty()) reviewer.second.firstOrNull() else null,
            checklist = checklist.distinctBy { it.key },
            comments = comments.distinctBy { it.key },
        )
        return MondayConverted(task, files.distinctBy { it.asset })
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
data class MondayCommentDto(val key: String, val author: String, val at: String? = null, val body: String)

/** Fila guardada en ext_tareas_monday__rows.payload: la tarea importada con estado, prioridad y fechas como texto. */
@Serializable
data class MondayRowDto(
    val key: String,
    val title: String,
    val detail: String = "",
    val groupName: String? = null,
    val status: String = TaskStatus.Pending.code,
    val priority: String = TaskPriority.Medium.code,
    val dueDate: String? = null,
    val assigneeEmails: List<String> = emptyList(),
    val assigneeNames: List<String> = emptyList(),
    val reviewerEmail: String? = null,
    val reviewerName: String? = null,
    val checklist: List<MondayCheckDto> = emptyList(),
    val comments: List<MondayCommentDto> = emptyList(),
) {
    fun toTask(): ImportedTask = ImportedTask(
        key = key,
        title = title,
        detail = detail,
        groupName = groupName,
        status = TaskStatus.fromCode(status) ?: TaskStatus.Pending,
        priority = TaskPriority.fromCode(priority) ?: TaskPriority.Medium,
        dueDate = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        assigneeEmails = assigneeEmails,
        assigneeNames = assigneeNames,
        reviewerEmail = reviewerEmail,
        reviewerName = reviewerName,
        checklist = checklist.map { ImportedCheck(it.key, it.text, it.done) },
        comments = comments.map { ImportedComment(it.key, it.author, it.at?.let { at -> runCatching { Instant.parse(at) }.getOrNull() }, it.body) },
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(task: ImportedTask) = MondayRowDto(
            key = task.key,
            title = task.title,
            detail = task.detail,
            groupName = task.groupName,
            status = task.status.code,
            priority = task.priority.code,
            dueDate = task.dueDate?.toString(),
            assigneeEmails = task.assigneeEmails,
            assigneeNames = task.assigneeNames,
            reviewerEmail = task.reviewerEmail,
            reviewerName = task.reviewerName,
            checklist = task.checklist.map { MondayCheckDto(it.key, it.text, it.done) },
            comments = task.comments.map { MondayCommentDto(it.key, it.author, it.at?.toString(), it.body) },
        )

        fun encode(task: ImportedTask): String = json.encodeToString(serializer(), of(task))

        fun decode(payload: String): ImportedTask = json.decodeFromString(serializer(), payload).toTask()
    }
}

/**
 * Resumen de la vista previa guardado en el trabajo (jsonb). Se calcula una vez, por lotes, al terminar de leer, y las
 * listas se acotan para que un tablero enorme no llene la base ni la pantalla.
 */
@Serializable
data class MondaySummaryDto(
    val tasks: Int = 0,
    val groups: List<String> = emptyList(),
    val checklistItems: Int = 0,
    val comments: Int = 0,
    val files: Int = 0,
    val matchedPeople: List<String> = emptyList(),
    val unmatchedPeople: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val problemCount: Int = 0,
) {
    fun toSummary() = ImportSummary(tasks, groups, checklistItems, comments, files, matchedPeople, unmatchedPeople, problems)

    companion object {
        const val MaxNames = 500
        const val MaxProblems = 200

        fun of(summary: ImportSummary) = MondaySummaryDto(
            summary.tasks, summary.groups, summary.checklistItems, summary.comments, summary.files,
            summary.matchedPeople, summary.unmatchedPeople, summary.problems, summary.problems.size,
        )

        /** Suma los resumenes de varios lotes: conteos sumados, nombres sin repetir y problemas acotados. */
        fun merge(parts: List<MondaySummaryDto>): MondaySummaryDto = MondaySummaryDto(
            tasks = parts.sumOf { it.tasks },
            groups = parts.flatMap { it.groups }.distinct().take(MaxNames),
            checklistItems = parts.sumOf { it.checklistItems },
            comments = parts.sumOf { it.comments },
            files = parts.sumOf { it.files },
            matchedPeople = parts.flatMap { it.matchedPeople }.distinct().take(MaxNames),
            unmatchedPeople = parts.flatMap { it.unmatchedPeople }.distinct().take(MaxNames),
            problems = parts.flatMap { it.problems }.take(MaxProblems),
            problemCount = parts.sumOf { it.problemCount },
        )
    }
}
