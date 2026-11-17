package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.util.UUID

/**
 * "Mover a Clientes": a custom field of the CRM becomes a field of Clientes (customer_field_definitions) with the same key, label
 * and an equivalent type, the values of its leads are copied to their customers (customers.flexible_attributes) without
 * replacing what the customer already has, and the field leaves the CRM. Everything runs in one transaction across both
 * schemas, like the import (CrmImportTargets).
 */
object CrmMoveToCustomers {
    /** What to do with the field of Clientes: create it with [type], or use the one that already has the same key and type. */
    sealed class Plan {
        data class Create(val type: String) : Plan()
        data object Reuse : Plan()
    }

    /** A field of Clientes found by key (active or not). */
    data class Existing(val type: String, val active: Boolean)

    /**
     * Decides the field of Clientes. A key that already exists with another type cancels the move (a message tells which type it has);
     * with the same type it is reused. Fields of files have no equivalent in Clientes.
     */
    fun plan(field: CrmField, existing: Existing?): Plan {
        if (field.type == CrmFieldType.Files) throw CrmValidationException("Los campos de archivos no se pueden mover a Clientes.")
        val type = CrmImportTargets.customerType(field.type, field.multiple)
        if (existing == null) return Plan.Create(type)
        if (existing.type != type) {
            throw CrmValidationException(
                "Clientes ya tiene un campo con la clave ${field.key} de tipo ${existing.type} y aqui es ${type}: no se movio nada. Renombra o elimina uno de los dos."
            )
        }
        return Plan.Reuse
    }

    /** Whether to let the move start; the message says what is missing. Both permissions are asked, the second one of Clientes. */
    fun check(canManageCustomerFields: Boolean, canSettings: Boolean) {
        if (!canSettings) throw CrmValidationException("Necesitas el permiso de configurar el CRM.")
        if (!canManageCustomerFields) throw CrmValidationException("Necesitas tambien el permiso de administrar campos de Clientes (clientes.fields.manage) para mover un campo a Clientes.")
    }

    /** Moves [field] to Clientes and returns the message for the user. */
    fun move(tenant: Connection, field: CrmField, canManageCustomerFields: Boolean, canSettings: Boolean): String {
        check(canManageCustomerFields, canSettings)
        val changedCustomers = mutableListOf<UUID>()
        var copied = 0
        var kept = 0
        val type = CrmImportTargets.customerType(field.type, field.multiple)
        var lost = 0
        CrmRepository.transaction(tenant) {
            // Two moves, or a move and a lead save, never interleave: the field row and the leads are locked first.
            tenant.prepareStatement("SELECT 1 FROM crm_field_definitions WHERE id = ? FOR UPDATE").use { query ->
                query.setObject(1, field.id)
                query.executeQuery().use { rows -> if (!rows.next()) throw CrmValidationException("Ese campo ya no existe.") }
            }
            tenant.createStatement().use { it.execute("LOCK TABLE crm_leads IN SHARE ROW EXCLUSIVE MODE") }
            val existing = existing(tenant, field.key)
            when (plan(field, existing)) {
                is Plan.Create -> create(tenant, field, type)
                Plan.Reuse -> if (existing?.active == false) {
                    tenant.prepareStatement("UPDATE customer_field_definitions SET is_active = TRUE, updated_at = CURRENT_TIMESTAMP WHERE field_key = ?").use { it.setString(1, field.key); it.executeUpdate() }
                }
            }
            val withValue = leadsWithValue(tenant, field.key)
            // Customers are locked in id order (no deadlock with another writer of customers).
            tenant.prepareStatement(
                "SELECT id FROM customers WHERE id IN (SELECT customer_id FROM crm_leads WHERE customer_id IS NOT NULL AND $LeadHasValue) ORDER BY id FOR UPDATE"
            ).use { it.setString(1, field.key); it.setString(2, field.key); it.setString(3, field.key); it.setString(4, field.key); it.executeQuery().close() }
            // The latest lead of each customer that has a value gives it; customers that already have one keep it.
            tenant.prepareStatement(
                """
                WITH latest AS (
                    SELECT DISTINCT ON (customer_id) customer_id, custom_fields -> CAST(? AS text) AS value
                    FROM crm_leads WHERE customer_id IS NOT NULL AND $LeadHasValue
                    ORDER BY customer_id, created_at DESC, id DESC
                )
                UPDATE customers c SET flexible_attributes = c.flexible_attributes || jsonb_build_object(CAST(? AS text), CASE WHEN ? THEN
                        CASE WHEN jsonb_typeof(latest.value) = 'array' THEN latest.value ELSE jsonb_build_array(latest.value) END
                    ELSE
                        CASE WHEN jsonb_typeof(latest.value) = 'array' THEN latest.value -> 0 ELSE latest.value END
                    END), updated_at = CURRENT_TIMESTAMP
                FROM latest
                WHERE c.id = latest.customer_id AND $CustomerEmpty
                RETURNING c.id
                """.trimIndent()
            ).use { update ->
                // Parameters in order of appearance: the value, the 4 of LeadHasValue, the key written, multiple, the 4 of CustomerEmpty.
                update.setString(1, field.key)
                repeat(4) { update.setString(2 + it, field.key) }
                update.setString(6, field.key)
                update.setBoolean(7, type == "multi_select")
                repeat(4) { update.setString(8 + it, field.key) }
                update.executeQuery().use { rows -> while (rows.next()) changedCustomers += rows.getObject(1, UUID::class.java) }
            }
            copied = changedCustomers.size
            kept = withValue - copied
            // The field leaves the CRM (its values too); a mapped date field also leaves the stay mapping.
            lost = CrmFields.remove(tenant, field)
        }
        changedCustomers.forEach { CrmCustomerEvents.changed(tenant, it) }
        return "Campo ${field.label} movido a Clientes: $copied cliente(s) recibieron su valor" +
            (if (kept > 0) " y $kept ya tenian uno (no se tocaron)" else "") + ". Ya no esta en el CRM." + CrmStay.lostNote(lost)
    }

