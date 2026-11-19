package com.ideasdeveloper.idc.extensions.empleados_monday

import com.ideasdeveloper.idc.modules.empleados.EmpleadosImportSummary
import com.ideasdeveloper.idc.modules.empleados.EmpleadosScheduleText
import com.ideasdeveloper.idc.modules.empleados.ImportedEmployee
import com.ideasdeveloper.idc.modules.empleados.ImportedSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.time.LocalTime

/** Columna de un tablero de monday (su id estable, su titulo y su tipo: status, text, dropdown...). */
data class MondayColumn(val id: String, val title: String, val type: String)

/** Valor de una columna en un elemento: [text] es lo que muestra monday y [value] su JSON crudo. */
data class MondayCell(val columnId: String, val title: String, val type: String, val text: String, val value: String = "")

/** Elemento de un tablero ya leido de la API, sin JSON de red: es lo unico que ve la conversion. */
data class MondayItem(val id: String, val name: String, val group: String, val cells: List<MondayCell>)

/**
 * A que dato del empleado va cada columna (objetivo -> id de columna de monday) y los valores por omision para todos:
 * [department] cuando el tablero no tiene columna de departamento y [jobTitle] cuando no tiene columna de puesto.
 */
@Serializable
data class MondayMapping(val columns: Map<String, String> = emptyMap(), val department: String = "", val jobTitle: String = "")

/** Datos del empleado que se pueden mapear a una columna del tablero. */
object MondayTarget {
    const val Name = "name"
    const val Site = "site"
    const val JobTitle = "jobTitle"
    const val Department = "department"
    const val Days = "days"
    const val Hours = "hours"
    const val Availability = "availability"
    const val Phone = "phone"
    const val Email = "email"
    const val Notes = "notes"

    class Definition(val target: String, val label: String)

    // En el orden en que se muestran en el formulario de mapeo.
    val all = listOf(
        Definition(Name, "Nombre (si no, el nombre del elemento)"),
        Definition(Site, "Sede"),
        Definition(JobTitle, "Puesto"),
        Definition(Department, "Departamento"),
        Definition(Days, "Dias de trabajo"),
        Definition(Hours, "Horario"),
        Definition(Availability, "Disponibilidad"),
        Definition(Phone, "Telefono"),
        Definition(Email, "Correo"),
        Definition(Notes, "Notas"),
    )
}

/** Un empleado ya convertido; [problem] explica lo que no se entendio (horario), vacio si todo se entendio. */
data class MondayConverted(val employee: ImportedEmployee, val problem: String = "")

/**
 * Funciones puras del mapeo: sugerir a que columna va cada dato por su titulo y tipo (espanol e ingles) y convertir un
 * elemento de monday en un empleado a importar. Nada de red ni de base de datos.
 */
object EmpleadosMondayMapping {
    const val KeyPrefix = "monday:"
    const val DefaultJobTitle = "Técnico"

    // Columnas que nunca son un dato del empleado ni van a las notas.
    private val ignoredTypes = setOf("name", "subitems", "board_relation", "mirror", "file", "creation_log", "last_updated", "item_id", "button", "dependency", "doc", "auto_number", "formula")

    private val textTypes = setOf("text", "long_text", "status", "dropdown", "tags")

    private class Rule(val target: String, val keywords: List<String>, val allowed: Set<String> = textTypes, val fallbackType: String? = null)

    // Orden de prioridad: lo mas especifico primero.
    private val rules = listOf(
        Rule(MondayTarget.Name, listOf("tecnico", "empleado", "nombre", "name", "employee", "technician"), setOf("text")),
        Rule(MondayTarget.Site, listOf("propiedad", "sede", "property", "location", "ubicacion", "sitio", "site")),
        Rule(MondayTarget.JobTitle, listOf("puesto", "cargo", "rol", "role", "title", "position")),
        Rule(MondayTarget.Department, listOf("departamento", "area", "department")),
        Rule(MondayTarget.Days, listOf("dias de trabajo", "work days", "working days", "dias", "days")),
        Rule(MondayTarget.Hours, listOf("horario", "horarios", "turno", "hours", "horas", "schedule", "shift")),
        Rule(MondayTarget.Availability, listOf("disponibilidad", "availability", "available")),
        Rule(MondayTarget.Phone, listOf("telefono", "phone", "celular", "movil", "mobile", "tel"), setOf("phone", "text"), "phone"),
        Rule(MondayTarget.Email, listOf("correo", "email", "e mail", "mail"), setOf("email", "text"), "email"),
        Rule(MondayTarget.Notes, listOf("notas", "notes", "nota", "note", "comentarios", "comments", "observaciones"), setOf("long_text", "text")),
    )

