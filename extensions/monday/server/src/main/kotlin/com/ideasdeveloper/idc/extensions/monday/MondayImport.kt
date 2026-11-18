package com.ideasdeveloper.idc.extensions.monday

import com.ideasdeveloper.idc.modules.crm.CrmAttachments
import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmExtensions
import com.ideasdeveloper.idc.modules.crm.CrmField
import com.ideasdeveloper.idc.modules.crm.CrmFieldType
import com.ideasdeveloper.idc.modules.crm.CrmFields
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmImport
import com.ideasdeveloper.idc.modules.crm.CrmImportException
import com.ideasdeveloper.idc.modules.crm.CrmImportJob
import com.ideasdeveloper.idc.modules.crm.CrmImportMapping
import com.ideasdeveloper.idc.modules.crm.CrmImportNote
import com.ideasdeveloper.idc.modules.crm.CrmImportParse
import com.ideasdeveloper.idc.modules.crm.CrmImportSource
import com.ideasdeveloper.idc.modules.crm.CrmImportSourceResult
import com.ideasdeveloper.idc.modules.crm.CrmImportTargets
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.modules.crm.CrmTable
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.server.net.PinnedHost
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.sql.Connection
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

data class MondayBoard(val id: String, val name: String, val workspace: String, val items: Int?)

/**
 * Importacion desde monday: lee tableros por la API GraphQL y los deja como un job de importacion. Todo lo propio de
 * monday vive aqui (y en [MondayFiles], [MondayMcpTools] y [MondayServerExtension]); el pipeline generico
 * del CRM no lo conoce. Solo depende de la superficie publica del CRM ([CrmHost], [CrmImport], [CrmImportSource]...).
 */
