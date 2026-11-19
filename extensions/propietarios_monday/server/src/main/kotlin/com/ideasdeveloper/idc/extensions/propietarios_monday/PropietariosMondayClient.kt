package com.ideasdeveloper.idc.extensions.propietarios_monday

import com.ideasdeveloper.idc.server.net.PinnedHost
import com.ideasdeveloper.idc.server.net.PinnedHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI

data class MondayBoard(val id: String, val name: String, val workspace: String, val items: Int?)

/** Un tablero con sus columnas, para sugerir el mapeo. */
data class MondayBoardInfo(val id: String, val name: String, val columns: List<MondayColumn>)

/** Una pagina de elementos: [cursor] vacio significa que ya no hay mas. */
data class MondayPage(val items: List<MondayItem>, val cursor: String)

/** Enlace temporal de descarga de un archivo de monday y su tamano, si lo informa. */
data class MondayAssetLink(val url: String, val size: Long?)

/**
 * Cliente de la API GraphQL de monday (el mismo de la extension tareas_monday, con sus mismas defensas): el servidor se
 * resuelve una vez y la conexion va a esa IP validada, con tope de tamano y de tiempo. El token solo viaja en la
 * cabecera Authorization; ningun mensaje de error ni registro lo incluye.
 */
object PropietariosMondayClient {
    const val MainWorkspace = "Espacio principal"
    const val MaxItems = 5_000
    const val MaxFileBytes = 25L * 1024 * 1024
    const val PageSize = 50
    private const val MaxBytes = 20 * 1024 * 1024
    private const val BoardsBudgetMillis = 30_000L
    private const val MondayHost = "api.monday.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[0-9]{1,20}$")
    private val log = LoggerFactory.getLogger(PropietariosMondayClient::class.java)

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    fun isBoardId(value: String): Boolean = idPattern.matches(value)

    // ---- Presupuesto de tiempo (deadline = System.nanoTime() limite de la corrida) ----

    /** Tiempo minimo que debe quedar para empezar una llamada o una descarga. */
    const val MinCallMillis = 5_000L

    fun hasTime(deadline: Long, now: Long = System.nanoTime()): Boolean = deadline - now >= MinCallMillis * 1_000_000L

    /** El tiempo de espera de una llamada: el normal o lo que queda de la corrida, el menor (sin deadline, el normal). */
    fun timeoutFor(defaultMillis: Long, deadline: Long?, now: Long = System.nanoTime()): Long =
        if (deadline == null) defaultMillis else minOf(defaultMillis, ((deadline - now) / 1_000_000L).coerceAtLeast(1_000L))

    /** Servidores de los que se bajan archivos: monday.com y su almacenamiento S3 (files-monday-com*.amazonaws.com). */
    fun isAllowedFileHost(host: String?): Boolean {
        val name = host?.trim()?.lowercase()?.trimEnd('.').orEmpty()
        if (name.isEmpty()) return false
        return name.endsWith(".monday.com") || (name.startsWith("files-monday-com") && name.endsWith(".amazonaws.com"))
    }

    // ---- Tableros ----

    /** Tableros activos que el token puede leer, por espacio de trabajo y nombre (sin tableros de subelementos ni documentos). */
    fun boards(token: String): List<MondayBoard> {
        requireToken(token)
        val boards = mutableListOf<MondayBoard>()
        // Tope de 30 s para todo el listado: si se acaba, se devuelve lo leido (la lista puede estar incompleta).
        val deadline = System.nanoTime() + BoardsBudgetMillis * 1_000_000L
        for (page in 1..50) {
            if (page > 1 && System.nanoTime() >= deadline) {
                log.warn("El listado de tableros de monday se corto por tiempo despues de {} paginas", page - 1)
                break
            }
            val data = query(token, "query { boards(limit: 100, page: $page, state: active) { id name type items_count workspace { name } } }", deadline)
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
        val data = query(token, "query { boards(ids: [$boardId]) { name columns { id title type } } }")
        val board = data["boards"].arr().firstOrNull().obj() ?: throw notFound(boardId)
        return MondayBoardInfo(boardId, board["name"].str().ifEmpty { boardId }, parseColumns(board["columns"]))
    }

    fun parseColumns(columns: JsonElement?): List<MondayColumn> = columns.arr().mapNotNull { it.obj() }.mapNotNull { column ->
        val id = column["id"].str().ifEmpty { return@mapNotNull null }
        MondayColumn(id, column["title"].str().ifEmpty { id }, column["type"].str())
    }

    // ---- Elementos (una pagina por llamada; el cursor lo guarda el trabajo) ----

    // Sin subelementos ni actualizaciones: un lote es una fila plana.
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
            // El valor crudo (JSON como texto) trae fechas, ubicacion y archivos; sin recortar espacios ni contenido.
            value = (value["value"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    // ---- Archivos ----

    /** Enlaces de descarga de archivos (caducan en menos de una hora: se piden al aplicar la fila). */
    fun assetLinks(token: String, ids: Collection<String>, deadline: Long? = null): Map<String, MondayAssetLink> {
        requireToken(token)
        return ids.filter { idPattern.matches(it) }.distinct().chunked(50).flatMap { chunk ->
            val data = query(token, "query { assets(ids: [${chunk.joinToString()}]) { id name public_url file_size } }", deadline)
            data["assets"].arr().mapNotNull { it.obj() }.mapNotNull { asset ->
                val id = asset["id"].str().takeIf { it.matches(idPattern) } ?: return@mapNotNull null
                val url = asset["public_url"].str().ifEmpty { return@mapNotNull null }
                id to MondayAssetLink(url, asset["file_size"].str().toLongOrNull())
            }
        }.toMap()
    }

    /** Descarga un enlace de monday (https en un servidor publico) y entrega su contenido a [store]. */
    fun download(url: String, deadline: Long? = null, store: (contentType: String, body: InputStream) -> Unit) {
        val uri = runCatching { URI.create(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) throw PropietariosMondayException("enlace de descarga invalido")
        if (!isAllowedFileHost(uri.host)) throw PropietariosMondayException("el archivo no esta en un servidor de monday")
        // Una sola resolucion: se valida y la conexion va a esa misma IP (anti DNS rebinding).
        val validated = try {
            PinnedHost.resolveValidated(uri.host)
        } catch (failure: PinnedHostException) {
            throw PropietariosMondayException(if (failure.message.orEmpty().startsWith("No se encontro")) "no se encontro ${uri.host}" else "el enlace apunta a una red privada")
        }
        val response = try {
            PinnedHost.openPinnedHttps(
                uri, validated, uri.host, timeoutMillis = timeoutFor(30_000, deadline).toInt(), connectTimeoutMillis = timeoutFor(15_000, deadline).toInt(),
                totalMillis = timeoutFor(5 * 60_000L, deadline), maxBodyBytes = MaxFileBytes + 1,
            )
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            throw PropietariosMondayException("no se pudo descargar")
        }
        // use cierra la conexion aunque store falle.
        response.use {
            if (it.status !in 200..299) throw PropietariosMondayException("la descarga respondio HTTP ${it.status}")
            store(it.header("Content-Type") ?: "application/octet-stream", it.body)
        }
    }

    // ---- Red ----

    private fun requireToken(token: String) {
        if (token.isBlank()) throw PropietariosMondayException("Escribe el token de la API de monday.")
    }

    private fun requireBoard(boardId: String) {
        if (!isBoardId(boardId)) throw PropietariosMondayException("El id del tablero debe ser un numero.")
    }

    private fun notFound(boardId: String) = PropietariosMondayException("monday no devolvio el tablero $boardId (revisa el id y los permisos del token).")

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
            throw PropietariosMondayException("No se pudo contactar monday.", transient = true)
        }
        if (text.length > MaxBytes) throw PropietariosMondayException("monday respondio algo demasiado grande; elige un tablero mas chico.")
        val body = runCatching { json.parseToJsonElement(text) }.getOrNull().obj()
        if (status == 401 || status == 403) throw PropietariosMondayException("monday rechazo el token.")
        if (status == 429 || status >= 500) throw PropietariosMondayException("monday respondio HTTP $status.", transient = true)
        body?.get("errors").arr().firstOrNull()?.let {
            val message = it.obj()?.get("message").str()
            // Limites de complejidad o de uso: pasan solos, se reintenta.
            val passing = message.lowercase().let { text -> "complexity" in text || "rate limit" in text || "too many" in text }
            throw PropietariosMondayException("monday: $message", transient = passing)
        }
        return body?.get("data").obj() ?: throw PropietariosMondayException("monday respondio HTTP $status.")
    }
}