    /** Minusculas, sin tildes ni signos: "Técnico" -> "tecnico". */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun matches(foldedTitle: String, keyword: String): Boolean {
        if (keyword.contains(' ')) return " $foldedTitle ".contains(" $keyword ")
        return foldedTitle.split(' ').any { it == keyword || (keyword.length >= 5 && it.startsWith(keyword)) }
    }

    /**
     * Departamento sugerido desde el nombre del tablero: lo que sigue a "Equipo de" ("Horario del Equipo de Mantenimiento" ->
     * "Mantenimiento") o lo que precede a "Team" ("Maintenance Team" -> "Maintenance"); vacio si no hay.
     */
    fun departmentFromBoardName(boardName: String): String {
        val name = boardName.trim()
        val spanish = Regex("""(?i)\bequipo\s+de(?:l)?\s+(.+)$""").find(name)?.groupValues?.get(1)
        val english = Regex("""(?i)(?:^|\s)([\p{L}][\p{L}\s&-]*?)\s+team\s*$""").find(name)?.groupValues?.get(1)
        val raw = (spanish ?: english).orEmpty().trim().take(120)
        return raw.replaceFirstChar { it.uppercase() }
    }

    /** Mapeo sugerido por titulo y tipo de cada columna; una columna sirve para un solo dato. Ver [MondayMapping.department]. */
    fun suggest(columns: List<MondayColumn>, boardName: String = ""): MondayMapping {
        val usable = columns.filter { it.type !in ignoredTypes }
        val used = mutableSetOf<String>()
        val result = linkedMapOf<String, String>()
        for (rule in rules) {
            fun free(predicate: (MondayColumn) -> Boolean) = usable.firstOrNull { it.id !in used && predicate(it) }
            val found = rule.keywords.firstNotNullOfOrNull { keyword ->
                free { it.type in rule.allowed && matches(fold(it.title), keyword) }
            } ?: rule.fallbackType?.let { type -> free { it.type == type } }
            if (found != null) {
                used += found.id
                result[rule.target] = found.id
            }
        }
        // Sin columna de puesto, una columna de nombre llamada "Tecnico" dice cual es el puesto de todos.
        val nameTitle = columns.firstOrNull { it.type == "name" }?.title?.let(::fold).orEmpty()
        val jobTitle = if (MondayTarget.JobTitle !in result && nameTitle in setOf("tecnico", "tecnica", "technician")) DefaultJobTitle else ""
        val department = if (MondayTarget.Department !in result) departmentFromBoardName(boardName) else ""
        return MondayMapping(result, department = department, jobTitle = jobTitle)
    }

    // ---- Elemento -> empleado ----

    private fun cell(item: MondayItem, mapping: MondayMapping, target: String): MondayCell? =
        mapping.columns[target]?.let { id -> item.cells.firstOrNull { it.columnId == id } }

    private fun text(item: MondayItem, mapping: MondayMapping, target: String): String = cell(item, mapping, target)?.text?.trim().orEmpty()

    /**
     * Convierte un elemento. Dias y horario pasan a un horario habitual (una franja por dia) si se entienden los dos; si no,
     * [ImportedEmployee.weekly] queda vacio, el texto original va a las notas y se devuelve un problema. Las columnas sin
     * destino con valor van a las notas como "Columna: valor".
     */
    fun toEmployee(item: MondayItem, mapping: MondayMapping): MondayConverted {
        val mappedIds = mapping.columns.values.toSet()
        val name = text(item, mapping, MondayTarget.Name).ifEmpty { item.name.trim() }
        val sites = text(item, mapping, MondayTarget.Site).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val daysText = text(item, mapping, MondayTarget.Days)
        val hoursText = text(item, mapping, MondayTarget.Hours)
        val notes = mutableListOf<String>()
        text(item, mapping, MondayTarget.Notes).takeIf { it.isNotEmpty() }?.let { notes += it }
        if (sites.size > 1) notes += "Otras sedes en Monday: ${sites.drop(1).joinToString(", ")}"

        var weekly: List<ImportedSlot> = emptyList()
        var problem = ""
        if (daysText.isNotEmpty() || hoursText.isNotEmpty()) {
            val days = if (daysText.isEmpty()) null else EmpleadosScheduleText.parseDays(daysText)
            val hours = if (hoursText.isEmpty()) null else EmpleadosScheduleText.parseHours(hoursText)
            if (days != null && hours != null && days.isNotEmpty()) {
                weekly = days.sorted().map { ImportedSlot(it, hours.first, hours.second) }
            } else {
                notes += "Horario en Monday: ${daysText.ifEmpty { "-" }} · ${hoursText.ifEmpty { "-" }}"
                problem = "$name: no se entendio el horario (${daysText.ifEmpty { "sin dias" }} · ${hoursText.ifEmpty { "sin horario" }}); quedo en las notas."
            }
        }
        // Las columnas sin destino con valor van a las notas como "Columna: valor".
        item.cells.filter { it.columnId !in mappedIds && it.type !in ignoredTypes && it.text.isNotBlank() }
            .forEach { notes += "${it.title.ifBlank { it.columnId }}: ${it.text.trim()}" }

        val employee = ImportedEmployee(
            key = KeyPrefix + item.id,
            fullName = name,
            jobTitle = text(item, mapping, MondayTarget.JobTitle).ifEmpty { mapping.jobTitle.trim() },
            department = text(item, mapping, MondayTarget.Department).ifEmpty { mapping.department.trim() }.ifEmpty { null },
            site = sites.firstOrNull(),
            phone = text(item, mapping, MondayTarget.Phone),
            email = text(item, mapping, MondayTarget.Email).lowercase(),
            availability = text(item, mapping, MondayTarget.Availability),
            notes = notes.joinToString("\n"),
            weekly = weekly,
            active = true,
        )
        return MondayConverted(employee, problem)
    }
}

