package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.ModuleJobContext
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.text.Normalizer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Una extension que importa miembros desde otra herramienta (por ejemplo Monday). El modulo pone la pantalla Importar,
 * los permisos y las reglas de aplicacion ([MembresiaImportApply]); la extension lee el origen y entrega [ImportedMember].
 */
interface MembresiaImportSource {
    /** Id de la extension, por ejemplo "membresia_monday". */
    val sourceId: String

    /** Nombre para mostrar, por ejemplo "Monday". */
    val title: String

    fun enabled(tenant: Connection, companyCode: String): Boolean

    /** Piezas de la pantalla Importar de esta fuente. */
    fun screen(context: MembresiaImportContext, params: Map<String, String>): List<UiNode>

    /** Una accion de la pantalla (op); devuelve el aviso y los parametros para volver a la pantalla. */
    fun action(context: MembresiaImportContext, op: String, values: Map<String, String>): MembresiaImportReply

    /** Herramientas MCP de la fuente (permiso membresia.import); MembresiaMcpTools las suma. */
    val mcpTools: List<McpTool>
        get() = emptyList()

    /**
     * Trabajo pendiente de la fuente (leer un tablero, aplicar filas): el Core no programa los jobs de las extensiones,
     * asi que el modulo llama esto cada minuto por cada empresa que la tenga activa. Debe terminar dentro de [budget].
     */
    fun runPending(job: ModuleJobContext, budget: java.time.Duration) {}
}

class MembresiaImportContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val permissions: ModulePermissions,
    val author: String,
    val today: LocalDate,
)

data class MembresiaImportReply(val notice: String, val error: Boolean = false, val params: Map<String, String> = emptyMap())

object MembresiaImportSources {
    @Volatile
    private var sources: List<MembresiaImportSource> = emptyList()

    /** Guarda las extensiones que implementan [MembresiaImportSource]. */
    fun link(extensions: List<ServerExtension>) {
        sources = extensions.filterIsInstance<MembresiaImportSource>()
    }

    val all: List<MembresiaImportSource>
        get() = sources

    /** Fuentes activadas en la empresa; una que falla al responder se omite. */
    fun enabled(tenant: Connection, companyCode: String): List<MembresiaImportSource> =
        sources.filter { source -> runCatching { source.enabled(tenant, companyCode) }.getOrDefault(false) }

    fun find(id: String): MembresiaImportSource? = sources.firstOrNull { it.sourceId == id }
}

data class ImportedVisit(val key: String, val date: LocalDate, val guestName: String? = null)

data class ImportedMember(
    val key: String,
    val name: String,
    val email: String = "",
    val phone: String = "",
    /** Etiqueta -> valor (Documento, Fecha de nacimiento, Contacto de emergencia, País): van a campos personalizados de Clientes. */
    val customerFields: Map<String, String> = emptyMap(),
    val planName: String,
    val startsOn: LocalDate?,
    val endsOn: LocalDate?,
    val amount: BigDecimal?,
    val guestPasses: Int? = null,
    val source: String = "",
    val hostName: String = "",
    val unit: String = "",
    val notes: String = "",
    val cancelled: Boolean = false,
    val visits: List<ImportedVisit> = emptyList(),
)

data class ImportPlanProposal(val name: String, val price: BigDecimal, val durationDays: Int, val guestPasses: Int)

data class ImportSummary(
    val members: Int,
    val newPlans: List<ImportPlanProposal>,
    val visits: Int,
    val customerFields: List<String>,
    val problems: List<String>,
)

sealed class ImportOutcome {
    data class Created(val membershipId: UUID) : ImportOutcome()
    data class Updated(val membershipId: UUID) : ImportOutcome()
    data class Skipped(val reason: String) : ImportOutcome()
}

