package com.ideasdeveloper.idc.extensions.compras_monday

import com.ideasdeveloper.idc.modules.compras.ComprasAttachments
import com.ideasdeveloper.idc.server.net.PinnedHost
import com.ideasdeveloper.idc.server.net.PinnedHostException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

/**
 * Cliente de la API GraphQL de monday. La red va solo por [PinnedHost] (se resuelve una vez el host, se valida que sea publico y la
 * conexion va a esa IP). El token viaja solo en la cabecera Authorization y nunca se escribe en el registro ni en un mensaje.
 */
object ComprasMondayApi {
    const val PageSize = 100
    const val MaxItems = 20_000
    private const val MaxBytes = 20 * 1024 * 1024
    private const val MondayHost = "api.monday.com"
    private val log = LoggerFactory.getLogger(ComprasMondayApi::class.java)
    private const val ItemFields = "id name created_at group { id title } column_values { id type text value }"

    /** Tableros activos que el token puede leer (hasta 50 paginas de 100). */
    fun boards(token: String): List<MondayBoard> {
        val boards = mutableListOf<MondayBoard>()
        for (page in 1..50) {
            val data = query(token, "query { boards(limit: 100, page: $page, state: active) { id name type items_count workspace { name } } }")
            boards += ComprasMondayParse.parseBoards(data)
            if (data["boards"].arr().size < 100) break
        }
        return boards.sortedWith(compareBy<MondayBoard>({ it.workspace != ComprasMondayParse.MainWorkspace }, { it.workspace.lowercase() }, { it.name.lowercase() }))
    }

    /** Columnas y grupos de un tablero. */
    fun boardInfo(token: String, boardId: String): MondayBoardInfo {
        if (!ComprasMondayParse.isBoardId(boardId)) throw ComprasMondayException("El id del tablero no es valido.")
        val data = query(token, "query { boards(ids: [$boardId]) { name columns { id title type } groups { id title } } }")
        return ComprasMondayParse.parseBoardInfo(data, boardId)
            ?: throw ComprasMondayException("monday no devolvio el tablero (revisa el id y los permisos del token).")
    }

    /** Una pagina de elementos: la primera si [cursor] es nulo, si no la siguiente a ese cursor (que debe tener forma valida). */
    fun page(token: String, boardId: String, cursor: String?): MondayPage {
        if (!ComprasMondayParse.isBoardId(boardId)) throw ComprasMondayException("El id del tablero no es valido.")
        if (cursor == null) {
            val data = query(token, "query { boards(ids: [$boardId]) { items_page(limit: $PageSize) { cursor items { $ItemFields } } } }")
            val board = data["boards"].arr().firstOrNull().obj() ?: throw ComprasMondayException("monday no devolvio el tablero (revisa el id y los permisos del token).")
            return ComprasMondayParse.parsePage(board["items_page"].obj())
        }
        if (!ComprasMondayParse.isValidCursor(cursor)) throw ComprasMondayException("monday devolvio un cursor no valido; vuelve a iniciar la importacion.")
        val data = query(token, "query { next_items_page(limit: $PageSize, cursor: \"$cursor\") { cursor items { $ItemFields } } }")
        return ComprasMondayParse.parsePage(data["next_items_page"].obj())
    }

    /** Enlace publico (caduca en menos de una hora) y tamano de un asset. */
    fun asset(token: String, assetId: String, totalMillis: Long = 60_000): Pair<String, Long?>? {
        if (!ComprasMondayParse.isBoardId(assetId)) return null
        val data = query(token, "query { assets(ids: [$assetId]) { id name public_url file_size } }", totalMillis)
        val asset = data["assets"].arr().firstOrNull().obj() ?: return null
        val url = asset["public_url"].str().ifEmpty { return null }
        return url to asset["file_size"].str().toLongOrNull()
    }

