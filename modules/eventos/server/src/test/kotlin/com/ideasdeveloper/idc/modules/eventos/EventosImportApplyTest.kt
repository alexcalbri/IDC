package com.ideasdeveloper.idc.modules.eventos

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure rules of the import: the preview summary, matching people, e-mails and trimming. */
class EventosImportApplyTest {
    private val ana = EventosUser(UUID.randomUUID(), "Ana Pérez", "Ana@Empresa.com")
    private val luis = EventosUser(UUID.randomUUID(), "Luis", "luis@empresa.com")
    private val users = listOf(ana, luis)
    private val date = LocalDateTime.of(2026, 12, 5, 19, 0)

    private fun row(
        key: String,
        title: String = "Evento $key",
        client: String = "Cliente $key",
        email: String = "",
        space: String = "",
        starts: LocalDateTime? = date,
        responsibleEmail: String? = null,
        responsibleName: String? = null,
    ) = ImportedEvent(
        key = key,
        title = title,
        space = space,
        startsAt = starts,
        status = EventStatus.Published,
        clientName = client,
        clientEmail = email,
        responsibleEmail = responsibleEmail,
        responsibleName = responsibleName,
    )

    @Test
    fun matchesPeopleByEmailIgnoringCase() {
        val summary = EventosImportApply.summary(
            listOf(row("1", responsibleEmail = "ana@empresa.COM"), row("2", responsibleEmail = " LUIS@empresa.com ")),
            users,
        )
        assertEquals(listOf("Ana Pérez", "Luis"), summary.matchedPeople)
        assertTrue(summary.unmatchedPeople.isEmpty())
    }

    @Test
    fun listsPeopleWithoutUser() {
        val rows = listOf(
            row("1", responsibleEmail = "nadie@otra.com"),
            row("2", responsibleEmail = "NADIE@otra.com"),
            row("3", responsibleName = "Carlos"),
            row("4", responsibleName = "carlos"),
            row("5"),
        )
        val summary = EventosImportApply.summary(rows, users)
        assertTrue(summary.matchedPeople.isEmpty())
        assertEquals(listOf("nadie@otra.com", "Carlos"), summary.unmatchedPeople)
    }

    @Test
    fun skipsRowsWithoutKeyDateOrTitleAndClient() {
        val rows = listOf(
            row("1"),
            row(" "),
            row("2", starts = null),
            row("3", title = " ", client = " "),
            row("4", title = " ", client = "Marta"),
        )
        val summary = EventosImportApply.summary(rows, users)
        assertEquals(2, summary.events)
        assertEquals(3, summary.problems.size)
    }

    @Test
    fun reportsRepeatedKeysOnce() {
        val summary = EventosImportApply.summary(listOf(row("1"), row("1"), row("2")), users)
        assertEquals(2, summary.events)
        assertEquals(1, summary.problems.size)
    }

    @Test
    fun countsEventsBySpace() {
        val rows = listOf(row("1", space = "La Jolla"), row("2", space = " La Jolla "), row("3", space = "Magrock"), row("4"))
        val summary = EventosImportApply.summary(rows, users)
        assertEquals(mapOf("La Jolla" to 2, "Magrock" to 1, "Sin espacio" to 1), summary.bySpace)
    }

    @Test
    fun newCustomersAreThoseWithoutAnExistingEmail() {
        val rows = listOf(
            row("1", email = "nuevo@correo.com"),
            row("2", email = "NUEVO@correo.com"),
            row("3", email = "existe@correo.com"),
            row("4", email = "invalido"),
            row("5"),
        )
        val summary = EventosImportApply.summary(rows, users, existingEmails = setOf("Existe@Correo.com"))
        // nuevo@ (once), the invalid e-mail and the one without e-mail each become a customer.
        assertEquals(3, summary.newCustomers)
    }

    @Test
    fun cleanEmailKeepsOnlyUsableOnes() {
        assertEquals("ana@correo.com", EventosImportApply.cleanEmail(" Ana@Correo.com "))
        assertEquals("", EventosImportApply.cleanEmail("sin arroba"))
        assertEquals("", EventosImportApply.cleanEmail("a b@c.com"))
        assertEquals("", EventosImportApply.cleanEmail(""))
    }

    @Test
    fun matchUserIgnoresCaseAndSpaces() {
        assertEquals(ana, EventosImportApply.matchUser(" ANA@empresa.com ", users))
        assertNull(EventosImportApply.matchUser("otra@empresa.com", users))
        assertNull(EventosImportApply.matchUser(null, users))
        assertNull(EventosImportApply.matchUser("  ", users))
    }

    @Test
    fun clipCutsWithAnEllipsis() {
        assertEquals("abc", EventosImportApply.clip("abc", 3))
        assertEquals("ab…", EventosImportApply.clip("abcdef", 3))
    }

    @Test
    fun notesKeepTheExtraLineAndGiveUpRoom() {
        assertEquals("Notas", EventosImportApply.notesWithExtra(" Notas ", null))
        assertEquals("Responsable en Monday: Marta", EventosImportApply.notesWithExtra("", "Responsable en Monday: Marta"))
        assertEquals("Notas\n\nResponsable en Monday: Marta", EventosImportApply.notesWithExtra("Notas", "Responsable en Monday: Marta"))
        val long = EventosImportApply.notesWithExtra("x".repeat(20_000), "Responsable en Monday: Marta")
        assertTrue(long.length <= EventosRules.MaxText)
        assertTrue(long.endsWith("Responsable en Monday: Marta"))
    }

    @Test
    fun importedRowCarriesPayments() {
        val payment = ImportedPayment("monday:1:p1", LocalDate.of(2026, 11, 1), BigDecimal("500"))
        val imported = row("1").copy(payments = listOf(payment), agreedAmount = BigDecimal("2000"), guests = 40)
        assertEquals(1, imported.payments.size)
        assertEquals(EventStatus.Published, imported.status)
    }
}
