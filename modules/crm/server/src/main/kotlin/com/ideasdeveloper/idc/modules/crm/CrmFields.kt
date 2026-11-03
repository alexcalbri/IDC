package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiFieldValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID

/** Types a custom lead field can have: the form field types of the app ([uiType]). */
enum class CrmFieldType(val id: String, val label: String) {
    Text("text", "Texto"),
    Textarea("textarea", "Texto largo"),
    Number("number", "Numero"),
    Email("email", "Correo"),
    Phone("phone", "Telefono"),
    Url("url", "Enlace"),
    Date("date", "Fecha"),
    Boolean("boolean", "Si / No"),
    Dropdown("dropdown", "Lista desplegable"),
    Files("files", "Archivos y carpetas");

    val uiType get() = id

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun byLabel(label: String) = entries.firstOrNull { it.label == label }
    }
}

data class CrmField(
    val id: UUID,
    val key: String,
    val label: String,
    val type: CrmFieldType,
    val options: List<String> = emptyList(),
    val multiple: kotlin.Boolean = false,
    val required: kotlin.Boolean = false,
    val position: Int = 0,
)

data class CrmFieldInput(
    val label: String,
    val type: CrmFieldType,
    val options: List<String>,
    val multiple: kotlin.Boolean,
    val required: kotlin.Boolean,
    val position: Int,
)

object CrmFields {
    // Built-in attachments field of every lead.
    const val Documents = "documents"
    const val DocumentsLabel = "Documentos"
    const val Section = "Campos personalizados"
    private const val MaxFields = 60
    private const val MaxOptions = 100

    // ---- Definitions ----