    fun query(token: String, query: String, totalMillis: Long = 60_000): JsonObject {
        if (token.isBlank()) throw ComprasMondayException("Falta el token de la API de monday.")
        val payload = JsonObject(mapOf("query" to JsonPrimitive(query))).toString().toByteArray(Charsets.UTF_8)
        val (status, text) = try {
            val validated = PinnedHost.resolveValidated(MondayHost)
            PinnedHost.openPinnedHttps(
                URI.create("https://$MondayHost/v2"), validated, MondayHost, "POST",
                mapOf("Authorization" to token.trim(), "API-Version" to "2024-10", "Content-Type" to "application/json"),
                payload, timeoutMillis = minOf(30_000L, totalMillis).toInt(), totalMillis = totalMillis, maxBodyBytes = MaxBytes + 1L,
            ).use { response -> response.status to response.body.readNBytes(MaxBytes + 1).decodeToString() }
        } catch (failure: Exception) {
            // Sin el mensaje original: no debe arrastrar datos de la peticion.
            log.warn("No se pudo contactar monday ({}).", failure.javaClass.simpleName)
            throw ComprasMondayException("No se pudo contactar monday.", retryable = true)
        }
        if (text.length > MaxBytes) throw ComprasMondayException("monday respondio con demasiados datos.")
        val body = ComprasMondayParse.parseJson(text)
        if (status == 401 || status == 403) throw ComprasMondayException("monday rechazo el token.")
        // Los errores de la API (limite de uso, fallos del servicio) suelen ser pasajeros: se reintentan.
        body?.get("errors").arr().firstOrNull()?.let { throw ComprasMondayException("monday: ${it.obj()?.get("message").str().take(300)}", retryable = true) }
        return body?.get("data").obj() ?: throw ComprasMondayException("monday respondio HTTP $status.", retryable = status == 429 || status >= 500)
    }

    /**
     * Abre la descarga de un asset: pide el enlace publico (fresco) y lo baja por una IP validada. Devuelve null si no se pudo o supera
     * el tope de adjuntos de Compras. Cerrar el flujo cierra la conexion.
     */
    fun openAsset(token: String, assetId: String, deadlineNanos: Long = Long.MAX_VALUE): InputStream? {
        try {
            // Lo que queda de la corrida acota cada peticion; con un piso de 5 s para no dejar a medias una fila que ya empezo.
            val left = if (deadlineNanos == Long.MAX_VALUE) Long.MAX_VALUE else maxOf((deadlineNanos - System.nanoTime()) / 1_000_000, 5_000L)
            val (url, size) = asset(token, assetId, minOf(60_000L, left)) ?: run {
                log.warn("monday no devolvio el archivo {}.", assetId)
                return null
            }
            if (size != null && size > ComprasAttachments.MaxFileBytes) {
                log.warn("El archivo {} de monday supera el maximo de Compras.", assetId)
                return null
            }
            val uri = runCatching { URI.create(url) }.getOrNull()
            if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) {
                log.warn("El enlace del archivo {} de monday no es https.", assetId)
                return null
            }
            // Una sola resolucion: se valida y la conexion va a esa misma IP (anti DNS rebinding).
            val validated = PinnedHost.resolveValidated(uri.host)
            val response = PinnedHost.openPinnedHttps(
                uri, validated, uri.host, timeoutMillis = minOf(30_000L, left).toInt(), connectTimeoutMillis = minOf(15_000L, left).toInt(),
                totalMillis = minOf(5 * 60_000L, left), maxBodyBytes = ComprasAttachments.MaxFileBytes + 1,
            )
            if (response.status !in 200..299) {
                response.close()
                log.warn("La descarga del archivo {} de monday respondio HTTP {}.", assetId, response.status)
                return null
            }
            return response.body
        } catch (failure: PinnedHostException) {
            log.warn("El enlace del archivo {} de monday apunta a un host no permitido.", assetId)
        } catch (failure: Exception) {
            log.warn("No se pudo descargar el archivo {} de monday ({}).", assetId, failure.javaClass.simpleName)
        }
        return null
    }
}
