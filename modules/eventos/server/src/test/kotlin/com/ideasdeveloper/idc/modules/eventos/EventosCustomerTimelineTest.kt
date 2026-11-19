package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.ui.UiOpenModule
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure part of the customer timeline: rows to entries (order, limit, kinds, titles, dates and what is never shown). */
class EventosCustomerTimelineTest {
    private val utc = ZoneId.of("UTC")
    private val eventId = UUID.randomUUID()

    private fun at(text: String) = Instant.parse(text)

    private fun registration(
        createdAt: Instant = at("2026-03-01T10:00:00Z"),
        startsAt: Instant = at("2026-04-10T18:00:00Z"),
        cancelled: Boolean = false,
        paid: Boolean = false,
        paidAt: Instant? = null,
        attendedAt: Instant? = null,
        cancelledAt: Instant? = null,
    ) = TimelineRegistration(
        eventId = eventId,
        title = "Taller de cerámica",
        startsAt = startsAt,
        createdAt = createdAt,
        cancelled = cancelled,
        seats = 2,
        amount = BigDecimal("40.00"),
        currency = "USD",
        paid = paid,
        paidAt = paidAt,
        attendedAt = attendedAt,
        cancelledAt = cancelledAt,
    )

    private fun privateEvent(
        createdAt: Instant = at("2026-02-01T10:00:00Z"),
        startsAt: Instant = at("2026-05-20T20:00:00Z"),
        cancelled: Boolean = false,
        cancelledAt: Instant? = null,
        agreed: BigDecimal? = BigDecimal("1500.00"),
    ) = TimelinePrivateEvent(
        eventId = eventId,
        title = "Boda Pérez",
        startsAt = startsAt,
        createdAt = createdAt,
        space = "Jardín",
        agreedAmount = agreed,
        currency = "USD",
        cancelled = cancelled,
        cancelledAt = cancelledAt,
        paidTotal = BigDecimal("500.00"),
    )

    private fun entries(
        registrations: List<TimelineRegistration> = emptyList(),
        privateEvents: List<TimelinePrivateEvent> = emptyList(),
        payments: List<TimelinePayment> = emptyList(),
        limit: Int = 50,
    ) = EventosCustomerTimeline.entries(registrations, privateEvents, payments, limit, utc)

    @Test
    fun registrationShowsDetailAndLink() {
        val result = entries(registrations = listOf(registration())).single()
        assertEquals("evento_inscripcion", result.kind)
        assertEquals("Inscripción: Taller de cerámica", result.title)
        assertEquals(at("2026-03-01T10:00:00Z"), result.at)
        assertTrue("2 plazas" in result.detail && "40.00 USD" in result.detail && "Pago pendiente" in result.detail)
        assertTrue(result.detail.startsWith(EventosRules.stamp(at("2026-04-10T18:00:00Z"), utc)))
        val link = result.link as UiOpenModule
        assertEquals("eventos", link.moduleId)
        assertEquals("evento", link.screen)
        assertEquals(mapOf("id" to eventId.toString()), link.params)
    }

    @Test
    fun paidRegistrationSaysPaid() {
        val result = entries(registrations = listOf(registration(paid = true))).single()
        assertTrue("Pagado" in result.detail && "Pago pendiente" !in result.detail)
    }

    @Test
    fun importedEventUsesItsRealDateNotTheImportDate() {
        // Imported today (created_at) for an event of last year: the earlier date wins.
        val imported = privateEvent(createdAt = at("2026-11-19T09:00:00Z"), startsAt = at("2025-06-14T20:00:00Z"))
        assertEquals(at("2025-06-14T20:00:00Z"), entries(privateEvents = listOf(imported)).single().at)
        val registered = registration(createdAt = at("2026-11-19T09:00:00Z"), startsAt = at("2025-06-14T20:00:00Z"))
        assertEquals(at("2025-06-14T20:00:00Z"), entries(registrations = listOf(registered)).single().at)
    }

