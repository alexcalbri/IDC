package com.ideasdeveloper.idc.modules.compras

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mapeo de una fila importada a lo que se guarda (puro, sin base de datos). */
class ComprasImportTest {
    private val now = Instant.parse("2026-03-01T10:00:00Z")
    private val before = Instant.parse("2026-01-15T08:00:00Z")

    private fun item(
        status: RequestStatus = RequestStatus.Approved,
        amount: String? = "1234.5",
        quotes: List<ImportedQuote> = emptyList(),
        key: String = "monday:99",
        createdAt: Instant? = before,
        reason: String? = null,
    ) = ImportedRequest(
        key = key, title = " Colchones ", description = "", department = " Hotel ", estimatedAmount = amount?.let { BigDecimal(it) }, currency = "usd",
        requiredDate = null, priority = Priority.High, status = status, requesterName = "Ana", requesterEmail = "ana@x.com",
        decisionReason = reason, createdAt = createdAt, quotes = quotes,
    )

    private fun quote(key: String = "monday:99:quote", amount: String = "800", selected: Boolean = true, currency: String = "EUR") =
        ImportedQuote(key, " Proveedor ", BigDecimal(amount), currency, selected, "Luis")

    @Test
    fun approvedKeepsImportedDecisionAndFreezesTheEstimatedAmount() {
        val plan = ComprasImportEngine.plan(item(), now, true)
        assertEquals("Colchones", plan.title)
        assertEquals("Hotel", plan.department)
        assertEquals("USD", plan.currency)
        assertEquals("Importado de Monday", plan.decidedByName)
        assertEquals(before, plan.decidedAt)
        assertEquals(BigDecimal("1234.50"), plan.approvedAmount)
        assertEquals("USD", plan.approvedCurrency)
        assertEquals(before, plan.submittedAt)
        assertNull(plan.completedAt)
    }

    @Test
    fun selectedQuoteIsTheAmountToApprove() {
        val plan = ComprasImportEngine.plan(item(quotes = listOf(quote())), now, true)
        assertEquals(BigDecimal("800.00"), plan.approvedAmount)
        assertEquals("EUR", plan.approvedCurrency)
        assertEquals("Proveedor", plan.quotes.single().supplier)
    }

    @Test
    fun onlyOneQuoteStaysSelectedAndBadQuotesAreReported() {
        val plan = ComprasImportEngine.plan(
            item(quotes = listOf(quote("a"), quote("b"), quote("c", amount = "-5"), quote("d", currency = "EURO"), quote("a"))),
            now,
            true,
        )
        assertEquals(listOf("a", "b"), plan.quotes.map { it.key })
        assertEquals(listOf(true, false), plan.quotes.map { it.selected })
        assertEquals(2, plan.problems.size)
    }

    @Test
    fun rejectedKeepsReasonButNoApprovedAmount() {
        val plan = ComprasImportEngine.plan(item(RequestStatus.Rejected, reason = " Muy caro "), now, true)
        assertEquals("Muy caro", plan.decisionReason)
        assertEquals("Importado de Monday", plan.decidedByName)
        assertNull(plan.approvedAmount)
        assertNull(plan.approvedCurrency)
    }

    @Test
    fun completedSetsCompletionDateAndInProgressHasDecision() {
        assertEquals(before, ComprasImportEngine.plan(item(RequestStatus.Completed), now, true).completedAt)
        assertEquals("Importado de Monday", ComprasImportEngine.plan(item(RequestStatus.InProgress), now, true).decidedByName)
    }

    @Test
    fun statusesWithoutDecisionHaveNone() {
        listOf(RequestStatus.Draft, RequestStatus.Quoting, RequestStatus.PendingApproval, RequestStatus.Cancelled).forEach {
            val plan = ComprasImportEngine.plan(item(it), now, true)
            assertNull(plan.decidedByName, it.id)
            assertNull(plan.decidedAt, it.id)
            assertNull(plan.approvedAmount, it.id)
        }
        assertEquals(before, ComprasImportEngine.plan(item(RequestStatus.PendingApproval), now, true).submittedAt)
        assertNull(ComprasImportEngine.plan(item(RequestStatus.Draft), now, true).submittedAt)
    }

