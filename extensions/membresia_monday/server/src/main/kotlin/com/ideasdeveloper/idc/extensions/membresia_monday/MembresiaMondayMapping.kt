package com.ideasdeveloper.idc.extensions.membresia_monday

import com.ideasdeveloper.idc.modules.membresia.ImportedMember
import com.ideasdeveloper.idc.modules.membresia.ImportedVisit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate

/** Columna de un tablero de monday (su id estable, su titulo y su tipo: status, numbers, timeline...). */
data class MondayColumn(val id: String, val title: String, val type: String)

/** Valor de una columna en un elemento: [text] es lo que muestra monday y [value] su JSON crudo (fechas, periodos). */
data class MondayCell(val columnId: String, val title: String, val type: String, val text: String, val value: String = "")

data class MondaySubitem(val id: String, val name: String, val cells: List<MondayCell>)

/** Elemento de un tablero ya leido de la API, sin JSON de red: es lo unico que ve la conversion. */
data class MondayItem(
    val id: String,
    val name: String,
    val group: String,
    val cells: List<MondayCell>,
    val subitems: List<MondaySubitem> = emptyList(),
)

/** A que dato de la membresia va cada columna: objetivo -> id de columna de monday. */
@Serializable
data class MondayMapping(val columns: Map<String, String> = emptyMap())

/** Datos de la membresia que se pueden mapear a una columna del tablero. */
object MondayTarget {
    const val Email = "email"
    const val Phone = "phone"
    const val Plan = "plan"
    const val Period = "period"
    const val Start = "start"
    const val End = "end"
    const val Amount = "amount"
    const val GuestPasses = "guestPasses"
    const val Source = "source"
    const val Host = "host"
    const val Room = "unit"
    const val Notes = "notes"
    const val Country = "country"
    const val Document = "document"
    const val Birth = "birth"
    const val Emergency = "emergency"

    /** Objetivo, etiqueta que ve el usuario y, si va a Clientes, la etiqueta del campo personalizado. */
    class Definition(val target: String, val label: String, val customerField: String? = null)

    // En el orden en que se muestran en el formulario de mapeo.
    val all = listOf(
        Definition(Email, "Correo"),
        Definition(Phone, "Telefono"),
        Definition(Plan, "Tipo de membresia (plan)"),
        Definition(Period, "Periodo (inicio y fin)"),
        Definition(Start, "Fecha de inicio (si no hay periodo)"),
        Definition(End, "Fecha de fin (si no hay periodo)"),
        Definition(Amount, "Monto"),
        Definition(GuestPasses, "Pases de invitado"),
        Definition(Source, "Origen"),
        Definition(Host, "Anfitrion"),
        Definition(Room, "Unidad o habitacion"),
        Definition(Notes, "Notas"),
        Definition(Country, "País", CustomerCountry),
        Definition(Document, "Documento o pasaporte", CustomerDocument),
        Definition(Birth, "Fecha de nacimiento", CustomerBirth),
        Definition(Emergency, "Contacto de emergencia", CustomerEmergency),
    )

    // Etiquetas de los campos personalizados de Clientes (ensureCustomerFields crea los que falten; "nacimiento" es de tipo fecha).
    const val CustomerCountry = "País"
    const val CustomerDocument = "Documento"
    const val CustomerBirth = "Fecha de nacimiento"
    const val CustomerEmergency = "Contacto de emergencia"
}

/**
 * Funciones puras del mapeo: sugerir a que columna va cada dato por su titulo y tipo (espanol e ingles) y convertir un
 * elemento de monday en un miembro a importar. Nada de red ni de base de datos.
 */
object MembresiaMondayMapping {
    const val KeyPrefix = "monday:"
    const val MaxGuestPasses = 1000
    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
    private val guestWord = Regex("""(?iu)\b(guests?|invitad[oa]s?)\b""")
    private val json = Json { ignoreUnknownKeys = true }

    // Columnas que nunca son un dato de la membresia.
    private val ignoredTypes = setOf("name", "subitems", "board_relation", "mirror", "file", "creation_log", "last_updated", "item_id", "button", "dependency", "doc", "auto_number")

    private class Rule(val target: String, val keywords: List<String>, val allowed: Set<String> = emptySet(), val fallbackType: String? = null)

