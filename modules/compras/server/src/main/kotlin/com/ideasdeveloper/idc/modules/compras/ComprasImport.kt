package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.FileTooLargeException
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import com.ideasdeveloper.idc.server.modules.StoredFileRef
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Una fuente de importacion (la aporta una extension, p. ej. compras_monday). */
interface ComprasImportSource {
    /** Prefijo de las claves de importacion y de las ops de la pantalla Importar, p. ej. "monday". */
    val id: String

    /** Nombre mostrado, p. ej. "Monday". */
    val label: String

    /** Id de la extension que la aporta, p. ej. "compras_monday". */
    val extensionId: String

    /** Piezas de la tarjeta de la fuente en la pantalla Importar (formularios con UiRequest a la ruta del modulo). */
    fun screen(ctx: ComprasImportContext, params: Map<String, String>): List<UiNode>

    /** Op de la pantalla Importar ("<id>:<op>"); devuelve el aviso a mostrar. Lanza ComprasValidationException. */
    fun action(ctx: ComprasImportContext, op: String, values: Map<String, String>): UiNotice?

    /** La fuente dice si su extension esta activa en esta empresa (la pantalla Importar y el trabajo la consultan). */
    fun isEnabled(ctx: ComprasImportContext): Boolean = true

    /** Herramientas MCP extra (permiso compras.import); el nombre no lleva prefijo, el registro anade "compras_". */
    val mcpTools: List<McpTool> get() = emptyList()

    /** Trabajo periodico: avanza lecturas y aplicaciones pendientes, acotado por [budget] filas. Idempotente. */
    fun runPending(ctx: ComprasImportContext, budget: Int)
}

/**
 * Lo que el modulo ofrece a una fuente: la conexion de la empresa, los archivos del modulo, el notificador y el
 * sellado de secretos (la extension no ve access.secrets ni ModuleFiles por su contrato). [actor] es null en el
 * trabajo de fondo. Bloqueante (JDBC): usar en withContext(Dispatchers.IO).
 */
class ComprasImportContext(
    val tenant: Connection,
    val companyCode: String,
    val actor: Actor?,
    val files: ModuleFiles,
    val notifier: ModuleNotifier?,
    private val secrets: SecretBox,
) {
    /** Cifra [plain] con la llave de datos de la empresa (para guardar un token). Nunca se muestra. */
    fun seal(plain: String): String = secrets.seal(tenant, plain)

    /** Descifra un valor guardado con [seal]. */
    fun open(sealed: String): String = secrets.open(tenant, sealed)
}

/** Un archivo por importar; [key] identifica el archivo de origen (unico por empresa). [kind]: support, quote, proforma, invoice, other. */
data class ImportedFile(val key: String, val name: String, val contentType: String, val kind: String, val open: () -> InputStream?)

data class ImportedQuote(
    val key: String,
    val supplier: String,
    val amount: BigDecimal,
    val currency: String,
    val selected: Boolean,
    val quotedByName: String,
    val notes: String = "",
    val files: List<ImportedFile> = emptyList(),
)

data class ImportedRequest(
    /** Clave de origen "<fuente>:<id>", p. ej. "monday:123456". */
    val key: String,
    val title: String,
    val description: String,
    /** Nombre del departamento; se crea (activo) si no existe. */
    val department: String?,
    val estimatedAmount: BigDecimal?,
    val currency: String,
    val requiredDate: LocalDate?,
    val priority: Priority,
    val status: RequestStatus,
    /** Se busca un usuario activo de la empresa por correo o por nombre exacto; si no, el solicitante es quien importa. */
    val requesterName: String,
    val requesterEmail: String?,
    /** Motivo de las rechazadas. */
    val decisionReason: String?,
    val createdAt: Instant?,
    val quotes: List<ImportedQuote> = emptyList(),
    val files: List<ImportedFile> = emptyList(),
)

/** [Partial]: la solicitud se creo o ya existia, pero faltaron archivos o cotizaciones (ver `problems`). */
enum class ImportOutcome { Created, Existing, Partial, Failed }

data class ImportResult(val outcome: ImportOutcome, val requestId: UUID?, val problems: List<String>)

/** Fuentes de importacion que traen las extensiones instaladas (las entrega el Core al arrancar el servidor). */
object ComprasImportSources {
    @Volatile
    private var sources: List<ComprasImportSource> = emptyList()

    fun link(extensions: List<ServerExtension>) {
        sources = extensions.filterIsInstance<ComprasImportSource>()
    }

    val all: List<ComprasImportSource> get() = sources

    fun byId(id: String): ComprasImportSource? = sources.firstOrNull { it.id == id }
}

