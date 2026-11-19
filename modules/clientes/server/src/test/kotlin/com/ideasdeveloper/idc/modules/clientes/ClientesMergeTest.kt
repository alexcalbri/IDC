package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.GroupCompany
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientesMergeTest {
    private val group = setOf("alfa", "beta", "gamma")

    private fun row(
        name: String,
        email: String = "ana@ejemplo.com",
        phone: String = "",
        at: String = "2024-01-01T00:00:00Z",
        id: UUID = UUID.randomUUID(),
        attributes: String = "{}",
        status: String = "active",
    ) = ClientesSharing.Row(id, name, email, phone, status, "general", "ABCDEFGH", attributes, Instant.parse(at))

    private fun candidate(row: ClientesSharing.Row, origin: String) = ClientesMerge.Candidate(row, origin)

    @Test
    fun oldestCustomerWinsAndTieGoesToSmallerId() {
        val old = candidate(row("Vieja", at = "2023-05-01T00:00:00Z"), "alfa")
        val new = candidate(row("Nueva", at = "2024-05-01T00:00:00Z"), "beta")
        assertEquals("Vieja", ClientesMerge.order(new, old).first.row.displayName)
        assertEquals("Vieja", ClientesMerge.order(old, new).first.row.displayName)

        val small = candidate(row("Uno", id = UUID.fromString("00000000-0000-0000-0000-000000000001")), "alfa")
        val big = candidate(row("Dos", id = UUID.fromString("00000000-0000-0000-0000-000000000002")), "beta")
        assertEquals("Uno", ClientesMerge.order(big, small).first.row.displayName)
        assertEquals("Dos", ClientesMerge.order(big, small).second.row.displayName)
    }

    @Test
    fun decisionCoversCopyMergeAndSeparated() {
        val incoming = candidate(row("Ana Perez"), "alfa")
        val other = candidate(row("Ana P.", email = "ANA@ejemplo.com", at = "2020-01-01T00:00:00Z"), "beta")

        assertIs<ClientesMerge.Decision.Copy>(ClientesMerge.decide(incoming, null, group, separated = false))
        assertIs<ClientesMerge.Decision.Merge>(ClientesMerge.decide(incoming, other, group, separated = false))
        assertIs<ClientesMerge.Decision.Skip>(ClientesMerge.decide(incoming, other, group, separated = true))
        // Same company: not a duplicate between companies. Company that is not linked: never touched.
        assertIs<ClientesMerge.Decision.Copy>(ClientesMerge.decide(incoming, candidate(other.row, "alfa"), group, separated = false))
        assertIs<ClientesMerge.Decision.Copy>(ClientesMerge.decide(incoming, candidate(other.row, "omega"), group, separated = false))
        // No email, no merge.
        assertIs<ClientesMerge.Decision.Copy>(
            ClientesMerge.decide(candidate(row("Sin correo", email = ""), "alfa"), candidate(row("Otro", email = ""), "beta"), group, separated = false)
        )
    }

    @Test
    fun sameEmailWithDifferentNamesIsMergedAndStaysVisibleAndSeparable() {
        val ana = candidate(row("Ana Perez", at = "2024-06-01T00:00:00Z"), "alfa")
        val maria = candidate(row("Maria Lopez", at = "2022-03-01T00:00:00Z"), "beta")

        val decision = ClientesMerge.decide(ana, maria, group, separated = false)
        assertIs<ClientesMerge.Decision.Merge>(decision)
        assertEquals("Maria Lopez", decision.winner.row.displayName)
        assertEquals("Ana Perez", decision.loser.row.displayName)

        // The text says it was the email only, who stays and how to undo it.
        val reason = ClientesMerge.mergedReason(decision.winner.row.displayName, decision.loser.row.displayName, "ana@ejemplo.com")
        assertContains(reason, "nombres distintos")
        assertContains(reason, "Maria Lopez")
        assertContains(reason, "Separar")
        assertFalse(ClientesMerge.sameName("Ana Perez", "Maria Lopez"))
        assertEquals(
            "Fusionado con Ana Perez (Alfa) por el correo ana@ejemplo.com",
            ClientesMerge.mergedActivity("Ana Perez", "Alfa", "ana@ejemplo.com"),
        )

        // What the loser's company stores: a merged row that lists what moved, so Separar can move it back.
        val key = ClientesMerge.ForeignKey("public", "customer_history", "customer_id", "id")
        val movedJson = ClientesMerge.movedToJson(listOf(ClientesMerge.movedFor("alfa", key, listOf("a1", "a2"))))
        val item = MergeItem(
            id = UUID.randomUUID(), company = GroupCompany("alfa", "Alfa"), keptId = decision.winner.row.id, keptName = "Maria Lopez",
            keptCompany = "Beta", mergedId = decision.loser.row.id, mergedName = "Ana Perez", email = "ana@ejemplo.com",
            status = "merged", reason = reason, at = OffsetDateTime.of(2024, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC),
        )
        assertEquals("merged", item.status)
        assertContains(item.reason, "nombres distintos")
        val moved = ClientesMerge.parseMoved(movedJson)
        assertEquals(1, moved.size)
        assertTrue(moved.single().reversible)
        assertEquals(listOf("a1", "a2"), moved.single().ids)
        // Separate puts the rows back with a statement that only touches the listed rows.
        val revert = moved.single()
        val sql = ClientesMerge.revertSql(ClientesMerge.ForeignKey(revert.schema, revert.table, revert.column, revert.pk))
        assertEquals("""UPDATE "public"."customer_history" SET "customer_id" = ? WHERE "customer_id" = ? AND "id"::text = ANY (?)""", sql)
    }

    @Test
    fun sameEmailAndSameNameSaysSo() {
        val reason = ClientesMerge.mergedReason("Ana Perez", " ana  PEREZ ", "ana@ejemplo.com")
        assertContains(reason, "mismo nombre")
        assertFalse(reason.contains("Separar"))
    }

    @Test
    fun emptyFieldsOfTheWinnerAreCompletedFromTheLoser() {
        val winner = row("Ana", email = "", phone = "", attributes = """{"ciudad":"","edad":30,"gustos":[]}""")
        val loser = row("Ana B", email = "ana@ejemplo.com", phone = "555", attributes = """{"ciudad":"Lima","edad":99,"gustos":["te"],"nota":"x"}""")
        val filled = ClientesMerge.fillEmpty(winner, loser)
        assertEquals("ana@ejemplo.com", filled.email)
        assertEquals("555", filled.phone)
        assertTrue(filled.changed)
        assertContains(filled.filled, "correo")
        assertContains(filled.filled, "campo ciudad")
        assertContains(filled.filled, "campo nota")
        assertTrue(filled.attributes.contains("\"Lima\""))
        // A value the winner already has is never replaced.
        assertTrue(filled.attributes.contains("30") && !filled.attributes.contains("99"))
    }

    @Test
    fun nothingToCompleteKeepsTheWinnerAsIs() {
        val winner = row("Ana", phone = "111", attributes = """{"a":"1"}""")
        val filled = ClientesMerge.fillEmpty(winner, row("Ana B", phone = "222", attributes = """{"a":"2"}"""))
        assertFalse(filled.changed)
        assertEquals("111", filled.phone)
        assertEquals("""{"a":"1"}""", filled.attributes)
    }

    @Test
    fun catalogIdentifiersAreQuotedWithInnerQuotesDoubled() {
        assertEquals("\"tabla\"\"rara\"", ClientesMerge.quote("tabla\"rara"))
        val key = ClientesMerge.ForeignKey("pub\"lic", "ventas\"; DROP TABLE x; --", "customer\"_id", "id")
        assertEquals(
            """UPDATE "pub""lic"."ventas""; DROP TABLE x; --" SET "customer""_id" = ? WHERE "customer""_id" = ? RETURNING "id"::text""",
            ClientesMerge.repointSql(key),
        )
        // Without a simple primary key nothing is returned and the move cannot be reverted.
        val noPk = ClientesMerge.ForeignKey("public", "t", "c", null)
        assertEquals("""UPDATE "public"."t" SET "c" = ? WHERE "c" = ?""", ClientesMerge.repointSql(noPk))
        val moved = ClientesMerge.movedFor("alfa", noPk, emptyList(), count = 7)
        assertFalse(moved.reversible)
        assertNull(ClientesMerge.parseMoved(ClientesMerge.movedToJson(listOf(moved))).single().pk)
    }

    @Test
    fun mergeLogAndNotesAreNotRepointedInBulk() {
        val keys = listOf("customer_history", "customer_merges", "customer_notes", "customer_activity", "ventas")
            .map { ClientesMerge.ForeignKey("public", it, "customer_id", "id") }
        assertEquals(listOf("customer_history", "customer_activity", "ventas"), ClientesMerge.repointable(keys).map { it.table })
    }

    @Test
    fun pendingPassSelectsUnsharedOwnCustomersAndSkipsMergedLosersAndReviews() {
        val sql = ClientesLinking.pendingSql(withCursor = false)
        assertContains(sql, "origin_company_code = '' AND NOT shared")
        assertContains(sql, "m.status IN ('merged', 'review')")
        assertFalse(sql.contains("id > ?"))
        assertContains(ClientesLinking.pendingSql(withCursor = true), "AND id > ?")
    }

    @Test
    fun sharedIsMarkedOnlyWhenEveryLinkedCompanyHasTheCustomer() {
        val ok = ClientesLinking.ShareOutcome.Copied
        assertTrue(ClientesLinking.fullyShared(listOf(ok, ClientesLinking.ShareOutcome.Merged, ClientesLinking.ShareOutcome.Skipped), 3))
        assertFalse(ClientesLinking.fullyShared(listOf(ok, ClientesLinking.ShareOutcome.Review), 2))
        // A company that was not reached (fewer outcomes than companies), or nobody to share with.
        assertFalse(ClientesLinking.fullyShared(listOf(ok), 2))
        assertFalse(ClientesLinking.fullyShared(emptyList(), 0))
    }

    @Test
    fun copyReviewKeyIsStablePerTargetAndRecognised() {
        val customer = UUID.randomUUID()
        val toBeta = ClientesLinking.copyReviewKey(customer, "beta")
        assertEquals(toBeta, ClientesLinking.copyReviewKey(customer, "beta"))
        assertFalse(toBeta == ClientesLinking.copyReviewKey(customer, "gamma"))
        assertTrue(ClientesLinking.isCopyReview(customer, toBeta, "beta"))
        assertFalse(ClientesLinking.isCopyReview(customer, toBeta, "gamma"))
        assertFalse(ClientesLinking.isCopyReview(customer, UUID.randomUUID(), "beta"))
    }

    @Test
    fun foreignKeyCatalogQueryDoesNotDependOnTheSearchPath() {
        assertContains(ClientesMerge.ForeignKeysSql, "to_regclass('public.customers')")
        assertFalse(ClientesMerge.ForeignKeysSql.contains("::regclass"))
    }

    @Test
    fun completedFieldsAreRevertedOnlyIfNobodyEditedThemSince() {
        val before = row("Ana", email = "", phone = "", attributes = """{"ciudad":""}""")
        val filled = ClientesMerge.fillEmpty(before, row("Ana B", email = "ana@ejemplo.com", phone = "555", attributes = """{"ciudad":"Lima"}"""))
        val entry = assertNotNull(ClientesMerge.filledMoved("beta", before, filled))
        val parsed = ClientesMerge.parseMoved(ClientesMerge.movedToJson(listOf(entry))).single()
        assertEquals("filled", parsed.kind)
        assertTrue(parsed.reversible)
        // Untouched since the merge: it can go back (the attribute order does not matter).
        assertTrue(ClientesMerge.filledStillApplies(parsed, filled.email, filled.phone, filled.attributes))
        assertTrue(ClientesMerge.filledStillApplies(parsed, "ana@ejemplo.com", "555", """{ "ciudad" : "Lima" }"""))
        // Edited later: kept.
        assertFalse(ClientesMerge.filledStillApplies(parsed, "otro@ejemplo.com", "555", filled.attributes))
        assertFalse(ClientesMerge.filledStillApplies(parsed, filled.email, filled.phone, """{"ciudad":"Cusco"}"""))
        // Nothing completed, nothing to record.
        assertNull(ClientesMerge.filledMoved("beta", before, ClientesMerge.fillEmpty(before, before)))
    }

    @Test
    fun onlyRealForeignKeysMayBeRevertedFromTheStoredLog() {
        val keys = listOf(ClientesMerge.ForeignKey("public", "customer_history", "customer_id", "id"))
        val good = ClientesMerge.movedFor("alfa", keys.single(), listOf("a1"))
        assertTrue(ClientesMerge.allowed(good, keys))
        // A table or column that is not a foreign key of the database (a tampered log) is refused.
        assertFalse(ClientesMerge.allowed(ClientesMerge.movedFor("alfa", ClientesMerge.ForeignKey("public", "users", "customer_id", "id"), listOf("a1")), keys))
        assertFalse(ClientesMerge.allowed(ClientesMerge.movedFor("alfa", ClientesMerge.ForeignKey("public", "customer_history", "id", "id"), listOf("a1")), keys))
        assertFalse(ClientesMerge.allowed(ClientesMerge.movedFor("alfa", ClientesMerge.ForeignKey("public", "customer_history", "customer_id", "other"), listOf("a1")), keys))
        // Never the merge log or the notes, even if a foreign key points there.
        val log = listOf(ClientesMerge.ForeignKey("public", "customer_merges", "merged_id", "id"))
        assertFalse(ClientesMerge.allowed(ClientesMerge.movedFor("alfa", log.single(), listOf("x")), log))
        assertFalse(ClientesMerge.allowed(Moved_note(), keys))
    }

    private fun Moved_note() = ClientesMerge.Moved("alfa", "note")

    @Test
    fun newDestinationsMarkEveryOwnCustomerToBeCopied() {
        assertEquals(listOf("gamma"), ClientesLinking.missingTargets(setOf("beta"), listOf("beta", "gamma")))
        assertTrue(ClientesLinking.missingTargets(setOf("beta"), listOf("beta")).isEmpty())
        assertContains(ClientesLinking.ResetSharedSql, "SET shared = FALSE")
        assertContains(ClientesLinking.ResetSharedSql, "origin_company_code = ''")
    }

    @Test
    fun poolTimeoutsAndConnectionErrorsAreNotReviews() {
        assertTrue(ClientesLinking.isConnectionFailure(java.sql.SQLTransientConnectionException("Connection is not available, request timed out after 5000ms")))
        assertTrue(ClientesLinking.isConnectionFailure(java.sql.SQLException("could not connect", "08001")))
        assertTrue(ClientesLinking.isConnectionFailure(RuntimeException("wrapper", java.sql.SQLException("x", "08006"))))
        assertFalse(ClientesLinking.isConnectionFailure(java.sql.SQLException("duplicate key value violates unique constraint", "23505")))
        assertFalse(ClientesLinking.isConnectionFailure(null))
    }

    @Test
    fun linkedAccountsClashHasASpecificReason() {
        assertEquals(
            "Las dos fichas tienen una cuenta de usuario enlazada; desenlaza una y pulsa Reintentar.",
            ClientesMerge.failureReason("Alfa", "customer_accounts"),
        )
    }

    @Test
    fun failureReasonIsShortAndHasNoSql() {
        val reason = ClientesMerge.failureReason("Alfa", "ventas")
        assertContains(reason, "ventas")
        assertFalse(reason.contains("ERROR") || reason.contains("constraint"))
    }

    @Test
    fun movedNotesSurviveTheJsonRoundTrip() {
        val note = buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("body", "Le gusta el te")
        }
        val parsed = ClientesMerge.parseMoved(ClientesMerge.movedToJson(listOf(ClientesMerge.Moved("alfa", "note", note = note)))).single()
        assertEquals("note", parsed.kind)
        assertTrue(parsed.reversible)
        assertNotNull(parsed.note)
        assertTrue(ClientesMerge.parseMoved("not json").isEmpty())
    }
}
