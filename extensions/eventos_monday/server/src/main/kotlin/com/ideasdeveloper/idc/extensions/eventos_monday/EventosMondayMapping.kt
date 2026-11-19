package com.ideasdeveloper.idc.extensions.eventos_monday

import com.ideasdeveloper.idc.modules.eventos.EventStatus
import com.ideasdeveloper.idc.modules.eventos.ImportSummary
import com.ideasdeveloper.idc.modules.eventos.ImportedEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Columna de un tablero de monday (su id estable, su titulo y su tipo: status, people, date...). */
data class MondayColumn(val id: String, val title: String, val type: String)

/** Valor de una columna en un elemento: [text] es lo que muestra monday y [value] su JSON crudo (fechas con hora, personas). */
data class MondayCell(val columnId: String, val title: String, val type: String, val text: String, val value: String = "")

/** Elemento de un tablero ya leido de la API, sin JSON de red: es lo unico que ve la conversion. */
data class MondayItem(
    val id: String,
    val name: String,
    val group: String,
    val cells: List<MondayCell>,
)

/** Persona de monday resuelta con users(ids): el correo puede faltar (invitados). */
data class MondayPerson(val id: String, val name: String, val email: String)

/**
 * A que dato del evento va cada columna (objetivo -> id de columna de monday) y que espacio tiene cada grupo
 * (titulo del grupo -> espacio; un valor vacio fija "sin espacio").
 */
@Serializable
data class MondayMapping(val columns: Map<String, String> = emptyMap(), val groups: Map<String, String> = emptyMap())

/** Datos del evento que se pueden mapear a una columna del tablero. */
object MondayTarget {
    const val Contact = "contact"
    const val Email = "email"
    const val Phone = "phone"
    const val Responsible = "responsible"
    const val Message = "message"
    const val Notes = "notes"
    const val Date = "date"
    const val FollowUp = "followup"
    const val Booking = "booking"
    const val Status = "status"
    const val Amount = "amount"
    const val Guests = "guests"

    class Definition(val target: String, val label: String)

    // En el orden en que se muestran en el formulario de mapeo.
    val all = listOf(
        Definition(Contact, "Contacto (cliente)"),
        Definition(Email, "Correo del cliente"),
        Definition(Phone, "Telefono del cliente"),
        Definition(Responsible, "Responsable"),
        Definition(Date, "Fecha del evento (o linea de tiempo)"),
        Definition(Status, "Estado"),
        Definition(Amount, "Monto acordado"),
        Definition(Guests, "Invitados"),
        Definition(Message, "Mensaje (descripcion)"),
        Definition(Notes, "Notas"),
        Definition(FollowUp, "Seguimiento (va a las notas)"),
        Definition(Booking, "Fecha de reserva (va a las notas)"),
    )
}

/**
 * Funciones puras del mapeo: sugerir a que columna va cada dato por su titulo y tipo (espanol e ingles) y convertir un
 * elemento de monday en un evento privado a importar. Nada de red ni de base de datos.
 */
object EventosMondayMapping {
    const val KeyPrefix = "monday:"
    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
    private val isoDateTime = Regex("""(\d{4}-\d{2}-\d{2})[ T](\d{1,2}):(\d{2})""")
    private val personId = Regex("^[0-9]{1,20}$")
    private val json = Json { ignoreUnknownKeys = true }

    // Columnas que nunca son un dato del evento ni van a las notas.
    private val ignoredTypes = setOf("name", "subitems", "board_relation", "mirror", "file", "creation_log", "last_updated", "item_id", "button", "dependency", "doc", "auto_number", "formula")

    private class Rule(val target: String, val keywords: List<String>, val allowed: Set<String> = emptySet(), val fallbackType: String? = null)

