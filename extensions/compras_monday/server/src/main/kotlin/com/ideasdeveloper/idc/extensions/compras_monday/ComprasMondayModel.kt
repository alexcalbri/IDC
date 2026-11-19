package com.ideasdeveloper.idc.extensions.compras_monday

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Error que se puede mostrar tal cual al usuario (nunca lleva el token). [retryable]: un fallo pasajero (red, HTTP, limite) que se reintenta. */
class ComprasMondayException(message: String, val retryable: Boolean = false) : RuntimeException(message)

/** Reintentos de la lectura: decision pura del estado tras un fallo. */
object ComprasMondayRetry {
    const val MaxAttempts = 5
    const val MaxPages = 250

    /** "reading" (se reintenta en la proxima corrida) o "failed" si el error no es pasajero o ya fueron [MaxAttempts] fallos seguidos. */
    fun statusAfterFailure(attemptsAfter: Int, retryable: Boolean): String =
        if (!retryable || attemptsAfter >= MaxAttempts) "failed" else "reading"
}

data class MondayBoard(val id: String, val name: String, val workspace: String, val items: Int?)

data class MondayColumn(val id: String, val title: String, val type: String)

data class MondayGroup(val id: String, val title: String)

/** Columnas y grupos de un tablero, para elegir los grupos a importar y mapear las columnas. */
data class MondayBoardInfo(val id: String, val name: String, val columns: List<MondayColumn>, val groups: List<MondayGroup>)

/** Archivo de una columna de archivos: solo se guarda el id del asset; el enlace de descarga caduca y se pide al aplicar. */
data class MondayItemFile(val asset: String, val name: String, val column: String)

data class MondayColumnValue(val type: String, val text: String)

data class MondayItem(
    val id: String,
    val name: String,
    val groupId: String,
    val groupTitle: String,
    val createdAt: String,
    val columns: Map<String, MondayColumnValue>,
    val files: List<MondayItemFile>,
)

/** Una pagina de elementos de un tablero; [cursor] es nulo en la ultima. */
data class MondayPage(val items: List<MondayItem>, val cursor: String?)

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

/** Lectura de las respuestas de la API de monday y (de)serializacion de las filas preparadas. Todo puro, sin red. */
object ComprasMondayParse {
    const val MainWorkspace = "Espacio principal"
    private const val MaxText = 2_000
    private val json = Json { ignoreUnknownKeys = true }
    private val numericId = Regex("^[0-9]{1,20}$")
    private val keyId = Regex("^[A-Za-z0-9_-]{1,100}$")
    private val cursorPattern = Regex("^[A-Za-z0-9_:=+/-]{1,4096}$")

    fun isBoardId(value: String) = numericId.matches(value)

    /** Ids de grupo y de columna de monday (letras, digitos, guion y guion bajo). */
    fun isKeyId(value: String) = keyId.matches(value)

    /** El cursor de monday es un texto opaco en base64; solo se envia si tiene ese aspecto (va dentro de una consulta GraphQL). */
    fun isValidCursor(value: String?) = value != null && cursorPattern.matches(value)

