package com.ideasdeveloper.idc.modules.crm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrmMoveToCustomersTest {
    private fun field(type: CrmFieldType, multiple: Boolean = false) = CrmField(UUID.randomUUID(), "cf_age", "Age", type, multiple = multiple)

    @Test
    fun createsTheFieldWithAnEquivalentType() {
        assertEquals(CrmMoveToCustomers.Plan.Create("number"), CrmMoveToCustomers.plan(field(CrmFieldType.Number), null))
        assertEquals(CrmMoveToCustomers.Plan.Create("date"), CrmMoveToCustomers.plan(field(CrmFieldType.Date), null))
        assertEquals(CrmMoveToCustomers.Plan.Create("boolean"), CrmMoveToCustomers.plan(field(CrmFieldType.Boolean), null))
        assertEquals(CrmMoveToCustomers.Plan.Create("select"), CrmMoveToCustomers.plan(field(CrmFieldType.Dropdown), null))
        assertEquals(CrmMoveToCustomers.Plan.Create("multi_select"), CrmMoveToCustomers.plan(field(CrmFieldType.Dropdown, multiple = true), null))
        assertEquals(CrmMoveToCustomers.Plan.Create("text"), CrmMoveToCustomers.plan(field(CrmFieldType.Url), null))
    }

    @Test
    fun sameKeyAndTypeIsReused() {
        assertEquals(CrmMoveToCustomers.Plan.Reuse, CrmMoveToCustomers.plan(field(CrmFieldType.Number), CrmMoveToCustomers.Existing("number", true)))
        assertEquals(CrmMoveToCustomers.Plan.Reuse, CrmMoveToCustomers.plan(field(CrmFieldType.Number), CrmMoveToCustomers.Existing("number", false)))
    }

    @Test
    fun sameKeyWithAnotherTypeCancelsTheMove() {
        val failure = assertFailsWith<CrmValidationException> {
            CrmMoveToCustomers.plan(field(CrmFieldType.Number), CrmMoveToCustomers.Existing("text", true))
        }
        assertTrue("cf_age" in failure.message.orEmpty() && "text" in failure.message.orEmpty())
        // A single list is not a multiple one.
        assertFailsWith<CrmValidationException> { CrmMoveToCustomers.plan(field(CrmFieldType.Dropdown), CrmMoveToCustomers.Existing("multi_select", true)) }
    }

    @Test
    fun filesFieldsCannotMove() {
        assertFailsWith<CrmValidationException> { CrmMoveToCustomers.plan(field(CrmFieldType.Files), null) }
    }

    @Test
    fun needsBothPermissions() {
        CrmMoveToCustomers.check(canManageCustomerFields = true, canSettings = true)
        assertFailsWith<CrmValidationException> { CrmMoveToCustomers.check(canManageCustomerFields = false, canSettings = true) }
        assertFailsWith<CrmValidationException> { CrmMoveToCustomers.check(canManageCustomerFields = true, canSettings = false) }
    }

    @Test
    fun emptyValuesCountAsNothing() {
        assertTrue(CrmMoveToCustomers.isEmptyValue(null))
        assertTrue(CrmMoveToCustomers.isEmptyValue(kotlinx.serialization.json.JsonNull))
        assertTrue(CrmMoveToCustomers.isEmptyValue(kotlinx.serialization.json.JsonPrimitive("")))
        assertTrue(CrmMoveToCustomers.isEmptyValue(kotlinx.serialization.json.JsonArray(emptyList())))
        assertFalse(CrmMoveToCustomers.isEmptyValue(kotlinx.serialization.json.JsonPrimitive("34")))
        assertFalse(CrmMoveToCustomers.isEmptyValue(kotlinx.serialization.json.JsonPrimitive(false)))
        assertFalse(CrmMoveToCustomers.isEmptyValue(kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("a")))))
    }
}