/** Una cotizacion ya validada, lista para guardar. */
data class QuotePlan(val key: String, val supplier: String, val amount: BigDecimal, val currency: String, val selected: Boolean, val quotedByName: String, val notes: String, val files: List<ImportedFile>)

/** Lo que se guardara de una solicitud importada: campos validados y la decision "Importado de ...". */
data class RequestPlan(
    val key: String,
    val title: String,
    val description: String,
    val department: String?,
    val estimatedAmount: BigDecimal?,
    val currency: String,
    val requiredDate: LocalDate?,
    val priority: Priority,
    val status: RequestStatus,
    val requesterName: String,
    val requesterEmail: String?,
    val decidedByName: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val approvedAmount: BigDecimal?,
    val approvedCurrency: String?,
    val submittedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val quotes: List<QuotePlan>,
    val files: List<ImportedFile>,
    val problems: List<String>,
    val sourceLabel: String,
    /** Se omitieron cotizaciones: el resultado sera Partial. */
    val incomplete: Boolean = false,
)

object ComprasImportEngine {
    private val log = LoggerFactory.getLogger(ComprasImportEngine::class.java)
    private val DecidedStatuses = setOf(RequestStatus.Approved, RequestStatus.Rejected, RequestStatus.InProgress, RequestStatus.Completed)
    private val ApprovedStatuses = setOf(RequestStatus.Approved, RequestStatus.InProgress, RequestStatus.Completed)
    private val SubmittedStatuses = setOf(RequestStatus.PendingApproval) + DecidedStatuses

    /** Nombre de la fuente de una clave: el de la fuente instalada o, si no, el prefijo con inicial mayuscula. */
    fun sourceLabel(key: String): String {
        val prefix = key.substringBefore(':')
        return ComprasImportSources.byId(prefix)?.label ?: prefix.replaceFirstChar { it.uppercase() }
    }

    /**
     * Valida y normaliza [item] sin tocar la base de datos (funcion pura). Lo que no sirve (monto invalido, moneda de una
     * cotizacion...) se omite y se informa en `problems`; sin clave o sin titulo lanza [ComprasValidationException].
     * Sin [canManage] (compras.manage o dueno) los estados con decision entran como pendientes de aprobacion.
     * Las solicitudes con decision entran con decided_by nulo, decision "Importado de <fuente>" y, si fueron aprobadas,
     * approved_amount/approved_currency = monto a aprobar (cotizacion elegida o estimado).
     */
    fun plan(item: ImportedRequest, now: Instant, canManage: Boolean): RequestPlan {
        val key = item.key.trim()
        if (key.isEmpty() || key.length > 200 || ':' !in key) throw ComprasValidationException("La clave de origen no es valida.")
        val title = item.title.trim().take(RequestDraft.MaxTitle)
        if (title.isEmpty()) throw ComprasValidationException("La fila no tiene titulo.")
        val problems = mutableListOf<String>()
        val currency = ComprasMoney.currency(item.currency)
        val description = item.description.trim().let {
            if (it.length > RequestDraft.MaxDescription) problems += "La descripcion se recorto a ${RequestDraft.MaxDescription} caracteres."
            it.take(RequestDraft.MaxDescription)
        }
        val estimated = item.estimatedAmount?.let { amount ->
            try {
                ComprasMoney.check(amount)
            } catch (failure: ComprasValidationException) {
                problems += "Monto omitido: ${failure.message}"
                null
            }
        }
        var incomplete = false
        val status = if (item.status in DecidedStatuses && !canManage) {
            problems += "Estado ${item.status.label} importado como pendiente: hace falta Administrar compras"
            RequestStatus.PendingApproval
        } else {
            item.status
        }
        var selectedTaken = false
        val quotes = mutableListOf<QuotePlan>()
        item.quotes.distinctBy { it.key }.forEach { quote ->
            if (quote.key.isBlank() || quote.key.length > 200) {
                incomplete = true
                problems += "Cotizacion omitida: clave no valida."
                return@forEach
            }
            val money = try {
                ComprasMoney.check(quote.amount) to ComprasMoney.currency(quote.currency)
            } catch (failure: ComprasValidationException) {
                problems += "Cotizacion omitida: ${failure.message}"
                incomplete = true
                return@forEach
            }
            val selected = quote.selected && !selectedTaken
            if (selected) selectedTaken = true
            quotes += QuotePlan(
                quote.key.trim(), quote.supplier.trim().ifEmpty { "Cotizacion" }.take(200), money.first, money.second, selected,
                quote.quotedByName.trim().ifEmpty { "Importado" }.take(200), quote.notes.trim().take(2000), quote.files,
            )
        }
        val label = sourceLabel(key)
        val decided = status in DecidedStatuses
        val chosen = quotes.firstOrNull { it.selected }
        val approvalAmount = chosen?.amount ?: estimated
        val approvalCurrency = chosen?.currency ?: currency
        val approved = status in ApprovedStatuses && approvalAmount != null
        val created = item.createdAt ?: now
        return RequestPlan(
            key = key,
            title = title,
            description = description,
            department = item.department?.trim()?.take(80)?.takeIf { it.isNotEmpty() },
            estimatedAmount = estimated,
            currency = currency,
            requiredDate = item.requiredDate,
            priority = item.priority,
            status = status,
            requesterName = item.requesterName.trim().take(200),
            requesterEmail = item.requesterEmail?.trim()?.takeIf { it.isNotEmpty() },
            decidedByName = if (decided) "Importado de $label" else null,
            decidedAt = if (decided) created else null,
            decisionReason = if (status == RequestStatus.Rejected) item.decisionReason?.trim()?.take(1000)?.takeIf { it.isNotEmpty() } else null,
            approvedAmount = if (approved) approvalAmount else null,
            approvedCurrency = if (approved) approvalCurrency else null,
            submittedAt = if (status in SubmittedStatuses) created else null,
            completedAt = if (status == RequestStatus.Completed) created else null,
            createdAt = created,
            quotes = quotes,
            files = item.files,
            problems = problems,
            sourceLabel = label,
            incomplete = incomplete,
        )
    }

