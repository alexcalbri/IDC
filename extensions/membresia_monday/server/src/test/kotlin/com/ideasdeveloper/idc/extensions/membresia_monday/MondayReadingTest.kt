package com.ideasdeveloper.idc.extensions.membresia_monday

import com.ideasdeveloper.idc.modules.membresia.ImportPlanProposal
import com.ideasdeveloper.idc.modules.membresia.ImportSummary
import com.ideasdeveloper.idc.modules.membresia.ImportedMember
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MondayReadingTest {
    @Test
    fun classifiesTransientAndDefinitiveErrors() {
        assertEquals(MondayErrorKind.Transient, MondayReading.classify(429, "", ""))
        assertEquals(MondayErrorKind.Transient, MondayReading.classify(502, "", ""))
        assertEquals(MondayErrorKind.Transient, MondayReading.classify(200, "RATE_LIMIT_EXCEEDED", "Rate limit exceeded"))
        assertEquals(MondayErrorKind.Complexity, MondayReading.classify(200, "ComplexityException", "Query has complexity of 6000000"))
        assertEquals(MondayErrorKind.CursorExpired, MondayReading.classify(200, "", "CursorExpiredError: cursor expired"))
        assertEquals(MondayErrorKind.Permanent, MondayReading.classify(200, "InvalidColumnIdException", "Column not found"))
        assertEquals(MondayErrorKind.Permanent, MondayReading.classify(400, "", "bad query"))
    }

    @Test
    fun transientErrorsRetryAndFailAtTheFifthAttempt() {
        val first = MondayReading.recover(MondayErrorKind.Transient, "x", attempts = 0, pageSize = 50, restarts = 0)
        assertEquals(MondayReading.Step.Retry(1, 50), first)
        assertEquals(MondayReading.Step.Retry(4, 50), MondayReading.recover(MondayErrorKind.Transient, "x", 3, 50, 0))
        val last = MondayReading.recover(MondayErrorKind.Transient, "x", 4, 50, 0)
        assertIs<MondayReading.Step.Fail>(last)
    }

    @Test
    fun complexityShrinksThePageButNeverBelowTheMinimum() {
        assertEquals(MondayReading.Step.Retry(1, 25), MondayReading.recover(MondayErrorKind.Complexity, "", 0, 50, 0))
        assertEquals(MondayReading.Step.Retry(2, MondayReading.MinPageSize), MondayReading.recover(MondayErrorKind.Complexity, "", 1, 12, 0))
        assertEquals(MondayReading.MinPageSize, MondayReading.nextPageSize(MondayReading.MinPageSize))
    }

    @Test
    fun anExpiredCursorRestartsOnceAndThenFails() {
        assertEquals(MondayReading.Step.Restart, MondayReading.recover(MondayErrorKind.CursorExpired, "", 0, 50, restarts = 0))
        assertIs<MondayReading.Step.Fail>(MondayReading.recover(MondayErrorKind.CursorExpired, "", 0, 50, restarts = 1))
    }

    @Test
    fun permanentErrorsFailAndMessagesAreTrimmed() {
        val step = MondayReading.recover(MondayErrorKind.Permanent, "x".repeat(500), 0, 50, 0)
        assertIs<MondayReading.Step.Fail>(step)
        assertEquals(MondayReading.MaxMessage, step.message.length)
    }

    @Test
    fun detectsARepeatedCursor() {
        assertTrue(MondayReading.repeatedCursor("abc", "abc"))
        assertEquals(false, MondayReading.repeatedCursor("abc", "def"))
        assertEquals(false, MondayReading.repeatedCursor(null, ""))
        assertEquals(false, MondayReading.repeatedCursor("abc", ""))
    }

    @Test
    fun problemCodesCarryNoNames() {
        val ok = ImportedMember(key = "monday:1", name = "Ana", planName = "Flex", startsOn = LocalDate.of(2025, 1, 1), endsOn = LocalDate.of(2025, 2, 1), amount = null)
        val rows = listOf(
            ok,
            ok.copy(key = "monday:2", name = "", planName = ""),
            ok.copy(key = "monday:3", startsOn = null),
            ok.copy(key = "monday:4", endsOn = LocalDate.of(2024, 12, 1)),
        )
        val codes = MondayReading.problemCodes(rows, unreadable = 2)
        assertEquals(
            mapOf(MondayReading.NoName to 1, MondayReading.NoPlan to 1, MondayReading.NoDates to 1, MondayReading.EndBeforeStart to 1, MondayReading.Unreadable to 2),
            codes,
        )
        assertTrue(codes.keys.none { it.contains("Ana") })
    }

    @Test
    fun theSummaryRoundTripsThroughItsStoredJson() {
        val summary = ImportSummary(12, listOf(ImportPlanProposal("Hot Desk", BigDecimal("150.00"), 30, 2)), 40, listOf("País"), listOf("Ana: sin plan."))
        val dto = MondaySummaryDto.from(summary, mapOf(MondayReading.NoPlan to 1))
        val back = assertNotNull(MondaySummaryDto.decode(MondaySummaryDto.encode(dto)))
        assertEquals(dto, back)
        assertEquals(ImportPlanProposal("Hot Desk", BigDecimal("150.00"), 30, 2), back.newPlans.single().toProposal())
        // Al terminar el trabajo se quitan los problemas (llevan nombres): el resto del resumen queda.
        assertEquals(12, back.copy(problems = emptyList()).members)
    }

    @Test
    fun aWipedPayloadCannotBeDecodedAsAMember() {
        assertFails { MondayRowDto.decode("{}") }
    }

    @Test
    fun guestPassesAreParsedSafelyAndClamped() {
        fun passes(text: String): Int? {
            val cell = MondayCell("g", "Guest Passes", "numbers", text)
            val member = MembresiaMondayMapping.toMember(
                MondayItem("1", "Ana", "", listOf(cell)),
                MondayMapping(mapOf(MondayTarget.GuestPasses to "g")),
            )
            return member.guestPasses
        }
        assertEquals(3, passes("3"))
        assertEquals(3, passes("3.0"))
        assertEquals(null, passes("2.5"))
        assertEquals(0, passes("-2"))
        assertEquals(MembresiaMondayMapping.MaxGuestPasses, passes("5000"))
        assertEquals(null, passes("99999999999999999999"))
        assertEquals(null, passes(""))
    }
}