    // Orden de prioridad: lo mas especifico primero (el correo y el telefono antes que el contacto; reserva y seguimiento antes que la fecha).
    private val rules = listOf(
        Rule(MondayTarget.Email, listOf("email", "e mail", "correo", "correo electronico", "mail"), setOf("email", "text"), "email"),
        Rule(MondayTarget.Phone, listOf("phone", "telefono", "tel", "celular", "cel", "mobile", "movil", "whatsapp"), setOf("phone", "text"), "phone"),
        Rule(MondayTarget.Contact, listOf("contact", "contacto", "client", "cliente", "customer", "contratante", "host", "anfitrion"), setOf("text", "people")),
        Rule(MondayTarget.Responsible, listOf("owner", "person", "persona", "responsable", "responsible", "assignee", "assigned", "asignado", "asignada", "encargado", "manager"), setOf("people"), "people"),
        Rule(MondayTarget.Booking, listOf("booking date", "booked on", "booked date", "reservation date", "fecha de reserva", "fecha reserva", "reserva", "reservado el"), setOf("date")),
        Rule(MondayTarget.FollowUp, listOf("follow up", "followup", "follow", "seguimiento"), setOf("date")),
        Rule(MondayTarget.Date, listOf("event date", "fecha del evento", "fecha evento", "fecha", "date", "when", "cuando", "timeline", "cronograma"), setOf("date", "timeline"), "date"),
        Rule(MondayTarget.Status, listOf("status", "estado", "state", "etapa", "stage"), setOf("status"), "status"),
        Rule(MondayTarget.Guests, listOf("guests", "guest", "invitados", "personas", "pax", "asistentes", "attendees", "headcount"), setOf("numbers")),
        Rule(MondayTarget.Amount, listOf("amount", "monto", "precio", "price", "total", "importe", "budget", "presupuesto", "cost", "costo"), setOf("numbers")),
        Rule(MondayTarget.Message, listOf("message", "mensaje", "description", "descripcion", "details", "detalles", "detalle", "consulta", "inquiry"), setOf("long_text", "text")),
        Rule(MondayTarget.Notes, listOf("notes", "notas", "note", "nota", "observaciones", "comments", "comentarios"), setOf("long_text", "text")),
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
    fun suggest(columns: List<MondayColumn>, groups: List<String> = emptyList()): MondayMapping {
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
        return MondayMapping(result, groups.associateWith(::spaceOfGroup))
    }

    // ---- Grupos y espacios ----

    private val groupNoise = setOf("evento", "eventos", "event", "events")
    private val groupNoSpace = setOf("upcoming", "booked", "proximos", "proximo", "reservados", "reservado", "pasados", "past", "archivo", "archived")

    /** Espacio que sugiere el titulo del grupo: sin "Eventos"/"Events" ("La Jolla Eventos" -> "La Jolla"); "Upcoming Events" y "Booked" no dan espacio. */
    fun spaceOfGroup(title: String): String {
        val kept = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && fold(it) !in groupNoise }
        if (kept.isEmpty() || kept.all { fold(it) in groupNoSpace || fold(it).isEmpty() }) return ""
        return kept.joinToString(" ")
    }

    /** El grupo es el de eventos reservados ("Booked" / "Reservados"): fuerza el estado confirmado. */
    fun isBookedGroup(title: String): Boolean = words(fold(title)).any { it in setOf("booked", "reservado", "reservados", "reservada", "reservadas") }

    /** Espacio de un elemento: el fijado en el mapeo para su grupo o, si no, el que sugiere el titulo del grupo. */
    fun spaceFor(group: String, mapping: MondayMapping): String =
        if (mapping.groups.containsKey(group)) mapping.groups[group].orEmpty().trim() else spaceOfGroup(group)

    // ---- Estado ----

    private fun words(folded: String) = folded.split(' ').filter { it.isNotEmpty() }
    private fun hasWord(folded: String, vararg candidates: String) = words(folded).any { it in candidates }
    private fun hasPrefix(folded: String, prefix: String) = words(folded).any { it.startsWith(prefix) }

