package com.ideasdeveloper.idc.modules.propietarios

import com.ideasdeveloper.idc.server.modules.FileTooLargeException
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.server.modules.StoredFileRef
import com.ideasdeveloper.idc.ui.UiFileRef
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Acceso a datos del modulo. Cada funcion es sincrona y recibe la conexion de la empresa: quien llama las ejecuta dentro de
 * withContext(Dispatchers.IO). Todo el SQL va parametrizado; las listas usan `= ANY(?)` con un arreglo uuid.
 */
object PropietariosRepository {
    const val MaxFileBytes = 25L * 1024 * 1024
    const val MaxFilesPerLot = 100
    const val MaxPendingFiles = 20
    const val MaxPendingBytes = 200L * 1024 * 1024
    const val MaxListed = 500
    private val log = LoggerFactory.getLogger(PropietariosRepository::class.java)
    private val NilId = UUID(0L, 0L)

    // Un cliente visible: creado en esta empresa o compartido con ella, y no archivado.
    private const val VisibleCustomer = "(c.origin_company_code = '' OR c.shared) AND c.status <> 'archived'"

    // Para mostrar titularidades basta que no sea de otra empresa sin compartir: un titular archivado sigue en el historial.
    private const val ReadableCustomer = "(c.origin_company_code = '' OR c.shared)"

    /** Hoy en la zona horaria del servidor. */
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

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun ResultSet.date(column: String): LocalDate? = getObject(column, LocalDate::class.java)

    private fun ResultSet.decimal(column: String): BigDecimal? = getBigDecimal(column)

