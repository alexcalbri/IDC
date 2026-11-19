package com.ideasdeveloper.idc.modules.propietarios

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
import java.util.UUID

/**
 * Un lugar desde donde se pueden importar datos (por ejemplo Monday). Las extensiones del modulo lo implementan: Propietarios lista
 * las activas en la pantalla "Importar", corre su trabajo pendiente en el trabajo `import-runner` y ofrece sus herramientas a los agentes.
 */
interface PropietariosImportSource {
    /** El id de la extension, por ejemplo "propietarios_monday". */
    val sourceId: String

    /** Lo que ve el usuario, por ejemplo "Monday". */
    val title: String

    fun enabled(tenant: Connection, companyCode: String): Boolean

    /** El cuerpo de la pantalla de importacion de esta fuente ([params] son los parametros de la pantalla). */
    fun screen(context: PropietariosImportContext, params: Map<String, String>): List<UiNode>

    /** Una operacion de la pantalla (POST); [values] es lo que envio el formulario. */
    fun action(context: PropietariosImportContext, op: String, values: Map<String, String>): PropietariosImportReply

    /** Herramientas para agentes (permiso propietarios.import); Propietarios las suma a las suyas. */
    val mcpTools: List<McpTool> get() = emptyList()

    /** Adelanta la lectura y la aplicacion y devuelve las filas procesadas. Corto, idempotente, errores al registro. */
    fun runPending(context: PropietariosImportJobContext, budget: Duration): Int
}

class PropietariosImportContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val permissions: ModulePermissions,
    val author: String,
    val today: LocalDate,
)

class PropietariosImportJobContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val files: ModuleFiles,
    val today: LocalDate,
)

/** La respuesta a una accion: el aviso que se muestra, si es un error y los parametros de la pantalla que sigue. */
data class PropietariosImportReply(val notice: String, val error: Boolean = false, val params: Map<String, String> = emptyMap())

object PropietariosImportSources {
    private val log = LoggerFactory.getLogger(PropietariosImportSources::class.java)

    @Volatile
    private var linked: List<PropietariosImportSource> = emptyList()

    /** Se llama una vez al arrancar con las extensiones del modulo; conserva las que son fuentes de importacion. */
    fun link(extensions: List<ServerExtension>) {
        linked = extensions.filterIsInstance<PropietariosImportSource>()
    }

    val all: List<PropietariosImportSource> get() = linked

    /** Las fuentes que la empresa tiene activas. Una fuente que no responde cuenta como desactivada. */
    fun enabled(tenant: Connection, companyCode: String): List<PropietariosImportSource> = all.filter { source ->
        try {
            source.enabled(tenant, companyCode)
        } catch (stop: InterruptedException) {
            Thread.currentThread().interrupt()
            throw stop
        } catch (stop: java.util.concurrent.CancellationException) {
            throw stop
        } catch (failure: Exception) {
            log.warn("No se pudo comprobar la fuente de importación {} ({}).", source.sourceId, failure.javaClass.simpleName)
            false
        }
    }

    fun find(id: String): PropietariosImportSource? = all.firstOrNull { it.sourceId == id }