    /** Estado de una pasada: problemas, archivos guardados, los que sobraron y si quedo incompleta. */
    private class Run(val problems: MutableList<String>, var partial: Boolean) {
        val stored = mutableListOf<StoredFileRef>()
        val dropped = mutableListOf<StoredFileRef>()

        fun missing(text: String) {
            problems += text
            partial = true
        }
    }

    /**
     * Importa una solicitud. Idempotente por import_key: si ya existe no se cambia, solo se anaden cotizaciones y archivos
     * cuya clave sea nueva. Una transaccion por solicitud; los archivos se guardan antes y se borran si la fila falla.
     * Resultado [ImportOutcome.Partial] si faltaron archivos o cotizaciones. Sin compras.manage (ni dueno) las decisiones
     * importadas pasan a pendientes, el solicitante es quien importa y no se crean departamentos. No avisa por solicitud.
     * Bloqueante (JDBC y disco): usar en withContext(Dispatchers.IO).
     */
    fun apply(ctx: ComprasImportContext, importer: Actor, item: ImportedRequest): ImportResult {
        val canManage = importer.can(ComprasModule.Manage)
        val plan = try {
            plan(item, Instant.now(), canManage)
        } catch (failure: ComprasValidationException) {
            return ImportResult(ImportOutcome.Failed, null, listOf(failure.message ?: "Fila no valida."))
        }
        val importerId = importer.userId ?: return ImportResult(ImportOutcome.Failed, null, listOf("No se pudo identificar al usuario que importa."))
        val run = Run(plan.problems.toMutableList(), plan.incomplete)
        return try {
            val tenant = ctx.tenant
            val existingId = idByKey(tenant, "compras_requests", plan.key)
            val requestId = existingId ?: UUID.randomUUID()
            val existingFiles = if (existingId == null) 0 else countFiles(tenant, existingId)
            val toStore = mutableListOf<PendingFile>()
            val candidates = plan.files.map { it to null as String? } + plan.quotes.flatMap { q -> q.files.map { it to q.key as String? } }
            candidates.distinctBy { it.first.key }.forEach { (file, quoteKey) ->
                if (file.key.isBlank() || idByKey(tenant, "compras_attachments", file.key) != null) return@forEach
                val name = ComprasAttachments.cleanName(file.name).ifEmpty { "archivo" }
                if (existingFiles + toStore.size >= ComprasAttachments.MaxFilesPerRequest) {
                    run.missing("$name: se alcanzo el maximo de ${ComprasAttachments.MaxFilesPerRequest} archivos.")
                    return@forEach
                }
                val ref = storeFile(ctx.files, file, name)
                if (ref == null) {
                    run.missing("$name: no se pudo traer el archivo.")
                    return@forEach
                }
                run.stored += ref
                toStore += PendingFile(file, name, quoteKey, ref)
            }
            val created = ComprasSql.transaction(tenant) { persist(ctx, importer, importerId, canManage, plan, requestId, existingId == null, toStore, run) }
            if (created == null && existingId == null) {
                // Otro proceso importo la misma clave entre la consulta y el insert: los archivos de esta pasada sobran.
                run.stored.forEach { runCatching { ctx.files.delete(it) } }
                return ImportResult(ImportOutcome.Existing, idByKey(tenant, "compras_requests", plan.key), run.problems)
            }
            run.dropped.forEach { runCatching { ctx.files.delete(it) } }
            val outcome = when {
                run.partial -> ImportOutcome.Partial
                created != null -> ImportOutcome.Created
                else -> ImportOutcome.Existing
            }
            ImportResult(outcome, created ?: existingId, run.problems)
        } catch (failure: Exception) {
            run.stored.forEach { runCatching { ctx.files.delete(it) } }
            log.warn("No se pudo importar la solicitud {}.", plan.key, failure)
            val detail = if (failure is SQLException) "SQLState ${failure.sqlState ?: "desconocido"}" else "error interno"
            ImportResult(ImportOutcome.Failed, null, run.problems + "No se pudo guardar la solicitud ($detail).")
        }
    }

