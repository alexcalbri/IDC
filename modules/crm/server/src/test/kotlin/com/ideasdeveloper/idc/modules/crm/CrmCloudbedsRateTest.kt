package com.ideasdeveloper.idc.modules.crm

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** El valor estimado del lead se reparte en tarifas por noche que suman exactamente ese valor. */
class CrmCloudbedsRateTest {

    private fun sum(parts: List<BigDecimal>) = parts.fold(BigDecimal.ZERO) { acc, v -> acc + v }

    @Test
    fun splitsEvenlyAndSumsExactly() {
        val parts = CrmReservations.splitEvenly(BigDecimal("300.00"), 2)
        assertEquals(listOf(BigDecimal("150.00"), BigDecimal("150.00")), parts)
        assertEquals(BigDecimal("300.00"), sum(parts))
    }

    @Test
    fun spareCentsGoFirstAndTotalIsPreserved() {
        val parts = CrmReservations.splitEvenly(BigDecimal("100.00"), 3)
        // 100 / 3 = 33.34 + 33.33 + 33.33
        assertEquals(listOf(BigDecimal("33.34"), BigDecimal("33.33"), BigDecimal("33.33")), parts)
        assertEquals(BigDecimal("100.00"), sum(parts))
    }

    @Test
    fun twoRoomsAcrossThreeNightsKeepsTheTotal() {
        val value = BigDecimal("1000.00")
        val perRoom = CrmReservations.splitEvenly(value, 2)
        val allNights = perRoom.flatMap { CrmReservations.splitEvenly(it, 3) }
        assertEquals(value, sum(allNights))
    }
}
