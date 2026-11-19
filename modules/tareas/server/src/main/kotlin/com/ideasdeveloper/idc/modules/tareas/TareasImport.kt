package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.FileTooLargeException
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiNode
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A place data can be imported from (for example Monday). Extensions of the module implement it: Tareas lists the active
 * ones in the "Importar" screen, runs their pending work in the `import-runner` job and offers their agent tools.
 */
interface TareasImportSource {
    /** The extension id, for example "tareas_monday". */
    val sourceId: String

    /** What the user sees, for example "Monday". */
    val title: String

    fun enabled(tenant: Connection, companyCode: String): Boolean

    /** The body of the import screen for this source ([params] are the query parameters of the screen). */
    fun screen(context: TareasImportContext, params: Map<String, String>): List<UiNode>

    /** An operation of the screen (POST); [values] are what the form sent. */
    fun action(context: TareasImportContext, op: String, values: Map<String, String>): TareasImportReply

    /** Tools for agents (permission tareas.import); Tareas adds them to its own. */
    val mcpTools: List<McpTool> get() = emptyList()

    /** Moves reading and applying forward and returns the rows processed. Short, idempotent, errors logged. */
    fun runPending(context: TareasImportJobContext, budget: Duration): Int
}

class TareasImportContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val permissions: ModulePermissions,
    val author: String,
    val today: LocalDate,
)

class TareasImportJobContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val files: ModuleFiles,
    val today: LocalDate,
)

/** The answer to an action: the notice shown, whether it is an error, and the parameters of the screen shown next. */
data class TareasImportReply(val notice: String, val error: Boolean = false, val params: Map<String, String> = emptyMap())

object TareasImportSources {
    private val log = LoggerFactory.getLogger(TareasImportSources::class.java)

    @Volatile
    private var linked: List<TareasImportSource> = emptyList()

    /** Called once at startup with the extensions of the module; keeps the ones that are import sources. */
    fun link(extensions: List<ServerExtension>) {
        linked = extensions.filterIsInstance<TareasImportSource>()
    }

    val all: List<TareasImportSource> get() = linked

    /** The sources the company has enabled. A source that fails to answer counts as disabled. */
    fun enabled(tenant: Connection, companyCode: String): List<TareasImportSource> = all.filter { source ->
        runCatching { source.enabled(tenant, companyCode) }
            .onFailure { log.warn("No se pudo comprobar la fuente de importación {} ({}).", source.sourceId, it.javaClass.simpleName) }
            .getOrDefault(false)
    }

    fun find(id: String): TareasImportSource? = all.firstOrNull { it.sourceId == id }

    /** Runs the enabled sources one after another, each with an equal share of what is left of [budget]. */
    fun runPending(context: TareasImportJobContext, budget: Duration): Int {
        val sources = enabled(context.tenant, context.companyCode)
        val started = Instant.now()
        var processed = 0
        sources.forEachIndexed { index, source ->
            val remaining = budget.minus(Duration.between(started, Instant.now()))
            if (remaining.isNegative || remaining.isZero) return processed
            val share = remaining.dividedBy((sources.size - index).toLong())
            processed += try {
                source.runPending(context, share)
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("Falló la importación de {} ({}).", source.sourceId, failure.javaClass.simpleName)
                0
            }
        }
        return processed
    }
}

data class ImportedCheck(val key: String, val text: String, val done: Boolean)

data class ImportedComment(val key: String, val author: String, val at: Instant?, val body: String)

data class ImportedTask(
    /** The id of the item in the source: it is the task's external_ref. */
    val key: String,
    val title: String,
    val detail: String = "",
    val groupName: String? = null,
    val status: TaskStatus = TaskStatus.Pending,
    val priority: TaskPriority = TaskPriority.Medium,
    val dueDate: LocalDate? = null,
    val assigneeEmails: List<String> = emptyList(),
    /** People that came without an email. */
    val assigneeNames: List<String> = emptyList(),
    val reviewerEmail: String? = null,
    val reviewerName: String? = null,
    val checklist: List<ImportedCheck> = emptyList(),
    val comments: List<ImportedComment> = emptyList(),
)

data class ImportSummary(
    val tasks: Int,
    val groups: List<String>,
    val checklistItems: Int,
    val comments: Int,
    val files: Int,
    val matchedPeople: List<String>,
    val unmatchedPeople: List<String>,
    val problems: List<String>,
)

