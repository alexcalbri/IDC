package com.ideasdeveloper.idc.modules.crm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CrmFieldSectionsTest {
    private val reserva = CrmFieldSection(UUID.randomUUID(), "Datos de la reserva", 1)
    private val familia = CrmFieldSection(UUID.randomUUID(), "Familia", 0)
    private val sections = listOf(reserva, familia)

    private fun field(key: String, position: Int, section: CrmFieldSection? = null, label: String = key) =
        CrmField(UUID.randomUUID(), key, label, CrmFieldType.Text, position = position, sectionId = section?.id)

    @Test
    fun fieldsAreOrderedBySectionPositionThenFieldPosition() {
        val salida = field("fecha_salida", 1, reserva)
        val ingreso = field("fecha_ingreso", 0, reserva)
        val hijos = field("cf_hijos", 5, familia)
        val suelto = field("cf_suelto", 0)
        val ordered = CrmFieldSections.ordered(listOf(salida, suelto, hijos, ingreso), sections)
        // Familia (position 0), Datos de la reserva (1), then the fields without section.
        assertEquals(listOf("cf_hijos", "fecha_ingreso", "fecha_salida", "cf_suelto"), ordered.map { it.key })
    }

    @Test
    fun fieldsOfASectionStayTogether() {
        // The form groups consecutive fields of the same section: no field of another section may fall in between.
        val fields = listOf(field("a", 3, reserva), field("b", 0, familia), field("c", 1, reserva), field("d", 1, familia), field("e", 0))
        val titles = CrmFieldSections.ordered(fields, sections).map { CrmFieldSections.title(it, sections) }
        assertEquals(listOf("Familia", "Familia", "Datos de la reserva", "Datos de la reserva", CrmFields.Section), titles)
    }

    @Test
    fun aDeletedSectionLeavesItsFieldsInTheDefaultSection() {
        val orphan = field("cf_x", 0, CrmFieldSection(UUID.randomUUID(), "Borrada"))
        assertEquals(CrmFields.Section, CrmFieldSections.title(orphan, sections))
        assertEquals("Datos de la reserva", CrmFieldSections.title(field("cf_y", 0, reserva), sections))
    }

    @Test
    fun sectionFormIsValidated() {
        val input = CrmFieldSections.parse(mapOf("sectionName" to " Notas "), sections, null)
        assertEquals(CrmFieldSectionInput("Notas"), input)
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to " "), sections, null) }
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to "familia"), sections, null) }
        // Renaming a section to its own name is fine.
        CrmFieldSections.parse(mapOf("sectionName" to "Familia"), sections, familia.id)
        // The sections of the fixed part of the form cannot be reused.
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to "Contacto"), sections, null) }
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to CrmFields.Section), sections, null) }
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to "datos del cliente"), sections, null) }
    }

    @Test
    fun fieldFormAssignsTheSection() {
        val named = CrmFields.parseDefinition(mapOf("label" to "Hijos", "type" to "Texto", "section" to "Familia"), null, sections)
        assertEquals(familia.id, named.sectionId)
        assertNull(CrmFields.parseDefinition(mapOf("label" to "Hijos", "type" to "Texto", "section" to CrmFields.Section), null, sections).sectionId)
        assertNull(CrmFields.parseDefinition(mapOf("label" to "Hijos", "type" to "Texto"), null, sections).sectionId)
        assertFailsWith<CrmValidationException> { CrmFields.parseDefinition(mapOf("label" to "Hijos", "type" to "Texto", "section" to "Otra"), null, sections) }
    }

    private fun system(key: String, position: Int) =
        CrmFieldSection(UUID.randomUUID(), CrmFieldSections.system.first { it.first == key }.second, position, key)

    private val customer = system(CrmFieldSections.Customer, 5)
    private val followup = system(CrmFieldSections.Followup, 0)
    private val message = system(CrmFieldSections.Message, 2)
    private val documents = system(CrmFieldSections.Documents, 1)
    private val all = listOf(followup, documents, message, reserva.copy(position = 4), customer)

    @Test
    fun customerDataIsAlwaysFirstWhateverThePositionsSay() {
        // Its stored position is the last one, yet it leads; the rest follow their positions.
        assertEquals(
            listOf("Datos del cliente", "Seguimiento", "Documentos", "Mensaje y detalles", "Datos de la reserva"),
            CrmFieldSections.sorted(all).map { it.name },
        )
        // A database before V020 has no built-in rows: they are added in their original order.
        assertEquals(
            listOf("Datos del cliente", "Seguimiento", "Mensaje y detalles", "Documentos"),
            CrmFieldSections.sorted(emptyList()).map { it.name },
        )
    }

    @Test
    fun aCustomFieldInABuiltInSectionRendersInsideIt() {
        val inFollowup = field("cf_age", 0, followup)
        val own = field("cf_hijos", 0, familia)
        val loose = field("cf_suelto", 0)
        val ordered = CrmFieldSections.ordered(listOf(loose, own, inFollowup), all + familia.copy(position = 6))
        // Seguimiento comes before the company's Familia; the field without section is last.
        assertEquals(listOf("cf_age", "cf_hijos", "cf_suelto"), ordered.map { it.key })
        assertEquals("Seguimiento", CrmFieldSections.title(inFollowup, all))
    }

    @Test
    fun builtInSectionsCannotBeDeleted() {
        assertFailsWith<CrmValidationException> { CrmFieldSections.checkDeletable(customer) }
        assertFailsWith<CrmValidationException> { CrmFieldSections.checkDeletable(followup) }
        CrmFieldSections.checkDeletable(reserva)
    }

    @Test
    fun sectionsReorderButNothingGoesBeforeCustomerData() {
        val order = CrmFieldSections.sorted(all) // Datos del cliente, Seguimiento, Documentos, Mensaje, Datos de la reserva
        // Customer data never moves.
        assertNull(CrmFieldSections.reorder(all, customer.id, up = false))
        assertNull(CrmFieldSections.reorder(all, customer.id, up = true))
        // The first one after it cannot go up (it would pass customer data); the last cannot go down.
        assertNull(CrmFieldSections.reorder(all, order[1].id, up = true))
        assertNull(CrmFieldSections.reorder(all, order.last().id, up = false))
        // A built-in section moves like any other and positions are renumbered.
        val moved = CrmFieldSections.reorder(all, message.id, up = true)!!
        assertEquals(listOf("Datos del cliente", "Seguimiento", "Mensaje y detalles", "Documentos", "Datos de la reserva"), moved.map { it.name })
        assertEquals(listOf(0, 1, 2, 3, 4), moved.map { it.position })
        // A custom section can go above built-in ones, never above customer data.
        val up = CrmFieldSections.reorder(all, reserva.id, up = true)!!
        assertEquals("Datos del cliente", up.first().name)
        assertEquals("Datos de la reserva", up[3].name)
    }
}
