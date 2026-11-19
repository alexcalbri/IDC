package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.server.modules.FileTooLargeException
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.server.modules.StoredFileRef
import com.ideasdeveloper.idc.ui.UiFileRef
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Data access of the module. Every function is synchronous and takes the company's connection: callers run them
 * inside withContext(Dispatchers.IO). All SQL is parameterized; lists use `= ANY(?)` with a uuid array.
 */
object TareasRepository {
    const val MaxFileBytes = 25L * 1024 * 1024
    const val MaxFilesPerTask = 100
    const val MaxChecklistItems = 100
    const val MaxCommentLength = 5000
    const val MaxBoardMembers = 500
    const val MaxCommentsPerTask = 2000
    const val MaxPendingFiles = 20
    const val MaxPendingBytes = 200L * 1024 * 1024
    private const val MaxListed = 500
    private const val MaxGroupsPerBoard = 100
    private val log = LoggerFactory.getLogger(TareasRepository::class.java)
    private val NilId = UUID(0L, 0L)
    private const val OpenStatuses = "('done', 'cancelled')"

    /** Today in the server's time zone: the same day the due reminders and the filters use. */
    fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    fun <T> transaction(tenant: Connection, block: () -> T): T {
        val previous = tenant.autoCommit
        // Already inside a transaction of the caller: it decides when to commit.
        if (!previous) return block()
        tenant.autoCommit = false
        try {
            val result = block()
            tenant.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { tenant.rollback() }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            tenant.autoCommit = previous
        }
    }

    // ---- helpers ----

    private fun PreparedStatement.bind(params: List<Any?>) {
        params.forEachIndexed { index, value ->
            when (value) {
                null -> setObject(index + 1, null)
                is java.sql.Array -> setArray(index + 1, value)
                else -> setObject(index + 1, value)
            }
        }
    }

    private fun <T> Connection.query(sql: String, params: List<Any?>, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            statement.bind(params)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
        }

    private fun Connection.update(sql: String, params: List<Any?>): Int =
        prepareStatement(sql).use { statement ->
            statement.bind(params)
            statement.executeUpdate()
        }

    private fun Connection.uuidArray(ids: Collection<UUID>): java.sql.Array = createArrayOf("uuid", ids.toTypedArray())

    private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun ResultSet.date(column: String): LocalDate? = getObject(column, LocalDate::class.java)

