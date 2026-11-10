package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.GroupCompany
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import java.sql.Connection
import java.util.UUID

/**
 * One customer record for the companies of the same business owner (V007). The customer belongs to
 * the company where it was created: there origin_company_code is '' and [Visible] always holds.
 * Sharing copies the record, with the same id and customer code, to the other companies of the
 * group (origin_company_code = the origin, shared = TRUE); every edit then reaches every copy. A
 * copy that stops being shared is hidden, not deleted, because other modules may reference it.
 * General notes live only in the origin company; each company keeps its own history.
 */
internal object ClientesSharing {
    // SQL condition of the customers this company sees: its own and the ones shared with it.
    const val Visible = "(origin_company_code = '' OR shared)"

    const val SharePermission = "clientes.share"

    class Customer(
        val id: UUID,
        val name: String,
        val email: String,
        val phone: String,
        val code: String,
        // Company where the customer was created.
        val origin: String,
        val shared: Boolean,
        val archived: Boolean,
        // Whether this company may see it (its own customer or a shared copy).
        val visible: Boolean,
    )

    // Every column copied between companies.
    private class Row(
        val id: UUID,
        val displayName: String,
        val email: String,
        val phone: String,
        val status: String,
        val customerType: String,
        val code: String,
        val attributes: String,
    )

