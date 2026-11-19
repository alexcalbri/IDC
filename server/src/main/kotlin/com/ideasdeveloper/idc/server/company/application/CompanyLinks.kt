package com.ideasdeveloper.idc.server.company.application

import com.ideasdeveloper.idc.server.modules.CompanyLinkException
import com.ideasdeveloper.idc.server.modules.GroupCompany
import java.sql.Connection
import java.util.UUID

/** A company row as the link rules need it (see [CompanyLinks.validateLink]). */
data class LinkCompanyRow(
    val code: String,
    val name: String,
    val active: Boolean,
    val serverWorkspace: Boolean,
    val ownerGroup: UUID?,
)

/**
 * Companies of one business owner that the owner links on purpose for one use (company_links in the
 * central database), e.g. 'clientes'. SQL only: callers pass the central connection and decide who may
 * link (the module checks the business owner; here only the same owner group is enforced).
 *
 * Every read joins companies again by owner_group, so a company that changes owner stops being
 * linked at once, whatever the stored row says. All values go in as parameters.
 */
object CompanyLinks {
    private val PurposePattern = Regex("[a-z][a-z0-9_]{0,62}")

    /** Throws when [purpose] is not a valid link purpose (same rule as the table's CHECK). */
    fun requirePurpose(purpose: String) {
        if (!PurposePattern.matches(purpose)) throw CompanyLinkException("El uso del vinculo no es valido.")
    }

    /**
     * Rules to link [targetCode] to [companyCode]. [groupRows] are the companies of the owner group of
     * [companyCode]; a company of another owner is simply not among them. Throws [CompanyLinkException].
     */
    fun validateLink(companyCode: String, targetCode: String, groupRows: List<LinkCompanyRow>) {
        if (companyCode == targetCode) throw CompanyLinkException("Elige otra empresa para vincular.")
        val company = groupRows.firstOrNull { it.code == companyCode }
            ?: throw CompanyLinkException("La empresa no existe o no pertenece a un dueno.")
        val target = groupRows.firstOrNull { it.code == targetCode }
            ?: throw CompanyLinkException("Solo se pueden vincular empresas del mismo dueno.")
        if (company.serverWorkspace || target.serverWorkspace) throw CompanyLinkException("El espacio del servidor no se puede vincular.")
        if (!company.active || !target.active) throw CompanyLinkException("Solo se pueden vincular empresas activas.")
    }

    /**
     * Rules to unlink [targetCode] from the group of [companyCode]: both must exist and share the owner
     * group (inactive ones may be unlinked). Throws [CompanyLinkException].
     */
    fun validateUnlink(companyCode: String, targetCode: String, groupRows: List<LinkCompanyRow>) {
        if (groupRows.none { it.code == companyCode }) throw CompanyLinkException("La empresa no existe o no pertenece a un dueno.")
        if (groupRows.none { it.code == targetCode }) throw CompanyLinkException("Solo se pueden desvincular empresas del mismo dueno.")
    }

    /**
     * Link group to use when [companyCode] joins: the one it already has inside this owner group, else
     * the owner group itself (one group per owner today).
     */
    fun resolveLinkGroup(ownerGroup: UUID, existingOwnerGroup: UUID?, existingLinkGroup: UUID?): UUID =
        if (existingOwnerGroup == ownerGroup && existingLinkGroup != null) existingLinkGroup else ownerGroup

