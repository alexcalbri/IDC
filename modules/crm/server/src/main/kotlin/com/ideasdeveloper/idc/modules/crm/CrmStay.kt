package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Keys of the date fields that count as the check-in ([startKey]) and the check-out ([endKey]) of a stay.
 * An empty key means "not chosen".
 */
data class CrmStayMapping(val startKey: String = "", val endKey: String = "") {
    val isEmpty get() = startKey.isEmpty() && endKey.isEmpty()

    companion object {
        val None = CrmStayMapping()
    }
}

/**
 * "Fechas de estadia": which custom date fields are the arrival and the departure of the lead (crm_settings.stay_start_field
 * and stay_end_field). The columns crm_leads.target_start and target_end are an internal copy filled from those fields on
 * every save (empty without mapping), so the hotspot, the exit automation, Cloudbeds and the mail templates keep reading them.
 */
object CrmStay {
    // Names the agents (MCP) and old clients used for the two dates: still accepted, they write in the mapped fields.
    const val StartAlias = "targetStart"
    const val EndAlias = "targetEnd"

    private const val NotChosen = "Sin elegir"

    fun load(tenant: Connection): CrmStayMapping = tenant.prepareStatement(
        "SELECT stay_start_field, stay_end_field FROM crm_settings WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows -> if (rows.next()) CrmStayMapping(rows.getString(1).orEmpty(), rows.getString(2).orEmpty()) else CrmStayMapping.None }
    }

    /** The mapping with only the keys that are still date fields; the same field is never both the start and the end. */
    fun effective(mapping: CrmStayMapping, fields: List<CrmField>): CrmStayMapping {
        fun valid(key: String) = key.takeIf { k -> fields.any { it.key == k && it.type == CrmFieldType.Date } }.orEmpty()
        val start = valid(mapping.startKey)
        val end = valid(mapping.endKey).takeIf { it != start }.orEmpty()
        return CrmStayMapping(start, end)
    }

    // ---- Settings ----

    /** Options of the two dropdowns of "Fechas de estadia": the labels of the date fields. */
    fun options(fields: List<CrmField>): List<String> = listOf(NotChosen) + fields.filter { it.type == CrmFieldType.Date }.map { it.label }

    fun formValues(mapping: CrmStayMapping, fields: List<CrmField>): Map<String, String> {
        fun label(key: String) = fields.firstOrNull { it.key == key && it.type == CrmFieldType.Date }?.label ?: NotChosen
        return mapOf("start" to label(mapping.startKey), "end" to label(mapping.endKey))
    }

    /** One line for the screens: which fields are the check-in and the check-out. */
    fun summary(mapping: CrmStayMapping, fields: List<CrmField>): String {
        fun label(key: String) = fields.firstOrNull { it.key == key }?.label ?: "sin elegir"
        val effective = effective(mapping, fields)
        return "Ingreso: ${label(effective.startKey)}. Salida: ${label(effective.endKey)}."
    }

    /** Reads the "Fechas de estadia" form (labels of date fields). */
    fun parseSelection(values: Map<String, String>, fields: List<CrmField>): CrmStayMapping {
        fun key(name: String): String {
            val label = values[name].orEmpty().trim()
            if (label.isEmpty() || label == NotChosen) return ""
            return fields.firstOrNull { it.type == CrmFieldType.Date && it.label == label }?.key
                ?: throw CrmValidationException("El campo $label ya no existe o no es de tipo fecha.")
        }
        val mapping = CrmStayMapping(key("start"), key("end"))
        if (mapping.startKey.isNotEmpty() && mapping.startKey == mapping.endKey) throw CrmValidationException("El ingreso y la salida deben ser campos distintos.")
        return mapping
    }

    /** Saves the mapping and brings the internal copy of the dates of every lead in line with it. */
    fun save(tenant: Connection, mapping: CrmStayMapping): Int = CrmRepository.transaction(tenant) {
        tenant.prepareStatement(
            "INSERT INTO crm_settings (id, stay_start_field, stay_end_field) VALUES (1, ?, ?) ON CONFLICT (id) DO UPDATE SET stay_start_field = EXCLUDED.stay_start_field, stay_end_field = EXCLUDED.stay_end_field, updated_at = CURRENT_TIMESTAMP"
        ).use { insert ->
            insert.setString(1, mapping.startKey)
            insert.setString(2, mapping.endKey)
            insert.executeUpdate()
        }
        resync(tenant)
    }

    // ---- Values ----