    @Test
    fun registrationLifecycleKindsAndTitles() {
        val result = entries(
            registrations = listOf(
                registration(
                    paid = true,
                    paidAt = at("2026-03-02T10:00:00Z"),
                    attendedAt = at("2026-04-10T18:30:00Z"),
                    cancelled = true,
                    cancelledAt = at("2026-04-11T08:00:00Z"),
                )
            )
        )
        assertEquals(
            listOf("evento_inscripcion_cancelada", "evento_asistencia", "evento_pago", "evento_inscripcion"),
            result.map { it.kind },
        )
        assertEquals("Inscripción cancelada: Taller de cerámica", result[0].title)
        assertEquals("Asistió: Taller de cerámica", result[1].title)
        assertEquals("Pago de inscripción: Taller de cerámica", result[2].title)
    }

    @Test
    fun activeRegistrationHasNoCancellationEntry() {
        val result = entries(registrations = listOf(registration(cancelledAt = at("2026-04-11T08:00:00Z"))))
        assertEquals(listOf("evento_inscripcion"), result.map { it.kind })
    }

    @Test
    fun privateEventShowsSpaceAgreedAndPaid() {
        val result = entries(privateEvents = listOf(privateEvent())).single()
        assertEquals("evento_privado", result.kind)
        assertEquals("Evento privado: Boda Pérez", result.title)
        assertTrue("Jardín" in result.detail && "Acordado: 1500.00 USD" in result.detail && "Pagado: 500.00 USD" in result.detail)
        assertEquals(at("2026-02-01T10:00:00Z"), result.at)
    }

    @Test
    fun privateEventWithoutAgreedAmountOmitsIt() {
        val result = entries(privateEvents = listOf(privateEvent(agreed = null))).single()
        assertFalse("Acordado" in result.detail)
    }

    @Test
    fun cancelledPrivateEventAddsEntryWithoutReason() {
        val result = entries(privateEvents = listOf(privateEvent(cancelled = true, cancelledAt = at("2026-03-15T12:00:00Z"))))
        assertEquals(listOf("evento_cancelado", "evento_privado"), result.map { it.kind })
        val cancelled = result.first()
        assertEquals("Evento cancelado: Boda Pérez", cancelled.title)
        // The model of the timeline has no field for the reason, so it cannot leak.
        assertTrue(TimelinePrivateEvent::class.java.declaredFields.none { it.name.contains("reason", ignoreCase = true) })
    }

    @Test
    fun eachPrivatePaymentIsOneEntryWithAmountOnly() {
        val payments = listOf(
            TimelinePayment(eventId, "Boda Pérez", LocalDate.parse("2026-03-01"), BigDecimal("200.00"), "USD"),
            TimelinePayment(eventId, "Boda Pérez", LocalDate.parse("2026-04-01"), BigDecimal("300.00"), "USD"),
        )
        val result = entries(payments = payments)
        assertEquals(listOf("evento_pago", "evento_pago"), result.map { it.kind })
        assertEquals("Pago del evento privado: Boda Pérez", result[0].title)
        assertEquals("300.00 USD", result[0].detail)
        assertEquals(at("2026-04-01T00:00:00Z"), result[0].at)
        assertTrue(TimelinePayment::class.java.declaredFields.none { it.name.contains("note", true) || it.name.contains("reference", true) })
    }

    @Test
    fun sortedNewestFirstAcrossSourcesAndLimited() {
        val payments = listOf(TimelinePayment(eventId, "Boda Pérez", LocalDate.parse("2026-06-01"), BigDecimal("100.00"), "USD"))
        val all = entries(
            registrations = listOf(registration(attendedAt = at("2026-04-10T18:30:00Z"))),
            privateEvents = listOf(privateEvent()),
            payments = payments,
        )
        assertEquals(all.map { it.at }.sortedDescending(), all.map { it.at })
        assertEquals("Pago del evento privado: Boda Pérez", all.first().title)
        val limited = entries(
            registrations = listOf(registration(attendedAt = at("2026-04-10T18:30:00Z"))),
            privateEvents = listOf(privateEvent()),
            payments = payments,
            limit = 2,
        )
        assertEquals(all.take(2).map { it.title }, limited.map { it.title })
        assertTrue(entries(registrations = listOf(registration()), limit = 0).isEmpty())
    }

    @Test
    fun longTitlesAreCut() {
        val long = registration().copy(title = "x".repeat(500))
        assertTrue(entries(registrations = listOf(long)).single().title.length <= 200)
    }
}