/** Reglas para aplicar filas importadas: planes, campos de Clientes, membresia y visitas; idempotente por import_key. */
object MembresiaImportApply {
    private val log = LoggerFactory.getLogger(MembresiaImportApply::class.java)
    private const val MaxProblemsListed = 50
    private const val MaxFieldValueLength = 500
    private val fieldKeyPattern = Regex("^[a-z][a-z0-9_]{0,62}$")

    // ------------------------------------------------------------ puras

    /** Planes que faltan: uno por cada nombre de plan de las filas que no existe (sin distinguir mayusculas). */
    fun proposePlans(rows: List<ImportedMember>, existing: List<Plan>): List<ImportPlanProposal> {
        val known = existing.map { normalizePlanName(it.name).lowercase() }.toSet()
        return rows.filter { it.planName.isNotBlank() }
            .groupBy { normalizePlanName(it.planName).lowercase() }
            .filterKeys { it !in known }
            .map { (_, group) ->
                val prices = group.mapNotNull { it.amount }.map { it.setScale(2, RoundingMode.HALF_UP) }.filter { MembresiaRules.validAmount(it) }
                val durations = group.mapNotNull { row ->
                    val start = row.startsOn
                    val end = row.endsOn
                    if (start != null && end != null && !end.isBefore(start)) java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1 else null
                }
                ImportPlanProposal(
                    name = normalizePlanName(group.first().planName),
                    price = mostCommon(prices, lowestOnTie = true) ?: BigDecimal.ZERO.setScale(2),
                    durationDays = (median(durations) ?: 30L).coerceIn(1L, MembresiaRules.MaxDurationDays.toLong()).toInt(),
                    guestPasses = (mostCommon(group.mapNotNull { it.guestPasses }, lowestOnTie = false) ?: 0).coerceIn(0, MembresiaRules.MaxGuestPasses),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /** Resumen previo a aplicar: conteos, planes que se crean, campos de Clientes y problemas por fila. */
    fun summary(rows: List<ImportedMember>, existing: List<Plan>): ImportSummary {
        val problems = rows.flatMap { row ->
            val who = row.name.trim().ifEmpty { "Fila ${row.key}" }
            buildList {
                if (row.name.isBlank()) add("$who: sin nombre.")
                if (row.planName.isBlank()) add("$who: sin plan.")
                if (row.startsOn == null || row.endsOn == null) add("$who: sin fechas de inicio y fin.")
                else if (row.endsOn.isBefore(row.startsOn)) add("$who: el fin es anterior al inicio.")
            }
        }
        val listed = if (problems.size > MaxProblemsListed) problems.take(MaxProblemsListed) + "y ${problems.size - MaxProblemsListed} problemas más." else problems
        return ImportSummary(
            members = rows.size,
            newPlans = proposePlans(rows, existing),
            visits = rows.sumOf { it.visits.size },
            customerFields = rows.flatMap { row -> row.customerFields.filterValues { it.isNotBlank() }.keys }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted(),
            problems = listed,
        )
    }

    /** Clave de campo de Clientes para una etiqueta: minusculas, sin acentos, a-z0-9 y guion bajo; cumple ^[a-z][a-z0-9_]{0,62}$. */
    fun fieldKeyFor(label: String): String {
        val plain = Normalizer.normalize(label.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        val slug = plain.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val key = when {
            slug.isEmpty() -> "campo"
            slug[0].isDigit() -> "c_$slug"
            else -> slug
        }.take(63).trimEnd('_')
        return key.ifEmpty { "campo" }
    }

    /** Tipo del campo de Clientes: fecha para "nacimiento" o "birth"; el resto texto. */
    fun fieldTypeFor(label: String): String {
        val plain = Normalizer.normalize(label, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        return if ("nacimiento" in plain || "birth" in plain) "date" else "text"
    }

    private fun <T : Comparable<T>> mostCommon(values: List<T>, lowestOnTie: Boolean): T? {
        if (values.isEmpty()) return null
        val counts = values.groupingBy { it }.eachCount()
        val top = counts.values.max()
        val tied = counts.filterValues { it == top }.keys
        return if (lowestOnTie) tied.min() else tied.max()
    }

    // Mediana de dias; en cantidad par, el promedio de los dos centrales redondeado hacia abajo.
    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    // ------------------------------------------------------------ con base de datos

    /** Crea los planes propuestos que falten. Devuelve todos los planes (existentes y nuevos) por nombre normalizado en minusculas. */
    fun ensurePlans(tenant: Connection, proposals: List<ImportPlanProposal>): Map<String, Plan> {
        val byName = MembresiaRepository.plans(tenant, includeInactive = true).associateBy { planKey(it.name) }.toMutableMap()
        proposals.forEach { proposal ->
            val key = planKey(proposal.name)
            if (key in byName) return@forEach
            val created = try {
                MembresiaRepository.savePlan(
                    tenant,
                    null,
                    PlanInput(normalizePlanName(proposal.name), "Plan creado al importar.", proposal.price, proposal.durationDays, null, proposal.guestPasses, true, byName.size),
                )
            } catch (failure: MembresiaValidationException) {
                // Otro proceso lo creo a la vez: se relee.
                MembresiaRepository.plans(tenant, includeInactive = true).firstOrNull { planKey(it.name) == key } ?: throw failure
            }
            byName[key] = created
        }
        return byName
    }

    /**
     * Crea en Clientes (customer_field_definitions) los campos personalizados que falten y devuelve etiqueta -> field_key.
     * Reutiliza el campo que ya tenga esa etiqueta o esa clave solo si es compatible ([compatibleField]); si no, esa etiqueta
     * se omite y queda en [CustomerFieldsResult.skipped] (no se escribe ese campo). Los nuevos van al final (display_order); se relee.
     */
    fun ensureCustomerFields(tenant: Connection, labels: Collection<String>): CustomerFieldsResult {
        val wanted = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return CustomerFieldsResult(emptyMap(), emptyList())
        fun read(): List<FieldDefinition> = tenant.prepareStatement("SELECT field_key, label, field_type, is_active FROM customer_field_definitions").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(FieldDefinition(rows.getString(1), rows.getString(2), rows.getString(3), rows.getBoolean(4))) } }
        }
        fun find(definitions: List<FieldDefinition>, label: String): FieldDefinition? =
            definitions.firstOrNull { it.label.trim().equals(label, ignoreCase = true) } ?: definitions.firstOrNull { it.key == fieldKeyFor(label) }

        val before = read()
        wanted.filter { find(before, it) == null }.forEach { label ->
            val type = fieldTypeFor(label)
            tenant.prepareStatement(
                """
                INSERT INTO customer_field_definitions (id, field_key, label, field_type, control_type, is_required, display_order)
                VALUES (?, ?, ?, ?, ?, FALSE, (SELECT COALESCE(MAX(display_order), 0) + 1 FROM customer_field_definitions))
                ON CONFLICT DO NOTHING
                """.trimIndent()
            ).use { insert ->
                insert.setObject(1, UUID.randomUUID())
                insert.setString(2, fieldKeyFor(label))
                insert.setString(3, label.take(200))
                insert.setString(4, type)
                insert.setString(5, type)
                insert.executeUpdate()
            }
        }
        val after = read()
        val keys = LinkedHashMap<String, String>()
        val skipped = mutableListOf<String>()
        wanted.forEach { label ->
            val definition = find(after, label)
            if (definition != null && fieldKeyPattern.matches(definition.key) && compatibleField(definition.active, definition.type, fieldTypeFor(label))) {
                keys[label] = definition.key
            } else {
                skipped += label
            }
        }
        return CustomerFieldsResult(keys, skipped)
    }

    /** Un campo existente sirve si esta activo y es del tipo pedido o de texto libre (text o textarea). */
    fun compatibleField(active: Boolean, existingType: String, wantedType: String): Boolean =
        active && (existingType == wantedType || existingType == "text" || existingType == "textarea")

    /** Nombre de plan normalizado: sin espacios en los bordes y de hasta 80 caracteres. */
    fun normalizePlanName(name: String): String = name.trim().take(MembresiaRules.MaxNameLength)

    private fun planKey(name: String): String = normalizePlanName(name).lowercase()

    /**
     * Aplica una fila en una transaccion, idempotente por import_key; un candado por clave evita carreras entre corridas.
     * - Si la membresia YA existe (misma import_key) no se cambia nada de ella (estado, plan, monto, fechas, notas, unidad, anfitrion):
     *   solo se agregan las visitas nuevas y devuelve [ImportOutcome.Updated]; asi reimportar nunca reactiva una cancelada
     *   ni pisa ediciones manuales. Si su cliente ya no es visible, se omite.
     * - Si es nueva: el cliente es el visible con el mismo correo (o se crea), se rellenan solo los campos de Clientes vacios
     *   (lo existente gana), se inserta la membresia y sus visitas.
     * Tras confirmar, cada efecto (customerChanged, historial membresia_alta, pase del hotspot) va en su propio try/catch:
     * su fallo se registra y no convierte la fila en fallida.
     */
    fun apply(
        access: ModuleAccessService,
        tenant: Connection,
        row: ImportedMember,
        plans: Map<String, Plan>,
        fieldKeys: Map<String, String>,
        author: String,
        today: LocalDate,
    ): ImportOutcome {
        val name = row.name.trim()
        val startsOn = row.startsOn
        val endsOn = row.endsOn
        if (name.isEmpty()) return ImportOutcome.Skipped("Sin nombre.")
        if (startsOn == null || endsOn == null) return ImportOutcome.Skipped("Sin fechas de inicio y fin.")
        if (row.key.isBlank() || row.key.length > 120) return ImportOutcome.Skipped("Clave de importación no válida.")
        val plan = plans[planKey(row.planName)] ?: return ImportOutcome.Skipped("El plan \"${normalizePlanName(row.planName)}\" no existe.")
        val dateErrors = MembresiaRules.validateDates(startsOn, endsOn, today, checkStart = false)
        if (dateErrors.isNotEmpty()) return ImportOutcome.Skipped(dateErrors.first())
        val amount = (row.amount ?: plan.price).setScale(2, RoundingMode.HALF_UP)
        if (!MembresiaRules.validAmount(amount)) return ImportOutcome.Skipped("Monto fuera de rango.")

        val applied = try {
            MembresiaRepository.inTransaction(tenant) { write(tenant, row, name, plan, fieldKeys, amount, startsOn, endsOn, author, today) }
        } catch (failure: MembresiaValidationException) {
            return ImportOutcome.Skipped(failure.errors.firstOrNull() ?: "Fila no válida.")
        }
        if (applied.customerChanged) effect("avisar a Clientes del cliente ${applied.customerId}") { access.customerChanged(tenant, applied.customerId) }
        if (applied.isNew) {
            val membership = MembresiaRepository.membershipForJob(tenant, applied.membershipId)
            if (membership == null) {
                log.warn("La membresía importada {} no se pudo releer.", applied.membershipId)
            } else {
                effect("escribir el historial de la membresía ${applied.membershipId}") { MembresiaHistory.created(access, tenant, membership, author) }
                effect("sincronizar el pase del hotspot de ${applied.membershipId}") { MembresiaHotspot.syncPass(tenant, membership, plan.wifiAccess) }
            }
        }
        return if (applied.isNew) ImportOutcome.Created(applied.membershipId) else ImportOutcome.Updated(applied.membershipId)
    }

    // Efecto posterior al commit: su fallo se registra y no rompe la fila; la cancelacion se relanza.
    private fun effect(description: String, action: () -> Unit) {
        try {
            action()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Exception) {
            log.warn("Importación: no se pudo {}: {}", description, failure.message)
        }
    }

    private class Applied(val membershipId: UUID, val customerId: UUID, val isNew: Boolean, val customerChanged: Boolean)

    private fun write(
        tenant: Connection,
        row: ImportedMember,
        name: String,
        plan: Plan,
        fieldKeys: Map<String, String>,
        amount: BigDecimal,
        startsOn: LocalDate,
        endsOn: LocalDate,
        author: String,
        today: LocalDate,
    ): Applied {
        val importKey = row.key.trim()
        // Serializa corridas simultaneas de la misma fila.
        tenant.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { lock ->
            lock.setString(1, "membresia-import:$importKey")
            lock.executeQuery().use { it.next() }
        }
        val existing = tenant.prepareStatement(
            """
            SELECT m.id, m.customer_id, ($Visible) AS visible
            FROM membresia_memberships m JOIN customers c ON c.id = m.customer_id
            WHERE m.import_key = ? FOR UPDATE OF m
            """.trimIndent()
        ).use { query ->
            query.setString(1, importKey)
            query.executeQuery().use { rows ->
                if (rows.next()) Triple(rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getBoolean(3)) else null
            }
        }
        if (existing != null) {
            if (!existing.third) throw MembresiaValidationException(listOf("El cliente ya no está disponible."))
            // Ya importada: no se toca nada de la membresia; solo visitas nuevas.
            importVisits(tenant, existing.first, existing.second, importKey, row.visits, author)
            return Applied(existing.first, existing.second, isNew = false, customerChanged = false)
        }

        if (row.email.isNotBlank()) {
            tenant.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { lock ->
                lock.setString(1, row.email.trim().lowercase())
                lock.executeQuery().use { it.next() }
            }
        }
        val lookup = MembresiaRepository.findOrCreateCustomer(tenant, NewCustomerInput(name, row.email, row.phone))
        val customerId = lookup.customerId
        val fieldsChanged = fillEmptyCustomerFields(tenant, customerId, row.customerFields, fieldKeys)

        val status = if (row.cancelled) StoredStatus.Cancelada else MembresiaRules.initialStatus(startsOn, endsOn, today)
        val guestTotal = (row.guestPasses ?: plan.guestPasses).coerceIn(0, MembresiaRules.MaxGuestPasses)
        val currency = MembresiaRepository.settings(tenant).currency
        val membershipId = tenant.prepareStatement(
            """
            INSERT INTO membresia_memberships (customer_id, plan_id, plan_name, starts_on, ends_on, amount, currency, status, source,
                host_name, unit, notes, included_visits, guest_passes_total, cancelled_at, cancel_reason, created_by, import_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END, ?, ?, ?)
            RETURNING id
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, customerId)
            insert.setObject(2, plan.id)
            insert.setString(3, plan.name)
            insert.setObject(4, startsOn)
            insert.setObject(5, endsOn)
            insert.setBigDecimal(6, amount)
            insert.setString(7, currency)
            insert.setString(8, status)
            insert.setString(9, row.source.trim().take(MembresiaRules.MaxSourceLength))
            insert.setString(10, row.hostName.trim().take(MembresiaRules.MaxHostNameLength))
            insert.setString(11, row.unit.trim().take(MembresiaRules.MaxUnitLength))
            insert.setString(12, row.notes.trim().take(MembresiaRules.MaxNotesLength))
            insert.setObject(13, plan.includedVisits)
            insert.setInt(14, guestTotal)
            insert.setBoolean(15, row.cancelled)
            insert.setString(16, if (row.cancelled) "Importada como cancelada." else "")
            insert.setString(17, author.take(160))
            insert.setString(18, importKey)
            insert.executeQuery().use { rows -> rows.next(); rows.getObject(1, UUID::class.java) }
        }
        importVisits(tenant, membershipId, customerId, importKey, row.visits, author)
        return Applied(membershipId, customerId, isNew = true, customerChanged = lookup.created || fieldsChanged)
    }

    // Rellena solo los campos de Clientes vacios o ausentes (lo existente gana) sin borrar los demas. True si escribio algo.
    private fun fillEmptyCustomerFields(tenant: Connection, customerId: UUID, fields: Map<String, String>, fieldKeys: Map<String, String>): Boolean {
        val wanted = fields.mapNotNull { (label, value) ->
            val key = fieldKeys[label.trim()] ?: return@mapNotNull null
            value.trim().takeIf { it.isNotEmpty() }?.let { key to it.take(MaxFieldValueLength) }
        }.toMap()
        if (wanted.isEmpty()) return false
        val current = tenant.prepareStatement("SELECT flexible_attributes::text FROM customers WHERE id = ? FOR UPDATE").use { query ->
            query.setObject(1, customerId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: return false
        val attributes = runCatching { Json.parseToJsonElement(current) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val toWrite = wanted.filter { (key, _) -> isBlank(attributes[key]) }
        if (toWrite.isEmpty()) return false
        tenant.prepareStatement("UPDATE customers SET flexible_attributes = flexible_attributes || CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setString(1, JsonObject(toWrite.mapValues { JsonPrimitive(it.value) }).toString())
            update.setObject(2, customerId)
            update.executeUpdate()
        }
        return true
    }

    private fun isBlank(element: JsonElement?): Boolean = when (element) {
        null, is JsonNull -> true
        is JsonPrimitive -> element.content.isBlank()
        else -> false
    }

    // Visitas por import_key (y por el indice diario) en un lote: repetir la importacion no duplica. Luego recalcula los contadores.
    private fun importVisits(tenant: Connection, membershipId: UUID, customerId: UUID, rowKey: String, visits: List<ImportedVisit>, author: String) {
        val zone = ZoneId.systemDefault()
        if (visits.isNotEmpty()) {
            tenant.prepareStatement(
                """
                INSERT INTO membresia_visits (membership_id, customer_id, kind, guest_name, visit_on, visited_at, recorded_by, import_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent()
            ).use { insert ->
                visits.forEach { visit ->
                    val guest = visit.guestName?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
                    insert.setObject(1, membershipId)
                    insert.setObject(2, customerId)
                    insert.setString(3, if (guest == null) "miembro" else "invitado")
                    insert.setString(4, guest)
                    insert.setObject(5, visit.date)
                    insert.setObject(6, OffsetDateTime.ofInstant(visit.date.atStartOfDay(zone).toInstant(), zone))
                    insert.setString(7, author.take(160))
                    insert.setString(8, "$rowKey:${visit.key}".take(160))
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
        // guest_passes_total sube si las visitas de invitado importadas superan el cupo (respeta el CHECK used <= total).
        tenant.prepareStatement(
            """
            UPDATE membresia_memberships SET
                visits_used = (SELECT count(*) FROM membresia_visits WHERE membership_id = ? AND kind = 'miembro'),
                guest_passes_used = (SELECT count(*) FROM membresia_visits WHERE membership_id = ? AND kind = 'invitado'),
                guest_passes_total = GREATEST(guest_passes_total, (SELECT count(*) FROM membresia_visits WHERE membership_id = ? AND kind = 'invitado'))
            WHERE id = ?
            """.trimIndent()
        ).use { update ->
            repeat(4) { update.setObject(it + 1, membershipId) }
            update.executeUpdate()
        }
    }

    private class FieldDefinition(val key: String, val label: String, val type: String, val active: Boolean)

    // Misma regla de visibilidad de Clientes que usa el repositorio.
    private const val Visible = "(c.origin_company_code = '' OR c.shared) AND c.status <> 'archived'"
}

/** Resultado de [MembresiaImportApply.ensureCustomerFields]: etiqueta -> field_key, y las etiquetas omitidas por no haber un campo compatible. */
data class CustomerFieldsResult(val keys: Map<String, String>, val skipped: List<String>)