    // Orden de prioridad: lo mas especifico primero para que "Emergency Contact" no se lleve el telefono.
    private val rules = listOf(
        Rule(MondayTarget.Emergency, listOf("emergency", "emergencia")),
        Rule(MondayTarget.Email, listOf("email", "mail", "correo"), setOf("email", "text"), "email"),
        Rule(MondayTarget.Phone, listOf("phone", "telefono", "celular", "whatsapp", "movil", "tel"), setOf("phone", "text", "numbers"), "phone"),
        Rule(MondayTarget.Birth, listOf("date of birth", "fecha de nacimiento", "nacimiento", "birth", "birthday", "cumpleanos", "dob"), setOf("date", "text")),
        Rule(MondayTarget.Country, listOf("country", "pais", "nacionalidad", "nationality"), setOf("text", "dropdown", "status", "country")),
        Rule(MondayTarget.Document, listOf("passport", "pasaporte", "document", "documento", "cedula", "dni", "id number"), setOf("text", "numbers", "long_text")),
        Rule(MondayTarget.GuestPasses, listOf("guest passes", "guest pass", "pases de invitado", "pases", "invitados", "guests", "guest"), setOf("numbers", "text")),
        Rule(MondayTarget.Amount, listOf("amount", "monto", "precio", "price", "usd", "pago", "payment", "importe", "total"), setOf("numbers", "text", "formula")),
        Rule(MondayTarget.Period, listOf("periodo", "period", "vigencia"), setOf("timeline"), "timeline"),
        Rule(MondayTarget.Start, listOf("inicio", "start", "desde", "from", "begin"), setOf("date")),
        Rule(MondayTarget.End, listOf("fin", "end", "vence", "vencimiento", "expir", "expiration", "hasta", "until"), setOf("date")),
        Rule(
            MondayTarget.Plan,
            listOf("tipo de membresia", "membership type", "membership level", "nivel de membresia", "nivel", "level", "plan", "membresia", "membership", "tipo", "type"),
            setOf("status", "dropdown", "text"),
        ),
        Rule(MondayTarget.Source, listOf("source", "fuente", "origen", "canal", "channel", "referral", "referido"), setOf("status", "dropdown", "text", "long_text")),
        Rule(MondayTarget.Host, listOf("host", "anfitrion", "responsable", "assigned", "owner"), setOf("people", "text", "status", "dropdown"), "people"),
        Rule(MondayTarget.Room,listOf("room", "unit", "unidad", "habitacion", "cuarto", "desk", "escritorio", "oficina", "office"), setOf("text", "status", "dropdown", "numbers")),
        Rule(MondayTarget.Notes, listOf("notes", "notas", "note", "nota", "comments", "comentarios", "observaciones", "remarks"), setOf("long_text", "text"), "long_text"),
    )

    /** Minusculas, sin tildes ni signos: "Tipo de Membresía" -> "tipo de membresia". */
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
            if ((rule.target == MondayTarget.Start || rule.target == MondayTarget.End) && MondayTarget.Period in result) continue
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

    // ---- Lectura de valores ----

    private fun valueObject(cell: MondayCell?): JsonObject? =
        cell?.value?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun iso(text: String?): LocalDate? =
        text?.let { isoDate.find(it)?.value }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Fecha de una celda "date" (JSON {"date":...}) o, si no, la primera fecha ISO de su texto. */
    fun dateOf(cell: MondayCell?): LocalDate? =
        cell?.let { iso(valueObject(it)?.string("date")) ?: iso(it.text) }

    /** Inicio y fin de una celda "timeline" (JSON {"from":...,"to":...}); sin JSON, las dos primeras fechas del texto. */
    fun periodOf(cell: MondayCell?): Pair<LocalDate?, LocalDate?> {
        if (cell == null) return null to null
        val obj = valueObject(cell)
        val from = iso(obj?.string("from")) ?: iso(obj?.string("date"))
        val to = iso(obj?.string("to"))
        if (from != null || to != null) return from to to
        val dates = isoDate.findAll(cell.text).mapNotNull { runCatching { LocalDate.parse(it.value) }.getOrNull() }.toList()
        return dates.getOrNull(0) to dates.getOrNull(1)
    }

    /** Numero con o sin simbolo de moneda; acepta "1,234.50" y "1.234,50". Sin numero devuelve nulo. */
    fun parseAmount(text: String?): BigDecimal? {
        val cleaned = text.orEmpty().filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.trim()
        if (cleaned.none { it.isDigit() }) return null
        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')
        val normalized = when {
            lastDot >= 0 && lastComma >= 0 ->
                if (lastDot > lastComma) cleaned.replace(",", "") else cleaned.replace(".", "").replace(',', '.')
            lastComma >= 0 ->
                // Una sola coma con 1 o 2 cifras detras es decimal ("12,5"); si no, separa miles ("1,200").
                if (cleaned.count { it == ',' } == 1 && cleaned.length - lastComma - 1 in 1..2) cleaned.replace(',', '.') else cleaned.replace(",", "")
            else -> cleaned
        }
        return normalized.toBigDecimalOrNull()
    }

    private fun cell(item: MondayItem, mapping: MondayMapping, target: String): MondayCell? =
        mapping.columns[target]?.let { id -> item.cells.firstOrNull { it.columnId == id } }

    private fun text(cell: MondayCell?, jsonKey: String? = null): String =
        cell?.text?.trim().orEmpty().ifEmpty { jsonKey?.let { valueObject(cell)?.string(it) }.orEmpty() }

    /** Un grupo con "Cancel" marca la membresia como cancelada; "Expired"/"Vencid" no (las fechas deciden). */
    fun isCancelledGroup(group: String): Boolean = fold(group).split(' ').any { it.startsWith("cancel") }

    // ---- Elemento -> miembro ----

