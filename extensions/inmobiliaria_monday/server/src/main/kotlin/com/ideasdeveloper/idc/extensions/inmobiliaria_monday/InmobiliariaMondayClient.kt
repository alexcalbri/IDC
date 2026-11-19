package com.ideasdeveloper.idc.extensions.inmobiliaria_monday

import com.ideasdeveloper.idc.server.net.PinnedHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/**
 * Cliente de la API GraphQL de monday, recortado de la extension monday: POST a https://api.monday.com/v2 (host fijo, con la
 * IP validada), respuesta acotada, tableros paginados y elementos de a 100. Solo lee tableros y elementos: ni usuarios ni archivos.
 * El token solo viaja en el encabezado Authorization; nunca se escribe en mensajes ni en registros.
 */
object InmobiliariaMondayClient {
    private const val Host = "api.monday.com"
    private const val MaxBytes = 20 * 1024 * 1024
    const val MainWorkspace = "Espacio principal"
    const val PageSize = 100
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[0-9]{1,20}$")

    // Columnas de monday que no son datos importables (archivos, subelementos, botones, documentos).
    private val skippedTypes = setOf("file", "subtasks", "button", "doc")

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim().orEmpty()

    fun validBoardId(id: String): Boolean = idPattern.matches(id)

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
            val id = board["id"].str().takeIf { idPattern.matches(it) } ?: return@mapNotNull null
            MondayBoard(id, board["name"].str().ifEmpty { id }, board["workspace"].obj()?.get("name").str().ifEmpty { MainWorkspace }, board["items_count"].str().toIntOrNull())
        }

    // ---- Elementos ----

    private const val ItemFields = "id name group { title } column_values { id type text value }"

    /** Primera pagina del tablero: su nombre, sus columnas, los elementos y el cursor de la pagina siguiente ("" si no hay mas). */
    class FirstPage(val boardName: String, val columns: List<MondayColumn>, val items: List<MondayItem>, val cursor: String)

    class NextPage(val items: List<MondayItem>, val cursor: String)

    /**
     * Plazos HTTP de una consulta: nunca pasan del tiempo que queda a la corrida ([remainingMillis]), para que una consulta lenta no
     * rebase el presupuesto del trabajo; con tiempo de sobra son los de siempre (30 s de lectura, 10 s de conexion, 60 s en total).
     */
    data class Timeouts(val readMillis: Int, val connectMillis: Int, val totalMillis: Long)

    fun timeoutsFor(remainingMillis: Long): Timeouts {
        val total = remainingMillis.coerceIn(1_000L, 60_000L)
        return Timeouts(minOf(30_000L, total).toInt(), minOf(10_000L, total).toInt(), total)
    }

    fun firstPage(token: String, boardId: String, remainingMillis: Long = 60_000L): FirstPage {
        requireToken(token)
        if (!validBoardId(boardId)) throw InmobiliariaMondayException("El id del tablero no es valido.")
        val data = query(token, "query { boards(ids: [$boardId]) { name columns { id title type } items_page(limit: $PageSize) { cursor items { $ItemFields } } } }", timeoutsFor(remainingMillis))
        return parseFirstPage(data, boardId)
    }

    fun nextPage(token: String, columns: List<MondayColumn>, cursor: String, remainingMillis: Long = 60_000L): NextPage {
        requireToken(token)
        // El cursor viene de monday; se escribe como cadena JSON para que no pueda alterar la consulta.
        val data = query(token, "query { next_items_page(limit: $PageSize, cursor: ${JsonPrimitive(cursor)}) { cursor items { $ItemFields } } }", timeoutsFor(remainingMillis))
        return parseNextPage(data, columns)
    }

    fun parseFirstPage(data: JsonObject, boardId: String): FirstPage {
        val board = data["boards"].arr().firstOrNull().obj()
            ?: throw InmobiliariaMondayException("monday no devolvio el tablero $boardId (revisa el id y los permisos del token).")
        // Nombre y Grupo son columnas sinteticas: se pueden mapear y sugerir como las demas (title / property).
        val columns = listOf(
            MondayColumn("name", MondayMapping.NameColumn, "name"),
            MondayColumn("group", MondayMapping.GroupColumn, "group"),
        ) + parseColumns(board["columns"].arr())
        val page = board["items_page"].obj()
        return FirstPage(board["name"].str(), columns, parseItems(page?.get("items").arr(), columns), page?.get("cursor").str())
    }

    fun parseNextPage(data: JsonObject, columns: List<MondayColumn>): NextPage {
        val page = data["next_items_page"].obj()
        return NextPage(parseItems(page?.get("items").arr(), columns), page?.get("cursor").str())
    }

    /** Columnas importables del tablero; un titulo repetido o vacio se distingue con el tipo o un numero para que cada columna tenga su propia clave. */
    fun parseColumns(columns: List<JsonElement>): List<MondayColumn> {
        val seen = mutableSetOf(MondayMapping.NameColumn, MondayMapping.GroupColumn)
        return columns.mapNotNull { it.obj() }.mapNotNull { column ->
            val id = column["id"].str().ifEmpty { return@mapNotNull null }
            val type = column["type"].str()
            // La columna "name" es el nombre del elemento, que ya se guarda aparte.
            if (id == "name" || type in skippedTypes) return@mapNotNull null
            val base = column["title"].str().ifEmpty { id }.take(120)
            var title = base
            var n = 2
            while (!seen.add(title)) title = "$base ($n)".also { n++ }
            MondayColumn(id, title, type)
        }
    }

    fun parseItems(items: List<JsonElement>, columns: List<MondayColumn>): List<MondayItem> {
        // Las columnas sinteticas (nombre y grupo) ya se leen del elemento, no de column_values.
        val byId = columns.filter { it.type != "name" && it.type != "group" }.associateBy { it.id }
        return items.mapNotNull { it.obj() }.mapNotNull { item ->
            val id = item["id"].str().takeIf { idPattern.matches(it) } ?: return@mapNotNull null
            val values = linkedMapOf(
                MondayMapping.NameColumn to MondayMapping.clip(item["name"].str(), MondayMapping.MaxNameChars),
                MondayMapping.GroupColumn to MondayMapping.clip(item["group"].obj()?.get("title").str(), MondayMapping.MaxNameChars),
            )
            item["column_values"].arr().mapNotNull { it.obj() }.forEach { value ->
                val column = byId[value["id"].str()] ?: return@forEach
                values[column.title] = MondayMapping.clip(columnText(column.type, value["text"].str(), value["value"].str()), MondayMapping.MaxValueChars)
            }
            MondayItem(id, values)
        }
    }

    /** Texto de una columna: en las de enlace, la URL (el texto de monday mezcla el rotulo con la URL); en las demas, el texto. */
    fun columnText(type: String, text: String, rawValue: String): String {
        if (type == "link") {
            val url = runCatching { json.parseToJsonElement(rawValue) }.getOrNull().obj()?.get("url").str()
            if (url.isNotEmpty()) return url
        }
        return text.trim()
    }

    // ---- Transporte ----

    private fun requireToken(token: String) {
        if (token.isBlank()) throw InmobiliariaMondayException("Guarda primero el token de la API de monday.")
    }

    private fun query(token: String, query: String, timeouts: Timeouts = timeoutsFor(60_000L)): JsonObject {
        val payload = JsonObject(mapOf("query" to JsonPrimitive(query))).toString().toByteArray(Charsets.UTF_8)
        // Se resuelve una vez y la conexion va a esa IP validada (anti DNS rebinding).
        val (status, text) = try {
            val validated = PinnedHost.resolveValidated(Host)
            PinnedHost.openPinnedHttps(
                URI.create("https://$Host/v2"), validated, Host, "POST",
                mapOf("Authorization" to token.trim(), "API-Version" to "2024-10", "Content-Type" to "application/json"),
                payload,
                timeoutMillis = timeouts.readMillis, connectTimeoutMillis = timeouts.connectMillis, totalMillis = timeouts.totalMillis, maxBodyBytes = MaxBytes + 1L,
            ).use { response -> response.status to response.body.readNBytes(MaxBytes + 1).decodeToString() }
        } catch (failure: Exception) {
            // Red caida, DNS o plazo agotado: pasajero, la lectura se reintenta en la siguiente corrida.
            throw InmobiliariaMondayTransientException("No se pudo contactar monday.")
        }
        return parseResponse(status, text)
    }

    /** Errores de monday por limite de uso o de complejidad: se resuelven esperando. */
    fun isTransientMessage(message: String): Boolean = message.lowercase().let { "complexity budget" in it || "rate limit" in it || "ratelimit" in it }

    /** Datos de una respuesta de monday o el error que corresponda (token rechazado, error de la consulta, respuesta inesperada). */
    fun parseResponse(status: Int, text: String): JsonObject {
        val body = runCatching { json.parseToJsonElement(text) }.getOrNull().obj()
        if (status == 401 || status == 403) throw InmobiliariaMondayException("monday rechazo el token.")
        if (status == 429 || status in 500..599) throw InmobiliariaMondayTransientException("monday respondio HTTP $status (error pasajero).")
        body?.get("errors").arr().firstOrNull()?.let {
            val message = it.obj()?.get("message").str().take(300)
            if (isTransientMessage(message)) throw InmobiliariaMondayTransientException("monday: $message")
            throw InmobiliariaMondayException("monday: $message")
        }
        return body?.get("data").obj() ?: throw InmobiliariaMondayException("monday respondio HTTP $status.")
    }
}
