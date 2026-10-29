package com.ideasdeveloper.idc.modules.crm

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class CrmImportException(message: String) : Exception(message)

// Rows read from a source: the column names in order and each row as column -> value.
data class CrmTable(val columns: List<String>, val rows: List<Map<String, String>>)

/** Reading of CSV, XLSX and monday exports, and the automatic column mapping. */
object CrmImportParse {
    const val MaxRows = 20_000
    private const val MaxBytes = 20 * 1024 * 1024
    const val GroupColumn = "Grupo"

    // CRM fields a column can feed, with the labels shown in the mapping.
    val fields = linkedMapOf(
        "" to "No importar",
        "name" to "Nombre",
        "email" to "Correo",
        "phone" to "Telefono",
        "stage" to "Etapa",
        "interest" to "Interes",
        "owner" to "Responsable",
        "source" to "Origen",
        "language" to "Idioma",
        "message" to "Mensaje",
        "details" to "Detalles",
        "note" to "Nota",
        "followUpOn" to "Proximo seguimiento",
        "targetStart" to "Fecha de inicio",
        "targetEnd" to "Fecha de fin",
        "dates" to "Rango de fechas (inicio - fin)",
        "estimatedValue" to "Valor estimado",
        "createdAt" to "Fecha de creacion",
    )

    // ---- CSV ----