    fun parseJson(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) }.getOrNull().obj()

    /** Tableros activos por espacio de trabajo y nombre (se dejan fuera los de subelementos y los documentos). */
    fun parseBoards(data: JsonObject): List<MondayBoard> = data["boards"].arr().mapNotNull { it.obj() }
        .filter { it["type"].str().ifEmpty { "board" } == "board" }
        .mapNotNull { board ->
            val id = board["id"].str().takeIf(::isBoardId) ?: return@mapNotNull null
            MondayBoard(id, board["name"].str().ifEmpty { id }, board["workspace"].obj()?.get("name").str().ifEmpty { MainWorkspace }, board["items_count"].str().toIntOrNull())
        }

    fun parseBoardInfo(data: JsonObject, boardId: String): MondayBoardInfo? {
        val board = data["boards"].arr().firstOrNull().obj() ?: return null
        val columns = board["columns"].arr().mapNotNull { it.obj() }.mapNotNull { column ->
            val id = column["id"].str().takeIf(::isKeyId) ?: return@mapNotNull null
            MondayColumn(id, column["title"].str().ifEmpty { id }, column["type"].str())
        }
        val groups = board["groups"].arr().mapNotNull { it.obj() }.mapNotNull { group ->
            val id = group["id"].str().takeIf(::isKeyId) ?: return@mapNotNull null
            MondayGroup(id, group["title"].str().ifEmpty { id })
        }
        return MondayBoardInfo(boardId, board["name"].str().ifEmpty { boardId }, columns, groups)
    }

    /** Una pagina ({cursor, items}). El cursor se devuelve tal cual: quien lo use debe validarlo con [isValidCursor]. */
    fun parsePage(page: JsonObject?): MondayPage {
        val items = page?.get("items").arr().mapNotNull { it.obj() }.mapNotNull(::parseItem)
        val cursor = page?.get("cursor").str().takeIf { it.isNotEmpty() }
        return MondayPage(items, cursor)
    }

    fun parseItem(item: JsonObject): MondayItem? {
        val id = item["id"].str().takeIf(::isBoardId) ?: return null
        val columns = linkedMapOf<String, MondayColumnValue>()
        val files = mutableListOf<MondayItemFile>()
        item["column_values"].arr().mapNotNull { it.obj() }.forEach { value ->
            val columnId = value["id"].str().takeIf(::isKeyId) ?: return@forEach
            val type = value["type"].str()
            columns[columnId] = MondayColumnValue(type, value["text"].str().take(MaxText))
            if (type == "file") files += filesOfColumn(columnId, value["value"].str())
        }
        return MondayItem(
            id = id,
            name = item["name"].str().take(500),
            groupId = item["group"].obj()?.get("id").str(),
            groupTitle = item["group"].obj()?.get("title").str(),
            createdAt = item["created_at"].str(),
            columns = columns,
            files = files,
        )
    }

    /** Assets de una columna de archivos ({"files":[{"fileType":"ASSET","assetId":1,"name":"a.pdf"}]}); los enlaces a Drive o Dropbox no son archivos. */
    fun filesOfColumn(column: String, value: String): List<MondayItemFile> {
        val list = runCatching { json.parseToJsonElement(value).obj()?.get("files") }.getOrNull().arr()
        return list.mapNotNull { it.obj() }.mapNotNull { file ->
            if (file["fileType"].str().ifEmpty { "ASSET" } != "ASSET") return@mapNotNull null
            val asset = file["assetId"].str().takeIf(::isBoardId) ?: return@mapNotNull null
            MondayItemFile(asset, file["name"].str().ifEmpty { "archivo-$asset" }.take(255), column)
        }
    }

    /** Solo los elementos de los grupos elegidos (sin grupos elegidos no pasa ninguno). */
    fun filterByGroups(items: List<MondayItem>, groupIds: Set<String>): List<MondayItem> = items.filter { it.groupId in groupIds }

    // ---- Fila preparada (payload jsonb) ----

    fun encode(item: MondayItem): String = buildJsonObject {
        put("id", item.id)
        put("name", item.name)
        put("group_id", item.groupId)
        put("group_title", item.groupTitle)
        put("created_at", item.createdAt)
        put("columns", buildJsonObject {
            item.columns.forEach { (id, value) -> put(id, buildJsonObject { put("type", value.type); put("text", value.text) }) }
        })
        put("files", buildJsonArray {
            item.files.forEach { file -> add(buildJsonObject { put("asset", file.asset); put("name", file.name); put("column", file.column) }) }
        })
    }.toString()

    fun decode(raw: String): MondayItem? {
        val item = parseJson(raw) ?: return null
        val id = item["id"].str().takeIf(::isBoardId) ?: return null
        val columns = item["columns"].obj().orEmpty().mapNotNull { (key, value) ->
            val column = value.obj() ?: return@mapNotNull null
            key to MondayColumnValue(column["type"].str(), column["text"].str())
        }.toMap()
        val files = item["files"].arr().mapNotNull { it.obj() }.mapNotNull { file ->
            val asset = file["asset"].str().takeIf(::isBoardId) ?: return@mapNotNull null
            MondayItemFile(asset, file["name"].str(), file["column"].str())
        }
        return MondayItem(id, item["name"].str(), item["group_id"].str(), item["group_title"].str(), item["created_at"].str(), columns, files)
    }

    // ---- Mapa y lista de textos en jsonb ----

    fun encodeMap(map: Map<String, String>): String = JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

    fun decodeMap(raw: String?): Map<String, String> =
        raw?.let { parseJson(it) }.orEmpty().mapValues { it.value.str() }

    fun encodeList(list: List<String>): String = JsonArray(list.map(::JsonPrimitive)).toString()

    fun decodeList(raw: String?): List<String> =
        raw?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }.arr().map { it.str() }.filter { it.isNotEmpty() }
}
