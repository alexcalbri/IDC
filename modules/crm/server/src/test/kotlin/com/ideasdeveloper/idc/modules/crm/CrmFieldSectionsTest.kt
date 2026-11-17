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
        val input = CrmFieldSections.parse(mapOf("sectionName" to " Notas ", "sectionPosition" to "3"), sections, null)
        assertEquals(CrmFieldSectionInput("Notas", 3), input)
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to " "), sections, null) }
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to "familia"), sections, null) }
        // Renaming a section to its own name is fine.
        CrmFieldSections.parse(mapOf("sectionName" to "Familia"), sections, familia.id)
        // The sections of the fixed part of the form cannot be reused.
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to "Contacto"), sections, null) }
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to CrmFields.Section), sections, null) }
        assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to "Notas", "sectionPosition" to "1000"), sections, null) }
    }

    @Test
    fun fieldFormAssignsTheSection() {
        val named = CrmFields.parseDefinition(mapOf("label" to "Hijos", "type" to "Texto", "section" to "Familia"), null, sections)
        assertEquals(familia.id, named.sectionId)
        assertNull(CrmFields.parseDefinition(mapOf("label" to "Hijos", "type" to "Texto", "section" to CrmFields.Section), null, sections).sectionId)
        assertNull(CrmFields.parseDefinition(mapOf("label" to "Hijos", "type" to "Texto"), null, sections).sectionId)
        assertFailsWith<CrmValidationException> { CrmFields.parseDefinition(mapOf("label" to "Hijos", "type" to "Texto", "section" to "Otra"), null, sections) }
    }
}
