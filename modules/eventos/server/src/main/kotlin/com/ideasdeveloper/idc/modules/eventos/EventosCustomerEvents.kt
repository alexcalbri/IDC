package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.modules.CustomerHistoryEvent
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.ui.UiOpenModule
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * What the module writes in the customer's history (Clientes). Each event carries a sourceRef so it is written once; a
 * failure is logged and never breaks the change that caused it.
 */
object EventosCustomerEvents {
    private val log = LoggerFactory.getLogger(EventosCustomerEvents::class.java)

    private fun link(eventId: UUID) = UiOpenModule(EventosModule.id, "evento", mapOf("id" to eventId.toString()))

    private fun write(tenant: Connection, access: ModuleAccessService, event: CustomerHistoryEvent) {
        runCatching { access.recordCustomerEvent(tenant, event) }
            .onFailure { log.warn("No se pudo escribir en el historial del cliente ({}).", it.javaClass.simpleName) }
    }

    /** "Inscripción: {evento}", when the customer is registered (also when moved from the waiting list; waiting people are not written). */
    fun registered(tenant: Connection, access: ModuleAccessService, event: Event, registration: Registration, author: String) {
        write(
            tenant,
            access,
            CustomerHistoryEvent(
                customerId = registration.customerId,
                moduleId = EventosModule.id,
                kind = "evento_inscripcion",
                title = "Inscripción: ${event.title}".take(200),
                detail = "${EventosRules.stamp(event.startsAt)} · ${EventosRules.seatsLabel(registration.seats)} · ${registration.amount.toPlainString()} ${event.currency}",
                author = author,
                link = link(event.id),
                sourceRef = "registration:${registration.id}:created",
            ),
        )
    }

    fun attended(tenant: Connection, access: ModuleAccessService, event: Event, registration: Registration, author: String) {
        write(
            tenant,
            access,
            CustomerHistoryEvent(
                customerId = registration.customerId,
                moduleId = EventosModule.id,
                kind = "evento_asistencia",
                title = "Asistió: ${event.title}".take(200),
                detail = EventosRules.stamp(event.startsAt),
                author = author,
                link = link(event.id),
                sourceRef = "registration:${registration.id}:attended",
            ),
        )
    }

    /** "Evento privado: {evento}" for the client, once the private event is confirmed (or imported as confirmed or done). */
    fun booked(tenant: Connection, access: ModuleAccessService, event: Event, customerId: UUID, author: String) {
        val detail = listOf(
            EventosRules.stamp(event.startsAt),
            event.space.trim(),
            event.agreedAmount?.let { "${it.toPlainString()} ${event.currency}" }.orEmpty(),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        write(
            tenant,
            access,
            CustomerHistoryEvent(
                customerId = customerId,
                moduleId = EventosModule.id,
                kind = "evento_privado",
                title = "Evento privado: ${event.title}".take(200),
                detail = detail,
                author = author,
                link = link(event.id),
                sourceRef = "event:${event.id}:booked",
            ),
        )
    }

    /** The client of a confirmed private event is told it was cancelled. */
    fun cancelled(tenant: Connection, access: ModuleAccessService, event: Event, customerId: UUID, author: String) {
        write(
            tenant,
            access,
            CustomerHistoryEvent(
                customerId = customerId,
                moduleId = EventosModule.id,
                kind = "evento_cancelado",
                title = "Evento cancelado: ${event.title}".take(200),
                detail = event.cancelReason,
                author = author,
                link = link(event.id),
                sourceRef = "event:${event.id}:cancelled",
            ),
        )
    }
}
