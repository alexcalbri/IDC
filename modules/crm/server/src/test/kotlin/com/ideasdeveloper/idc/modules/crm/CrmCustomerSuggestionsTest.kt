package com.ideasdeveloper.idc.modules.crm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CrmCustomerSuggestionsTest {
    @Test
    fun phonesAreComparedByTheirDigits() {
        assertEquals("50761234567", CrmRepository.phoneDigits("+507 6123-4567"))
        assertEquals("61234567", CrmRepository.phoneDigits("(612) 345 67"))
        // Too short to identify a person.
        assertEquals("", CrmRepository.phoneDigits("123-45"))
    }

    @Test
    fun choosingASuggestionFillsTheContactAndLinksTheCustomer() {
        val id = UUID.randomUUID()
        val customer = CrmRepository.CustomerSuggestion(id, "Ana Perez", "ana@example.com", "6123-4567", "C-0012", openLeads = 2, exact = true)

        val result = CrmScreens.customerSuggestions(listOf(customer))

        assertEquals("Ya existe un cliente con este correo o telefono: el lead se le asignara.", result.heading)
        val item = result.items.single()
        assertEquals("Ana Perez", item.title)
        assertEquals("C-0012 - ana@example.com - 6123-4567 - 2 leads abiertos", item.subtitle)
        assertEquals(mapOf("name" to "Ana Perez", "email" to "ana@example.com", "phone" to "6123-4567", "customerId" to id.toString()), item.values)
    }

    @Test
    fun nameMatchesWithoutExactContactAreJustListed() {
        val customer = CrmRepository.CustomerSuggestion(UUID.randomUUID(), "", "", "6123-4567", null, openLeads = 0, exact = false)

        val result = CrmScreens.customerSuggestions(listOf(customer))

        assertEquals("Clientes existentes", result.heading)
        assertEquals("6123-4567", result.items.single().title)
        assertEquals("6123-4567", result.items.single().subtitle)
    }
}