object MondayImport : CrmImportSource {
    const val Source = "monday"
    const val GroupColumn = "Grupo"
    const val BoardColumn = "Tablero"
    const val MainWorkspace = "Espacio principal"
    private const val MaxBytes = 20 * 1024 * 1024
    private const val MondayHost = "api.monday.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[0-9]{1,20}$")

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    // ---- CrmImportSource ----

    override val id = Source
    override val label = "monday"
    override val readingLabel = "Leyendo monday"
    override val extensionId: String = CrmExtensionIds.MondayImport

    override fun fieldType(columnType: String?) = crmType(columnType)

    override fun columnLabel(columnType: String) = " (monday: $columnType)"

    override fun importCards(context: CrmScreens.Context): List<UiNode> {
        val missing = CrmExtensions.missingNodes(context.tenant, CrmExtensionIds.MondayImport)
        val token = mondayToken(context.tenant)
        // Sin la extension de monday activa, en su lugar se muestra el aviso para instalarla.
        return missing ?: listOf(
            UiCard(
                listOfNotNull(
                    UiForm(
                        "mondayToken",
                        listOf(
                            UiField("token", "Token de la API de monday", type = "password", required = token == null,
                                help = "monday > tu avatar > Developers > Mi token. Se guarda para la empresa."),
                        ),
                        mapOf("token" to CrmHost.maskSecret(token)),
                        submit = UiButton("Guardar y cargar tableros", UiRequest(UiRoute + "/import", params = mapOf("op" to "mondayToken"), formId = "mondayToken")),
                    ),
                    token?.let {
                        UiRow(
                            listOf(
                                UiButton("Cargar tableros", UiNavigate("import", mapOf("monday" to "boards")), style = "secondary"),
                                UiButton("Quitar token", UiRequest(UiRoute + "/import", params = mapOf("op" to "mondayForget"), confirm = "Se borra el token de monday guardado. Continuar?"), style = "secondary"),
                            ),
                        )
                    },
                ),
                title = "monday",
            ),
        )
    }

    override fun importExtras(context: CrmScreens.Context, params: Map<String, String>): UiNode? {
        val token = mondayToken(context.tenant)
        val missing = CrmExtensions.missingNodes(context.tenant, CrmExtensionIds.MondayImport)
        return token?.takeIf { params["monday"] == "boards" && missing == null }?.let { boardsCard(it) }
    }

    override fun statusScreen(job: CrmImportJob, header: List<UiNode>): List<UiNode>? {
        val id = mapOf("job" to job.id.toString())
        return when (job.status) {
            "reading" -> header + listOf(
                UiText("Se estan leyendo ${job.boards.size} tablero(s) de monday con sus actualizaciones y subelementos. Un tablero grande tarda unos minutos; vuelve a abrir esta pantalla para seguir con el mapeo."),
                UiRow(
                    listOf(
                        UiButton("Actualizar", UiNavigate("importjob", id), style = "secondary"),
                        UiButton("Cancelar", UiRequest(UiRoute + "/importjob", params = id + ("op" to "cancel")), style = "danger"),
                    ),
                ),
            )
            "failed" -> header + listOf(
                UiText(job.error.ifEmpty { "No se pudieron leer los tableros." }, tone = UiTone.Danger),
                UiButton("Elegir tableros de nuevo", UiNavigate("import", mapOf("monday" to "boards")), style = "secondary"),
            )
            else -> null
        }
    }

    override fun mappingNotes(job: CrmImportJob): List<UiNode> = listOf(
        UiText(
            "Los archivos de las columnas que mapees a \"Archivos del lead\" y los adjuntos de las actualizaciones se descargan de monday al importar " +
                "(hasta ${CrmAttachments.MaxFileBytes / (1024 * 1024)} MB cada uno) y quedan en ${CrmFields.DocumentsLabel}. Las actualizaciones y sus respuestas se importan como notas.",
            UiTextStyle.Caption,
        ),
    )

    override fun describe(tenant: Connection, into: JsonObjectBuilder) {
        into.put("mondayTokenConfigured", mondayToken(tenant) != null)
    }

    /** Acciones de la pantalla de importar: guardar y quitar el token, e importar los tableros marcados. */
    override fun action(tenant: Connection, op: String, values: Map<String, String>, userId: UUID?): CrmImportSourceResult? = when (op) {
        "mondayToken" -> {
            val inactive = CrmHost.extensionInactiveReason(tenant, CrmExtensionIds.MondayImport)
            val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && !CrmHost.isSecretPlaceholder(it) }
            when {
                inactive != null -> CrmImportSourceResult(inactive, UiTone.Warning)
                typed == null && mondayToken(tenant) == null -> CrmImportSourceResult("Escribe el token de la API de monday.", UiTone.Danger)
                else -> {
                    typed?.let { saveMondayToken(tenant, it) }
                    CrmImportSourceResult("Token de monday guardado.", UiTone.Success, params = mapOf("monday" to "boards"))
                }
            }
        }
        "mondayForget" -> {
            saveMondayToken(tenant, null)
            CrmImportSourceResult("Token de monday borrado.", UiTone.Success)
        }
        "monday" -> {
            val inactive = CrmHost.extensionInactiveReason(tenant, CrmExtensionIds.MondayImport)
            when {
                inactive != null -> CrmImportSourceResult(inactive, UiTone.Warning)
                // Leer un tablero tarda minutos, mas de lo que espera la app: lo lee el trabajo de importacion.
                mondayToken(tenant) == null -> CrmImportSourceResult("Guarda primero el token de la API de monday.", UiTone.Danger)
                else -> {
                    val boards = values.filter { it.key.startsWith("board:") && it.value == "true" }.keys.map { it.removePrefix("board:") }
                    try {
                        val job = queueMonday(tenant, boards, userId)
                        CrmImportSourceResult("Se estan leyendo los tableros de monday.", UiTone.Success, "importjob", mapOf("job" to job.toString()))
                    } catch (failure: CrmImportException) {
                        CrmImportSourceResult(failure.message.orEmpty(), UiTone.Danger, params = mapOf("monday" to "boards"))
                    }
                }
            }
        }
        else -> null
    }

    override val mcpTools: List<McpTool> get() = MondayMcpTools.tools

    private const val UiRoute = CrmScreens.UiRoute

    /** Boards of the saved token grouped by workspace, each with a checkbox. */
    private fun boardsCard(token: String): UiNode {
        val boards = try {
            mondayBoards(token)
        } catch (failure: CrmImportException) {
            return UiCard(listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger)), title = "Tableros de monday")
        }
        if (boards.isEmpty()) return UiCard(listOf(UiText("El token no tiene acceso a ningun tablero activo.", tone = UiTone.Warning)), title = "Tableros de monday")
        val fields = boards.map { board ->
            UiField("board:${board.id}", board.name + board.items?.let { " ($it elementos)" }.orEmpty(), type = "boolean", section = board.workspace)
        }
        return UiCard(
            listOf(
                UiText("Marca los tableros que quieres importar. Cada tablero queda como interes."),
                UiForm(
                    "importMonday", fields,
                    submit = UiButton("Importar tableros marcados", UiRequest(UiRoute + "/import", params = mapOf("op" to "monday", "monday" to "boards"), formId = "importMonday")),
                    minFieldWidth = 320,
                ),
            ),
            title = "Tableros de monday",
        )
    }

    // ---- Layout and mapping of monday ----

    /**
     * Turns raw records of a monday export into a table: title lines before the header, one line per group
     * (a single cell) followed by the header again; the group goes to [GroupColumn].
     */
    fun table(records: List<List<String>>): CrmTable {
        if (records.isEmpty()) throw CrmImportException("El archivo no tiene filas.")
        val (headerIndex, names) = CrmImportParse.header(records)
        val header = records[headerIndex].map { it.trim() }
        var group = records.take(headerIndex).lastOrNull { it.count { cell -> cell.isNotBlank() } == 1 }?.first { it.isNotBlank() }?.trim().orEmpty()
        val grouped = records.drop(headerIndex + 1).any { it.count { cell -> cell.isNotBlank() } == 1 }
        val rows = mutableListOf<Map<String, String>>()
        records.drop(headerIndex + 1).forEach { record ->
            val filled = record.count { it.isNotBlank() }
            when {
                record.map { it.trim() } == header -> Unit
                grouped && filled == 1 && record.firstOrNull()?.isNotBlank() == true -> group = record.first().trim()
                else -> rows += names.mapIndexed { index, name -> name to record.getOrNull(index).orEmpty().trim() }.toMap() +
                    (if (grouped || group.isNotEmpty()) mapOf(GroupColumn to group) else emptyMap())
            }
        }
        val columns = names + listOfNotNull(GroupColumn.takeIf { grouped || group.isNotEmpty() })
        return CrmTable(columns, rows)
    }

    /** The monday group is the stage when no status column was found, and the board is the interest. */
    fun refine(columns: List<String>, guessed: Map<String, String>): Map<String, String> {
        var mapping = guessed
        if (GroupColumn in columns && "stage" !in mapping.values) mapping = mapping + (GroupColumn to "stage")
        if (BoardColumn in columns && "interest" !in mapping.values) mapping = mapping + (BoardColumn to "interest")
        return mapping
    }

    /** Field for each column of a monday table: the generic guess plus the group as stage and the board as interest. */
    fun guess(columns: List<String>): Map<String, String> = refine(columns, CrmImportParse.guess(columns))

    // CRM field type for a monday column type.
    fun crmType(mondayType: String?): Pair<CrmFieldType, Boolean> = when (mondayType) {
        "long_text" -> CrmFieldType.Textarea to false
        "numbers" -> CrmFieldType.Number to false
        "email" -> CrmFieldType.Email to false
        "phone" -> CrmFieldType.Phone to false
        "link" -> CrmFieldType.Url to false
        "date" -> CrmFieldType.Date to false
        "checkbox" -> CrmFieldType.Boolean to false
        "status" -> CrmFieldType.Dropdown to false
        "dropdown" -> CrmFieldType.Dropdown to true
        "file" -> CrmFieldType.Files to false
        else -> CrmFieldType.Text to false
    }

    /**
     * Where each monday file of the row goes: files of a column mapped to the lead's files land in
     * Documentos in a folder named after the column, those of a column mapped to a CRM files field
     * in that field; files of updates go to Documentos > Actualizaciones de monday > date.
     */
    fun filesOf(row: Map<String, String>, mapping: CrmImportMapping, crmFields: List<CrmField>): List<Pair<MondayFile, Pair<String, String>>> =
        MondayFiles.decode(row[MondayFiles.FilesColumn]).mapNotNull { file ->
            val place = when {
                file.update.isNotEmpty() -> CrmFields.Documents to "${MondayFiles.UpdatesFolder}/${file.update}"
                else -> when (val target = mapping.columns[file.column].orEmpty()) {
                    CrmImportTargets.Files -> CrmFields.Documents to file.column
                    else -> crmFields.firstOrNull { target == CrmImportTargets.CrmPrefix + it.key && it.type == CrmFieldType.Files }?.let { it.key to "" }
                }
            }
            place?.let { file to it }
        }

    // ---- API of monday ----

    /**
     * Items of monday boards through the GraphQL API: every column as text (and its type), the
     * group, the board (used as interest), the creation date, updates with their replies and
     * subitems as notes, the files of file columns and of updates, and the item id as key.
     */
    fun fromMonday(token: String, boardIds: List<String>): CrmTable {
        if (token.isBlank()) throw CrmImportException("Escribe el token de la API de monday.")
        if (boardIds.isEmpty() || boardIds.any { !it.matches(idPattern) }) throw CrmImportException("Escribe los ID de los tableros (numeros separados por coma).")
        val itemFields = "id name created_at group { title } column_values { type text value column { title } } " +
            "updates(limit: 100) { text_body created_at creator { name } assets { id name } replies { text_body created_at creator { name } } } " +
            "subitems { name column_values { column { title } text } }"
        val columns = linkedSetOf("Nombre")
        val types = mutableMapOf<String, String>()
        val rows = mutableListOf<Map<String, String>>()
        boardIds.forEach { boardId ->
            var data = monday(token, "query { boards(ids: [$boardId]) { name items_page(limit: 100) { cursor items { $itemFields } } } }")
            val board = data["boards"].arr().firstOrNull().obj() ?: throw CrmImportException("monday no devolvio el tablero $boardId (revisa el ID y los permisos del token).")
            val boardName = board["name"].str()
            var page = board["items_page"].obj()
            while (page != null) {
                page["items"].arr().mapNotNull { it.obj() }.forEach { item ->
                    val row = linkedMapOf("Nombre" to item["name"].str())
                    val files = mutableListOf<MondayFile>()
                    item["column_values"].arr().mapNotNull { it.obj() }.forEach { value ->
                        val title = value["column"].obj()?.get("title").str().ifEmpty { return@forEach }
                        columns += title
                        types.putIfAbsent(title, value["type"].str())
                        row[title] = value["text"].str()
                        if (value["type"].str() == "file") files += MondayFiles.fromColumnValue(title, value["value"].str())
                    }
                    row[GroupColumn] = item["group"].obj()?.get("title").str()
                    row[BoardColumn] = boardName
                    row[CrmImport.KeyColumn] = "monday:${item["id"].str()}"
                    row["__created"] = item["created_at"].str()
                    val notes = item["updates"].arr().mapNotNull { it.obj() }.flatMap { update ->
                        val date = CrmImportParse.date(update["created_at"].str())
                        val attached = update["assets"].arr().mapNotNull { it.obj() }.map { asset ->
                            MondayFile(asset["id"].str(), asset["name"].str(), update = date?.toString() ?: "sin-fecha")
                        }
                        files += attached
                        val text = update["text_body"].str() + if (attached.isEmpty()) "" else
                            "\nAdjuntos: ${attached.joinToString { it.name }} (en ${CrmFields.DocumentsLabel} > ${MondayFiles.UpdatesFolder})"
                        listOf(CrmImportNote(date, update["creator"].obj()?.get("name").str(), text.trim())) +
                            update["replies"].arr().mapNotNull { it.obj() }.map { reply ->
                                val author = reply["creator"].obj()?.get("name").str()
                                CrmImportNote(CrmImportParse.date(reply["created_at"].str()), author, "Respuesta${if (author.isEmpty()) "" else " de $author"}: ${reply["text_body"].str()}")
                            }
                    } + item["subitems"].arr().mapNotNull { it.obj() }.map { subitem ->
                        val details = subitem["column_values"].arr().mapNotNull { it.obj() }
                            .mapNotNull { value -> value["text"].str().ifEmpty { null }?.let { "${value["column"].obj()?.get("title").str()}: $it" } }
                        CrmImportNote(null, "monday", "Subelemento: ${subitem["name"].str()}${if (details.isEmpty()) "" else " (${details.joinToString()})"}")
                    }
                    row[CrmImport.NotesColumn] = CrmImport.notesJson(notes)
                    if (files.isNotEmpty()) row[MondayFiles.FilesColumn] = MondayFiles.encode(files)
                    rows += row
                    if (rows.size > CrmImportParse.MaxRows) throw CrmImportException("Los tableros tienen mas de ${CrmImportParse.MaxRows} elementos.")
                }
                val cursor = page["cursor"].str()
                if (cursor.isEmpty()) break
                data = monday(token, "query { next_items_page(limit: 100, cursor: \"$cursor\") { cursor items { $itemFields } } }")
                page = data["next_items_page"].obj()
            }
        }
        return CrmTable(columns.toList() + listOf(GroupColumn, BoardColumn), rows, types)
    }

    fun mondayToken(tenant: Connection): String? = MondaySettingsStore.loadToken(CrmHost, tenant)

    fun saveMondayToken(tenant: Connection, token: String?) {
        // Borrar el token siempre se puede; guardarlo exige la extension activa.
        if (token != null) CrmHost.extensionInactiveReason(tenant, CrmExtensionIds.MondayImport)?.let { throw CrmImportException(it) }
        MondaySettingsStore.saveToken(CrmHost, tenant, token)
    }

    /** Active boards the token can read, by workspace and name (subitem boards and docs left out). */
    fun mondayBoards(token: String): List<MondayBoard> {
        if (token.isBlank()) throw CrmImportException("Escribe el token de la API de monday.")
        val boards = mutableListOf<MondayBoard>()
        for (page in 1..50) {
            val data = monday(token, "query { boards(limit: 100, page: $page, state: active) { id name type items_count workspace { name } } }")
            boards += parseMondayBoards(data)
            if (data["boards"].arr().size < 100) break
        }
        return boards.sortedWith(compareBy<MondayBoard>({ it.workspace != MainWorkspace }, { it.workspace.lowercase() }, { it.name.lowercase() }))
    }

    fun parseMondayBoards(data: JsonObject): List<MondayBoard> = data["boards"].arr().mapNotNull { it.obj() }
        .filter { it["type"].str().ifEmpty { "board" } == "board" }
        .mapNotNull { board ->
            val id = board["id"].str().takeIf { it.matches(idPattern) } ?: return@mapNotNull null
            MondayBoard(id, board["name"].str().ifEmpty { id }, board["workspace"].obj()?.get("name").str().ifEmpty { MainWorkspace }, board["items_count"].str().toIntOrNull())
        }

    internal fun mondayQuery(token: String, query: String): JsonObject = monday(token, query)

    private fun monday(token: String, query: String): JsonObject {
        val payload = JsonObject(mapOf("query" to JsonPrimitive(query))).toString().toByteArray(Charsets.UTF_8)
        // Se resuelve una vez y la conexion va a esa IP validada (anti DNS rebinding).
        val (status, text) = try {
            val validated = PinnedHost.resolveValidated(MondayHost)
            PinnedHost.openPinnedHttps(
                URI.create("https://$MondayHost/v2"), validated, MondayHost, "POST",
                mapOf("Authorization" to token.trim(), "API-Version" to "2024-10", "Content-Type" to "application/json"),
                payload, timeoutMillis = 30_000, totalMillis = 60_000, maxBodyBytes = MaxBytes + 1L,
            ).use { response -> response.status to response.body.readNBytes(MaxBytes + 1).decodeToString() }
        } catch (failure: Exception) {
            throw CrmImportException("No se pudo contactar monday.")
        }
        val body = runCatching { json.parseToJsonElement(text) }.getOrNull().obj()
        if (status == 401 || status == 403) throw CrmImportException("monday rechazo el token.")
        body?.get("errors").arr().firstOrNull()?.let { throw CrmImportException("monday: ${it.obj()?.get("message").str()}") }
        return body?.get("data").obj() ?: throw CrmImportException("monday respondio HTTP $status.")
    }

    // ---- Job: cola, lectura y aplicacion ----

    /** Queues monday boards for the import job, which reads them and leaves the job ready to map. */
    fun queueMonday(tenant: Connection, boardIds: List<String>, userId: UUID?): UUID {
        CrmHost.extensionInactiveReason(tenant, CrmExtensionIds.MondayImport)?.let { throw CrmImportException(it) }
        if (boardIds.isEmpty() || boardIds.any { !it.matches(idPattern) }) throw CrmImportException("Marca al menos un tablero.")
        val id = UUID.randomUUID()
        CrmImport.insertJob(tenant, id, Source, "monday ${boardIds.joinToString()}", "reading", boardIds, userId)
        return id
    }

    /** Reads the queued monday boards; a job canceled meanwhile stays canceled. */
    override fun readQueued(tenant: Connection) {
        CrmImport.jobQuery(tenant, "WHERE status = 'reading' AND source = '$Source' ORDER BY created_at").forEach { job ->
            val table = try {
                CrmHost.extensionInactiveReason(tenant, CrmExtensionIds.MondayImport)?.let { throw CrmImportException(it) }
                val token = mondayToken(tenant) ?: throw CrmImportException("Se borro el token de monday; guardalo de nuevo.")
                fromMonday(token, job.boards).also { if (it.rows.isEmpty()) throw CrmImportException("Los tableros marcados no tienen elementos.") }
            } catch (failure: Exception) {
                tenant.prepareStatement("UPDATE crm_import_jobs SET status = 'failed', error = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'reading'").use { update ->
                    update.setString(1, (failure as? CrmImportException)?.message ?: "No se pudieron leer los tableros de monday.")
                    update.setObject(2, job.id)
                    update.executeUpdate()
                }
                return@forEach
            }
            val names = table.rows.mapNotNull { it[BoardColumn]?.takeIf(String::isNotEmpty) }.distinct()
            CrmImport.transaction(tenant) {
                CrmImport.storeRows(
                    tenant, job.id, "monday ${names.joinToString()}", table, from = "reading",
                    refine = ::refine, noProposal = setOf(GroupColumn, BoardColumn),
                )
            }
        }
    }

    /**
     * Applies pending rows of the running monday jobs with the generic runner; the files of each row are downloaded
     * afterwards into [files] (left out without it). Monday jobs wait while its extension is not active.
     */
    override fun runPending(tenant: Connection, limit: Int, today: LocalDate, files: ModuleFiles?, budget: Duration): Int {
        // Las importaciones de monday esperan a que su extension vuelva a estar activa.
        if (!CrmHost.extensionEnabled(tenant, CrmExtensionIds.MondayImport)) return 0
        val token by lazy { mondayToken(tenant) }
        val crmFields by lazy { CrmFields.all(tenant) }
        return CrmImport.runPending(
            tenant, limit, today, budget,
            accept = { it.source == Source },
            label = { "monday" },
        ) { job, row, values, status, message ->
            val wanted = if (status == "created" || status == "updated") filesOf(row, job.mapping, crmFields) else emptyList()
            val apiToken = if (wanted.isEmpty()) null else token
            val problems = when {
                wanted.isEmpty() || files == null -> emptyList()
                apiToken == null -> listOf("sin token de monday para descargar los archivos")
                else -> CrmImport.leadByKey(tenant, values.key)?.let { leadId ->
                    val places = wanted.toMap()
                    MondayFiles.import(tenant, files, apiToken, leadId, wanted.map { it.first }) { places[it] }
                }.orEmpty()
            }
            if (problems.isEmpty()) message
            else listOf(message, "Archivos: " + problems.take(5).joinToString("; ")).filter { it.isNotEmpty() }.joinToString(" | ")
        }
    }
}
