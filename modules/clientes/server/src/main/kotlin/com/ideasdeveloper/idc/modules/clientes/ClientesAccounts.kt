package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Optional link between a user account of this company and a customer card (V009): the card is the
 * person's record and the account of a user who also buys services may be linked to it. Users and cards
 * live in this company's database, so the link lives here too; the card must be visible in this company.
 *
 * SECURITY: a link grants NOTHING. No authorization check anywhere may read it: the account keeps exactly the
 * permissions the business owner gave it and gains no access to the card, its notes or its history.
 *
 * Anti-absorption rule: an account is only linked to a card whose email equals the account's email (case
 * ignored, never empty), so linking can never attach a user to somebody else's record. There is no self-service:
 * linking needs the business owner or [LinkPermission], also for the user's own account.
 */
internal object ClientesAccounts {
    const val LinkPermission = "clientes.accounts.link"
    const val UserKind = "user"

    /** Shown when the linked account's email changed and no longer equals the card's. */
    const val MismatchWarning = "El correo de la cuenta ya no coincide con el de la ficha."

    /** Whether this company's database already has V009 (its Clientes is up to date). */
    fun supported(tenant: Connection): Boolean = tenant.createStatement().use { statement ->
        statement.executeQuery("SELECT to_regclass('customer_accounts') IS NOT NULL").use { rows -> rows.next() && rows.getBoolean(1) }
    }

    class Account(val id: UUID, val name: String, val email: String)

    class Link(val customerId: UUID, val account: Account?, val linkedBy: String, val linkedAt: OffsetDateTime)

    /** Business owner, or whoever holds [LinkPermission] ([ModulePermissions.has] already grants it to the owner). Own account included. */
    fun canLink(permissions: ModulePermissions): Boolean = permissions.businessOwner || permissions.has(LinkPermission)

    /** The account's name and email are shown only to who can link accounts; everyone else sees just "Usuario". */
    fun canSeeAccount(permissions: ModulePermissions): Boolean = canLink(permissions)

    /** Same email, ignoring case and spaces, and not empty. */
    fun emailMatches(accountEmail: String, customerEmail: String): Boolean {
        val account = accountEmail.trim()
        return account.isNotEmpty() && account.equals(customerEmail.trim(), ignoreCase = true)
    }

    /**
     * Roles of the card, for who may view customers: "Cliente" always and "Usuario" when an account is linked
     * (with its name only when [showAccount]).
     * TODO: Empleado, Propietario and Agente are not derived here (Clientes knows no module); they will come from a Core hook.
     */
    fun roles(link: Link?, showAccount: Boolean): List<String> = buildList {
        add("Cliente")
        if (link != null) add(if (showAccount && link.account != null) "Usuario: ${link.account.name}" else "Usuario")
    }

    private fun requireLinker(permissions: ModulePermissions) {
        if (!canLink(permissions)) throw ClientesSharingException("No tienes permiso para enlazar cuentas de usuario a clientes.")
    }

    private fun readAccount(rows: java.sql.ResultSet) =
        Account(rows.getObject("id", UUID::class.java), rows.getString("name"), rows.getString("email").orEmpty())

    private const val AccountColumns = "u.id, COALESCE(NULLIF(trim(u.display_name), ''), u.postgres_role) AS name, COALESCE(u.email, '') AS email"