sealed class ImportOutcome {
    data class Created(val taskId: UUID) : ImportOutcome()
    data class Updated(val taskId: UUID) : ImportOutcome()
    data class Skipped(val reason: String) : ImportOutcome()
}

/** Writes imported rows into Tareas. Every row is its own transaction, idempotent, and never sends notices. */
object TareasImportApply {
    private val log = LoggerFactory.getLogger(TareasImportApply::class.java)
    private val StampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private const val MaxBoardName = 120
    private const val MaxGroupName = 80
    private const val MaxChecklistText = 300
    private const val MaxAuthor = 120
    private const val ImportAuthor = "Importación"

    // ---- pure rules ----

    /** Cuts [text] to [max] characters ending with "…" instead of failing. */
    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    /** The user whose email is [email], ignoring case and spaces. */
    fun matchUser(email: String?, users: List<TareasUser>): TareasUser? {
        val wanted = email?.trim()?.lowercase().orEmpty()
        if (wanted.isEmpty()) return null
        return users.firstOrNull { it.email.trim().lowercase() == wanted }
    }

    /** "[Autor · fecha] texto", the body of an imported comment (at most the comment limit). */
    fun commentText(author: String, at: Instant?, body: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val head = listOfNotNull(author.trim().ifEmpty { null }, at?.atZone(zone)?.format(StampFormat)).joinToString(" · ")
        val text = if (head.isEmpty()) body.trim() else "[$head] ${body.trim()}"
        return clip(text, TareasRepository.MaxCommentLength)
    }

    /** The detail followed by the people that had no user in Tareas; the notes are kept and the detail gives up room for them. */
    fun detailWithNotes(detail: String, unmatchedAssignees: List<String>, unmatchedReviewers: List<String>): String {
        val notes = listOfNotNull(
            unmatchedAssignees.takeIf { it.isNotEmpty() }?.let { "Responsables en Monday sin usuario: ${it.joinToString(", ")}" },
            unmatchedReviewers.takeIf { it.isNotEmpty() }?.let { "Revisor en Monday sin usuario: ${it.joinToString(", ")}" },
        ).joinToString("\n")
        val base = detail.trim()
        if (notes.isEmpty()) return clip(base, TaskInput.MaxDetail)
        if (base.isEmpty()) return clip(notes, TaskInput.MaxDetail)
        val room = (TaskInput.MaxDetail - notes.length - 2).coerceAtLeast(0)
        return if (room == 0) clip(notes, TaskInput.MaxDetail) else clip(base, room) + "\n\n" + notes
    }

    /** An open task due tomorrow or before: its reminders are recorded as sent so an import does not notify about old tasks. */
    fun suppressesReminders(status: TaskStatus, dueDate: LocalDate?, today: LocalDate): Boolean =
        status.isOpen && dueDate != null && !dueDate.isAfter(today.plusDays(1))

    /** What an import would do, for the preview. Pure. */
    fun summary(rows: List<ImportedTask>, users: List<TareasUser>, files: Int = 0): ImportSummary {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val valid = mutableListOf<ImportedTask>()
        rows.forEach { row ->
            val key = row.key.trim()
            when {
                key.isEmpty() -> problems += "Un ítem no tiene identificador y se omitirá."
                row.title.isBlank() -> problems += "El ítem $key no tiene título y se omitirá."
                !seen.add(key) -> problems += "El ítem $key aparece más de una vez."
                else -> valid += row
            }
        }
        val groups = valid.mapNotNull { it.groupName?.trim()?.takeIf { name -> name.isNotEmpty() } }.distinctBy { it.lowercase() }
        val matched = linkedMapOf<String, String>()
        val unmatched = linkedMapOf<String, String>()
        fun person(email: String?, name: String?) {
            val user = matchUser(email, users)
            val cleanEmail = email?.trim().orEmpty()
            when {
                user != null -> matched.putIfAbsent(user.id.toString(), user.label)
                cleanEmail.isNotEmpty() -> unmatched.putIfAbsent(cleanEmail.lowercase(), cleanEmail)
                !name.isNullOrBlank() -> unmatched.putIfAbsent(name.trim().lowercase(), name.trim())
            }
        }
        valid.forEach { row ->
            row.assigneeEmails.forEach { person(it, null) }
            row.assigneeNames.forEach { person(null, it) }
            if (!row.reviewerEmail.isNullOrBlank()) person(row.reviewerEmail, null) else person(null, row.reviewerName)
        }
        return ImportSummary(
            tasks = valid.size,
            groups = groups,
            checklistItems = valid.sumOf { it.checklist.size },
            comments = valid.sumOf { it.comments.size },
            files = files,
            matchedPeople = matched.values.toList(),
            unmatchedPeople = unmatched.values.toList(),
            problems = problems,
        )
    }