    fun all(tenant: Connection): List<CrmField> = tenant.prepareStatement(
        "SELECT id, field_key, label, field_type, options::text, multiple, required, position FROM crm_field_definitions ORDER BY position, created_at"
    ).use { query ->
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val type = CrmFieldType.byId(rows.getString(4)) ?: continue
                    add(
                        CrmField(
                            rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), type,
                            Json.parseToJsonElement(rows.getString(5)).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull },
                            rows.getBoolean(6), rows.getBoolean(7), rows.getInt(8),
                        )
                    )
                }
            }
        }
    }

    /** Creates ([id] null) or edits a field. The type of an existing field never changes. */
    fun save(tenant: Connection, id: UUID?, input: CrmFieldInput, existing: List<CrmField>): CrmField {
        if (existing.any { it.label.equals(input.label, ignoreCase = true) && it.id != id }) throw CrmValidationException("Ya existe el campo ${input.label}.")
        val options = JsonArray(input.options.map { JsonPrimitive(it) }).toString()
        if (id == null) {
            if (existing.size >= MaxFields) throw CrmValidationException("El CRM admite hasta $MaxFields campos personalizados.")
            val field = CrmField(UUID.randomUUID(), uniqueKey(input.label, existing.map { it.key }.toSet()), input.label, input.type, input.options, input.multiple, input.required, input.position)
            tenant.prepareStatement(
                "INSERT INTO crm_field_definitions (id, field_key, label, field_type, options, multiple, required, position) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)"
            ).use { insert ->
                insert.setObject(1, field.id)
                insert.setString(2, field.key)
                insert.setString(3, field.label)
                insert.setString(4, field.type.id)
                insert.setString(5, options)
                insert.setBoolean(6, field.multiple)
                insert.setBoolean(7, field.required)
                insert.setInt(8, field.position)
                insert.executeUpdate()
            }
            return field
        }
        val current = existing.firstOrNull { it.id == id } ?: throw CrmValidationException("Ese campo ya no existe.")
        tenant.prepareStatement(
            "UPDATE crm_field_definitions SET label = ?, options = CAST(? AS jsonb), multiple = ?, required = ?, position = ? WHERE id = ?"
        ).use { update ->
            update.setString(1, input.label)
            update.setString(2, options)
            update.setBoolean(3, input.multiple)
            update.setBoolean(4, input.required)
            update.setInt(5, input.position)
            update.setObject(6, id)
            update.executeUpdate()
        }
        return current.copy(label = input.label, options = input.options, multiple = input.multiple, required = input.required, position = input.position)
    }

    /** Deletes the field with its values in every lead (and its files). */
    fun delete(tenant: Connection, field: CrmField) {
        val autoCommit = tenant.autoCommit
        tenant.autoCommit = false
        try {
            tenant.prepareStatement("UPDATE crm_leads SET custom_fields = custom_fields - ? WHERE jsonb_exists(custom_fields, ?)").use { update ->
                update.setString(1, field.key)
                update.setString(2, field.key)
                update.executeUpdate()
            }
            // The stored files are deleted afterwards by the attachments job.
            CrmAttachments.deleteField(tenant, field.key)
            tenant.prepareStatement("DELETE FROM crm_field_definitions WHERE id = ?").use { it.setObject(1, field.id); it.executeUpdate() }
            tenant.commit()
        } catch (failure: Exception) {
            tenant.rollback()
            throw failure
        } finally {
            tenant.autoCommit = autoCommit
        }
    }

    // "Fecha de firma" -> cf_fecha_de_firma (cf_fecha_de_firma_2 when taken).
    fun uniqueKey(label: String, taken: Set<String>): String {
        val slug = Normalizer.normalize(label.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "campo" }
        val base = "cf_$slug"
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in taken }
    }

    /** Reads the field form of Configuracion. [editing] keeps its type. */
    fun parseDefinition(values: Map<String, String>, editing: CrmField?): CrmFieldInput {
        val label = values["label"].orEmpty().trim()
        if (label.isEmpty()) throw CrmValidationException("Escribe el nombre del campo.")
        if (label.length > 80) throw CrmValidationException("El nombre admite hasta 80 caracteres.")
        val type = editing?.type ?: CrmFieldType.byLabel(values["type"].orEmpty()) ?: throw CrmValidationException("Elige el tipo del campo.")
        val options = if (type == CrmFieldType.Dropdown) {
            values["options"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        } else emptyList()
        if (type == CrmFieldType.Dropdown && options.isEmpty()) throw CrmValidationException("Escribe las opciones de la lista, una por linea.")
        if (options.size > MaxOptions) throw CrmValidationException("La lista admite hasta $MaxOptions opciones.")
        options.firstOrNull { it.length > 120 }?.let { throw CrmValidationException("Cada opcion admite hasta 120 caracteres.") }
        val position = values["position"].orEmpty().trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..999 }
            ?: throw CrmValidationException("La posicion debe ser un numero de 0 a 999.")
        return CrmFieldInput(
            label = label,
            type = type,
            options = options,
            multiple = type == CrmFieldType.Dropdown && values["multiple"] == "true",
            required = type != CrmFieldType.Boolean && values["required"] == "true",
            position = position,
        )
    }

    // ---- Values of a lead ----

    /** Form value of each custom field (attachments excluded): multiple dropdowns as a JSON array. */
    fun formValues(fields: List<CrmField>, stored: JsonObject): Map<String, String> = fields.filter { it.type != CrmFieldType.Files }.associate { field ->
        val value = stored[field.key]
        field.key to when {
            value == null -> ""
            value is JsonArray -> UiFieldValues.options(value.mapNotNull { it.jsonPrimitive.contentOrNull })
            else -> value.jsonPrimitive.contentOrNull.orEmpty()
        }
    }

    /**
     * Validates the custom fields sent in the form. Only the keys present in [values] change: the
     * result maps each of them to its new value or to null to clear it (agents send only what changes).
     */
    fun parseValues(values: Map<String, String>, fields: List<CrmField>, stored: JsonObject = JsonObject(emptyMap())): Map<String, JsonElement?> = buildMap {
        fields.filter { it.type != CrmFieldType.Files && it.key in values }.forEach { field ->
            val raw = values[field.key].orEmpty().trim()
            val label = field.label
            val value: JsonElement? = when (field.type) {
                CrmFieldType.Boolean -> JsonPrimitive(raw == "true")
                CrmFieldType.Dropdown -> {
                    val chosen = UiFieldValues.options(raw)
                    // An option removed from the list stays valid in the leads that already had it.
                    val kept = when (val previous = stored[field.key]) {
                        is JsonArray -> previous.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                        is JsonPrimitive -> setOfNotNull(previous.contentOrNull)
                        else -> emptySet()
                    }
                    chosen.firstOrNull { it !in field.options && it !in kept }?.let { throw CrmValidationException("$label: la opcion $it no existe.") }
                    if (!field.multiple && chosen.size > 1) throw CrmValidationException("$label admite una sola opcion.")
                    when {
                        chosen.isEmpty() -> null
                        field.multiple -> JsonArray(chosen.map { JsonPrimitive(it) })
                        else -> JsonPrimitive(chosen.single())
                    }
                }
                else -> raw.takeIf { it.isNotEmpty() }?.let { text -> JsonPrimitive(validText(field, text)) }
            }
            if (field.required && value == null) throw CrmValidationException("Completa el campo $label.")
            put(field.key, value)
        }
    }

    private fun validText(field: CrmField, text: String): String {
        val label = field.label
        val max = if (field.type == CrmFieldType.Textarea) 5000 else 500
        if (text.length > max) throw CrmValidationException("$label admite hasta $max caracteres.")
        when (field.type) {
            CrmFieldType.Number -> text.replace(',', '.').toBigDecimalOrNull()?.let { return it.stripTrailingZeros().toPlainString() }
                ?: throw CrmValidationException("$label debe ser un numero.")
            CrmFieldType.Email -> if (!text.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) throw CrmValidationException("$label: escribe un correo valido.")
            CrmFieldType.Url -> if (!text.startsWith("https://") && !text.startsWith("http://")) throw CrmValidationException("$label: el enlace debe empezar con https://.")
            CrmFieldType.Date -> runCatching { LocalDate.parse(text) }.getOrElse { throw CrmValidationException("$label: usa el formato AAAA-MM-DD.") }
            else -> Unit
        }
        return text
    }

    /** Ids of the files kept in each files field sent in the form (built-in documents included). */
    fun parseFiles(values: Map<String, String>, fields: List<CrmField>): Map<String, List<UUID>> {
        val keys = listOf(Documents to DocumentsLabel) + fields.filter { it.type == CrmFieldType.Files }.map { it.key to it.label }
        return keys.filter { (key, _) -> key in values }.associate { (key, label) ->
            val ids = UiFieldValues.files(values[key].orEmpty()).mapNotNull { runCatching { UUID.fromString(it.id) }.getOrNull() }.distinct()
            val required = fields.firstOrNull { it.key == key }?.required == true
            if (required && ids.isEmpty()) throw CrmValidationException("Adjunta al menos un archivo en $label.")
            key to ids
        }
    }

    /** Stored values after applying [changes] (null clears a key). */
    fun merge(stored: JsonObject, changes: Map<String, JsonElement?>): JsonObject =
        JsonObject((stored + changes).filterValues { it != null }.mapValues { it.value!! })

    fun display(value: JsonElement?): String = when (value) {
        null -> ""
        is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString(", ")
        is JsonPrimitive -> value.booleanOrNull?.let { if (it) "Si" else "No" } ?: value.contentOrNull.orEmpty()
        else -> value.toString()
    }

    fun parseStored(text: String?): JsonObject = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    /** "Campo: antes -> despues" for each custom field that changed. */
    fun changes(fields: List<CrmField>, before: JsonObject, after: JsonObject): List<String> = fields.filter { it.type != CrmFieldType.Files }.mapNotNull { field ->
        val old = display(before[field.key])
        val new = display(after[field.key])
        if (old == new) null else "${field.label}: ${old.ifEmpty { "(vacio)" }} -> ${new.ifEmpty { "(vacio)" }}"
    }
}
