package com.ideasdeveloper.idc.modules.eventos

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** A current membership of a customer (module Membresia). */
data class ActiveMembership(val planName: String, val endsOn: LocalDate)

/**
 * Optional integration with Membresia, without a compile dependency: it reads the view `membresia_active_members` only when
 * the view exists and the company has the module enabled. Any failure answers "no membership" (and is logged); inside a
 * transaction the query runs in a savepoint so a failure does not abort the caller's transaction.
 */
object EventosMembership {
    private val log = LoggerFactory.getLogger(EventosMembership::class.java)

    fun activeMembership(tenant: Connection, customerId: UUID): ActiveMembership? {
        val savepoint = runCatching { if (tenant.autoCommit) null else tenant.setSavepoint() }.getOrNull()
        return try {
            read(tenant, customerId).also { savepoint?.let { point -> tenant.releaseSavepoint(point) } }
        } catch (failure: Exception) {
            savepoint?.let { point -> runCatching { tenant.rollback(point) } }
            log.warn("No se pudo leer la membresía del cliente ({}).", failure.javaClass.simpleName)
            null
        }
    }

    private fun read(tenant: Connection, customerId: UUID): ActiveMembership? {
        val available = tenant.prepareStatement(
            """
            SELECT to_regclass('public.membresia_active_members') IS NOT NULL
               AND EXISTS (SELECT 1 FROM tenant_modules WHERE module_id = 'membresia' AND status = 'enabled')
            """.trimIndent(),
        ).use { statement -> statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) } }
        if (!available) return null
        return tenant.prepareStatement(
            "SELECT plan_name, ends_on FROM membresia_active_members WHERE customer_id = ? ORDER BY ends_on DESC LIMIT 1",
        ).use { statement ->
            statement.setObject(1, customerId)
            statement.executeQuery().use { rows ->
                if (rows.next()) ActiveMembership(rows.getString(1).orEmpty(), rows.getObject(2, LocalDate::class.java)) else null
            }
        }
    }

    /** "Miembro vigente: {plan} hasta {fecha}", the line shown in the card and when registering. */
    fun label(membership: ActiveMembership): String = "Miembro vigente: ${membership.planName} hasta ${membership.endsOn}"
}