    // ---- database ----

    private fun PreparedStatement.bind(params: List<Any?>) {
        params.forEachIndexed { index, value -> setObject(index + 1, value) }
    }

    private fun <T> Connection.rows(sql: String, params: List<Any?>, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            statement.bind(params)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
        }

    private fun Connection.exec(sql: String, params: List<Any?>): Int =
        prepareStatement(sql).use { statement ->
            statement.bind(params)
            statement.executeUpdate()
        }

    /**
     * The board to import into: [targetBoardId] when given (it must exist and not be archived), otherwise the one already
     * created for [importKey], otherwise a new company-wide board named [name].
     */
    fun ensureBoard(tenant: Connection, importKey: String, name: String, targetBoardId: UUID?, actorId: UUID?): UUID {
        if (targetBoardId != null) {
            val board = TareasRepository.getBoard(tenant, targetBoardId) ?: throw TareasValidationException("El tablero de destino ya no existe.")
            if (board.archived) throw TareasValidationException("El tablero de destino está archivado.")
            return board.id
        }
        val key = importKey.trim()
        if (key.isEmpty() || key.length > 200) throw TareasValidationException("La clave de importación del tablero no es válida.")
        fun existing() = tenant.rows("SELECT id FROM tareas_boards WHERE import_key = ?", listOf(key)) { it.getObject("id", UUID::class.java) }.firstOrNull()
        return TareasRepository.transaction(tenant) {
            existing() ?: run {
                tenant.exec(
                    """
                    INSERT INTO tareas_boards (id, name, description, visibility, position, created_by, import_key)
                    VALUES (?, ?, '', 'company', (SELECT COALESCE(MAX(position), 0) + 1 FROM tareas_boards), ?, ?)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                    listOf(UUID.randomUUID(), clip(name.trim().ifEmpty { "Importado" }, MaxBoardName), actorId, key),
                )
                existing() ?: throw TareasValidationException("No se pudo crear el tablero.")
            }
        }
    }

    /**
     * Creates or updates the task of [row] in [boardId] (one transaction, same result when repeated) and adds its checklist and
     * comments that are not there yet. Sends no notices. A row without title or identifier is skipped.
     */
    fun apply(tenant: Connection, source: String, boardId: UUID, row: ImportedTask, users: List<TareasUser>, actorId: UUID?, today: LocalDate): ImportOutcome {
        if (!ExternalTaskRequest.SourcePattern.matches(source) || source in ExternalTaskRequest.ReservedSources) {
            throw TareasValidationException("El origen de la importación no es válido.")
        }
        val key = row.key.trim()
        if (key.isEmpty()) return ImportOutcome.Skipped("El ítem no tiene identificador.")
        if (key.length > ExternalTaskRequest.MaxExternalRef) return ImportOutcome.Skipped("El identificador del ítem $key es demasiado largo.")
        if (row.title.isBlank()) return ImportOutcome.Skipped("El ítem $key no tiene título.")
        return TareasRepository.transaction(tenant) { applyRow(tenant, source, boardId, key, row, users, actorId, today) }
    }

    private fun applyRow(tenant: Connection, source: String, boardId: UUID, key: String, row: ImportedTask, users: List<TareasUser>, actorId: UUID?, today: LocalDate): ImportOutcome {
        val assigneeIds = linkedSetOf<UUID>()
        val unmatchedAssignees = linkedMapOf<String, String>()
        row.assigneeEmails.map { it.trim() }.filter { it.isNotEmpty() }.forEach { email ->
            val user = matchUser(email, users)
            if (user != null) assigneeIds += user.id else unmatchedAssignees.putIfAbsent(email.lowercase(), email)
        }
        row.assigneeNames.map { it.trim() }.filter { it.isNotEmpty() }.forEach { unmatchedAssignees.putIfAbsent(it.lowercase(), it) }
        val reviewerUser = matchUser(row.reviewerEmail, users)
        val unmatchedReviewers = when {
            reviewerUser != null -> emptyList()
            !row.reviewerEmail.isNullOrBlank() -> listOf(row.reviewerEmail.trim())
            !row.reviewerName.isNullOrBlank() -> listOf(row.reviewerName.trim())
            else -> emptyList()
        }
        val title = clip(row.title.trim(), TaskInput.MaxTitle)
        val detail = detailWithNotes(row.detail, unmatchedAssignees.values.toList(), unmatchedReviewers)
        val groupId = row.groupName?.trim()?.takeIf { it.isNotEmpty() }?.let { TareasRepository.findOrCreateGroup(tenant, boardId, clip(it, MaxGroupName)) }
        val done = row.status == TaskStatus.Done

        val existing = TareasRepository.findByExternalRef(tenant, boardId, source, key)
        var resolved: UUID? = existing?.id
        var created = false
        if (resolved == null) {
            val id = UUID.randomUUID()
            val inserted = tenant.exec(
                """
                INSERT INTO tareas_tasks (id, board_id, group_id, title, detail, status, priority, due_date, reviewer_id, source, external_ref, position, completed_at, completed_by, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(position), 0) + 1 FROM tareas_tasks WHERE board_id = ?), CASE WHEN ?::boolean THEN now() ELSE NULL END, CASE WHEN ?::boolean THEN ?::uuid ELSE NULL END, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                listOf(id, boardId, groupId, title, detail, row.status.code, row.priority.code, row.dueDate, reviewerUser?.id, source, key, boardId, done, done, actorId, actorId),
            )
            if (inserted > 0) {
                resolved = id
                created = true
            } else {
                // Another run inserted it at the same moment: update that one.
                resolved = TareasRepository.findByExternalRef(tenant, boardId, source, key)?.id
            }
        }
        val taskId: UUID = resolved ?: throw TareasValidationException("No se pudo guardar el ítem $key.")
        if (!created) {
            tenant.exec(
                """
                UPDATE tareas_tasks
                SET title = ?, detail = ?, status = ?, priority = ?, due_date = ?, group_id = COALESCE(?::uuid, group_id), reviewer_id = ?,
                    completed_at = CASE WHEN ?::boolean THEN COALESCE(completed_at, now()) ELSE NULL END,
                    completed_by = CASE WHEN ?::boolean THEN COALESCE(completed_by, ?::uuid) ELSE NULL END,
                    updated_at = now()
                WHERE id = ?
                """.trimIndent(),
                listOf(title, detail, row.status.code, row.priority.code, row.dueDate, groupId, reviewerUser?.id, done, done, actorId, taskId),
            )
        }
        TareasRepository.setAssignees(tenant, taskId, assigneeIds.take(TaskInput.MaxAssignees))
        if (suppressesReminders(row.status, row.dueDate, today) && row.dueDate != null) {
            assigneeIds.take(TaskInput.MaxAssignees).forEach { user ->
                listOf("overdue", "due_soon").forEach { kind ->
                    tenant.exec("INSERT INTO tareas_reminders (task_id, user_id, kind, due_date) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", listOf(taskId, user, kind, row.dueDate))
                }
            }
        }
        if (created) TareasRepository.addActivity(tenant, taskId, "imported", "Importada desde $source", ImportAuthor)
        val omitted = addChecklist(tenant, taskId, row.checklist) + addComments(tenant, taskId, row.comments)
        if (omitted > 0) log.warn("El ítem {} de {} superó los topes de lista o comentarios: se omitieron {} elementos.", key, source, omitted)
        return if (created) ImportOutcome.Created(taskId) else ImportOutcome.Updated(taskId)
    }

