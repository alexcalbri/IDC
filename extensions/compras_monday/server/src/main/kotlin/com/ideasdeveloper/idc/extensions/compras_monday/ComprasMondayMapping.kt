package com.ideasdeveloper.idc.extensions.compras_monday

import com.ideasdeveloper.idc.modules.compras.ComprasMoney
import com.ideasdeveloper.idc.modules.compras.ComprasValidationException
import com.ideasdeveloper.idc.modules.compras.ImportedFile
import com.ideasdeveloper.idc.modules.compras.ImportedQuote
import com.ideasdeveloper.idc.modules.compras.ImportedRequest
import com.ideasdeveloper.idc.modules.compras.Priority
import com.ideasdeveloper.idc.modules.compras.RequestDraft
import com.ideasdeveloper.idc.modules.compras.RequestStatus
import java.io.InputStream
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate

/** Campos de la solicitud que se pueden mapear a una columna de monday (el titulo es siempre el nombre del elemento). */
object MondayFields {
    const val Description = "description"
    const val Department = "department"
    const val Amount = "amount"
    const val RequiredDate = "required_date"
    const val Requester = "requester"
    const val Priority = "priority"
    const val Status = "status"
    const val Files = "files"
    const val QuotedBy = "quoted_by"
    const val Supplier = "supplier"

    /** Moneda de la importacion; va junto al mapeo pero no es una columna. */
    const val Currency = "currency"

    val All = listOf(Status, Priority, Amount, RequiredDate, Files, QuotedBy, Supplier, Requester, Department, Description)

    fun label(field: String): String = when (field) {
        Description -> "Descripcion"
        Department -> "Departamento"
        Amount -> "Monto"
        RequiredDate -> "Fecha requerida"
        Requester -> "Solicitante"
        Priority -> "Prioridad"
        Status -> "Estado"
        Files -> "Archivos"
        QuotedBy -> "Cotizado por"
        Supplier -> "Proveedor"
        else -> field
    }
}

/** Resultado de convertir un elemento: la solicitud (nula si no se puede importar) y los avisos para mostrar con la fila. */
data class MondayConversion(val request: ImportedRequest?, val problems: List<String>)

/** Mapeo de columnas, de estados y de prioridades, y construccion de la solicitud importada. Todo puro, sin red ni base de datos. */
object ComprasMondayMapping {
    const val DefaultCurrency = "USD"
    const val DefaultQuoteSupplier = "Cotizacion de Monday"
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("[\\s_-]+")
    private val boardPrefix = Regex("^\\s*purchase\\s*&\\s*project\\s*requests\\s*[-–—:]?\\s*", RegexOption.IGNORE_CASE)
    private val email = Regex("[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+")

    /** Minusculas, sin tildes y con los separadores unificados ("Aprobado-en progreso" -> "aprobado en progreso"). */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase().replace(spaces, " ").trim()

    // ---- Estados y prioridades ----

    private val statuses: Map<String, RequestStatus> = buildMap {
        listOf("pending", "pendiente", "pendiente de aprobacion", "pending approval", "por aprobar").forEach { put(it, RequestStatus.PendingApproval) }
        listOf("approved", "aprobado", "aprobada").forEach { put(it, RequestStatus.Approved) }
        listOf("aprobado en progreso", "approved in progress", "in progress", "en progreso", "working on it", "comprado", "comprada")
            .forEach { put(it, RequestStatus.InProgress) }
        listOf("completed", "completado", "completada", "done", "listo", "lista").forEach { put(it, RequestStatus.Completed) }
        listOf("rechazado", "rechazada", "rejected").forEach { put(it, RequestStatus.Rejected) }
        listOf("en cotizacion", "quoting", "cotizando").forEach { put(it, RequestStatus.Quoting) }
        listOf("cancelado", "cancelada", "cancelled", "canceled").forEach { put(it, RequestStatus.Cancelled) }
        listOf("borrador", "draft").forEach { put(it, RequestStatus.Draft) }
    }

    /** Estado de monday -> estado de Compras, con el aviso a mostrar cuando no es una correspondencia clara. */
    fun mapStatus(raw: String): Pair<RequestStatus, String?> {
        val key = normalize(raw)
        return when {
            key.isEmpty() -> RequestStatus.Draft to null
            key == "stuck" -> RequestStatus.PendingApproval to "Estado Stuck tratado como pendiente de aprobacion"
            else -> statuses[key]?.let { it to null } ?: (RequestStatus.Draft to "Estado desconocido: ${raw.trim().take(60)}")
        }
    }

