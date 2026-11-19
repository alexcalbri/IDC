package com.ideasdeveloper.idc.modules.membresia

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MembresiaImportTest {
    private val start = LocalDate.of(2026, 1, 1)

    private fun member(
        key: String = "k",
        name: String = "Ana",
        plan: String = "Mensual",
        days: Long? = 30,
        amount: String? = "50",
        guests: Int? = null,
        fields: Map<String, String> = emptyMap(),
        visits: Int = 0,
    ) = ImportedMember(
        key = key, name = name, planName = plan,
        startsOn = days?.let { start }, endsOn = days?.let { start.plusDays(it - 1) },
        amount = amount?.let(::BigDecimal), guestPasses = guests, customerFields = fields,
        visits = (1..visits).map { ImportedVisit("v$it", start.plusDays(it.toLong())) },
    )

    private fun plan(name: String) = Plan(UUID.randomUUID(), name, "", BigDecimal("10.00"), 30, null, 0, true, true, 0)

    @Test
    fun proposesTheMostCommonAmountAndMedianDays() {
        val rows = listOf(
            member("1", amount = "50", days = 30),
            member("2", amount = "50", days = 30),
            member("3", amount = "60", days = 90),
            member("4", amount = "70", days = 365),
            member("5", amount = "50", days = 31),
        )
        val proposal = MembresiaImportApply.proposePlans(rows, emptyList()).single()
        assertEquals("Mensual", proposal.name)
        assertEquals(BigDecimal("50.00"), proposal.price)
        assertEquals(31, proposal.durationDays)
    }

    @Test
    fun defaultsToThirtyDaysAndZeroPriceWithoutData() {
        val proposal = MembresiaImportApply.proposePlans(listOf(member(days = null, amount = null)), emptyList()).single()
        assertEquals(30, proposal.durationDays)
        assertEquals(BigDecimal("0.00"), proposal.price)
        assertEquals(0, proposal.guestPasses)
    }

    @Test
    fun medianOfAnEvenCountAveragesTheMiddle() {
        val rows = listOf(member("1", days = 30), member("2", days = 90))
        assertEquals(60, MembresiaImportApply.proposePlans(rows, emptyList()).single().durationDays)
    }

    @Test
    fun doesNotRepeatExistingPlansIgnoringCase() {
        val rows = listOf(member("1", plan = "MENSUAL"), member("2", plan = "Anual"), member("3", plan = "anual"), member("4", plan = " "))
        val proposals = MembresiaImportApply.proposePlans(rows, listOf(plan("mensual")))
        assertEquals(listOf("Anual"), proposals.map { it.name })
    }

    @Test
    fun proposesMostCommonGuestPasses() {
        val rows = listOf(member("1", guests = 2), member("2", guests = 2), member("3", guests = 5))
        assertEquals(2, MembresiaImportApply.proposePlans(rows, emptyList()).single().guestPasses)
    }

    @Test
    fun summaryCountsAndReportsProblems() {
        val rows = listOf(
            member("1", fields = mapOf("Documento" to "123", "País" to "")),
            member("2", name = " "),
            member("3", plan = ""),
            member("4", days = null),
            member("5", visits = 3, fields = mapOf("Documento" to "9", "Fecha de nacimiento" to "1990-01-01")),
            ImportedMember("6", "Beto", planName = "Mensual", startsOn = start, endsOn = start.minusDays(1), amount = null),
        )
        val summary = MembresiaImportApply.summary(rows, listOf(plan("Mensual")))
        assertEquals(6, summary.members)
        assertEquals(3, summary.visits)
        assertEquals(emptyList(), summary.newPlans.filter { it.name == "Mensual" })
        assertEquals(listOf("Documento", "Fecha de nacimiento"), summary.customerFields)
        assertEquals(4, summary.problems.size)
        assertTrue(summary.problems.any { it.contains("sin nombre") })
        assertTrue(summary.problems.any { it.contains("sin plan") })
        assertTrue(summary.problems.any { it.contains("sin fechas") })
        assertTrue(summary.problems.any { it.contains("anterior al inicio") })
    }

    @Test
    fun summaryOfCleanRowsHasNoProblems() {
        val summary = MembresiaImportApply.summary(listOf(member("1"), member("2")), emptyList())
        assertEquals(emptyList(), summary.problems)
        assertEquals(listOf("Mensual"), summary.newPlans.map { it.name })
    }

    @Test
    fun fieldKeysAreSlugsWithoutAccents() {
        val pattern = Regex("^[a-z][a-z0-9_]{0,62}$")
        assertEquals("pais", MembresiaImportApply.fieldKeyFor("País"))
        assertEquals("fecha_de_nacimiento", MembresiaImportApply.fieldKeyFor("Fecha de nacimiento"))
        assertEquals("contacto_de_emergencia", MembresiaImportApply.fieldKeyFor("  Contacto de emergencia! "))
        assertEquals("c_2fa", MembresiaImportApply.fieldKeyFor("2FA"))
        assertEquals("campo", MembresiaImportApply.fieldKeyFor("???"))
        listOf("País", "Documento / Pasaporte", "2FA", "???", "x".repeat(200)).forEach {
            assertTrue(pattern.matches(MembresiaImportApply.fieldKeyFor(it)), it)
        }
    }

    @Test
    fun birthFieldsAreDatesAndTheRestText() {
        assertEquals("date", MembresiaImportApply.fieldTypeFor("Fecha de nacimiento"))
        assertEquals("date", MembresiaImportApply.fieldTypeFor("Birth date"))
        assertEquals("text", MembresiaImportApply.fieldTypeFor("Documento"))
    }

    @Test
    fun planNamesAreTrimmedAndLimitedTo80() {
        assertEquals("Mensual", MembresiaImportApply.normalizePlanName("  Mensual "))
        assertEquals(80, MembresiaImportApply.normalizePlanName("x".repeat(120)).length)
        val rows = listOf(member("1", plan = "  Mensual "), member("2", plan = "mensual"))
        assertEquals(emptyList(), MembresiaImportApply.proposePlans(rows, listOf(plan("Mensual"))))
        assertEquals("Mensual", MembresiaImportApply.proposePlans(rows, emptyList()).single().name)
        val long = "y".repeat(100)
        assertEquals(1, MembresiaImportApply.proposePlans(listOf(member("1", plan = long), member("2", plan = long)), emptyList()).size)
    }

    @Test
    fun onlyActiveTextOrSameTypeFieldsAreCompatible() {
        assertTrue(MembresiaImportApply.compatibleField(true, "text", "text"))
        assertTrue(MembresiaImportApply.compatibleField(true, "text", "date"))
        assertTrue(MembresiaImportApply.compatibleField(true, "textarea", "text"))
        assertTrue(MembresiaImportApply.compatibleField(true, "date", "date"))
        assertEquals(false, MembresiaImportApply.compatibleField(true, "date", "text"))
        assertEquals(false, MembresiaImportApply.compatibleField(true, "number", "text"))
        assertEquals(false, MembresiaImportApply.compatibleField(true, "select", "date"))
        assertEquals(false, MembresiaImportApply.compatibleField(false, "text", "text"))
    }
}
