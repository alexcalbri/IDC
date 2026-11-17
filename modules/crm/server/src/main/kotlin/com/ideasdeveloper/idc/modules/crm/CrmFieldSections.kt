package com.ideasdeveloper.idc.modules.crm

import java.sql.Connection
import java.util.UUID

/** A section of the lead form that groups custom fields (table crm_field_sections, V019). */
data class CrmFieldSection(val id: UUID, val name: String, val position: Int = 0)

data class CrmFieldSectionInput(val name: String, val position: Int)

/**
 * Sections of the custom fields: each company names its own ("Datos de la reserva") and puts fields in them.
 * Fields without a section go in [CrmFields.Section] ("Campos personalizados"). Deleting a section keeps its fields.
 */
object CrmFieldSections {
    private const val MaxSections = 30

    // Sections of the fixed part of the lead form: a custom section with one of these names would merge with it.
    private val reserved = listOf("Contacto", "Seguimiento", "Mensaje y detalles", CrmFields.DocumentsLabel, CrmFields.Section)

    fun all(tenant: Connection): List<CrmFieldSection> = tenant.prepareStatement(
        "SELECT id, name, position FROM crm_field_sections ORDER BY position, created_at"
    ).use { query ->
        query.executeQuery().use { rows ->
            buildList { while (rows.next()) add(CrmFieldSection(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getInt(3))) }
        }
    }

    /** Reads the section form of Configuracion. */
    fun parse(values: Map<String, String>, existing: List<CrmFieldSection>, id: UUID?): CrmFieldSectionInput {
        val name = values["sectionName"].orEmpty().trim()
        if (name.isEmpty()) throw CrmValidationException("Escribe el nombre de la seccion.")
        if (name.length > 60) throw CrmValidationException("El nombre admite hasta 60 caracteres.")
        if (reserved.any { it.equals(name, ignoreCase = true) }) throw CrmValidationException("$name ya es una seccion de la ficha del lead: elige otro nombre.")
        if (existing.any { it.name.equals(name, ignoreCase = true) && it.id != id }) throw CrmValidationException("Ya existe la seccion $name.")
        val position = values["sectionPosition"].orEmpty().trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..999 }
            ?: throw CrmValidationException("La posicion debe ser un numero de 0 a 999.")
        return CrmFieldSectionInput(name, position)
    }

    /** Creates ([id] null) or renames/reorders a section. */
    fun save(tenant: Connection, id: UUID?, input: CrmFieldSectionInput, existing: List<CrmFieldSection>): CrmFieldSection {
        if (id == null) {
            if (existing.size >= MaxSections) throw CrmValidationException("El CRM admite hasta $MaxSections secciones.")
            val section = CrmFieldSection(UUID.randomUUID(), input.name, input.position)
            tenant.prepareStatement("INSERT INTO crm_field_sections (id, name, position) VALUES (?, ?, ?)").use { insert ->
                insert.setObject(1, section.id)
                insert.setString(2, section.name)
                insert.setInt(3, section.position)
                insert.executeUpdate()
            }
            return section
        }
        existing.firstOrNull { it.id == id } ?: throw CrmValidationException("Esa seccion ya no existe.")
        tenant.prepareStatement("UPDATE crm_field_sections SET name = ?, position = ? WHERE id = ?").use { update ->
            update.setString(1, input.name)
            update.setInt(2, input.position)
            update.setObject(3, id)
            update.executeUpdate()
        }
        return CrmFieldSection(id, input.name, input.position)
    }

    /** Deletes the section; its fields stay, without section (foreign key ON DELETE SET NULL). */
    fun delete(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("DELETE FROM crm_field_sections WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() == 1 }

    /** Title of the form section a field shows in. */
    fun title(field: CrmField, sections: List<CrmFieldSection>): String =
        field.sectionId?.let { id -> sections.firstOrNull { it.id == id }?.name } ?: CrmFields.Section

    /**
     * The fields in the order the lead form draws them: section by position (the fields without section last,
     * under "Campos personalizados"), and inside each one by position. Fields of a section stay together because
     * the form groups consecutive fields of the same section.
     */
    fun ordered(fields: List<CrmField>, sections: List<CrmFieldSection>): List<CrmField> {
        val rank = sections.sortedWith(compareBy({ it.position }, { it.name.lowercase() })).withIndex().associate { (index, section) -> section.id to index }
        return fields.sortedWith(
            compareBy<CrmField> { field -> field.sectionId?.let { rank[it] } ?: Int.MAX_VALUE }
                .thenBy { it.position }
                .thenBy { it.label.lowercase() }
        )
    }
}
