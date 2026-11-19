package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Constructores compartidos por las pruebas puras de Mantenimiento (sin base de datos). */
object MantenimientoFixtures {
    fun order(
        kind: OrderKind = OrderKind.Corrective,
        status: OrderStatus = OrderStatus.Open,
        requestedBy: UUID? = null,
        assigneeId: UUID? = null,
        number: Long = 7,
        dueDate: LocalDate? = null,
        title: String = "Fuga en el baño",
    ) = Order(
        id = UUID.randomUUID(),
        number = number,
        kind = kind,
        status = status,
        priority = OrderPriority.Medium,
        title = title,
        description = "",
        propertyId = null,
        propertyName = null,
        assetId = null,
        assetName = null,
        planId = null,
        planDue = null,
        dueDate = dueDate,
        assigneeId = assigneeId,
        requestedBy = requestedBy,
        requestedByName = "",
        department = "",
        quoteAmount = null,
        quoteCurrency = "USD",
        quotedBy = "",
        costAmount = null,
        costCurrency = "USD",
        resolution = "",
        rejectedReason = "",
        approvedAt = null,
        approvedBy = null,
        completedAt = null,
        completedBy = null,
        tareasTaskId = null,
        purchaseRequestId = null,
        purchaseFolio = "",
        purchaseLinkingAt = null,
        createdBy = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    fun permissions(userId: UUID?, vararg granted: String, businessOwner: Boolean = false, serverOwner: Boolean = false) =
        ModulePermissions("acme", "token", businessOwner, granted.toSet(), userId = userId, serverOwner = serverOwner)

    fun user(label: String, email: String?) = MantenimientoUser(UUID.randomUUID(), label, email)
}
