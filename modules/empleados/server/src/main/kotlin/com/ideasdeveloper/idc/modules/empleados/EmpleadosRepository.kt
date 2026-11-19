package com.ideasdeveloper.idc.modules.empleados

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** Los horarios de varios empleados cargados para un rango de fechas, listos para calcular con [EmpleadosSchedule.resolve]. */
class ScheduleData(
    val weekly: Map<UUID, List<WeeklySlot>>,
    val shifts: Map<UUID, List<Shift>>,
    val absences: Map<UUID, List<Absence>>,
) {
    fun resolve(employeeId: UUID, date: LocalDate, defaultSite: UUID?): DaySchedule =
        EmpleadosSchedule.resolve(date, weekly[employeeId].orEmpty(), shifts[employeeId].orEmpty(), absences[employeeId].orEmpty(), defaultSite)
}

/** Las dos listas editables del modulo: comparten estructura y reglas. El nombre de la tabla sale de aqui, nunca del usuario. */
enum class Catalog(val table: String, val singular: String) {
    Sites("empleados_sites", "sede"),
    Departments("empleados_departments", "departamento"),
}

/**
 * Acceso a datos del modulo. Todo es sincrono y recibe la conexion de la empresa: quien llama lo ejecuta dentro de
 * withContext(Dispatchers.IO). Todo el SQL esta parametrizado; las listas usan `= ANY(?)` con un arreglo uuid.
 *
 * La visibilidad por usuario se aplica aqui, en SQL: las columnas de contacto, ingreso y notas solo se seleccionan cuando el
 * [FieldLevel] las incluye, y quien solo ve el directorio basico nunca recibe fichas de baja.
 */
object EmpleadosRepository {
    const val MaxListed = 500

    /** Tope de fichas que una consulta devuelve (otros modulos piden todos los activos con [EmpleadosExternal]). */
    const val MaxEmployeesQuery = 5000
    const val MaxWeekEmployees = 300
    const val MaxCatalogItems = 200
    const val MaxSlotsPerDay = 4
    private const val MaxNotified = 20

    /** Largo maximo del texto de busqueda (se recorta en el servidor). */
    const val MaxQuery = 100
    private val log = LoggerFactory.getLogger(EmpleadosRepository::class.java)
    private val NilId = UUID(0L, 0L)

