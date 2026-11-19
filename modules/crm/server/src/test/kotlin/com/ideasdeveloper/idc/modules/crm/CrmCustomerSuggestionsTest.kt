package com.ideasdeveloper.idc.modules.crm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val customer = CrmRepository.CustomerSuggestion(id, "Ana Perez", "ana@example.com", "6123-4567", openLeads = 2, exact = true)

        val result = CrmScreens.customerSuggestions(listOf(customer))

        assertEquals("Ya existe un cliente con este correo o telefono: el lead se le asignara.", result.heading)
        val item = result.items.single()
        assertEquals("Ana Perez", item.title)
        // The customer code (WiFi portal credential) is never shown.
        assertEquals("ana@example.com - 6123-4567 - 2 leads abiertos", item.subtitle)
        assertTrue(CrmRepository.CustomerSuggestion::class.java.declaredFields.none { it.name.contains("code", ignoreCase = true) })
        assertEquals(mapOf("name" to "Ana Perez", "email" to "ana@example.com", "phone" to "6123-4567", "customerId" to id.toString()), item.values)
    }

    @Test
    fun nameMatchesWithoutExactContactAreJustListed() {
        val customer = CrmRepository.CustomerSuggestion(UUID.randomUUID(), "", "", "6123-4567", openLeads = 0, exact = false)

        val result = CrmScreens.customerSuggestions(listOf(customer))

        assertEquals("Clientes existentes", result.heading)
        assertEquals("6123-4567", result.items.single().title)
        assertEquals("6123-4567", result.items.single().subtitle)
    }

    private fun scope(seesAll: Boolean, interestsEnabled: Boolean = true, userId: UUID? = UUID.randomUUID(), interests: Set<UUID> = emptySet()) =
        CrmScope(userId, seesAll, reception = false, interestsEnabled = interestsEnabled, interests = interests)

    @Test
    fun onlySeeAllSearchesEveryCustomer() {
        assertNull(CrmRepository.customerScopeSql(scope(seesAll = true)))
        assertTrue(!CrmRepository.customerSearchRestricted(scope(seesAll = true)))
        assertTrue(CrmRepository.customerSearchRestricted(scope(seesAll = false)))
        assertTrue(CrmRepository.customerSearchRestricted(scope(seesAll = false, interestsEnabled = false)))
    }

    @Test
    fun aMatchedCustomerOutsideTheScopeIsNotLinked() {
        // Not restricted: used without even asking the scope.
        assertTrue(CrmRepository.acceptCustomer(restricted = false) { error("no se consulta") })
        assertTrue(CrmRepository.acceptCustomer(restricted = true) { true })
        assertTrue(!CrmRepository.acceptCustomer(restricted = true) { false })
    }

    @Test
    fun restrictedSearchKeepsCustomersWithALeadInTheUserScope() {
        val user = UUID.randomUUID()
        val interest = UUID.randomUUID()
        val (sql, params) = CrmRepository.customerScopeSql(scope(seesAll = false, userId = user, interests = setOf(interest)))!!

        assertTrue(sql.startsWith("EXISTS (SELECT 1 FROM crm_leads l WHERE l.customer_id = c.id AND "))
        assertEquals(listOf<Any>(user, listOf(interest)), params)
        // A user with nothing in scope sees no customer at all.
        assertEquals("FALSE", CrmRepository.customerScopeSql(scope(seesAll = false, userId = null))!!.first.substringAfter("AND ").removeSuffix(")"))
    }
}
