package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import com.ideasdeveloper.idc.server.modules.NotificationTarget
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Notices in the Core's notification center, sent after the change is committed. A notice never breaks the change that
 * caused it: each one is created inside runCatching and a failure is only logged. The author is never notified.
 *
 * Who is told: the responsible user of the event when it is an active user; otherwise the active users that manage events
 * (eventos.manage) and the business owner.
 */
object EventosNotify {
    private val log = LoggerFactory.getLogger(EventosNotify::class.java)

    const val NewRegistrationTitle = "Nueva inscripción"
    const val WaitlistTitle = "En lista de espera"
    const val FullTitle = "Evento lleno"
    const val SeatFreedTitle = "Se liberó una plaza"

    fun target(eventId: UUID) = NotificationTarget("evento", mapOf("id" to eventId.toString()))

    /** The responsible when active, otherwise the managers; each once and never the author. */
    fun recipients(responsibleUserId: UUID?, responsibleActive: Boolean, managers: Collection<UUID>, authorId: UUID?): List<UUID> {
        val base = if (responsibleUserId != null && responsibleActive) listOf(responsibleUserId) else managers.toList()
        return base.distinct().filter { it != authorId }
    }

    fun newRegistrationText(customer: String, event: String, seats: Int): String = "$customer · $event · ${EventosRules.seatsLabel(seats)}"

    fun fullText(event: String, capacity: Int?): String = if (capacity == null) event else "$event · $capacity plazas"

    fun seatFreedText(event: String, waiting: Int): String = "$event: $waiting en espera"

    fun newRegistration(tenant: Connection, notifier: ModuleNotifier?, event: Event, customer: String, seats: Int, waitlisted: Boolean, authorId: UUID?) =
        send(tenant, notifier, event, authorId, if (waitlisted) WaitlistTitle else NewRegistrationTitle, newRegistrationText(customer, event.title, seats))

    fun eventFull(tenant: Connection, notifier: ModuleNotifier?, event: Event, authorId: UUID?) =
        send(tenant, notifier, event, authorId, FullTitle, fullText(event.title, event.capacity))

    /** A registered person left and people are waiting: the manager decides whom to move ("Pasar a inscrito"). */
    fun seatFreed(tenant: Connection, notifier: ModuleNotifier?, event: Event, waiting: Int, authorId: UUID?) =
        send(tenant, notifier, event, authorId, SeatFreedTitle, seatFreedText(event.title, waiting))

    private fun send(tenant: Connection, notifier: ModuleNotifier?, event: Event, authorId: UUID?, title: String, text: String) {
        if (notifier == null) return
        runCatching {
            val active = event.responsibleUserId?.let { EventosRepository.isActiveUser(tenant, it) } ?: false
            val managers = if (active) emptyList() else EventosRepository.managerIds(tenant)
            recipients(event.responsibleUserId, active, managers, authorId).forEach { userId ->
                runCatching { notifier.notify(tenant, userId, title, text.take(1000), target(event.id)) }
                    .onFailure { log.warn("No se pudo crear el aviso \"{}\" de un evento ({}).", title, it.javaClass.simpleName) }
            }
        }.onFailure { log.warn("No se pudo preparar el aviso \"{}\" de un evento ({}).", title, it.javaClass.simpleName) }
    }
}