    // SQL of "has a value": the key exists and is not null, not '' and not an empty list (4 parameters: the key). Mirrors [isEmptyValue].
    private const val LeadHasValue =
        "(jsonb_exists(custom_fields, CAST(? AS text)) AND jsonb_typeof(custom_fields -> CAST(? AS text)) <> 'null' AND " +
            "COALESCE(custom_fields ->> CAST(? AS text), '') <> '' AND custom_fields -> CAST(? AS text) <> '[]'::jsonb)"

    // The customer has nothing for the key: missing, null, '' or an empty list (4 parameters: the key).
    private const val CustomerEmpty =
        "(NOT jsonb_exists(c.flexible_attributes, CAST(? AS text)) OR jsonb_typeof(c.flexible_attributes -> CAST(? AS text)) = 'null' OR " +
            "COALESCE(c.flexible_attributes ->> CAST(? AS text), '') = '' OR c.flexible_attributes -> CAST(? AS text) = '[]'::jsonb)"

    /** Whether a stored value counts as empty (nothing to move, or free to be filled): absent, null, blank text or an empty list. */
    fun isEmptyValue(value: kotlinx.serialization.json.JsonElement?): Boolean = when (value) {
        null, is kotlinx.serialization.json.JsonNull -> true
        is JsonArray -> value.isEmpty()
        is JsonPrimitive -> value.isString && value.content.isEmpty()
        else -> false
    }

    private fun existing(tenant: Connection, key: String): Existing? =
        tenant.prepareStatement("SELECT field_type, is_active FROM customer_field_definitions WHERE field_key = ?").use { query ->
            query.setString(1, key)
            query.executeQuery().use { rows -> if (rows.next()) Existing(rows.getString(1), rows.getBoolean(2)) else null }
        }

    private fun create(tenant: Connection, field: CrmField, type: String) {
        val order = tenant.prepareStatement("SELECT COALESCE(max(display_order), -1) + 1 FROM customer_field_definitions").use { query ->
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        tenant.prepareStatement(
            """
            INSERT INTO customer_field_definitions (id, field_key, label, field_type, control_type, is_required, display_order, options)
            VALUES (?, ?, ?, ?, ?, FALSE, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, field.key)
            insert.setString(3, field.label.take(80))
            insert.setString(4, type)
            insert.setString(5, type)
            insert.setInt(6, order)
            insert.setString(7, JsonArray(if (type == "select" || type == "multi_select") field.options.map { JsonPrimitive(it) } else emptyList()).toString())
            insert.executeUpdate()
        }
    }

    // Customers that have a lead with a value for the field.
    private fun leadsWithValue(tenant: Connection, key: String): Int =
        tenant.prepareStatement("SELECT count(DISTINCT customer_id) FROM crm_leads WHERE customer_id IS NOT NULL AND $LeadHasValue").use { query ->
            repeat(4) { query.setString(it + 1, key) }
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
}
