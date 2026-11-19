package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Business operations shared by the screens and the MCP tools, so the rules live in one place. Like Tareas, every function
 * is synchronous and takes the company's connection: routes call them inside withContext(Dispatchers.IO) and the MCP
 * handlers run on the tool thread. Each one checks the permission, writes inside a transaction (the capacity is protected with
 * SELECT ... FOR UPDATE on the event) and, after the commit, tells Clientes (customerChanged), writes the customer's history
 * and sends the notices. A rule that fails throws [EventosValidationException] with a message for the user.
 *
 * Columns that reference users receive [actorId], which is null for a server owner (a central user that is not in the
 * company's users).
 */
object EventosService {
    private const val EventNotFound = "Evento no encontrado."
    private const val RegistrationNotFound = "Inscripción no encontrada."
    private const val NoPermission = "No tienes permiso para esta acción."
    private const val AlreadyRegistered = "El cliente ya está inscrito en este evento (o en su lista de espera)."
    private const val UniqueViolation = "23505"
    private const val LiveRegistrationIndex = "eventos_registrations_live_idx"

    /** Work after the commit (Clientes, history, notices) never turns a saved change into an error. */
    private fun afterCommit(what: String, block: () -> Unit) {
        try {
            block()
        } catch (stop: InterruptedException) {
            throw stop
        } catch (failure: Exception) {
            org.slf4j.LoggerFactory.getLogger(EventosService::class.java).warn("Fallo {} despues de guardar ({}).", what, failure.javaClass.simpleName)
        }
    }

    private fun requirePermission(permissions: ModulePermissions, id: String) {
        if (!permissions.has(id)) throw EventosValidationException(NoPermission)
    }

    private fun actorId(permissions: ModulePermissions): UUID? = permissions.userId?.takeUnless { permissions.serverOwner }

    private fun actorName(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions): String =
        access.userIdentity(tenant, permissions)?.name ?: "Usuario"

    private fun loadEvent(tenant: Connection, id: UUID): EventWithStats =
        EventosRepository.getEvent(tenant, id) ?: throw EventosValidationException(EventNotFound)

    private fun loadRegistration(tenant: Connection, id: UUID): Registration =
        EventosRepository.getRegistration(tenant, id) ?: throw EventosValidationException(RegistrationNotFound)

    /** The event row is locked until the end of the transaction; it is read after the lock so the numbers are the ones that count. */
    private fun lockedEvent(tenant: Connection, id: UUID): EventWithStats {
        if (!EventosRepository.lockEvent(tenant, id)) throw EventosValidationException(EventNotFound)
        return loadEvent(tenant, id)
    }

    private fun requireResponsible(tenant: Connection, userId: UUID?) {
        if (userId == null) return
        if (EventosRepository.users(tenant).none { it.id == userId }) {
            throw EventosValidationException("El responsable debe ser un usuario activo del módulo de eventos.")
        }
    }

    private fun requireClient(tenant: Connection, input: EventInput) {
        val client = input.clientCustomerId ?: return
        if (EventosRepository.visibleCustomer(tenant, client) == null) throw EventosValidationException("El cliente no existe o no está disponible.")
    }

    private fun validate(input: EventInput, requireClient: Boolean) {
        val errors = EventosRules.validateEvent(input, requireClient)
        if (errors.isNotEmpty()) throw EventosValidationException(errors)
    }

    // ---- events ----

    /**
     * Creates an event (state Draft, or Published when [publish]). A private event confirmed on creation writes "Evento privado"
     * in its client's history. [requireClient]: a private event needs its client (the app asks for it).
     */
    fun createEvent(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        input: EventInput,
        publish: Boolean = false,
        requireClient: Boolean = false,
    ): Event {
        requirePermission(permissions, EventosModule.Manage)
        validate(input, requireClient)
        requireClient(tenant, input)
        requireResponsible(tenant, input.responsibleUserId)
        val author = actorName(tenant, access, permissions)
        val id = UUID.randomUUID()
        val currency = EventosRepository.settings(tenant).currency
        val status = if (publish) EventStatus.Published else EventStatus.Draft
        EventosRepository.transaction(tenant) {
            EventosRepository.insertEvent(tenant, id, input, status, currency, author)
        }
        val event = loadEvent(tenant, id).event
        if (EventosRules.isBooked(event.kind, event.status)) {
            event.clientCustomerId?.let { client -> afterCommit("el historial del evento privado") { EventosCustomerEvents.booked(tenant, access, event, client, author) } }
        }
        return event
    }

    /**
     * Edits an event. The kind never changes and the capacity cannot go below the seats taken; Published and Full are recomputed
     * from the new capacity.
     */
    fun updateEvent(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, input: EventInput): Event {
        requirePermission(permissions, EventosModule.Manage)
        validate(input, requireClient = false)
        val author = actorName(tenant, access, permissions)
        var previousClient: UUID? = null
        EventosRepository.transaction(tenant) {
            val current = lockedEvent(tenant, id)
            val event = current.event
            if (input.kind != event.kind) throw EventosValidationException("No se puede cambiar el tipo de un evento: duplícalo como otro tipo.")
            EventosRules.capacityError(input.capacity, current.stats.occupied)?.let { throw EventosValidationException(it) }
            if (input.clientCustomerId != event.clientCustomerId) requireClient(tenant, input)
            if (input.responsibleUserId != event.responsibleUserId) requireResponsible(tenant, input.responsibleUserId)
            previousClient = event.clientCustomerId
            EventosRepository.updateEvent(tenant, id, input)
            val status = EventosRules.statusAfterOccupancy(event.status, input.capacity, current.stats.occupied)
            if (status != event.status) EventosRepository.setStatus(tenant, id, status)
        }
        val event = loadEvent(tenant, id).event
        val client = event.clientCustomerId
        if (client != null && client != previousClient && EventosRules.isBooked(event.kind, event.status)) {
            afterCommit("el historial del evento privado") { EventosCustomerEvents.booked(tenant, access, event, client, author) }
        }
        return event
    }

    /**
     * A new Draft event with everything of [id] except the state, the dates (the user picks them), registrations, payments
     * and import key.
     */
    fun duplicateEvent(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, startsAt: Instant, endsAt: Instant?): Event {
        requirePermission(permissions, EventosModule.Manage)
        val source = loadEvent(tenant, id).event
        val input = EventInput(
            kind = source.kind,
            title = source.title,
            description = source.description,
            category = source.category,
            series = source.series,
            space = source.space,
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = source.capacity,
            hostName = source.hostName,
            responsibleUserId = source.responsibleUserId,
            priceMember = source.priceMember,
            pricePublic = source.pricePublic,
            clientCustomerId = source.clientCustomerId,
            agreedAmount = source.agreedAmount,
            guests = source.guests,
            notes = source.notes,
        )
        validate(input, requireClient = false)
        val author = actorName(tenant, access, permissions)
        val newId = UUID.randomUUID()
        EventosRepository.transaction(tenant) {
            EventosRepository.insertEvent(tenant, newId, input, EventStatus.Draft, source.currency, author)
        }
        return loadEvent(tenant, newId).event
    }

    /**
     * Publishes (Draft to Published), cancels with [reason], marks as done or reopens (Cancelled to Draft) an event, following
     * [EventosRules.transitionError]. Confirming a private event writes "Evento privado" in its client's history and
     * cancelling a confirmed one writes "Evento cancelado".
     */
    fun changeStatus(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, target: EventStatus, reason: String = ""): Event {
        requirePermission(permissions, EventosModule.Manage)
        val author = actorName(tenant, access, permissions)
        var previous = EventStatus.Draft
        EventosRepository.transaction(tenant) {
            val current = lockedEvent(tenant, id)
            val event = current.event
            EventosRules.transitionError(event.kind, event.status, target, reason)?.let { throw EventosValidationException(it) }
            previous = event.status
            val next = if (target == EventStatus.Published) EventosRules.statusAfterOccupancy(target, event.capacity, current.stats.occupied) else target
            EventosRepository.setStatus(tenant, id, next, reason)
        }
        val event = loadEvent(tenant, id).event
        val client = event.clientCustomerId
        if (client != null && event.kind == EventKind.Private) {
            afterCommit("el historial del evento privado") {
                if (target == EventStatus.Published) EventosCustomerEvents.booked(tenant, access, event, client, author)
                if (target == EventStatus.Cancelled && previous == EventStatus.Published) EventosCustomerEvents.cancelled(tenant, access, event, client, author)
            }
        }
        return event
    }

    // ---- registrations ----

    private class Placed(val id: UUID, val status: RegistrationStatus, val customerName: String, val membership: ActiveMembership?, val becameFull: Boolean)

    /**
     * Registers a customer ([input].customerId, which must be visible) or, with [newCustomer], finds or creates one by e-mail.
     * In one transaction with the event locked: the seats that fit make an "inscrito", otherwise "espera" (the answer says so).
     * The event becomes Full when the seats are all taken.
     */
    fun register(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        input: RegistrationInput,
        newCustomer: NewCustomerInput? = null,
    ): RegistrationResult {
        requirePermission(permissions, EventosModule.Register)
        val errors = EventosRules.validateRegistration(input.seats, input.amount, input.note)
        if (errors.isNotEmpty()) throw EventosValidationException(errors)
        if (newCustomer == null && input.customerId == null) throw EventosValidationException("Elige el cliente que se inscribe.")
        val actor = actorId(permissions)
        val author = actorName(tenant, access, permissions)
        var createdCustomer: UUID? = null
        val placed = try {
            EventosRepository.transaction(tenant) {
                val current = lockedEvent(tenant, input.eventId)
                val event = current.event
                EventosRules.registerError(event.kind, event.status)?.let { throw EventosValidationException(it) }
                val customerId: UUID
                val customerName: String
                if (newCustomer != null) {
                    val lookup = EventosRepository.findOrCreateCustomer(tenant, newCustomer)
                    if (lookup.created) createdCustomer = lookup.customerId
                    customerId = lookup.customerId
                    customerName = if (lookup.created) newCustomer.name.trim() else EventosRepository.visibleCustomer(tenant, lookup.customerId)?.name.orEmpty()
                } else {
                    val wanted = input.customerId ?: throw EventosValidationException("Elige el cliente que se inscribe.")
                    val customer = EventosRepository.visibleCustomer(tenant, wanted) ?: throw EventosValidationException("El cliente no existe o no está disponible.")
                    customerId = customer.id
                    customerName = customer.name
                }
                if (EventosRepository.liveRegistration(tenant, event.id, customerId) != null) throw EventosValidationException(AlreadyRegistered)
                val occupied = current.stats.occupied
                val status = EventosRules.decide(event.capacity, occupied, input.seats, current.stats.waiting)
                val membership = EventosMembership.activeMembership(tenant, customerId)
                val priceKind = input.priceKind ?: EventosRules.proposedPriceKind(event.priceMember, membership != null)
                val amount = EventosRules.resolveAmount(priceKind, input.amount, event.priceMember, event.pricePublic, input.seats)
                val id = UUID.randomUUID()
                EventosRepository.insertRegistration(tenant, id, event.id, customerId, status, input.seats, priceKind, amount, input.paid, input.note, author)
                val taken = if (status == RegistrationStatus.Registered) occupied + input.seats else occupied
                val next = EventosRules.statusAfterOccupancy(event.status, event.capacity, taken)
                if (next != event.status) EventosRepository.setStatus(tenant, event.id, next)
                Placed(id, status, customerName, membership, becameFull = next == EventStatus.Full && event.status != EventStatus.Full)
            }
        } catch (failure: SQLException) {
            // The unique index (one live registration per customer and event) catches two requests at the same moment.
            if (failure.sqlState == UniqueViolation && failure.message.orEmpty().contains(LiveRegistrationIndex)) throw EventosValidationException(AlreadyRegistered)
            throw failure
        }
        createdCustomer?.let { created -> afterCommit("el aviso a Clientes") { access.customerChanged(tenant, created) } }
        val registration = loadRegistration(tenant, placed.id)
        val event = loadEvent(tenant, input.eventId).event
        val waitlisted = placed.status == RegistrationStatus.Waitlist
        afterCommit("el historial y los avisos de una inscripcion") {
            if (!waitlisted) EventosCustomerEvents.registered(tenant, access, event, registration, author)
            val notifier = access.notificationsFor(EventosModule.id)
            EventosNotify.newRegistration(tenant, notifier, event, placed.customerName, registration.seats, waitlisted, actor)
            if (placed.becameFull) EventosNotify.eventFull(tenant, notifier, event, actor)
        }
        return RegistrationResult(registration, waitlisted, placed.becameFull, placed.membership, event.currency)
    }

    /**
     * Cancels a registration. A freed seat of a Full event puts it back to Published; if somebody is waiting the responsible
     * is told ("Se liberó una plaza"); nobody is moved up automatically.
     */
    fun cancelRegistration(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, registrationId: UUID): Registration {
        requirePermission(permissions, EventosModule.Register)
        val actor = actorId(permissions)
        var previous = RegistrationStatus.Cancelled
        var waiting = 0
        val eventId = loadRegistration(tenant, registrationId).eventId
        EventosRepository.transaction(tenant) {
            lockedEvent(tenant, eventId)
            val registration = loadRegistration(tenant, registrationId)
            if (registration.status == RegistrationStatus.Cancelled) throw EventosValidationException("La inscripción ya estaba cancelada.")
            previous = registration.status
            EventosRepository.setRegistrationStatus(tenant, registrationId, RegistrationStatus.Cancelled)
            val current = loadEvent(tenant, eventId)
            val next = EventosRules.statusAfterOccupancy(current.event.status, current.event.capacity, current.stats.occupied)
            if (next != current.event.status) EventosRepository.setStatus(tenant, eventId, next)
            waiting = current.stats.waiting
        }
        if (EventosRules.freedSeatNotice(previous, waiting)) {
            afterCommit("el aviso de plaza liberada") {
                val event = loadEvent(tenant, eventId).event
                EventosNotify.seatFreed(tenant, access.notificationsFor(EventosModule.id), event, waiting, actor)
            }
        }
        return loadRegistration(tenant, registrationId)
    }

    /** Moves somebody from the waiting list to "inscrito" (the team decides); the seats must fit, otherwise the capacity has to grow. */
    fun promote(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, registrationId: UUID): Registration {
        requirePermission(permissions, EventosModule.Register)
        val author = actorName(tenant, access, permissions)
        val eventId = loadRegistration(tenant, registrationId).eventId
        EventosRepository.transaction(tenant) {
            val current = lockedEvent(tenant, eventId)
            val event = current.event
            val registration = loadRegistration(tenant, registrationId)
            if (registration.status != RegistrationStatus.Waitlist) throw EventosValidationException("La inscripción no está en la lista de espera.")
            EventosRules.registerError(event.kind, event.status)?.let { throw EventosValidationException(it) }
            if (!EventosRules.fits(event.capacity, current.stats.occupied, registration.seats)) {
                throw EventosValidationException("No hay plazas; aumenta el cupo.")
            }
            EventosRepository.setRegistrationStatus(tenant, registrationId, RegistrationStatus.Registered)
            val next = EventosRules.statusAfterOccupancy(event.status, event.capacity, current.stats.occupied + registration.seats)
            if (next != event.status) EventosRepository.setStatus(tenant, eventId, next)
        }
        val registration = loadRegistration(tenant, registrationId)
        afterCommit("el historial de una inscripcion") {
            EventosCustomerEvents.registered(tenant, access, loadEvent(tenant, eventId).event, registration, author)
        }
        return registration
    }

    /** Marks or unmarks the payment of a registration (the date is recorded; [note] is optional, up to 200 characters). */
    fun setPaid(tenant: Connection, permissions: ModulePermissions, registrationId: UUID, paid: Boolean, note: String = ""): Registration {
        requirePermission(permissions, EventosModule.Register)
        if (note.length > EventosRules.MaxPaymentNote) throw EventosValidationException("La nota del pago admite hasta ${EventosRules.MaxPaymentNote} caracteres.")
        val registration = loadRegistration(tenant, registrationId)
        if (registration.status == RegistrationStatus.Cancelled) throw EventosValidationException("La inscripción está cancelada.")
        EventosRepository.setPaid(tenant, registrationId, paid, if (paid) note else "")
        return loadRegistration(tenant, registrationId)
    }

    /** Marks or unmarks attendance (idempotent). Only registered people, once the event started and while it is not cancelled. */
    fun setAttendance(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, registrationId: UUID, attended: Boolean): Registration {
        requirePermission(permissions, EventosModule.Register)
        val registration = loadRegistration(tenant, registrationId)
        val event = loadEvent(tenant, registration.eventId).event
        EventosRules.attendanceError(registration.status, event.status, event.startsAt, Instant.now())?.let { throw EventosValidationException(it) }
        if ((registration.attendedAt != null) == attended) return registration
        val author = actorName(tenant, access, permissions)
        EventosRepository.setAttendance(tenant, registrationId, attended, author)
        val updated = loadRegistration(tenant, registrationId)
        if (attended) afterCommit("el historial de asistencia") { EventosCustomerEvents.attended(tenant, access, event, updated, author) }
        return updated
    }

    // ---- payments (abonos) of private events ----

    fun addPayment(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        eventId: UUID,
        paidOn: LocalDate,
        amount: java.math.BigDecimal,
        method: String = "",
        note: String = "",
    ): EventPayment {
        requirePermission(permissions, EventosModule.Manage)
        val errors = EventosRules.validatePayment(amount, method, note)
        if (errors.isNotEmpty()) throw EventosValidationException(errors)
        val author = actorName(tenant, access, permissions)
        val id = UUID.randomUUID()
        EventosRepository.transaction(tenant) {
            val event = lockedEvent(tenant, eventId).event
            if (event.kind != EventKind.Private) throw EventosValidationException("Los abonos son solo para eventos privados.")
            EventosRepository.insertPayment(tenant, id, eventId, paidOn, amount, method, note, author)
        }
        return EventosRepository.getPayment(tenant, id) ?: throw EventosValidationException("No se pudo guardar el abono.")
    }

    fun deletePayment(tenant: Connection, permissions: ModulePermissions, eventId: UUID, paymentId: UUID) {
        requirePermission(permissions, EventosModule.Manage)
        if (EventosRepository.deletePayment(tenant, eventId, paymentId) == 0) throw EventosValidationException("Abono no encontrado.")
    }

    // ---- settings ----

    fun saveSettings(tenant: Connection, permissions: ModulePermissions, currency: String, spaces: List<String>, categories: List<String>) {
        requirePermission(permissions, EventosModule.Manage)
        val cleanCurrency = currency.trim().uppercase()
        val errors = EventosRules.validateSettings(cleanCurrency, spaces, categories)
        if (errors.isNotEmpty()) throw EventosValidationException(errors)
        EventosRepository.saveSettings(tenant, EventosSettings(cleanCurrency, spaces, categories))
    }
}