    /**
     * Estado del evento segun la etiqueta de estado, la fecha y el grupo. Cancelado/Perdido -> Cancelled; confirmado
     * (Booked, Reservado, Confirmado; o grupo "Booked") -> Published, o Done si la fecha ya paso; Done/Realizado con fecha
     * futura -> Published; el resto es Draft (tentativo).
     */
    fun statusOf(label: String?, startsAt: LocalDateTime?, today: LocalDate, bookedGroup: Boolean = false): EventStatus {
        val folded = fold(label.orEmpty())
        val past = startsAt != null && startsAt.toLocalDate().isBefore(today)
        val cancelled = hasPrefix(folded, "cancel") || hasWord(folded, "perdido", "perdida", "lost")
        val done = hasPrefix(folded, "complet") || hasWord(folded, "done", "realizado", "realizada", "hecho", "hecha", "finalizado", "finalizada", "listo", "lista", "terminado", "terminada")
        val confirmed = bookedGroup || hasPrefix(folded, "confirm") || hasWord(folded, "booked", "reservado", "reservada")
        return when {
            cancelled -> EventStatus.Cancelled
            done -> if (past || startsAt == null) EventStatus.Done else EventStatus.Published
            confirmed -> if (past) EventStatus.Done else EventStatus.Published
            else -> EventStatus.Draft
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

    /**
     * Inicio de una celda de fecha, con hora si la trae: monday guarda la hora en UTC ({"date":"2026-11-20","time":"18:30:00"})
     * y se pasa a la zona del servidor [zone]; la hora que solo viene en el texto ("2026-11-20 18:30") ya es local. Sin hora, 00:00.
     */
    fun dateTimeOf(cell: MondayCell?, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? {
        if (cell == null) return null
        val obj = valueObject(cell)
        val date = iso(obj?.string("date"))
        if (date != null) {
            val time = obj?.string("time")?.takeIf { it.isNotEmpty() }?.let { raw -> runCatching { LocalTime.parse(raw) }.getOrNull() }
            return if (time == null) date.atStartOfDay() else LocalDateTime.of(date, time).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime()
        }
        isoDateTime.find(cell.text)?.let { match ->
            val day = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return@let
            val hour = match.groupValues[2].toInt()
            val minute = match.groupValues[3].toInt()
            if (hour in 0..23 && minute in 0..59) return LocalDateTime.of(day, LocalTime.of(hour, minute))
        }
        return iso(cell.text)?.atStartOfDay()
    }

    /** Inicio y fin de una celda de linea de tiempo (JSON {"from":...,"to":...}); sin JSON, las dos primeras fechas del texto. */
    fun timelineOf(cell: MondayCell?): Pair<LocalDateTime?, LocalDateTime?> {
        if (cell == null) return null to null
        val obj = valueObject(cell)
        val from = iso(obj?.string("from"))
        val to = iso(obj?.string("to"))
        val (start, end) = if (from != null || to != null) {
            (from ?: to) to (to ?: from)
        } else {
            val dates = isoDate.findAll(cell.text).mapNotNull { runCatching { LocalDate.parse(it.value) }.getOrNull() }.toList()
            dates.getOrNull(0) to (dates.getOrNull(1) ?: dates.getOrNull(0))
        }
        val startsAt = start?.atStartOfDay()
        val endsAt = end?.atStartOfDay()?.takeIf { startsAt != null && it.isAfter(startsAt) }
        return startsAt to endsAt
    }

    /** Inicio y fin del evento segun la celda mapeada: linea de tiempo (desde/hasta) o fecha (con hora si la trae). */
    fun startEndOf(cell: MondayCell?, zone: ZoneId = ZoneId.systemDefault()): Pair<LocalDateTime?, LocalDateTime?> =
        if (cell?.type == "timeline") timelineOf(cell) else dateTimeOf(cell, zone) to null

    /** Ids de monday de una celda de personas ({"personsAndTeams":[{"id":1,"kind":"person"}]}), con su clase. */
    fun personEntries(cell: MondayCell?): List<Pair<String, String>> {
        val array = valueObject(cell)?.get("personsAndTeams") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry.string("id").takeIf { personId.matches(it) } ?: return@mapNotNull null
            id to entry.string("kind").ifEmpty { "person" }
        }
    }

    /** Todos los ids de personas (no equipos) de varias celdas, para pedirlos a monday con users(ids). */
    fun personIds(cells: List<MondayCell>): Set<String> =
        cells.filter { it.type == "people" }.flatMap { personEntries(it) }.filter { it.second == "person" }.map { it.first }.toSet()

    /**
     * Correos y nombres de una celda de personas: con correo conocido va el correo (para emparejarla con un usuario); sin
     * correo, el nombre (queda anotado en las notas). Sin JSON valido se usan los nombres del texto de la celda.
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

    /** Monto de una celda numerica o de texto ("$1,200.50", "1.200,50"); null si no hay numero valido o pasa de 99 999 999,99. */
    fun amountOf(text: String?): BigDecimal? {
        var raw = text.orEmpty().filter { it.isDigit() || it == '.' || it == ',' }
        if (raw.none { it.isDigit() }) return null
        val lastDot = raw.lastIndexOf('.')
        val lastComma = raw.lastIndexOf(',')
        raw = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) raw.replace(",", "") else raw.replace(".", "").replace(',', '.')
            lastComma >= 0 -> if (raw.length - lastComma - 1 in 1..2 && raw.count { it == ',' } == 1) raw.replace(',', '.') else raw.replace(",", "")
            else -> raw
        }
        return runCatching { BigDecimal(raw) }.getOrNull()?.takeIf { it.signum() >= 0 && it < BigDecimal("100000000") }?.setScale(2, java.math.RoundingMode.HALF_UP)
    }

