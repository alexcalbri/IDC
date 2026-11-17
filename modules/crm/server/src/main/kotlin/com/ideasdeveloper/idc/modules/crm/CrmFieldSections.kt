package com.ideasdeveloper.idc.modules.crm

import java.sql.Connection
import java.util.UUID

/**
 * A section of the lead form (table crm_field_sections). The built-in sections have a [systemKey] (V020) and a fixed name; the
 * ones the company creates have none.
 */
data class CrmFieldSection(val id: UUID, val name: String, val position: Int = 0, val systemKey: String? = null) {
    val isSystem get() = systemKey != null
}

data class CrmFieldSectionInput(val name: String)

/**
 * Sections of the lead form: the built-in ones (Datos del cliente, Seguimiento, Mensaje y detalles, Documentos) and the company's.
 * Any custom field can be placed in any of them; fields without a section go last in [CrmFields.Section]. All sections can be
 * reordered except "Datos del cliente", which is always first. Built-in sections cannot be deleted or renamed.
 */
object CrmFieldSections {
    private const val MaxCustomSections = 30

    const val Customer = "customer"
    const val Followup = "followup"
    const val Message = "message"
    const val Documents = "documents"

    /** The built-in sections in their original order: key and fixed name. */
    val system = listOf(
        Customer to "Datos del cliente",
        Followup to "Seguimiento",
        Message to "Mensaje y detalles",
        Documents to CrmFields.DocumentsLabel,
    )

    // Names a custom section cannot take: the built-in ones, the old name of the first and the group of fields without section.
    private val reserved = system.map { it.second } + listOf("Contacto", CrmFields.Section)

    fun all(tenant: Connection): List<CrmFieldSection> = tenant.prepareStatement(
        "SELECT id, name, position, system_key FROM crm_field_sections ORDER BY position, created_at"
    ).use { query ->
        query.executeQuery().use { rows ->
            buildList { while (rows.next()) add(CrmFieldSection(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getInt(3), rows.getString(4))) }
        }
    }

    /**
     * Every section in the order the form draws them: "Datos del cliente" first whatever its position says, then the rest by
     * position. A built-in section missing from [sections] (a database before V020) is added in its original place.
     */
    fun sorted(sections: List<CrmFieldSection>): List<CrmFieldSection> {
        val full = sections + system.withIndex().filter { (_, entry) -> sections.none { it.systemKey == entry.first } }.map { (index, entry) ->
            CrmFieldSection(UUID.nameUUIDFromBytes("crm-system-section:${entry.first}".toByteArray()), entry.second, index, entry.first)
        }
        return full.sortedWith(compareBy<CrmFieldSection> { it.systemKey != Customer }.thenBy { it.position }.thenBy { it.name.lowercase() })
    }

    /** Reads the section form of Configuracion (custom sections only). */
    fun parse(values: Map<String, String>, existing: List<CrmFieldSection>, id: UUID?): CrmFieldSectionInput {
        val name = values["sectionName"].orEmpty().trim()
        if (name.isEmpty()) throw CrmValidationException("Escribe el nombre de la seccion.")
        if (name.length > 60) throw CrmValidationException("El nombre admite hasta 60 caracteres.")
        if (reserved.any { it.equals(name, ignoreCase = true) }) throw CrmValidationException("$name ya es una seccion de la ficha del lead: elige otro nombre.")
        if (existing.any { it.name.equals(name, ignoreCase = true) && it.id != id }) throw CrmValidationException("Ya existe la seccion $name.")
        return CrmFieldSectionInput(name)
    }

