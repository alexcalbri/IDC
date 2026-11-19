package com.ideasdeveloper.idc.extensions.propietarios_monday

import com.ideasdeveloper.idc.modules.propietarios.ImportedLot
import com.ideasdeveloper.idc.modules.propietarios.ImportedOwner
import com.ideasdeveloper.idc.modules.propietarios.LotImportSummary
import com.ideasdeveloper.idc.modules.propietarios.LotStatus
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
import java.time.LocalDate

/** Columna de un tablero de monday (su id estable, su titulo y su tipo: text, numbers, location, date...). */
data class MondayColumn(val id: String, val title: String, val type: String)

/** Valor de una columna en un elemento: [text] es lo que muestra monday y [value] su JSON crudo (fechas, ubicacion, archivos). */
data class MondayCell(val columnId: String, val title: String, val type: String, val text: String, val value: String = "")

/** Elemento de un tablero ya leido de la API, sin JSON de red: es lo unico que ve la conversion. */
data class MondayItem(val id: String, val name: String, val group: String, val cells: List<MondayCell>)

/** A que dato del lote va cada columna: objetivo -> id de columna de monday (o [PropietariosMondayMapping.GroupSource] / [PropietariosMondayMapping.ItemSource] para el propietario). */
@Serializable
data class MondayMapping(val columns: Map<String, String> = emptyMap())

/** Datos del lote o del titular que se pueden mapear a una columna del tablero. */
object MondayTarget {
    const val Owner = "owner"
    const val Lot = "lot"
    const val Property = "property"
    const val Register = "register"
    const val Type = "type"
    const val Area = "area"
    const val Email = "email"
    const val Phone = "phone"
    const val Status = "status"
    const val Location = "location"
    const val Start = "start"
    const val Share = "share"

    class Definition(val target: String, val label: String)

    // En el orden en que se muestran en el formulario de mapeo.
    val all = listOf(
        Definition(Owner, "Propietario"),
        Definition(Lot, "Numero de lote"),
        Definition(Property, "Propiedad (nombre)"),
        Definition(Register, "Numero de registro"),
        Definition(Type, "Tipo de propiedad"),
        Definition(Area, "Area (m2)"),
        Definition(Email, "Correo (uno o varios)"),
        Definition(Phone, "Telefono"),
        Definition(Status, "Estado del lote"),
        Definition(Location, "Ubicacion (mapa)"),
        Definition(Start, "Fecha de inicio de la titularidad"),
        Definition(Share, "Porcentaje de propiedad"),
    )
}

/** Archivo de monday de una columna de archivos: solo se guarda el id, el enlace caduca. */
data class MondayFile(val asset: String, val name: String, val column: String = "")

/** Un lote ya convertido y sus archivos por descargar al aplicarlo. */
data class MondayConverted(val lot: ImportedLot, val files: List<MondayFile>)

/**
 * Funciones puras del mapeo: sugerir a que columna va cada dato por su titulo y tipo (espanol e ingles) y convertir un
 * elemento de monday en un lote con sus titulares. Nada de red ni de base de datos.
 */
object PropietariosMondayMapping {
    const val KeyPrefix = "monday:"
    const val AssetPrefix = "monday:asset:"

    /** El propietario es el titulo del grupo del elemento (por omision). */
    const val GroupSource = "@group"

    /** El propietario es el nombre del elemento. */
    const val ItemSource = "@item"

    private val assetId = Regex("^[0-9]{1,20}$")
    private val emailPattern = Regex("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$")
    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
    private val numberPattern = Regex("""\d[\d.,]*""")
    private val json = Json { ignoreUnknownKeys = true }

    // Columnas que nunca son un dato del lote ni van a las notas.
    private val ignoredTypes = setOf("name", "subitems", "board_relation", "mirror", "file", "creation_log", "last_updated", "item_id", "button", "dependency", "doc", "auto_number", "formula")

    private val textual = setOf("text", "long_text", "numbers", "numeric", "dropdown", "status")

    private class Rule(val target: String, val keywords: List<String>, val allowed: Set<String> = emptySet(), val fallbackType: String? = null)

