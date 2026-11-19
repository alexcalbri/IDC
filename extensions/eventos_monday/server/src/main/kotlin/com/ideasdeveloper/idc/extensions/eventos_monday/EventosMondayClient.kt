package com.ideasdeveloper.idc.extensions.eventos_monday

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

/** Un tablero con sus columnas y los titulos de sus grupos, para sugerir el mapeo. */
data class MondayBoardInfo(val id: String, val name: String, val columns: List<MondayColumn>, val groups: List<String> = emptyList())

/** Una pagina de elementos: [cursor] vacio significa que ya no hay mas. */
data class MondayPage(val items: List<MondayItem>, val cursor: String)

/**
 * Cliente de la API GraphQL de monday (copiado del de tareas_monday, con sus mismas defensas): el servidor se resuelve
 * una vez y la conexion va a esa IP validada, con tope de tamano y de tiempo. El token solo viaja en la cabecera
 * Authorization; ningun mensaje de error ni registro lo incluye. Aqui no hay archivos ni subelementos.
 */
object EventosMondayClient {
    const val MainWorkspace = "Espacio principal"
    const val MaxItems = 20_000
    const val PageSize = 25
    private const val MaxBytes = 20 * 1024 * 1024
    private const val MondayHost = "api.monday.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[0-9]{1,20}$")
    private val log = LoggerFactory.getLogger(EventosMondayClient::class.java)

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    fun isBoardId(value: String): Boolean = idPattern.matches(value)

    // ---- Presupuesto de tiempo (deadline = System.nanoTime() limite de la corrida) ----

    /** Tiempo minimo que debe quedar para empezar una llamada. */
    const val MinCallMillis = 5_000L

    fun hasTime(deadline: Long, now: Long = System.nanoTime()): Boolean = deadline - now >= MinCallMillis * 1_000_000L

    /** El tiempo de espera de una llamada: el normal o lo que queda de la corrida, el menor (sin deadline, el normal). */
    fun timeoutFor(defaultMillis: Long, deadline: Long?, now: Long = System.nanoTime()): Long =
        if (deadline == null) defaultMillis else minOf(defaultMillis, ((deadline - now) / 1_000_000L).coerceAtLeast(1_000L))

    // ---- Tableros ----

    /** Tableros activos que el token puede leer, por espacio de trabajo y nombre (sin tableros de subelementos ni documentos). */
    fun boards(token: String): List<MondayBoard> {
        requireToken(token)
        val boards = mutableListOf<MondayBoard>()
        for (page in 1..50) {
            val data = query(token, "query { boards(limit: 100, page: $page, state: active) { id name type items_count workspace { name } } }")
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

    /** Nombre, columnas y grupos de un tablero. */
    fun boardInfo(token: String, boardId: String): MondayBoardInfo {
        requireToken(token)
        requireBoard(boardId)
        val data = query(token, "query { boards(ids: [$boardId]) { name columns { id title type } groups { title } } }")
        val board = data["boards"].arr().firstOrNull().obj() ?: throw notFound(boardId)
        return MondayBoardInfo(
            boardId, board["name"].str().ifEmpty { boardId }, parseColumns(board["columns"]),
            board["groups"].arr().mapNotNull { it.obj() }.map { it["title"].str() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    fun parseColumns(columns: JsonElement?): List<MondayColumn> = columns.arr().mapNotNull { it.obj() }.mapNotNull { column ->
        val id = column["id"].str().ifEmpty { return@mapNotNull null }
        MondayColumn(id, column["title"].str().ifEmpty { id }, column["type"].str())
    }

    // ---- Elementos (una pagina por llamada; el cursor lo guarda el trabajo) ----

    private const val ItemFields = "id name group { title } column_values { id type text value column { title } }"

    /** Primera pagina ([cursor] nulo) o la siguiente de un tablero. */
    fun itemsPage(token: String, boardId: String, cursor: String?, deadline: Long? = null): MondayPage {
        requireToken(token)
        requireBoard(boardId)
        val page = if (cursor.isNullOrEmpty()) {
            val data = query(token, "query { boards(ids: [$boardId]) { items_page(limit: $PageSize) { cursor items { $ItemFields } } } }", deadline)
            (data["boards"].arr().firstOrNull().obj() ?: throw notFound(boardId))["items_page"].obj()
        } else {
            // El cursor se escapa como cadena JSON, que tambien es una cadena GraphQL valida.
            query(token, "query { next_items_page(limit: $PageSize, cursor: ${JsonPrimitive(cursor)}) { cursor items { $ItemFields } } }", deadline)["next_items_page"].obj()
        }
        return MondayPage(page?.get("items").arr().mapNotNull { it.obj() }.map(::parseItem), page?.get("cursor").str())
    }

    fun parseItem(item: JsonObject): MondayItem = MondayItem(
        id = item["id"].str(),
        name = item["name"].str(),
        group = item["group"].obj()?.get("title").str(),
        cells = parseCells(item["column_values"]),
    )

    private fun parseCells(values: JsonElement?): List<MondayCell> = values.arr().mapNotNull { it.obj() }.map { value ->
        MondayCell(
            columnId = value["id"].str(),
            title = value["column"].obj()?.get("title").str(),
            type = value["type"].str(),
            text = value["text"].str(),
            // El valor crudo (JSON como texto) trae fechas con hora y personas; sin recortar espacios ni contenido.
            value = (value["value"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    // ---- Personas ----

    /** Nombre y correo de las personas de monday por id (sin duplicados; maximo 50 por consulta). */
    fun users(token: String, ids: Collection<String>, deadline: Long? = null): List<MondayPerson> {
        requireToken(token)
        return ids.filter { idPattern.matches(it) }.distinct().chunked(50).flatMap { chunk ->
            val data = query(token, "query { users(ids: [${chunk.joinToString()}]) { id name email } }", deadline)
            data["users"].arr().mapNotNull { it.obj() }.mapNotNull { user ->
                val id = user["id"].str().takeIf { it.matches(idPattern) } ?: return@mapNotNull null
                MondayPerson(id, user["name"].str(), user["email"].str().lowercase())
            }
        }
    }

    // ---- Red ----

    private fun requireToken(token: String) {
        if (token.isBlank()) throw EventosMondayException("Escribe el token de la API de monday.")
    }

    private fun requireBoard(boardId: String) {
        if (!isBoardId(boardId)) throw EventosMondayException("El id del tablero debe ser un numero.")
    }

    private fun notFound(boardId: String) = EventosMondayException("monday no devolvio el tablero $boardId (revisa el id y los permisos del token).")

    private fun query(token: String, query: String, deadline: Long? = null): JsonObject {
        val payload = JsonObject(mapOf("query" to JsonPrimitive(query))).toString().toByteArray(Charsets.UTF_8)
        // Se resuelve una vez y la conexion va a esa IP validada (anti DNS rebinding).
        val (status, text) = try {
            val validated = PinnedHost.resolveValidated(MondayHost)
            PinnedHost.openPinnedHttps(
                URI.create("https://$MondayHost/v2"), validated, MondayHost, "POST",
                mapOf("Authorization" to token.trim(), "API-Version" to "2024-10", "Content-Type" to "application/json"),
                payload, timeoutMillis = timeoutFor(30_000, deadline).toInt(), totalMillis = timeoutFor(60_000, deadline), maxBodyBytes = MaxBytes + 1L,
            ).use { response -> response.status to response.body.readNBytes(MaxBytes + 1).decodeToString() }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            // Solo el tipo de fallo: el mensaje de la excepcion podria incluir la solicitud.
            log.warn("No se pudo contactar monday: {}", failure.javaClass.simpleName)
            throw EventosMondayException("No se pudo contactar monday.", transient = true)
        }
        if (text.length > MaxBytes) throw EventosMondayException("monday respondio algo demasiado grande; elige un tablero mas chico.")
        val body = runCatching { json.parseToJsonElement(text) }.getOrNull().obj()
        if (status == 401 || status == 403) throw EventosMondayException("monday rechazo el token.")
        if (status == 429 || status >= 500) throw EventosMondayException("monday respondio HTTP $status.", transient = true)
        body?.get("errors").arr().firstOrNull()?.let {
            val message = it.obj()?.get("message").str()
            // Limites de complejidad o de uso: pasan solos, se reintenta.
            val passing = message.lowercase().let { text -> "complexity" in text || "rate limit" in text || "too many" in text }
            throw EventosMondayException("monday: $message", transient = passing)
        }
        return body?.get("data").obj() ?: throw EventosMondayException("monday respondio HTTP $status.")
    }
}