    fun customer(tenant: Connection, id: UUID, currentCode: String): Customer? = tenant.prepareStatement(
        """
        SELECT id, display_name, primary_email, primary_phone, customer_code, origin_company_code, shared, status
        FROM customers WHERE id = ?
        """.trimIndent()
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            val origin = rows.getString("origin_company_code")
            val shared = rows.getBoolean("shared")
            Customer(
                id = id,
                name = rows.getString("display_name"),
                email = rows.getString("primary_email"),
                phone = rows.getString("primary_phone"),
                code = rows.getString("customer_code"),
                origin = origin.ifBlank { currentCode },
                shared = shared,
                archived = rows.getString("status") == "archived",
                visible = origin.isBlank() || shared,
            )
        }
    }

    // Whether the customer row exists and this company may see it.
    fun visible(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("SELECT 1 FROM customers WHERE id = ? AND $Visible").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { it.next() }
        }

    // Whether the customer was created in this company (only there it is shared or archived).
    fun isOrigin(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("SELECT 1 FROM customers WHERE id = ? AND origin_company_code = ''").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { it.next() }
        }

    /**
     * Shares the customer created in [currentCode] with the other companies of the group, or stops
     * sharing it. Sharing checks every company first and changes nothing when one of them already has
     * another customer with the same email or code. Returns what was done, for the user.
     */
    fun setShared(access: ModuleAccessService, tenant: Connection, currentCode: String, id: UUID, share: Boolean): String {
        val row = row(tenant, id) ?: throw ClientesSharingException("Cliente no encontrado.")
        if (!isOrigin(tenant, id)) throw ClientesSharingException("Solo la empresa donde se creo el cliente puede compartirlo.")
        val others = access.groupCompanies(currentCode).filter { it.code != currentCode }
        if (share) {
            if (others.isEmpty()) throw ClientesSharingException("Esta empresa no tiene otras empresas vinculadas con las que compartir clientes.")
            val problems = others.mapNotNull { company ->
                val connection = access.tenantConnectionForCompany(company.code)
                val problem = if (connection == null) {
                    "no se pudo abrir la empresa"
                } else {
                    connection.use { target -> if (!supportsSharing(target)) "actualiza el modulo Clientes de esa empresa" else conflict(target, row) }
                }
                problem?.let { "${company.name}: $it." }
            }
            if (problems.isNotEmpty()) {
                throw ClientesSharingException("No se compartio el cliente. " + problems.joinToString(" "))
            }
            others.forEach { company ->
                access.tenantConnectionForCompany(company.code)?.use { target -> upsertCopy(target, row, origin = currentCode, shared = true) }
            }
        } else {
            others.forEach { company ->
                access.tenantConnectionForCompany(company.code)?.use { target ->
                    if (supportsSharing(target)) {
                        target.prepareStatement("UPDATE customers SET shared = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND origin_company_code = ?").use { update ->
                            update.setObject(1, id)
                            update.setString(2, currentCode)
                            update.executeUpdate()
                        }
                    }
                }
            }
        }
        tenant.prepareStatement("UPDATE customers SET shared = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setBoolean(1, share)
            update.setObject(2, id)
            update.executeUpdate()
        }
        return if (share) {
            "Cliente compartido con ${others.joinToString { it.name }}."
        } else {
            "El cliente ya no se ve en las otras empresas; sus historiales se conservan."
        }
    }

    /**
     * After an edit or archive in this company, copies the customer's data to the other copies of a
     * shared customer (the origin and the companies it is shared with). Nothing for a customer that
     * is not shared. Returns the names of the companies that could not be updated.
     */
    fun propagate(access: ModuleAccessService, tenant: Connection, currentCode: String, id: UUID): List<String> {
        val customer = customer(tenant, id, currentCode) ?: return emptyList()
        if (!customer.shared) return emptyList()
        val row = row(tenant, id) ?: return emptyList()
        val group = access.groupCompanies(currentCode)
        val failed = mutableListOf<String>()
        group.filter { it.code != currentCode }.forEach { company ->
            val updated = access.tenantConnectionForCompany(company.code)?.use { target ->
                if (!supportsSharing(target)) return@use true
                target.prepareStatement(
                    """
                    UPDATE customers SET display_name = ?, primary_email = ?, primary_phone = ?, status = ?, customer_type = ?,
                        flexible_attributes = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.trimIndent()
                ).use { update ->
                    update.setString(1, row.displayName)
                    update.setString(2, row.email)
                    update.setString(3, row.phone)
                    update.setString(4, row.status)
                    update.setString(5, row.customerType)
                    update.setString(6, row.attributes)
                    update.setObject(7, id)
                    update.executeUpdate()
                }
                true
            } ?: false
            if (!updated) failed += company.name
        }
        return failed
    }

    /**
     * Companies whose tab of the customer the user sees: the business owner every company of the
     * group where the customer is (the origin and, when shared, the others); anyone else only the
     * current company, the one they are part of.
     */
    fun tabs(access: ModuleAccessService, tenant: Connection, currentCode: String, customer: Customer, businessOwner: Boolean): List<GroupCompany> {
        val group = access.groupCompanies(currentCode)
        val current = group.firstOrNull { it.code == currentCode }
            ?: access.companyOf(tenant)?.let { GroupCompany(currentCode, it.name) }
            ?: GroupCompany(currentCode, currentCode)
        if (!businessOwner) return listOf(current)
        val others = group.filter { it.code != currentCode }.filter { company ->
            company.code == customer.origin || (customer.shared && access.tenantConnectionForCompany(company.code)?.use { target ->
                supportsSharing(target) && visible(target, customer.id)
            } == true)
        }
        return listOf(current) + others
    }

    // Name of a company of the group, or its code when it is no longer in the group.
    fun companyName(access: ModuleAccessService, currentCode: String, code: String): String =
        access.groupCompanies(currentCode).firstOrNull { it.code == code }?.name ?: code

    /** Runs [block] on the database of [code]: this one when it is the current company. Null when it cannot be opened. */
    fun <T> onCompany(access: ModuleAccessService, tenant: Connection, currentCode: String, code: String, block: (Connection) -> T): T? =
        if (code == currentCode) block(tenant) else access.tenantConnectionForCompany(code)?.use(block)

    // Whether a company's database already has the V007 columns (its Clientes is up to date).
    fun supportsSharing(tenant: Connection): Boolean =
        tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT to_regclass('customer_history') IS NOT NULL").use { rows -> rows.next() && rows.getBoolean(1) }
        }

    // Why [row] cannot be copied to [target], or null when it can.
    private fun conflict(target: Connection, row: Row): String? {
        target.prepareStatement("SELECT 1 FROM customers WHERE customer_code = ? AND id <> ?").use { query ->
            query.setString(1, row.code)
            query.setObject(2, row.id)
            query.executeQuery().use { if (it.next()) return "otro cliente ya usa el codigo ${row.code}" }
        }
        if (row.email.isNotBlank()) {
            target.prepareStatement(
                "SELECT display_name FROM customers WHERE lower(primary_email) = lower(?) AND id <> ? AND status <> 'archived' AND $Visible LIMIT 1"
            ).use { query ->
                query.setString(1, row.email)
                query.setObject(2, row.id)
                query.executeQuery().use { rows -> if (rows.next()) return "ya existe el cliente ${rows.getString(1)} con el correo ${row.email}" }
            }
        }
        return null
    }

    private fun upsertCopy(target: Connection, row: Row, origin: String, shared: Boolean) {
        target.prepareStatement(
            """
            INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes, origin_company_code, shared)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            ON CONFLICT (id) DO UPDATE SET display_name = EXCLUDED.display_name, primary_email = EXCLUDED.primary_email,
                primary_phone = EXCLUDED.primary_phone, status = EXCLUDED.status, customer_type = EXCLUDED.customer_type,
                flexible_attributes = EXCLUDED.flexible_attributes, origin_company_code = EXCLUDED.origin_company_code,
                shared = EXCLUDED.shared, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { upsert ->
            upsert.setObject(1, row.id)
            upsert.setString(2, row.displayName)
            upsert.setString(3, row.email)
            upsert.setString(4, row.phone)
            upsert.setString(5, row.status)
            upsert.setString(6, row.customerType)
            upsert.setString(7, row.code)
            upsert.setString(8, row.attributes)
            upsert.setString(9, origin)
            upsert.setBoolean(10, shared)
            upsert.executeUpdate()
        }
    }

    private fun row(tenant: Connection, id: UUID): Row? = tenant.prepareStatement(
        """
        SELECT id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes::text AS attributes
        FROM customers WHERE id = ?
        """.trimIndent()
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            Row(
                id = id,
                displayName = rows.getString("display_name"),
                email = rows.getString("primary_email"),
                phone = rows.getString("primary_phone"),
                status = rows.getString("status"),
                customerType = rows.getString("customer_type"),
                code = rows.getString("customer_code"),
                attributes = rows.getString("attributes"),
            )
        }
    }
}

class ClientesSharingException(message: String) : Exception(message)
