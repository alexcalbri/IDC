package com.ideasdeveloper.idc.modules.crm

import java.sql.Connection

/**
 * Something the user must change before the action can run. [warning] marks rules (not bad input),
 * which the screens show as a warning instead of an error.
 */
class CrmActionNotice(message: String, val warning: Boolean = false) : Exception(message)

/** Aviso cuando la extensión de Cloudbeds no está instalada y alguien pide algo de ella. */
const val CrmCloudbedsMissing = "La integración de Cloudbeds no está instalada."

/**
 * Integration actions on a lead's items, shared by the screens and the agent tools so both follow
 * the same rules: closing the sale of a website property, assigning a Cloudbeds room and creating
 * the Cloudbeds reservation. They throw [CrmActionNotice] and return the success message.
 */
object CrmLeadItemActions {
    /** Marks a website property as sold on the site and in the lead, and wins the lead. [values]: price, date. */
    fun markSold(context: CrmScreens.Context, lead: CrmLead, item: CrmItem, values: Map<String, String>, actor: CrmActor): String {
        val tenant = context.tenant
        if (item.source != WordPressPluginInfo.Source) throw CrmActionNotice("Solo las propiedades del sitio se cierran asi.", warning = true)
        val price = values["price"].orEmpty().trim().ifEmpty { null }?.let {
            it.replace(",", "").toBigDecimalOrNull()?.takeIf { value -> value.signum() >= 0 }
                ?: throw CrmActionNotice("El precio debe ser un numero positivo.")
        }
        val date = try {
            CrmWordPressScreens.saleDate(values["date"], context.today)
        } catch (failure: CrmValidationException) {
            throw CrmActionNotice(failure.message.orEmpty())
        }
        try {
            CrmHost.wordpressMarkSold(tenant, item.externalId.toLong(), price, date)
        } catch (failure: CrmWordPressException) {
            throw CrmActionNotice("La venta no se cerro: ${failure.message}")
        }
        CrmItems.setStatus(tenant, item, "sold", price ?: item.price)
        CrmRepository.addActivity(tenant, lead.id, "item", "Venta cerrada: ${item.title}${price?.let { " por ${it.toPlainString()}" }.orEmpty()} el $date. Marcada como vendida en el sitio.", actor)
        // The lead is won: first won stage of the pipeline.
        context.stages.firstOrNull { it.kind == StageKind.Won }?.takeIf { lead.archivedAt == null }?.let { won -> CrmRepository.moveLead(tenant, lead, won, context.stages, actor) }
        return "Venta cerrada y propiedad marcada como vendida."
    }

    /**
     * Links a free Cloudbeds room to the lead as chosen for a stay. [params]: start, end, adults,
     * children (see [CrmCloudbedsScreens.stay]), room, type, roomName, typeName, total, max.
     */
    fun assignRoom(context: CrmScreens.Context, lead: CrmLead, params: Map<String, String>, actor: CrmActor): String =
        cloudbeds().assignRoom(CrmHost, context.tenant, lead, params, actor, context.today).message

    /** El proveedor de Cloudbeds; sin la extensión instalada avisa con un [CrmActionNotice] en vez de romper. */
    private fun cloudbeds(): CloudbedsProvider = CrmModuleExtensions.cloudbeds() ?: throw CrmActionNotice(CrmCloudbedsMissing, warning = true)

    /** Result of a reservation: the message and whether the user should check something in Cloudbeds. */
    data class Reservation(val message: String, val warning: Boolean)

    /**
     * Creates in Cloudbeds the reservation of the lead's chosen rooms and wins the lead. [values]:
     * adults_<itemId>, children_<itemId>, country, zip, payment (label of [CrmCloudbedsStore.paymentLabels]).
     */
    fun reserve(context: CrmScreens.Context, lead: CrmLead, values: Map<String, String>, actor: CrmActor): Reservation =
        reserve(context.tenant, context.stages, lead, values, actor)

    /** [reserve] with only what it needs, so the automations (no screen context) can run it too. */
    fun reserve(tenant: Connection, stages: List<CrmStage>, lead: CrmLead, values: Map<String, String>, actor: CrmActor): Reservation {
        val reservation = cloudbeds().reserveForLead(CrmHost, tenant, lead, values, actor)
        // Reserva creada: el lead pasa a la primera etapa de ganado (decisión del CRM, no de Cloudbeds).
        CrmHost.winLead(tenant, lead, stages, actor)
        return reservation
    }
}