    // Orden de prioridad: lo mas especifico primero para que "Register Number" no se lleve la columna del lote.
    private val rules = listOf(
        Rule(MondayTarget.Register, listOf("register number", "registro", "register", "matricula", "folio"), textual),
        Rule(MondayTarget.Lot, listOf("lot number", "numero de lote", "numero lote", "no lote", "n lote", "lote", "lot", "parcela"), textual),
        Rule(MondayTarget.Type, listOf("property type", "tipo de propiedad", "tipo propiedad", "tipo", "type"), textual),
        Rule(MondayTarget.Property, listOf("property", "propiedad", "predio", "inmueble"), textual),
        Rule(MondayTarget.Area, listOf("area m2", "area", "m2", "superficie", "metros", "size", "tamano"), textual),
        Rule(MondayTarget.Email, listOf("correo electronico", "correo", "email", "e mail", "mail"), setOf("email", "text", "long_text"), "email"),
        Rule(MondayTarget.Phone, listOf("telefono", "phone", "celular", "movil", "whatsapp", "tel"), setOf("phone", "text", "numbers"), "phone"),
        Rule(MondayTarget.Share, listOf("porcentaje", "percentage", "percent", "share", "participacion"), setOf("numbers", "numeric", "text")),
        Rule(MondayTarget.Status, listOf("status", "estado", "etapa", "stage"), setOf("status", "dropdown", "text"), "status"),
        Rule(MondayTarget.Location, listOf("location", "ubicacion", "coordenadas", "coordinates", "mapa", "map"), setOf("location"), "location"),
        Rule(MondayTarget.Start, listOf("start date", "fecha de inicio", "fecha inicio", "desde", "since", "adquisicion", "compra", "purchase", "fecha", "date", "timeline"), setOf("date", "timeline"), "date"),
        Rule(MondayTarget.Owner, listOf("owner", "propietario", "propietaria", "titular", "dueno", "duena"), setOf("text", "long_text", "people", "dropdown")),
    )

    /** Minusculas, sin tildes ni signos: "Área" -> "area". */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun matches(foldedTitle: String, keyword: String): Boolean {
        if (keyword.contains(' ')) return " $foldedTitle ".contains(" $keyword ")
        return foldedTitle.split(' ').any { it == keyword || (keyword.length >= 5 && it.startsWith(keyword)) }
    }