    @Test
    fun approvedWithoutAmountKeepsBothApprovedFieldsNull() {
        val plan = ComprasImportEngine.plan(item(amount = null), now, true)
        assertNull(plan.approvedAmount)
        assertNull(plan.approvedCurrency)
    }

    @Test
    fun missingCreatedAtUsesNow() {
        assertEquals(now, ComprasImportEngine.plan(item(createdAt = null), now, true).createdAt)
    }

    @Test
    fun invalidAmountIsDroppedWithAProblem() {
        val plan = ComprasImportEngine.plan(item(amount = "1.234"), now, true)
        assertNull(plan.estimatedAmount)
        assertTrue(plan.problems.single().startsWith("Monto omitido"))
    }

    @Test
    fun keyAndTitleAreRequired() {
        assertFailsWith<ComprasValidationException> { ComprasImportEngine.plan(item(key = ""), now, true) }
        assertFailsWith<ComprasValidationException> { ComprasImportEngine.plan(item(key = "sinprefijo"), now, true) }
        assertFailsWith<ComprasValidationException> { ComprasImportEngine.plan(item().copy(title = "  "), now, true) }
        assertFailsWith<ComprasValidationException> { ComprasImportEngine.plan(item().copy(currency = "US"), now, true) }
    }

    @Test
    fun longTextIsCutAndReported() {
        val plan = ComprasImportEngine.plan(item().copy(title = "t".repeat(300), description = "d".repeat(6000)), now, true)
        assertEquals(RequestDraft.MaxTitle, plan.title.length)
        assertEquals(RequestDraft.MaxDescription, plan.description.length)
        assertFalse(plan.problems.isEmpty())
    }

    @Test
    fun sourceLabelFallsBackToCapitalizedPrefix() {
        assertEquals("Monday", ComprasImportEngine.sourceLabel("monday:1"))
        assertEquals("Trello", ComprasImportEngine.sourceLabel("trello:abc"))
    }

    @Test
    fun withoutManageDecidedStatusesBecomePending() {
        listOf(RequestStatus.Approved, RequestStatus.Rejected, RequestStatus.InProgress, RequestStatus.Completed).forEach {
            val plan = ComprasImportEngine.plan(item(it, quotes = listOf(quote())), now, false)
            assertEquals(RequestStatus.PendingApproval, plan.status, it.id)
            assertNull(plan.decidedByName, it.id)
            assertNull(plan.approvedAmount, it.id)
            assertNull(plan.completedAt, it.id)
            assertTrue(plan.problems.single().contains("hace falta Administrar compras"), it.id)
        }
        // Los estados sin decision no cambian ni avisan.
        val draft = ComprasImportEngine.plan(item(RequestStatus.Draft), now, false)
        assertEquals(RequestStatus.Draft, draft.status)
        assertTrue(draft.problems.isEmpty())
    }

    @Test
    fun omittedQuotesMarkThePlanIncomplete() {
        assertTrue(ComprasImportEngine.plan(item(quotes = listOf(quote(amount = "-1"))), now, true).incomplete)
        assertFalse(ComprasImportEngine.plan(item(quotes = listOf(quote())), now, true).incomplete)
    }

    @Test
    fun requesterIsAnotherUserOnlyWithManageAndAUniqueEmailMatch() {
        val importer = java.util.UUID.randomUUID()
        val other = java.util.UUID.randomUUID()
        val match = other to "Ana Perez"
        assertEquals(other to "Ana", ComprasImportEngine.requesterFor(match, true, importer, "Beto", "Ana"))
        assertEquals(other to "Ana Perez", ComprasImportEngine.requesterFor(match, true, importer, "Beto", ""))
        // Sin manage o sin coincidencia: quien importa, con el nombre de la fuente.
        assertEquals(importer to "Ana", ComprasImportEngine.requesterFor(match, false, importer, "Beto", "Ana"))
        assertEquals(importer to "Ana", ComprasImportEngine.requesterFor(null, true, importer, "Beto", "Ana"))
        assertEquals(importer to "Beto", ComprasImportEngine.requesterFor(null, false, importer, "Beto", ""))
    }

    @Test
    fun noSourcesLinkedMeansNoneFound() {
        ComprasImportSources.link(emptyList())
        assertTrue(ComprasImportSources.all.isEmpty())
        assertNull(ComprasImportSources.byId("monday"))
    }
}