    fun mapPriority(raw: String): Pair<Priority, String?> {
        val priority = when (normalize(raw)) {
            "" -> return Priority.Medium to null
            "low", "baja" -> Priority.Low
            "medium", "media", "normal" -> Priority.Medium
            "high", "alta" -> Priority.High
            "critical", "critica", "urgent", "urgente" -> Priority.Critical
            else -> return Priority.Medium to "Prioridad desconocida: ${raw.trim().take(60)}"
        }
        return priority to null
    }

    // ---- Sugerencia de columnas ----

    private class Rule(val field: String, val types: Set<String>, val names: List<String>, val fallbackToType: Boolean = false, val excluded: List<String> = emptyList())

    private val rules = listOf(
        Rule(MondayFields.Status, setOf("status"), listOf("estado", "status", "aprobacion", "approval")),
        Rule(MondayFields.Priority, setOf("status", "dropdown"), listOf("prioridad", "priority")),
        // "Monto aprobado" es lo que se aprobo, no lo que se pidio: no es el estimado.
        Rule(MondayFields.Amount, setOf("numbers"), listOf("price", "precio", "cotizacion", "usd", "monto", "amount", "costo", "cost"), excluded = listOf("aprobado", "aprobada", "approved")),
        Rule(MondayFields.RequiredDate, setOf("date"), listOf("fecha requerida", "required", "fecha limite", "due")),
        Rule(MondayFields.Files, setOf("file"), listOf("archivos", "files", "factura", "cotizacion", "adjuntos"), fallbackToType = true),
        Rule(MondayFields.QuotedBy, setOf("people", "text"), listOf("cotizado por", "quoted by")),
        Rule(MondayFields.Supplier, setOf("text"), listOf("proveedor", "supplier", "vendor")),
        Rule(MondayFields.Requester, setOf("people", "text"), listOf("solicitante", "requester", "solicitado por", "requested by")),
        Rule(MondayFields.Department, setOf("status", "dropdown", "text"), listOf("departamento", "department")),
        Rule(MondayFields.Description, setOf("long_text", "text"), listOf("descripcion", "description", "detalle")),
    )

    /**
     * Columna sugerida para cada campo (por titulo y tipo); un campo sin candidata no aparece. Una columna no se usa dos veces.
     * Primero se asignan todas las coincidencias exactas del titulo, despues las que lo contienen y al final el respaldo por tipo,
     * de modo que una coincidencia parcial nunca le quita la columna a quien la nombra exacta.
     */
    fun suggest(columns: List<MondayColumn>): Map<String, String> {
        val used = mutableSetOf<String>()
        val result = linkedMapOf<String, String>()
        fun candidates(rule: Rule) = columns
            .filter { it.type in rule.types && it.id !in used }
            .map { it to normalize(it.title) }
            .filter { (_, title) -> rule.excluded.none { title.contains(it) } }
        fun assign(rule: Rule, column: MondayColumn?) {
            if (column != null && rule.field !in result) {
                used += column.id
                result[rule.field] = column.id
            }
        }
        rules.forEach { rule -> assign(rule, candidates(rule).firstOrNull { (_, title) -> title in rule.names }?.first) }
        rules.filter { it.field !in result }.forEach { rule ->
            assign(rule, candidates(rule).firstOrNull { (_, title) -> rule.names.any { name -> title.contains(name) } }?.first)
        }
        rules.filter { it.field !in result && it.fallbackToType }.forEach { rule -> assign(rule, candidates(rule).firstOrNull()?.first) }
        return result
    }

    // ---- Datos sueltos ----

    /** Departamento por defecto: el nombre del tablero sin "Purchase & Project Requests -" (Hotel, F&B, Esc, Maintenance...). */
    fun departmentFromBoard(boardName: String): String? = boardName.replace(boardPrefix, "").trim().take(100).ifEmpty { null }

    private val currencyCode = Regex("\\b[A-Za-z]{3}\\b")
    private val currencyMarks = Regex("[$€£\\s]")

    /**
     * Monto escrito por monday ("1,234.5", "$1,234.50", "USD 12"): se quitan el codigo de moneda y los simbolos, y se lee con las
     * reglas de Compras. Devuelve el monto y el aviso: monto nulo y sin aviso si esta vacio; con aviso si es cero, trae texto que no
     * es moneda o no es un monto valido.
     */
    fun parseAmount(raw: String?): Pair<BigDecimal?, String?> {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return null to null
        val cleaned = text.replace(currencyCode, "").replace(currencyMarks, "")
        if (cleaned.any { it.isLetter() } || cleaned.none { it.isDigit() }) return null to "El monto trae texto no reconocido: ${text.take(40)}"
        val amount = try {
            ComprasMoney.parseOptional(cleaned)
        } catch (failure: ComprasValidationException) {
            return null to "Monto no valido (${text.take(40)}): ${failure.message}"
        }
        if (amount == null || amount.signum() == 0) return null to "El monto es 0"
        return amount to null
    }

