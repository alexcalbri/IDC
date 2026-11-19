package com.ideasdeveloper.idc.modules.compras

import java.sql.Connection
import java.sql.PreparedStatement

/** Fragmento SQL con marcadores `?` y sus valores, en orden. Nunca lleva valores concatenados. */
data class SqlFragment(val sql: String, val params: List<Any?> = emptyList()) {
    /** Une dos condiciones con AND. */
    infix fun and(other: SqlFragment) = SqlFragment("($sql) AND (${other.sql})", params + other.params)

    companion object {
        val True = SqlFragment("TRUE")
        val False = SqlFragment("FALSE")

        /** Todas las condiciones con AND ([True] si no hay ninguna). */
        fun all(parts: List<SqlFragment>): SqlFragment = when (parts.size) {
            0 -> True
            1 -> parts.single()
            else -> SqlFragment(parts.joinToString(" AND ") { "(${it.sql})" }, parts.flatMap { it.params })
        }
    }
}

/** Utilidades JDBC compartidas por el repositorio, los adjuntos y la regla de aprobacion. */
object ComprasSql {
    /** Asigna [params] a [statement] desde el marcador [startAt]. */
    fun bind(statement: PreparedStatement, params: List<Any?>, startAt: Int = 1) {
        params.forEachIndexed { index, value ->
            val position = startAt + index
            when (value) {
                null -> statement.setObject(position, null)
                is String -> statement.setString(position, value)
                is java.math.BigDecimal -> statement.setBigDecimal(position, value)
                is Int -> statement.setInt(position, value)
                is Long -> statement.setLong(position, value)
                is Boolean -> statement.setBoolean(position, value)
                else -> statement.setObject(position, value)
            }
        }
    }

    /**
     * Ejecuta [block] en una transaccion: commit si termina, rollback si falla, y restaura el autoCommit.
     * Si la conexion ya esta dentro de una transaccion (autoCommit apagado) se une a ella y su dueno decide.
     */
    fun <T> transaction(connection: Connection, block: () -> T): T {
        if (!connection.autoCommit) return block()
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            runCatching { connection.autoCommit = true }
        }
    }

    /** Escapa `\`, `%` y `_` para un LIKE ... ESCAPE '\'. */
    fun escapeLike(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/**
 * Visibilidad de solicitudes, UN solo lugar para listas, tablero, totales, busquedas, detalle, descargas
 * y MCP. Un usuario ve una solicitud si: tiene view_all o manage, o es el solicitante, o decidio, o (tiene
 * approve, es aprobador activo y la solicitud esta pendiente con un monto dentro de su limite).
 * Todas las funciones devuelven [SqlFragment] parametrizado; [alias] es el alias de compras_requests.
 */
object ComprasVisibility {
    private val AliasPattern = Regex("^[a-z][a-z0-9_]{0,30}$")

    private fun checked(alias: String): String {
        require(AliasPattern.matches(alias)) { "Alias SQL invalido" }
        return alias
    }

    /** Monto a aprobar en SQL: el de la cotizacion elegida o, si no hay, el estimado. */
    fun approvalAmountSql(alias: String = "r"): String =
        "COALESCE((SELECT q.amount FROM compras_quotes q WHERE q.request_id = ${checked(alias)}.id AND q.selected), $alias.estimated_amount)"

    /** Moneda del monto a aprobar en SQL. */
    fun approvalCurrencySql(alias: String = "r"): String =
        "COALESCE((SELECT q.currency FROM compras_quotes q WHERE q.request_id = ${checked(alias)}.id AND q.selected), $alias.currency)"

    /** Ve todas las solicitudes y los totales (view_all, manage o dueno); sin esto, los totales se calculan sobre las visibles. */
    fun seesAll(actor: Actor): Boolean = actor.can(ComprasModule.ViewAll) || actor.can(ComprasModule.Manage)

    /**
     * Pendientes de aprobacion que [actor] puede aprobar por su limite ([approver] es su fila).
     * El dueno aprueba cualquiera; sin permiso o sin fila activa no aprueba ninguna.
     */
    fun approvable(actor: Actor, approver: Approver?, alias: String = "r"): SqlFragment {
        val a = checked(alias)
        val pending = "$a.status = '${RequestStatus.PendingApproval.id}'"
        if (actor.isOwner) return SqlFragment(pending)
        if (!actor.permissions.has(ComprasModule.Approve) || approver == null || !approver.active) return SqlFragment.False
        val max = approver.maxAmount ?: return SqlFragment(pending)
        return SqlFragment(
            "$pending AND ${approvalAmountSql(a)} <= ? AND ${approvalCurrencySql(a)} = ?",
            listOf(max, approver.limitCurrency),
        )
    }

    /** Condicion WHERE de las solicitudes que [actor] puede ver. */
    fun where(actor: Actor, approver: Approver?, alias: String = "r"): SqlFragment {
        val a = checked(alias)
        if (seesAll(actor)) return SqlFragment.True
        val userId = actor.userId ?: return SqlFragment.False
        val parts = mutableListOf("$a.requester_id = ?", "$a.decided_by = ?")
        val params = mutableListOf<Any?>(userId, userId)
        val approvable = approvable(actor, approver, a)
        if (approvable != SqlFragment.False) {
            parts += "(${approvable.sql})"
            params.addAll(approvable.params)
        }
        return SqlFragment("(${parts.joinToString(" OR ")})", params)
    }

    /** Condicion WHERE de los filtros de la lista (se combina con [where] usando [SqlFragment.and]). */
    fun filter(filter: RequestFilter, actor: Actor, approver: Approver?, alias: String = "r"): SqlFragment {
        val a = checked(alias)
        val parts = mutableListOf<SqlFragment>()
        filter.status?.let { parts += SqlFragment("$a.status = ?", listOf(it.id)) }
        filter.priority?.let { parts += SqlFragment("$a.priority = ?", listOf(it.id)) }
        filter.departmentId?.let { parts += SqlFragment("$a.department_id = ?", listOf(it)) }
        if (filter.mine) parts += actor.userId?.let { SqlFragment("$a.requester_id = ?", listOf(it)) } ?: SqlFragment.False
        filter.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val pattern = "%" + ComprasSql.escapeLike(text.take(100)) + "%"
            parts += SqlFragment(
                "($a.title ILIKE ? ESCAPE '\\' OR $a.description ILIKE ? ESCAPE '\\' OR $a.requester_name ILIKE ? ESCAPE '\\' " +
                    "OR ('C-' || CASE WHEN $a.number < 100000 THEN lpad($a.number::text, 5, '0') ELSE $a.number::text END) ILIKE ? ESCAPE '\\')",
                listOf(pattern, pattern, pattern, pattern),
            )
        }
        if (filter.highOnly) {
            parts += SqlFragment("$a.priority IN ('high', 'critical') AND $a.status NOT IN ('completed', 'cancelled', 'rejected')")
        }
        if (filter.pendingMyApproval) parts += approvable(actor, approver, a)
        return SqlFragment.all(parts)
    }

    /** Esta solicitud concreta es visible para [actor]: la misma regla que [where], para una fila ya cargada. */
    fun canSee(actor: Actor, approver: Approver?, request: PurchaseRequest): Boolean {
        if (seesAll(actor)) return true
        if (actor.isUser(request.requesterId) || actor.isUser(request.decidedBy)) return true
        return request.status == RequestStatus.PendingApproval && ComprasApproval.canApprove(actor, approver, request.approvalAmount, request.approvalCurrency)
    }
}
