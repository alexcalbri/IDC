package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.GroupCompany
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * One customer record for the companies of the same business owner (V007). The customer belongs to
 * the company where it was created: there origin_company_code is '' and [Visible] always holds.
 * Linked companies (ClientesLinking, Core company_links) copy every customer, with the same id and
 * customer code, to each other (origin_company_code = the origin, shared = TRUE); every edit then
 * reaches every copy. A copy of a company that is no longer linked is hidden, not deleted, because
 * other modules may reference it.
 * General notes live only in the origin company; each company keeps its own history.
 */
object ClientesSharing {
    private val log = org.slf4j.LoggerFactory.getLogger(ClientesSharing::class.java)

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

    // Every column copied between companies (created_at too, so the oldest customer wins a merge).
    class Row(
        val id: UUID,
        val displayName: String,
        val email: String,
        val phone: String,
        val status: String,
        val customerType: String,
        val code: String,
        val attributes: String,
        val createdAt: Instant,
    )

    // Columns read by [readRow].
    const val RowColumns = "id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes::text AS attributes, created_at"

    fun readRow(rows: ResultSet): Row = Row(
        id = rows.getObject("id", UUID::class.java),
        displayName = rows.getString("display_name"),
        email = rows.getString("primary_email"),
        phone = rows.getString("primary_phone"),
        status = rows.getString("status"),
        customerType = rows.getString("customer_type"),
        code = rows.getString("customer_code"),
        attributes = rows.getString("attributes"),
        createdAt = rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
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
     * After an edit or archive in this company, copies the customer's data to the other copies of a
     * shared customer (the origin and the companies it is shared with). Nothing for a customer that
     * is not shared. Returns the names of the companies that could not be updated.
     */
    fun propagate(access: ModuleAccessService, tenant: Connection, currentCode: String, id: UUID): List<String> {
        val customer = customer(tenant, id, currentCode) ?: return emptyList()
        if (!customer.shared) return emptyList()
        val row = row(tenant, id) ?: return emptyList()
        // Only the companies linked right now: a company that was unlinked never receives data.
        val group = access.linkedCompanies(currentCode, ClientesLinking.LinkPurpose)
        val failed = mutableListOf<String>()
        // Short wait for the group lock (never minutes): without it the update still goes on.
        ClientesLinking.withShortLock(access, currentCode) {
            group.filter { it.code != currentCode }.forEach { company ->
                val updated = try {
                    access.tenantConnectionForCompany(company.code)?.use { target ->
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
                } catch (failure: Exception) {
                    log.warn("Could not propagate customer {} to company {}", id, company.code, failure)
                    false
                }
                if (!updated) failed += company.name
            }
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

    /** Inserts or refreshes the copy of [row] in [target]; a refresh keeps the copy's own created_at. */
    fun upsertCopy(target: Connection, row: Row, origin: String, shared: Boolean) {
        target.prepareStatement(
            """
            INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes, origin_company_code, shared, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
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
            upsert.setObject(11, OffsetDateTime.ofInstant(row.createdAt, ZoneOffset.UTC))
            upsert.executeUpdate()
        }
    }

    fun row(tenant: Connection, id: UUID): Row? = tenant.prepareStatement("SELECT $RowColumns FROM customers WHERE id = ?").use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) readRow(rows) else null }
    }
}

open class ClientesSharingException(message: String) : Exception(message)
