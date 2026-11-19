package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.modules.CustomerHistoryEvent
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.ui.UiOpenModule
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Eventos de la membresia en el historial del cliente (Clientes). Las visitas no se escriben (serian demasiadas).
 * Escribir dos veces el mismo sourceRef deja una sola entrada; nunca rompe al llamador.
 */
object MembresiaHistory {
    private val log = LoggerFactory.getLogger(MembresiaHistory::class.java)

    fun created(access: ModuleAccessService, tenant: Connection, membership: Membership, author: String = "") =
        write(access, tenant, membership, "membresia_alta", "Membresía ${membership.planName}", "created", author)

    fun renewed(access: ModuleAccessService, tenant: Connection, membership: Membership, author: String = "") =
        write(access, tenant, membership, "membresia_renovada", "Membresía ${membership.planName} renovada", "renewed", author)

    fun cancelled(access: ModuleAccessService, tenant: Connection, membership: Membership, author: String = "") =
        write(access, tenant, membership, "membresia_cancelada", "Membresía ${membership.planName} cancelada", "cancelled", author)

    fun expired(access: ModuleAccessService, tenant: Connection, membership: Membership) =
        write(access, tenant, membership, "membresia_vencida", "Membresía ${membership.planName} vencida", "expired", "")

    /** Texto del detalle: "{inicio} a {fin} · {monto} {moneda}". */
    fun detail(membership: Membership): String =
        "${membership.startsOn} a ${membership.endsOn} · ${membership.amount.toPlainString()} ${membership.currency}"

    private fun write(access: ModuleAccessService, tenant: Connection, membership: Membership, kind: String, title: String, ref: String, author: String) {
        try {
            access.recordCustomerEvent(
                tenant,
                CustomerHistoryEvent(
                    customerId = membership.customerId,
                    moduleId = MembresiaModule.Id,
                    kind = kind,
                    title = title.take(300),
                    detail = detail(membership),
                    author = author,
                    link = UiOpenModule(MembresiaModule.Id, "miembro", mapOf("customer" to membership.customerId.toString())),
                    sourceRef = "membership:${membership.id}:$ref",
                ),
            )
        } catch (failure: Exception) {
            log.warn("No se pudo escribir el evento {} de la membresía {} en el historial: {}", kind, membership.id, failure.message)
        }
    }
}