    /** Hoy en la zona horaria del servidor: el mismo dia que usan los avisos y la vista semanal. */
    fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    fun <T> transaction(tenant: Connection, block: () -> T): T {
        val previous = tenant.autoCommit
        // Ya dentro de una transaccion de quien llama: el decide cuando confirmar.
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

    // ---- ayudas ----

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

    private fun ResultSet.date(column: String): LocalDate? = getObject(column, LocalDate::class.java)

    private fun ResultSet.time(column: String): LocalTime? = getObject(column, LocalTime::class.java)

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    /** Escapa los comodines de LIKE para que lo que escribe el usuario se busque tal cual. */
    private fun like(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Violacion de una restriccion unica (otra peticion se adelanto). */
    fun isUniqueViolation(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any { it is SQLException && it.sqlState == "23505" }

    // ---- usuarios ----

    /** Etiquetas de usuarios: dos personas con el mismo nombre se distinguen por su usuario. */
    private fun labelled(rows: List<Triple<UUID, String, String>>, usernames: Map<UUID, String>): List<UserRef> {
        val repeated = rows.groupingBy { it.second.lowercase() }.eachCount().filterValues { it > 1 }.keys
        return rows.map { (id, name, email) -> UserRef(id, if (name.lowercase() in repeated) "$name (${usernames[id]})" else name, email) }
    }

    /** Usuarios activos sin ficha, mas [keepUserId] (el enlazado ahora): los que se pueden enlazar a una ficha. */
    fun linkableUsers(tenant: Connection, keepUserId: UUID?): List<UserRef> {
        val usernames = mutableMapOf<UUID, String>()
        val rows = tenant.query(
            """
            SELECT u.id, u.postgres_role, COALESCE(NULLIF(btrim(u.display_name), ''), u.postgres_role) AS label, COALESCE(u.email, '') AS email
            FROM application_users u
            WHERE u.is_active
              AND (u.id = ? OR NOT EXISTS (SELECT 1 FROM empleados_employees e WHERE e.user_id = u.id))
            ORDER BY label, u.postgres_role
            LIMIT $MaxListed
            """.trimIndent(),
            listOf(keepUserId ?: NilId),
        ) {
            val id = it.uuid("id") ?: NilId
            usernames[id] = it.getString("postgres_role")
            Triple(id, it.getString("label"), it.getString("email"))
        }
        return labelled(rows, usernames)
    }

    /** Usuario activo que se puede enlazar a [employeeId] (null: ficha nueva): existe y no tiene otra ficha. */
    fun canLink(tenant: Connection, userId: UUID, employeeId: UUID?): Boolean = tenant.query(
        """
        SELECT 1 FROM application_users u
        WHERE u.id = ? AND u.is_active
          AND NOT EXISTS (SELECT 1 FROM empleados_employees e WHERE e.user_id = u.id AND e.id <> ?)
        """.trimIndent(),
        listOf(userId, employeeId ?: NilId),
    ) { 1 }.isNotEmpty()

    /** A quien avisar de una ausencia pedida: duenos del negocio y quien tiene empleados.shifts o empleados.manage (hasta 20). */
    fun approvers(tenant: Connection): List<UUID> = tenant.query(
        """
        SELECT u.id FROM application_users u
        WHERE u.is_active
          AND (EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = u.id)
               OR EXISTS (SELECT 1 FROM application_user_permissions granted
                          WHERE granted.user_id = u.id AND granted.permission_id IN (?, ?)))
        ORDER BY (CASE WHEN EXISTS (SELECT 1 FROM business_owner first_owner WHERE first_owner.user_id = u.id) THEN 0 ELSE 1 END), u.id
        LIMIT $MaxNotified
        """.trimIndent(),
        listOf(EmpleadosModule.Shifts, EmpleadosModule.Manage),
    ) { it.uuid("id") ?: NilId }

    /** La ficha enlazada a la cuenta, o null. */
    fun employeeIdForUser(tenant: Connection, userId: UUID): UUID? =
        tenant.query("SELECT id FROM empleados_employees WHERE user_id = ?", listOf(userId)) { it.uuid("id") ?: NilId }.firstOrNull()

    /** Cuenta enlazada a la ficha (solo para avisar: nunca se muestra a quien no administra fichas). */
    fun userIdOf(tenant: Connection, employeeId: UUID): UUID? =
        tenant.query("SELECT user_id FROM empleados_employees WHERE id = ?", listOf(employeeId)) { it.uuid("user_id") }.firstOrNull()

    /** Cuentas enlazadas de varias fichas (por ficha); solo para otros modulos y avisos, nunca para mostrar en pantalla. */
    fun userIdsOf(tenant: Connection, employeeIds: Collection<UUID>): Map<UUID, UUID> {
        if (employeeIds.isEmpty()) return emptyMap()
        return tenant.query(
            "SELECT id, user_id FROM empleados_employees WHERE user_id IS NOT NULL AND id = ANY(?)",
            listOf(tenant.uuidArray(employeeIds.distinct())),
        ) { (it.uuid("id") ?: NilId) to (it.uuid("user_id") ?: NilId) }.toMap()
    }

    // ---- fichas ----

    private fun employeeColumns(level: FieldLevel): String = buildString {
        append("e.id, e.full_name, e.job_title, e.status, e.department_id, d.name AS department_name, e.site_id, s.name AS site_name")
        if (level.contact) append(", e.phone, e.email, e.availability")
        if (level.hireDate) append(", e.hire_date")
        if (level.internal) append(", e.ended_on, e.notes, e.user_id")
    }

    private const val EmployeeFrom = """
        FROM empleados_employees e
        LEFT JOIN empleados_departments d ON d.id = e.department_id
        LEFT JOIN empleados_sites s ON s.id = e.site_id
    """

    private fun employee(rows: ResultSet, level: FieldLevel) = Employee(
        id = rows.uuid("id") ?: NilId,
        fullName = rows.getString("full_name"),
        jobTitle = rows.getString("job_title"),
        departmentId = rows.uuid("department_id"),
        department = rows.getString("department_name"),
        siteId = rows.uuid("site_id"),
        site = rows.getString("site_name"),
        status = EmployeeStatus.fromCode(rows.getString("status")) ?: EmployeeStatus.Active,
        level = level,
        phone = if (level.contact) rows.getString("phone") else null,
        email = if (level.contact) rows.getString("email") else null,
        availability = if (level.contact) rows.getString("availability") else null,
        hireDate = if (level.hireDate) rows.date("hire_date") else null,
        endedOn = if (level.internal) rows.date("ended_on") else null,
        notes = if (level.internal) rows.getString("notes") else null,
        userId = if (level.internal) rows.uuid("user_id") else null,
    )

    /** La condicion SQL de que fichas ve el usuario: quien gestiona turnos todas; los demas los activos y la suya. */
    private fun visible(scope: EmpleadosScope, params: MutableList<Any?>): String = when {
        scope.canShifts || scope.canManage -> "TRUE"
        scope.myEmployeeId != null -> {
            params += scope.myEmployeeId
            "(e.status = 'active' OR e.id = ?)"
        }
        else -> "e.status = 'active'"
    }

    /**
     * Fichas que el usuario ve, por nombre. Lee solo las columnas de [level] (por defecto, las que el usuario puede ver en
     * una lista); los desplegables piden [FieldLevel.Basic]. La busqueda por texto mira telefono y correo solo si el nivel los incluye.
     */
    fun list(tenant: Connection, scope: EmpleadosScope, filter: EmployeeFilter, level: FieldLevel = scope.listFields): List<Employee> {
        val params = mutableListOf<Any?>()
        val where = mutableListOf(visible(scope, params))
        if (scope.canShifts || scope.canManage) {
            filter.status?.let {
                where += "e.status = ?"
                params += it.code
            }
        }
        filter.siteId?.let {
            where += "e.site_id = ?"
            params += it
        }
        filter.departmentId?.let {
            where += "e.department_id = ?"
            params += it
        }
        filter.query?.trim()?.take(MaxQuery)?.takeIf { it.isNotEmpty() }?.let { text ->
            val pattern = "%${like(text.lowercase())}%"
            val columns = buildList {
                add("lower(e.full_name)")
                add("lower(e.job_title)")
                if (level.contact) {
                    add("lower(e.email)")
                    add("e.phone")
                }
            }
            where += columns.joinToString(" OR ", "(", ")") { "$it LIKE ?" }
            repeat(columns.size) { params += pattern }
        }
        params += filter.limit.coerceIn(1, MaxEmployeesQuery)
        return tenant.query(
            "SELECT ${employeeColumns(level)} $EmployeeFrom WHERE ${where.joinToString(" AND ")} ORDER BY lower(e.full_name), e.id LIMIT ?",
            params,
        ) { employee(it, level) }
    }

    /** La ficha [id] si el usuario la ve, con los campos que le corresponden; null si no existe o no la ve. */
    fun get(tenant: Connection, scope: EmpleadosScope, id: UUID): Employee? {
        val params = mutableListOf<Any?>(id)
        val level = scope.fields(id)
        return tenant.query(
            "SELECT ${employeeColumns(level)} $EmployeeFrom WHERE e.id = ? AND ${visible(scope, params)}",
            params,
        ) { employee(it, level) }.firstOrNull()
    }

    /** Nombre y sede de fichas por id, para avisos y mensajes (solo datos basicos; quien llama ya comprobo el permiso). */
    fun basics(tenant: Connection, ids: Collection<UUID>): Map<UUID, Employee> {
        if (ids.isEmpty()) return emptyMap()
        return tenant.query(
            "SELECT ${employeeColumns(FieldLevel.Basic)} $EmployeeFrom WHERE e.id = ANY(?)",
            listOf(tenant.uuidArray(ids.distinct())),
        ) { employee(it, FieldLevel.Basic) }.associateBy { it.id }
    }

    fun insertEmployee(tenant: Connection, input: EmployeeInput, actorId: UUID?): UUID = tenant.query(
        """
        INSERT INTO empleados_employees (full_name, job_title, department_id, site_id, phone, email, hire_date, availability, notes, user_id, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """.trimIndent(),
        listOf(
            input.fullName.trim(), input.jobTitle.trim(), input.departmentId, input.siteId, input.phone.trim(), input.email.trim(),
            input.hireDate, input.availability.trim(), input.notes, input.userId, actorId,
        ),
    ) { it.uuid("id") ?: NilId }.first()

    fun updateEmployee(tenant: Connection, id: UUID, input: EmployeeInput): Boolean = tenant.update(
        """
        UPDATE empleados_employees
        SET full_name = ?, job_title = ?, department_id = ?, site_id = ?, phone = ?, email = ?, hire_date = ?, availability = ?, notes = ?, user_id = ?, updated_at = now()
        WHERE id = ?
        """.trimIndent(),
        listOf(
            input.fullName.trim(), input.jobTitle.trim(), input.departmentId, input.siteId, input.phone.trim(), input.email.trim(),
            input.hireDate, input.availability.trim(), input.notes, input.userId, id,
        ),
    ) > 0

    /** Da de baja (con la fecha) o reactiva (sin fecha de baja). */
    fun setStatus(tenant: Connection, id: UUID, status: EmployeeStatus, endedOn: LocalDate?): Boolean = tenant.update(
        "UPDATE empleados_employees SET status = ?, ended_on = ?, updated_at = now() WHERE id = ?",
        listOf(status.code, if (status == EmployeeStatus.Inactive) endedOn else null, id),
    ) > 0

    // ---- sedes y departamentos ----

    private fun catalogItem(rows: ResultSet) = CatalogItem(rows.uuid("id") ?: NilId, rows.getString("name"), rows.getInt("position"), rows.getBoolean("archived"))

    fun listCatalog(tenant: Connection, catalog: Catalog, includeArchived: Boolean = false): List<CatalogItem> = tenant.query(
        "SELECT id, name, position, archived FROM ${catalog.table} ${if (includeArchived) "" else "WHERE archived = FALSE"} ORDER BY position, lower(name), id LIMIT $MaxCatalogItems",
        emptyList(),
        ::catalogItem,
    )

    /** Nombres de todas las sedes (tambien archivadas) por id, para mostrar horarios pasados. */
    fun catalogNames(tenant: Connection, catalog: Catalog): Map<UUID, String> =
        tenant.query("SELECT id, name FROM ${catalog.table} LIMIT 5000", emptyList()) { (it.uuid("id") ?: NilId) to it.getString("name") }.toMap()

    fun getCatalog(tenant: Connection, catalog: Catalog, id: UUID): CatalogItem? =
        tenant.query("SELECT id, name, position, archived FROM ${catalog.table} WHERE id = ?", listOf(id), ::catalogItem).firstOrNull()

    private fun cleanName(catalog: Catalog, name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) throw EmpleadosValidationException("El nombre de la ${catalog.singular} es obligatorio.")
        if (clean.length > 120) throw EmpleadosValidationException("El nombre de la ${catalog.singular} admite hasta 120 caracteres.")
        return clean
    }

    /** Falla si ya hay [MaxCatalogItems] activas: crear o desarchivar una mas pasaria el tope. */
    private fun requireRoom(tenant: Connection, catalog: Catalog) {
        val active = tenant.query("SELECT count(*) AS total FROM ${catalog.table} WHERE archived = FALSE", emptyList()) { it.getInt("total") }.first()
        if (active >= MaxCatalogItems) throw EmpleadosValidationException("Se alcanzó el máximo de $MaxCatalogItems ${catalog.singular}s activas.")
    }

    /**
     * Serializa los cambios de franjas, turnos y ausencias de un empleado (validar y escribir sin que otra peticion se cuele).
     * Se toma al inicio de la transaccion, antes de validar; se libera sola al confirmar o deshacer.
     */
    fun lockEmployee(tenant: Connection, employeeId: UUID) {
        tenant.query("SELECT pg_advisory_xact_lock(hashtext('empleados:emp:' || ?))", listOf(employeeId.toString())) { 1 }
    }

    private fun lockCatalog(tenant: Connection, catalog: Catalog) {
        // Serializa altas y movimientos de una misma lista (posiciones y limite).
        tenant.query("SELECT pg_advisory_xact_lock(hashtext(?))", listOf(catalog.table)) { 1 }
    }

    /** Anade una sede o departamento; si existe con ese nombre archivado se desarchiva, y si esta activo es un error. */
    fun addCatalog(tenant: Connection, catalog: Catalog, name: String): UUID {
        val clean = cleanName(catalog, name)
        return try {
            transaction(tenant) {
                lockCatalog(tenant, catalog)
                val existing = tenant.query("SELECT id, archived FROM ${catalog.table} WHERE lower(name) = lower(?)", listOf(clean)) { it.uuid("id") to it.getBoolean("archived") }.firstOrNull()
                if (existing != null) {
                    if (!existing.second) throw EmpleadosValidationException("Ya existe una ${catalog.singular} llamada \"$clean\".")
                    requireRoom(tenant, catalog)
                    val position = nextPosition(tenant, catalog)
                    tenant.update("UPDATE ${catalog.table} SET archived = FALSE, name = ?, position = ?, updated_at = now() WHERE id = ?", listOf(clean, position, existing.first))
                    return@transaction existing.first ?: NilId
                }
                requireRoom(tenant, catalog)
                tenant.query("INSERT INTO ${catalog.table} (name, position) VALUES (?, ?) RETURNING id", listOf(clean, nextPosition(tenant, catalog))) { it.uuid("id") ?: NilId }.first()
            }
        } catch (failure: SQLException) {
            if (isUniqueViolation(failure)) throw EmpleadosValidationException("Ya existe una ${catalog.singular} llamada \"$clean\".") else throw failure
        }
    }

    private fun nextPosition(tenant: Connection, catalog: Catalog): Int =
        tenant.query("SELECT COALESCE(max(position), -1) + 1 AS next FROM ${catalog.table}", emptyList()) { it.getInt("next") }.first()

    fun renameCatalog(tenant: Connection, catalog: Catalog, id: UUID, name: String): Boolean {
        val clean = cleanName(catalog, name)
        return try {
            tenant.update("UPDATE ${catalog.table} SET name = ?, updated_at = now() WHERE id = ?", listOf(clean, id)) > 0
        } catch (failure: SQLException) {
            if (isUniqueViolation(failure)) throw EmpleadosValidationException("Ya existe una ${catalog.singular} llamada \"$clean\".") else throw failure
        }
    }

    /** Sube o baja un elemento entre los activos y reescribe las posiciones 0..n. */
    fun moveCatalog(tenant: Connection, catalog: Catalog, id: UUID, up: Boolean): Boolean = transaction(tenant) {
        lockCatalog(tenant, catalog)
        val items = listCatalog(tenant, catalog).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@transaction false
        val target = if (up) index - 1 else index + 1
        if (target in items.indices) {
            val moved = items.removeAt(index)
            items.add(target, moved)
        }
        items.forEachIndexed { position, item ->
            if (item.position != position) tenant.update("UPDATE ${catalog.table} SET position = ?, updated_at = now() WHERE id = ?", listOf(position, item.id))
        }
        true
    }

    fun archiveCatalog(tenant: Connection, catalog: Catalog, id: UUID): Boolean =
        tenant.update("UPDATE ${catalog.table} SET archived = TRUE, updated_at = now() WHERE id = ?", listOf(id)) > 0

    // ---- importacion ----

    /** Una ficha ya importada: lo necesario para actualizarla sin pisar el enlace con el usuario ni reactivarla. */
    class ImportRef(val id: UUID, val userId: UUID?, val status: EmployeeStatus)

    /** Textos ya recortados de una fila importada; un texto vacio es "la fila no lo trae". */
    class ImportFields(val fullName: String, val jobTitle: String, val phone: String, val email: String, val availability: String, val notes: String)

    fun findByImportKey(tenant: Connection, key: String): ImportRef? = tenant.query(
        "SELECT id, user_id, status FROM empleados_employees WHERE import_key = ?",
        listOf(key),
    ) { ImportRef(it.uuid("id") ?: NilId, it.uuid("user_id"), EmployeeStatus.fromCode(it.getString("status")) ?: EmployeeStatus.Active) }.firstOrNull()

    /**
     * La sede o departamento con ese nombre (sin distinguir mayusculas, tambien archivados: se reutiliza y desarchiva) o uno
     * nuevo. [importKey] solo para sedes: se guarda si ninguna otra sede ya lo tiene.
     */
    fun findOrCreateCatalog(tenant: Connection, catalog: Catalog, name: String, importKey: String?): UUID {
        val clean = cleanName(catalog, name)
        // Serializa con las altas manuales y otras importaciones (posiciones y tope de 200).
        lockCatalog(tenant, catalog)
        val existing = tenant.query("SELECT id, archived FROM ${catalog.table} WHERE lower(name) = lower(?)", listOf(clean)) { (it.uuid("id") ?: NilId) to it.getBoolean("archived") }.firstOrNull()
        if (existing != null) {
            if (existing.second) {
                requireRoom(tenant, catalog)
                tenant.update("UPDATE ${catalog.table} SET archived = FALSE, position = ?, updated_at = now() WHERE id = ?", listOf(nextPosition(tenant, catalog), existing.first))
            }
            return existing.first
        }
        requireRoom(tenant, catalog)
        val position = nextPosition(tenant, catalog)
        return if (catalog == Catalog.Sites && importKey != null) {
            tenant.query(
                "INSERT INTO empleados_sites (name, position, import_key) VALUES (?, ?, CASE WHEN EXISTS (SELECT 1 FROM empleados_sites WHERE import_key = ?) THEN NULL ELSE ? END) RETURNING id",
                listOf(clean, position, importKey, importKey),
            ) { it.uuid("id") ?: NilId }.first()
        } else {
            tenant.query("INSERT INTO ${catalog.table} (name, position) VALUES (?, ?) RETURNING id", listOf(clean, position)) { it.uuid("id") ?: NilId }.first()
        }
    }

    fun insertImported(tenant: Connection, key: String, fields: ImportFields, departmentId: UUID?, siteId: UUID?, userId: UUID?, active: Boolean, actorId: UUID?, today: LocalDate): UUID = tenant.query(
        """
        INSERT INTO empleados_employees (full_name, job_title, department_id, site_id, phone, email, availability, notes, user_id, status, ended_on, import_key, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """.trimIndent(),
        listOf(
            fields.fullName, fields.jobTitle, departmentId, siteId, fields.phone, fields.email, fields.availability, fields.notes, userId,
            if (active) EmployeeStatus.Active.code else EmployeeStatus.Inactive.code, if (active) null else today, key, actorId,
        ),
    ) { it.uuid("id") ?: NilId }.first()

    /** Actualiza solo los campos que la fila trae no vacios; [deactivate] da de baja (nunca reactiva). */
    fun updateImported(tenant: Connection, id: UUID, fields: ImportFields, departmentId: UUID?, siteId: UUID?, userId: UUID?, deactivate: Boolean, today: LocalDate) {
        val sets = mutableListOf("full_name = ?")
        val params = mutableListOf<Any?>(fields.fullName)
        fun text(column: String, value: String) {
            if (value.isNotEmpty()) {
                sets += "$column = ?"
                params += value
            }
        }
        text("job_title", fields.jobTitle)
        text("phone", fields.phone)
        text("email", fields.email)
        text("availability", fields.availability)
        text("notes", fields.notes)
        departmentId?.let { sets += "department_id = ?"; params += it }
        siteId?.let { sets += "site_id = ?"; params += it }
        userId?.let { sets += "user_id = ?"; params += it }
        if (deactivate) {
            sets += "status = 'inactive'"
            sets += "ended_on = ?"
            params += today
        }
        sets += "updated_at = now()"
        params += id
        tenant.update("UPDATE empleados_employees SET ${sets.joinToString(", ")} WHERE id = ?", params)
    }

    // ---- horario habitual ----

    private fun weeklySlot(rows: ResultSet) = WeeklySlot(
        id = rows.uuid("id") ?: NilId,
        employeeId = rows.uuid("employee_id") ?: NilId,
        weekday = rows.getInt("weekday"),
        start = rows.time("start_time") ?: LocalTime.MIDNIGHT,
        end = rows.time("end_time") ?: LocalTime.MIDNIGHT,
        siteId = rows.uuid("site_id"),
        position = rows.getInt("position"),
    )

    fun weekly(tenant: Connection, employeeIds: Collection<UUID>?): List<WeeklySlot> {
        val base = "SELECT id, employee_id, weekday, start_time, end_time, site_id, position FROM empleados_weekly"
        return if (employeeIds == null) {
            tenant.query("$base WHERE employee_id IN (SELECT id FROM empleados_employees WHERE status = 'active') ORDER BY employee_id, weekday, start_time, id", emptyList(), ::weeklySlot)
        } else {
            if (employeeIds.isEmpty()) return emptyList()
            tenant.query("$base WHERE employee_id = ANY(?) ORDER BY employee_id, weekday, start_time, id", listOf(tenant.uuidArray(employeeIds.distinct())), ::weeklySlot)
        }
    }

    fun getWeeklySlot(tenant: Connection, id: UUID): WeeklySlot? =
        tenant.query("SELECT id, employee_id, weekday, start_time, end_time, site_id, position FROM empleados_weekly WHERE id = ?", listOf(id), ::weeklySlot).firstOrNull()

    fun insertWeeklySlot(tenant: Connection, employeeId: UUID, weekday: Int, start: LocalTime, end: LocalTime, siteId: UUID?): UUID = tenant.query(
        """
        INSERT INTO empleados_weekly (employee_id, weekday, start_time, end_time, site_id, position)
        VALUES (?, ?, ?, ?, ?, (SELECT COALESCE(max(position), -1) + 1 FROM empleados_weekly WHERE employee_id = ? AND weekday = ?))
        RETURNING id
        """.trimIndent(),
        listOf(employeeId, weekday, start, end, siteId, employeeId, weekday),
    ) { it.uuid("id") ?: NilId }.first()

    fun deleteWeeklySlot(tenant: Connection, id: UUID): Boolean = tenant.update("DELETE FROM empleados_weekly WHERE id = ?", listOf(id)) > 0

    /** Reemplaza todo el horario habitual del empleado (sede de la ficha en todas las franjas). */
    fun replaceWeekly(tenant: Connection, employeeId: UUID, slots: List<Triple<Int, LocalTime, LocalTime>>) = transaction(tenant) {
        lockEmployee(tenant, employeeId)
        tenant.update("DELETE FROM empleados_weekly WHERE employee_id = ?", listOf(employeeId))
        slots.forEachIndexed { index, (weekday, start, end) ->
            tenant.update(
                "INSERT INTO empleados_weekly (employee_id, weekday, start_time, end_time, site_id, position) VALUES (?, ?, ?, ?, NULL, ?)",
                listOf(employeeId, weekday, start, end, index),
            )
        }
    }

    // ---- turnos ----

    private fun shift(rows: ResultSet) = Shift(
        id = rows.uuid("id") ?: NilId,
        employeeId = rows.uuid("employee_id") ?: NilId,
        date = rows.date("shift_date") ?: LocalDate.EPOCH,
        kind = ShiftKind.fromCode(rows.getString("kind")) ?: ShiftKind.Work,
        start = rows.time("start_time"),
        end = rows.time("end_time"),
        siteId = rows.uuid("site_id"),
        note = rows.getString("note"),
    )

    private const val ShiftColumns = "id, employee_id, shift_date, kind, start_time, end_time, site_id, note"

    /** Turnos de los empleados (null: todos los activos) entre [from] y [to] incluidos. */
    fun shifts(tenant: Connection, employeeIds: Collection<UUID>?, from: LocalDate, to: LocalDate): List<Shift> {
        val order = "ORDER BY shift_date, start_time NULLS LAST, id"
        return if (employeeIds == null) {
            tenant.query(
                "SELECT $ShiftColumns FROM empleados_shifts WHERE shift_date BETWEEN ? AND ? AND employee_id IN (SELECT id FROM empleados_employees WHERE status = 'active') $order",
                listOf(from, to), ::shift,
            )
        } else {
            if (employeeIds.isEmpty()) return emptyList()
            tenant.query(
                "SELECT $ShiftColumns FROM empleados_shifts WHERE shift_date BETWEEN ? AND ? AND employee_id = ANY(?) $order",
                listOf(from, to, tenant.uuidArray(employeeIds.distinct())), ::shift,
            )
        }
    }

    fun getShift(tenant: Connection, id: UUID): Shift? =
        tenant.query("SELECT $ShiftColumns FROM empleados_shifts WHERE id = ?", listOf(id), ::shift).firstOrNull()

    fun shiftsOfDay(tenant: Connection, employeeId: UUID, date: LocalDate): List<Shift> =
        tenant.query("SELECT $ShiftColumns FROM empleados_shifts WHERE employee_id = ? AND shift_date = ? ORDER BY start_time NULLS LAST, id", listOf(employeeId, date), ::shift)

    fun insertShift(tenant: Connection, input: ShiftInput, actorId: UUID?): UUID = tenant.query(
        """
        INSERT INTO empleados_shifts (employee_id, shift_date, kind, start_time, end_time, site_id, note, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """.trimIndent(),
        listOf(
            input.employeeId, input.date, input.kind.code,
            input.start.takeIf { input.kind == ShiftKind.Work }, input.end.takeIf { input.kind == ShiftKind.Work },
            input.siteId, input.note.trim(), actorId,
        ),
    ) { it.uuid("id") ?: NilId }.first()

    fun updateShift(tenant: Connection, shift: Shift): Boolean = tenant.update(
        "UPDATE empleados_shifts SET shift_date = ?, start_time = ?, end_time = ?, site_id = ?, note = ?, updated_at = now() WHERE id = ?",
        listOf(shift.date, shift.start, shift.end, shift.siteId, shift.note, shift.id),
    ) > 0

    fun deleteShift(tenant: Connection, id: UUID): Boolean = tenant.update("DELETE FROM empleados_shifts WHERE id = ?", listOf(id)) > 0

    fun deleteShiftsOfDay(tenant: Connection, employeeId: UUID, date: LocalDate): Int =
        tenant.update("DELETE FROM empleados_shifts WHERE employee_id = ? AND shift_date = ?", listOf(employeeId, date))

    fun deleteOffShiftsOfDay(tenant: Connection, employeeId: UUID, date: LocalDate): Int =
        tenant.update("DELETE FROM empleados_shifts WHERE employee_id = ? AND shift_date = ? AND kind = 'off'", listOf(employeeId, date))

    // ---- ausencias ----

    private fun absence(rows: ResultSet) = Absence(
        id = rows.uuid("id") ?: NilId,
        employeeId = rows.uuid("employee_id") ?: NilId,
        kind = AbsenceKind.fromCode(rows.getString("kind")) ?: AbsenceKind.Other,
        startDate = rows.date("start_date") ?: LocalDate.EPOCH,
        endDate = rows.date("end_date") ?: LocalDate.EPOCH,
        status = AbsenceStatus.fromCode(rows.getString("status")) ?: AbsenceStatus.Pending,
        reason = rows.getString("reason"),
        decisionNote = rows.getString("decision_note"),
        requestedBy = rows.uuid("requested_by"),
        decidedBy = rows.uuid("decided_by"),
        decidedAt = rows.instant("decided_at"),
        createdAt = rows.instant("created_at") ?: Instant.EPOCH,
    )

    private const val AbsenceColumns = "a.id, a.employee_id, a.kind, a.start_date, a.end_date, a.status, a.reason, a.decision_note, a.requested_by, a.decided_by, a.decided_at, a.created_at"

    private const val RedactedAbsenceColumns =
        "a.id, a.employee_id, a.kind, a.start_date, a.end_date, a.status, " +
            "CASE WHEN a.kind <> 'sick' OR ? OR a.employee_id = ? THEN a.reason ELSE '' END AS reason, " +
            "CASE WHEN a.kind <> 'sick' OR ? OR a.employee_id = ? THEN a.decision_note ELSE '' END AS decision_note, " +
            "a.requested_by, a.decided_by, a.decided_at, a.created_at"

    /** Lectura interna para el servicio (no se muestra tal cual): el motivo y la nota llegan completos. */
    fun getAbsence(tenant: Connection, id: UUID): Absence? =
        tenant.query("SELECT $AbsenceColumns FROM empleados_absences a WHERE a.id = ?", listOf(id), ::absence).firstOrNull()

    /**
     * Ausencias con el nombre del empleado, las mas proximas primero. [employeeId] limita a un empleado (quien no gestiona
     * turnos solo pide las suyas); [from] y [to] piden las que tocan ese rango de fechas.
     */
    fun listAbsences(
        tenant: Connection,
        scope: EmpleadosScope,
        employeeId: UUID?,
        statuses: Collection<AbsenceStatus>?,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int = 200,
        newestFirst: Boolean = false,
    ): List<AbsenceRow> {
        val where = mutableListOf("TRUE")
        // Dos pares de parametros del SELECT (motivo y nota): el motivo y la nota de una ausencia por enfermedad solo salen
        // de la base de datos para quien administra fichas y para el propio empleado; a los demas llegan vacios.
        val myId = scope.myEmployeeId ?: NilId
        val params = mutableListOf<Any?>(scope.canManage, myId, scope.canManage, myId)
        employeeId?.let {
            where += "a.employee_id = ?"
            params += it
        }
        if (statuses != null) {
            if (statuses.isEmpty()) return emptyList()
            where += "a.status = ANY(?)"
            params += tenant.createArrayOf("text", statuses.map { it.code }.toTypedArray())
        }
        to?.let {
            where += "a.start_date <= ?"
            params += it
        }
        from?.let {
            where += "a.end_date >= ?"
            params += it
        }
        params += limit.coerceIn(1, MaxListed)
        val order = if (newestFirst) "a.start_date DESC, a.created_at DESC" else "a.start_date, a.created_at"
        return tenant.query(
            "SELECT $RedactedAbsenceColumns, e.full_name FROM empleados_absences a JOIN empleados_employees e ON e.id = a.employee_id WHERE ${where.joinToString(" AND ")} ORDER BY $order, a.id LIMIT ?",
            params,
        ) { AbsenceRow(absence(it), it.getString("full_name")) }
    }

    /** Si el empleado ya tiene una ausencia pendiente o aprobada que toca esas fechas (menos [exceptId]). */
    fun hasOverlappingAbsence(tenant: Connection, employeeId: UUID, from: LocalDate, to: LocalDate, exceptId: UUID? = null): Boolean = tenant.query(
        """
        SELECT 1 FROM empleados_absences
        WHERE employee_id = ? AND status IN ('pending', 'approved') AND start_date <= ? AND end_date >= ? AND id <> ?
        LIMIT 1
        """.trimIndent(),
        listOf(employeeId, to, from, exceptId ?: NilId),
    ) { 1 }.isNotEmpty()

    fun insertAbsence(tenant: Connection, input: AbsenceInput, status: AbsenceStatus, actorId: UUID?): UUID = tenant.query(
        """
        INSERT INTO empleados_absences (employee_id, kind, start_date, end_date, status, reason, requested_by, decided_by, decided_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """.trimIndent(),
        listOf(
            input.employeeId, input.kind.code, input.startDate, input.endDate, status.code, input.reason.trim(), actorId,
            actorId.takeIf { status == AbsenceStatus.Approved },
            if (status == AbsenceStatus.Approved) OffsetDateTime.now() else null,
        ),
    ) { it.uuid("id") ?: NilId }.first()

    /** Pasa una ausencia pendiente a [status]; false si ya no estaba pendiente (otra persona se adelanto). */
    fun decideAbsence(tenant: Connection, id: UUID, status: AbsenceStatus, decidedBy: UUID?, note: String): Boolean = tenant.update(
        "UPDATE empleados_absences SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?, updated_at = now() WHERE id = ? AND status = 'pending'",
        listOf(status.code, decidedBy, note.trim(), id),
    ) > 0

    /** El empleado cancela su propia solicitud pendiente. */
    fun cancelAbsence(tenant: Connection, id: UUID): Boolean = tenant.update(
        "UPDATE empleados_absences SET status = 'cancelled', updated_at = now() WHERE id = ? AND status = 'pending'",
        listOf(id),
    ) > 0

    // ---- calculo para un rango ----

    /** Carga horario habitual, turnos y ausencias aprobadas de los empleados (null: todos los activos) entre [from] y [to]. */
    fun scheduleData(tenant: Connection, employeeIds: Collection<UUID>?, from: LocalDate, to: LocalDate): ScheduleData {
        // Una ausencia dura como mucho 366 dias: el limite inferior de start_date deja usar el indice de aprobadas.
        val earliestStart = from.minusDays(366)
        val approved = if (employeeIds == null) {
            tenant.query(
                "SELECT $AbsenceColumns FROM empleados_absences a WHERE a.status = 'approved' AND a.start_date <= ? AND a.start_date >= ? AND a.end_date >= ? AND a.employee_id IN (SELECT id FROM empleados_employees WHERE status = 'active')",
                listOf(to, earliestStart, from), ::absence,
            )
        } else if (employeeIds.isEmpty()) {
            emptyList()
        } else {
            tenant.query(
                "SELECT $AbsenceColumns FROM empleados_absences a WHERE a.status = 'approved' AND a.start_date <= ? AND a.start_date >= ? AND a.end_date >= ? AND a.employee_id = ANY(?)",
                listOf(to, earliestStart, from, tenant.uuidArray(employeeIds.distinct())), ::absence,
            )
        }
        return ScheduleData(
            weekly = weekly(tenant, employeeIds).groupBy { it.employeeId },
            shifts = shifts(tenant, employeeIds, from, to).groupBy { it.employeeId },
            absences = approved.groupBy { it.employeeId },
        )
    }

    /** El horario vigente de un empleado en una fecha (para los avisos de cambio). */
    fun daySchedule(tenant: Connection, employeeId: UUID, date: LocalDate): Pair<DaySchedule, UUID?> {
        val siteId = tenant.query("SELECT site_id FROM empleados_employees WHERE id = ?", listOf(employeeId)) { it.uuid("site_id") }.firstOrNull()
        return scheduleData(tenant, listOf(employeeId), date, date).resolve(employeeId, date, siteId) to siteId
    }
}
