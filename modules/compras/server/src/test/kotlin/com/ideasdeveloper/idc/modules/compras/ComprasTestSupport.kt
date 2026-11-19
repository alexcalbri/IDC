package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Datos de prueba compartidos (sin base de datos). */
object ComprasTestSupport {
    fun actor(userId: UUID? = UUID.randomUUID(), vararg granted: String, owner: Boolean = false, serverOwner: Boolean = false) =
        Actor(userId, "Persona", ModulePermissions("acme", "token", owner, granted.toSet(), userId = userId, serverOwner = serverOwner))

    fun approver(userId: UUID, max: String?, currency: String = "USD", active: Boolean = true) =
        Approver(userId, "Aprobador", max?.let { BigDecimal(it) }, currency, active)

    fun request(
        requesterId: UUID = UUID.randomUUID(),
        status: RequestStatus = RequestStatus.Draft,
        amount: String? = "500.00",
        currency: String = "USD",
        decidedBy: UUID? = null,
    ) = PurchaseRequest(
        id = UUID.randomUUID(), number = 12, title = "Sabanas", description = "", departmentId = null, departmentName = null,
        estimatedAmount = amount?.let { BigDecimal(it) }, currency = currency, requiredDate = null, priority = Priority.Medium,
        status = status, requesterId = requesterId, requesterName = "Ana", decidedBy = decidedBy, decidedByName = null, decidedAt = null,
        decisionReason = null, approvedAmount = null, approvedCurrency = null, sourceKind = null, sourceRef = null, submittedAt = null,
        completedAt = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        approvalAmount = amount?.let { BigDecimal(it) }, approvalCurrency = currency,
    )
}
