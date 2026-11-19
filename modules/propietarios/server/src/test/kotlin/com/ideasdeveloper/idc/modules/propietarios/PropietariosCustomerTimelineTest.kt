package com.ideasdeveloper.idc.modules.propietarios

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

/** La parte pura del historial del cliente: filas de titularidades a entradas (orden, limite, tipos, traspasos y fechas). */
class PropietariosCustomerTimelineTest {
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-11-19")
    private val lotId = UUID.randomUUID()

    private fun row(
        lotNumber: String = "12",
        startsOn: String? = "2024-03-01",
        endsOn: String? = null,
        share: String? = null,
        createdAt: Instant = Instant.parse("2026-11-19T09:00:00Z"),
        handedOver: Boolean = false,
        received: Boolean = false,
    ) = TimelineOwnership(
        lotId = lotId,
        lotNumber = lotNumber,
        developmentName = "Altos del Mar",
        startsOn = startsOn?.let(LocalDate::parse),
        endsOn = endsOn?.let(LocalDate::parse),
        sharePercent = share?.let(::BigDecimal),
        createdAt = createdAt,
        handedOver = handedOver,
        received = received,
    )

    private fun entries(vararg rows: TimelineOwnership, limit: Int = 50) = PropietariosCustomerTimeline.entries(rows.toList(), today, limit, utc)

    @Test
    fun currentOwnerHasOnlyAnAltaWithLinkAndDetail() {
        val entry = entries(row(share = "50.00")).single()
        assertEquals("propietario_alta", entry.kind)
        assertEquals("Propietario del lote 12", entry.title)
        assertEquals("Altos del Mar · desde 2024-03-01\nParticipación: 50%", entry.detail)
        assertEquals(Instant.parse("2024-03-01T00:00:00Z"), entry.at)
        val link = entry.link as UiOpenModule
        assertEquals("propietarios", link.moduleId)
        assertEquals("lote", link.screen)
        assertEquals(mapOf("id" to lotId.toString()), link.params)
    }

    @Test
    fun withoutShareThereIsNoParticipationLine() {
        assertFalse("Participación" in entries(row()).single().detail)
    }

    @Test
    fun importedOwnershipUsesItsOwnStartDateNotTheImportDate() {
        val imported = row(startsOn = "2019-05-20", createdAt = Instant.parse("2026-11-19T09:00:00Z"))
        assertEquals(Instant.parse("2019-05-20T00:00:00Z"), entries(imported).single().at)
    }

    @Test
    fun withoutStartDateTheCreationDateIsUsed() {
        val created = Instant.parse("2025-01-02T08:00:00Z")
        val entry = entries(row(startsOn = null, createdAt = created)).single()
        assertEquals(created, entry.at)
        assertEquals("Altos del Mar", entry.detail)
    }

    @Test
    fun endedOwnershipAddsBajaOnItsEndDate() {
        val result = entries(row(endsOn = "2025-06-30"))
        assertEquals(listOf("propietario_baja", "propietario_alta"), result.map { it.kind })
        assertEquals("Dejó de ser propietario del lote 12", result[0].title)
        assertEquals(Instant.parse("2025-06-30T00:00:00Z"), result[0].at)
    }

    @Test
    fun endDateInTheFutureOrNullGivesNoBaja() {
        assertEquals(listOf("propietario_alta"), entries(row(endsOn = "2027-01-01")).map { it.kind })
        assertEquals(listOf("propietario_baja", "propietario_alta"), entries(row(endsOn = "2026-11-19")).map { it.kind })
    }

    @Test
    fun transferUsesTransferKindsInsteadOfAltaAndBaja() {
        val seller = entries(row(endsOn = "2025-06-30", handedOver = true))
        assertEquals(listOf("propietario_traspaso_salida", "propietario_alta"), seller.map { it.kind })
        assertEquals("Traspasó el lote 12", seller[0].title)
        val buyer = entries(row(startsOn = "2025-06-30", received = true)).single()
        assertEquals("propietario_traspaso_entrada", buyer.kind)
        assertEquals("Recibió el lote 12 por traspaso", buyer.title)
    }

    @Test
    fun entriesNeverNameAnotherOwnerNorCarryNotes() {
        // The rows have no field for the other owner, notes or attachments, so none can reach the entries.
        val fields = TimelineOwnership::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { "name" in it && it != "developmentname" && it != "lotnumber" })
        assertTrue(fields.none { "note" in it || "customer" in it || "attach" in it })
        val all = entries(row(endsOn = "2025-06-30", handedOver = true), row(startsOn = "2025-06-30", received = true))
        assertTrue(all.all { it.author.isEmpty() })
    }

    @Test
    fun ownershipStartingInTheFutureIsNotListed() {
        assertTrue(entries(row(startsOn = "2027-01-01")).isEmpty())
        assertEquals(listOf("propietario_alta"), entries(row(startsOn = "2026-11-19")).map { it.kind })
    }

    @Test
    fun sortedNewestFirstAndLimited() {
        val rows = arrayOf(
            row(lotNumber = "1", startsOn = "2020-01-01"),
            row(lotNumber = "2", startsOn = "2022-01-01", endsOn = "2023-01-01"),
            row(lotNumber = "3", startsOn = "2024-01-01"),
        )
        val all = entries(*rows)
        assertEquals(all.map { it.at }.sortedDescending(), all.map { it.at })
        assertEquals("Propietario del lote 3", all.first().title)
        val limited = entries(*rows, limit = 2)
        assertEquals(all.take(2).map { it.title }, limited.map { it.title })
        assertTrue(entries(*rows, limit = 0).isEmpty())
    }
}
