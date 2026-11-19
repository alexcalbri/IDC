package com.ideasdeveloper.idc.extensions.inmobiliaria_monday

import com.ideasdeveloper.idc.modules.crm.CrmImportKind
import com.ideasdeveloper.idc.modules.crm.CrmImportRecord
import java.text.Normalizer
import java.util.UUID

/** Error de la importacion con un mensaje seguro para mostrar al usuario (nunca lleva el token). */
open class InmobiliariaMondayException(message: String) : Exception(message)

/** Error pasajero de monday (red, 429, 5xx, limite de complejidad): la lectura se reintenta en la siguiente corrida. */
class InmobiliariaMondayTransientException(message: String) : InmobiliariaMondayException(message)

data class MondayBoard(val id: String, val name: String, val workspace: String, val items: Int?)

/** Columna de un tablero: [title] es la clave con la que se guarda su valor en cada fila (sin repetidos). */
data class MondayColumn(val id: String, val title: String, val type: String)

/** Un elemento del tablero ya convertido: valores por titulo de columna. */
data class MondayItem(val id: String, val values: Map<String, String>)

/** Estados de una importacion (columna state de la tabla de jobs). */
object MondayJobState {
    const val Reading = "reading"
    const val Ready = "ready"
    const val Applying = "applying"
    const val Done = "done"
    const val Failed = "failed"
}

/** Estados de una fila leida (columna state de la tabla de filas). */
object MondayRowState {
    const val Pending = "pending"
    const val Applied = "applied"
    const val Failed = "failed"
}

data class MondayJob(
    val id: UUID,
    val boardId: String,
    val boardName: String,
    val kind: String,
    val newListings: Boolean,
    val mapping: Map<String, String>,
    val columns: List<MondayColumn>,
    val state: String,
    val readStarted: Boolean,
    val readCursor: String,
    val rowsRead: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    val problems: List<String>,
    val error: String,
    val createdBy: UUID?,
    val createdByName: String,
    val appliedBy: UUID? = null,
    val appliedByName: String = "",
    val readAttempts: Int = 0,
)

/** Una fila leida, con los valores de monday por titulo de columna. */
data class MondayRow(val id: Long, val itemId: String, val importKey: String, val data: Map<String, String>, val state: String, val problem: String)

/** Resultado de una fila tras aplicar un lote: su estado y el texto (el error si fallo; el aviso, si lo hay, si se guardo). */
data class MondayRowVerdict(val row: MondayRow, val state: String, val text: String)

/** Que hacer despues de leer una pagina del tablero. */
enum class ReadStep { Finish, Pause, Continue }

/** Reglas puras de la importacion: clave de importacion, mapeo sugerido y conversion de filas a registros. */
object MondayMapping {
    const val NameColumn = "Nombre"
    const val GroupColumn = "Grupo"
    const val KindProperty = "property"
    const val KindDeal = "deal"
    const val IntakeField = "intake"

    /** Tope de filas por importacion, de problemas guardados y tamano de cada lote al aplicar. */
    const val MaxRows = 20_000
    const val MaxProblems = 200
    const val BatchSize = 100

    /** Topes al leer: texto de cada valor de columna y nombre del elemento. */
    const val MaxValueChars = 5000
    const val MaxNameChars = 300

    /** Intentos de lectura con error pasajero antes de dar la importacion por fallida. */
    const val MaxReadAttempts = 5

    /** Con menos tiempo que esto en la corrida no se lanza otra consulta a monday. */
    const val MinQueryMillis = 3_000L

    /** Tope de problemas y de largo de cada uno que devuelve la herramienta MCP de estado, y de filas de su vista previa. */
    const val McpMaxProblems = 50
    const val McpProblemChars = 300
    const val McpPreviewRows = 5
    const val McpPreviewChars = 200

    private val diacritics = Regex("\\p{M}+")
    private val apostrophes = Regex("[’`']")
    private val separators = Regex("[^a-z0-9]+")

    /** Clave estable del elemento: reimportar el mismo elemento actualiza, nunca duplica. */
    fun importKey(itemId: String): String = "monday:$itemId"

    /** Titulo sin tildes, en minusculas y solo con letras y numeros separados por un espacio (Seller's Lawyer pasa a sellers lawyer). */
    fun normalize(title: String): String {
        val plain = Normalizer.normalize(title, Normalizer.Form.NFD).replace(diacritics, "")
        return plain.lowercase().replace("%", " pct ").replace(apostrophes, "").replace(separators, " ").trim()
    }