    /** Companies linked with [companyCode] for [purpose], it first and then by name; only it when not linked. */
    fun linked(connection: Connection, companyCode: String, purpose: String): List<GroupCompany> {
        requirePurpose(purpose)
        val group = connection.prepareStatement(LinkedSql).use { query ->
            query.setString(1, purpose)
            query.setString(2, companyCode)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(GroupCompany(rows.getString(1), rows.getString(2))) }
            }
        }
        if (group.size > 1) return group
        // Not linked (or alone): only the company itself, when it exists.
        return connection.prepareStatement("SELECT code, name FROM companies WHERE code = ?").use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> if (rows.next()) listOf(GroupCompany(rows.getString(1), rows.getString(2))) else emptyList() }
        }
    }

    /** Links [targetCode] and [companyCode] in one transaction; returns the resulting group. */
    fun link(connection: Connection, companyCode: String, targetCode: String, purpose: String, linkedBy: String): List<GroupCompany> {
        requirePurpose(purpose)
        return inTransaction(connection) {
            val ownerGroup = ownerGroupOf(connection, companyCode)
                ?: throw CompanyLinkException("La empresa no existe o no pertenece a un dueno.")
            val rows = lockGroup(connection, ownerGroup)
            validateLink(companyCode, targetCode, rows)
            val existing = linkRow(connection, purpose, companyCode)
            val linkGroup = resolveLinkGroup(ownerGroup, existing?.first, existing?.second)
            val author = linkedBy.take(300)
            upsert(connection, purpose, companyCode, ownerGroup, linkGroup, author)
            upsert(connection, purpose, targetCode, ownerGroup, linkGroup, author)
            linked(connection, companyCode, purpose)
        }
    }

    /**
     * Removes [targetCode] from the group of [companyCode] (it may be itself). When one company is left
     * alone it is removed too. Returns what stays linked on the side of [companyCode].
     */
    fun unlink(connection: Connection, companyCode: String, targetCode: String, purpose: String): List<GroupCompany> {
        requirePurpose(purpose)
        return inTransaction(connection) {
            val ownerGroup = ownerGroupOf(connection, companyCode)
                ?: throw CompanyLinkException("La empresa no existe o no pertenece a un dueno.")
            val rows = lockGroup(connection, ownerGroup)
            validateUnlink(companyCode, targetCode, rows)
            val mine = linkRow(connection, purpose, companyCode)
            val linkGroup = mine?.second
            if (mine != null && mine.first == ownerGroup && linkGroup != null) {
                delete(connection, purpose, targetCode, linkGroup)
                // Whoever is left in the group, still valid; a group of one is no group.
                val left = connection.prepareStatement(LeftInGroupSql).use { query ->
                    query.setString(1, purpose)
                    query.setObject(2, linkGroup)
                    query.setObject(3, ownerGroup)
                    query.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
                }
                if (left.size <= 1) left.forEach { delete(connection, purpose, it, linkGroup) }
            }
            linked(connection, companyCode, purpose)
        }
    }

    private fun <T> inTransaction(connection: Connection, block: () -> T): T {
        val previous = connection.autoCommit
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            runCatching { connection.autoCommit = previous }
        }
    }

    private fun ownerGroupOf(connection: Connection, companyCode: String): UUID? =
        connection.prepareStatement("SELECT owner_group FROM companies WHERE code = ?").use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    // Locks the owner group's rows in a fixed order, so concurrent link/unlink calls cannot deadlock.
    private fun lockGroup(connection: Connection, ownerGroup: UUID): List<LinkCompanyRow> =
        connection.prepareStatement(
            "SELECT code, name, is_active, is_server_workspace, owner_group FROM companies WHERE owner_group = ? ORDER BY code FOR UPDATE"
        ).use { query ->
            query.setObject(1, ownerGroup)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(LinkCompanyRow(rows.getString(1), rows.getString(2), rows.getBoolean(3), rows.getBoolean(4), rows.getObject(5, UUID::class.java)))
                    }
                }
            }
        }

    // (owner_group, link_group) stored for the company, or null when it has no row.
    private fun linkRow(connection: Connection, purpose: String, companyCode: String): Pair<UUID, UUID>? =
        connection.prepareStatement("SELECT owner_group, link_group FROM company_links WHERE purpose = ? AND company_code = ?").use { query ->
            query.setString(1, purpose)
            query.setString(2, companyCode)
            query.executeQuery().use { rows ->
                if (rows.next()) rows.getObject(1, UUID::class.java) to rows.getObject(2, UUID::class.java) else null
            }
        }

    private fun upsert(connection: Connection, purpose: String, companyCode: String, ownerGroup: UUID, linkGroup: UUID, linkedBy: String) {
        connection.prepareStatement(UpsertSql).use { statement ->
            statement.setString(1, purpose)
            statement.setString(2, companyCode)
            statement.setObject(3, ownerGroup)
            statement.setObject(4, linkGroup)
            statement.setString(5, linkedBy)
            statement.executeUpdate()
        }
    }

    private fun delete(connection: Connection, purpose: String, companyCode: String, linkGroup: UUID) {
        connection.prepareStatement("DELETE FROM company_links WHERE purpose = ? AND company_code = ? AND link_group = ?").use { statement ->
            statement.setString(1, purpose)
            statement.setString(2, companyCode)
            statement.setObject(3, linkGroup)
            statement.executeUpdate()
        }
    }

    // The row keeps who and when while the company stays in the same group; a stale row (another owner
    // or group) is replaced as a new link.
    const val UpsertSql: String = """
        INSERT INTO company_links (purpose, company_code, owner_group, link_group, linked_by)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (purpose, company_code) DO UPDATE SET
            linked_by = CASE WHEN company_links.owner_group = EXCLUDED.owner_group AND company_links.link_group = EXCLUDED.link_group
                             THEN company_links.linked_by ELSE EXCLUDED.linked_by END,
            linked_at = CASE WHEN company_links.owner_group = EXCLUDED.owner_group AND company_links.link_group = EXCLUDED.link_group
                             THEN company_links.linked_at ELSE CURRENT_TIMESTAMP END,
            owner_group = EXCLUDED.owner_group,
            link_group = EXCLUDED.link_group
        """

    // Members still in the group for unlinking: NOT filtered by is_active, so a company deactivated
    // while linked can still be unlinked and its row never lingers (it would share again on reactivation).
    const val LeftInGroupSql: String = """
        SELECT l.company_code
        FROM company_links l
        JOIN companies c ON c.code = l.company_code AND c.owner_group = l.owner_group
        WHERE l.purpose = ? AND l.link_group = ? AND l.owner_group = ?
        """

    // Same owner group (stored and current), same link group, active, never the server workspace.
    const val LinkedSql: String = """
        SELECT c.code, c.name
        FROM company_links me
        JOIN companies mc ON mc.code = me.company_code AND mc.owner_group = me.owner_group
        JOIN company_links l ON l.purpose = me.purpose AND l.link_group = me.link_group AND l.owner_group = me.owner_group
        JOIN companies c ON c.code = l.company_code AND c.owner_group = l.owner_group
        WHERE me.purpose = ? AND me.company_code = ?
          AND mc.is_active AND NOT mc.is_server_workspace
          AND c.is_active AND NOT c.is_server_workspace
        ORDER BY CASE WHEN c.code = me.company_code THEN 0 ELSE 1 END, lower(c.name)
        """
}