    /** Escapes LIKE wildcards so what the user types is matched literally. */
    private fun like(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // ---- users ----

    /** Active users that can use the module: the business owner or holders of any tareas.* permission. */
    fun users(tenant: Connection): List<TareasUser> {
        class Row(val id: UUID, val role: String, val name: String, val email: String)
        val rows = tenant.query(
            """
            SELECT users.id, users.postgres_role, COALESCE(NULLIF(btrim(users.display_name), ''), users.postgres_role) AS label, COALESCE(users.email, '') AS email
            FROM application_users users
            WHERE users.is_active
              AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
                   OR EXISTS (SELECT 1 FROM application_user_permissions granted
                              WHERE granted.user_id = users.id AND granted.permission_id LIKE 'tareas.%')
            )
            ORDER BY label, users.postgres_role
            LIMIT $MaxListed
            """.trimIndent(),
            emptyList(),
        ) { Row(it.uuid("id") ?: NilId, it.getString("postgres_role"), it.getString("label"), it.getString("email")) }
        val repeated = rows.groupingBy { it.name.lowercase() }.eachCount().filterValues { it > 1 }.keys
        // Two people with the same name are told apart by their username.
        return rows.map { TareasUser(it.id, if (it.name.lowercase() in repeated) "${it.name} (${it.role})" else it.name, it.email) }
    }

    /** Private boards the user belongs to. */
    fun memberships(tenant: Connection, userId: UUID?): Set<UUID> {
        if (userId == null) return emptySet()
        return tenant.query("SELECT board_id FROM tareas_board_members WHERE user_id = ?", listOf(userId)) { it.uuid("board_id") ?: NilId }.toSet()
    }

    // ---- boards ----

    private const val BoardSelect = """
        b.id, b.name, b.description, b.visibility, b.position, b.archived, b.created_by,
        COALESCE(c.open_count, 0) AS open_count, COALESCE(c.overdue_count, 0) AS overdue_count
        FROM tareas_boards b
        LEFT JOIN LATERAL (
            SELECT count(*) AS open_count, count(*) FILTER (WHERE t.due_date < ?) AS overdue_count
            FROM tareas_tasks t
            WHERE t.board_id = b.id AND t.archived_at IS NULL AND t.status NOT IN ('done', 'cancelled')
        ) c ON TRUE
    """

    /** Boards the scope sees, with their open and overdue counts. Archived ones only for admins and only when asked. */
    fun listBoards(tenant: Connection, scope: TareasScope, includeArchived: Boolean = false): List<Board> {
        val params = mutableListOf<Any?>()
        val visible = if (scope.admin) {
            if (includeArchived) "TRUE" else "b.archived = FALSE"
        } else {
            params += tenant.uuidArray(scope.memberOf)
            "(b.archived = FALSE AND (b.visibility = 'company' OR b.id = ANY(?)))"
        }
        return readBoards(tenant, visible, params)
    }

    fun getBoard(tenant: Connection, id: UUID): Board? = readBoards(tenant, "b.id = ?", listOf(id)).firstOrNull()

    private fun readBoards(tenant: Connection, where: String, params: List<Any?>): List<Board> {
        class Row(val id: UUID, val name: String, val description: String, val visibility: BoardVisibility, val position: Int, val archived: Boolean, val createdBy: UUID?, val openCount: Int, val overdueCount: Int)
        val rows = tenant.query(
            "SELECT $BoardSelect WHERE $where ORDER BY b.position, lower(b.name), b.id LIMIT $MaxListed",
            listOf<Any?>(today()) + params,
        ) {
            Row(
                it.uuid("id") ?: NilId, it.getString("name"), it.getString("description"),
                BoardVisibility.fromCode(it.getString("visibility")) ?: BoardVisibility.Company,
                it.getInt("position"), it.getBoolean("archived"), it.uuid("created_by"), it.getInt("open_count"), it.getInt("overdue_count"),
            )
        }
        if (rows.isEmpty()) return emptyList()
        val members = tenant.query(
            "SELECT board_id, user_id FROM tareas_board_members WHERE board_id = ANY(?) ORDER BY user_id",
            listOf(tenant.uuidArray(rows.map { it.id })),
        ) { (it.uuid("board_id") ?: NilId) to (it.uuid("user_id") ?: NilId) }.groupBy({ it.first }, { it.second })
        return rows.map { Board(it.id, it.name, it.description, it.visibility, it.position, it.archived, it.createdBy, members[it.id].orEmpty(), it.openCount, it.overdueCount) }
    }

    fun createBoard(tenant: Connection, name: String, description: String, visibility: BoardVisibility, members: Collection<UUID>, actorId: UUID?): UUID {
        validateBoard(name, description, members)
        val id = UUID.randomUUID()
        transaction(tenant) {
            tenant.update(
                """
                INSERT INTO tareas_boards (id, name, description, visibility, position, created_by)
                VALUES (?, ?, ?, ?, (SELECT COALESCE(MAX(position), 0) + 1 FROM tareas_boards), ?)
                """.trimIndent(),
                listOf(id, name.trim(), description.trim(), visibility.code, actorId),
            )
            replaceMembers(tenant, id, members)
        }
        return id
    }

    /** Edits the board and replaces its members. Returns false when the board does not exist. */
    fun updateBoard(tenant: Connection, id: UUID, name: String, description: String, visibility: BoardVisibility, members: Collection<UUID>): Boolean {
        validateBoard(name, description, members)
        return transaction(tenant) {
            val updated = tenant.update(
                "UPDATE tareas_boards SET name = ?, description = ?, visibility = ?, updated_at = now() WHERE id = ?",
                listOf(name.trim(), description.trim(), visibility.code, id),
            )
            if (updated > 0) replaceMembers(tenant, id, members)
            updated > 0
        }
    }

    fun archiveBoard(tenant: Connection, id: UUID, archived: Boolean = true): Boolean =
        tenant.update("UPDATE tareas_boards SET archived = ?, updated_at = now() WHERE id = ?", listOf(archived, id)) > 0

    private fun validateBoard(name: String, description: String, members: Collection<UUID>) {
        if (name.isBlank()) throw TareasValidationException("El nombre del tablero es obligatorio.")
        if (name.trim().length > 120) throw TareasValidationException("El nombre del tablero admite hasta 120 caracteres.")
        if (description.length > 2000) throw TareasValidationException("La descripción del tablero admite hasta 2000 caracteres.")
        if (members.distinct().size > MaxBoardMembers) throw TareasValidationException("Un tablero admite hasta $MaxBoardMembers miembros.")
    }

    private fun replaceMembers(tenant: Connection, boardId: UUID, members: Collection<UUID>) {
        val wanted = members.distinct()
        tenant.update("DELETE FROM tareas_board_members WHERE board_id = ? AND NOT (user_id = ANY(?))", listOf(boardId, tenant.uuidArray(wanted)))
        tenant.prepareStatement("INSERT INTO tareas_board_members (board_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING").use { insert ->
            wanted.forEach { user ->
                insert.setObject(1, boardId)
                insert.setObject(2, user)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    // ---- groups ----

    fun listGroups(tenant: Connection, boardId: UUID, includeArchived: Boolean = false): List<BoardGroup> = tenant.query(
        "SELECT id, board_id, name, position, archived FROM tareas_groups WHERE board_id = ?${if (includeArchived) "" else " AND archived = FALSE"} ORDER BY position, lower(name), id",
        listOf(boardId),
    ) { BoardGroup(it.uuid("id") ?: NilId, it.uuid("board_id") ?: NilId, it.getString("name"), it.getInt("position"), it.getBoolean("archived")) }

    fun getGroup(tenant: Connection, id: UUID): BoardGroup? = tenant.query(
        "SELECT id, board_id, name, position, archived FROM tareas_groups WHERE id = ?", listOf(id),
    ) { BoardGroup(it.uuid("id") ?: NilId, it.uuid("board_id") ?: NilId, it.getString("name"), it.getInt("position"), it.getBoolean("archived")) }.firstOrNull()

    private fun lockBoard(tenant: Connection, boardId: UUID) {
        tenant.query("SELECT 1 AS found FROM tareas_boards WHERE id = ? FOR UPDATE", listOf(boardId)) { it.getInt("found") }.firstOrNull()
            ?: throw TareasValidationException("El tablero ya no existe.")
    }

    private const val DuplicateGroup = "Ya existe un grupo con ese nombre."

    // Null when an active group of the board already has that name.
    private fun insertGroup(tenant: Connection, boardId: UUID, clean: String): UUID? {
        val count = tenant.query("SELECT count(*) AS total FROM tareas_groups WHERE board_id = ? AND archived = FALSE", listOf(boardId)) { it.getInt("total") }.firstOrNull() ?: 0
        if (count >= MaxGroupsPerBoard) throw TareasValidationException("Un tablero admite hasta $MaxGroupsPerBoard grupos.")
        val id = UUID.randomUUID()
        val inserted = tenant.update(
            "INSERT INTO tareas_groups (id, board_id, name, position) VALUES (?, ?, ?, (SELECT COALESCE(MAX(position), 0) + 1 FROM tareas_groups WHERE board_id = ?)) ON CONFLICT DO NOTHING",
            listOf(id, boardId, clean, boardId),
        )
        return if (inserted > 0) id else null
    }

    fun addGroup(tenant: Connection, boardId: UUID, name: String): UUID {
        val clean = cleanGroupName(name)
        return transaction(tenant) {
            lockBoard(tenant, boardId)
            insertGroup(tenant, boardId, clean) ?: throw TareasValidationException(DuplicateGroup)
        }
    }

    fun renameGroup(tenant: Connection, id: UUID, name: String): Boolean {
        val clean = cleanGroupName(name)
        return transaction(tenant) {
            val group = getGroup(tenant, id) ?: return@transaction false
            lockBoard(tenant, group.boardId)
            val taken = tenant.query(
                "SELECT 1 AS found FROM tareas_groups WHERE board_id = ? AND archived = FALSE AND lower(name) = lower(?) AND id <> ?",
                listOf(group.boardId, clean, id),
            ) { it.getInt("found") }.isNotEmpty()
            if (taken) throw TareasValidationException(DuplicateGroup)
            tenant.update("UPDATE tareas_groups SET name = ? WHERE id = ?", listOf(clean, id)) > 0
        }
    }

    /** Moves a group one place up or down among the active groups of its board. */
    fun moveGroup(tenant: Connection, id: UUID, up: Boolean): Boolean = transaction(tenant) {
        val group = getGroup(tenant, id) ?: return@transaction false
        lockBoard(tenant, group.boardId)
        val ordered = listGroups(tenant, group.boardId).map { it.id }.toMutableList()
        val index = ordered.indexOf(id)
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in ordered.indices) return@transaction false
        ordered[index] = ordered[target].also { ordered[target] = ordered[index] }
        tenant.prepareStatement("UPDATE tareas_groups SET position = ? WHERE id = ?").use { update ->
            ordered.forEachIndexed { position, groupId ->
                update.setInt(1, position + 1)
                update.setObject(2, groupId)
                update.addBatch()
            }
            update.executeBatch()
        }
        true
    }

    fun archiveGroup(tenant: Connection, id: UUID): Boolean = tenant.update("UPDATE tareas_groups SET archived = TRUE WHERE id = ?", listOf(id)) > 0

    /** The active group of the board with that name (any case) or a new one: for imports and external modules. */
    fun findOrCreateGroup(tenant: Connection, boardId: UUID, name: String): UUID {
        val clean = cleanGroupName(name)
        fun existing() = tenant.query(
            "SELECT id FROM tareas_groups WHERE board_id = ? AND archived = FALSE AND lower(name) = lower(?) ORDER BY position LIMIT 1",
            listOf(boardId, clean),
        ) { it.uuid("id") }.firstOrNull()
        return transaction(tenant) {
            lockBoard(tenant, boardId)
            existing() ?: insertGroup(tenant, boardId, clean) ?: existing() ?: throw TareasValidationException(DuplicateGroup)
        }
    }

    private fun cleanGroupName(name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) throw TareasValidationException("El nombre del grupo es obligatorio.")
        if (clean.length > 80) throw TareasValidationException("El nombre del grupo admite hasta 80 caracteres.")
        return clean
    }

    // ---- tasks ----

    private const val TaskColumns = """
        t.id, t.board_id, t.group_id, t.title, t.detail, t.status, t.priority, t.due_date, t.reviewer_id, t.customer_id,
        t.source, t.external_ref, t.position, t.completed_at, t.created_by, t.created_at, t.updated_at,
        g.name AS group_name, b.name AS board_name, b.visibility AS board_visibility, b.archived AS board_archived,
        COALESCE(ck.done_count, 0) AS checklist_done, COALESCE(ck.total_count, 0) AS checklist_total, t.archived_at
    """

    private const val TaskFrom = """
        FROM tareas_tasks t
        JOIN tareas_boards b ON b.id = t.board_id
        LEFT JOIN tareas_groups g ON g.id = t.group_id
        LEFT JOIN LATERAL (
            SELECT count(*) FILTER (WHERE c.done) AS done_count, count(*) AS total_count FROM tareas_checklist c WHERE c.task_id = t.id
        ) ck ON TRUE
    """

    private const val PriorityOrder = "CASE t.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END"

    /**
     * The visibility rule of [TareasScope.seesTask] in SQL: an admin sees everything; otherwise a task is visible when its
     * board is active and company-wide or the user is a member, or the user is a responsible or the reviewer of the task.
     */
    private fun visibleTasks(tenant: Connection, scope: TareasScope, params: MutableList<Any?>): String {
        params += scope.admin
        params += tenant.uuidArray(scope.memberOf)
        params += scope.userId ?: NilId
        params += scope.userId ?: NilId
        return "(?::boolean OR (b.archived = FALSE AND (b.visibility = 'company' OR b.id = ANY(?))) " +
            "OR EXISTS (SELECT 1 FROM tareas_task_assignees va WHERE va.task_id = t.id AND va.user_id = ?) OR t.reviewer_id = ?)"
    }

    private fun readTasks(tenant: Connection, where: String, params: List<Any?>, order: String, limit: Int): List<Task> {
        val rows = tenant.query(
            "SELECT $TaskColumns $TaskFrom WHERE $where ORDER BY $order LIMIT ?",
            params + limit.coerceIn(1, 1000),
        ) { rows ->
            Task(
                id = rows.uuid("id") ?: NilId,
                boardId = rows.uuid("board_id") ?: NilId,
                groupId = rows.uuid("group_id"),
                title = rows.getString("title"),
                detail = rows.getString("detail"),
                status = TaskStatus.fromCode(rows.getString("status")) ?: TaskStatus.Pending,
                priority = TaskPriority.fromCode(rows.getString("priority")) ?: TaskPriority.Medium,
                dueDate = rows.date("due_date"),
                reviewerId = rows.uuid("reviewer_id"),
                customerId = rows.uuid("customer_id"),
                source = rows.getString("source"),
                externalRef = rows.getString("external_ref"),
                position = rows.getInt("position"),
                completedAt = rows.instant("completed_at"),
                createdBy = rows.uuid("created_by"),
                createdAt = rows.instant("created_at") ?: Instant.EPOCH,
                updatedAt = rows.instant("updated_at") ?: Instant.EPOCH,
                assignees = emptyList(),
                groupName = rows.getString("group_name"),
                boardName = rows.getString("board_name"),
                boardVisibility = BoardVisibility.fromCode(rows.getString("board_visibility")) ?: BoardVisibility.Company,
                boardArchived = rows.getBoolean("board_archived"),
                checklistDone = rows.getInt("checklist_done"),
                checklistTotal = rows.getInt("checklist_total"),
                archivedAt = rows.instant("archived_at"),
            )
        }
        if (rows.isEmpty()) return rows
        // One query for the responsibles of every listed task.
        val assignees = tenant.query(
            "SELECT task_id, user_id FROM tareas_task_assignees WHERE task_id = ANY(?) ORDER BY user_id",
            listOf(tenant.uuidArray(rows.map { it.id })),
        ) { (it.uuid("task_id") ?: NilId) to (it.uuid("user_id") ?: NilId) }.groupBy({ it.first }, { it.second })
        return rows.map { it.copy(assignees = assignees[it.id].orEmpty()) }
    }

    /** Tasks the scope sees, filtered, ordered by due date, priority and position. Archived tasks never appear. */
    fun list(tenant: Connection, scope: TareasScope, filter: TaskFilter): List<Task> {
        val params = mutableListOf<Any?>()
        val where = StringBuilder("t.archived_at IS NULL AND ").append(visibleTasks(tenant, scope, params))
        val today = today()
        if (filter.boardId != null) {
            where.append(" AND t.board_id = ?")
            params += filter.boardId
        } else {
            where.append(" AND b.archived = FALSE")
        }
        if (filter.assignee != null) {
            where.append(" AND EXISTS (SELECT 1 FROM tareas_task_assignees fa WHERE fa.task_id = t.id AND fa.user_id = ?)")
            params += filter.assignee
        }
        if (filter.status != null) {
            where.append(" AND t.status = ?")
            params += filter.status.code
        } else if (!filter.includeClosed) {
            where.append(" AND t.status NOT IN $OpenStatuses")
        }
        when (filter.due) {
            DueFilter.Overdue -> { where.append(" AND t.status NOT IN $OpenStatuses AND t.due_date < ?"); params += today }
            DueFilter.Today -> { where.append(" AND t.due_date = ?"); params += today }
            DueFilter.Week -> { where.append(" AND t.due_date BETWEEN ? AND ?"); params += today; params += today.plusDays(7) }
            DueFilter.None -> where.append(" AND t.due_date IS NULL")
            null -> Unit
        }
        val text = filter.query?.trim().orEmpty().take(200)
        if (text.isNotEmpty()) {
            val pattern = "%${like(text.lowercase())}%"
            // The detail is only searched with three letters or more: shorter text matches almost everything.
            if (text.length >= 3) {
                where.append(" AND (lower(t.title) LIKE ? OR lower(t.detail) LIKE ?)")
                params += pattern
                params += pattern
            } else {
                where.append(" AND lower(t.title) LIKE ?")
                params += pattern
            }
        }
        return readTasks(tenant, where.toString(), params, "t.due_date NULLS LAST, $PriorityOrder, t.position, t.created_at, t.id", filter.limit)
    }

    fun get(tenant: Connection, id: UUID): Task? = readTasks(tenant, "t.id = ?", listOf(id), "t.id", 1).firstOrNull()

    /** Tasks in review that [reviewerId] has to approve or return (active boards only): they show in "Mis tareas". */
    fun reviewing(tenant: Connection, reviewerId: UUID, limit: Int = 200): List<Task> = readTasks(
        tenant,
        "t.archived_at IS NULL AND t.status = 'review' AND t.reviewer_id = ? AND b.archived = FALSE",
        listOf(reviewerId),
        "t.due_date NULLS LAST, $PriorityOrder, t.created_at, t.id",
        limit,
    )

    fun findByExternalRef(tenant: Connection, boardId: UUID, source: String, externalRef: String): Task? =
        readTasks(tenant, "t.board_id = ? AND t.source = ? AND t.external_ref = ?", listOf(boardId, source, externalRef), "t.id", 1).firstOrNull()

    /** Creates a task (pending) with its responsibles and returns its id; see [createIfAbsent] for tasks with an external reference. */
    fun create(tenant: Connection, input: TaskInput, actorId: UUID?, source: String = "manual", externalRef: String? = null): UUID =
        createIfAbsent(tenant, input, actorId, source, externalRef).id

    /**
     * Like [create], but a task with the same board + source + external_ref (also one created at the same moment by another
     * request) is returned with created = false and left untouched.
     */
    fun createIfAbsent(tenant: Connection, input: TaskInput, actorId: UUID?, source: String = "manual", externalRef: String? = null): CreateResult {
        checkInput(tenant, input, null)
        val id = UUID.randomUUID()
        return transaction(tenant) {
            val inserted = tenant.update(
                """
                INSERT INTO tareas_tasks (id, board_id, group_id, title, detail, priority, due_date, reviewer_id, customer_id, source, external_ref, position, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(position), 0) + 1 FROM tareas_tasks WHERE board_id = ?), ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                listOf(id, input.boardId, input.groupId, input.title.trim(), input.detail, input.priority.code, input.dueDate, input.reviewerId, input.customerId, source, externalRef, input.boardId, actorId),
            )
            if (inserted == 0) {
                val existing = externalRef?.let { findByExternalRef(tenant, input.boardId, source, it) }
                    ?: throw TareasValidationException("No se pudo crear la tarea.")
                return@transaction CreateResult(existing.id, false)
            }
            insertAssignees(tenant, id, input.assignees.distinct())
            CreateResult(id, true)
        }
    }

    /** Edits the task's fields (not its state nor its responsibles). Returns false when it does not exist or is archived. */
    fun update(tenant: Connection, id: UUID, input: TaskInput): Boolean {
        val existing = get(tenant, id) ?: return false
        checkInput(tenant, input, existing)
        return tenant.update(
            """
            UPDATE tareas_tasks
            SET board_id = ?, group_id = ?, title = ?, detail = ?, priority = ?, due_date = ?, reviewer_id = ?, customer_id = ?, updated_at = now()
            WHERE id = ? AND archived_at IS NULL
            """.trimIndent(),
            listOf(input.boardId, input.groupId, input.title.trim(), input.detail, input.priority.code, input.dueDate, input.reviewerId, input.customerId, id),
        ) > 0
    }

    // [existing]: the task being edited; what it already has (group, customer) is not checked again.
    private fun checkInput(tenant: Connection, input: TaskInput, existing: Task?) {
        input.validate().firstOrNull()?.let { throw TareasValidationException(it) }
        val board = tenant.query("SELECT archived FROM tareas_boards WHERE id = ?", listOf(input.boardId)) { it.getBoolean("archived") }.firstOrNull()
            ?: throw TareasValidationException("El tablero ya no existe.")
        if (board) throw TareasValidationException("El tablero está archivado.")
        if (input.groupId != null) {
            val archived = tenant.query("SELECT archived FROM tareas_groups WHERE id = ? AND board_id = ?", listOf(input.groupId, input.boardId)) { it.getBoolean("archived") }.firstOrNull()
                ?: throw TareasValidationException("El grupo no pertenece al tablero.")
            if (archived && input.groupId != existing?.groupId) throw TareasValidationException("El grupo está archivado.")
        }
        if (input.customerId != null && input.customerId != existing?.customerId && !customerExists(tenant, input.customerId)) {
            throw TareasValidationException("El cliente ya no existe o está archivado.")
        }
    }

    /**
     * Changes the state and returns the previous one (null when the task does not exist or is archived). Done sets who and when
     * (kept when it was already done); leaving it clears them.
     */
    fun setStatus(tenant: Connection, id: UUID, status: TaskStatus, actorId: UUID?): TaskStatus? = transaction(tenant) {
        tenant.prepareStatement(
            """
            UPDATE tareas_tasks t
            SET status = ?,
                completed_at = CASE WHEN ?::boolean THEN (CASE WHEN old.status = 'done' THEN t.completed_at ELSE now() END) ELSE NULL END,
                completed_by = CASE WHEN ?::boolean THEN (CASE WHEN old.status = 'done' THEN t.completed_by ELSE ?::uuid END) ELSE NULL END,
                updated_at = now()
            FROM (SELECT status FROM tareas_tasks WHERE id = ? FOR UPDATE) old
            WHERE t.id = ? AND t.archived_at IS NULL
            RETURNING old.status
            """.trimIndent(),
        ).use { statement ->
            val done = status == TaskStatus.Done
            statement.bind(listOf(status.code, done, done, actorId, id, id))
            statement.executeQuery().use { rows -> if (rows.next()) TaskStatus.fromCode(rows.getString(1)) else null }
        }
    }

    /** Replaces the responsibles and returns the ones that were added. */
    fun setAssignees(tenant: Connection, taskId: UUID, assignees: Collection<UUID>): List<UUID> {
        val wanted = assignees.distinct()
        if (wanted.size > TaskInput.MaxAssignees) throw TareasValidationException("Una tarea admite hasta ${TaskInput.MaxAssignees} responsables.")
        return transaction(tenant) {
            val current = tenant.query("SELECT user_id FROM tareas_task_assignees WHERE task_id = ?", listOf(taskId)) { it.uuid("user_id") ?: NilId }.toSet()
            tenant.update("DELETE FROM tareas_task_assignees WHERE task_id = ? AND NOT (user_id = ANY(?))", listOf(taskId, tenant.uuidArray(wanted)))
            val added = wanted.filter { it !in current }
            insertAssignees(tenant, taskId, added)
            tenant.update("UPDATE tareas_tasks SET updated_at = now() WHERE id = ?", listOf(taskId))
            added
        }
    }

    private fun insertAssignees(tenant: Connection, taskId: UUID, users: Collection<UUID>) {
        if (users.isEmpty()) return
        tenant.prepareStatement("INSERT INTO tareas_task_assignees (task_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING").use { insert ->
            users.forEach { user ->
                insert.setObject(1, taskId)
                insert.setObject(2, user)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    fun archive(tenant: Connection, id: UUID): Boolean =
        tenant.update("UPDATE tareas_tasks SET archived_at = now(), updated_at = now() WHERE id = ? AND archived_at IS NULL", listOf(id)) > 0

    // ---- checklist ----

    // Serializes the writers that count before inserting (limits per task).
    private fun lockTask(tenant: Connection, taskId: UUID) {
        tenant.query("SELECT 1 AS found FROM tareas_tasks WHERE id = ? FOR UPDATE", listOf(taskId)) { it.getInt("found") }.firstOrNull()
            ?: throw TareasValidationException("Tarea no encontrada.")
    }

    fun listChecklist(tenant: Connection, taskId: UUID): List<ChecklistItem> = tenant.query(
        "SELECT id, task_id, text, done, position, done_by, done_at FROM tareas_checklist WHERE task_id = ? ORDER BY position, id",
        listOf(taskId),
    ) { checklistItem(it) }

    fun getChecklistItem(tenant: Connection, itemId: UUID): ChecklistItem? = tenant.query(
        "SELECT id, task_id, text, done, position, done_by, done_at FROM tareas_checklist WHERE id = ?", listOf(itemId),
    ) { checklistItem(it) }.firstOrNull()

    private fun checklistItem(rows: ResultSet) = ChecklistItem(
        rows.uuid("id") ?: NilId, rows.uuid("task_id") ?: NilId, rows.getString("text"), rows.getBoolean("done"),
        rows.getInt("position"), rows.uuid("done_by"), rows.instant("done_at"),
    )

    fun addChecklistItem(tenant: Connection, taskId: UUID, text: String): UUID {
        val clean = text.trim()
        if (clean.isEmpty()) throw TareasValidationException("El elemento de la lista está vacío.")
        if (clean.length > 300) throw TareasValidationException("Cada elemento de la lista admite hasta 300 caracteres.")
        return transaction(tenant) {
            lockTask(tenant, taskId)
            val count = tenant.query("SELECT count(*) AS total FROM tareas_checklist WHERE task_id = ?", listOf(taskId)) { it.getInt("total") }.firstOrNull() ?: 0
            if (count >= MaxChecklistItems) throw TareasValidationException("Una tarea admite hasta $MaxChecklistItems elementos en la lista.")
            val id = UUID.randomUUID()
            tenant.update(
                "INSERT INTO tareas_checklist (id, task_id, text, position) VALUES (?, ?, ?, (SELECT COALESCE(MAX(position), 0) + 1 FROM tareas_checklist WHERE task_id = ?))",
                listOf(id, taskId, clean, taskId),
            )
            id
        }
    }

    fun toggleChecklistItem(tenant: Connection, itemId: UUID, done: Boolean, userId: UUID?): Boolean = tenant.update(
        "UPDATE tareas_checklist SET done = ?, done_by = CASE WHEN ? THEN ?::uuid ELSE NULL END, done_at = CASE WHEN ? THEN now() ELSE NULL END WHERE id = ?",
        listOf(done, done, userId, done, itemId),
    ) > 0

    fun removeChecklistItem(tenant: Connection, itemId: UUID): Boolean = tenant.update("DELETE FROM tareas_checklist WHERE id = ?", listOf(itemId)) > 0

    // ---- comments ----

    fun addComment(tenant: Connection, taskId: UUID, authorId: UUID?, authorName: String, body: String): UUID {
        val clean = body.trim()
        if (clean.isEmpty()) throw TareasValidationException("El comentario está vacío.")
        if (clean.length > MaxCommentLength) throw TareasValidationException("El comentario admite hasta $MaxCommentLength caracteres.")
        return transaction(tenant) {
            lockTask(tenant, taskId)
            val count = tenant.query("SELECT count(*) AS total FROM tareas_comments WHERE task_id = ?", listOf(taskId)) { it.getInt("total") }.firstOrNull() ?: 0
            if (count >= MaxCommentsPerTask) throw TareasValidationException("Esta tarea ya tiene el máximo de $MaxCommentsPerTask comentarios.")
            val id = UUID.randomUUID()
            tenant.update(
                "INSERT INTO tareas_comments (id, task_id, author_id, author_name, body) VALUES (?, ?, ?, ?, ?)",
                listOf(id, taskId, authorId, authorName.trim().ifEmpty { "Usuario" }.take(120), clean),
            )
            id
        }
    }

    /** The latest [limit] comments, oldest first. */
    fun listComments(tenant: Connection, taskId: UUID, limit: Int = 100): List<TaskComment> = tenant.query(
        "SELECT id, task_id, author_id, author_name, body, created_at FROM tareas_comments WHERE task_id = ? ORDER BY created_at DESC, id LIMIT ?",
        listOf(taskId, limit.coerceIn(1, 500)),
    ) {
        TaskComment(it.uuid("id") ?: NilId, it.uuid("task_id") ?: NilId, it.uuid("author_id"), it.getString("author_name"), it.getString("body"), it.instant("created_at") ?: Instant.EPOCH)
    }.reversed()

    // ---- activity ----

    fun addActivity(tenant: Connection, taskId: UUID, kind: String, detail: String, authorName: String) {
        tenant.update(
            "INSERT INTO tareas_activity (id, task_id, kind, detail, author_name) VALUES (?, ?, ?, ?, ?)",
            listOf(UUID.randomUUID(), taskId, kind.take(40), detail.take(1000), authorName.take(120)),
        )
    }

    /** The latest [limit] changes, newest first. */
    fun listActivity(tenant: Connection, taskId: UUID, limit: Int = 50): List<TaskActivity> = tenant.query(
        "SELECT id, task_id, kind, detail, author_name, created_at FROM tareas_activity WHERE task_id = ? ORDER BY created_at DESC, id LIMIT ?",
        listOf(taskId, limit.coerceIn(1, 500)),
    ) {
        TaskActivity(it.uuid("id") ?: NilId, it.uuid("task_id") ?: NilId, it.getString("kind"), it.getString("detail"), it.getString("author_name"), it.instant("created_at") ?: Instant.EPOCH)
    }

    // ---- attachments ----

    class StoredAttachment(val attachment: TaskAttachment, val open: () -> InputStream?)

    // Name without folders or control characters; the folder is kept apart, normalized to a/b/c.
    fun cleanFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.trim().take(255)

    fun cleanFilePath(path: String): String = path.replace('\\', '/').split('/')
        .map { part -> part.filter { it >= ' ' }.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")
        .take(500)

    /**
     * Stores an upload that waits for its form to be saved (task_id NULL) and returns the ref the form keeps. The content
     * goes to [files] first; if the row cannot be saved the stored file is removed.
     */
    fun upload(tenant: Connection, files: ModuleFiles, name: String, path: String, contentType: String, content: InputStream, uploadedBy: UUID?): UiFileRef {
        val fileName = cleanFileName(name)
        if (fileName.isEmpty()) throw TareasValidationException("El archivo no tiene nombre.")
        val type = contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" }
        // Uploads that wait for their form are limited per user, so abandoned forms cannot fill the storage.
        val pending = tenant.query(
            "SELECT count(*) AS total, COALESCE(sum(size), 0) AS bytes FROM tareas_attachments WHERE task_id IS NULL AND NOT delete_pending AND uploaded_by IS NOT DISTINCT FROM ?",
            listOf(uploadedBy),
        ) { it.getInt("total") to it.getLong("bytes") }.firstOrNull() ?: (0 to 0L)
        if (pending.first >= MaxPendingFiles || pending.second >= MaxPendingBytes) {
            throw TareasValidationException("Tienes demasiados archivos sin guardar; guarda o espera.")
        }
        val stored = try {
            files.put(fileName, type, content, MaxFileBytes)
        } catch (_: FileTooLargeException) {
            throw TareasValidationException("$fileName supera el máximo de ${MaxFileBytes / (1024 * 1024)} MB.")
        }
        val ref = UiFileRef(UUID.randomUUID().toString(), fileName, cleanFilePath(path), stored.size)
        try {
            tenant.update(
                "INSERT INTO tareas_attachments (id, name, path, content_type, size, backend, ref, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(UUID.fromString(ref.id), ref.name, ref.path, type, ref.size, stored.backend, stored.ref, uploadedBy),
            )
        } catch (failure: Exception) {
            runCatching { files.delete(stored) }
            throw failure
        }
        return ref
    }

    /** Links pending uploads of [uploaderId] to the task and returns their names. Files of anyone else, or already linked, are ignored; without a user nothing is linked. */
    fun attach(tenant: Connection, taskId: UUID, ids: Collection<UUID>, uploaderId: UUID?): List<String> {
        val pending = ids.distinct()
        if (pending.isEmpty() || uploaderId == null) return emptyList()
        return transaction(tenant) {
            lockTask(tenant, taskId)
            val total = tenant.query("SELECT count(*) AS total FROM tareas_attachments WHERE task_id = ? AND NOT delete_pending", listOf(taskId)) { it.getInt("total") }.firstOrNull() ?: 0
            if (total + pending.size > MaxFilesPerTask) throw TareasValidationException("Una tarea admite hasta $MaxFilesPerTask archivos.")
            tenant.query(
                """
                UPDATE tareas_attachments SET task_id = ?
                WHERE task_id IS NULL AND NOT delete_pending AND uploaded_by = ? AND id = ANY(?)
                RETURNING name
                """.trimIndent(),
                listOf(taskId, uploaderId, tenant.uuidArray(pending)),
            ) { it.getString("name") }
        }
    }

    fun listAttachments(tenant: Connection, taskId: UUID): List<TaskAttachment> = tenant.query(
        "SELECT id, task_id, name, path, content_type, size, uploaded_by, created_at FROM tareas_attachments WHERE task_id = ? AND NOT delete_pending ORDER BY path, name, id",
        listOf(taskId),
    ) { attachment(it) }

    private fun attachment(rows: ResultSet) = TaskAttachment(
        rows.uuid("id") ?: NilId, rows.uuid("task_id"), rows.getString("name"), rows.getString("path"), rows.getString("content_type"),
        rows.getLong("size"), rows.uuid("uploaded_by"), rows.instant("created_at") ?: Instant.EPOCH,
    )

    /** A file that is not marked for deletion: of a task, or a pending upload. */
    fun file(tenant: Connection, files: ModuleFiles, id: UUID): StoredAttachment? {
        class Row(val attachment: TaskAttachment, val backend: String, val ref: String)
        val row = tenant.query(
            "SELECT id, task_id, name, path, content_type, size, uploaded_by, created_at, backend, ref FROM tareas_attachments WHERE id = ? AND NOT delete_pending",
            listOf(id),
        ) { Row(attachment(it), it.getString("backend"), it.getString("ref")) }.firstOrNull() ?: return null
        val stored = StoredFileRef(row.backend, row.ref, row.attachment.size)
        return StoredAttachment(row.attachment) { files.open(stored) }
    }

    /** Marks files of the task for deletion; the cleanup job removes the stored content later. */
    fun markDeleted(tenant: Connection, taskId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return tenant.update("UPDATE tareas_attachments SET delete_pending = TRUE WHERE task_id = ? AND id = ANY(?)", listOf(taskId, tenant.uuidArray(ids.distinct())))
    }

    /** Deletes the stored files and rows of removed attachments and of uploads whose form was never saved within a day. */
    fun cleanup(tenant: Connection, files: ModuleFiles, batch: Int = 200) {
        class Doomed(val id: UUID, val stored: StoredFileRef)
        val doomed = tenant.query(
            """
            SELECT id, backend, ref, size FROM tareas_attachments
            WHERE delete_pending OR (task_id IS NULL AND created_at < now() - INTERVAL '24 hours')
            ORDER BY created_at
            LIMIT ?
            """.trimIndent(),
            listOf(batch.coerceIn(1, 1000)),
        ) { Doomed(it.uuid("id") ?: NilId, StoredFileRef(it.getString("backend"), it.getString("ref"), it.getLong("size"))) }
        doomed.forEach { item ->
            try {
                files.delete(item.stored)
                tenant.update("DELETE FROM tareas_attachments WHERE id = ?", listOf(item.id))
            } catch (failure: Exception) {
                // The row stays and is retried on the next run.
                log.warn("No se pudo borrar el adjunto {} de {}", item.id, files.companyCode, failure)
            }
        }
    }

    // ---- customers ----

    /** The customer exists and is not archived. */
    fun customerExists(tenant: Connection, id: UUID): Boolean =
        tenant.query("SELECT 1 AS found FROM customers WHERE id = ? AND status <> 'archived'", listOf(id)) { it.getInt("found") }.isNotEmpty()

    fun customerName(tenant: Connection, id: UUID): String? =
        tenant.query("SELECT display_name FROM customers WHERE id = ?", listOf(id)) { it.getString("display_name") }.firstOrNull()

    /** Customers (not archived) whose name or email contains [query]; the route checks tareas.manage and clientes.view. */
    fun customerSuggestions(tenant: Connection, query: String, limit: Int = 6): List<CustomerSuggestion> {
        val text = query.trim().lowercase().take(200)
        if (text.length < 2) return emptyList()
        val pattern = "%${like(text)}%"
        return tenant.query(
            """
            SELECT id, display_name, primary_email FROM customers
            WHERE status <> 'archived' AND (lower(display_name) LIKE ? OR lower(primary_email) LIKE ?)
            ORDER BY display_name, id
            LIMIT ?
            """.trimIndent(),
            listOf(pattern, pattern, limit.coerceIn(1, 20)),
        ) { CustomerSuggestion(it.uuid("id") ?: NilId, it.getString("display_name"), it.getString("primary_email")) }
    }

    // ---- workload and reminders ----

    /** Open tasks per responsible among the tasks the scope sees (of one board, or of all when [boardId] is null). */
    fun workload(tenant: Connection, scope: TareasScope, boardId: UUID?): List<WorkloadRow> {
        val params = mutableListOf<Any?>(today())
        val visible = visibleTasks(tenant, scope, params)
        val board = if (boardId != null) { params += boardId; " AND t.board_id = ?" } else " AND b.archived = FALSE"
        return tenant.query(
            """
            SELECT a.user_id,
                   count(*) FILTER (WHERE t.status NOT IN $OpenStatuses) AS open_count,
                   count(*) FILTER (WHERE t.status NOT IN $OpenStatuses AND t.due_date < ?) AS overdue_count,
                   count(*) FILTER (WHERE t.status = 'review') AS review_count
            FROM tareas_tasks t
            JOIN tareas_boards b ON b.id = t.board_id
            JOIN tareas_task_assignees a ON a.task_id = t.id
            WHERE t.archived_at IS NULL AND $visible$board
            GROUP BY a.user_id
            HAVING count(*) FILTER (WHERE t.status NOT IN $OpenStatuses) > 0
            ORDER BY open_count DESC, a.user_id
            """.trimIndent(),
            params,
        ) { WorkloadRow(it.uuid("user_id") ?: NilId, it.getInt("open_count"), it.getInt("overdue_count"), it.getInt("review_count")) }
    }

    /** Reminders not sent yet: open tasks due tomorrow ("due_soon") or before [today] ("overdue"), one per responsible. */
    fun reminderCandidates(tenant: Connection, today: LocalDate, limit: Int = 200): List<ReminderCandidate> = tenant.query(
        """
        SELECT t.id, a.user_id, t.title, b.name AS board_name, t.due_date,
               CASE WHEN t.due_date < ? THEN 'overdue' ELSE 'due_soon' END AS kind
        FROM tareas_tasks t
        JOIN tareas_boards b ON b.id = t.board_id AND b.archived = FALSE
        JOIN tareas_task_assignees a ON a.task_id = t.id
        JOIN application_users au ON au.id = a.user_id AND au.is_active
        WHERE t.archived_at IS NULL AND t.status NOT IN $OpenStatuses
          AND (t.due_date < ? OR t.due_date = ?)
          AND NOT EXISTS (
              SELECT 1 FROM tareas_reminders r
              WHERE r.task_id = t.id AND r.user_id = a.user_id AND r.due_date = t.due_date
                AND r.kind = CASE WHEN t.due_date < ? THEN 'overdue' ELSE 'due_soon' END)
        ORDER BY t.due_date, t.id, a.user_id
        LIMIT ?
        """.trimIndent(),
        listOf(today, today, today.plusDays(1), today, limit.coerceIn(1, 1000)),
    ) {
        ReminderCandidate(it.uuid("id") ?: NilId, it.uuid("user_id") ?: NilId, it.getString("kind"), it.date("due_date") ?: today, it.getString("title"), it.getString("board_name"))
    }

    /** Records the reminder; true only for the caller that inserted it, so each notice is sent once. */
    fun claimReminder(tenant: Connection, taskId: UUID, userId: UUID, kind: String, dueDate: LocalDate): Boolean = tenant.update(
        "INSERT INTO tareas_reminders (task_id, user_id, kind, due_date) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        listOf(taskId, userId, kind, dueDate),
    ) > 0
}
