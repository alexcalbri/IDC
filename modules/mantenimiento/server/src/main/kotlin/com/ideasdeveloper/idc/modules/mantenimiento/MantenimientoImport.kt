package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.FileTooLargeException
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiNode
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
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
 * Un lugar desde donde importar datos (por ejemplo Monday). Las extensiones del módulo lo implementan: Mantenimiento lista las activas
 * en la pantalla "Importar", ejecuta su trabajo pendiente en `import-runner` y ofrece sus herramientas a los agentes.
 */
interface MantenimientoImportSource {
    /** El id de la extensión, por ejemplo "mantenimiento_monday". */
    val sourceId: String

    /** Lo que ve el usuario, por ejemplo "Monday". */
    val title: String

    fun enabled(tenant: Connection, companyCode: String): Boolean

    /** El cuerpo de la pantalla de importación de esta fuente ([params] son los parámetros de la pantalla). */
    fun screen(context: MantenimientoImportContext, params: Map<String, String>): List<UiNode>

    /** Una operación de la pantalla (POST); [values] es lo que envió el formulario. */
    fun action(context: MantenimientoImportContext, op: String, values: Map<String, String>): MantenimientoImportReply

    /** Herramientas para agentes (permiso mantenimiento.import); Mantenimiento las suma a las suyas. */
    val mcpTools: List<McpTool> get() = emptyList()

    /** Avanza la lectura y la aplicación y devuelve las filas procesadas. Corto, idempotente, errores registrados. */
    fun runPending(context: MantenimientoImportJobContext, budget: Duration): Int
}

class MantenimientoImportContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val permissions: ModulePermissions,
    val author: String,
    val today: LocalDate,
)

class MantenimientoImportJobContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val files: ModuleFiles,
    val today: LocalDate,
)

/** La respuesta a una acción: el aviso que se muestra, si es un error y los parámetros de la pantalla siguiente. */
data class MantenimientoImportReply(val notice: String, val error: Boolean = false, val params: Map<String, String> = emptyMap())

object MantenimientoImportSources {
    private val log = LoggerFactory.getLogger(MantenimientoImportSources::class.java)

    @Volatile
    private var linked: List<MantenimientoImportSource> = emptyList()

    /** Se llama una vez al arrancar con las extensiones del módulo; se queda con las que son fuentes de importación. */
    fun link(extensions: List<ServerExtension>) {
        linked = extensions.filterIsInstance<MantenimientoImportSource>()
    }

    val all: List<MantenimientoImportSource> get() = linked

    /** Las fuentes que la empresa tiene activas. Una fuente que no responde cuenta como inactiva. */
    fun enabled(tenant: Connection, companyCode: String): List<MantenimientoImportSource> = all.filter { source ->
        runCatching { source.enabled(tenant, companyCode) }
            .onFailure { log.warn("No se pudo comprobar la fuente de importación {} ({}).", source.sourceId, it.javaClass.simpleName) }
            .getOrDefault(false)
    }

    fun find(id: String): MantenimientoImportSource? = all.firstOrNull { it.sourceId == id }

