package com.ideasdeveloper.idc.modules.propietarios

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiFileRef
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** Una titularidad que empieza o termina en una operacion: su id y el cliente. */
data class OwnershipChange(val ownershipId: UUID, val customerId: UUID)

/** Lo que hizo una operacion sobre los titulares de un lote; con esto se escriben los efectos posteriores a confirmar. */
data class OwnershipResult(
    val lotId: UUID,
    val lotNumber: String,
    val developmentName: String,
    val kind: String,
    val date: LocalDate,
    val started: List<OwnershipChange>,
    val ended: List<OwnershipChange>,
)

/**
 * Reglas del modulo sobre la base de datos: cada operacion es una transaccion y aplica las mismas reglas desde las pantallas y desde
 * los agentes. Los permisos los comprueba quien llama (rutas y herramientas MCP) antes de abrir la conexion.
 */
object PropietariosService {
    private fun missingLot() = PropietariosValidationException("Lote no encontrado.")

    /** Falla con un mensaje para el usuario si no tiene [permission] (las rutas y las herramientas ya lo piden antes; esto es la segunda barrera). */
    fun require(permissions: ModulePermissions, permission: String) {
        if (!permissions.has(permission)) throw PropietariosValidationException("No tienes permiso para esta acción.")
    }

    // ---- desarrollos ----

    fun createDevelopment(tenant: Connection, input: DevelopmentInput, actorId: UUID?): UUID = PropietariosRepository.createDevelopment(tenant, input, actorId)

    fun updateDevelopment(tenant: Connection, id: UUID, input: DevelopmentInput) {
        if (!PropietariosRepository.updateDevelopment(tenant, id, input)) throw PropietariosValidationException("Desarrollo no encontrado.")
    }

    fun archiveDevelopment(tenant: Connection, id: UUID) {
        if (!PropietariosRepository.archiveDevelopment(tenant, id)) throw PropietariosValidationException("Desarrollo no encontrado.")
    }

    // ---- lotes ----

    /** Crea un lote en un desarrollo sin archivar y deja constancia en su historial. */
    fun createLot(tenant: Connection, input: LotInput, author: String, actorId: UUID?, today: LocalDate = PropietariosRepository.today()): UUID =
        PropietariosRepository.transaction(tenant) {
            val development = PropietariosRepository.getDevelopment(tenant, input.developmentId)?.takeIf { !it.archived }
                ?: throw PropietariosValidationException("Desarrollo no encontrado.")
            val id = PropietariosRepository.createLot(tenant, input, actorId)
            PropietariosRepository.addHistory(tenant, id, "edicion", today, "Lote creado en ${development.name}.", author)
            id
        }

    /** Que cambio entre el lote guardado y el formulario, para el historial ("" si nada). Pura. */
    fun lotDiff(old: Lot, new: LotInput): String {
        fun show(value: String) = value.trim().ifEmpty { "—" }
        fun area(value: BigDecimal?) = value?.stripTrailingZeros()?.toPlainString()?.let { "$it m²" } ?: "—"
        val changes = buildList {
            if (old.lotNumber != new.lotNumber.trim()) add("Número de lote: ${show(old.lotNumber)} → ${show(new.lotNumber)}")
            if (old.registerNumber != new.registerNumber.trim()) add("Registro: ${show(old.registerNumber)} → ${show(new.registerNumber)}")
            if (old.propertyName != new.propertyName.trim()) add("Propiedad: ${show(old.propertyName)} → ${show(new.propertyName)}")
            if (old.propertyType != new.propertyType.trim()) add("Tipo: ${show(old.propertyType)} → ${show(new.propertyType)}")
            if (old.status != new.status) add("Estado: ${old.status.label} → ${new.status.label}")
            if (old.areaM2?.stripTrailingZeros() != new.areaM2?.stripTrailingZeros()) add("Área: ${area(old.areaM2)} → ${area(new.areaM2)}")
            if (old.mapUrl != new.mapUrl.trim() || old.latitude?.stripTrailingZeros() != new.latitude?.stripTrailingZeros() || old.longitude?.stripTrailingZeros() != new.longitude?.stripTrailingZeros()) {
                add("Ubicación en el mapa actualizada")
            }
            if (old.notes != new.notes.trim()) add("Notas actualizadas")
        }
        return changes.joinToString("\n")
    }

    fun updateLot(tenant: Connection, lotId: UUID, input: LotInput, author: String, today: LocalDate = PropietariosRepository.today()) {
        PropietariosRepository.transaction(tenant) {
            PropietariosRepository.lockLot(tenant, lotId) ?: throw missingLot()
            val old = PropietariosRepository.getLot(tenant, lotId) ?: throw missingLot()
            // El desarrollo de un lote no cambia desde el formulario: se ignora lo que traiga la entrada.
            val fixed = input.copy(developmentId = old.developmentId)
            if (!PropietariosRepository.updateLot(tenant, lotId, fixed)) throw missingLot()
            val diff = lotDiff(old, fixed)
            if (diff.isNotEmpty()) PropietariosRepository.addHistory(tenant, lotId, "edicion", today, diff, author)
        }
    }