    // Serializes the writers that count before inserting (limits per task).
    private fun lockTask(tenant: Connection, taskId: UUID) {
        tenant.rows("SELECT 1 AS found FROM tareas_tasks WHERE id = ? FOR UPDATE", listOf(taskId)) { it.getInt("found") }
    }

    /** Adds the items not there yet (by key) up to the limit; returns how many were left out for the limit. */
    private fun addChecklist(tenant: Connection, taskId: UUID, items: List<ImportedCheck>): Int {
        if (items.isEmpty()) return 0
        lockTask(tenant, taskId)
        var count = tenant.rows("SELECT count(*) AS total FROM tareas_checklist WHERE task_id = ?", listOf(taskId)) { it.getInt("total") }.firstOrNull() ?: 0
        var omitted = 0
        items.forEach { item ->
            val key = item.key.trim().take(200)
            val text = clip(item.text.trim(), MaxChecklistText)
            if (key.isEmpty() || text.isEmpty()) return@forEach
            if (count >= TareasRepository.MaxChecklistItems) {
                omitted++
                return@forEach
            }
            val inserted = tenant.exec(
                """
                INSERT INTO tareas_checklist (id, task_id, text, done, position, done_at, import_key)
                VALUES (?, ?, ?, ?::boolean, (SELECT COALESCE(MAX(position), 0) + 1 FROM tareas_checklist WHERE task_id = ?), CASE WHEN ?::boolean THEN now() ELSE NULL END, ?)
                ON CONFLICT (task_id, import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(UUID.randomUUID(), taskId, text, item.done, taskId, item.done, key),
            )
            count += inserted
        }
        return omitted
    }

    private fun addComments(tenant: Connection, taskId: UUID, comments: List<ImportedComment>): Int {
        if (comments.isEmpty()) return 0
        lockTask(tenant, taskId)
        var count = tenant.rows("SELECT count(*) AS total FROM tareas_comments WHERE task_id = ?", listOf(taskId)) { it.getInt("total") }.firstOrNull() ?: 0
        var omitted = 0
        comments.forEach { comment ->
            val key = comment.key.trim().take(200)
            if (key.isEmpty() || comment.body.isBlank()) return@forEach
            if (count >= TareasRepository.MaxCommentsPerTask) {
                omitted++
                return@forEach
            }
            val author = clip(comment.author.trim().ifEmpty { "Monday" }, MaxAuthor)
            val inserted = tenant.exec(
                """
                INSERT INTO tareas_comments (id, task_id, author_id, author_name, body, created_at, import_key)
                VALUES (?, ?, NULL, ?, ?, COALESCE(?::timestamptz, now()), ?)
                ON CONFLICT (task_id, import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(UUID.randomUUID(), taskId, author, commentText(author, comment.at, comment.body), comment.at?.atOffset(ZoneOffset.UTC), key),
            )
            count += inserted
        }
        return omitted
    }

    /**
     * Stores a file of the source as an attachment of the task, once per [importKey] (false when it was already there; the
     * content is then not read). The task admits [TareasRepository.MaxFilesPerTask] files and each one [TareasRepository.MaxFileBytes].
     */
    fun attachFile(tenant: Connection, files: ModuleFiles, taskId: UUID, importKey: String, name: String, contentType: String, content: InputStream, actorId: UUID?): Boolean {
        val key = importKey.trim().take(200)
        if (key.isEmpty()) throw TareasValidationException("La clave del archivo no es válida.")
        val already = tenant.rows("SELECT 1 AS found FROM tareas_attachments WHERE task_id = ? AND import_key = ?", listOf(taskId, key)) { it.getInt("found") }.isNotEmpty()
        if (already) return false
        val total = tenant.rows("SELECT count(*) AS total FROM tareas_attachments WHERE task_id = ? AND NOT delete_pending", listOf(taskId)) { it.getInt("total") }.firstOrNull() ?: 0
        if (total >= TareasRepository.MaxFilesPerTask) throw TareasValidationException("La tarea ya tiene el máximo de ${TareasRepository.MaxFilesPerTask} archivos.")
        val fileName = TareasRepository.cleanFileName(name).ifEmpty { "archivo" }
        val type = contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" }
        val stored = try {
            files.put(fileName, type, content, TareasRepository.MaxFileBytes)
        } catch (_: FileTooLargeException) {
            throw TareasValidationException("$fileName supera el máximo de ${TareasRepository.MaxFileBytes / (1024 * 1024)} MB.")
        }
        try {
            val inserted = tenant.exec(
                """
                INSERT INTO tareas_attachments (id, task_id, name, path, content_type, size, backend, ref, uploaded_by, import_key)
                VALUES (?, ?, ?, '', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_id, import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(UUID.randomUUID(), taskId, fileName, type, stored.size, stored.backend, stored.ref, actorId, key),
            )
            if (inserted == 0) {
                runCatching { files.delete(stored) }
                return false
            }
            return true
        } catch (failure: Exception) {
            runCatching { files.delete(stored) }
            throw failure
        }
    }
}