    /** Ejecuta las fuentes activas una tras otra, cada una con una parte igual de lo que queda de [budget]. */
    fun runPending(context: MantenimientoImportJobContext, budget: Duration): Int {
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

data class ImportedCheck(val key: String, val text: String, val done: Boolean = false)

data class ImportedNote(val key: String, val author: String, val at: Instant?, val body: String)

data class ImportedPlan(
    /** El id del elemento en la fuente (por ejemplo `monday:<itemId>`): es el import_key del plan. */
    val key: String,
    val title: String,
    val description: String = "",
    val propertyName: String? = null,
    val assetName: String? = null,
    val frequency: PlanFrequency,
    val intervalDays: Int? = null,
    val nextDue: LocalDate? = null,
    val priority: OrderPriority = OrderPriority.Medium,
    val assigneeEmail: String? = null,
    val assigneeName: String? = null,
    val checklist: List<ImportedCheck> = emptyList(),
)

data class ImportedOrder(
    /** El id del elemento en la fuente (por ejemplo `monday:<itemId>`): es el import_key de la orden. */
    val key: String,
    val kind: OrderKind,
    val status: OrderStatus,
    val title: String,
    val description: String = "",
    val propertyName: String? = null,
    val assetName: String? = null,
    val department: String = "",
    val priority: OrderPriority = OrderPriority.Medium,
    val dueDate: LocalDate? = null,
    val assigneeEmail: String? = null,
    val assigneeName: String? = null,
    val quoteAmount: BigDecimal? = null,
    val quoteCurrency: String = "USD",
    val quotedBy: String = "",
    val costAmount: BigDecimal? = null,
    val requestedByName: String = "",
    val checklist: List<ImportedCheck> = emptyList(),
    val notes: List<ImportedNote> = emptyList(),
)

data class ImportSummary(
    val plans: Int,
    val orders: Int,
    val properties: List<String>,
    val assets: List<String>,
    val checklistItems: Int,
    val notes: Int,
    val files: Int,
    val matchedPeople: List<String>,
    val unmatchedPeople: List<String>,
    val problems: List<String>,
)

sealed class ImportOutcome {
    data class Created(val id: UUID) : ImportOutcome()
    data class Updated(val id: UUID) : ImportOutcome()
    data class Skipped(val reason: String) : ImportOutcome()
}

/** Escribe en Mantenimiento las filas importadas. Cada fila es su propia transacción, idempotente (por import_key) y no manda avisos. */
object MantenimientoImportApply {
    private val log = LoggerFactory.getLogger(MantenimientoImportApply::class.java)
    private val StampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private const val MaxKey = 200
    private const val MaxChecklistText = 300
    private const val MaxAuthor = 120
    private const val MaxPerson = 120
    private const val MaxRows = 100
    private const val MaxNotes = 2000
    private const val ImportAuthor = "Importación"
    private val MaxAmount = BigDecimal("999999999999.99")
    private val CurrencyPattern = Regex("^[A-Z]{3}$")

    // ---- reglas puras ----

    /** Recorta [text] a [max] caracteres terminando en "…" en lugar de fallar. */
    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    /** El usuario cuyo correo es [email], sin importar mayúsculas ni espacios. */
    fun matchUser(email: String?, users: List<MantenimientoUser>): MantenimientoUser? {
        val wanted = email?.trim()?.lowercase().orEmpty()
        if (wanted.isEmpty()) return null
        return users.firstOrNull { it.email?.trim()?.lowercase() == wanted }
    }

    /** "[Autor · fecha] texto": el cuerpo de una nota importada (como mucho el tope de una nota). */
    fun noteText(author: String, at: Instant?, body: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val head = listOfNotNull(author.trim().ifEmpty { null }, at?.atZone(zone)?.format(StampFormat)).joinToString(" · ")
        val text = if (head.isEmpty()) body.trim() else "[$head] ${body.trim()}"
        return clip(text, MantenimientoRepository.MaxNoteLength)
    }

    /** La descripción seguida de las personas que no tenían usuario; la nota se conserva y la descripción cede espacio. */
    fun descriptionWithNote(description: String, unmatched: List<String>, max: Int): String {
        val note = unmatched.takeIf { it.isNotEmpty() }?.let { "Responsable en Monday sin usuario: ${it.joinToString(", ")}" }.orEmpty()
        val base = description.trim()
        if (note.isEmpty()) return clip(base, max)
        if (base.isEmpty()) return clip(note, max)
        val room = (max - note.length - 2).coerceAtLeast(0)
        return if (room == 0) clip(note, max) else clip(base, room) + "\n\n" + note
    }

    /** Orden abierta de trabajo que vence mañana o antes: sus avisos se dan por enviados para que una importación no avise de lo viejo. */
    fun suppressesReminders(status: OrderStatus, dueDate: LocalDate?, today: LocalDate): Boolean =
        status.isWorking && dueDate != null && !dueDate.isAfter(today.plusDays(1))

    /** Un tipo y estado que el módulo admite: lo propio de proyectos solo vale en proyectos. */
    fun normalizeStatus(kind: OrderKind, status: OrderStatus): OrderStatus = when {
        kind == OrderKind.Project -> status
        status == OrderStatus.Requested || status == OrderStatus.Quoting -> OrderStatus.Open
        status == OrderStatus.Rejected -> OrderStatus.Cancelled
        else -> status
    }

    private fun cleanAmount(amount: BigDecimal?): BigDecimal? {
        if (amount == null || amount.signum() < 0) return null
        val rounded = amount.setScale(2, RoundingMode.HALF_UP)
        return rounded.takeIf { it <= MaxAmount }
    }

    private fun cleanCurrency(currency: String): String = currency.trim().uppercase().takeIf { CurrencyPattern.matches(it) } ?: "USD"

    private fun cleanInterval(frequency: PlanFrequency, interval: Int?): Int? =
        if (frequency == PlanFrequency.EveryNDays) (interval ?: 30).coerceIn(1, PlanInput.MaxIntervalDays) else null

    private class PeopleTally {
        val matched = linkedMapOf<String, String>()
        val unmatched = linkedMapOf<String, String>()

        fun add(email: String?, name: String?, users: List<MantenimientoUser>) {
            val user = matchUser(email, users)
            val cleanEmail = email?.trim().orEmpty()
            when {
                user != null -> matched.putIfAbsent(user.id.toString(), user.label)
                cleanEmail.isNotEmpty() -> unmatched.putIfAbsent(cleanEmail.lowercase(), cleanEmail)
                !name.isNullOrBlank() -> unmatched.putIfAbsent(name.trim().lowercase(), name.trim())
            }
        }
    }

    private fun distinctNames(names: List<String?>): List<String> =
        names.mapNotNull { it?.trim()?.takeIf { name -> name.isNotEmpty() } }.distinctBy { it.lowercase() }

    /** Lo que importar planes haría, para la vista previa. Pura. */
    fun summarizePlans(rows: List<ImportedPlan>, users: List<MantenimientoUser>, files: Int = 0): ImportSummary {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val valid = mutableListOf<ImportedPlan>()
        rows.forEach { row ->
            val key = row.key.trim()
            when {
                key.isEmpty() -> problems += "Un ítem no tiene identificador y se omitirá."
                row.title.isBlank() -> problems += "El ítem $key no tiene título y se omitirá."
                !seen.add(key) -> problems += "El ítem $key aparece más de una vez."
                else -> valid += row
            }
        }
        valid.filter { it.frequency == PlanFrequency.EveryNDays && (it.intervalDays == null || it.intervalDays !in 1..PlanInput.MaxIntervalDays) }
            .forEach { problems += "El plan ${it.key.trim()} no tiene un número de días válido: se usarán 30." }
        val people = PeopleTally()
        valid.forEach { people.add(it.assigneeEmail, it.assigneeName, users) }
        return ImportSummary(
            plans = valid.size,
            orders = 0,
            properties = distinctNames(valid.map { it.propertyName }),
            assets = distinctNames(valid.map { it.assetName }),
            checklistItems = valid.sumOf { it.checklist.size },
            notes = 0,
            files = files,
            matchedPeople = people.matched.values.toList(),
            unmatchedPeople = people.unmatched.values.toList(),
            problems = problems,
        )
    }

    /** Lo que importar órdenes haría, para la vista previa. Pura. */
    fun summarizeOrders(rows: List<ImportedOrder>, users: List<MantenimientoUser>, files: Int = 0): ImportSummary {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val valid = mutableListOf<ImportedOrder>()
        rows.forEach { row ->
            val key = row.key.trim()
            when {
                key.isEmpty() -> problems += "Un ítem no tiene identificador y se omitirá."
                row.title.isBlank() -> problems += "El ítem $key no tiene título y se omitirá."
                !seen.add(key) -> problems += "El ítem $key aparece más de una vez."
                else -> valid += row
            }
        }
        valid.filter { normalizeStatus(it.kind, it.status) != it.status }
            .forEach { problems += "El ítem ${it.key.trim()} tiene un estado de proyecto pero no es un proyecto: se importará como ${normalizeStatus(it.kind, it.status).label}." }
        val people = PeopleTally()
        valid.forEach { people.add(it.assigneeEmail, it.assigneeName, users) }
        return ImportSummary(
            plans = 0,
            orders = valid.size,
            properties = distinctNames(valid.map { it.propertyName }),
            assets = distinctNames(valid.map { it.assetName }),
            checklistItems = valid.sumOf { it.checklist.size },
            notes = valid.sumOf { it.notes.size },
            files = files,
            matchedPeople = people.matched.values.toList(),
            unmatchedPeople = people.unmatched.values.toList(),
            problems = problems,
        )
    }

    // ---- base de datos ----

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

    /** Los usuarios a los que se puede asignar una importación: los mismos que pueden ser responsables en el módulo. */
    fun assignableUsers(tenant: Connection): List<MantenimientoUser> = MantenimientoRepository.assignableUsers(tenant)

    private class Assignee(val id: UUID?, val unmatched: List<String>)

    private fun assigneeOf(email: String?, name: String?, users: List<MantenimientoUser>): Assignee {
        val user = matchUser(email, users)
        if (user != null) return Assignee(user.id, emptyList())
        val label = email?.trim()?.takeIf { it.isNotEmpty() } ?: name?.trim()?.takeIf { it.isNotEmpty() }
        return Assignee(null, listOfNotNull(label?.let { clip(it, MaxPerson) }))
    }

    private fun propertyId(tenant: Connection, name: String?): UUID? =
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { MantenimientoRepository.findOrCreateProperty(tenant, it) }

    private fun assetId(tenant: Connection, propertyId: UUID?, name: String?): UUID? =
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { MantenimientoRepository.findOrCreateAsset(tenant, propertyId, it) }

    /**
     * Crea o actualiza el plan de [row] (una transacción, mismo resultado al repetir) con su lista de comprobación. No manda avisos.
     * Una fila sin título o identificador se omite. Al repetir no se toca la próxima fecha ni si el plan está activo.
     */
    fun applyPlan(tenant: Connection, row: ImportedPlan, users: List<MantenimientoUser>, actorId: UUID?, today: LocalDate): ImportOutcome {
        val key = row.key.trim()
        if (key.isEmpty()) return ImportOutcome.Skipped("El ítem no tiene identificador.")
        if (key.length > MaxKey) return ImportOutcome.Skipped("El identificador del ítem $key es demasiado largo.")
        if (row.title.isBlank()) return ImportOutcome.Skipped("El ítem $key no tiene título.")
        return MantenimientoRepository.transaction(tenant) {
            val assignee = assigneeOf(row.assigneeEmail, row.assigneeName, users)
            val property = propertyId(tenant, row.propertyName)
            val asset = assetId(tenant, property, row.assetName)
            val title = clip(row.title.trim(), PlanInput.MaxTitle)
            val description = descriptionWithNote(row.description, assignee.unmatched, PlanInput.MaxDescription)
            val interval = cleanInterval(row.frequency, row.intervalDays)
            val existing = tenant.rows("SELECT id FROM mantenimiento_plans WHERE import_key = ?", listOf(key)) { it.getObject("id", UUID::class.java) }.firstOrNull()
            var planId: UUID? = existing
            var created = false
            if (planId == null) {
                val id = UUID.randomUUID()
                val inserted = tenant.exec(
                    """
                    INSERT INTO mantenimiento_plans (id, title, description, property_id, asset_id, frequency, interval_days, schedule_mode, next_due, anchor_date, default_assignee, priority, import_key, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'fixed', ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (import_key) WHERE import_key IS NOT NULL DO NOTHING
                    """.trimIndent(),
                    listOf(id, title, description, property, asset, row.frequency.id, interval, row.nextDue, row.nextDue, assignee.id, row.priority.id, key, actorId),
                )
                if (inserted > 0) {
                    planId = id
                    created = true
                } else {
                    // Otra ejecución lo insertó en el mismo momento: se actualiza ese.
                    planId = tenant.rows("SELECT id FROM mantenimiento_plans WHERE import_key = ?", listOf(key)) { it.getObject("id", UUID::class.java) }.firstOrNull()
                }
            }
            val id = planId ?: throw MantenimientoValidationException("No se pudo guardar el ítem $key.")
            if (!created) {
                tenant.exec(
                    """
                    UPDATE mantenimiento_plans
                    SET title = ?, description = ?, property_id = ?, asset_id = ?, frequency = ?, interval_days = ?, default_assignee = ?, priority = ?, updated_at = now()
                    WHERE id = ?
                    """.trimIndent(),
                    listOf(title, description, property, asset, row.frequency.id, interval, assignee.id, row.priority.id, id),
                )
            }
            val omitted = addPlanChecklist(tenant, id, row.checklist)
            if (omitted > 0) log.warn("El plan {} superó el tope de la lista de comprobación: se omitieron {} elementos.", key, omitted)
            if (created) ImportOutcome.Created(id) else ImportOutcome.Updated(id)
        }
    }

    private fun addPlanChecklist(tenant: Connection, planId: UUID, items: List<ImportedCheck>): Int {
        if (items.isEmpty()) return 0
        tenant.rows("SELECT 1 AS found FROM mantenimiento_plans WHERE id = ? FOR UPDATE", listOf(planId)) { it.getInt("found") }
        var count = tenant.rows("SELECT count(*) AS total FROM mantenimiento_plan_checklist WHERE plan_id = ?", listOf(planId)) { it.getInt("total") }.firstOrNull() ?: 0
        var omitted = 0
        items.forEach { item ->
            val key = item.key.trim().take(MaxKey)
            val text = clip(item.text.trim(), MaxChecklistText)
            if (key.isEmpty() || text.isEmpty()) return@forEach
            if (count >= MaxRows) {
                omitted++
                return@forEach
            }
            count += tenant.exec(
                """
                INSERT INTO mantenimiento_plan_checklist (id, plan_id, text, position, import_key)
                VALUES (?, ?, ?, (SELECT COALESCE(MAX(position), 0) + 1 FROM mantenimiento_plan_checklist WHERE plan_id = ?), ?)
                ON CONFLICT (plan_id, import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(UUID.randomUUID(), planId, text, planId, key),
            )
        }
        return omitted
    }

    /**
     * Crea o actualiza la orden de [row] (una transacción, mismo resultado al repetir) con su lista y sus notas que aún no estén.
     * No manda avisos. Una fila sin título o identificador se omite.
     */
    fun applyOrder(tenant: Connection, row: ImportedOrder, users: List<MantenimientoUser>, actorId: UUID?, today: LocalDate): ImportOutcome {
        val key = row.key.trim()
        if (key.isEmpty()) return ImportOutcome.Skipped("El ítem no tiene identificador.")
        if (key.length > MaxKey) return ImportOutcome.Skipped("El identificador del ítem $key es demasiado largo.")
        if (row.title.isBlank()) return ImportOutcome.Skipped("El ítem $key no tiene título.")
        return MantenimientoRepository.transaction(tenant) {
            val assignee = assigneeOf(row.assigneeEmail, row.assigneeName, users)
            val property = propertyId(tenant, row.propertyName)
            val asset = assetId(tenant, property, row.assetName)
            val status = normalizeStatus(row.kind, row.status)
            val title = clip(row.title.trim(), OrderInput.MaxTitle)
            val description = descriptionWithNote(row.description, assignee.unmatched, OrderInput.MaxDescription)
            val done = status == OrderStatus.Done
            val approved = row.kind == OrderKind.Project && (status.isWorking || done)
            val department = clip(row.department.trim(), OrderInput.MaxDepartment)
            val quotedBy = clip(row.quotedBy.trim(), OrderInput.MaxPerson)
            val requester = clip(row.requestedByName.trim().ifEmpty { ImportAuthor }, MaxAuthor)
            val quote = cleanAmount(row.quoteAmount)
            val quoteCurrency = cleanCurrency(row.quoteCurrency)
            val cost = cleanAmount(row.costAmount)

            val existing = tenant.rows("SELECT id FROM mantenimiento_orders WHERE import_key = ?", listOf(key)) { it.getObject("id", UUID::class.java) }.firstOrNull()
            var orderId: UUID? = existing
            var created = false
            if (orderId == null) {
                val id = UUID.randomUUID()
                val inserted = tenant.exec(
                    """
                    INSERT INTO mantenimiento_orders (id, kind, status, priority, title, description, property_id, asset_id, due_date, assignee_id, requested_by_name, department,
                        quote_amount, quote_currency, quoted_by, cost_amount, completed_at, completed_by, approved_at, approved_by, import_key, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        CASE WHEN ?::boolean THEN now() ELSE NULL END, CASE WHEN ?::boolean THEN ?::uuid ELSE NULL END,
                        CASE WHEN ?::boolean THEN now() ELSE NULL END, CASE WHEN ?::boolean THEN ?::uuid ELSE NULL END, ?, ?)
                    ON CONFLICT (import_key) WHERE import_key IS NOT NULL DO NOTHING
                    """.trimIndent(),
                    listOf(
                        id, row.kind.id, status.id, row.priority.id, title, description, property, asset, row.dueDate, assignee.id, requester, department,
                        quote, quoteCurrency, quotedBy, cost, done, done, actorId, approved, approved, actorId, key, actorId,
                    ),
                )
                if (inserted > 0) {
                    orderId = id
                    created = true
                } else {
                    orderId = tenant.rows("SELECT id FROM mantenimiento_orders WHERE import_key = ?", listOf(key)) { it.getObject("id", UUID::class.java) }.firstOrNull()
                }
            }
            val id = orderId ?: throw MantenimientoValidationException("No se pudo guardar el ítem $key.")
            if (!created) {
                tenant.exec(
                    """
                    UPDATE mantenimiento_orders
                    SET kind = ?, status = ?, priority = ?, title = ?, description = ?, property_id = ?, asset_id = ?, due_date = ?, assignee_id = ?, department = ?,
                        quote_amount = ?, quote_currency = ?, quoted_by = ?, cost_amount = ?,
                        completed_at = CASE WHEN ?::boolean THEN COALESCE(completed_at, now()) ELSE NULL END,
                        completed_by = CASE WHEN ?::boolean THEN COALESCE(completed_by, ?::uuid) ELSE NULL END,
                        updated_at = now()
                    WHERE id = ?
                    """.trimIndent(),
                    listOf(row.kind.id, status.id, row.priority.id, title, description, property, asset, row.dueDate, assignee.id, department, quote, quoteCurrency, quotedBy, cost, done, done, actorId, id),
                )
            }
            // Si la integración con Tareas está encendida, la orden importada se publica (sin avisos).
            MantenimientoRepository.markSync(tenant, id)
            if (assignee.id != null && suppressesReminders(status, row.dueDate, today) && row.dueDate != null) {
                listOf("overdue", "due_soon").forEach { kind ->
                    tenant.exec(
                        "INSERT INTO mantenimiento_reminders (order_id, user_id, kind, due_date) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        listOf(id, assignee.id, kind, row.dueDate),
                    )
                }
            }
            if (created) {
                tenant.exec(
                    "INSERT INTO mantenimiento_order_notes (id, order_id, kind, body, author_id, author_name) VALUES (?, ?, 'system', 'Importada desde otro sistema.', NULL, ?)",
                    listOf(UUID.randomUUID(), id, ImportAuthor),
                )
            }
            val omitted = addOrderChecklist(tenant, id, row.checklist) + addOrderNotes(tenant, id, row.notes)
            if (omitted > 0) log.warn("El ítem {} superó los topes de lista o notas: se omitieron {} elementos.", key, omitted)
            if (created) ImportOutcome.Created(id) else ImportOutcome.Updated(id)
        }
    }

    // Serializa a quienes cuentan antes de insertar (topes por orden).
    private fun lockOrder(tenant: Connection, orderId: UUID) {
        tenant.rows("SELECT 1 AS found FROM mantenimiento_orders WHERE id = ? FOR UPDATE", listOf(orderId)) { it.getInt("found") }
    }

    /** Agrega los elementos que aún no están (por clave) hasta el tope; devuelve cuántos quedaron fuera por el tope. */
    private fun addOrderChecklist(tenant: Connection, orderId: UUID, items: List<ImportedCheck>): Int {
        if (items.isEmpty()) return 0
        lockOrder(tenant, orderId)
        var count = tenant.rows("SELECT count(*) AS total FROM mantenimiento_order_checklist WHERE order_id = ?", listOf(orderId)) { it.getInt("total") }.firstOrNull() ?: 0
        var omitted = 0
        items.forEach { item ->
            val key = item.key.trim().take(MaxKey)
            val text = clip(item.text.trim(), MaxChecklistText)
            if (key.isEmpty() || text.isEmpty()) return@forEach
            if (count >= MaxRows) {
                omitted++
                return@forEach
            }
            count += tenant.exec(
                """
                INSERT INTO mantenimiento_order_checklist (id, order_id, text, done, position, done_at, import_key)
                VALUES (?, ?, ?, ?::boolean, (SELECT COALESCE(MAX(position), 0) + 1 FROM mantenimiento_order_checklist WHERE order_id = ?), CASE WHEN ?::boolean THEN now() ELSE NULL END, ?)
                ON CONFLICT (order_id, import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(UUID.randomUUID(), orderId, text, item.done, orderId, item.done, key),
            )
        }
        return omitted
    }

    private fun addOrderNotes(tenant: Connection, orderId: UUID, notes: List<ImportedNote>): Int {
        if (notes.isEmpty()) return 0
        lockOrder(tenant, orderId)
        var count = tenant.rows("SELECT count(*) AS total FROM mantenimiento_order_notes WHERE order_id = ?", listOf(orderId)) { it.getInt("total") }.firstOrNull() ?: 0
        var omitted = 0
        notes.forEach { note ->
            val key = note.key.trim().take(MaxKey)
            if (key.isEmpty() || note.body.isBlank()) return@forEach
            if (count >= MaxNotes) {
                omitted++
                return@forEach
            }
            val author = clip(note.author.trim().ifEmpty { ImportAuthor }, MaxAuthor)
            count += tenant.exec(
                """
                INSERT INTO mantenimiento_order_notes (id, order_id, kind, body, author_id, author_name, created_at, import_key)
                VALUES (?, ?, 'note', ?, NULL, ?, COALESCE(?::timestamptz, now()), ?)
                ON CONFLICT (order_id, import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(UUID.randomUUID(), orderId, noteText(author, note.at, note.body), author, note.at?.atOffset(ZoneOffset.UTC), key),
            )
        }
        return omitted
    }

    /**
     * Guarda un archivo de la fuente como adjunto de la orden, una vez por [importKey] (false si ya estaba: entonces no se lee el
     * contenido). La orden admite [MantenimientoRepository.MaxFilesPerOrder] archivos y cada uno [MantenimientoRepository.MaxFileBytes].
     */
    fun attachFile(tenant: Connection, files: ModuleFiles, orderId: UUID, importKey: String, name: String, contentType: String, content: InputStream, actorId: UUID?): Boolean {
        val key = importKey.trim().take(MaxKey)
        if (key.isEmpty()) throw MantenimientoValidationException("La clave del archivo no es válida.")
        val already = tenant.rows("SELECT 1 AS found FROM mantenimiento_attachments WHERE order_id = ? AND import_key = ?", listOf(orderId, key)) { it.getInt("found") }.isNotEmpty()
        if (already) return false
        val total = tenant.rows("SELECT count(*) AS total FROM mantenimiento_attachments WHERE order_id = ? AND NOT delete_pending", listOf(orderId)) { it.getInt("total") }.firstOrNull() ?: 0
        if (total >= MantenimientoRepository.MaxFilesPerOrder) {
            throw MantenimientoValidationException("La orden ya tiene el máximo de ${MantenimientoRepository.MaxFilesPerOrder} archivos.")
        }
        val fileName = MantenimientoRepository.cleanFileName(name).ifEmpty { "archivo" }
        val type = contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" }
        val stored = try {
            files.put(fileName, type, content, MantenimientoRepository.MaxFileBytes)
        } catch (_: FileTooLargeException) {
            throw MantenimientoValidationException("$fileName supera el máximo de ${MantenimientoRepository.MaxFileBytes / (1024 * 1024)} MB.")
        }
        try {
            val inserted = tenant.exec(
                """
                INSERT INTO mantenimiento_attachments (id, order_id, name, path, content_type, size, backend, ref, uploaded_by, import_key)
                VALUES (?, ?, ?, '', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id, import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(UUID.randomUUID(), orderId, fileName, type, stored.size, stored.backend, stored.ref, actorId, key),
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