    fun archiveLot(tenant: Connection, lotId: UUID, author: String, today: LocalDate = PropietariosRepository.today()) {
        PropietariosRepository.transaction(tenant) {
            if (!PropietariosRepository.archiveLot(tenant, lotId)) throw missingLot()
            PropietariosRepository.addHistory(tenant, lotId, "edicion", today, "Lote archivado.", author)
        }
    }

    // ---- titularidad ----

    /**
     * Cambio de titularidad: en una transaccion y con el lote bloqueado, cierra las titularidades abiertas (fin = [date]), abre las de
     * [newOwners] (inicio = [date]), pasa el lote a vendido si estaba disponible o reservado y escribe el historial ('traspaso'; 'baja' si
     * la lista esta vacia). No avisa a nadie ni escribe en el historial del cliente (lo lee [PropietariosCustomerTimeline]).
     */
    fun transfer(
        tenant: Connection,
        lotId: UUID,
        date: LocalDate,
        newOwners: List<NewOwner>,
        notes: String,
        author: String,
        actorId: UUID?,
    ): OwnershipResult = PropietariosRepository.transaction(tenant) {
        val status = PropietariosRepository.lockLot(tenant, lotId) ?: throw missingLot()
        val lot = PropietariosRepository.getLot(tenant, lotId) ?: throw missingLot()
        if (notes.trim().length > PropietariosRules.MaxNotes) throw PropietariosValidationException("Las notas admiten hasta ${PropietariosRules.MaxNotes} caracteres.")
        val open = PropietariosRepository.openOwnerships(tenant, lotId, date)
        val customers = PropietariosRepository.visibleCustomers(tenant, newOwners.map { it.customerId })
        val plan = PropietariosRules.planTransfer(date, open, newOwners, customers.keys, status)
        PropietariosRepository.closeOwnerships(tenant, plan.closeIds, date)
        val started = newOwners.map { owner ->
            OwnershipChange(PropietariosRepository.insertOwnership(tenant, lotId, owner.customerId, owner.sharePercent, date, notes, actorId), owner.customerId)
        }
        if (plan.newStatus != status) PropietariosRepository.setLotStatus(tenant, lotId, plan.newStatus)
        val detail = PropietariosRules.historyDetail(
            plan.kind,
            from = open.map { PropietariosRules.ownerLabel(it.customerName, it.sharePercent) },
            to = newOwners.map { PropietariosRules.ownerLabel(customers[it.customerId]?.name.orEmpty(), it.sharePercent) },
            notes = notes,
        )
        PropietariosRepository.addHistory(tenant, lotId, plan.kind, date, detail, author)
        // Quien sigue como titular en el cambio (por ejemplo con otro porcentaje) no recibe un aviso de baja.
        val staying = newOwners.map { it.customerId }.toSet()
        OwnershipResult(lotId, lot.lotNumber, lot.developmentName, plan.kind, date, started, open.filter { it.customerId !in staying }.map { OwnershipChange(it.id, it.customerId) })
    }

    /** Anade un cotitular a un lote (sin cerrar a los demas): el cliente debe ser visible, no repetirse y los porcentajes sumar 100 % como maximo. */
    fun addOwner(
        tenant: Connection,
        lotId: UUID,
        newOwner: NewOwner,
        startsOn: LocalDate?,
        notes: String,
        author: String,
        actorId: UUID?,
        today: LocalDate = PropietariosRepository.today(),
    ): OwnershipResult = PropietariosRepository.transaction(tenant) {
        val status = PropietariosRepository.lockLot(tenant, lotId) ?: throw missingLot()
        val lot = PropietariosRepository.getLot(tenant, lotId) ?: throw missingLot()
        val customer = PropietariosRepository.visibleCustomer(tenant, newOwner.customerId)
            ?: throw PropietariosValidationException("El cliente ya no está disponible en Clientes.")
        val open = PropietariosRepository.openOwnerships(tenant, lotId, today)
        if (open.any { it.customerId == customer.id }) throw PropietariosValidationException("${customer.name} ya es titular vigente de este lote.")
        PropietariosRules.checkShares(listOf(newOwner.sharePercent), PropietariosRepository.assignedShare(tenant, lotId, today))
        val id = PropietariosRepository.insertOwnership(tenant, lotId, customer.id, newOwner.sharePercent, startsOn, notes, actorId)
        val after = PropietariosRules.statusAfterOwnership(status)
        if (after != status) PropietariosRepository.setLotStatus(tenant, lotId, after)
        PropietariosRepository.addHistory(
            tenant, lotId, "alta", startsOn ?: today,
            PropietariosRules.historyDetail("alta", emptyList(), listOf(PropietariosRules.ownerLabel(customer.name, newOwner.sharePercent)), notes), author,
        )
        OwnershipResult(lotId, lot.lotNumber, lot.developmentName, "alta", startsOn ?: today, listOf(OwnershipChange(id, customer.id)), emptyList())
    }

