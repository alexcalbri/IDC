package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Duration
import java.util.UUID

/**
 * A file of a monday item: from a file column ([column]) or attached to an update ([update], its
 * date). Only the asset id is kept with the row: monday's download links expire within the hour,
 * so a fresh one is asked when the row is applied.
 */
data class CrmMondayFile(val asset: String, val name: String, val column: String = "", val update: String = "")

object CrmMondayFiles {
    const val FilesColumn = "__files"
    const val UpdatesFolder = "Actualizaciones de monday"
    private val json = Json { ignoreUnknownKeys = true }
    private val download = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val assetId = Regex("^[0-9]{1,20}$")

    fun encode(files: List<CrmMondayFile>): String = JsonArray(
        files.filter { assetId.matches(it.asset) }.map { file ->
            buildJsonObject {
                put("asset", file.asset)
                put("name", file.name.take(255))
                if (file.column.isNotEmpty()) put("column", file.column)
                if (file.update.isNotEmpty()) put("update", file.update)
            }
        },
    ).toString()

    fun decode(raw: String?): List<CrmMondayFile> = runCatching { json.parseToJsonElement(raw.orEmpty()) as JsonArray }.getOrNull().orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        fun text(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        CrmMondayFile(text("asset"), text("name"), text("column"), text("update")).takeIf { assetId.matches(it.asset) }
    }

    /** Assets of a file column value ({"files":[{"fileType":"ASSET","assetId":1,"name":"a.pdf"}]}); links to Drive or Dropbox are not files. */
    fun fromColumnValue(column: String, value: String): List<CrmMondayFile> {
        val files = runCatching { (json.parseToJsonElement(value) as JsonObject)["files"] as JsonArray }.getOrNull().orEmpty()
        return files.mapNotNull { element ->
            val file = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (file[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (text("fileType").ifEmpty { "ASSET" } != "ASSET") return@mapNotNull null
            CrmMondayFile(text("assetId"), text("name"), column = column).takeIf { assetId.matches(it.asset) }
        }
    }

    /**
     * Downloads the files of an imported row into the lead. [target] says for each file the field
     * key and folder (null: not imported). Files already imported (same asset) are skipped.
     * Returns what could not be imported, to show with the row.
     */
    fun import(
        tenant: Connection,
        files: ModuleFiles,
        token: String,
        leadId: UUID,
        wanted: List<CrmMondayFile>,
        target: (CrmMondayFile) -> Pair<String, String>?,
    ): List<String> {
        val targeted = wanted.mapNotNull { file -> target(file)?.let { file to it } }.distinctBy { it.first.asset }
        if (targeted.isEmpty()) return emptyList()
        val done = tenant.prepareStatement(
            "SELECT source_ref FROM crm_attachments WHERE lead_id = ? AND deleted_at IS NULL AND source_ref = ANY(?)"
        ).use { query ->
            query.setObject(1, leadId)
            query.setArray(2, tenant.createArrayOf("text", targeted.map { ref(it.first) }.toTypedArray()))
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        val pending = targeted.filter { ref(it.first) !in done }
        if (pending.isEmpty()) return emptyList()
        val problems = mutableListOf<String>()
        val links = pending.map { it.first.asset }.chunked(50).flatMap { ids ->
            runCatching {
                val data = CrmImport.mondayQuery(token, "query { assets(ids: [${ids.joinToString()}]) { id name public_url file_size } }")
                (data["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val asset = element as? JsonObject ?: return@mapNotNull null
                    fun text(key: String) = (asset[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    text("id") to (text("public_url") to text("file_size").toLongOrNull())
                }
            }.getOrElse { failure ->
                problems += "No se pidieron los archivos a monday: ${failure.message}"
                emptyList()
            }
        }.toMap()
        pending.forEach { (file, place) ->
            val (url, size) = links[file.asset] ?: run {
                if (problems.isEmpty()) problems += "${file.name}: monday no lo devolvio"
                return@forEach
            }
            if (size != null && size > CrmAttachments.MaxFileBytes) {
                problems += "${file.name}: supera ${CrmAttachments.MaxFileBytes / (1024 * 1024)} MB"
                return@forEach
            }
            try {
                val (fieldKey, folder) = place
                fetch(url) { contentType, body ->
                    CrmAttachments.importLinked(tenant, files, leadId, fieldKey, file.name, folder, contentType, body, ref(file), "Importacion monday")
                }
            } catch (failure: Exception) {
                problems += "${file.name}: ${(failure as? CrmValidationException ?: failure as? CrmImportException)?.message ?: "no se pudo descargar"}"
            }
        }
        return problems
    }

    private fun ref(file: CrmMondayFile) = "monday:asset:${file.asset}"

    // Downloads a monday file link (https on a public host) and hands its stream to [store].
    private fun fetch(url: String, store: (contentType: String, body: java.io.InputStream) -> Unit) {
        val uri = runCatching { URI.create(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) throw CrmImportException("enlace de descarga invalido")
        val addresses = runCatching { InetAddress.getAllByName(uri.host).toList() }.getOrElse { throw CrmImportException("no se encontro ${uri.host}") }
        if (addresses.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }) {
            throw CrmImportException("el enlace apunta a una red privada")
        }
        val response = download.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            if (response.statusCode() !in 200..299) throw CrmImportException("la descarga respondio HTTP ${response.statusCode()}")
            store(response.headers().firstValue("Content-Type").orElse("application/octet-stream"), body)
        }
    }
}