    /** Corre las fuentes activas una tras otra, cada una con una parte igual de lo que queda de [budget]. */
    fun runPending(context: PropietariosImportJobContext, budget: Duration): Int {
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

/** Un propietario de una fila importada. El correo, el telefono, el porcentaje y la fecha de inicio son opcionales. */
data class ImportedOwner(
    val name: String,
    val email: String = "",
    val phone: String = "",
    val sharePercent: BigDecimal? = null,
    val startsOn: LocalDate? = null,
)

/** Un lote de la fuente. [key] es el id del elemento en la fuente: es la import_key del lote. */
data class ImportedLot(
    val key: String,
    val lotNumber: String,
    val developmentName: String? = null,
    val registerNumber: String = "",
    val propertyName: String = "",
    val propertyType: String = "",
    val status: LotStatus? = null,
    val areaM2: BigDecimal? = null,
    val latitude: BigDecimal? = null,
    val longitude: BigDecimal? = null,
    val mapUrl: String = "",
    val notes: String = "",
    val owners: List<ImportedOwner> = emptyList(),
)

/** Lo que haria una importacion, para la vista previa. */
data class LotImportSummary(
    val lots: Int,
    val developments: List<String>,
    val owners: Int,
    val ownersWithoutEmail: List<String>,
    val totalAreaM2: BigDecimal,
    val duplicateLots: List<String>,
    val problems: List<String>,
)

sealed class LotImportOutcome {
    data class Created(val lotId: UUID) : LotImportOutcome()
    data class Updated(val lotId: UUID) : LotImportOutcome()
    data class Skipped(val reason: String) : LotImportOutcome()
}

/** Escribe en Propietarios las filas importadas. Cada fila es su propia transaccion, idempotente por import_key. */
object PropietariosImportApply {
    private val log = LoggerFactory.getLogger(PropietariosImportApply::class.java)
    private const val MaxOwnersPerLot = 20
    private const val MaxKey = 200
    private const val MaxPhone = 40
    private const val MaxEmail = 254
    private const val ImportedCustomerType = "propietarios"

    // ---- reglas puras ----

    /** La identidad de un propietario dentro de una fila: su correo (sin mayusculas) o, sin correo, su nombre (sin mayusculas). */
    fun ownerIdentity(owner: ImportedOwner): String = owner.email.trim().lowercase().ifEmpty { owner.name.trim().lowercase() }

    /** La import_key de la titularidad de un propietario en una fila: `<clave de la fila>:owner:<correo|nombre>`, acortada para caber en 200 caracteres. */
    fun ownerKey(rowKey: String, identity: String): String {
        val full = "${rowKey.trim()}:owner:${identity.trim().lowercase()}"
        if (full.length <= MaxKey) return full
        return full.take(MaxKey - 10) + "#" + Integer.toHexString(full.hashCode())
    }

    /** La clave del desarrollo que se crea por nombre: `name:<nombre en minusculas>`, acortada para caber en 200 caracteres. */
    fun developmentKey(name: String): String = "name:" + PropietariosRules.clip(name.trim().lowercase(), MaxKey - 5)

    /** Un correo utilizable: con arroba, sin espacios y de hasta 254 caracteres. */
    fun validEmail(email: String): Boolean = email.isNotEmpty() && email.length <= MaxEmail && email.contains('@') && email.none { it.isWhitespace() }

    /** Por que una fila se omite sin tocar la base de datos (sin identificador, identificador demasiado largo o sin numero de lote); null si se puede aplicar. */
    fun skipReason(row: ImportedLot): String? {
        val key = row.key.trim()
        return when {
            key.isEmpty() -> "El elemento no tiene identificador."
            key.length > MaxKey -> "El identificador del elemento $key es demasiado largo."
            row.lotNumber.isBlank() -> "El elemento $key no tiene número de lote."
            else -> null
        }
    }

    /** Que haria una importacion, para la vista previa. Pura. */
    fun summary(rows: List<ImportedLot>): LotImportSummary {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val valid = mutableListOf<ImportedLot>()
        rows.forEach { row ->
            val key = row.key.trim()
            when {
                key.isEmpty() -> problems += "Un elemento no tiene identificador y se omitirá."
                row.lotNumber.isBlank() -> problems += "El elemento $key no tiene número de lote y se omitirá."
                !seen.add(key) -> problems += "El elemento $key aparece más de una vez."
                else -> valid += row
            }
        }
        val developments = valid.mapNotNull { it.developmentName?.trim()?.takeIf { name -> name.isNotEmpty() } }.distinctBy { it.lowercase() }
        val owners = linkedMapOf<String, ImportedOwner>()
        valid.forEach { row ->
            row.owners.forEach { owner ->
                val identity = ownerIdentity(owner)
                if (identity.isNotEmpty()) owners.putIfAbsent(identity, owner)
            }
        }
        val withoutEmail = owners.values.filter { !validEmail(it.email.trim()) }.map { it.name.trim().ifEmpty { it.email.trim() } }.distinctBy { it.lowercase() }
        var totalArea = BigDecimal.ZERO
        valid.forEach { row ->
            val label = row.lotNumber.trim()
            val area = row.areaM2
            if (area != null) {
                if (area.signum() < 0 || area.compareTo(PropietariosRules.MaxArea) > 0) {
                    problems += "El lote $label tiene un área fuera de rango y se importará sin área."
                } else {
                    totalArea += area
                }
            }
            val shares = row.owners.mapNotNull { it.sharePercent }
            if (shares.any { !PropietariosRules.validShare(it) } || shares.fold(BigDecimal.ZERO) { sum, share -> sum + share } > BigDecimal(100)) {
                problems += "Los porcentajes del lote $label no son válidos o suman más de 100 %: se importará sin porcentajes."
            }
            if (row.owners.size > MaxOwnersPerLot) problems += "El lote $label tiene más de $MaxOwnersPerLot titulares: solo se importarán los primeros $MaxOwnersPerLot."
        }
        val duplicates = valid.groupBy { (it.developmentName?.trim().orEmpty().lowercase()) to it.lotNumber.trim().lowercase() }
            .filterValues { it.size > 1 }
            .values.map { group ->
                val first = group.first()
                val development = first.developmentName?.trim().orEmpty()
                if (development.isEmpty()) first.lotNumber.trim() else "$development / ${first.lotNumber.trim()}"
            }
        return LotImportSummary(valid.size, developments, owners.size, withoutEmail, totalArea, duplicates, problems)
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

    // Serializa a quienes escriben lo mismo a la vez (dura hasta el fin de la transaccion).
    private fun lock(tenant: Connection, name: String) {
        tenant.rows("SELECT pg_advisory_xact_lock(hashtext(?)) AS locked", listOf(name)) { 1 }
    }

    private const val Visible = "(c.origin_company_code = '' OR c.shared) AND c.status <> 'archived'"

    /**
     * El desarrollo donde importar: [targetDevelopmentId] si viene (debe existir y no estar archivado); si no, el que ya tiene [importKey];
     * si no, el sin archivar con el mismo nombre (se le pone la clave); si no, uno nuevo llamado [name].
     */
    fun ensureDevelopment(tenant: Connection, importKey: String, name: String, targetDevelopmentId: UUID?, actorId: UUID?): UUID {
        if (targetDevelopmentId != null) {
            val development = PropietariosRepository.getDevelopment(tenant, targetDevelopmentId)
                ?: throw PropietariosValidationException("El desarrollo de destino ya no existe.")
            if (development.archived) throw PropietariosValidationException("El desarrollo de destino está archivado.")
            return development.id
        }
        val key = importKey.trim()
        if (key.isEmpty() || key.length > MaxKey) throw PropietariosValidationException("La clave de importación del desarrollo no es válida.")
        val clean = PropietariosRules.clip(name.trim().ifEmpty { "Importado" }, PropietariosRules.MaxName)
        return PropietariosRepository.transaction(tenant) {
            lock(tenant, "propietarios-development:$key")
            fun byKey() = tenant.rows("SELECT id FROM propietarios_developments WHERE import_key = ?", listOf(key)) { it.getObject("id", UUID::class.java) }.firstOrNull()
            val existing = byKey()
            if (existing != null) {
                existing
            } else {
                val named = PropietariosRepository.findDevelopmentByName(tenant, clean)
                if (named != null) {
                    tenant.exec("UPDATE propietarios_developments SET import_key = ? WHERE id = ? AND import_key IS NULL", listOf(key, named.id))
                    named.id
                } else {
                    tenant.exec(
                        "INSERT INTO propietarios_developments (id, name, created_by, import_key) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        listOf(UUID.randomUUID(), clean, actorId, key),
                    )
                    byKey() ?: throw PropietariosValidationException("No se pudo crear el desarrollo.")
                }
            }
        }
    }

    private class Applied(
        val lotId: UUID,
        val created: Boolean,
        val createdCustomers: List<UUID>,
    )

    /**
     * Crea o actualiza el lote de [row] en [developmentId] (una transaccion por fila, mismo resultado al repetir) y le agrega los
     * propietarios que aun no tiene. Si la fila trae [ImportedLot.developmentName] se usa (o se crea) el desarrollo con ese nombre.
     * Una fila sin identificador o sin numero de lote se omite. Tras confirmar, el aviso a Clientes va en su propio try/catch: su fallo se registra y no convierte la fila en fallida.
     */
    fun apply(tenant: Connection, access: ModuleAccessService, developmentId: UUID, row: ImportedLot, author: String, actorId: UUID?, today: LocalDate): LotImportOutcome {
        skipReason(row)?.let { return LotImportOutcome.Skipped(it) }
        val key = row.key.trim()
        val lotNumber = PropietariosRules.clip(row.lotNumber.trim(), PropietariosRules.MaxLotNumber)
        val applied = try {
            PropietariosRepository.transaction(tenant) { write(tenant, developmentId, row, key, lotNumber, author, actorId, today) }
        } catch (failure: PropietariosValidationException) {
            return LotImportOutcome.Skipped(failure.message.orEmpty())
        }
        applied.createdCustomers.forEach { customerId ->
            effect("avisar a Clientes del cliente $customerId") { access.customerChanged(tenant, customerId) }
        }
        return if (applied.created) LotImportOutcome.Created(applied.lotId) else LotImportOutcome.Updated(applied.lotId)
    }

    // Efecto posterior al commit: su fallo se registra y no rompe la fila; la cancelacion se relanza.
    private fun effect(description: String, action: () -> Unit) {
        try {
            action()
        } catch (cancellation: java.util.concurrent.CancellationException) {
            throw cancellation
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Exception) {
            log.warn("Importación: no se pudo {}: {}", description, failure.message)
        }
    }

    private class Candidate(val identity: String, val name: String, val email: String, val phone: String, val share: BigDecimal?, val startsOn: LocalDate?, val ownerKey: String)

    private fun write(tenant: Connection, developmentIdArg: UUID, row: ImportedLot, key: String, lotNumber: String, author: String, actorId: UUID?, today: LocalDate): Applied {
        lock(tenant, "propietarios-import:$key")
        val developmentName = row.developmentName?.trim()?.takeIf { it.isNotEmpty() }
        val developmentId = if (developmentName != null) {
            ensureDevelopment(tenant, developmentKey(developmentName), developmentName, null, actorId)
        } else {
            ensureDevelopment(tenant, "", "", developmentIdArg, actorId)
        }
        val development = PropietariosRepository.getDevelopment(tenant, developmentId) ?: throw PropietariosValidationException("El desarrollo ya no existe.")

        // Recortes en lugar de fallos: lo que no cabe o no es valido se omite o se acorta.
        val register = PropietariosRules.clip(row.registerNumber.trim(), PropietariosRules.MaxRegister)
        val propertyName = PropietariosRules.clip(row.propertyName.trim(), PropietariosRules.MaxName)
        val propertyType = PropietariosRules.clip(row.propertyType.trim(), PropietariosRules.MaxPropertyType)
        val area = row.areaM2?.takeIf { it.signum() >= 0 && it.compareTo(PropietariosRules.MaxArea) <= 0 }?.setScale(2, RoundingMode.HALF_UP)
        val coordinates = if (row.latitude != null && row.longitude != null && row.latitude.abs() <= BigDecimal(90) && row.longitude.abs() <= BigDecimal(180)) {
            row.latitude.setScale(6, RoundingMode.HALF_UP) to row.longitude.setScale(6, RoundingMode.HALF_UP)
        } else {
            null
        }
        val mapUrl = row.mapUrl.trim().takeIf { PropietariosRules.validateMap(it, null, null).isEmpty() }.orEmpty()
        val notes = PropietariosRules.clip(row.notes.trim(), PropietariosRules.MaxNotes)

        val candidates = linkedMapOf<String, Candidate>()
        row.owners.take(MaxOwnersPerLot).forEach { owner ->
            val identity = ownerIdentity(owner)
            if (identity.isEmpty() || candidates.containsKey(identity)) return@forEach
            val email = owner.email.trim().takeIf { validEmail(it) }.orEmpty()
            val name = PropietariosRules.clip(owner.name.trim().ifEmpty { owner.email.trim() }, PropietariosRules.MaxName)
            candidates[identity] = Candidate(identity, name, email, PropietariosRules.clip(owner.phone.trim(), MaxPhone), owner.sharePercent, owner.startsOn, ownerKey(key, identity))
        }

        // El lote: por import_key; si no, se adopta el del mismo numero que no vino de otra importacion; si no, se crea.
        val byKey = tenant.rows("SELECT id, archived_at FROM propietarios_lots WHERE import_key = ? FOR UPDATE", listOf(key)) {
            it.getObject("id", UUID::class.java) to (it.getObject("archived_at") != null)
        }.firstOrNull()
        val adoptable = if (byKey != null) {
            null
        } else {
            tenant.rows(
                "SELECT id, import_key FROM propietarios_lots WHERE development_id = ? AND lower(lot_number) = lower(?) AND archived_at IS NULL FOR UPDATE",
                listOf(developmentId, lotNumber),
            ) { it.getObject("id", UUID::class.java) to it.getString("import_key") }.firstOrNull()
        }
        if (adoptable != null && adoptable.second != null) {
            throw PropietariosValidationException("Ya existe otro lote $lotNumber en ${development.name} que vino de otra importación.")
        }
        val existingId = byKey?.first ?: adoptable?.first
        if (byKey != null && byKey.second) throw PropietariosValidationException("El lote $lotNumber está archivado.")

        val created = existingId == null
        val lotId: UUID
        if (existingId == null) {
            val status = row.status ?: if (candidates.isNotEmpty()) LotStatus.Vendido else LotStatus.Disponible
            lotId = PropietariosRepository.createLot(
                tenant,
                LotInput(developmentId, lotNumber, register, propertyName, propertyType, status, area, coordinates?.first, coordinates?.second, mapUrl, notes),
                actorId,
                importKey = key,
            )
        } else {
            lotId = existingId
            val old = PropietariosRepository.getLot(tenant, lotId) ?: throw PropietariosValidationException("El lote ya no existe.")
            if (adoptable != null) tenant.exec("UPDATE propietarios_lots SET import_key = ? WHERE id = ? AND import_key IS NULL", listOf(key, lotId))
            // Un dato que la fuente trae vacio no borra el que ya tiene el lote.
            PropietariosRepository.updateLot(
                tenant, lotId,
                LotInput(
                    developmentId = old.developmentId,
                    lotNumber = lotNumber,
                    registerNumber = register.ifEmpty { old.registerNumber },
                    propertyName = propertyName.ifEmpty { old.propertyName },
                    propertyType = propertyType.ifEmpty { old.propertyType },
                    status = row.status ?: old.status,
                    areaM2 = area ?: old.areaM2,
                    latitude = coordinates?.first ?: old.latitude,
                    longitude = coordinates?.second ?: old.longitude,
                    mapUrl = mapUrl.ifEmpty { old.mapUrl },
                    notes = notes.ifEmpty { old.notes },
                ),
            )
        }

        // Propietarios: los que ya tienen su titularidad (por clave) no se tocan.
        // Orden fijo por clave de candado: dos importaciones a la vez toman los candados de clientes en el mismo orden (sin interbloqueo).
        val fresh = candidates.values.filter { candidate ->
            tenant.rows("SELECT id FROM propietarios_ownerships WHERE lot_id = ? AND import_key = ?", listOf(lotId, candidate.ownerKey)) { it.getObject("id", UUID::class.java) }.isEmpty()
        }.sortedBy { lockKey(it) }
        val createdCustomers = mutableListOf<UUID>()
        val resolved = fresh.map { candidate -> candidate to findOrCreateCustomer(tenant, candidate, createdCustomers) }
        val assigned = PropietariosRepository.assignedShare(tenant, lotId, today)
        val given = resolved.mapNotNull { it.first.share }
        val dropShares = given.any { !PropietariosRules.validShare(it) } || given.fold(assigned) { sum, share -> sum + share } > BigDecimal(100)
        var started = 0
        resolved.forEach { (candidate, customerId) ->
            val ownershipId = UUID.randomUUID()
            val share = candidate.share?.takeIf { !dropShares }
            val inserted = tenant.exec(
                """
                INSERT INTO propietarios_ownerships (id, lot_id, customer_id, share_percent, starts_on, notes, created_by, import_key)
                VALUES (?, ?, ?, ?, ?, '', ?, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                listOf(ownershipId, lotId, customerId, share, candidate.startsOn, actorId, candidate.ownerKey),
            )
            if (inserted > 0) started++
        }

        val note = if (dropShares && given.isNotEmpty()) " Los porcentajes no eran válidos o sumaban más de 100 %: se importó sin porcentajes." else ""
        if (created) {
            PropietariosRepository.addHistory(tenant, lotId, "importacion", today, "Lote importado con $started titulares.$note", author)
        } else if (started > 0 || note.isNotEmpty()) {
            PropietariosRepository.addHistory(tenant, lotId, "importacion", today, "Importación: $started titulares añadidos.$note", author)
        }
        return Applied(lotId, created, createdCustomers)
    }

    // La clave del candado de un candidato: su correo en minusculas o, sin correo, su identidad.
    private fun lockKey(candidate: Candidate): String =
        if (candidate.email.isNotEmpty()) candidate.email.lowercase() else "propietarios-customer:${candidate.identity}"

    /**
     * El cliente visible con el mismo correo (sin mayusculas) o, sin correo, con el mismo nombre exacto y tipo propietarios; si no hay, se crea
     * (tipo propietarios, activo) y se anota en [createdCustomers] para avisar a Clientes tras confirmar.
     */
    private fun findOrCreateCustomer(tenant: Connection, candidate: Candidate, createdCustomers: MutableList<UUID>): UUID {
        // Mismo candado que usan otros modulos al crear clientes por correo: dos importaciones a la vez no duplican al cliente.
        lock(tenant, lockKey(candidate))
        val existing = if (candidate.email.isNotEmpty()) {
            tenant.rows(
                "SELECT c.id FROM customers c WHERE lower(c.primary_email) = lower(?) AND $Visible ORDER BY c.created_at, c.id LIMIT 1",
                listOf(candidate.email),
            ) { it.getObject("id", UUID::class.java) }.firstOrNull()
        } else {
            tenant.rows(
                "SELECT c.id FROM customers c WHERE lower(c.display_name) = lower(?) AND c.customer_type = ? AND $Visible ORDER BY c.created_at, c.id LIMIT 1",
                listOf(candidate.name, ImportedCustomerType),
            ) { it.getObject("id", UUID::class.java) }.firstOrNull()
        }
        if (existing != null) return existing
        val id = UUID.randomUUID()
        tenant.exec(
            "INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type) VALUES (?, ?, ?, ?, 'active', ?)",
            listOf(id, candidate.name, candidate.email, candidate.phone, ImportedCustomerType),
        )
        createdCustomers += id
        return id
    }

    /**
     * Guarda un archivo de la fuente como adjunto del lote, una vez por [importKey] (false si ya estaba; el contenido no se lee entonces).
     * El lote admite [PropietariosRepository.MaxFilesPerLot] archivos y cada uno [PropietariosRepository.MaxFileBytes].
     */
    fun attachFile(tenant: Connection, files: ModuleFiles, lotId: UUID, importKey: String, name: String, contentType: String, content: InputStream, actorId: UUID?): Boolean {
        val key = importKey.trim().take(MaxKey)
        if (key.isEmpty()) throw PropietariosValidationException("La clave del archivo no es válida.")
        val already = tenant.rows("SELECT 1 AS found FROM propietarios_attachments WHERE lot_id = ? AND import_key = ?", listOf(lotId, key)) { it.getInt("found") }.isNotEmpty()
        if (already) return false
        val total = tenant.rows("SELECT count(*) AS total FROM propietarios_attachments WHERE lot_id = ? AND NOT delete_pending", listOf(lotId)) { it.getInt("total") }.firstOrNull() ?: 0
        if (total >= PropietariosRepository.MaxFilesPerLot) throw PropietariosValidationException("El lote ya tiene el máximo de ${PropietariosRepository.MaxFilesPerLot} archivos.")
        val fileName = PropietariosRepository.cleanFileName(name).ifEmpty { "archivo" }
        val type = contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" }
        val stored = try {
            files.put(fileName, type, content, PropietariosRepository.MaxFileBytes)
        } catch (_: FileTooLargeException) {
            throw PropietariosValidationException("$fileName supera el máximo de ${PropietariosRepository.MaxFileBytes / (1024 * 1024)} MB.")
        }
        try {
            val inserted = tenant.exec(
                """
                INSERT INTO propietarios_attachments (id, lot_id, name, path, content_type, size, backend, ref, uploaded_by, import_key)
                VALUES (?, ?, ?, '', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (lot_id, import_key) WHERE import_key IS NOT NULL DO NOTHING
                """.trimIndent(),
                listOf(UUID.randomUUID(), lotId, fileName, type, stored.size, stored.backend, stored.ref, actorId, key),
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