    /** CSV separated by comma, semicolon or tab (detected on the header), with quotes, BOM and multiline cells. */
    fun csv(text: String): CrmTable {
        val clean = text.removePrefix("﻿")
        if (clean.isBlank()) throw CrmImportException("El archivo esta vacio.")
        val firstLine = clean.lineSequence().first()
        val separator = listOf(',', ';', '\t').maxBy { candidate -> firstLine.count { it == candidate } }
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < clean.length) {
            val char = clean[index]
            when {
                quoted && char == '"' && clean.getOrNull(index + 1) == '"' -> { cell.append('"'); index++ }
                char == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && char == separator -> { row += cell.toString(); cell.clear() }
                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && clean.getOrNull(index + 1) == '\n') index++
                    row += cell.toString(); cell.clear()
                    records += row; row = mutableListOf()
                    if (records.size > MaxRows + 1) throw CrmImportException("El archivo tiene mas de $MaxRows filas.")
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); records += row }
        return table(records.filter { record -> record.any { it.isNotBlank() } })
    }

    // ---- XLSX ----

    private fun secureXml() = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = false
    }.newDocumentBuilder()

    /** First sheet of an XLSX file (shared strings, inline strings, numbers; DTDs rejected; sizes limited). */
    fun xlsx(bytes: ByteArray): CrmTable {
        if (bytes.size > MaxBytes) throw CrmImportException("El archivo supera los 20 MB.")
        val entries = mutableMapOf<String, ByteArray>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name in setOf("xl/sharedStrings.xml", "xl/workbook.xml", "xl/_rels/workbook.xml.rels") || entry.name.startsWith("xl/worksheets/sheet")) {
                    val content = zip.readNBytes(MaxBytes + 1)
                    expanded += content.size
                    if (content.size > MaxBytes || expanded > 4L * MaxBytes) throw CrmImportException("El archivo XLSX es demasiado grande al descomprimirlo.")
                    entries[entry.name] = content
                }
            }
        }
        val builder = secureXml()
        fun parse(name: String) = entries[name]?.let { runCatching { builder.parse(ByteArrayInputStream(it)) }.getOrElse { throw CrmImportException("El XLSX no es valido ($name).") } }
        val shared = parse("xl/sharedStrings.xml")?.getElementsByTagName("si")?.let { nodes ->
            (0 until nodes.length).map { nodes.item(it).textContent }
        }.orEmpty()
        // The first sheet of the workbook, through its relationship id.
        val firstSheet = parse("xl/workbook.xml")?.getElementsByTagName("sheet")?.item(0) as? Element
        val relId = firstSheet?.getAttribute("r:id").orEmpty()
        val target = parse("xl/_rels/workbook.xml.rels")?.getElementsByTagName("Relationship")?.let { rels ->
            (0 until rels.length).map { rels.item(it) as Element }.firstOrNull { it.getAttribute("Id") == relId }?.getAttribute("Target")
        }
        val sheetName = target?.removePrefix("/xl/")?.removePrefix("xl/")?.let { "xl/$it" }?.takeIf { it in entries }
            ?: entries.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
            ?: throw CrmImportException("El XLSX no tiene hojas.")
        val sheet = parse(sheetName)!!
        val rows = sheet.getElementsByTagName("row")
        if (rows.length > MaxRows + 1) throw CrmImportException("El archivo tiene mas de $MaxRows filas.")
        val records = (0 until rows.length).map { r ->
            val cells = (rows.item(r) as Element).getElementsByTagName("c")
            val values = sortedMapOf<Int, String>()
            (0 until cells.length).forEach { c ->
                val cell = cells.item(c) as Element
                val column = columnIndex(cell.getAttribute("r")).takeIf { it >= 0 } ?: values.size
                val raw = cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                values[column] = when (cell.getAttribute("t")) {
                    "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                    "inlineStr" -> cell.getElementsByTagName("is").item(0)?.textContent.orEmpty()
                    else -> raw
                }
            }
            val width = (values.keys.maxOrNull() ?: -1) + 1
            (0 until width).map { values[it].orEmpty() }
        }
        return table(records.filter { record -> record.any { it.isNotBlank() } })
    }

    // "C12" -> 2.
    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return -1
        return letters.fold(0) { total, letter -> total * 26 + (letter - 'A' + 1) } - 1
    }

    // ---- Layout ----

    /**
     * Turns raw records into a table. Recognizes the monday export: title lines before the header,
     * one line per group (a single cell) followed by the header again; the group goes to [GroupColumn].
     */
    fun table(records: List<List<String>>): CrmTable {
        if (records.isEmpty()) throw CrmImportException("El archivo no tiene filas.")
        // Header: the first record with at least two cells, else the first one.
        val headerIndex = records.indexOfFirst { record -> record.count { it.isNotBlank() } >= 2 }.coerceAtLeast(0)
        val header = records[headerIndex].map { it.trim() }
        val names = unique(header.mapIndexed { index, name -> name.ifEmpty { "Columna ${index + 1}" } })
        var group = records.take(headerIndex).lastOrNull { it.count { cell -> cell.isNotBlank() } == 1 }?.first { it.isNotBlank() }?.trim().orEmpty()
        val grouped = records.drop(headerIndex + 1).any { it.count { cell -> cell.isNotBlank() } == 1 }
        val rows = mutableListOf<Map<String, String>>()
        records.drop(headerIndex + 1).forEach { record ->
            val filled = record.count { it.isNotBlank() }
            when {
                record.map { it.trim() } == header -> Unit
                grouped && filled == 1 && record.firstOrNull()?.isNotBlank() == true -> group = record.first().trim()
                else -> rows += names.mapIndexed { index, name -> name to record.getOrNull(index).orEmpty().trim() }.toMap() +
                    (if (grouped || group.isNotEmpty()) mapOf(GroupColumn to group) else emptyMap())
            }
        }
        val columns = names + listOfNotNull(GroupColumn.takeIf { grouped || group.isNotEmpty() })
        return CrmTable(columns, rows)
    }

    private fun unique(names: List<String>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return names.map { name ->
            val count = seen.merge(name, 1, Int::plus)!!
            if (count == 1) name else "$name ($count)"
        }
    }

    // ---- Automatic mapping ----

    private val guesses = listOf(
        "email" to Regex("(?i)^(e-?mail|correo|email address)"),
        "phone" to Regex("(?i)(phone|tel[eé]fono|celular|m[oó]vil|whatsapp)"),
        "stage" to Regex("(?i)(status|estado|etapa|stage|pipeline)"),
        "owner" to Regex("(?i)(involved|owner|responsable|agente|agent|assigned|asignado|person)"),
        "interest" to Regex("(?i)(inquiring|inter[eé]s|interest|tipo de propiedad|property type)"),
        "source" to Regex("(?i)(referral|origen|source|canal|channel)"),
        "language" to Regex("(?i)(idioma|language)"),
        "message" to Regex("(?i)^(message|mensaje|consulta)"),
        "details" to Regex("(?i)(notes|detalles|details|comentarios)"),
        "followUpOn" to Regex("(?i)(follow ?up|seguimiento)"),
        "dates" to Regex("(?i)(booking date|fechas|timeline|estad[ií]a)"),
        "targetStart" to Regex("(?i)(check-?in|entrada|fecha de inicio|start)"),
        "targetEnd" to Regex("(?i)(check-?out|salida|fecha de fin|end)"),
        "estimatedValue" to Regex("(?i)(valor|value|budget|presupuesto|precio|price)"),
        "createdAt" to Regex("(?i)(leads date|created|creado|fecha de registro|^date$)"),
        "name" to Regex("(?i)^(name|nombre|contact|contacto|cliente|lead|full name)"),
    )

    /** Field for each column, guessed from its name; each field is used once (first column wins). */
    fun guess(columns: List<String>): Map<String, String> {
        val used = mutableSetOf<String>()
        val mapping = columns.associateWith { column ->
            guesses.firstOrNull { (field, pattern) -> field !in used && pattern.containsMatchIn(column.trim()) }?.first?.also { used += it }.orEmpty()
        }.toMutableMap()
        // The monday group is the stage when no status column was found.
        if (GroupColumn in columns && "stage" !in used) mapping[GroupColumn] = "stage"
        // Without a name column, the first column is the name (monday puts the item name first).
        if ("name" !in mapping.values && columns.isNotEmpty() && mapping[columns.first()].isNullOrEmpty()) mapping[columns.first()] = "name"
        return mapping
    }

    // ---- Values ----

    private val dateFormats = listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "MM/dd/yyyy", "M/d/yyyy", "dd-MM-yyyy", "MMM d, yyyy", "d MMM yyyy", "yyyy/MM/dd")
        .map { DateTimeFormatter.ofPattern(it, java.util.Locale.ENGLISH) }

    /** Date from the usual formats, Excel serial numbers (XLSX) or "2026-10-27 14:05" (time ignored); null when unknown. */
    fun date(raw: String): LocalDate? {
        val trimmed = raw.trim().substringBefore('T')
        val value = if (Regex("^\\d{4}-\\d{2}-\\d{2} ").containsMatchIn(trimmed)) trimmed.take(10) else trimmed
        if (value.isEmpty()) return null
        value.toDoubleOrNull()?.takeIf { it in 20000.0..80000.0 }?.let { return LocalDate.of(1899, 12, 30).plusDays(it.toLong()) }
        return dateFormats.firstNotNullOfOrNull { format -> runCatching { LocalDate.parse(value, format) }.getOrNull() }
    }

    /** Range "2026-10-25 - 2026-11-03" (monday timeline) or "Oct 25 - Nov 3" with the year of [fallbackYear]. */
    fun range(raw: String, fallbackYear: Int): Pair<LocalDate?, LocalDate?> {
        val parts = raw.split(Regex("\\s+(-|–|a|to)\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return date(raw) to null
        fun withYear(text: String) = date(text) ?: date("$text, $fallbackYear")
        val start = withYear(parts[0])
        var end = withYear(parts[1])
        if (start != null && end != null && end.isBefore(start)) end = end.plusYears(1)
        return start to end
    }
}
