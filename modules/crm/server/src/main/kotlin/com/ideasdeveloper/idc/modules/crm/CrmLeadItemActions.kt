package com.ideasdeveloper.idc.modules.crm

import java.sql.Connection

/**
 * Something the user must change before the action can run. [warning] marks rules (not bad input),
 * which the screens show as a warning instead of an error.
 */
class CrmActionNotice(message: String, val warning: Boolean = false) : Exception(message)

/**
 * Integration actions on a lead's items, shared by the screens and the agent tools so both follow
 * the same rules: closing the sale of a website property, assigning a Cloudbeds room and creating
 * the Cloudbeds reservation. They throw [CrmActionNotice] and return the success message.
 */
object CrmLeadItemActions {
    /** Marks a website property as sold on the site and in the lead, and wins the lead. [values]: price, date. */
    fun markSold(context: CrmScreens.Context, lead: CrmLead, item: CrmItem, values: Map<String, String>, actor: CrmActor): String {
        val tenant = context.tenant
        if (item.source != CrmWordPress.Source) throw CrmActionNotice("Solo las propiedades del sitio se cierran asi.", warning = true)
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
            CrmWordPress.markSold(tenant, item.externalId.toLong(), price, date)
        } catch (failure: CrmWordPressException) {
            throw CrmActionNotice("La venta no se cerro: ${failure.message}")
        }
        CrmItems.setStatus(tenant, item, "sold", price ?: item.price)
        CrmRepository.addActivity(tenant, lead.id, "item", "Venta cerrada: ${item.title}${price?.let { " por ${it.toPlainString()}" }.orEmpty()} el $date. Marcada como vendida en el sitio.", actor)
        // The lead is won: first won stage of the pipeline.
        context.stages.firstOrNull { it.kind == StageKind.Won }?.let { won -> CrmRepository.moveLead(tenant, lead, won, context.stages, actor) }
        return "Venta cerrada y propiedad marcada como vendida."
    }

    /**
     * Links a free Cloudbeds room to the lead as chosen for a stay. [params]: start, end, adults,
     * children (see [CrmCloudbedsScreens.stay]), room, type, roomName, typeName, total, max.
     */
    fun assignRoom(context: CrmScreens.Context, lead: CrmLead, params: Map<String, String>, actor: CrmActor): String {
        val tenant = context.tenant
        val stay = try {
            CrmCloudbedsScreens.stay(lead, params, context.today)
        } catch (failure: CrmValidationException) {
            throw CrmActionNotice(failure.message.orEmpty())
        }
        val room = params["room"].orEmpty().takeIf { it.isNotBlank() && it.length <= 60 } ?: throw CrmActionNotice("Habitacion invalida.")
        val chosenDates = CrmItems.list(tenant, lead.id, CrmCloudbeds.Source).filter { it.status == "chosen" }.map { it.startsOn to it.endsOn }.toSet()
        if (chosenDates.isNotEmpty() && chosenDates != setOf(stay.start to stay.end)) {
            throw CrmActionNotice("Las habitaciones de una reserva comparten fechas: quita las otras o busca con sus fechas.", warning = true)
        }
        val title = "${params["roomName"].orEmpty()} - ${params["typeName"].orEmpty()}".trim(' ', '-').ifEmpty { "Habitacion $room" }
        CrmItems.link(
            tenant, lead.id, CrmCloudbeds.Source, room, title,
            subtitle = params["max"]?.toIntOrNull()?.takeIf { it > 0 }?.let { "Hasta $it personas" }.orEmpty(),
            price = params["total"]?.toBigDecimalOrNull(), status = "chosen", startsOn = stay.start, endsOn = stay.end,
            data = mapOf("roomTypeId" to params["type"].orEmpty(), "max" to params["max"].orEmpty(), "adults" to stay.adults.toString(), "children" to stay.children.toString()),
        )
        CrmRepository.addActivity(tenant, lead.id, "item", "Habitacion asignada: $title, del ${stay.start} al ${stay.end}.", actor)
        return "$title asignada a ${lead.name}."
    }

    /** Result of a reservation: the message and whether the user should check something in Cloudbeds. */
    data class Reservation(val message: String, val warning: Boolean)

    /**
     * Creates in Cloudbeds the reservation of the lead's chosen rooms and wins the lead. [values]:
     * adults_<itemId>, children_<itemId>, country, zip, payment (label of [CrmCloudbeds.paymentLabels]).
     */
    fun reserve(context: CrmScreens.Context, lead: CrmLead, values: Map<String, String>, actor: CrmActor): Reservation {
        val tenant = context.tenant
        val rooms = CrmItems.list(tenant, lead.id, CrmCloudbeds.Source).filter { it.status == "chosen" }
        val start = rooms.firstOrNull()?.startsOn
        val end = rooms.firstOrNull()?.endsOn
        if (rooms.isEmpty() || start == null || end == null) throw CrmActionNotice("Asigna al menos una habitacion.", warning = true)
        if (rooms.any { it.startsOn != start || it.endsOn != end }) throw CrmActionNotice("Las habitaciones tienen fechas distintas; deja solo las de una estadia.", warning = true)
        val requests = rooms.map { room ->
            val adults = values["adults_${room.id}"]?.trim()?.toIntOrNull()?.takeIf { it in 1..50 } ?: throw CrmActionNotice("Cada habitacion necesita al menos un adulto (${room.title}).")
            val children = values["children_${room.id}"]?.trim()?.ifEmpty { "0" }?.toIntOrNull()?.takeIf { it in 0..50 } ?: 0
            val max = room.data["max"]?.toIntOrNull() ?: 0
            if (max > 0 && adults + children > max) throw CrmActionNotice("${room.title} admite hasta $max personas.")
            CbRoomRequest(room.externalId, room.data["roomTypeId"].orEmpty().ifEmpty { throw CrmActionNotice("Vuelve a asignar ${room.title}.") }, adults, children)
        }
        val country = values["country"].orEmpty().trim().uppercase()
        if (country.isNotEmpty() && !country.matches(Regex("^[A-Z]{2}$"))) throw CrmActionNotice("El pais es un codigo de 2 letras, por ejemplo NI o US.")
        val (first, last) = CrmContacts.splitName(lead.name)
        val guest = CbGuest(first, last, lead.email, CrmContacts.normalizePhone(lead.phone), country, values["zip"].orEmpty().trim().take(20))
        val result = try {
            val payment = CrmCloudbeds.paymentLabels.entries.firstOrNull { it.value == values["payment"] }?.key
            CrmCloudbeds.reserve(tenant, lead.id, start, end, guest, requests, payment)
        } catch (failure: CrmCloudbedsException) {
            throw CrmActionNotice("La reserva no se creo: ${failure.message}")
        } catch (failure: CrmValidationException) {
            throw CrmActionNotice(failure.message.orEmpty())
        }
        val roomNames = rooms.joinToString { it.title }
        CrmCloudbeds.saveReservation(tenant, lead.id, result, start, end, roomNames, lead.email)
        rooms.zip(requests).forEach { (room, request) ->
            CrmItems.setStatus(tenant, room, "booked")
            CrmItems.setData(tenant, room, room.data + mapOf("adults" to request.adults.toString(), "children" to request.children.toString(), "reservation" to result.reservationId))
        }
        CrmRepository.addActivity(tenant, lead.id, "item", "Reserva ${result.reservationId} creada en Cloudbeds: $roomNames, del $start al $end${result.total?.let { " - total ${it.toPlainString()}" }.orEmpty()}.", actor)
        context.stages.firstOrNull { it.kind == StageKind.Won }?.let { won -> CrmRepository.moveLead(tenant, lead, won, context.stages, actor) }
        return if (result.unassigned > 0) {
            Reservation("Reserva ${result.reservationId} creada, pero Cloudbeds dejo ${result.unassigned} habitacion(es) sin asignar (puede que se ocupara o que la propiedad no permita elegir habitacion). Revisala en Cloudbeds.", warning = true)
        } else {
            Reservation("Reserva ${result.reservationId} creada en Cloudbeds.", warning = false)
        }
    }
}