    private fun dateOf(custom: JsonObject, key: String): LocalDate? =
        key.takeIf { it.isNotEmpty() }?.let { (custom[it] as? JsonPrimitive)?.contentOrNull }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /**
     * The start and end dates of a lead from its custom [custom] values. [mapping] must be [effective]. Throws when
     * the end is before the start, naming the fields.
     */
    fun dates(mapping: CrmStayMapping, fields: List<CrmField>, custom: JsonObject): Pair<LocalDate?, LocalDate?> {
        val start = dateOf(custom, mapping.startKey)
        val end = dateOf(custom, mapping.endKey)
        if (start != null && end != null && end.isBefore(start)) {
            fun label(key: String, default: String) = fields.firstOrNull { it.key == key }?.label ?: default
            throw CrmValidationException("${label(mapping.endKey, "La fecha de salida")} no puede ser anterior a ${label(mapping.startKey, "la fecha de ingreso")}.")
        }
        return start to end
    }

    /**
     * [input] with target_start and target_end taken from the mapped fields of what the lead will have after saving
     * ([stored] + [CrmLeadInput.custom]); without mapping (or when the mapped fields no longer exist) both are empty.
     */
    fun withDates(input: CrmLeadInput, mapping: CrmStayMapping, fields: List<CrmField>, stored: JsonObject): CrmLeadInput {
        val (start, end) = datesAfterSave(effective(mapping, fields), fields, stored, input.custom)
        return input.copy(targetStart = start, targetEnd = end)
    }

    /**
     * The dates a lead has after saving [custom] over [stored]. The pair is validated (end not before start) only when this save
     * changes one of the two mapped dates; a stored inverted pair (a mapping change can produce one) never blocks other edits:
     * the values stay and the copy drops the end, like [resync]. [mapping] must be [effective].
     */
    fun datesAfterSave(mapping: CrmStayMapping, fields: List<CrmField>, stored: JsonObject, custom: Map<String, JsonElement?>): Pair<LocalDate?, LocalDate?> {
        val merged = CrmFields.merge(stored, custom)
        val touched = listOf(mapping.startKey, mapping.endKey).any { it.isNotEmpty() && it in custom && custom[it] != stored[it] }
        return if (touched) dates(mapping, fields, merged) else datesOrNull(mapping, merged)
    }

    /** Same as [dates] without validating: invalid or inverted values leave the end empty. */
    fun datesOrNull(mapping: CrmStayMapping, custom: JsonObject): Pair<LocalDate?, LocalDate?> {
        val start = dateOf(custom, mapping.startKey)
        val end = dateOf(custom, mapping.endKey)?.takeIf { start == null || !it.isBefore(start) }
        return start to end
    }

    /**
     * Moves the old names of the dates ([StartAlias], [EndAlias]) to the mapped fields of the form values. Without a mapped
     * field a typed date is refused (an empty value is ignored: nothing to write). [mapping] must be [effective].
     */
    fun withAliases(values: Map<String, String>, mapping: CrmStayMapping): Map<String, String> {
        if (StartAlias !in values && EndAlias !in values) return values
        val result = values.toMutableMap()
        fun move(alias: String, key: String, what: String) {
            val raw = result.remove(alias) ?: return
            if (raw.isBlank() && key.isEmpty()) return
            if (key.isEmpty()) throw CrmValidationException("El CRM no tiene un campo de $what: elige uno en Configuracion > Integraciones > Fechas de estadia.")
            // The field named in the form wins over the old name.
            result.putIfAbsent(key, raw)
        }
        move(StartAlias, mapping.startKey, "fecha de ingreso")
        move(EndAlias, mapping.endKey, "fecha de salida")
        return result
    }

    /** Detail lines of a pipeline card: each mapped date with the label of its field. */
    fun cardLines(lead: CrmLead, mapping: CrmStayMapping, fields: List<CrmField>): List<String> = listOf(mapping.startKey, mapping.endKey).mapNotNull { key ->
        val field = fields.firstOrNull { it.key == key } ?: return@mapNotNull null
        CrmFields.display(lead.customFields[key]).takeIf { it.isNotEmpty() }?.let { "${field.label}: $it" }
    }

    // ---- Internal copy (target_start / target_end) ----

    /**
     * Fills target_start and target_end of one lead ([leadId]) or of all of them (null) from the mapped fields (the [effective]
     * mapping: keys of fields that no longer exist or are not dates count as none). Values that are not valid dates are left empty
     * (crm_safe_date never throws) and an end before the start is dropped (the table refuses it). Returns how many leads lost a
     * date they had (their copy went from filled to empty).
     */
    fun resync(tenant: Connection, leadId: UUID? = null): Int {
        val mapping = effective(load(tenant), CrmFields.all(tenant))
        val where = if (leadId != null) "WHERE id = ?" else ""
        val derived = """
            (SELECT id, s, CASE WHEN s IS NOT NULL AND e < s THEN NULL ELSE e END AS e
             FROM (SELECT id, crm_safe_date(custom_fields ->> CAST(? AS text)) AS s, crm_safe_date(custom_fields ->> CAST(? AS text)) AS e
                   FROM crm_leads $where) y)
        """.trimIndent()
        fun bind(statement: java.sql.PreparedStatement) {
            statement.setString(1, mapping.startKey)
            statement.setString(2, mapping.endKey)
            if (leadId != null) statement.setObject(3, leadId)
        }
        val lost = tenant.prepareStatement(
            "SELECT count(*) FROM crm_leads l JOIN $derived x ON x.id = l.id WHERE (l.target_start IS NOT NULL AND x.s IS NULL) OR (l.target_end IS NOT NULL AND x.e IS NULL)"
        ).use { query -> bind(query); query.executeQuery().use { rows -> rows.next(); rows.getInt(1) } }
        tenant.prepareStatement(
            "UPDATE crm_leads l SET target_start = x.s, target_end = x.e FROM $derived x WHERE x.id = l.id AND (l.target_start IS DISTINCT FROM x.s OR l.target_end IS DISTINCT FROM x.e)"
        ).use { update -> bind(update); update.executeUpdate() }
        return lost
    }