    /** Que cambio entre una titularidad y lo enviado, para el historial ("" si nada). Pura. */
    fun ownershipDiff(old: Ownership, share: BigDecimal?, startsOn: LocalDate?, endsOn: LocalDate?): String {
        fun pct(value: BigDecimal?) = value?.stripTrailingZeros()?.toPlainString()?.let { "$it %" } ?: "—"
        fun day(value: LocalDate?) = value?.toString() ?: "—"
        val changes = buildList {
            if (old.sharePercent?.stripTrailingZeros() != share?.stripTrailingZeros()) add("porcentaje ${pct(old.sharePercent)} → ${pct(share)}")
            if (old.startsOn != startsOn) add("inicio ${day(old.startsOn)} → ${day(startsOn)}")
            if (old.endsOn != endsOn) add("fin ${day(old.endsOn)} → ${day(endsOn)}")
        }
        return if (changes.isEmpty()) "" else "Titularidad de ${old.customerName}: ${changes.joinToString("; ")}"
    }

    /** Edita porcentaje y fechas de una titularidad ('edicion' en el historial). Si con ello deja de estar vigente se devuelve como baja del cliente. */
    fun editOwnership(
        tenant: Connection,
        ownershipId: UUID,
        share: BigDecimal?,
        startsOn: LocalDate?,
        endsOn: LocalDate?,
        notes: String,
        author: String,
        today: LocalDate = PropietariosRepository.today(),
    ): OwnershipResult = PropietariosRepository.transaction(tenant) {
        val lotId = PropietariosRepository.ownershipLotId(tenant, ownershipId) ?: throw PropietariosValidationException("Titularidad no encontrada.")
        // Primero el candado del lote (sin archivar) y despues se relee la titularidad: asi se ve lo que dejo un cambio en curso.
        PropietariosRepository.lockLot(tenant, lotId) ?: throw missingLot()
        val old = PropietariosRepository.getOwnership(tenant, ownershipId, today) ?: throw PropietariosValidationException("Titularidad no encontrada.")
        val lot = PropietariosRepository.getLot(tenant, old.lotId) ?: throw missingLot()
        if (startsOn != null && endsOn != null && endsOn.isBefore(startsOn)) throw PropietariosValidationException("La fecha de fin no puede ser anterior a la de inicio.")
        if (notes.trim().length > PropietariosRules.MaxNotes) throw PropietariosValidationException("Las notas admiten hasta ${PropietariosRules.MaxNotes} caracteres.")
        val stillCurrent = endsOn == null || endsOn.isAfter(today)
        PropietariosRules.checkShares(
            listOf(share),
            if (stillCurrent) PropietariosRepository.assignedShare(tenant, old.lotId, today, exceptOwnershipId = ownershipId) else BigDecimal.ZERO,
        )
        if (!PropietariosRepository.updateOwnership(tenant, ownershipId, share, startsOn, endsOn, notes)) throw PropietariosValidationException("Titularidad no encontrada.")
        val diff = ownershipDiff(old, share, startsOn, endsOn)
        if (diff.isNotEmpty()) PropietariosRepository.addHistory(tenant, old.lotId, "edicion", today, diff, author)
        val ended = if (old.current && !stillCurrent) listOf(OwnershipChange(ownershipId, old.customerId)) else emptyList()
        OwnershipResult(old.lotId, lot.lotNumber, lot.developmentName, "edicion", endsOn ?: today, emptyList(), ended)
    }

    // ---- adjuntos ----

    /** Une los archivos nuevos del formulario al lote y deja constancia en el historial. */
    fun attachFiles(tenant: Connection, lotId: UUID, added: List<UiFileRef>, uploaderId: UUID?, author: String, today: LocalDate = PropietariosRepository.today()) {
        val ids = added.mapNotNull { runCatching { UUID.fromString(it.id) }.getOrNull() }
        val names = PropietariosRepository.attach(tenant, lotId, ids, uploaderId)
        if (names.isNotEmpty()) PropietariosRepository.addHistory(tenant, lotId, "edicion", today, "Adjuntos añadidos: ${names.joinToString(", ")}", author)
    }

    fun removeFiles(tenant: Connection, lotId: UUID, ids: List<UUID>, author: String, today: LocalDate = PropietariosRepository.today()) {
        if (PropietariosRepository.markDeleted(tenant, lotId, ids) > 0) {
            PropietariosRepository.addHistory(tenant, lotId, "edicion", today, "Adjuntos quitados: ${ids.size}.", author)
        }
    }
}