    private class PendingFile(val file: ImportedFile, val name: String, val quoteKey: String?, val ref: StoredFileRef)

    // Lee el archivo de origen y lo guarda en el almacen; null si no esta disponible, es demasiado grande o falla.
    private fun storeFile(files: ModuleFiles, file: ImportedFile, name: String): StoredFileRef? = try {
        val type = file.contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" }
        file.open()?.use { input -> files.put(name, type, input, ComprasAttachments.MaxFileBytes) }
    } catch (_: FileTooLargeException) {
        null
    } catch (failure: Exception) {
        log.warn("No se pudo traer un archivo importado.", failure)
        null
    }

    private fun idByKey(tenant: Connection, table: String, key: String): UUID? {
        // El nombre de tabla es siempre una constante de este archivo, nunca un valor externo.
        require(table == "compras_requests" || table == "compras_quotes" || table == "compras_attachments")
        return tenant.prepareStatement("SELECT id FROM $table WHERE import_key = ?").use { query ->
            query.setString(1, key)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
    }

    private fun countFiles(tenant: Connection, requestId: UUID): Int =
        tenant.prepareStatement("SELECT count(*) FROM compras_attachments WHERE request_id = ? AND deleted_at IS NULL").use { query ->
            query.setObject(1, requestId)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    // Usuario activo con ese correo exacto (una sola coincidencia); null si no hay o es ambiguo. Nunca por nombre.
    private fun findUserByEmail(tenant: Connection, email: String): Pair<UUID, String>? = tenant.prepareStatement(
        "SELECT id, COALESCE(NULLIF(trim(display_name), ''), postgres_role) FROM application_users WHERE is_active AND lower(email) = lower(?) LIMIT 2"
    ).use { query ->
        query.setString(1, email)
        query.executeQuery().use { rows ->
            val found = buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getString(2)) }
            found.singleOrNull()
        }
    }

    // Departamento existente; uno nuevo solo lo crea quien administra compras (si no, null y un problema).
    private fun departmentFor(tenant: Connection, name: String, canManage: Boolean, run: Run): UUID? {
        fun find(): UUID? = tenant.prepareStatement("SELECT id FROM compras_departments WHERE lower(name) = lower(?)").use { query ->
            query.setString(1, name)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
        find()?.let { return it }
        if (!canManage) {
            run.problems += "Departamento '$name' no existe: hace falta Administrar compras para crearlo."
            return null
        }
        tenant.prepareStatement(
            "INSERT INTO compras_departments (id, name, sort_order) VALUES (?, ?, COALESCE((SELECT max(sort_order) + 1 FROM compras_departments), 0)) ON CONFLICT (lower(name)) DO NOTHING"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, name)
            insert.executeUpdate()
        }
        return find() ?: throw IllegalStateException("departamento")
    }

    private fun ts(instant: Instant?): Timestamp? = instant?.let { Timestamp.from(it) }

    /**
     * Solicitante de una solicitud importada: otro usuario solo si [canManage] y hay coincidencia unica por correo exacto;
     * si no, quien importa y el nombre que trae la fuente (o el del importador si no trae).
     */
    fun requesterFor(match: Pair<UUID, String>?, canManage: Boolean, importerId: UUID, importerName: String, sourceName: String): Pair<UUID, String> {
        val matched = match?.takeIf { canManage }
        return (matched?.first ?: importerId) to (sourceName.ifBlank { null } ?: matched?.second ?: importerName).take(200)
    }