    /** Creates ([id] null, at the end) or renames a custom section. Built-in sections keep their name. */
    fun save(tenant: Connection, id: UUID?, input: CrmFieldSectionInput, existing: List<CrmFieldSection>): CrmFieldSection {
        if (id == null) {
            if (existing.count { !it.isSystem } >= MaxCustomSections) throw CrmValidationException("El CRM admite hasta $MaxCustomSections secciones propias.")
            val section = CrmFieldSection(UUID.randomUUID(), input.name, (existing.maxOfOrNull { it.position } ?: -1) + 1)
            tenant.prepareStatement("INSERT INTO crm_field_sections (id, name, position) VALUES (?, ?, ?)").use { insert ->
                insert.setObject(1, section.id)
                insert.setString(2, section.name)
                insert.setInt(3, section.position)
                insert.executeUpdate()
            }
            return section
        }
        val current = existing.firstOrNull { it.id == id } ?: throw CrmValidationException("Esa seccion ya no existe.")
        if (current.isSystem) throw CrmValidationException("${current.name} es una seccion fija de la ficha: no se renombra.")
        tenant.prepareStatement("UPDATE crm_field_sections SET name = ? WHERE id = ?").use { update ->
            update.setString(1, input.name)
            update.setObject(2, id)
            update.executeUpdate()
        }
        return current.copy(name = input.name)
    }

    /** Refuses the built-in sections: they are part of the form. */
    fun checkDeletable(section: CrmFieldSection) {
        if (section.isSystem) throw CrmValidationException("${section.name} es una seccion fija de la ficha: no se elimina.")
    }

    /** Deletes a custom section; its fields stay, without section (foreign key ON DELETE SET NULL). */
    fun delete(tenant: Connection, section: CrmFieldSection): Boolean {
        checkDeletable(section)
        return tenant.prepareStatement("DELETE FROM crm_field_sections WHERE id = ? AND system_key IS NULL").use { it.setObject(1, section.id); it.executeUpdate() == 1 }
    }

    /**
     * The sections after moving [id] one place up or down, or null when it cannot move: "Datos del cliente" never moves, nothing
     * goes before it, and the first or last section has nowhere to go. Positions are renumbered 0..n-1 in the new order.
     */
    fun reorder(sections: List<CrmFieldSection>, id: UUID, up: Boolean): List<CrmFieldSection>? {
        val order = sorted(sections)
        val index = order.indexOfFirst { it.id == id }
        if (index <= 0 || order[index].systemKey == Customer) return null
        val target = if (up) index - 1 else index + 1
        if (target < 1 || target > order.lastIndex) return null
        val moved = order.toMutableList().also { it[index] = order[target]; it[target] = order[index] }
        return moved.mapIndexed { position, section -> section.copy(position = position) }
    }

    /** Moves a section and writes the new positions (only the sections that changed; the sections of a database before V020 are not written). */
    fun move(tenant: Connection, sections: List<CrmFieldSection>, id: UUID, up: Boolean): Boolean {
        val moved = reorder(sections, id, up) ?: return false
        val before = sections.associate { it.id to it.position }
        CrmRepository.transaction(tenant) {
            tenant.prepareStatement("UPDATE crm_field_sections SET position = ? WHERE id = ?").use { update ->
                moved.filter { before[it.id] != null && before[it.id] != it.position }.forEach {
                    update.setInt(1, it.position)
                    update.setObject(2, it.id)
                    update.addBatch()
                }
                update.executeBatch()
            }
        }
        return true
    }

    /** Title of the form section a field shows in. */
    fun title(field: CrmField, sections: List<CrmFieldSection>): String =
        field.sectionId?.let { id -> sections.firstOrNull { it.id == id }?.name } ?: CrmFields.Section

    /** Custom fields in the order of the form: section by section (see [sorted]), each by position; the fields without section last. */
    fun ordered(fields: List<CrmField>, sections: List<CrmFieldSection>): List<CrmField> {
        val rank = sorted(sections).withIndex().associate { (index, section) -> section.id to index }
        return fields.sortedWith(
            compareBy<CrmField> { field -> field.sectionId?.let { rank[it] } ?: Int.MAX_VALUE }
                .thenBy { it.position }
                .thenBy { it.label.lowercase() }
        )
    }
}
