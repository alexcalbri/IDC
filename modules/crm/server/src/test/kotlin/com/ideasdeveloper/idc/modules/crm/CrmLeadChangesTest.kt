package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrmLeadChangesTest {
    private val lead = CrmLead(
        UUID.randomUUID(), UUID.randomUUID(), "Ana", "ana@example.com", "+50760000000", UUID.randomUUID(), null, emptyList(), "Web", "", "Hola", "",
        LocalDate.of(2026, 11, 2), null, BigDecimal("100.00"), null, Instant.now(), null, Instant.now(),
    )

    private fun input(phone: String = lead.phone, message: String = lead.message, value: BigDecimal? = lead.estimatedValue, end: LocalDate? = null) =
        CrmLeadInput(lead.name, lead.email, phone, lead.stageId, null, emptyList(), lead.source, lead.language, message, lead.details, lead.targetStart, end, value, null)

    // The history says what changed, not just that something did.
    @Test
    fun listsEachChangedValue() {
        val changes = CrmRepository.leadChanges(lead, input(phone = "+50761111111", message = "Otro texto"))
        assertEquals("Telefono: +50760000000 -> +50761111111\nMensaje modificado", changes)
    }

    // The dates are custom fields: the history names them with the label of the field.
    @Test
    fun dateChangesUseTheFieldLabel() {
        val departure = CrmField(UUID.randomUUID(), "fecha_salida", "Fecha de salida", CrmFieldType.Date)
        val sent = input().copy(custom = mapOf("fecha_salida" to JsonPrimitive("2026-11-06")))
        assertEquals("Fecha de salida: (vacio) -> 2026-11-06", CrmRepository.leadChanges(lead, sent, listOf(departure)))
    }

    @Test
    fun sameValuesAreNoChange() {
        assertNull(CrmRepository.leadChanges(lead, input(value = BigDecimal("100"))))
    }
}
