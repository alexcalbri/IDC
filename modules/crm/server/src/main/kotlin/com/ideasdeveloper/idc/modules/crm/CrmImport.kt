package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// How a job turns columns into CRM fields and source values into stages, users and interests.
data class CrmImportMapping(
    val columns: Map<String, String> = emptyMap(),
    val stages: Map<String, String> = emptyMap(),
    val owners: Map<String, String> = emptyMap(),
    val interests: Map<String, String> = emptyMap(),
)

data class CrmImportJob(
    val id: UUID,
    val source: String,
    val name: String,
    val status: String,
    val mapping: CrmImportMapping,
    val columns: List<String>,
    val total: Int,
    val processed: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val conflicts: Int,
    val errors: Int,
    val createdAt: Instant,
)

data class CrmImportNote(val date: LocalDate?, val author: String, val text: String)

data class CrmMondayBoard(val id: String, val name: String, val workspace: String, val items: Int?)

/** Imports of leads from files, monday and CT Leads Pro, applied in batches by a job. */
object CrmImport {
    private const val MaxBytes = 20 * 1024 * 1024
    const val KeyColumn = "__key"
    const val NotesColumn = "__notes"
    const val ListingsColumn = "__listings"
    const val BoardColumn = "Tablero"
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    // ---- Sources ----

