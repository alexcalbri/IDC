package com.ideasdeveloper.idc.extensions.membresia_monday

import com.ideasdeveloper.idc.server.net.PinnedHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.net.URI

data class MondayBoard(val id: String, val name: String, val workspace: String, val items: Int?)

/** Un tablero con sus columnas, para sugerir el mapeo. */
data class MondayBoardInfo(val id: String, val name: String, val columns: List<MondayColumn>)

/**
 * Cliente de la API GraphQL de monday (copiado del de la extension "monday", con sus mismas defensas): el servidor se
 * resuelve una vez y la conexion va a esa IP validada, con tope de tamano y de tiempo. El token solo viaja en la
 * cabecera Authorization; ningun mensaje de error ni registro lo incluye.
 */
object MembresiaMondayClient {
    const val MainWorkspace = "Espacio principal"
    const val MaxItems = 20_000
    private const val MaxBoardPages = 10
    private const val MaxBytes = 20 * 1024 * 1024
    private const val MondayHost = "api.monday.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[0-9]{1,20}$")
    private val log = LoggerFactory.getLogger(MembresiaMondayClient::class.java)

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    fun isBoardId(value: String): Boolean = idPattern.matches(value)

    // ---- Tableros ----

    /** Tableros activos que el token puede leer, por espacio de trabajo y nombre (sin tableros de subelementos ni documentos). */
    fun boards(token: String): List<MondayBoard> {
        requireToken(token)
        val boards = mutableListOf<MondayBoard>()
        for (page in 1..MaxBoardPages) {
            val data = query(token, "query { boards(limit: 100, page: $page, state: active) { id name type items_count workspace { name } } }", Interactive)
            boards += parseBoards(data)
            if (data["boards"].arr().size < 100) break
        }
        return boards.sortedWith(compareBy<MondayBoard>({ it.workspace != MainWorkspace }, { it.workspace.lowercase() }, { it.name.lowercase() }))
    }

    fun parseBoards(data: JsonObject): List<MondayBoard> = data["boards"].arr().mapNotNull { it.obj() }
        .filter { it["type"].str().ifEmpty { "board" } == "board" }
        .mapNotNull { board ->
            val id = board["id"].str().takeIf { it.matches(idPattern) } ?: return@mapNotNull null
            MondayBoard(id, board["name"].str().ifEmpty { id }, board["workspace"].obj()?.get("name").str().ifEmpty { MainWorkspace }, board["items_count"].str().toIntOrNull())
        }

    /** Nombre y columnas de un tablero. */
    fun boardInfo(token: String, boardId: String): MondayBoardInfo {
        requireToken(token)
        requireBoard(boardId)
        val data = query(token, "query { boards(ids: [$boardId]) { name columns { id title type } } }", Interactive)
        val board = data["boards"].arr().firstOrNull().obj() ?: throw notFound(boardId)
        return MondayBoardInfo(boardId, board["name"].str().ifEmpty { boardId }, parseColumns(board["columns"]))
    }

    fun parseColumns(columns: JsonElement?): List<MondayColumn> = columns.arr().mapNotNull { it.obj() }.mapNotNull { column ->
        val id = column["id"].str().ifEmpty { return@mapNotNull null }
        MondayColumn(id, column["title"].str().ifEmpty { id }, column["type"].str())
    }

    // ---- Elementos ----

    private const val ValueFields = "id type text value column { title }"
    private const val ItemFields = "id name group { title } column_values { $ValueFields } subitems { id name column_values { $ValueFields } }"

    /** Una pagina de elementos; [cursor] vacio significa que era la ultima. */
    class ItemsPage(val items: List<MondayItem>, val cursor: String)

    /** Una pagina de [pageSize] elementos con sus subelementos: la primera con [cursor] nulo, las demas con el cursor de la anterior. */
    fun itemsPage(token: String, boardId: String, cursor: String?, pageSize: Int): ItemsPage {
        requireToken(token)
        requireBoard(boardId)
        val size = pageSize.coerceIn(MondayReading.MinPageSize, 100)
        val page = if (cursor.isNullOrEmpty()) {
            val data = query(token, "query { boards(ids: [$boardId]) { items_page(limit: $size) { cursor items { $ItemFields } } } }")
            (data["boards"].arr().firstOrNull().obj() ?: throw notFound(boardId))["items_page"].obj()
        } else {
            // El cursor se escapa como cadena JSON, que tambien es una cadena GraphQL valida.
            query(token, "query { next_items_page(limit: $size, cursor: ${JsonPrimitive(cursor)}) { cursor items { $ItemFields } } }")["next_items_page"].obj()
        }
        return ItemsPage(page?.get("items").arr().mapNotNull { it.obj() }.map(::parseItem), page?.get("cursor").str())
    }

    fun parseItem(item: JsonObject): MondayItem = MondayItem(
        id = item["id"].str(),
        name = item["name"].str(),
        group = item["group"].obj()?.get("title").str(),
        cells = parseCells(item["column_values"]),
        subitems = item["subitems"].arr().mapNotNull { it.obj() }.map { sub ->
            MondaySubitem(sub["id"].str(), sub["name"].str(), parseCells(sub["column_values"]))
        },
    )

    private fun parseCells(values: JsonElement?): List<MondayCell> = values.arr().mapNotNull { it.obj() }.map { value ->
        MondayCell(
            columnId = value["id"].str(),
            title = value["column"].obj()?.get("title").str(),
            type = value["type"].str(),
            text = value["text"].str(),
            // El valor crudo (JSON como texto) trae las fechas y periodos; sin recortar espacios ni contenido.
            value = (value["value"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    // ---- Red ----

    private fun requireToken(token: String) {
        if (token.isBlank()) throw MembresiaMondayException("Escribe el token de la API de monday.")
    }

    private fun requireBoard(boardId: String) {
        if (!isBoardId(boardId)) throw MembresiaMondayException("El id del tablero debe ser un numero.")
    }

    private fun notFound(boardId: String) = MembresiaMondayException("monday no devolvio el tablero $boardId (revisa el id y los permisos del token).")

    // Pantallas y herramientas esperan a una persona: timeouts cortos. La lectura en segundo plano usa los largos.
    private const val Interactive = true

    private fun query(token: String, query: String, interactive: Boolean = false): JsonObject {
        val payload = JsonObject(mapOf("query" to JsonPrimitive(query))).toString().toByteArray(Charsets.UTF_8)
        // Se resuelve una vez y la conexion va a esa IP validada (anti DNS rebinding).
        val (status, text) = try {
            val validated = PinnedHost.resolveValidated(MondayHost)
            PinnedHost.openPinnedHttps(
                URI.create("https://$MondayHost/v2"), validated, MondayHost, "POST",
                mapOf("Authorization" to token.trim(), "API-Version" to "2024-10", "Content-Type" to "application/json"),
                payload, timeoutMillis = if (interactive) 15_000 else 30_000, totalMillis = if (interactive) 15_000 else 60_000, maxBodyBytes = MaxBytes + 1L,
            ).use { response -> response.status to response.body.readNBytes(MaxBytes + 1).decodeToString() }
        } catch (failure: Exception) {
            // Solo el tipo de fallo: el mensaje de la excepcion podria incluir la solicitud.
            log.warn("No se pudo contactar monday: {}", failure.javaClass.simpleName)
            throw MembresiaMondayException("No se pudo contactar monday.", MondayErrorKind.Transient)
        }
        if (text.length > MaxBytes) throw MembresiaMondayException("monday respondio algo demasiado grande; elige un tablero mas chico.", MondayErrorKind.Complexity)
        if (status == 401 || status == 403) throw MembresiaMondayException("monday rechazo el token.")
        val body = runCatching { json.parseToJsonElement(text) }.getOrNull().obj()
        body?.get("errors").arr().firstOrNull()?.let { error ->
            val code = error.obj()?.get("extensions").obj()?.get("code").str()
            val message = error.obj()?.get("message").str()
            throw MembresiaMondayException("monday: ${message.take(MondayReading.MaxMessage)}", MondayReading.classify(status, code, message))
        }
        return body?.get("data").obj() ?: throw MembresiaMondayException("monday respondio HTTP $status.", MondayReading.classify(status, "", ""))
    }
}
