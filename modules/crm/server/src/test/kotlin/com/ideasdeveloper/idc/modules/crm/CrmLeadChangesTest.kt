package com.ideasdeveloper.idc.modules.crm

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrmLeadChangesTest {
    private val lead = CrmLead(
        UUID.randomUUID(), UUID.randomUUID(), "Ana", "ana@example.com", "+50760000000", UUID.randomUUID(), null, null, "Web", "", "Hola", "",
        LocalDate.of(2026, 11, 2), null, BigDecimal("100.00"), null, Instant.now(), null, Instant.now(),
    )

    private fun input(phone: String = lead.phone, message: String = lead.message, value: BigDecimal? = lead.estimatedValue, end: LocalDate? = null) =
        CrmLeadInput(lead.name, lead.email, phone, lead.stageId, null, null, lead.source, lead.language, message, lead.details, lead.targetStart, end, value, null)

    // The history says what changed, not just that something did.
    @Test
    fun listsEachChangedValue() {
        val changes = CrmRepository.leadChanges(lead, input(phone = "+50761111111", message = "Otro texto", end = LocalDate.of(2026, 11, 6)))
        assertEquals("Telefono: +50760000000 -> +50761111111\nMensaje modificado\nFecha de fin: (vacio) -> 2026-11-06", changes)
    }

    @Test
    fun sameValuesAreNoChange() {
        assertNull(CrmRepository.leadChanges(lead, input(value = BigDecimal("100"))))
    }
}