    /** Active account of this company by id, or null. */
    fun accountById(tenant: Connection, id: UUID): Account? = tenant.prepareStatement(
        "SELECT $AccountColumns FROM application_users u WHERE u.id = ? AND u.is_active"
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) readAccount(rows) else null }
    }

    /** Active account of this company by email (case ignored); null when none or when the email is not unique. */
    fun accountByEmail(tenant: Connection, email: String): Account? {
        if (email.isBlank()) return null
        return tenant.prepareStatement(
            "SELECT $AccountColumns FROM application_users u WHERE u.is_active AND lower(u.email) = lower(?) LIMIT 2"
        ).use { query ->
            query.setString(1, email.trim())
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(readAccount(rows)) }.singleOrNull() }
        }
    }

    /** Free accounts whose email equals [customerEmail]: the only ones the card may be linked to. */
    fun candidates(tenant: Connection, customerEmail: String): List<Account> {
        if (customerEmail.isBlank()) return emptyList()
        return tenant.prepareStatement(
            """
            SELECT $AccountColumns FROM application_users u
            WHERE u.is_active AND lower(u.email) = lower(?)
              AND NOT EXISTS (SELECT 1 FROM customer_accounts a WHERE a.account_kind = '$UserKind' AND a.account_id = u.id)
            ORDER BY lower(COALESCE(NULLIF(trim(u.display_name), ''), u.postgres_role))
            LIMIT 20
            """.trimIndent()
        ).use { query ->
            query.setString(1, customerEmail.trim())
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(readAccount(rows)) } }
        }
    }

    /** The user account linked to the card, or null. The account may no longer exist (name then null). */
    fun linkOf(tenant: Connection, customerId: UUID): Link? = tenant.prepareStatement(
        """
        SELECT a.linked_by, a.linked_at, u.id AS uid, COALESCE(NULLIF(trim(u.display_name), ''), u.postgres_role) AS name, COALESCE(u.email, '') AS email
        FROM customer_accounts a LEFT JOIN application_users u ON u.id = a.account_id
        WHERE a.customer_id = ? AND a.account_kind = '$UserKind'
        """.trimIndent()
    ).use { query ->
        query.setObject(1, customerId)
        query.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            val account = rows.getObject("uid", UUID::class.java)?.let { Account(it, rows.getString("name"), rows.getString("email")) }
            Link(customerId, account, rows.getString("linked_by"), rows.getObject("linked_at", OffsetDateTime::class.java))
        }
    }

    private fun isUniqueViolation(failure: SQLException) = failure.sqlState == "23505"

    /**
     * Links [accountId] to the card [customerId]. The card must be visible here, active and have the account's email.
     * One account, one card; one user account per card.
     */
    fun link(tenant: Connection, currentCode: String, permissions: ModulePermissions, customerId: UUID, accountId: UUID, author: Pair<UUID?, String>): String {
        requireLinker(permissions)
        val account = accountById(tenant, accountId) ?: throw ClientesSharingException("La cuenta no existe o esta inactiva.")
        if (account.email.isBlank()) throw ClientesSharingException("La cuenta ${account.name} no tiene correo: agregaselo antes de enlazarla a un cliente.")
        val customer = ClientesSharing.customer(tenant, customerId, currentCode)?.takeIf { it.visible }
            ?: throw ClientesSharingException("Cliente no encontrado.")
        if (customer.archived) throw ClientesSharingException("El cliente esta archivado: restauralo antes de enlazar una cuenta.")
        if (!emailMatches(account.email, customer.email)) {
            throw ClientesSharingException("El correo de la cuenta (${account.email}) no es el de la ficha (${customer.email.ifBlank { "sin correo" }}). Solo se enlaza a la ficha con el mismo correo.")
        }
        insertLink(tenant, customerId, account, author)
        return "Cuenta de ${account.name} enlazada a ${customer.name}."
    }

    private fun insertLink(tenant: Connection, customerId: UUID, account: Account, author: Pair<UUID?, String>) {
        linkOf(tenant, customerId)?.let { throw ClientesSharingException("Esta ficha ya tiene una cuenta de usuario enlazada.") }
        try {
            tenant.prepareStatement(
                "INSERT INTO customer_accounts (id, customer_id, account_kind, account_id, linked_by) VALUES (?, ?, ?, ?, ?)"
            ).use { insert ->
                insert.setObject(1, UUID.randomUUID())
                insert.setObject(2, customerId)
                insert.setString(3, UserKind)
                insert.setObject(4, account.id)
                insert.setString(5, author.second.take(300))
                insert.executeUpdate()
            }
        } catch (failure: SQLException) {
            if (isUniqueViolation(failure)) throw ClientesSharingException("Esa cuenta ya esta enlazada a otro cliente, o la ficha ya tiene una cuenta de usuario.")
            throw failure
        }
        ClientesActivity.record(tenant, customerId, "user_linked", "Cuenta de usuario enlazada", author)
    }

    /** Removes the user link of the card. The card and the account stay as they are. */
    fun unlink(tenant: Connection, customerId: UUID, permissions: ModulePermissions, author: Pair<UUID?, String>): String {
        requireLinker(permissions)
        val link = linkOf(tenant, customerId) ?: throw ClientesSharingException("Esta ficha no tiene una cuenta de usuario enlazada.")
        tenant.prepareStatement("DELETE FROM customer_accounts WHERE customer_id = ? AND account_kind = '$UserKind'").use { delete ->
            delete.setObject(1, customerId)
            delete.executeUpdate()
        }
        ClientesActivity.record(tenant, customerId, "user_unlinked", "Cuenta de usuario desenlazada", author)
        return "Cuenta desenlazada de la ficha."
    }

    /** Result of linking from an account (agents): the card used and whether it was created. */
    class AccountLinkResult(val customerId: UUID, val customerName: String, val created: Boolean, val message: String)

    /**
     * Links the account to the visible card with its email, or creates the card with the account's name and email
     * when there is none. Never to a card with another email or without one. Linking happens before sharing the new
     * card with the linked companies, so a merge carries the link with it.
     */
    fun linkAccount(access: ModuleAccessService, tenant: Connection, currentCode: String, permissions: ModulePermissions, accountId: UUID, author: Pair<UUID?, String>): AccountLinkResult {
        requireLinker(permissions)
        val account = accountById(tenant, accountId) ?: throw ClientesSharingException("La cuenta no existe o esta inactiva.")
        if (account.email.isBlank()) throw ClientesSharingException("La cuenta ${account.name} no tiene correo: agregaselo antes de enlazarla a un cliente.")
        val existing = tenant.prepareStatement(
            "SELECT id FROM customers WHERE lower(primary_email) = lower(?) AND status <> 'archived' AND ${ClientesSharing.Visible} ORDER BY (EXISTS (SELECT 1 FROM customer_accounts a WHERE a.customer_id = customers.id AND a.account_kind = '$UserKind')), created_at, id LIMIT 1"
        ).use { query ->
            query.setString(1, account.email.trim())
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
        if (existing != null) {
            val message = link(tenant, currentCode, permissions, existing, accountId, author)
            return AccountLinkResult(existing, ClientesSharing.customer(tenant, existing, currentCode)?.name.orEmpty(), false, message)
        }
        val request = CreateCustomerRequest(displayName = account.name, primaryEmail = account.email.trim(), primaryPhone = "")
        ClientesServerModule.validateCustomerRequest(tenant, request)?.let { throw ClientesSharingException("No se pudo crear la ficha: ${it.message}") }
        val id = UUID.randomUUID()
        // Card, activity and link in one transaction: if the link fails no orphan card is left. Sharing runs after the commit.
        val autoCommit = tenant.autoCommit
        tenant.autoCommit = false
        try {
            ClientesServerModule.upsertCustomer(tenant, id, request, insert = true)
            ClientesActivity.record(tenant, id, "created", "Creado desde una cuenta de usuario", author)
            insertLink(tenant, id, account, author)
            tenant.commit()
        } catch (failure: Exception) {
            runCatching { tenant.rollback() }
            throw failure
        } finally {
            tenant.autoCommit = autoCommit
        }
        ClientesServerModule.shareNew(access, tenant, currentCode, id)
        return AccountLinkResult(id, account.name, true, "Se creo la ficha de ${account.name} y se enlazo con su cuenta.")
    }
}