    /** CSV or XLSX from an https URL (Google Sheets export, Dropbox...). Private and local addresses are refused. */
    fun fromUrl(url: String): CrmTable {
        var current = url.trim()
        repeat(4) {
            val uri = runCatching { URI.create(current) }.getOrNull()
            if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) throw CrmImportException("Usa una direccion https:// publica.")
            val addresses = runCatching { InetAddress.getAllByName(uri.host).toList() }.getOrElse { throw CrmImportException("No se encontro ${uri.host}.") }
            if (addresses.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }) {
                throw CrmImportException("Esa direccion apunta a una red privada.")
            }
            val response = try {
                http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            } catch (failure: Exception) {
                throw CrmImportException("No se pudo descargar el archivo.")
            }
            if (response.statusCode() in 300..399) {
                current = response.headers().firstValue("Location").orElse("").let { uri.resolve(it).toString() }
                response.body().close()
                return@repeat
            }
            if (response.statusCode() !in 200..299) throw CrmImportException("La descarga respondio HTTP ${response.statusCode()}.")
            val bytes = response.body().use { it.readNBytes(MaxBytes + 1) }
            if (bytes.size > MaxBytes) throw CrmImportException("El archivo supera los 20 MB.")
            return if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) CrmImportParse.xlsx(bytes) else CrmImportParse.csv(bytes.decodeToString())
        }
        throw CrmImportException("Demasiadas redirecciones.")
    }

    /**
     * Items of monday boards through the GraphQL API: every column as text, the group, the board
     * (used as interest), the creation date, updates and subitems as notes, and the item id as key.
     */
    fun fromMonday(token: String, boardIds: List<String>): CrmTable {
        if (token.isBlank()) throw CrmImportException("Escribe el token de la API de monday.")
        if (boardIds.isEmpty() || boardIds.any { !it.matches(Regex("^[0-9]{1,20}$")) }) throw CrmImportException("Escribe los ID de los tableros (numeros separados por coma).")
        val itemFields = "id name created_at group { title } column_values { column { title } text } updates(limit: 30) { text_body created_at creator { name } } subitems { name column_values { column { title } text } }"
        val columns = linkedSetOf("Nombre")
        val rows = mutableListOf<Map<String, String>>()
        boardIds.forEach { boardId ->
            var data = monday(token, "query { boards(ids: [$boardId]) { name items_page(limit: 100) { cursor items { $itemFields } } } }")
            val board = data["boards"].arr().firstOrNull().obj() ?: throw CrmImportException("monday no devolvio el tablero $boardId (revisa el ID y los permisos del token).")
            val boardName = board["name"].str()
            var page = board["items_page"].obj()
            while (page != null) {
                page["items"].arr().mapNotNull { it.obj() }.forEach { item ->
                    val row = linkedMapOf("Nombre" to item["name"].str())
                    item["column_values"].arr().mapNotNull { it.obj() }.forEach { value ->
                        val title = value["column"].obj()?.get("title").str().ifEmpty { return@forEach }
                        columns += title
                        row[title] = value["text"].str()
                    }
                    row[CrmImportParse.GroupColumn] = item["group"].obj()?.get("title").str()
                    row[BoardColumn] = boardName
                    row[KeyColumn] = "monday:${item["id"].str()}"
                    row["__created"] = item["created_at"].str()
                    val notes = item["updates"].arr().mapNotNull { it.obj() }.map { update ->
                        CrmImportNote(CrmImportParse.date(update["created_at"].str()), update["creator"].obj()?.get("name").str(), update["text_body"].str())
                    } + item["subitems"].arr().mapNotNull { it.obj() }.map { subitem ->
                        val details = subitem["column_values"].arr().mapNotNull { it.obj() }
                            .mapNotNull { value -> value["text"].str().ifEmpty { null }?.let { "${value["column"].obj()?.get("title").str()}: $it" } }
                        CrmImportNote(null, "monday", "Subelemento: ${subitem["name"].str()}${if (details.isEmpty()) "" else " (${details.joinToString()})"}")
                    }
                    row[NotesColumn] = notesJson(notes)
                    rows += row
                    if (rows.size > CrmImportParse.MaxRows) throw CrmImportException("Los tableros tienen mas de ${CrmImportParse.MaxRows} elementos.")
                }
                val cursor = page["cursor"].str()
                if (cursor.isEmpty()) break
                data = monday(token, "query { next_items_page(limit: 100, cursor: \"$cursor\") { cursor items { $itemFields } } }")
                page = data["next_items_page"].obj()
            }
        }
        return CrmTable(columns.toList() + listOf(CrmImportParse.GroupColumn, BoardColumn), rows)
    }

    fun mondayToken(tenant: Connection): String? =
        tenant.prepareStatement("SELECT monday_token FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1)?.takeIf { it.isNotBlank() } else null }
        }

    fun saveMondayToken(tenant: Connection, token: String?) {
        tenant.prepareStatement(
            "INSERT INTO crm_settings (id, monday_token) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET monday_token = EXCLUDED.monday_token, updated_at = CURRENT_TIMESTAMP"
        ).use { update ->
            update.setString(1, token?.trim()?.takeIf { it.isNotEmpty() })
            update.executeUpdate()
        }
    }

    /** Active boards the token can read, by workspace and name (subitem boards and docs left out). */
    fun mondayBoards(token: String): List<CrmMondayBoard> {
        if (token.isBlank()) throw CrmImportException("Escribe el token de la API de monday.")
        val boards = mutableListOf<CrmMondayBoard>()
        for (page in 1..50) {
            val data = monday(token, "query { boards(limit: 100, page: $page, state: active) { id name type items_count workspace { name } } }")
            boards += parseMondayBoards(data)
            if (data["boards"].arr().size < 100) break
        }
        return boards.sortedWith(compareBy<CrmMondayBoard>({ it.workspace != MainWorkspace }, { it.workspace.lowercase() }, { it.name.lowercase() }))
    }

    const val MainWorkspace = "Espacio principal"

    fun parseMondayBoards(data: JsonObject): List<CrmMondayBoard> = data["boards"].arr().mapNotNull { it.obj() }
        .filter { it["type"].str().ifEmpty { "board" } == "board" }
        .mapNotNull { board ->
            val id = board["id"].str().takeIf { it.matches(Regex("^[0-9]{1,20}$")) } ?: return@mapNotNull null
            CrmMondayBoard(id, board["name"].str().ifEmpty { id }, board["workspace"].obj()?.get("name").str().ifEmpty { MainWorkspace }, board["items_count"].str().toIntOrNull())
        }

    private fun monday(token: String, query: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create("https://api.monday.com/v2"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", token.trim())
            .header("API-Version", "2024-10")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonObject(mapOf("query" to JsonPrimitive(query))).toString()))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: Exception) {
            throw CrmImportException("No se pudo contactar monday.")
        }
        val body = runCatching { json.parseToJsonElement(response.body()) }.getOrNull().obj()
        if (response.statusCode() == 401 || response.statusCode() == 403) throw CrmImportException("monday rechazo el token.")
        body?.get("errors").arr().firstOrNull()?.let { throw CrmImportException("monday: ${it.obj()?.get("message").str()}") }
        return body?.get("data").obj() ?: throw CrmImportException("monday respondio HTTP ${response.statusCode()}.")
    }

    /** Leads of CT Leads Pro read through the IDC Connector plugin (stage, agent, source, tags, notes, messages, listings). */
    fun fromLeadsPro(tenant: Connection): CrmTable {
        val settings = CrmWordPress.load(tenant)
        if (!settings.ready) throw CrmImportException("Conecta primero el sitio en Integraciones > WordPress.")
        val rows = mutableListOf<Map<String, String>>()
        var page = 1
        while (true) {
            val result = try {
                CrmWordPress.rpc(settings, "leads", buildJsonObject { put("page", page); put("per_page", 200) }).obj()
            } catch (failure: CrmWordPressException) {
                throw CrmImportException(failure.message.orEmpty())
            } ?: throw CrmImportException("Respuesta invalida del sitio.")
            if (result["error"].str().isNotEmpty()) throw CrmImportException(result["error"].str())
            val items = result["items"].arr().mapNotNull { it.obj() }
            items.forEach { lead ->
                val notes = (lead["notes"].arr() + lead["messages"].arr()).mapNotNull { it.obj() }.map {
                    CrmImportNote(CrmImportParse.date(it["date"].str()), it["author"].str(), it["text"].str())
                }
                rows += mapOf(
                    "Nombre" to lead["name"].str(),
                    "Correo" to lead["email"].str(),
                    "Telefono" to lead["phone"].str(),
                    "Etapa" to lead["stage"].str(),
                    "Agente" to lead["agent"].obj()?.get("name").str(),
                    "Origen" to lead["source"].str(),
                    "Etiquetas" to lead["tags"].arr().joinToString { it.str() },
                    "Creado" to lead["created"].str(),
                    KeyColumn to "leadspro:${lead["id"].str()}",
                    NotesColumn to notesJson(notes),
                    ListingsColumn to JsonArray(lead["listings"].arr()).toString(),
                )
            }
            if (items.size < 200 || page >= 200) break
            page++
        }
        return CrmTable(listOf("Nombre", "Correo", "Telefono", "Etapa", "Agente", "Origen", "Etiquetas", "Creado"), rows)
    }

    private fun notesJson(notes: List<CrmImportNote>) = JsonArray(
        notes.filter { it.text.isNotBlank() }.map { note ->
            buildJsonObject {
                put("date", note.date?.toString().orEmpty())
                put("author", note.author)
                put("text", note.text.take(5000))
            }
        },
    ).toString()

    fun notes(raw: String?): List<CrmImportNote> = runCatching { json.parseToJsonElement(raw.orEmpty()) }.getOrNull().arr().mapNotNull { it.obj() }.map {
        CrmImportNote(CrmImportParse.date(it["date"].str()), it["author"].str(), it["text"].str())
    }

    // ---- Jobs ----

    private fun mappingJson(mapping: CrmImportMapping) = buildJsonObject {
        putJsonObject("columns") { mapping.columns.forEach { (key, value) -> put(key, value) } }
        putJsonObject("stages") { mapping.stages.forEach { (key, value) -> put(key, value) } }
        putJsonObject("owners") { mapping.owners.forEach { (key, value) -> put(key, value) } }
        putJsonObject("interests") { mapping.interests.forEach { (key, value) -> put(key, value) } }
    }

    private fun mapping(raw: String): CrmImportMapping {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull().obj() ?: return CrmImportMapping()
        fun section(name: String) = root[name].obj()?.mapValues { it.value.str() }.orEmpty()
        return CrmImportMapping(section("columns"), section("stages"), section("owners"), section("interests"))
    }

    /** Stores the rows of a source and guesses the mapping (monday boards become interests). */
    fun create(tenant: Connection, source: String, name: String, table: CrmTable, userId: UUID?): UUID {
        if (table.rows.isEmpty()) throw CrmImportException("No hay filas para importar.")
        val columns = table.columns.filter { !it.startsWith("__") }
        var guessed = CrmImportParse.guess(columns)
        if (BoardColumn in columns && "interest" !in guessed.values) guessed = guessed + (BoardColumn to "interest")
        val id = UUID.randomUUID()
        transaction(tenant) {
            tenant.prepareStatement(
                "INSERT INTO crm_import_jobs (id, source, name, mapping, columns, total, created_by) VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)"
            ).use { insert ->
                insert.setObject(1, id)
                insert.setString(2, source)
                insert.setString(3, name.take(200))
                insert.setString(4, mappingJson(CrmImportMapping(guessed)).toString())
                insert.setString(5, JsonArray(columns.map { JsonPrimitive(it) }).toString())
                insert.setInt(6, table.rows.size)
                insert.setObject(7, userId)
                insert.executeUpdate()
            }
            tenant.prepareStatement("INSERT INTO crm_import_rows (job_id, row_no, data) VALUES (?, ?, CAST(? AS jsonb))").use { insert ->
                table.rows.forEachIndexed { index, row ->
                    insert.setObject(1, id)
                    insert.setInt(2, index + 1)
                    insert.setString(3, JsonObject(row.mapValues { JsonPrimitive(it.value) }).toString())
                    insert.addBatch()
                    if (index % 500 == 499) insert.executeBatch()
                }
                insert.executeBatch()
            }
        }
        return id
    }

    fun jobs(tenant: Connection, limit: Int = 20): List<CrmImportJob> = jobQuery(tenant, "ORDER BY created_at DESC LIMIT $limit")

    fun job(tenant: Connection, id: UUID): CrmImportJob? = jobQuery(tenant, "WHERE id = ?") { it.setObject(1, id) }.firstOrNull()

    private fun jobQuery(tenant: Connection, tail: String, bind: (java.sql.PreparedStatement) -> Unit = {}): List<CrmImportJob> = tenant.prepareStatement(
        "SELECT id, source, name, status, mapping::text, columns::text, total, processed, created, updated, skipped, conflicts, errors, created_at FROM crm_import_jobs $tail"
    ).use { query ->
        bind(query)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(
                    CrmImportJob(
                        rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getString(4), mapping(rows.getString(5)),
                        runCatching { json.parseToJsonElement(rows.getString(6)) }.getOrNull().arr().map { it.str() },
                        rows.getInt(7), rows.getInt(8), rows.getInt(9), rows.getInt(10), rows.getInt(11), rows.getInt(12), rows.getInt(13),
                        rows.getTimestamp(14).toInstant(),
                    )
                )
            }
        }
    }

    fun saveMapping(tenant: Connection, job: CrmImportJob, mapping: CrmImportMapping) {
        tenant.prepareStatement("UPDATE crm_import_jobs SET mapping = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'mapping'").use { update ->
            update.setString(1, mappingJson(mapping).toString())
            update.setObject(2, job.id)
            update.executeUpdate()
        }
    }

    fun setStatus(tenant: Connection, job: CrmImportJob, status: String) {
        tenant.prepareStatement("UPDATE crm_import_jobs SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setString(1, status); it.setObject(2, job.id); it.executeUpdate() }
    }

    fun presets(tenant: Connection): Map<String, CrmImportMapping> =
        tenant.prepareStatement("SELECT name, mapping::text FROM crm_import_presets ORDER BY name").use { query ->
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), mapping(rows.getString(2))) } }
        }

    fun savePreset(tenant: Connection, name: String, mapping: CrmImportMapping) {
        tenant.prepareStatement(
            "INSERT INTO crm_import_presets (id, name, mapping) VALUES (?, ?, CAST(? AS jsonb)) ON CONFLICT (name) DO UPDATE SET mapping = EXCLUDED.mapping"
        ).use { upsert ->
            upsert.setObject(1, UUID.randomUUID())
            upsert.setString(2, name.take(80))
            upsert.setString(3, mappingJson(mapping).toString())
            upsert.executeUpdate()
        }
    }

    fun rows(tenant: Connection, job: CrmImportJob, status: String? = null, limit: Int = 50): List<Triple<Int, Map<String, String>, String>> = tenant.prepareStatement(
        "SELECT row_no, data::text, message FROM crm_import_rows WHERE job_id = ? AND (? IS NULL OR status = ?) ORDER BY row_no LIMIT ?"
    ).use { query ->
        query.setObject(1, job.id)
        query.setString(2, status)
        query.setString(3, status)
        query.setInt(4, limit)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(Triple(rows.getInt(1), runCatching { json.parseToJsonElement(rows.getString(2)) }.getOrNull().obj()?.mapValues { it.value.str() }.orEmpty(), rows.getString(3)))
            }
        }
    }

    /** Different values of the columns mapped to [field] (for the stage, owner and interest maps). */
    fun distinctValues(tenant: Connection, job: CrmImportJob, field: String, limit: Int = 60): List<String> {
        val columns = job.mapping.columns.filterValues { it == field }.keys
        if (columns.isEmpty()) return emptyList()
        return tenant.prepareStatement(
            "SELECT DISTINCT value FROM crm_import_rows, jsonb_each_text(data) AS kv(key, value) WHERE job_id = ? AND key = ANY(?) AND value <> '' ORDER BY value LIMIT ?"
        ).use { query ->
            query.setObject(1, job.id)
            query.setArray(2, tenant.createArrayOf("text", columns.toTypedArray()))
            query.setInt(3, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    // ---- Rows ----

    data class Context(
        val stages: List<CrmStage>,
        val interests: List<CrmInterest>,
        val users: List<CrmUser>,
        val today: LocalDate,
        val source: String,
    )

    // A row reduced to CRM values.
    data class Values(
        val key: String,
        val name: String,
        val email: String,
        val phone: String,
        val stage: CrmStage?,
        val interestId: UUID?,
        val ownerId: UUID?,
        val fields: Map<String, String>,
        val notes: List<CrmImportNote>,
        val createdAt: LocalDate?,
    )

    fun values(row: Map<String, String>, mapping: CrmImportMapping, context: Context): Values {
        fun all(field: String) = mapping.columns.filterValues { it == field }.keys.mapNotNull { row[it]?.trim()?.ifEmpty { null } }
        fun first(field: String) = all(field).firstOrNull().orEmpty()
        val email = all("email").map { CrmContacts.fixEmail(it) }.firstOrNull { CrmContacts.isEmail(it) }.orEmpty()
        val phone = all("phone").map { CrmContacts.normalizePhone(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val name = first("name")
        // Stages in Spanish or English are matched by name when the map has no value for them.
        val stageValue = first("stage")
        val stageName = mapping.stages[stageValue] ?: stageValue
        val stage = context.stages.firstOrNull { it.name.equals(stageName, ignoreCase = true) }
        val interest = first("interest").let { value -> mapping.interests[value] ?: value }.let { name -> context.interests.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id }
        val owner = first("owner").let { value -> mapping.owners[value] ?: value }.let { name -> context.users.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id }
        val noteColumns = mapping.columns.filterValues { it == "note" }.keys.mapNotNull { column -> row[column]?.trim()?.ifEmpty { null }?.let { CrmImportNote(null, "", "$column: $it") } }
        val created = CrmImportParse.date(first("createdAt")) ?: CrmImportParse.date(row["__created"].orEmpty())
        val (start, end) = all("dates").firstOrNull()?.let { CrmImportParse.range(it, created?.year ?: context.today.year) } ?: (null to null)
        val key = row[KeyColumn]?.ifEmpty { null } ?: "${context.source}:" + sha("${email.lowercase()}|$phone|${name.lowercase()}")
        return Values(
            key = key.take(200),
            name = name,
            email = email,
            phone = phone,
            stage = stage,
            interestId = interest,
            ownerId = owner,
            fields = mapOf(
                "source" to first("source"),
                "language" to first("language"),
                "message" to all("message").joinToString("\n"),
                "details" to all("details").joinToString("\n"),
                "followUpOn" to (CrmImportParse.date(first("followUpOn"))?.toString().orEmpty()),
                "targetStart" to ((CrmImportParse.date(first("targetStart")) ?: start)?.toString().orEmpty()),
                "targetEnd" to ((CrmImportParse.date(first("targetEnd")) ?: end)?.toString().orEmpty()),
                "estimatedValue" to first("estimatedValue").filter { it.isDigit() || it == '.' },
            ),
            notes = notes(row[NotesColumn]) + noteColumns,
            createdAt = created,
        )
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    enum class Outcome(val status: String) { Created("created"), Updated("updated"), Skipped("skipped"), Conflict("conflict") }

    private fun leadByKey(tenant: Connection, key: String): UUID? =
        tenant.prepareStatement("SELECT id FROM crm_leads WHERE import_key = ?").use { query ->
            query.setString(1, key)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    private fun customerBy(tenant: Connection, condition: String, value: String): UUID? =
        tenant.prepareStatement("SELECT id FROM customers WHERE $condition AND status <> 'archived' ORDER BY created_at LIMIT 1").use { query ->
            query.setString(1, value)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    private fun customerByPhone(tenant: Connection, phone: String): UUID? =
        tenant.prepareStatement("SELECT customer_id FROM crm_identities WHERE channel = 'phone' AND external_id = ?").use { query ->
            query.setString(1, phone)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: customerBy(tenant, "primary_phone = ?", phone)

    /** What applying the row would do, without writing: used by the preview and by [apply]. */
    fun classify(tenant: Connection, values: Values): Pair<Outcome, String> {
        if (values.name.isEmpty() && values.email.isEmpty() && values.phone.isEmpty()) return Outcome.Skipped to "Sin nombre, correo ni telefono."
        if (leadByKey(tenant, values.key) != null) return Outcome.Updated to ""
        val byEmail = values.email.takeIf { it.isNotEmpty() }?.let { customerBy(tenant, "lower(primary_email) = lower(?)", it) }
        val byPhone = values.phone.takeIf { it.isNotEmpty() }?.let { customerByPhone(tenant, it) }
        // A phone and an email of different people are never merged: the row waits for review.
        if (byEmail != null && byPhone != null && byEmail != byPhone) return Outcome.Conflict to "El correo y el telefono pertenecen a clientes distintos."
        return Outcome.Created to ""
    }

    /** Applies one row: updates the lead imported before with the same key, or creates customer and lead. */
    fun apply(tenant: Connection, values: Values, context: Context, sourceLabel: String, listings: String?): Pair<Outcome, String> {
        val (outcome, message) = classify(tenant, values)
        if (outcome == Outcome.Skipped || outcome == Outcome.Conflict) return outcome to message
        val actor = CrmActor(null, "Importacion")
        val existing = leadByKey(tenant, values.key)
        val leadId = existing ?: run {
            val customer = values.email.takeIf { it.isNotEmpty() }?.let { customerBy(tenant, "lower(primary_email) = lower(?)", it) }
                ?: values.phone.takeIf { it.isNotEmpty() }?.let { customerByPhone(tenant, it) }
                ?: UUID.randomUUID().also { id ->
                    tenant.prepareStatement(
                        "INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type) VALUES (?, ?, ?, ?, 'prospect', 'crm')"
                    ).use { insert ->
                        insert.setObject(1, id)
                        insert.setString(2, values.name.ifEmpty { values.email.ifEmpty { values.phone } }.take(200))
                        insert.setString(3, values.email)
                        insert.setString(4, values.phone)
                        insert.executeUpdate()
                    }
                }
            if (values.phone.isNotEmpty()) CrmInbox.addIdentity(tenant, customer, "phone", values.phone)
            val stage = values.stage ?: context.stages.firstOrNull { it.kind == StageKind.Open } ?: context.stages.first()
            val id = UUID.randomUUID()
            val createdAt = values.createdAt?.atStartOfDay()?.toInstant(ZoneOffset.UTC) ?: Instant.now()
            tenant.prepareStatement(
                """
                INSERT INTO crm_leads (id, customer_id, stage_id, interest_id, owner_user_id, source, import_key, closed_at, created_at, stage_changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN ? END, ?, ?)
                """.trimIndent()
            ).use { insert ->
                insert.setObject(1, id)
                insert.setObject(2, customer)
                insert.setObject(3, stage.id)
                insert.setObject(4, values.interestId)
                insert.setObject(5, values.ownerId)
                insert.setString(6, values.fields["source"].orEmpty().ifEmpty { sourceLabel }.take(120))
                insert.setString(7, values.key)
                insert.setBoolean(8, stage.kind != StageKind.Open)
                insert.setTimestamp(9, Timestamp.from(createdAt))
                insert.setTimestamp(10, Timestamp.from(createdAt))
                insert.setTimestamp(11, Timestamp.from(createdAt))
                insert.executeUpdate()
            }
            CrmRepository.addActivity(tenant, id, "created", "Importado desde $sourceLabel en ${stage.name}.", actor, toStage = stage.id)
            id
        }
        updateFields(tenant, leadId, values, existing != null, context)
        addNotes(tenant, leadId, values.notes)
        listings?.let { raw ->
            runCatching { json.parseToJsonElement(raw) }.getOrNull().arr().forEach { element ->
                CrmWordPress.listing(element)?.let { CrmWordPress.linkListing(tenant, leadId, it, null) }
            }
        }
        return (if (existing != null) Outcome.Updated else Outcome.Created) to ""
    }

    // Fills the lead with the row values; on updates only non-empty values replace the current ones.
    private fun updateFields(tenant: Connection, leadId: UUID, values: Values, update: Boolean, context: Context) {
        fun text(key: String) = values.fields[key].orEmpty()
        fun date(key: String) = text(key).ifEmpty { null }?.let { LocalDate.parse(it) }
        tenant.prepareStatement(
            """
            UPDATE crm_leads SET
                language = CASE WHEN ? = '' THEN language ELSE ? END,
                message = CASE WHEN ? = '' THEN message ELSE ? END,
                details = CASE WHEN ? = '' THEN details ELSE ? END,
                follow_up_on = COALESCE(?, follow_up_on),
                target_start = COALESCE(?, target_start),
                target_end = COALESCE(?, target_end),
                estimated_value = COALESCE(?, estimated_value),
                interest_id = COALESCE(?, interest_id),
                owner_user_id = COALESCE(?, owner_user_id),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            val start = date("targetStart")
            // An end before the start is dropped (the table refuses it).
            val end = date("targetEnd")?.takeIf { start == null || !it.isBefore(start) }
            statement.setString(1, text("language")); statement.setString(2, text("language").take(40))
            statement.setString(3, text("message")); statement.setString(4, text("message").take(5000))
            statement.setString(5, text("details")); statement.setString(6, text("details").take(5000))
            statement.setObject(7, date("followUpOn"))
            statement.setObject(8, start)
            statement.setObject(9, end)
            statement.setBigDecimal(10, text("estimatedValue").toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it < BigDecimal("1000000000000") }?.setScale(2, java.math.RoundingMode.HALF_UP))
            statement.setObject(11, values.interestId)
            statement.setObject(12, values.ownerId)
            statement.setObject(13, leadId)
            statement.executeUpdate()
        }
        // On a new import of an existing lead the mapped stage wins, recorded in the stage history.
        if (update && values.stage != null) {
            CrmRepository.lead(tenant, leadId)?.let { lead ->
                if (lead.stageId != values.stage.id) CrmRepository.moveLead(tenant, lead, values.stage, context.stages, CrmActor(null, "Importacion"))
            }
        }
    }

    // Historical comments become notes with their original date; the same text is never added twice.
    private fun addNotes(tenant: Connection, leadId: UUID, notes: List<CrmImportNote>) {
        if (notes.isEmpty()) return
        val existing = tenant.prepareStatement("SELECT body FROM crm_activity WHERE lead_id = ? AND kind = 'note'").use { query ->
            query.setObject(1, leadId)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(normalize(rows.getString(1))) } }
        }.toMutableSet()
        tenant.prepareStatement(
            "INSERT INTO crm_activity (id, lead_id, kind, body, author, created_at) VALUES (?, ?, 'note', ?, ?, COALESCE(?, CURRENT_TIMESTAMP))"
        ).use { insert ->
            notes.forEach { note ->
                val body = note.text.trim().take(5000)
                if (body.isEmpty() || !existing.add(normalize(body))) return@forEach
                insert.setObject(1, UUID.randomUUID())
                insert.setObject(2, leadId)
                insert.setString(3, body)
                insert.setString(4, note.author.ifEmpty { "Importacion" }.take(200))
                insert.setTimestamp(5, note.date?.let { Timestamp.from(it.atStartOfDay().toInstant(ZoneOffset.UTC)) })
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private fun normalize(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()

    data class Preview(val created: Int, val updated: Int, val skipped: Int, val conflicts: Int)

    /** Counts what the import would do with every row, without writing anything. */
    fun preview(tenant: Connection, job: CrmImportJob, context: Context): Preview {
        var created = 0; var updated = 0; var skipped = 0; var conflicts = 0
        val seen = mutableSetOf<String>()
        rows(tenant, job, limit = CrmImportParse.MaxRows).forEach { (_, row, _) ->
            val values = values(row, job.mapping, context)
            when (classify(tenant, values).first) {
                // Rows repeated in the same file update the lead the first one creates.
                Outcome.Created -> if (seen.add(values.key)) created++ else updated++
                Outcome.Updated -> updated++
                Outcome.Skipped -> skipped++
                Outcome.Conflict -> conflicts++
            }
        }
        return Preview(created, updated, skipped, conflicts)
    }

    /** Applies up to [limit] pending rows of every running job. Each row is committed on its own. */
    fun runPending(tenant: Connection, limit: Int, today: LocalDate): Int {
        val running = jobQuery(tenant, "WHERE status = 'running' ORDER BY created_at")
        var done = 0
        running.forEach { job ->
            val context = Context(CrmRepository.stages(tenant), CrmRepository.interests(tenant), CrmRepository.users(tenant), today, job.source)
            val label = when (job.source) { "monday" -> "monday"; "leadspro" -> "CT Leads Pro"; else -> "archivo" }
            val pending = tenant.prepareStatement("SELECT row_no, data::text FROM crm_import_rows WHERE job_id = ? AND status = 'pending' ORDER BY row_no LIMIT ?").use { query ->
                query.setObject(1, job.id)
                query.setInt(2, limit - done)
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1) to rows.getString(2)) } }
            }
            pending.forEach { (rowNo, raw) ->
                val row = runCatching { json.parseToJsonElement(raw) }.getOrNull().obj()?.mapValues { it.value.str() }.orEmpty()
                val (status, message) = try {
                    transaction(tenant) {
                        val (outcome, text) = apply(tenant, values(row, job.mapping, context), context, label, row[ListingsColumn])
                        outcome.status to text
                    }
                } catch (failure: Exception) {
                    "error" to (failure.message ?: failure.javaClass.simpleName)
                }
                tenant.prepareStatement("UPDATE crm_import_rows SET status = ?, message = ? WHERE job_id = ? AND row_no = ?").use { update ->
                    update.setString(1, status)
                    update.setString(2, message.take(500))
                    update.setObject(3, job.id)
                    update.setInt(4, rowNo)
                    update.executeUpdate()
                }
                done++
            }
            tenant.prepareStatement(
                """
                UPDATE crm_import_jobs j SET
                    processed = s.processed, created = s.created, updated = s.updated, skipped = s.skipped, conflicts = s.conflicts, errors = s.errors,
                    status = CASE WHEN s.pending = 0 THEN 'done' ELSE j.status END, updated_at = CURRENT_TIMESTAMP
                FROM (
                    SELECT count(*) FILTER (WHERE status <> 'pending') AS processed, count(*) FILTER (WHERE status = 'created') AS created,
                           count(*) FILTER (WHERE status = 'updated') AS updated, count(*) FILTER (WHERE status = 'skipped') AS skipped,
                           count(*) FILTER (WHERE status = 'conflict') AS conflicts, count(*) FILTER (WHERE status = 'error') AS errors,
                           count(*) FILTER (WHERE status = 'pending') AS pending
                    FROM crm_import_rows WHERE job_id = ?
                ) s
                WHERE j.id = ?
                """.trimIndent()
            ).use { update ->
                update.setObject(1, job.id)
                update.setObject(2, job.id)
                update.executeUpdate()
            }
            if (done >= limit) return done
        }
        return done
    }

    private fun <T> transaction(tenant: Connection, block: () -> T): T {
        val previous = tenant.autoCommit
        tenant.autoCommit = false
        try {
            return block().also { tenant.commit() }
        } catch (failure: Exception) {
            tenant.rollback()
            throw failure
        } finally {
            tenant.autoCommit = previous
        }
    }
}