    // Titulos normalizados (ingles y espanol) que se sugieren para cada campo, por tipo de registro. El orden de los campos desempata.
    private val propertyRules: List<Pair<String, Set<String>>> = listOf(
        "title" to setOf("nombre", "name", "item", "property", "propiedad", "title", "titulo", "listing", "address", "direccion"),
        "code" to setOf("code", "codigo", "mls", "ref", "reference", "referencia"),
        "kind" to setOf("type", "tipo", "property type", "tipo de propiedad"),
        "zone" to setOf("neighborhood", "barrio", "zone", "zona", "area", "location", "ubicacion", "sector"),
        "development" to setOf("development", "desarrollo", "proyecto", "project"),
        "status" to setOf("status", "estado", "estatus"),
        "agent" to setOf("agent", "agente", "listing agent", "agente del listado"),
        "owner_name" to setOf("seller", "vendedor", "owner", "propietario", "dueno", "owner name", "seller name"),
        "owner_email" to setOf("seller email", "owner email", "email", "correo", "seller e mail", "owner e mail"),
        "owner_phone" to setOf("seller phone", "owner phone", "phone", "telefono"),
        "lot_area" to setOf("lot size", "lot area", "lot", "terreno", "land size", "land area", "area de terreno", "lot m2"),
        "built_area" to setOf("construction", "construccion", "built area", "building size", "construction size", "area construida"),
        "bedrooms" to setOf("bedrooms", "bedroom", "beds", "habitaciones", "recamaras", "cuartos", "dormitorios"),
        "bathrooms" to setOf("baths", "bathrooms", "bathroom", "banos"),
        "price" to setOf("price", "new price", "precio", "precio nuevo", "list price", "asking price"),
        "previous_price" to setOf("old price", "previous price", "precio anterior", "precio viejo", "original price"),
        "currency" to setOf("currency", "moneda"),
        "description" to setOf("description", "descripcion"),
        "notes" to setOf("comments", "comentarios", "notes", "notas"),
        "link_website" to setOf("website", "web", "web site", "sitio web", "english website", "website en"),
        "link_website_es" to setOf("spanish website", "website es", "sitio web espanol", "web espanol", "sitio web es"),
        "link_craigslist" to setOf("craigslist"),
        "link_encuentra24" to setOf("encuentra24", "encuentra 24"),
        "link_drive" to setOf("google drive", "drive", "gdrive"),
        "link_other" to setOf("link", "url", "enlace", "other link", "otro enlace"),
    )

    private val dealRules: List<Pair<String, Set<String>>> = listOf(
        "property" to setOf("nombre", "name", "item", "property", "propiedad", "listing", "address", "direccion"),
        "buyer_name" to setOf("buyer name", "lead", "client", "cliente", "nombre del comprador"),
        "buyer_email" to setOf("buyer email", "email", "correo", "correo del comprador"),
        "stage" to setOf("stage", "etapa", "deal stage", "status", "estado", "estatus"),
        "agreed_price" to setOf("price", "agreed price", "sale price", "sold price", "closing price", "precio", "precio acordado", "precio de venta"),
        "commission_pct" to setOf("commission pct", "commission percent", "commission percentage", "commission rate", "comision pct", "porcentaje de comision"),
        "commission_amount" to setOf("commission", "commission amount", "comision", "monto de comision"),
        "estimated_close_on" to setOf("estimated closing", "estimated closing date", "estimated close", "est closing", "cierre estimado", "fecha estimada de cierre"),
        "closed_on" to setOf("closed on", "closing date", "closed date", "close date", "date closed", "fecha de cierre"),
        "comments" to setOf("comments", "comentarios", "notes", "notas"),
        "seller" to setOf("seller", "vendedor"),
        "seller_agent" to setOf("seller agent", "sellers agent", "listing agent", "agente vendedor"),
        "seller_lawyer" to setOf("seller lawyer", "sellers lawyer", "lawyer", "abogado vendedor", "abogado del vendedor", "abogado"),
        "seller_referral" to setOf("seller referral", "referral", "referido", "referido del vendedor"),
        "buyer" to setOf("buyer", "comprador"),
        "buyer_agent" to setOf("buyer agent", "buyers agent", "agente comprador"),
        "buyer_lawyer" to setOf("buyer lawyer", "buyers lawyer", "abogado comprador", "abogado del comprador"),
        "agents" to setOf("agent", "agents", "agente", "agentes"),
        "brokers" to setOf("broker", "brokers", "corredor", "corredores"),
    )

    /**
     * Mapeo sugerido por el titulo de cada columna (ingles o espanol): una columna va a un solo campo y un campo recibe una
     * sola columna; solo se proponen campos de [validKeys] (los del tipo de registro que acepta la extension destino).
     */
    fun suggest(kind: String, columns: List<MondayColumn>, validKeys: Set<String>): Map<String, String> {
        val rules = if (kind == KindDeal) dealRules else propertyRules
        val used = mutableSetOf<String>()
        val result = linkedMapOf<String, String>()
        columns.forEach { column ->
            val title = normalize(column.title)
            val field = rules.firstOrNull { (key, names) -> key in validKeys && key !in used && title in names }?.first ?: return@forEach
            used += field
            result[column.title] = field
        }
        return result
    }