    private val extensionTypes = mapOf(
        "pdf" to "application/pdf", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "txt" to "text/plain", "csv" to "text/csv", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )

    /** Tipo de contenido por la extension del nombre (la descarga de monday suele llegar como octet-stream). */
    fun contentTypeFor(fileName: String): String =
        extensionTypes[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /** Tipo de respaldo por el nombre: facturas y proformas se reconocen, lo demas es respaldo. */
    fun kindFor(fileName: String): String {
        val name = normalize(fileName)
        return when {
            name.contains("factura") || name.contains("invoice") -> "invoice"
            name.contains("proforma") -> "proforma"
            else -> "support"
        }
    }

    private fun firstName(text: String): String = text.split(',', ';').map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    // ---- Construccion de la solicitud ----

    /**
     * Arma la solicitud a importar desde un elemento de monday. [mapping] lleva una columna por campo ([MondayFields]) y la moneda
     * ([MondayFields.Currency]). [fallbackRequester] es el solicitante cuando monday no trae uno. [opener] abre el contenido de un
     * asset al importar (no se llama aqui). Lo dudoso no detiene la importacion: queda como aviso en la fila.
     */
    fun convert(
        item: MondayItem,
        boardName: String,
        mapping: Map<String, String>,
        fallbackRequester: String,
        opener: (asset: String) -> InputStream?,
    ): MondayConversion {
        val problems = mutableListOf<String>()
        val title = item.name.trim().take(RequestDraft.MaxTitle)
        if (title.isEmpty()) return MondayConversion(null, listOf("El elemento no tiene nombre."))
        fun text(field: String): String = mapping[field]?.takeIf { it.isNotBlank() }?.let { item.columns[it]?.text }.orEmpty().trim()

        val currency = runCatching { ComprasMoney.currency(mapping[MondayFields.Currency]) }.getOrDefault(DefaultCurrency)
        val (status, statusProblem) = mapStatus(text(MondayFields.Status))
        statusProblem?.let { problems += it }
        val (priority, priorityProblem) = mapPriority(text(MondayFields.Priority))
        priorityProblem?.let { problems += it }
        val (amount, amountProblem) = parseAmount(text(MondayFields.Amount))
        amountProblem?.let { problems += it }
        val requiredDate = text(MondayFields.RequiredDate).take(10).takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDate.parse(it) }.getOrElse {
                problems += "Fecha requerida no valida: ${text(MondayFields.RequiredDate).take(40)}"
                null
            }
        }
        val requester = firstName(text(MondayFields.Requester))
        val requesterEmail = email.find(text(MondayFields.Requester))?.value
        val department = text(MondayFields.Department).take(100).ifEmpty { null } ?: departmentFromBoard(boardName)
        val quotedBy = firstName(text(MondayFields.QuotedBy))

        // Con "cotizado por" y un monto, el monto es una cotizacion elegida (tablero de mantenimiento); si no, es el estimado.
        val hasQuote = amount != null && quotedBy.isNotEmpty() && mapping[MondayFields.QuotedBy].orEmpty().isNotBlank()
        val filesColumn = mapping[MondayFields.Files].orEmpty()
        val assets = item.files.filter { filesColumn.isNotEmpty() && it.column == filesColumn }.distinctBy { it.asset }
        fun importedFile(file: MondayItemFile, kind: String) =
            ImportedFile("monday:asset:${file.asset}", file.name, contentTypeFor(file.name), kind) { opener(file.asset) }

        val quotes = if (hasQuote) {
            listOf(
                ImportedQuote(
                    key = "monday:${item.id}:quote",
                    supplier = text(MondayFields.Supplier).take(200).ifEmpty { DefaultQuoteSupplier },
                    amount = amount!!,
                    currency = currency,
                    selected = true,
                    quotedByName = quotedBy.take(200),
                    files = assets.map { importedFile(it, "quote") },
                ),
            )
        } else emptyList()

        return MondayConversion(
            ImportedRequest(
                key = "monday:${item.id}",
                title = title,
                description = text(MondayFields.Description).take(RequestDraft.MaxDescription),
                department = department,
                estimatedAmount = if (hasQuote) null else amount,
                currency = currency,
                requiredDate = requiredDate,
                priority = priority,
                status = status,
                requesterName = requester.ifEmpty { fallbackRequester },
                requesterEmail = requesterEmail,
                decisionReason = if (status == RequestStatus.Rejected) "Rechazada en monday" else null,
                createdAt = runCatching { Instant.parse(item.createdAt) }.getOrNull(),
                quotes = quotes,
                files = if (hasQuote) emptyList() else assets.map { importedFile(it, kindFor(it.name)) },
            ),
            problems,
        )
    }
}