    /**
     * A date field was deleted or moved out of the CRM: if it was mapped, the mapping and the copy of that date are cleared.
     * Returns how many leads lost their stay dates.
     */
    fun fieldRemoved(tenant: Connection, key: String): Int {
        val changed = tenant.prepareStatement(
            """
            UPDATE crm_settings SET stay_start_field = CASE WHEN stay_start_field = ? THEN '' ELSE stay_start_field END,
                stay_end_field = CASE WHEN stay_end_field = ? THEN '' ELSE stay_end_field END
            WHERE id = 1 AND (stay_start_field = ? OR stay_end_field = ?)
            """.trimIndent()
        ).use { update ->
            repeat(4) { update.setString(it + 1, key) }
            update.executeUpdate()
        }
        return if (changed > 0) resync(tenant) else 0
    }

    /** Whether [key] is one of the two mapped fields (changing or removing it can clear dates the hotspot and Cloudbeds read). */
    fun isMapped(mapping: CrmStayMapping, key: String) = key.isNotEmpty() && (key == mapping.startKey || key == mapping.endKey)

    /** The sentence the notices add when a change cleared the stay dates of [leads] leads ("" for none). */
    fun lostNote(leads: Int): String =
        if (leads <= 0) "" else " $leads lead(s) perdieron sus fechas de estadia (Cloudbeds, el hotspot y la salida automatica ya no las ven)."

    const val ConfirmClear = "Cambiar el mapeo puede borrar las fechas de estadia que usan Cloudbeds, el hotspot y la salida automatica. Continuar?"

    // ---- Imports ----

    /**
     * Writes the dates of an imported row into the mapped fields (empty dates never erase) and refreshes the copy. Returns a
     * warning for the import report when a date had nowhere to go, null otherwise.
     */
    fun importDates(tenant: Connection, leadId: UUID, start: LocalDate?, end: LocalDate?): String? {
        if (start == null && end == null) return null
        val mapping = effective(load(tenant), CrmFields.all(tenant))
        val lead = CrmRepository.lead(tenant, leadId) ?: return null
        val (changes, warning) = importChanges(mapping, lead.customFields, start, end)
        if (changes.isNotEmpty()) {
            tenant.prepareStatement("UPDATE crm_leads SET custom_fields = CAST(? AS jsonb) WHERE id = ?").use { update ->
                update.setString(1, CrmFields.merge(lead.customFields, changes).toString())
                update.setObject(2, leadId)
                update.executeUpdate()
            }
            resync(tenant, leadId)
        }
        return warning
    }

    /** What an imported row writes in the mapped fields, and the warning for the dates that cannot be written. Pure. */
    fun importChanges(mapping: CrmStayMapping, stored: JsonObject, start: LocalDate?, end: LocalDate?): Pair<Map<String, JsonElement?>, String?> {
        val warnings = mutableListOf<String>()
        val changes = mutableMapOf<String, JsonElement?>()
        if (start != null) {
            if (mapping.startKey.isEmpty()) warnings += "fecha de ingreso" else changes[mapping.startKey] = JsonPrimitive(start.toString())
        }
        if (end != null) {
            if (mapping.endKey.isEmpty()) warnings += "fecha de salida" else changes[mapping.endKey] = JsonPrimitive(end.toString())
        }
        // An end before the start is dropped (the form refuses it too), looking at what the lead would have after the import.
        val after = CrmFields.merge(stored, changes)
        val (finalStart, finalEnd) = datesOrNull(mapping, after)
        val rawEnd = dateOf(after, mapping.endKey)
        if (end != null && mapping.endKey.isNotEmpty() && finalStart != null && rawEnd != null && finalEnd == null) {
            changes.remove(mapping.endKey)
            warnings += "fecha de salida anterior a la de ingreso"
        }
        val warning = warnings.takeIf { it.isNotEmpty() }?.let {
            "Fechas ignoradas (${it.joinToString(", ")}): elige los campos en Configuracion > Integraciones > Fechas de estadia"
        }
        return changes to warning
    }
}
