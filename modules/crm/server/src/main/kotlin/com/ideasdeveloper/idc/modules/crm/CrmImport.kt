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
import java.net.URI
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import com.ideasdeveloper.idc.server.net.PinnedHost
import com.ideasdeveloper.idc.server.net.PinnedHostException

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
    // Ids de origen que una fuente externa aun debe leer (estado reading) y por que fallo un job.
    val boards: List<String> = emptyList(),
    val error: String = "",
    // Tipo de cada columna como lo informo la fuente, para tipar los campos que crea la importacion.
    val columnTypes: Map<String, String> = emptyMap(),
)

data class CrmImportNote(val date: LocalDate?, val author: String, val text: String)

/** Imports of leads from files (CSV, XLSX, URL), applied in batches by a job. Las fuentes externas se registran en [CrmImportSources]. */
object CrmImport {
    private const val MaxBytes = 20 * 1024 * 1024
    const val KeyColumn = "__key"
    const val NotesColumn = "__notes"
    const val ListingsColumn = "__listings"
    private val json = Json { ignoreUnknownKeys = true }

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
            // Una sola resolucion por salto: se valida y la conexion va a esa misma IP (anti DNS rebinding).
            val validated = try {
                PinnedHost.resolveValidated(uri.host)
            } catch (failure: PinnedHostException) {
                throw CrmImportException(if (failure.message.orEmpty().startsWith("No se encontro")) "No se encontro ${uri.host}." else "Esa direccion apunta a una red privada.")
            }
            // Todo dentro de use: el socket se cierra aunque algo falle entre abrir y leer.
            val (location, bytes) = try {
                PinnedHost.openPinnedHttps(uri, validated, uri.host, timeoutMillis = 30_000, totalMillis = 120_000, maxBodyBytes = MaxBytes + 1L).use { response ->
                    when {
                        response.status in 300..399 -> response.header("Location").orEmpty() to null
                        response.status !in 200..299 -> throw CrmImportException("La descarga respondio HTTP ${response.status}.")
                        else -> null to response.body.readNBytes(MaxBytes + 1)
                    }
                }
            } catch (failure: CrmImportException) {
                throw failure
            } catch (failure: Exception) {
                throw CrmImportException("No se pudo descargar el archivo.")
            }
            if (bytes == null) {
                current = runCatching { uri.resolve(location.orEmpty()).toString() }.getOrElse { throw CrmImportException("Usa una direccion https:// publica.") }
                return@repeat
            }
            if (bytes.size > MaxBytes) throw CrmImportException("El archivo supera los 20 MB.")
            return if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) CrmImportParse.xlsx(bytes) else CrmImportParse.csv(bytes.decodeToString())
        }
        throw CrmImportException("Demasiadas redirecciones.")
    }

    /** Notes of a row as the JSON stored in [NotesColumn]. */
    fun notesJson(notes: List<CrmImportNote>) = JsonArray(
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

    /** Stores the rows of a source and guesses the mapping. */
    fun create(tenant: Connection, source: String, name: String, table: CrmTable, userId: UUID?): UUID {
        if (table.rows.isEmpty()) throw CrmImportException("No hay filas para importar.")
        val id = UUID.randomUUID()
        transaction(tenant) {
            insertJob(tenant, id, source, name, "mapping", emptyList(), userId)
            storeRows(tenant, id, name, table)
        }
        return id
    }

    /** Inserts a job; [boards] are the source ids an external source still has to read (status "reading"). */
    fun insertJob(tenant: Connection, id: UUID, source: String, name: String, status: String, boards: List<String>, userId: UUID?) {
        tenant.prepareStatement(
            "INSERT INTO crm_import_jobs (id, source, name, status, boards, created_by) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)"
        ).use { insert ->
            insert.setObject(1, id)
            insert.setString(2, source)
            insert.setString(3, name.take(200))
            insert.setString(4, status)
            insert.setString(5, JsonArray(boards.map { JsonPrimitive(it) }).toString())
            insert.setObject(6, userId)
            insert.executeUpdate()
        }
    }

    /**
     * Stores the rows of a job and guesses the mapping; the job moves to mapping. [from] is the status the job must have.
     * Una fuente externa puede afinar la adivinanza con [refine] (columnas, mapeo adivinado -> mapeo final) y marcar en
     * [noProposal] las columnas a las que no se les propone un campo nuevo.
     */
    fun storeRows(
        tenant: Connection,
        id: UUID,
        name: String,
        table: CrmTable,
        from: String = "mapping",
        refine: (List<String>, Map<String, String>) -> Map<String, String> = { _, guessed -> guessed },
        noProposal: Set<String> = emptySet(),
    ) {
        val columns = table.columns.filter { !it.startsWith("__") }
        var guessed = refine(columns, CrmImportParse.guess(columns))
        // Columns the CRM has no field for: an existing custom field with that name, or a new one to confirm.
        val crmFields = CrmFields.all(tenant)
        val customerFields = CrmImportTargets.customerFields(tenant)
        guessed = guessed.mapValues { (column, field) ->
            if (field.isNotEmpty() || column in noProposal) field
            else CrmImportTargets.propose(column, table.types[column], table.rows.any { !it[column].isNullOrBlank() }, crmFields, customerFields)
        }
        val moved = tenant.prepareStatement(
            "UPDATE crm_import_jobs SET name = ?, status = 'mapping', mapping = CAST(? AS jsonb), columns = CAST(? AS jsonb), total = ?, column_types = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ?"
        ).use { update ->
            update.setString(1, name.take(200))
            update.setString(2, mappingJson(CrmImportMapping(guessed)).toString())
            update.setString(3, JsonArray(columns.map { JsonPrimitive(it) }).toString())
            update.setInt(4, table.rows.size)
            update.setString(5, JsonObject(table.types.filterKeys { it in columns }.mapValues { JsonPrimitive(it.value) }).toString())
            update.setObject(6, id)
            update.setString(7, from)
            update.executeUpdate()
        }
        if (moved == 0) return
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

    fun jobs(tenant: Connection, limit: Int = 20): List<CrmImportJob> = jobQuery(tenant, "ORDER BY created_at DESC LIMIT $limit")

    fun job(tenant: Connection, id: UUID): CrmImportJob? = jobQuery(tenant, "WHERE id = ?") { it.setObject(1, id) }.firstOrNull()

    /** Jobs filtered by a SQL tail ("WHERE status = 'reading' ORDER BY created_at"); used by the runners of the sources. */
    fun jobQuery(tenant: Connection, tail: String, bind: (java.sql.PreparedStatement) -> Unit = {}): List<CrmImportJob> = tenant.prepareStatement(
        "SELECT id, source, name, status, mapping::text, columns::text, total, processed, created, updated, skipped, conflicts, errors, created_at, boards::text, error, column_types::text FROM crm_import_jobs $tail"
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
                        runCatching { json.parseToJsonElement(rows.getString(15)) }.getOrNull().arr().map { it.str() },
                        rows.getString(16),
                        runCatching { json.parseToJsonElement(rows.getString(17)) }.getOrNull().obj()?.mapValues { it.value.str() }.orEmpty(),
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

    /** Different values of one column, for the options of the dropdown fields the import creates. */
    fun columnValues(tenant: Connection, job: CrmImportJob, column: String, limit: Int = 200): List<String> = tenant.prepareStatement(
        "SELECT DISTINCT data ->> ? FROM crm_import_rows WHERE job_id = ? AND coalesce(data ->> ?, '') <> '' LIMIT ?"
    ).use { query ->
        query.setString(1, column)
        query.setObject(2, job.id)
        query.setString(3, column)
        query.setInt(4, limit)
        query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    /**
     * Saves the mapping of the form, creating first the fields its columns ask for ("Crear campo...").
     * Returns the labels of the created fields.
     */
    fun saveMappingCreatingFields(tenant: Connection, job: CrmImportJob, mapping: CrmImportMapping): List<String> = transaction(tenant) {
        // La fuente externa del job (si la tiene) tipa las columnas y marca los campos que crea con su extension.
        val (columns, created) = CrmImportTargets.createMissing(
            tenant, mapping.columns, job.columnTypes,
            extensionId = CrmImportSources.of(job.source)?.extensionId,
            fieldType = { columnType -> CrmImportSources.fieldType(job.source, columnType) },
        ) { column -> columnValues(tenant, job, column) }
        saveMapping(tenant, job, mapping.copy(columns = columns))
        created
    }

    /**
     * Different values of the columns mapped to [field] (for the stage, owner and interest maps). The people column
     * ("owner") can hold several people in one cell ("Ana, Beto"): each person is a value of its own.
     */
    fun distinctValues(tenant: Connection, job: CrmImportJob, field: String, limit: Int = 60): List<String> {
        val columns = job.mapping.columns.filterValues { it == field }.keys
        if (columns.isEmpty()) return emptyList()
        val values = tenant.prepareStatement(
            "SELECT DISTINCT value FROM crm_import_rows, jsonb_each_text(data) AS kv(key, value) WHERE job_id = ? AND key = ANY(?) AND value <> '' ORDER BY value LIMIT ?"
        ).use { query ->
            query.setObject(1, job.id)
            query.setArray(2, tenant.createArrayOf("text", columns.toTypedArray()))
            // The limit of the people column applies after splitting the cells into people.
            query.setInt(3, if (field == "owner") 5000 else limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        return if (field == "owner") values.flatMap { CrmAssignees.parseList(it) }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.take(limit) else values
    }

    // ---- Rows ----

    data class Context(
        val stages: List<CrmStage>,
        val interests: List<CrmInterest>,
        val users: List<CrmUser>,
        val today: LocalDate,
        val source: String,
        val crmFields: List<CrmField> = emptyList(),
        val customerFields: List<CustomerField> = emptyList(),
    )

    // A row reduced to CRM values.
    data class Values(
        val key: String,
        val name: String,
        val email: String,
        val phone: String,
        val stage: CrmStage?,
        val interestId: UUID?,
        // Users matched to the people of the row's assigned column (all of them) and the people that had no user.
        val ownerIds: List<UUID>,
        val ownersUnmatched: List<String>,
        val fields: Map<String, String>,
        val notes: List<CrmImportNote>,
        val createdAt: LocalDate?,
        // Custom fields of the CRM and of the customer, by key, with the source text.
        val crm: Map<String, String> = emptyMap(),
        val customer: Map<String, String> = emptyMap(),
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
        // Several people in the column (or in several columns): each one is matched by the equivalences, email or name.
        val owners = CrmAssignees.match(all("owner").flatMap { CrmAssignees.parseList(it) }.distinctBy { it.lowercase() }, context.users, mapping.owners)
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
            ownerIds = owners.ids,
            ownersUnmatched = owners.unmatched,
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
            crm = custom(row, mapping, CrmImportTargets.CrmPrefix),
            customer = custom(row, mapping, CrmImportTargets.CustomerPrefix),
        )
    }

    // Text of the columns mapped to custom fields of one kind, by field key (several columns: joined).
    private fun custom(row: Map<String, String>, mapping: CrmImportMapping, prefix: String): Map<String, String> =
        mapping.columns.filterValues { it.startsWith(prefix) }.entries
            .groupBy({ it.value.removePrefix(prefix) }, { row[it.key]?.trim().orEmpty() })
            .mapValues { (_, texts) -> texts.filter { it.isNotEmpty() }.joinToString(", ") }
            .filterValues { it.isNotEmpty() }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    enum class Outcome(val status: String) { Created("created"), Updated("updated"), Skipped("skipped"), Conflict("conflict") }

    /** The lead imported with that key (import_key), if any. */
    fun leadByKey(tenant: Connection, key: String): UUID? =
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
                INSERT INTO crm_leads (id, customer_id, stage_id, interest_id, source, import_key, closed_at, created_at, stage_changed_at)
                VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN CAST(? AS timestamptz) END, ?, ?)
                """.trimIndent()
            ).use { insert ->
                insert.setObject(1, id)
                insert.setObject(2, customer)
                insert.setObject(3, stage.id)
                insert.setObject(4, values.interestId)
                insert.setString(5, values.fields["source"].orEmpty().ifEmpty { sourceLabel }.take(120))
                insert.setString(6, values.key)
                insert.setBoolean(7, stage.kind != StageKind.Open)
                insert.setTimestamp(8, Timestamp.from(createdAt))
                insert.setTimestamp(9, Timestamp.from(createdAt))
                insert.setTimestamp(10, Timestamp.from(createdAt))
                insert.executeUpdate()
            }
            CrmRepository.addActivity(tenant, id, "created", "Importado desde $sourceLabel en ${stage.name}.", actor, toStage = stage.id)
            id
        }
        updateFields(tenant, leadId, values, existing != null, context)
        // Importing again adds the people that are new and never repeats the ones the lead already has.
        val added = CrmAssignees.add(tenant, leadId, values.ownerIds)
        if (added.isNotEmpty()) {
            CrmRepository.addActivity(tenant, leadId, "owner", "Asignados desde la importacion: ${CrmAssignees.names(CrmNames(context.users, context.interests), added)}.", actor)
        }
        val problems = CrmImportTargets.apply(tenant, leadId, values.crm, values.customer, context.crmFields, context.customerFields) +
            // The dates of the row go to the mapped stay fields (the check-in and check-out); without mapping they are ignored and reported.
            listOfNotNull(
                CrmStay.importDates(
                    tenant, leadId,
                    values.fields["targetStart"].orEmpty().ifEmpty { null }?.let { LocalDate.parse(it) },
                    values.fields["targetEnd"].orEmpty().ifEmpty { null }?.let { LocalDate.parse(it) },
                ),
            )
        addNotes(tenant, leadId, values.notes)
        listings?.let { raw ->
            runCatching { json.parseToJsonElement(raw) }.getOrNull().arr().forEach { element ->
                WpListing.parse(element)?.let { CrmHost.wordpressLinkListing(tenant, leadId, it, null) }
            }
        }
        // The people of the row without a CRM user are not assigned and are listed in the report (users are never created here).
        val report = problems + listOfNotNull(CrmAssignees.unmatchedNote(values.ownersUnmatched).takeIf { it.isNotEmpty() })
        return (if (existing != null) Outcome.Updated else Outcome.Created) to report.joinToString("; ")
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
                estimated_value = COALESCE(?, estimated_value),
                interest_id = COALESCE(?, interest_id),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, text("language")); statement.setString(2, text("language").take(40))
            statement.setString(3, text("message")); statement.setString(4, text("message").take(5000))
            statement.setString(5, text("details")); statement.setString(6, text("details").take(5000))
            statement.setObject(7, date("followUpOn"))
            statement.setBigDecimal(8, text("estimatedValue").toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it < BigDecimal("1000000000000") }?.setScale(2, java.math.RoundingMode.HALF_UP))
            statement.setObject(9, values.interestId)
            statement.setObject(10, leadId)
            statement.executeUpdate()
        }
        // On a new import of an existing lead the mapped stage wins, recorded in the stage history.
        if (update && values.stage != null) {
            CrmRepository.lead(tenant, leadId)?.let { lead ->
                if (lead.archivedAt == null && lead.stageId != values.stage.id) CrmAutomationEvents.suppressed { CrmRepository.moveLead(tenant, lead, values.stage, context.stages, CrmActor(null, "Importacion")) }
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

    /**
     * Applies up to [limit] pending rows of every running job that [accept] takes. Each row is committed on its own.
     * Stops after [budget] so a long run leaves the rest to the next one.
     * Extension points of the external sources (see [CrmImportSources]): [accept] chooses the jobs (the default
     * runs all of them), [label] names the source in the history of the lead, and [afterRow] runs after each row
     * applied with its job, source row, values, status and message and returns the message to store (for example to
     * add what could not be downloaded).
     */
    fun runPending(
        tenant: Connection,
        limit: Int,
        today: LocalDate,
        budget: Duration = Duration.ofSeconds(50),
        accept: (CrmImportJob) -> Boolean = { true },
        label: (CrmImportJob) -> String = { "archivo" },
        afterRow: (CrmImportJob, Map<String, String>, Values, String, String) -> String = { _, _, _, _, message -> message },
    ): Int {
        val running = jobQuery(tenant, "WHERE status = 'running' ORDER BY created_at")
        val deadline = Instant.now().plus(budget)
        var done = 0
        running.forEach { job ->
            if (!accept(job)) return@forEach
            val context = Context(
                CrmRepository.stages(tenant), CrmRepository.interests(tenant), CrmRepository.users(tenant), today, job.source,
                CrmFields.all(tenant), CrmImportTargets.customerFields(tenant),
            )
            val sourceLabel = label(job)
            val pending = tenant.prepareStatement("SELECT row_no, data::text FROM crm_import_rows WHERE job_id = ? AND status = 'pending' ORDER BY row_no LIMIT ?").use { query ->
                query.setObject(1, job.id)
                query.setInt(2, limit - done)
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1) to rows.getString(2)) } }
            }
            for ((rowNo, raw) in pending) {
                if (Instant.now().isAfter(deadline)) break
                val row = runCatching { json.parseToJsonElement(raw) }.getOrNull().obj()?.mapValues { it.value.str() }.orEmpty()
                val values = values(row, job.mapping, context)
                var (status, message) = try {
                    transaction(tenant) {
                        val (outcome, text) = apply(tenant, values, context, sourceLabel, row[ListingsColumn])
                        outcome.status to text
                    }
                } catch (failure: Exception) {
                    "error" to (failure.message ?: failure.javaClass.simpleName)
                }
                message = afterRow(job, row, values, status, message)
                tenant.prepareStatement("UPDATE crm_import_rows SET status = ?, message = ? WHERE job_id = ? AND row_no = ?").use { update ->
                    update.setString(1, status)
                    update.setString(2, message.take(500))
                    update.setObject(3, job.id)
                    update.setInt(4, rowNo)
                    update.executeUpdate()
                }
                done++
            }
            if (Instant.now().isAfter(deadline)) return done
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

    fun <T> transaction(tenant: Connection, block: () -> T): T {
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