    /**
     * Mapeo sugerido por titulo y tipo de cada columna; una columna sirve para un solo dato. El propietario es el titulo
     * del grupo salvo que haya una columna que lo diga (Owner, Propietario, Titular).
     */
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
        if (MondayTarget.Owner !in result) result[MondayTarget.Owner] = GroupSource
        return MondayMapping(result)
    }

    // ---- Estado del lote ----

    private fun words(folded: String) = folded.split(' ').filter { it.isNotEmpty() }
    private fun hasWord(folded: String, vararg candidates: String) = words(folded).any { it in candidates }
    private fun hasPrefix(folded: String, prefix: String) = words(folded).any { it.startsWith(prefix) }

    /** Estado del lote segun la etiqueta de la columna de estado; vacio o desconocido es null (se decide por tener titular o no). */
    fun statusOf(text: String?): LotStatus? {
        val folded = fold(text.orEmpty())
        return when {
            folded.isEmpty() -> null
            hasPrefix(folded, "construc") || hasPhrase(folded, "under construction") -> LotStatus.EnConstruccion
            hasPrefix(folded, "construid") || hasWord(folded, "built", "completed", "terminado", "terminada") -> LotStatus.Construido
            hasPrefix(folded, "vendid") || hasWord(folded, "sold") -> LotStatus.Vendido
            hasPrefix(folded, "reserv") -> LotStatus.Reservado
            hasPrefix(folded, "disponible") || hasWord(folded, "available", "libre", "free") -> LotStatus.Disponible
            else -> null
        }
    }

    private fun hasPhrase(folded: String, phrase: String) = " $folded ".contains(" $phrase ")

    // ---- Lectura de valores ----

    private fun valueObject(cell: MondayCell?): JsonObject? =
        cell?.value?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun iso(text: String?): LocalDate? =
        text?.let { isoDate.find(it)?.value }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Fecha de una celda "date" (JSON {"date":...}) o, si no, la primera fecha ISO de su texto. */
    fun dateOf(cell: MondayCell?): LocalDate? = cell?.let { iso(valueObject(it)?.string("date")) ?: iso(it.text) }

    /** Fecha de inicio de una celda "timeline" (JSON {"from":...,"to":...}); sin inicio, la de fin; sin JSON, la primera del texto. */
    fun timelineStartOf(cell: MondayCell?): LocalDate? {
        if (cell == null) return null
        val obj = valueObject(cell)
        val from = iso(obj?.string("from"))
        val to = iso(obj?.string("to"))
        if (from != null || to != null) return from ?: to
        return iso(cell.text)
    }

    /** Fecha de inicio de la titularidad: el inicio de una linea de tiempo o la fecha de una columna de fecha. */
    fun startOf(cell: MondayCell?): LocalDate? = if (cell?.type == "timeline") timelineStartOf(cell) else dateOf(cell)

    /**
     * Numero con coma o punto decimal: "1.234,5" y "1234.5" dan 1234.5; "1,234.5" tambien. Quita unidades (m2, %, $) y espacios.
     * Con un solo separador y tres cifras detras ("1,234") la coma es de miles; un punto solo es siempre decimal. Negativo o vacio: null.
     */
    fun parseDecimal(text: String?): BigDecimal? {
        val raw = text.orEmpty().trim()
        if (raw.startsWith("-")) return null
        // Solo el primer numero del texto: "250 m2" es 250, no 2502.
        val cleaned = numberPattern.find(raw)?.value ?: return null
        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')
        val normalized = when {
            lastDot >= 0 && lastComma >= 0 ->
                if (lastComma > lastDot) cleaned.replace(".", "").replace(',', '.') else cleaned.replace(",", "")
            lastComma >= 0 -> {
                val single = cleaned.count { it == ',' } == 1
                val decimals = cleaned.length - lastComma - 1
                if (single && decimals != 3) cleaned.replace(',', '.') else cleaned.replace(",", "")
            }
            lastDot >= 0 && cleaned.count { it == '.' } > 1 -> cleaned.replace(".", "")
            else -> cleaned
        }.trim('.')
        if (normalized.isEmpty()) return null
        return runCatching { BigDecimal(normalized).setScale(2, RoundingMode.HALF_UP) }.getOrNull()
    }

    /** Porcentaje de propiedad valido (mayor que 0 y hasta 100) o null. */
    fun shareOf(text: String?): BigDecimal? = parseDecimal(text)?.takeIf { it.signum() > 0 && it <= BigDecimal(100) }

    /** Correos de un texto con varios separados por coma, punto y coma o espacios: (validos sin repetir en minusculas, textos que no son correos). */
    fun emailsOf(text: String?): Pair<List<String>, List<String>> {
        val tokens = text.orEmpty().split(Regex("[,;\\s]+")).map { it.trim().trim('<', '>') }.filter { it.isNotEmpty() }
        val valid = tokens.filter { emailPattern.matches(it) }.map { it.lowercase() }.distinct()
        val invalid = tokens.filter { !emailPattern.matches(it) }.distinct()
        return valid to invalid
    }

    /** Latitud y longitud de una celda de ubicacion ({"lat":"19.4","lng":"-99.1","address":"..."}) dentro de rango, o null. */
    fun coordinatesOf(cell: MondayCell?): Pair<BigDecimal, BigDecimal>? {
        val obj = valueObject(cell) ?: return null
        val lat = obj.string("lat").toBigDecimalOrNull()?.setScale(6, RoundingMode.HALF_UP) ?: return null
        val lng = obj.string("lng").toBigDecimalOrNull()?.setScale(6, RoundingMode.HALF_UP) ?: return null
        if (lat.abs() > BigDecimal(90) || lng.abs() > BigDecimal(180)) return null
        return lat to lng
    }

    // ---- Elemento -> lote ----

    private fun cell(item: MondayItem, mapping: MondayMapping, target: String): MondayCell? =
        mapping.columns[target]?.takeIf { it != GroupSource && it != ItemSource }?.let { id -> item.cells.firstOrNull { it.columnId == id } }

    /** Convierte un elemento en un lote con sus titulares (uno por correo) y sus archivos. */
    fun toLot(item: MondayItem, mapping: MondayMapping): MondayConverted {
        val itemName = item.name.trim()
        val lotCell = cell(item, mapping, MondayTarget.Lot)
        val lotNumber = lotCell?.text?.trim().orEmpty().ifEmpty { itemName }
        val ownerName = when (val source = mapping.columns[MondayTarget.Owner]) {
            null, GroupSource -> item.group.trim()
            ItemSource -> itemName
            else -> item.cells.firstOrNull { it.columnId == source }?.text?.trim().orEmpty()
        }
        val (emails, invalidEmails) = emailsOf(cell(item, mapping, MondayTarget.Email)?.text)
        val phone = cell(item, mapping, MondayTarget.Phone)?.text?.trim().orEmpty()
        val share = shareOf(cell(item, mapping, MondayTarget.Share)?.text)
        val startsOn = startOf(cell(item, mapping, MondayTarget.Start))
        val owners = when {
            // Un titular por correo: el primero lleva el nombre del propietario, los demas el correo como nombre.
            emails.isNotEmpty() -> emails.mapIndexed { index, email ->
                ImportedOwner(
                    name = if (index == 0) ownerName.ifEmpty { email } else email,
                    email = email,
                    phone = if (index == 0) phone else "",
                    sharePercent = if (emails.size == 1) share else null,
                    startsOn = startsOn,
                )
            }
            ownerName.isNotEmpty() -> listOf(ImportedOwner(name = ownerName, email = "", phone = phone, sharePercent = share, startsOn = startsOn))
            else -> emptyList()
        }
        val status = cell(item, mapping, MondayTarget.Status)?.let { statusOf(it.text) }
            ?: if (owners.isNotEmpty()) LotStatus.Vendido else LotStatus.Disponible
        val coordinates = coordinatesOf(cell(item, mapping, MondayTarget.Location))
        val mappedIds = mapping.columns.values.toSet()
        val notes = buildList {
            // Columnas sin destino con valor: "Columna: valor".
            item.cells.filter { it.columnId !in mappedIds && it.type !in ignoredTypes && it.text.isNotBlank() }
                .forEach { add("${it.title.ifBlank { it.columnId }}: ${it.text.trim()}") }
            if (invalidEmails.isNotEmpty()) add("Correo (no valido): ${invalidEmails.joinToString(", ")}")
            if (emails.size > 1 && share != null) add("Porcentaje: $share")
            valueObject(cell(item, mapping, MondayTarget.Location))?.string("address")?.takeIf { it.isNotEmpty() }?.let { add("Ubicacion: $it") }
            // Si el lote viene de una columna y el nombre del elemento no es el propietario, no se pierde.
            if (lotCell != null && itemName.isNotEmpty() && itemName != lotNumber && mapping.columns[MondayTarget.Owner] != ItemSource) add("Elemento: $itemName")
        }
        val lot = ImportedLot(
            key = KeyPrefix + item.id,
            lotNumber = lotNumber,
            registerNumber = cell(item, mapping, MondayTarget.Register)?.text?.trim().orEmpty(),
            propertyName = cell(item, mapping, MondayTarget.Property)?.text?.trim().orEmpty(),
            propertyType = cell(item, mapping, MondayTarget.Type)?.text?.trim().orEmpty(),
            status = status,
            areaM2 = parseDecimal(cell(item, mapping, MondayTarget.Area)?.text),
            latitude = coordinates?.first,
            longitude = coordinates?.second,
            notes = notes.joinToString("\n"),
            owners = owners,
        )
        val files = item.cells.filter { it.type == "file" }.flatMap { fileColumn(it) }
        return MondayConverted(lot, files.distinctBy { it.asset })
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
            }
        },
    ).toString()

    fun decodeFiles(raw: String?): List<MondayFile> =
        runCatching { json.parseToJsonElement(raw.orEmpty()) as JsonArray }.getOrNull().orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            MondayFile(item.string("asset"), item.string("name"), item.string("column")).takeIf { assetId.matches(it.asset) }
        }
}

