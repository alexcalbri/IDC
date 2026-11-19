package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.server.modules.CustomerHistoryEvent
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.ui.UiOpenModule
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Lo que la extension escribe en el historial del cliente (Clientes, una pestana por empresa): la venta cerrada
 * (compra para el comprador, venta para el vendedor) y la captacion de una propiedad. Las filas de customer_history las ve hoy
 * quien tenga clientes.view en Clientes, sin pasar por el filtro del CRM: por eso solo llevan el codigo de la propiedad y la fecha de cierre, nunca el precio acordado, las comisiones, los porcentajes, las notas ni los nombres de las otras partes.
 *
 * Las funciones [saleEvents] y [intakeEvents] son puras (se prueban sin base de datos); [sold] e [intake] las escriben sin romper nunca
 * a quien llama (el almacen usa un savepoint dentro de la transaccion). Cada entrada tiene un `sourceRef` estable: repetir el cierre no duplica
 * y, si la venta se reabre despues, la entrada se queda (el historial cuenta lo que paso).
 */
object InmobiliariaCustomerEvents {
    const val ModuleId = "crm"
    const val KindPurchase = "inmueble_compra"
    const val KindSale = "inmueble_venta"
    const val KindIntake = "inmueble_captacion"

    private val log = LoggerFactory.getLogger(InmobiliariaCustomerEvents::class.java)

    // Se asigna al registrar las rutas de la extension, antes de que corra ninguna operacion.
    @Volatile
    var access: ModuleAccessService? = null


    /**
     * Entradas de una venta cerrada: una de compra por cada comprador (el comprador de la transaccion y las partes Comprador con cliente)
     * y una de venta por cada vendedor (las partes Vendedor con cliente y el propietario de la propiedad). Un cliente no se repite
     * dentro de su rol. La fecha es el dia de cierre (UTC), para que lo importado salga con su fecha real.
     */
    fun saleEvents(deal: Deal, property: Property, closedOn: LocalDate, author: String = ""): List<CustomerHistoryEvent> {
        val at = closedOn.atStartOfDay(ZoneOffset.UTC).toInstant()
        val detail = "Cerrada el $closedOn"
        val link = UiOpenModule(ModuleId, InmobiliariaScreenIds.Deal, mapOf("id" to deal.id.toString()))
        val buyers = (listOfNotNull(deal.buyerCustomerId) + deal.parties.filter { it.role == PartyRole.Buyer }.mapNotNull { it.customerId }).distinct()
        val sellers = (deal.parties.filter { it.role == PartyRole.Seller }.mapNotNull { it.customerId } + listOfNotNull(property.ownerCustomerId)).distinct()
        fun event(customerId: UUID, kind: String, title: String, side: String) = CustomerHistoryEvent(
            customerId = customerId,
            moduleId = ModuleId,
            kind = kind,
            title = "$title propiedad ${property.code}",
            detail = detail,
            author = author,
            link = link,
            sourceRef = "deal:${deal.id}:won:$side:$customerId",
            at = at,
        )
        return buyers.map { event(it, KindPurchase, "Compra de", "buyer") } + sellers.map { event(it, KindSale, "Venta de", "seller") }
    }

    /**
     * Entrada de captacion para el propietario de la propiedad. Nada si no hay propietario o si la propiedad viene de una importacion
     * (las importaciones solo generan la venta cerrada, para no inundar el historial).
     */
    fun intakeEvents(property: Property, author: String = "", now: Instant = Instant.now()): List<CustomerHistoryEvent> {
        val owner = property.ownerCustomerId ?: return emptyList()
        if (property.importKey != null) return emptyList()
        return listOf(
            CustomerHistoryEvent(
                customerId = owner,
                moduleId = ModuleId,
                kind = KindIntake,
                title = "Propiedad en captación ${property.code}",
                detail = "",
                author = author,
                link = UiOpenModule(ModuleId, InmobiliariaScreenIds.Property, mapOf("id" to property.id.toString())),
                sourceRef = "property:${property.id}:owner:$owner",
                at = now,
            ),
        )
    }

    /** Escribe las entradas de una venta cerrada. No hace nada sin Clientes y nunca rompe a quien llama. */
    fun sold(tenant: Connection, deal: Deal, property: Property, closedOn: LocalDate, author: String) =
        write(tenant, "sale") { saleEvents(deal, property, closedOn, author) }

    /** Escribe la entrada de captacion de una propiedad con propietario puesto a mano. */
    fun intake(tenant: Connection, property: Property, author: String) =
        write(tenant, "intake") { intakeEvents(property, author) }

    private fun write(tenant: Connection, what: String, events: () -> List<CustomerHistoryEvent>) {
        val access = access ?: return log.debug("Customer history ({}) not written: no access to Clientes yet.", what)
        // recordCustomerEvent ya captura sus fallos y usa un savepoint: no se envuelve de nuevo.
        events().forEach { access.recordCustomerEvent(tenant, it) }
    }
}