    /** Escapa los comodines de LIKE para que lo que escribe el usuario se busque tal cual. */
    fun like(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun isUniqueViolation(failure: SQLException): Boolean = failure.sqlState == "23505"

    // ---- desarrollos ----

    private const val DevelopmentColumns = "d.id, d.name, d.location, d.map_url, d.latitude, d.longitude, d.notes, d.archived_at"

    private fun development(rows: ResultSet) = Development(
        rows.uuid("id") ?: NilId, rows.getString("name"), rows.getString("location"), rows.getString("map_url"),
        rows.decimal("latitude"), rows.decimal("longitude"), rows.getString("notes"), rows.getObject("archived_at") != null,
    )

    /** Desarrollos sin archivar con sus metricas (lotes, area y titulares vigentes), por nombre. */
    fun listDevelopments(tenant: Connection, today: LocalDate): List<DevelopmentSummary> = tenant.query(
        """
        SELECT $DevelopmentColumns, COALESCE(s.lots, 0) AS lots, COALESCE(s.area, 0) AS area, COALESCE(w.owners, 0) AS owners
        FROM propietarios_developments d
        LEFT JOIN (
            SELECT development_id, count(*) AS lots, COALESCE(sum(area_m2), 0) AS area
            FROM propietarios_lots WHERE archived_at IS NULL GROUP BY development_id
        ) s ON s.development_id = d.id
        LEFT JOIN (
            SELECT l.development_id, count(DISTINCT o.customer_id) AS owners
            FROM propietarios_ownerships o JOIN propietarios_lots l ON l.id = o.lot_id
            WHERE l.archived_at IS NULL AND (o.ends_on IS NULL OR o.ends_on > ?)
            GROUP BY l.development_id
        ) w ON w.development_id = d.id
        WHERE d.archived_at IS NULL
        ORDER BY lower(d.name), d.id
        LIMIT ?
        """.trimIndent(),
        listOf(today, MaxListed),
    ) { DevelopmentSummary(development(it), it.getInt("lots"), it.decimal("area") ?: BigDecimal.ZERO, it.getInt("owners")) }

    fun getDevelopment(tenant: Connection, id: UUID): Development? =
        tenant.query("SELECT $DevelopmentColumns FROM propietarios_developments d WHERE d.id = ?", listOf(id)) { development(it) }.firstOrNull()

    fun findDevelopmentByName(tenant: Connection, name: String): Development? = tenant.query(
        "SELECT $DevelopmentColumns FROM propietarios_developments d WHERE lower(d.name) = lower(?) AND d.archived_at IS NULL ORDER BY d.created_at, d.id LIMIT 1",
        listOf(name.trim()),
    ) { development(it) }.firstOrNull()

    fun createDevelopment(tenant: Connection, input: DevelopmentInput, actorId: UUID?): UUID {
        check(input)
        val id = UUID.randomUUID()
        try {
            tenant.update(
                "INSERT INTO propietarios_developments (id, name, location, map_url, latitude, longitude, notes, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(id, input.name.trim(), input.location.trim(), input.mapUrl.trim(), input.latitude, input.longitude, input.notes.trim(), actorId),
            )
        } catch (failure: SQLException) {
            if (isUniqueViolation(failure)) throw PropietariosValidationException(DuplicateDevelopment)
            throw failure
        }
        return id
    }

    private const val DuplicateDevelopment = "Ya existe un desarrollo con ese nombre."

    fun updateDevelopment(tenant: Connection, id: UUID, input: DevelopmentInput): Boolean {
        check(input)
        try {
            return tenant.update(
                """
                UPDATE propietarios_developments
                SET name = ?, location = ?, map_url = ?, latitude = ?, longitude = ?, notes = ?, updated_at = now()
                WHERE id = ? AND archived_at IS NULL
                """.trimIndent(),
                listOf(input.name.trim(), input.location.trim(), input.mapUrl.trim(), input.latitude, input.longitude, input.notes.trim(), id),
            ) > 0
        } catch (failure: SQLException) {
            if (isUniqueViolation(failure)) throw PropietariosValidationException(DuplicateDevelopment)
            throw failure
        }
    }

    /** Archiva el desarrollo y sus lotes (los lotes archivados dejan de salir en listas y busquedas). Bloquea los lotes antes de archivar. */
    fun archiveDevelopment(tenant: Connection, id: UUID): Boolean = transaction(tenant) {
        tenant.query("SELECT id FROM propietarios_lots WHERE development_id = ? ORDER BY id FOR UPDATE", listOf(id)) { it.getInt(1) }
        val archived = tenant.update("UPDATE propietarios_developments SET archived_at = now(), updated_at = now() WHERE id = ? AND archived_at IS NULL", listOf(id)) > 0
        if (archived) tenant.update("UPDATE propietarios_lots SET archived_at = now(), updated_at = now() WHERE development_id = ? AND archived_at IS NULL", listOf(id))
        archived
    }

    private fun check(input: DevelopmentInput) {
        val problems = input.validate()
        if (problems.isNotEmpty()) throw PropietariosValidationException(problems.joinToString(" "))
    }

    /** Lotes y area por estado de un desarrollo (solo lotes sin archivar). */
    fun statusTotals(tenant: Connection, developmentId: UUID): Map<LotStatus, StatusTotals> {
        val found = tenant.query(
            "SELECT status, count(*) AS total, COALESCE(sum(area_m2), 0) AS area FROM propietarios_lots WHERE development_id = ? AND archived_at IS NULL GROUP BY status",
            listOf(developmentId),
        ) { Triple(it.getString("status"), it.getInt("total"), it.decimal("area") ?: BigDecimal.ZERO) }
        return LotStatus.entries.associateWith { status ->
            found.firstOrNull { it.first == status.code }?.let { StatusTotals(it.second, it.third) } ?: StatusTotals(0, BigDecimal.ZERO)
        }
    }

    // ---- lotes ----

    private const val LotSelect = """
        SELECT l.id, l.development_id, d.name AS development_name, l.lot_number, l.register_number, l.property_name, l.property_type, l.status,
               l.area_m2, l.latitude, l.longitude, l.map_url, l.notes, l.archived_at
        FROM propietarios_lots l JOIN propietarios_developments d ON d.id = l.development_id
    """

    private fun lot(rows: ResultSet) = Lot(
        id = rows.uuid("id") ?: NilId,
        developmentId = rows.uuid("development_id") ?: NilId,
        developmentName = rows.getString("development_name"),
        lotNumber = rows.getString("lot_number"),
        registerNumber = rows.getString("register_number"),
        propertyName = rows.getString("property_name"),
        propertyType = rows.getString("property_type"),
        status = LotStatus.fromCode(rows.getString("status")) ?: LotStatus.Disponible,
        areaM2 = rows.decimal("area_m2"),
        latitude = rows.decimal("latitude"),
        longitude = rows.decimal("longitude"),
        mapUrl = rows.getString("map_url"),
        notes = rows.getString("notes"),
        archived = rows.getObject("archived_at") != null,
    )

    /** Un lote por id (tambien archivado), o null. */
    fun getLot(tenant: Connection, id: UUID): Lot? = tenant.query("$LotSelect WHERE l.id = ?", listOf(id)) { lot(it) }.firstOrNull()

    /**
     * Lotes sin archivar de desarrollos sin archivar. El texto busca en el numero de lote, el numero de registro y el nombre de
     * la propiedad (sin distinguir mayusculas); los que coinciden exactamente con el numero van primero.
     */
    fun listLots(tenant: Connection, filter: LotFilter): List<Lot> {
        val params = mutableListOf<Any?>()
        val where = StringBuilder("l.archived_at IS NULL AND d.archived_at IS NULL")
        filter.developmentId?.let { where.append(" AND l.development_id = ?"); params += it }
        filter.status?.let { where.append(" AND l.status = ?"); params += it.code }
        val text = filter.query?.trim()?.lowercase()?.take(200)?.takeIf { it.isNotEmpty() }
        val order = StringBuilder("lower(d.name), length(l.lot_number), lower(l.lot_number), l.id")
        val orderParams = mutableListOf<Any?>()
        if (text != null) {
            val pattern = "%${like(text)}%"
            where.append(" AND (lower(l.lot_number) LIKE ? OR lower(l.register_number) LIKE ? OR lower(l.property_name) LIKE ?)")
            params += pattern; params += pattern; params += pattern
            order.insert(0, "(lower(l.lot_number) = ? OR lower(l.register_number) = ?) DESC, ")
            orderParams += text; orderParams += text
        }
        params.addAll(orderParams)
        params += filter.limit.coerceIn(1, MaxListed)
        return tenant.query("$LotSelect WHERE $where ORDER BY $order LIMIT ?", params) { lot(it) }
    }

    fun createLot(tenant: Connection, input: LotInput, actorId: UUID?, importKey: String? = null): UUID {
        checkLot(input)
        val id = UUID.randomUUID()
        try {
            tenant.update(
                """
                INSERT INTO propietarios_lots (id, development_id, lot_number, register_number, property_name, property_type, status, area_m2,
                    latitude, longitude, map_url, notes, created_by, import_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                listOf(
                    id, input.developmentId, input.lotNumber.trim(), input.registerNumber.trim(), input.propertyName.trim(), input.propertyType.trim(),
                    input.status.code, input.areaM2, input.latitude, input.longitude, input.mapUrl.trim(), input.notes.trim(), actorId, importKey,
                ),
            )
        } catch (failure: SQLException) {
            if (isUniqueViolation(failure)) throw PropietariosValidationException(DuplicateLot)
            throw failure
        }
        return id
    }

    fun updateLot(tenant: Connection, id: UUID, input: LotInput): Boolean {
        checkLot(input)
        return try {
            tenant.update(
                """
                UPDATE propietarios_lots
                SET lot_number = ?, register_number = ?, property_name = ?, property_type = ?, status = ?, area_m2 = ?,
                    latitude = ?, longitude = ?, map_url = ?, notes = ?, updated_at = now()
                WHERE id = ? AND archived_at IS NULL
                """.trimIndent(),
                listOf(
                    input.lotNumber.trim(), input.registerNumber.trim(), input.propertyName.trim(), input.propertyType.trim(), input.status.code,
                    input.areaM2, input.latitude, input.longitude, input.mapUrl.trim(), input.notes.trim(), id,
                ),
            ) > 0
        } catch (failure: SQLException) {
            if (isUniqueViolation(failure)) throw PropietariosValidationException(DuplicateLot)
            throw failure
        }
    }

    fun archiveLot(tenant: Connection, id: UUID): Boolean = transaction(tenant) {
        // Con el lote bloqueado: un cambio de titularidad en curso termina antes de archivarlo.
        if (lockLot(tenant, id) == null) {
            false
        } else {
            tenant.update("UPDATE propietarios_lots SET archived_at = now(), updated_at = now() WHERE id = ? AND archived_at IS NULL", listOf(id)) > 0
        }
    }

    private const val DuplicateLot = "Ya existe un lote con ese número en el desarrollo."

    private fun checkLot(input: LotInput) {
        val problems = input.validate()
        if (problems.isNotEmpty()) throw PropietariosValidationException(problems.joinToString(" "))
    }

    /** Bloquea el lote para serializar los cambios de titularidad; devuelve su estado, o null si no existe o esta archivado. */
    fun lockLot(tenant: Connection, lotId: UUID): LotStatus? = tenant.query(
        "SELECT status FROM propietarios_lots WHERE id = ? AND archived_at IS NULL FOR UPDATE",
        listOf(lotId),
    ) { LotStatus.fromCode(it.getString("status")) ?: LotStatus.Disponible }.firstOrNull()

    fun setLotStatus(tenant: Connection, lotId: UUID, status: LotStatus) {
        tenant.update("UPDATE propietarios_lots SET status = ?, updated_at = now() WHERE id = ?", listOf(status.code, lotId))
    }

    // ---- titularidades ----

    private const val OwnershipSelect = """
        SELECT o.id, o.lot_id, o.customer_id,
               CASE WHEN $ReadableCustomer THEN c.display_name ELSE 'Cliente no disponible' END AS display_name,
               CASE WHEN $ReadableCustomer THEN c.primary_email ELSE '' END AS primary_email,
               o.share_percent, o.starts_on, o.ends_on, o.notes, o.created_at, o.ended_at,
               (o.ends_on IS NULL OR o.ends_on > ?) AS is_current
        FROM propietarios_ownerships o JOIN customers c ON c.id = o.customer_id
    """

    private fun ownership(rows: ResultSet) = Ownership(
        id = rows.uuid("id") ?: NilId,
        lotId = rows.uuid("lot_id") ?: NilId,
        customerId = rows.uuid("customer_id") ?: NilId,
        customerName = rows.getString("display_name"),
        customerEmail = rows.getString("primary_email").orEmpty(),
        sharePercent = rows.decimal("share_percent"),
        startsOn = rows.date("starts_on"),
        endsOn = rows.date("ends_on"),
        notes = rows.getString("notes"),
        createdAt = rows.instant("created_at") ?: Instant.EPOCH,
        endedAt = rows.instant("ended_at"),
        current = rows.getBoolean("is_current"),
    )

    /** Titularidades de un lote (las vigentes primero) de clientes que el usuario puede leer. */
    fun listOwnerships(tenant: Connection, lotId: UUID, today: LocalDate): List<Ownership> = tenant.query(
        "$OwnershipSelect WHERE o.lot_id = ? AND $ReadableCustomer ORDER BY is_current DESC, o.ends_on DESC NULLS FIRST, lower(c.display_name), o.id",
        listOf(today, lotId),
    ) { ownership(it) }

    /** Todas las titularidades abiertas de un lote en [date] (sin fecha de fin o con una posterior), de cualquier cliente: un cambio debe cerrarlas todas. */
    fun openOwnerships(tenant: Connection, lotId: UUID, date: LocalDate): List<Ownership> = tenant.query(
        "$OwnershipSelect WHERE o.lot_id = ? AND (o.ends_on IS NULL OR o.ends_on > ?) ORDER BY lower(c.display_name), o.id",
        listOf(date, lotId, date),
    ) { ownership(it) }

    fun getOwnership(tenant: Connection, id: UUID, today: LocalDate, visibleOnly: Boolean = true): Ownership? =
        tenant.query("$OwnershipSelect WHERE o.id = ?" + if (visibleOnly) " AND $ReadableCustomer" else "", listOf(today, id)) { ownership(it) }.firstOrNull()

    /** Solo el lote de una titularidad (sin leer al cliente), para bloquear el lote antes de releer la titularidad. */
    fun ownershipLotId(tenant: Connection, id: UUID): UUID? =
        tenant.query("SELECT lot_id FROM propietarios_ownerships WHERE id = ?", listOf(id)) { it.uuid("lot_id") }.firstOrNull()

    /** Cierra titularidades: fecha de fin y momento de cierre. Devuelve cuantas cerro. */
    fun closeOwnerships(tenant: Connection, ids: Collection<UUID>, date: LocalDate): Int {
        if (ids.isEmpty()) return 0
        return tenant.update(
            "UPDATE propietarios_ownerships SET ends_on = ?, ended_at = now() WHERE id = ANY(?)",
            listOf(date, tenant.uuidArray(ids.distinct())),
        )
    }

    fun insertOwnership(tenant: Connection, lotId: UUID, customerId: UUID, share: BigDecimal?, startsOn: LocalDate?, notes: String, actorId: UUID?): UUID {
        val id = UUID.randomUUID()
        try {
            tenant.update(
                "INSERT INTO propietarios_ownerships (id, lot_id, customer_id, share_percent, starts_on, notes, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                listOf(id, lotId, customerId, share, startsOn, PropietariosRules.clip(notes.trim(), PropietariosRules.MaxNotes), actorId),
            )
        } catch (failure: SQLException) {
            if (isUniqueViolation(failure)) throw PropietariosValidationException("Ese cliente ya es titular vigente del lote.")
            throw failure
        }
        return id
    }

    /** Cambia porcentaje y fechas de una titularidad; con fecha de fin queda cerrada, sin ella vuelve a estar abierta. */
    fun updateOwnership(tenant: Connection, id: UUID, share: BigDecimal?, startsOn: LocalDate?, endsOn: LocalDate?, notes: String): Boolean {
        try {
            return tenant.update(
                """
                UPDATE propietarios_ownerships
                SET share_percent = ?, starts_on = ?, ends_on = ?, notes = ?,
                    ended_at = CASE WHEN ?::date IS NULL THEN NULL ELSE COALESCE(ended_at, now()) END
                WHERE id = ?
                """.trimIndent(),
                listOf(share, startsOn, endsOn, PropietariosRules.clip(notes.trim(), PropietariosRules.MaxNotes), endsOn, id),
            ) > 0
        } catch (failure: SQLException) {
            if (isUniqueViolation(failure)) throw PropietariosValidationException("Ese cliente ya es titular vigente del lote.")
            throw failure
        }
    }

    /** Suma de los porcentajes de los titulares vigentes de un lote, sin contar [exceptOwnershipId]. */
    fun assignedShare(tenant: Connection, lotId: UUID, today: LocalDate, exceptOwnershipId: UUID? = null): BigDecimal = tenant.query(
        """
        SELECT COALESCE(sum(share_percent), 0) AS total FROM propietarios_ownerships
        WHERE lot_id = ? AND (ends_on IS NULL OR ends_on > ?) AND id IS DISTINCT FROM ?
        """.trimIndent(),
        listOf(lotId, today, exceptOwnershipId),
    ) { it.decimal("total") ?: BigDecimal.ZERO }.firstOrNull() ?: BigDecimal.ZERO

    /** Lotes (sin archivar) con titulares vigentes de un cliente y los que tuvo antes, de los vigentes a los pasados. */
    fun ownedLots(tenant: Connection, customerId: UUID, today: LocalDate): List<OwnedLot> = tenant.query(
        """
        SELECT o.id, o.lot_id, o.customer_id, c.display_name, c.primary_email, o.share_percent, o.starts_on, o.ends_on, o.notes, o.created_at, o.ended_at,
               (o.ends_on IS NULL OR o.ends_on > ?) AS is_current,
               l.id AS lid, l.development_id, d.name AS development_name, l.lot_number, l.register_number, l.property_name, l.property_type, l.status,
               l.area_m2, l.latitude, l.longitude, l.map_url, l.notes AS lot_notes, l.archived_at
        FROM propietarios_ownerships o
        JOIN customers c ON c.id = o.customer_id
        JOIN propietarios_lots l ON l.id = o.lot_id
        JOIN propietarios_developments d ON d.id = l.development_id
        WHERE o.customer_id = ? AND l.archived_at IS NULL AND d.archived_at IS NULL AND $ReadableCustomer
        ORDER BY is_current DESC, o.ends_on DESC NULLS FIRST, lower(d.name), length(l.lot_number), lower(l.lot_number), o.id
        LIMIT ?
        """.trimIndent(),
        listOf(today, customerId, MaxListed),
    ) { rows ->
        val owned = ownership(rows)
        val lotValue = Lot(
            id = rows.uuid("lid") ?: NilId,
            developmentId = rows.uuid("development_id") ?: NilId,
            developmentName = rows.getString("development_name"),
            lotNumber = rows.getString("lot_number"),
            registerNumber = rows.getString("register_number"),
            propertyName = rows.getString("property_name"),
            propertyType = rows.getString("property_type"),
            status = LotStatus.fromCode(rows.getString("status")) ?: LotStatus.Disponible,
            areaM2 = rows.decimal("area_m2"),
            latitude = rows.decimal("latitude"),
            longitude = rows.decimal("longitude"),
            mapUrl = rows.getString("map_url"),
            notes = rows.getString("lot_notes"),
            archived = false,
        )
        OwnedLot(owned, lotValue)
    }

    /** Clientes visibles con titularidades vigentes: cuantos lotes tienen y su area total; el texto busca en nombre y correo. */
    fun listOwners(tenant: Connection, query: String?, today: LocalDate, limit: Int = 200): List<OwnerSummary> {
        val params = mutableListOf<Any?>(today)
        val where = StringBuilder("(o.ends_on IS NULL OR o.ends_on > ?) AND l.archived_at IS NULL AND d.archived_at IS NULL AND $VisibleCustomer")
        val text = query?.trim()?.lowercase()?.take(200)?.takeIf { it.isNotEmpty() }
        if (text != null) {
            val pattern = "%${like(text)}%"
            where.append(" AND (lower(c.display_name) LIKE ? OR lower(c.primary_email) LIKE ?)")
            params += pattern; params += pattern
        }
        params += limit.coerceIn(1, MaxListed)
        return tenant.query(
            """
            SELECT c.id, c.display_name, c.primary_email, count(DISTINCT o.lot_id) AS lots, COALESCE(sum(l.area_m2), 0) AS area
            FROM propietarios_ownerships o
            JOIN customers c ON c.id = o.customer_id
            JOIN propietarios_lots l ON l.id = o.lot_id
            JOIN propietarios_developments d ON d.id = l.development_id
            WHERE $where
            GROUP BY c.id, c.display_name, c.primary_email
            ORDER BY lower(c.display_name), c.id
            LIMIT ?
            """.trimIndent(),
            params,
        ) { OwnerSummary(it.uuid("id") ?: NilId, it.getString("display_name"), it.getString("primary_email").orEmpty(), it.getInt("lots"), it.decimal("area") ?: BigDecimal.ZERO) }
    }

    // ---- historial ----

    fun addHistory(tenant: Connection, lotId: UUID, kind: String, happenedOn: LocalDate, detail: String, author: String) {
        tenant.update(
            "INSERT INTO propietarios_lot_history (id, lot_id, kind, happened_on, detail, author) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(UUID.randomUUID(), lotId, kind, happenedOn, PropietariosRules.clip(detail, PropietariosRules.MaxDetail), PropietariosRules.clip(author.trim(), 160)),
        )
    }

    fun listHistory(tenant: Connection, lotId: UUID, limit: Int = 50): List<LotHistoryEntry> = tenant.query(
        "SELECT id, kind, happened_on, detail, author, created_at FROM propietarios_lot_history WHERE lot_id = ? ORDER BY created_at DESC, id LIMIT ?",
        listOf(lotId, limit.coerceIn(1, 200)),
    ) { LotHistoryEntry(it.uuid("id") ?: NilId, it.getString("kind"), it.date("happened_on") ?: LocalDate.EPOCH, it.getString("detail"), it.getString("author"), it.instant("created_at") ?: Instant.EPOCH) }

    // ---- adjuntos ----

    class StoredAttachment(val attachment: LotAttachment, val open: () -> InputStream?)

    // Nombre sin carpetas ni caracteres de control; la carpeta va aparte, normalizada a a/b/c.
    fun cleanFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.trim().take(255)

    fun cleanFilePath(path: String): String = path.replace('\\', '/').split('/')
        .map { part -> part.filter { it >= ' ' }.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")
        .take(500)

    /**
     * Guarda una subida que espera a que se guarde su formulario (lot_id NULL) y devuelve la referencia que el formulario conserva.
     * El contenido va primero a [files]; si la fila no se puede guardar se borra el archivo almacenado.
     */
    fun upload(tenant: Connection, files: ModuleFiles, name: String, path: String, contentType: String, content: InputStream, uploadedBy: UUID?): UiFileRef {
        val fileName = cleanFileName(name)
        if (fileName.isEmpty()) throw PropietariosValidationException("El archivo no tiene nombre.")
        val type = contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" }
        // Las subidas que esperan su formulario se limitan por usuario para que formularios abandonados no llenen el almacenamiento.
        val pending = tenant.query(
            "SELECT count(*) AS total, COALESCE(sum(size), 0) AS bytes FROM propietarios_attachments WHERE lot_id IS NULL AND NOT delete_pending AND uploaded_by IS NOT DISTINCT FROM ?",
            listOf(uploadedBy),
        ) { it.getInt("total") to it.getLong("bytes") }.firstOrNull() ?: (0 to 0L)
        if (pending.first >= MaxPendingFiles || pending.second >= MaxPendingBytes) {
            throw PropietariosValidationException("Tienes demasiados archivos sin guardar; guarda o espera.")
        }
        val stored = try {
            files.put(fileName, type, content, MaxFileBytes)
        } catch (_: FileTooLargeException) {
            throw PropietariosValidationException("$fileName supera el máximo de ${MaxFileBytes / (1024 * 1024)} MB.")
        }
        val ref = UiFileRef(UUID.randomUUID().toString(), fileName, cleanFilePath(path), stored.size)
        try {
            tenant.update(
                "INSERT INTO propietarios_attachments (id, name, path, content_type, size, backend, ref, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(UUID.fromString(ref.id), ref.name, ref.path, type, ref.size, stored.backend, stored.ref, uploadedBy),
            )
        } catch (failure: Exception) {
            runCatching { files.delete(stored) }
            throw failure
        }
        return ref
    }

    /** Une las subidas pendientes de [uploaderId] al lote y devuelve sus nombres. Las de otra persona, o ya unidas, se ignoran; sin usuario no se une nada. */
    fun attach(tenant: Connection, lotId: UUID, ids: Collection<UUID>, uploaderId: UUID?): List<String> {
        val pending = ids.distinct()
        if (pending.isEmpty() || uploaderId == null) return emptyList()
        return transaction(tenant) {
            tenant.query("SELECT 1 AS found FROM propietarios_lots WHERE id = ? FOR UPDATE", listOf(lotId)) { it.getInt("found") }
            val total = tenant.query("SELECT count(*) AS total FROM propietarios_attachments WHERE lot_id = ? AND NOT delete_pending", listOf(lotId)) { it.getInt("total") }.firstOrNull() ?: 0
            if (total + pending.size > MaxFilesPerLot) throw PropietariosValidationException("Un lote admite hasta $MaxFilesPerLot archivos.")
            tenant.query(
                """
                UPDATE propietarios_attachments SET lot_id = ?
                WHERE lot_id IS NULL AND NOT delete_pending AND uploaded_by = ? AND id = ANY(?)
                RETURNING name
                """.trimIndent(),
                listOf(lotId, uploaderId, tenant.uuidArray(pending)),
            ) { it.getString("name") }
        }
    }

    fun listAttachments(tenant: Connection, lotId: UUID): List<LotAttachment> = tenant.query(
        "SELECT id, lot_id, name, path, content_type, size, uploaded_by, created_at FROM propietarios_attachments WHERE lot_id = ? AND NOT delete_pending ORDER BY path, name, id",
        listOf(lotId),
    ) { attachment(it) }

    private fun attachment(rows: ResultSet) = LotAttachment(
        rows.uuid("id") ?: NilId, rows.uuid("lot_id"), rows.getString("name"), rows.getString("path"), rows.getString("content_type"),
        rows.getLong("size"), rows.uuid("uploaded_by"), rows.instant("created_at") ?: Instant.EPOCH,
    )

    /** Un archivo que no esta marcado para borrar: de un lote, o una subida pendiente. */
    fun file(tenant: Connection, files: ModuleFiles, id: UUID): StoredAttachment? {
        class Row(val attachment: LotAttachment, val backend: String, val ref: String)
        val row = tenant.query(
            "SELECT id, lot_id, name, path, content_type, size, uploaded_by, created_at, backend, ref FROM propietarios_attachments WHERE id = ? AND NOT delete_pending",
            listOf(id),
        ) { Row(attachment(it), it.getString("backend"), it.getString("ref")) }.firstOrNull() ?: return null
        val stored = StoredFileRef(row.backend, row.ref, row.attachment.size)
        return StoredAttachment(row.attachment) { files.open(stored) }
    }

    /** Marca archivos del lote para borrar; el trabajo de limpieza borra el contenido despues. */
    fun markDeleted(tenant: Connection, lotId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return tenant.update("UPDATE propietarios_attachments SET delete_pending = TRUE WHERE lot_id = ? AND id = ANY(?)", listOf(lotId, tenant.uuidArray(ids.distinct())))
    }

    /** Borra el contenido y las filas de adjuntos quitados y de subidas cuyo formulario no se guardo en un dia. */
    fun cleanup(tenant: Connection, files: ModuleFiles, batch: Int = 200) {
        class Doomed(val id: UUID, val stored: StoredFileRef)
        val doomed = tenant.query(
            """
            SELECT id, backend, ref, size FROM propietarios_attachments
            WHERE delete_pending OR (lot_id IS NULL AND created_at < now() - INTERVAL '24 hours')
            ORDER BY created_at
            LIMIT ?
            """.trimIndent(),
            listOf(batch.coerceIn(1, 1000)),
        ) { Doomed(it.uuid("id") ?: NilId, StoredFileRef(it.getString("backend"), it.getString("ref"), it.getLong("size"))) }
        doomed.forEach { item ->
            try {
                files.delete(item.stored)
                tenant.update("DELETE FROM propietarios_attachments WHERE id = ?", listOf(item.id))
            } catch (failure: Exception) {
                // La fila se queda y se reintenta en la siguiente corrida.
                log.warn("No se pudo borrar el adjunto {} de {}", item.id, files.companyCode, failure)
            }
        }
    }

    // ---- clientes (el modulo guarda solo la referencia; los datos de contacto viven en Clientes) ----

    /** Los clientes de [ids] que el usuario puede ver (visibles y sin archivar), por id. */
    fun visibleCustomers(tenant: Connection, ids: Collection<UUID>): Map<UUID, CustomerRef> {
        if (ids.isEmpty()) return emptyMap()
        return tenant.query(
            "SELECT c.id, c.display_name, c.primary_email FROM customers c WHERE c.id = ANY(?) AND $VisibleCustomer",
            listOf(tenant.uuidArray(ids.distinct())),
        ) { CustomerRef(it.uuid("id") ?: NilId, it.getString("display_name"), it.getString("primary_email").orEmpty()) }.associateBy { it.id }
    }

    fun visibleCustomer(tenant: Connection, id: UUID): CustomerRef? = visibleCustomers(tenant, listOf(id))[id]

    /** Clientes visibles cuyo nombre o correo contiene [query] (2 caracteres como minimo); la ruta comprueba propietarios.manage y clientes.view. */
    fun customerSuggestions(tenant: Connection, query: String, limit: Int = 6): List<CustomerRef> {
        val text = query.trim().lowercase().take(200)
        if (text.length < 2) return emptyList()
        val pattern = "%${like(text)}%"
        return tenant.query(
            """
            SELECT c.id, c.display_name, c.primary_email FROM customers c
            WHERE $VisibleCustomer AND (lower(c.display_name) LIKE ? OR lower(c.primary_email) LIKE ?)
            ORDER BY c.display_name, c.id
            LIMIT ?
            """.trimIndent(),
            listOf(pattern, pattern, limit.coerceIn(1, 20)),
        ) { CustomerRef(it.uuid("id") ?: NilId, it.getString("display_name"), it.getString("primary_email").orEmpty()) }
    }
}