    // Escribe todo en la transaccion abierta. Devuelve el id si creo la solicitud, o null si ya existia (solo anade piezas).
    private fun persist(
        ctx: ComprasImportContext,
        importer: Actor,
        importerId: UUID,
        canManage: Boolean,
        plan: RequestPlan,
        requestId: UUID,
        creating: Boolean,
        files: List<PendingFile>,
        run: Run,
    ): UUID? {
        val tenant = ctx.tenant
        var createdNow = false
        if (creating) {
            val match = if (canManage) plan.requesterEmail?.let { findUserByEmail(tenant, it) } else null
            val (requesterId, requesterName) = requesterFor(match, canManage, importerId, importer.name, plan.requesterName)
            val department = plan.department?.let { departmentFor(tenant, it, canManage, run) }
            val inserted = tenant.prepareStatement(
                """
                INSERT INTO compras_requests (id, title, description, department_id, estimated_amount, currency, required_date, priority, status,
                    requester_id, requester_name, decided_by_name, decided_at, decision_reason, approved_amount, approved_currency,
                    submitted_at, completed_at, created_at, updated_at, import_key, imported_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (import_key) DO NOTHING
                """.trimIndent()
            ).use { insert ->
                ComprasSql.bind(
                    insert,
                    listOf(
                        requestId, plan.title, plan.description, department, plan.estimatedAmount, plan.currency, plan.requiredDate, plan.priority.id,
                        plan.status.id, requesterId, requesterName, plan.decidedByName, ts(plan.decidedAt), plan.decisionReason,
                        plan.approvedAmount, plan.approvedCurrency, ts(plan.submittedAt), ts(plan.completedAt), ts(plan.createdAt), ts(plan.createdAt), plan.key,
                    ),
                )
                insert.executeUpdate()
            }
            if (inserted == 0) return null
            createdNow = true
        }
        val quoteIds = mutableMapOf<String, UUID>()
        var addedQuotes = 0
        plan.quotes.forEach { quote ->
            val existing = idByKey(tenant, "compras_quotes", quote.key)
            if (existing != null) {
                quoteIds[quote.key] = existing
                return@forEach
            }
            val id = UUID.randomUUID()
            val inserted = tenant.prepareStatement(
                """
                INSERT INTO compras_quotes (id, request_id, supplier, amount, currency, notes, selected, quoted_by_name, created_at, import_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (import_key) DO NOTHING
                """.trimIndent()
            ).use { insert ->
                // En una solicitud que ya existia no se cambia la cotizacion elegida.
                ComprasSql.bind(insert, listOf(id, requestId, quote.supplier, quote.amount, quote.currency, quote.notes, quote.selected && createdNow, quote.quotedByName, ts(plan.createdAt), quote.key))
                insert.executeUpdate()
            }
            if (inserted == 0) {
                run.missing("Cotizacion ${quote.supplier}: ya existe en otra solicitud.")
                return@forEach
            }
            quoteIds[quote.key] = id
            addedQuotes++
        }
        var addedFiles = 0
        files.forEach { pending ->
            val kind = pending.file.kind.takeIf { it in ComprasAttachments.Kinds } ?: "support"
            val inserted = tenant.prepareStatement(
                """
                INSERT INTO compras_attachments (id, request_id, quote_id, kind, file_name, folder_path, content_type, size_bytes, storage_backend, storage_ref,
                    uploaded_by, uploaded_by_id, created_at, import_key)
                VALUES (?, ?, ?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (import_key) DO NOTHING
                """.trimIndent()
            ).use { insert ->
                ComprasSql.bind(
                    insert,
                    listOf(
                        UUID.randomUUID(), requestId, pending.quoteKey?.let { quoteIds[it] }, kind, pending.name,
                        pending.file.contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" },
                        pending.ref.size, pending.ref.backend, pending.ref.ref, importer.name.take(200), importerId, ts(plan.createdAt), pending.file.key,
                    ),
                )
                insert.executeUpdate()
            }
            if (inserted == 0) {
                // Otro proceso ya trajo ese archivo: el recien guardado sobra y se borra al confirmar.
                run.dropped += pending.ref
                run.missing("${pending.name}: ya existe en otra solicitud.")
            } else {
                addedFiles++
            }
        }
        val itemId = plan.key.substringAfter(':')
        when {
            createdNow -> ComprasRepository.addEvent(tenant, requestId, importer, "imported", "Importada de ${plan.sourceLabel} (item $itemId)")
            addedQuotes > 0 || addedFiles > 0 -> {
                tenant.prepareStatement("UPDATE compras_requests SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, requestId); it.executeUpdate() }
                ComprasRepository.addEvent(tenant, requestId, importer, "imported", "Se anadieron $addedQuotes cotizaciones y $addedFiles archivos de ${plan.sourceLabel} (item $itemId)")
            }
        }
        return if (createdNow) requestId else null
    }
}