    fun toMember(item: MondayItem, mapping: MondayMapping): ImportedMember {
        val period = periodOf(cell(item, mapping, MondayTarget.Period))
        val startsOn = period.first ?: dateOf(cell(item, mapping, MondayTarget.Start))
        val endsOn = period.second ?: dateOf(cell(item, mapping, MondayTarget.End))
        val customerFields = linkedMapOf<String, String>()
        MondayTarget.all.filter { it.customerField != null }.forEach { definition ->
            val source = cell(item, mapping, definition.target)
            val value = if (definition.target == MondayTarget.Birth) dateOf(source)?.toString() ?: text(source) else text(source)
            if (value.isNotEmpty()) customerFields[definition.customerField!!] = value
        }
        return ImportedMember(
            key = KeyPrefix + item.id,
            name = item.name.trim(),
            email = text(cell(item, mapping, MondayTarget.Email), "email").lowercase(),
            phone = text(cell(item, mapping, MondayTarget.Phone), "phone"),
            customerFields = customerFields,
            planName = text(cell(item, mapping, MondayTarget.Plan)),
            startsOn = startsOn,
            endsOn = endsOn,
            amount = parseAmount(cell(item, mapping, MondayTarget.Amount)?.text),
            guestPasses = parseAmount(cell(item, mapping, MondayTarget.GuestPasses)?.text)
                ?.let { runCatching { it.intValueExact() }.getOrNull() }?.coerceIn(0, MaxGuestPasses),
            source = text(cell(item, mapping, MondayTarget.Source)),
            hostName = text(cell(item, mapping, MondayTarget.Host)),
            unit = text(cell(item, mapping, MondayTarget.Room)),
            notes = text(cell(item, mapping, MondayTarget.Notes)),
            cancelled = isCancelledGroup(item.group),
            visits = item.subitems.mapNotNull(::visitOf).distinctBy { it.key },
        )
    }

    /** Un subelemento con fecha es una visita; si su nombre o una columna dice invitado/guest, es de invitado. */
    fun visitOf(sub: MondaySubitem): ImportedVisit? {
        val date = sub.cells.firstOrNull { it.type == "date" && dateOf(it) != null }?.let(::dateOf)
            ?: sub.cells.firstOrNull { it.type == "timeline" && periodOf(it).first != null }?.let { periodOf(it).first }
            ?: sub.cells.firstNotNullOfOrNull { cell -> iso(cell.text) }
            ?: return null
        return ImportedVisit(KeyPrefix + sub.id, date, guestNameOf(sub))
    }

    private fun guestNameOf(sub: MondaySubitem): String? {
        for (cell in sub.cells) {
            if (!guestWord.containsMatchIn(cell.title) || cell.text.isBlank()) continue
            // Una casilla marcada dice "v": el invitado es el nombre del subelemento.
            if (cell.type == "checkbox") {
                if (cell.text.trim().equals("v", ignoreCase = true)) return nameWithoutGuestWord(sub.name)
            } else {
                return cell.text.trim()
            }
        }
        return if (guestWord.containsMatchIn(sub.name)) nameWithoutGuestWord(sub.name) else null
    }

    private fun nameWithoutGuestWord(name: String): String =
        guestWord.replace(name, " ").trim { it.isWhitespace() || it in ":-,()/–—" }.ifEmpty { "Invitado" }
}

/** Fila guardada en ext_membresia_monday__rows.payload: el miembro importado con fechas y monto como texto. */
@Serializable
data class MondayRowDto(
    val key: String,
    val name: String,
    val email: String = "",
    val phone: String = "",
    val customerFields: Map<String, String> = emptyMap(),
    val planName: String = "",
    val startsOn: String? = null,
    val endsOn: String? = null,
    val amount: String? = null,
    val guestPasses: Int? = null,
    val source: String = "",
    val hostName: String = "",
    val unit: String = "",
    val notes: String = "",
    val cancelled: Boolean = false,
    val visits: List<MondayVisitDto> = emptyList(),
) {
    fun toMember(): ImportedMember = ImportedMember(
        key = key, name = name, email = email, phone = phone, customerFields = customerFields, planName = planName,
        startsOn = startsOn?.let(LocalDate::parse), endsOn = endsOn?.let(LocalDate::parse), amount = amount?.let(::BigDecimal),
        guestPasses = guestPasses, source = source, hostName = hostName, unit = unit, notes = notes, cancelled = cancelled,
        visits = visits.map { ImportedVisit(it.key, LocalDate.parse(it.date), it.guestName) },
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun from(member: ImportedMember) = MondayRowDto(
            key = member.key, name = member.name, email = member.email, phone = member.phone, customerFields = member.customerFields,
            planName = member.planName, startsOn = member.startsOn?.toString(), endsOn = member.endsOn?.toString(),
            amount = member.amount?.toPlainString(), guestPasses = member.guestPasses, source = member.source, hostName = member.hostName,
            unit = member.unit, notes = member.notes, cancelled = member.cancelled,
            visits = member.visits.map { MondayVisitDto(it.key, it.date.toString(), it.guestName) },
        )

        fun encode(member: ImportedMember): String = json.encodeToString(serializer(), from(member))

        fun decode(payload: String): ImportedMember = json.decodeFromString(serializer(), payload).toMember()
    }
}

@Serializable
data class MondayVisitDto(val key: String, val date: String, val guestName: String? = null)
