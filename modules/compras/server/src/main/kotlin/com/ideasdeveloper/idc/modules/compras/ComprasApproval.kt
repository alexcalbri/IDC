package com.ideasdeveloper.idc.modules.compras

import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID

/**
 * Regla de aprobacion: basta UNA aprobacion de alguien cuyo limite cubra el monto. El dueno del negocio
 * y el del servidor aprueban sin limite aunque no esten en la lista de aprobadores.
 */
object ComprasApproval {
    /** El limite de [approver] cubre [amount] en [currency]: sin limite, o misma moneda y monto dentro del limite. */
    fun covers(approver: Approver, amount: BigDecimal, currency: String): Boolean {
        val max = approver.maxAmount ?: return true
        return currency == approver.limitCurrency && amount.compareTo(max) <= 0
    }

    /**
     * Puede [actor] aprobar [amount] en [currency]. [approver] es su fila de `compras_approvers` (null si no
     * esta designado). Requiere permiso compras.approve, fila activa y limite que cubra el monto; el monto debe ser > 0.
     * El solicitante puede aprobar lo suyo si cumple lo anterior.
     */
    fun canApprove(actor: Actor, approver: Approver?, amount: BigDecimal?, currency: String): Boolean {
        if (amount == null || amount.signum() <= 0) return false
        if (actor.isOwner) return true
        if (!actor.permissions.has(ComprasModule.Approve)) return false
        return approver != null && approver.active && covers(approver, amount, currency)
    }

    /** Puede [actor] rechazar: dueno, o con compras.approve y aprobador activo (cualquier limite). */
    fun canReject(actor: Actor, approver: Approver?): Boolean =
        actor.isOwner || (actor.permissions.has(ComprasModule.Approve) && approver != null && approver.active)

    // Usuarios activos con la fila de aprobador vigente y los permisos approve y view; el dueno del negocio siempre cuenta.
    // [coverage] es SQL fijo del limite (sin valores). Parametros: approve, view y los del limite.
    private fun eligibleSql(coverage: String): String = """
        SELECT u.id FROM application_users u
        WHERE u.is_active AND (
            EXISTS (SELECT 1 FROM business_owner o WHERE o.user_id = u.id)
            OR (
                EXISTS (SELECT 1 FROM application_user_permissions g WHERE g.user_id = u.id AND g.permission_id = ?)
                AND EXISTS (SELECT 1 FROM application_user_permissions v WHERE v.user_id = u.id AND v.permission_id = ?)
                AND EXISTS (
                    SELECT 1 FROM compras_approvers a
                    WHERE a.user_id = u.id AND a.active AND ($coverage)
                )
            )
        )
        ORDER BY u.id
    """.trimIndent()

    /**
     * Usuarios activos de la empresa que pueden aprobar [amount] en [currency] (a quienes se notifica):
     * el dueno del negocio, o con permisos compras.approve y compras.view y fila activa cuyo limite cubre el monto.
     * Bloqueante (JDBC): llamar dentro de withContext(Dispatchers.IO).
     */
    fun eligibleApprovers(tenant: Connection, amount: BigDecimal, currency: String): List<UUID> =
        tenant.prepareStatement(eligibleSql("a.max_amount IS NULL OR (a.limit_currency = ? AND a.max_amount >= ?)")).use { query ->
            query.setString(1, ComprasModule.Approve)
            query.setString(2, ComprasModule.View)
            query.setString(3, currency)
            query.setBigDecimal(4, amount)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }

    /** Aprobadores sin limite (y el dueno del negocio): a ellos se avisa cuando nadie mas cubre el monto. */
    fun unlimitedApprovers(tenant: Connection): List<UUID> =
        tenant.prepareStatement(eligibleSql("a.max_amount IS NULL")).use { query ->
            query.setString(1, ComprasModule.Approve)
            query.setString(2, ComprasModule.View)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
}