    /** Invitados: un entero de 1 a 100000 (los decimales se redondean hacia abajo); fuera de rango es null. */
    fun guestsOf(text: String?): Int? =
        amountOf(text)?.toInt()?.takeIf { it in 1..100_000 }

    // ---- Elemento -> evento ----

    private fun cell(item: MondayItem, mapping: MondayMapping, target: String): MondayCell? =
        mapping.columns[target]?.let { id -> item.cells.firstOrNull { it.columnId == id } }

    /**
     * Convierte un elemento en un evento privado importado. [today] decide si un evento confirmado ya paso; [zone] es la
     * zona en que se muestran las horas del servidor. El grupo fija el espacio y, si es "Booked", fuerza el estado confirmado.
     */
    fun toEvent(
        item: MondayItem,
        mapping: MondayMapping,
        people: Map<String, MondayPerson> = emptyMap(),
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ImportedEvent {
        val title = item.name.trim()
        val contactCell = cell(item, mapping, MondayTarget.Contact)
        val contactName: String
        var contactEmail = ""
        if (contactCell?.type == "people") {
            val (emails, names) = peopleOf(contactCell, people)
            contactEmail = emails.firstOrNull().orEmpty()
            contactName = names.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: personEntries(contactCell).firstNotNullOfOrNull { people[it.first]?.name?.trim()?.takeIf { name -> name.isNotEmpty() } }.orEmpty()
        } else {
            contactName = contactCell?.text?.trim().orEmpty()
        }
        val email = cell(item, mapping, MondayTarget.Email)?.text?.trim().orEmpty().ifEmpty { contactEmail }
        val phone = cell(item, mapping, MondayTarget.Phone)?.text?.trim().orEmpty()
        val (responsibleEmails, responsibleNames) = peopleOf(cell(item, mapping, MondayTarget.Responsible), people)
        val (startsAt, endsAt) = startEndOf(cell(item, mapping, MondayTarget.Date), zone)
        val status = statusOf(cell(item, mapping, MondayTarget.Status)?.text, startsAt, today, isBookedGroup(item.group))
        val mappedIds = mapping.columns.values.toSet()
        // Las columnas sin destino con valor van a las notas como "Columna: valor".
        val extras = item.cells
            .filter { it.columnId !in mappedIds && it.type !in ignoredTypes && it.text.isNotBlank() }
            .map { "${it.title.ifBlank { it.columnId }}: ${it.text.trim()}" }
        val followUp = dateOf(cell(item, mapping, MondayTarget.FollowUp))?.let { "Seguimiento: $it" }
        val booked = dateOf(cell(item, mapping, MondayTarget.Booking))?.let { "Reservado el: $it" }
        val notes = (listOf(cell(item, mapping, MondayTarget.Notes)?.text?.trim().orEmpty(), followUp.orEmpty(), booked.orEmpty()) + extras)
            .filter { it.isNotEmpty() }.joinToString("\n")
        return ImportedEvent(
            key = KeyPrefix + item.id,
            title = title,
            description = cell(item, mapping, MondayTarget.Message)?.text?.trim().orEmpty(),
            notes = notes,
            space = spaceFor(item.group.trim(), mapping),
            startsAt = startsAt,
            endsAt = endsAt,
            status = status,
            clientName = contactName.ifEmpty { title },
            clientEmail = email,
            clientPhone = phone,
            responsibleEmail = responsibleEmails.firstOrNull(),
            responsibleName = if (responsibleEmails.isEmpty()) responsibleNames.firstOrNull() else null,
            agreedAmount = amountOf(cell(item, mapping, MondayTarget.Amount)?.text),
            guests = guestsOf(cell(item, mapping, MondayTarget.Guests)?.text),
        )
    }
}

/** Fila guardada en ext_eventos_monday__rows.payload: el evento importado con estado y fechas como texto. */
@Serializable
data class MondayRowDto(
    val key: String,
    val title: String,
    val description: String = "",
    val notes: String = "",
    val category: String = "",
    val space: String = "",
    val startsAt: String? = null,
    val endsAt: String? = null,
    val status: String = EventStatus.Draft.code,
    val clientName: String = "",
    val clientEmail: String = "",
    val clientPhone: String = "",
    val responsibleEmail: String? = null,
    val responsibleName: String? = null,
    val agreedAmount: String? = null,
    val guests: Int? = null,
) {
    fun toEvent(): ImportedEvent = ImportedEvent(
        key = key,
        title = title,
        description = description,
        notes = notes,
        category = category,
        space = space,
        startsAt = startsAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
        endsAt = endsAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
        status = EventStatus.entries.firstOrNull { it.code == status } ?: EventStatus.Draft,
        clientName = clientName,
        clientEmail = clientEmail,
        clientPhone = clientPhone,
        responsibleEmail = responsibleEmail,
        responsibleName = responsibleName,
        agreedAmount = agreedAmount?.let { runCatching { BigDecimal(it) }.getOrNull() },
        guests = guests,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(event: ImportedEvent) = MondayRowDto(
            key = event.key,
            title = event.title,
            description = event.description,
            notes = event.notes,
            category = event.category,
            space = event.space,
            startsAt = event.startsAt?.toString(),
            endsAt = event.endsAt?.toString(),
            status = event.status.code,
            clientName = event.clientName,
            clientEmail = event.clientEmail,
            clientPhone = event.clientPhone,
            responsibleEmail = event.responsibleEmail,
            responsibleName = event.responsibleName,
            agreedAmount = event.agreedAmount?.toPlainString(),
            guests = event.guests,
        )

        fun encode(event: ImportedEvent): String = json.encodeToString(serializer(), of(event))

        fun decode(payload: String): ImportedEvent = json.decodeFromString(serializer(), payload).toEvent()
    }
}

/**
 * Resumen de la vista previa guardado en el trabajo (jsonb). Se calcula una vez, por lotes, al terminar de leer, y las
 * listas se acotan para que un tablero enorme no llene la base ni la pantalla.
 */
@Serializable
data class MondaySummaryDto(
    val events: Int = 0,
    val newCustomers: Int = 0,
    val matchedPeople: List<String> = emptyList(),
    val unmatchedPeople: List<String> = emptyList(),
    val bySpace: Map<String, Int> = emptyMap(),
    val problems: List<String> = emptyList(),
    val problemCount: Int = 0,
) {
    fun toSummary() = ImportSummary(events, newCustomers, matchedPeople, unmatchedPeople, bySpace, problems)

    companion object {
        const val MaxNames = 500
        const val MaxProblems = 200

        fun of(summary: ImportSummary) = MondaySummaryDto(
            summary.events, summary.newCustomers, summary.matchedPeople, summary.unmatchedPeople, summary.bySpace,
            summary.problems, summary.problems.size,
        )

        /** Suma los resumenes de varios lotes: conteos sumados, nombres sin repetir y problemas acotados. */
        fun merge(parts: List<MondaySummaryDto>): MondaySummaryDto = MondaySummaryDto(
            events = parts.sumOf { it.events },
            newCustomers = parts.sumOf { it.newCustomers },
            matchedPeople = parts.flatMap { it.matchedPeople }.distinct().take(MaxNames),
            unmatchedPeople = parts.flatMap { it.unmatchedPeople }.distinct().take(MaxNames),
            bySpace = parts.flatMap { it.bySpace.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() },
            problems = parts.flatMap { it.problems }.take(MaxProblems),
            problemCount = parts.sumOf { it.problemCount },
        )
    }
}