@Serializable
data class MondaySlotDto(val weekday: Int, val start: String, val end: String)

/** Fila guardada en ext_empleados_monday__rows.payload: el empleado importado con las horas como texto ("HH:mm"). */
@Serializable
data class MondayRowDto(
    val key: String,
    val fullName: String,
    val jobTitle: String = "",
    val department: String? = null,
    val site: String? = null,
    val phone: String = "",
    val email: String = "",
    val availability: String = "",
    val notes: String = "",
    val weekly: List<MondaySlotDto> = emptyList(),
    val active: Boolean = true,
    val problem: String = "",
) {
    fun toEmployee(): ImportedEmployee = ImportedEmployee(
        key = key,
        fullName = fullName,
        jobTitle = jobTitle,
        department = department,
        site = site,
        phone = phone,
        email = email,
        availability = availability,
        notes = notes,
        weekly = weekly.mapNotNull { slot ->
            val start = runCatching { LocalTime.parse(slot.start) }.getOrNull()
            val end = runCatching { LocalTime.parse(slot.end) }.getOrNull()
            if (start == null || end == null) null else ImportedSlot(slot.weekday, start, end)
        },
        active = active,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(converted: MondayConverted): MondayRowDto {
            val employee = converted.employee
            return MondayRowDto(
                key = employee.key,
                fullName = employee.fullName,
                jobTitle = employee.jobTitle,
                department = employee.department,
                site = employee.site,
                phone = employee.phone,
                email = employee.email,
                availability = employee.availability,
                notes = employee.notes,
                weekly = employee.weekly.map { MondaySlotDto(it.weekday, it.start.toString(), it.end.toString()) },
                active = employee.active,
                problem = converted.problem,
            )
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
    val employees: Int = 0,
    val sites: List<String> = emptyList(),
    val departments: List<String> = emptyList(),
    val withSchedule: Int = 0,
    val linkedUsers: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val problemCount: Int = 0,
) {
    companion object {
        const val MaxNames = 500
        const val MaxProblems = 200

        /** Resumen del modulo mas los problemas de la conversion (horarios no entendidos) del lote. */
        fun of(summary: EmpleadosImportSummary, conversionProblems: List<String>): MondaySummaryDto {
            val problems = summary.problems + conversionProblems
            return MondaySummaryDto(
                summary.employees, summary.sites, summary.departments, summary.withSchedule, summary.linkedUsers,
                problems.take(MaxProblems), problems.size,
            )
        }

        /** Suma los resumenes de varios lotes: conteos sumados, nombres sin repetir y problemas acotados. */
        fun merge(parts: List<MondaySummaryDto>): MondaySummaryDto = MondaySummaryDto(
            employees = parts.sumOf { it.employees },
            sites = parts.flatMap { it.sites }.distinct().take(MaxNames),
            departments = parts.flatMap { it.departments }.distinct().take(MaxNames),
            withSchedule = parts.sumOf { it.withSchedule },
            linkedUsers = parts.flatMap { it.linkedUsers }.distinct().take(MaxNames),
            problems = parts.flatMap { it.problems }.take(MaxProblems),
            problemCount = parts.sumOf { it.problemCount },
        )
    }
}
