package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.ui.UiOpenModule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Integración opcional con Compras: desde una orden se crea la solicitud de compra con la herramienta pública `compras_create_request`
 * y los permisos vivos del usuario (ver [MantenimientoBridge]). La reserva atómica de la orden evita dos solicitudes a la vez.
 */
object MantenimientoPurchases {
    private val log = LoggerFactory.getLogger(MantenimientoPurchases::class.java)

    const val CreateRequestTool = "compras_create_request"
    const val ModuleId = "compras"

    /** Pantalla de una solicitud en Compras (ComprasScreens: "request" con el parámetro id). */
    const val RequestScreen = "request"
    private const val MaxTitle = 200
    private const val MaxDescription = 5000

    sealed class PurchaseOutcome {
        data class Created(val requestId: UUID, val folio: String) : PurchaseOutcome()

        /** La orden ya tiene solicitud o alguien la está pidiendo en este momento. */
        data class Busy(val message: String) : PurchaseOutcome()

        data class Failed(val reason: String) : PurchaseOutcome()
    }

    fun requestTitle(order: Order): String = "Mantenimiento ${order.displayNumber}: ${order.title}".take(MaxTitle)

    /** El monto estimado: la cotización si hay, si no el costo real. */
    fun amountOf(order: Order): Pair<String, String>? {
        order.quoteAmount?.let { return it.toPlainString() to order.quoteCurrency }
        order.costAmount?.let { return it.toPlainString() to order.costCurrency }
        return null
    }

    /** La prioridad de Compras que corresponde a la de la orden (Compras usa critical donde aquí se usa urgent). */
    fun purchasePriority(priority: OrderPriority): String = when (priority) {
        OrderPriority.Urgent -> "critical"
        OrderPriority.High -> "high"
        OrderPriority.Medium -> "medium"
        OrderPriority.Low -> "low"
    }

    fun requestDescription(order: Order): String = buildString {
        order.propertyName?.let { appendLine("Propiedad: $it") }
        order.assetName?.let { appendLine("Activo: $it") }
        if (order.description.isNotBlank()) append(order.description.trim())
    }.trim().take(MaxDescription)

    fun requestArguments(order: Order) = buildJsonObject {
        put("title", requestTitle(order))
        put("description", requestDescription(order))
        amountOf(order)?.let { (amount, currency) ->
            put("estimated_amount", amount)
            put("currency", currency)
        }
        put("priority", purchasePriority(order.priority))
        put("submit", false)
    }

    /** "Ver solicitud de compra": abre la solicitud en Compras. Null si la orden aún no tiene. */
    fun openRequestAction(order: Order): UiOpenModule? =
        order.purchaseRequestId?.let { UiOpenModule(ModuleId, RequestScreen, mapOf("id" to it.toString())) }

    /** Hay botón "Solicitar compra" solo si Compras está activo y el usuario puede usar la herramienta ahora mismo. */
    fun available(access: ModuleAccessService, tenant: Connection, companyCode: String, userId: UUID, serverOwner: Boolean): Boolean =
        MantenimientoBridge.available(access, tenant, companyCode, userId, serverOwner, CreateRequestTool)

    fun idAndFolioOf(json: JsonElement): Pair<UUID, String>? = runCatching {
        val body = json.jsonObject
        val id = UUID.fromString((body["id"] as? JsonPrimitive)?.contentOrNull)
        id to (body["folio"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }.getOrNull()

    // Si no se puede liberar, la reserva expira sola a los 5 minutos: se registra.
    private fun release(tenant: Connection, orderId: UUID) {
        try {
            MantenimientoRepository.releasePurchase(tenant, orderId)
        } catch (failure: Exception) {
            log.warn("No se pudo liberar la reserva de compra de la orden {} ({}).", orderId, failure.javaClass.simpleName)
        }
    }

    /**
     * Reserva la orden, crea la solicitud (borrador) con los permisos del usuario y guarda su id y folio. La reserva se libera siempre
     * que no quede guardada la solicitud. Quien llama comprueba antes que el usuario puede trabajar la orden.
     */
    fun request(access: ModuleAccessService, tenant: Connection, companyCode: String, userId: UUID, serverOwner: Boolean, orderId: UUID): PurchaseOutcome {
        val order = MantenimientoRepository.getOrder(tenant, orderId) ?: return PurchaseOutcome.Failed("La orden ya no existe.")
        when (MantenimientoRepository.reservePurchase(tenant, orderId)) {
            MantenimientoRepository.PurchaseReservation.Reserved -> Unit
            MantenimientoRepository.PurchaseReservation.AlreadyLinked ->
                return PurchaseOutcome.Busy("La orden ya tiene una solicitud de compra (${order.purchaseFolio}).")
            MantenimientoRepository.PurchaseReservation.InProgress ->
                return PurchaseOutcome.Busy("Ya se está creando la solicitud de compra de esta orden. Intenta de nuevo en unos minutos.")
            MantenimientoRepository.PurchaseReservation.Missing -> return PurchaseOutcome.Failed("La orden ya no existe.")
        }
        val result = try {
            MantenimientoBridge.call(access, tenant, companyCode, userId, serverOwner, CreateRequestTool, requestArguments(order))
        } catch (failure: Throwable) {
            // La llamada falló sin respuesta de Compras: se libera la reserva.
            release(tenant, orderId)
            throw failure
        }
        return when (result) {
            is MantenimientoBridge.BridgeResult.Unavailable -> {
                release(tenant, orderId)
                PurchaseOutcome.Failed("No se pudo crear la solicitud de compra: ${result.reason}.")
            }
            is MantenimientoBridge.BridgeResult.Ok -> {
                val created = idAndFolioOf(result.json)
                if (created == null) {
                    // Compras respondió Ok: la solicitud puede existir. La reserva se deja expirar para no duplicarla.
                    log.warn("Compras no devolvió el id de la solicitud de {}.", order.displayNumber)
                    PurchaseOutcome.Failed("Compras no devolvió la solicitud creada.")
                } else {
                    // Con la solicitud ya creada nunca se libera la reserva: se reintenta guardar una vez y, si falla, expira sola.
                    val saved = (1..2).any { runCatching { MantenimientoRepository.savePurchase(tenant, orderId, created.first, created.second) }.getOrDefault(false) }
                    if (!saved) log.warn("No se pudo enlazar la solicitud {} ({}) a la orden {}.", created.second, created.first, order.displayNumber)
                    PurchaseOutcome.Created(created.first, created.second)
                }
            }
        }
    }
}