@Serializable
data class MondayOwnerDto(
    val name: String,
    val email: String = "",
    val phone: String = "",
    val sharePercent: String? = null,
    val startsOn: String? = null,
)

/** Fila guardada en ext_propietarios_monday__rows.payload: el lote importado con numeros y fechas como texto. */
@Serializable
data class MondayRowDto(
    val key: String,
    val lotNumber: String,
    val developmentName: String? = null,
    val registerNumber: String = "",
    val propertyName: String = "",
    val propertyType: String = "",
    val status: String? = null,
    val areaM2: String? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val mapUrl: String = "",
    val notes: String = "",
    val owners: List<MondayOwnerDto> = emptyList(),
) {
    fun toLot(): ImportedLot = ImportedLot(
        key = key,
        lotNumber = lotNumber,
        developmentName = developmentName,
        registerNumber = registerNumber,
        propertyName = propertyName,
        propertyType = propertyType,
        status = status?.let { LotStatus.fromCode(it) },
        areaM2 = areaM2?.toBigDecimalOrNull(),
        latitude = latitude?.toBigDecimalOrNull(),
        longitude = longitude?.toBigDecimalOrNull(),
        mapUrl = mapUrl,
        notes = notes,
        owners = owners.map { owner ->
            ImportedOwner(
                name = owner.name,
                email = owner.email,
                phone = owner.phone,
                sharePercent = owner.sharePercent?.toBigDecimalOrNull(),
                startsOn = owner.startsOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
        },
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(lot: ImportedLot) = MondayRowDto(
            key = lot.key,
            lotNumber = lot.lotNumber,
            developmentName = lot.developmentName,
            registerNumber = lot.registerNumber,
            propertyName = lot.propertyName,
            propertyType = lot.propertyType,
            status = lot.status?.code,
            areaM2 = lot.areaM2?.toPlainString(),
            latitude = lot.latitude?.toPlainString(),
            longitude = lot.longitude?.toPlainString(),
            mapUrl = lot.mapUrl,
            notes = lot.notes,
            owners = lot.owners.map { MondayOwnerDto(it.name, it.email, it.phone, it.sharePercent?.toPlainString(), it.startsOn?.toString()) },
        )

        fun encode(lot: ImportedLot): String = json.encodeToString(serializer(), of(lot))

        fun decode(payload: String): ImportedLot = json.decodeFromString(serializer(), payload).toLot()
    }
}

/**
 * Resumen de la vista previa guardado en el trabajo (jsonb). Se calcula una vez, con todas las filas, al terminar de leer,
 * y las listas se acotan para que un tablero enorme no llene la base ni la pantalla.
 */
@Serializable
data class MondaySummaryDto(
    val lots: Int = 0,
    val developments: List<String> = emptyList(),
    val owners: Int = 0,
    val ownersWithoutEmail: List<String> = emptyList(),
    val ownersWithoutEmailCount: Int = 0,
    val totalAreaM2: String = "0",
    val duplicateLots: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val problemCount: Int = 0,
    val files: Int = 0,
) {
    companion object {
        const val MaxNames = 500
        const val MaxProblems = 200

        fun of(summary: LotImportSummary, files: Int = 0) = MondaySummaryDto(
            lots = summary.lots,
            developments = summary.developments.take(MaxNames),
            owners = summary.owners,
            ownersWithoutEmail = summary.ownersWithoutEmail.take(MaxNames),
            ownersWithoutEmailCount = summary.ownersWithoutEmail.size,
            totalAreaM2 = summary.totalAreaM2.toPlainString(),
            duplicateLots = summary.duplicateLots.take(MaxNames),
            problems = summary.problems.take(MaxProblems),
            problemCount = summary.problems.size,
            files = files,
        )
    }
}
