package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiFieldValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** A field of Clientes (customer_field_definitions); its values live in customers.flexible_attributes. */
data class CustomerField(val key: String, val label: String, val type: String, val options: List<String>)

/**
 * Where an import column can go besides the fixed lead fields: the lead's Documentos, a custom
 * field of the CRM ("crm:<key>"), a field of the customer in Clientes ("cliente:<key>"), or a new
 * one of either kind created when the mapping is saved ("new:crm", "new:cliente").
 */
object CrmImportTargets {
    const val Files = "files"
    const val NewCrm = "new:crm"
    const val NewCustomer = "new:cliente"
    const val CrmPrefix = "crm:"
    const val CustomerPrefix = "cliente:"

    // Columns that describe the person rather than the deal: proposed as fields of the customer.
    private val personal = Regex(
        "(?i)(document|c[eé]dula|\\bdni\\b|pasaporte|passport|\\bruc\\b|\\bnit\\b|\\brfc\\b|curp|identificaci|\\bid\\b|" +
            "nacimiento|birth|cumplea|direcci|address|domicilio|nacionalidad|nationality|g[eé]nero|gender|sexo|profesi|ocupaci|estado civil)"
    )

    // ---- Options of the mapping ----

    /** Every target of a column, value -> label, for the mapping dropdowns (the fixed fields first). */
    fun options(crmFields: List<CrmField>, customerFields: List<CustomerField>): LinkedHashMap<String, String> {
        val options = LinkedHashMap(CrmImportParse.fields)
        options[Files] = "Archivos del lead (${CrmFields.DocumentsLabel})"
        crmFields.forEach { options[CrmPrefix + it.key] = "Campo del CRM: ${it.label}" }
        customerFields.forEach { options[CustomerPrefix + it.key] = "Campo del cliente: ${it.label}" }
        options[NewCrm] = "Crear campo en el CRM"
        options[NewCustomer] = "Crear campo del cliente"
        return options
    }

    /**
     * What to propose for a column the name guess left without a field: an existing field with the
     * same name, the lead's files for file columns, a new customer field for personal data, a new
     * CRM field for anything else with values, and nothing for empty columns.
     */
    fun propose(column: String, mondayType: String?, hasValues: Boolean, crmFields: List<CrmField>, customerFields: List<CustomerField>): String {
        crmFields.firstOrNull { it.label.equals(column.trim(), ignoreCase = true) }?.let { return CrmPrefix + it.key }
        customerFields.firstOrNull { it.label.equals(column.trim(), ignoreCase = true) }?.let { return CustomerPrefix + it.key }
        if (mondayType == "file") return Files
        if (!hasValues) return ""
        return if (personal.containsMatchIn(column)) NewCustomer else NewCrm
    }

    // ---- Types ----

    // CRM field type for a monday column type.
    fun crmType(mondayType: String?): Pair<CrmFieldType, Boolean> = when (mondayType) {
        "long_text" -> CrmFieldType.Textarea to false
        "numbers" -> CrmFieldType.Number to false
        "email" -> CrmFieldType.Email to false
        "phone" -> CrmFieldType.Phone to false
        "link" -> CrmFieldType.Url to false
        "date" -> CrmFieldType.Date to false
        "checkbox" -> CrmFieldType.Boolean to false
        "status" -> CrmFieldType.Dropdown to false
        "dropdown" -> CrmFieldType.Dropdown to true
        "file" -> CrmFieldType.Files to false
        else -> CrmFieldType.Text to false
    }

    // Clientes field type (ClientesModule.fieldTypes) for a CRM field type.
    private fun customerType(type: CrmFieldType, multiple: Boolean): String = when (type) {
        CrmFieldType.Textarea -> "textarea"
        CrmFieldType.Number -> "number"
        CrmFieldType.Email -> "email"
        CrmFieldType.Phone -> "phone"
        CrmFieldType.Date -> "date"
        CrmFieldType.Boolean -> "boolean"
        CrmFieldType.Dropdown -> if (multiple) "multi_select" else "select"
        else -> "text"
    }

    // ---- Customer fields ----