    /** Etiquetas de los campos obligatorios del tipo de registro que el mapeo no cubre. */
    fun missingRequired(kind: CrmImportKind, mapping: Map<String, String>): List<String> =
        kind.fields.filter { it.required && it.key !in mapping.values }.map { it.label }

    /** Valida un mapeo recibido de fuera: solo columnas conocidas y campos del tipo; devuelve el mapeo limpio. */
    fun checkMapping(kind: CrmImportKind, columns: List<MondayColumn>, mapping: Map<String, String>): Map<String, String> {
        val titles = columns.map { it.title }.toSet()
        val keys = kind.fields.map { it.key }.toSet()
        val clean = linkedMapOf<String, String>()
        mapping.forEach { (column, field) ->
            if (field.isBlank()) return@forEach
            if (column !in titles) throw InmobiliariaMondayException("El tablero no tiene la columna '${column.take(80)}'.")
            if (field !in keys) throw InmobiliariaMondayException("'${field.take(60)}' no es un campo de ${kind.label}.")
            clean[column] = field
        }
        val repeated = clean.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (repeated.isNotEmpty()) throw InmobiliariaMondayException("Cada campo recibe una sola columna; se repite: ${repeated.joinToString()}.")
        return clean
    }

    /** Valores del registro de una fila segun el mapeo; en tableros de nuevos listados las propiedades llevan intake = true. */
    fun values(row: Map<String, String>, mapping: Map<String, String>, kind: CrmImportKind, newListings: Boolean): Map<String, String> {
        val values = linkedMapOf<String, String>()
        mapping.forEach { (column, field) ->
            val text = row[column].orEmpty().trim()
            if (text.isNotEmpty() && field !in values) values[field] = text
        }
        if (newListings && kind.id == KindProperty && kind.fields.any { it.key == IntakeField }) values[IntakeField] = "true"
        return values
    }

    fun record(row: MondayRow, mapping: Map<String, String>, kind: CrmImportKind, newListings: Boolean) =
        CrmImportRecord(row.importKey, values(row.data, mapping, kind, newListings))

    /** Mapeo con el que queda lista la importacion: el enviado si coincide con las columnas reales; si no, el sugerido y un aviso. */
    fun mappingAfterRead(kind: CrmImportKind, columns: List<MondayColumn>, sent: Map<String, String>): Pair<Map<String, String>, String> {
        val suggested = { suggest(kind.id, columns, kind.fields.map { it.key }.toSet()) }
        if (sent.isEmpty()) return suggested() to ""
        return try {
            checkMapping(kind, columns, sent) to ""
        } catch (failure: InmobiliariaMondayException) {
            suggested() to "El mapeo enviado no coincide con las columnas del tablero (${failure.message.orEmpty()}); se uso el sugerido. Revisalo antes de aplicar."
        }
    }

    /** Despues de una pagina: terminar (sin cursor), pausar (poco tiempo en la corrida; el cursor queda guardado) o seguir. */
    fun readStep(cursor: String, remainingMillis: Long): ReadStep = when {
        cursor.isEmpty() -> ReadStep.Finish
        remainingMillis < MinQueryMillis -> ReadStep.Pause
        else -> ReadStep.Continue
    }

    /** Un error pasajero de lectura deja la importacion leyendo hasta el maximo de intentos; despues falla. */
    fun giveUpReading(attempts: Int): Boolean = attempts >= MaxReadAttempts

    /**
     * Estado de cada fila de un lote segun el resultado del destino. Una fila falla solo si su clave esta en [failedKeys] (clave
     * exacta: monday:1 no es monday:12); el resto quedo guardada, con el aviso de [warnings] si lo trae.
     */
    fun verdicts(rows: List<MondayRow>, failedKeys: List<String>, problems: List<String>, warnings: List<String>): List<MondayRowVerdict> {
        val failed = failedKeys.toSet()
        return rows.map { row ->
            if (row.importKey in failed) {
                MondayRowVerdict(row, MondayRowState.Failed, problemOf(row.importKey, problems) ?: "No se pudo guardar el registro.")
            } else {
                MondayRowVerdict(row, MondayRowState.Applied, problemOf(row.importKey, warnings).orEmpty())
            }
        }
    }

    /** Recorta un texto a [max] caracteres. */
    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max)

    /** Mensaje de un problema de un registro (formato "<importKey>: <mensaje>") o null si ningun problema es de ese registro. */
    fun problemOf(importKey: String, problems: List<String>): String? =
        problems.firstOrNull { it.startsWith("$importKey:") }?.removePrefix("$importKey:")?.trim()

    /** Anade problemas sin pasar del tope guardado. */
    fun capProblems(current: List<String>, extra: List<String>): List<String> = (current + extra.map { it.take(500) }).take(MaxProblems)
}
