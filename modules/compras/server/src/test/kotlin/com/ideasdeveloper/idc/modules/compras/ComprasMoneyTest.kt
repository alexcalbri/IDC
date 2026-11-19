package com.ideasdeveloper.idc.modules.compras

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ComprasMoneyTest {
    @Test
    fun parsesDotAndCommaDecimals() {
        assertEquals(BigDecimal("1234.50"), ComprasMoney.parse("1,234.50"))
        assertEquals(BigDecimal("1234.50"), ComprasMoney.parse("1.234,50"))
        assertEquals(BigDecimal("12.50"), ComprasMoney.parse("12,5"))
        assertEquals(BigDecimal("12.50"), ComprasMoney.parse("12.5"))
        assertEquals(BigDecimal("1234.00"), ComprasMoney.parse("1,234"))
        assertEquals(BigDecimal("1234567.00"), ComprasMoney.parse("1.234.567"))
        assertEquals(BigDecimal("1000.00"), ComprasMoney.parse(" 1000 "))
    }

    @Test
    fun resultAlwaysHasTwoDecimals() {
        assertEquals(2, ComprasMoney.parse("5").scale())
    }

    @Test
    fun blankIsNullWhenOptionalAndFailsWhenRequired() {
        assertNull(ComprasMoney.parseOptional("  "))
        assertNull(ComprasMoney.parseOptional(null))
        assertFailsWith<ComprasValidationException> { ComprasMoney.parse("") }
    }

    @Test
    fun rejectsNegativeTooManyDecimalsGarbageAndHugeAmounts() {
        assertFailsWith<ComprasValidationException> { ComprasMoney.parse("-5") }
        assertFailsWith<ComprasValidationException> { ComprasMoney.parse("1.234") }
        assertFailsWith<ComprasValidationException> { ComprasMoney.parse("abc") }
        assertFailsWith<ComprasValidationException> { ComprasMoney.parse("1e5") }
        assertFailsWith<ComprasValidationException> { ComprasMoney.parse("1000000000000") }
        assertEquals(BigDecimal("999999999999.99"), ComprasMoney.parse("999999999999.99"))
    }

    @Test
    fun checkRejectsNegativeAndMoreThanTwoDecimals() {
        assertFailsWith<ComprasValidationException> { ComprasMoney.check(BigDecimal("-0.01")) }
        assertFailsWith<ComprasValidationException> { ComprasMoney.check(BigDecimal("1.005")) }
        assertEquals(BigDecimal("1.50"), ComprasMoney.check(BigDecimal("1.5")))
    }

    @Test
    fun formatsWithGroupingAndCurrency() {
        assertEquals("USD 1,234.50", ComprasMoney.format(BigDecimal("1234.5"), "USD"))
        assertEquals("EUR 0.00", ComprasMoney.format(BigDecimal.ZERO, "EUR"))
        assertEquals("USD 1,234,567.89", ComprasMoney.format(BigDecimal("1234567.89"), "USD"))
        assertEquals("USD 999.00", ComprasMoney.format(BigDecimal("999"), "USD"))
    }

    @Test
    fun currencyIsNormalizedOrRejected() {
        assertEquals("USD", ComprasMoney.currency(" usd "))
        assertFailsWith<ComprasValidationException> { ComprasMoney.currency("US") }
        assertFailsWith<ComprasValidationException> { ComprasMoney.currency("U5D") }
        assertFailsWith<ComprasValidationException> { ComprasMoney.currency(null) }
    }

    @Test
    fun folioRoundTrips() {
        assertEquals("C-00012", ComprasFolio.format(12))
        assertEquals(12L, ComprasFolio.parse("C-00012"))
        assertEquals(12L, ComprasFolio.parse("c-12"))
        assertEquals(12L, ComprasFolio.parse("12"))
        assertNull(ComprasFolio.parse("abc"))
        assertNull(ComprasFolio.parse("C-"))
        assertEquals("C-123456", ComprasFolio.format(123456))
        assertEquals(123456L, ComprasFolio.parse("C-123456"))
    }
}