    fun customerFields(tenant: Connection): List<CustomerField> = tenant.prepareStatement(
        "SELECT field_key, label, field_type, options::text FROM customer_field_definitions WHERE is_active ORDER BY display_order, field_key"
    ).use { query ->
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val options = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(rows.getString(4)).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
                        .getOrDefault(emptyList())
                    add(CustomerField(rows.getString(1), rows.getString(2), rows.getString(3), options))
                }
            }
        }
    }

    private fun createCustomerField(tenant: Connection, label: String, type: String, options: List<String>, existing: List<CustomerField>): CustomerField {
        val taken = existing.map { it.key }.toSet()
        // Clientes keys have no prefix; the same slug rules as the CRM.
        val key = CrmFields.uniqueKey(label, taken.map { "cf_$it" }.toSet()).removePrefix("cf_")
        val position = tenant.prepareStatement("SELECT COALESCE(max(display_order), -1) + 1 FROM customer_field_definitions").use { query ->
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        tenant.prepareStatement(
            """
            INSERT INTO customer_field_definitions (id, field_key, label, field_type, control_type, is_required, display_order, options)
            VALUES (?, ?, ?, ?, ?, FALSE, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, key)
            insert.setString(3, label.take(80))
            insert.setString(4, type)
            insert.setString(5, type)
            insert.setInt(6, position)
            insert.setString(7, JsonArray(options.map { JsonPrimitive(it) }).toString())
            insert.executeUpdate()
        }
        return CustomerField(key, label.take(80), type, options)
    }

    /**
     * Creates the fields of the columns mapped to "new:crm" / "new:cliente" (typed from the monday
     * column, dropdown options from the values of the rows) and returns the mapping pointing to
     * them, with the labels of what was created. A file column always becomes a CRM field.
     */
    fun createMissing(
        tenant: Connection,
        columns: Map<String, String>,
        types: Map<String, String>,
        distinct: (String) -> List<String>,
    ): Pair<Map<String, String>, List<String>> {
        val created = mutableListOf<String>()
        var crmFields = CrmFields.all(tenant)
        var customerFields = customerFields(tenant)
        val mapped = columns.mapValues { (column, target) ->
            if (target != NewCrm && target != NewCustomer) return@mapValues target
            val (type, multiple) = crmType(types[column])
            val label = column.trim().take(80).ifEmpty { "Campo" }
            val options = if (type == CrmFieldType.Dropdown) {
                distinct(column).flatMap { value -> if (multiple) value.split(",") else listOf(value) }
                    .map { it.trim().take(120) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(100)
            } else emptyList()
            // A dropdown without values in the rows is created as text.
            val (finalType, finalMultiple) = if (type == CrmFieldType.Dropdown && options.isEmpty()) CrmFieldType.Text to false else type to multiple
            if (target == NewCustomer && finalType != CrmFieldType.Files) {
                customerFields.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { return@mapValues CustomerPrefix + it.key }
                val field = createCustomerField(tenant, label, customerType(finalType, finalMultiple), options, customerFields)
                customerFields = customerFields + field
                created += "$label (cliente)"
                CustomerPrefix + field.key
            } else {
                crmFields.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { return@mapValues CrmPrefix + it.key }
                val position = (crmFields.maxOfOrNull { it.position } ?: -1) + 1
                val field = CrmFields.save(tenant, null, CrmFieldInput(label, finalType, options, finalMultiple, false, position.coerceAtMost(999)), crmFields)
                crmFields = crmFields + field
                created += "$label (CRM)"
                CrmPrefix + field.key
            }
        }
        return mapped to created
    }

    // ---- Values ----

    /** The monday text of a column turned into the form value a field of [type] expects; "" when unusable. */
    fun formValue(text: String, type: CrmFieldType, multiple: Boolean): String {
        val value = text.trim()
        if (value.isEmpty()) return ""
        return when (type) {
            CrmFieldType.Boolean -> (value.lowercase() in setOf("v", "true", "si", "sí", "yes", "x", "1", "checked")).toString()
            CrmFieldType.Dropdown -> if (multiple) UiFieldValues.options(value.split(",").map { it.trim() }.filter { it.isNotEmpty() }) else value
            CrmFieldType.Url -> Regex("https?://\\S+").find(value)?.value.orEmpty()
            CrmFieldType.Email -> Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+").find(value)?.value.orEmpty()
            CrmFieldType.Date -> value.take(10).takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
                ?: CrmImportParse.date(value)?.toString().orEmpty()
            CrmFieldType.Number -> number(value)
            else -> value
        }
    }

    /**
     * "1,500.50", "1.500,50", "1500,5" and "$ 1 500" as a plain number. With both separators the
     * last one is the decimal one; a lone comma or dot followed by three digits groups thousands.
     */
    fun number(text: String): String {
        val raw = text.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
        val lastComma = raw.lastIndexOf(',')
        val lastDot = raw.lastIndexOf('.')
        val decimal = when {
            lastComma >= 0 && lastDot >= 0 -> if (lastComma > lastDot) ',' else '.'
            lastComma >= 0 || lastDot >= 0 -> {
                val separator = if (lastComma >= 0) ',' else '.'
                val after = raw.length - raw.lastIndexOf(separator) - 1
                if (raw.count { it == separator } == 1 && after != 3) separator else null
            }
            else -> null
        }
        val (whole, fraction) = if (decimal == null) raw to "" else raw.substringBeforeLast(decimal) to raw.substringAfterLast(decimal)
        val digits = whole.filter { it.isDigit() || it == '-' }
        return if (fraction.isEmpty()) digits else "$digits.${fraction.filter { it.isDigit() }}"
    }

    /** A customer field value as stored in flexible_attributes; null when invalid or empty. */
    fun customerValue(text: String, field: CustomerField): JsonElement? {
        val value = text.trim()
        if (value.isEmpty()) return null
        return when (field.type) {
            "boolean" -> JsonPrimitive(formValue(value, CrmFieldType.Boolean, false) == "true")
            "multi_select" -> JsonArray(value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) }).takeIf { it.isNotEmpty() }
            "number", "money" -> formValue(value, CrmFieldType.Number, false).toBigDecimalOrNull()?.let { JsonPrimitive(it.stripTrailingZeros().toPlainString()) }
            "date" -> formValue(value, CrmFieldType.Date, false).takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }
            "email" -> formValue(value, CrmFieldType.Email, false).takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }
            else -> JsonPrimitive(value.take(5000))
        }
    }

    /**
     * Writes the custom values of an imported row: CRM fields into the lead (invalid values are
     * left out and reported) and customer fields into the customer. Empty values never erase.
     */
    fun apply(
        tenant: Connection,
        leadId: UUID,
        crmValues: Map<String, String>,
        customerValues: Map<String, String>,
        crmFields: List<CrmField>,
        customerFields: List<CustomerField>,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (crmValues.isNotEmpty()) {
            val lead = CrmRepository.lead(tenant, leadId) ?: return problems
            val changes = mutableMapOf<String, JsonElement?>()
            crmValues.forEach { (key, text) ->
                val field = crmFields.firstOrNull { it.key == key && it.type != CrmFieldType.Files } ?: return@forEach
                val form = formValue(text, field.type, field.multiple).ifEmpty { return@forEach }
                try {
                    // Options a lead already had stay valid; a value not in the list is reported.
                    CrmFields.parseValues(mapOf(key to form), listOf(field.copy(required = false)), lead.customFields)[key]?.let { changes[key] = it }
                } catch (failure: CrmValidationException) {
                    problems += failure.message.orEmpty()
                }
            }
            if (changes.isNotEmpty()) {
                tenant.prepareStatement("UPDATE crm_leads SET custom_fields = CAST(? AS jsonb) WHERE id = ?").use { update ->
                    update.setString(1, CrmFields.merge(lead.customFields, changes).toString())
                    update.setObject(2, leadId)
                    update.executeUpdate()
                }
            }
        }
        if (customerValues.isNotEmpty()) {
            val values = customerValues.mapNotNull { (key, text) ->
                val field = customerFields.firstOrNull { it.key == key } ?: return@mapNotNull null
                val value = customerValue(text, field)
                if (value == null && text.isNotBlank()) problems += "${field.label}: valor no valido"
                value?.let { key to it }
            }.toMap()
            if (values.isNotEmpty()) {
                tenant.prepareStatement(
                    "UPDATE customers SET flexible_attributes = flexible_attributes || CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE id = (SELECT customer_id FROM crm_leads WHERE id = ?)"
                ).use { update ->
                    update.setString(1, JsonObject(values).toString())
                    update.setObject(2, leadId)
                    update.executeUpdate()
                }
            }
        }
        return problems
    }
}
