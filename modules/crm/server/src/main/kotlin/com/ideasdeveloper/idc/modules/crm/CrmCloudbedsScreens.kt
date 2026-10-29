package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldCondition
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.time.LocalDate

/** Rooms of Cloudbeds for a lead, the reservation and the Cloudbeds settings. */
object CrmCloudbedsScreens {
    private const val UiRoute = CrmScreens.UiRoute

    data class Stay(val start: LocalDate, val end: LocalDate, val adults: Int, val children: Int)

    /** Dates and guests of the search: the params, else the lead's dates (tomorrow and 2 nights by default). */
    fun stay(lead: CrmLead, params: Map<String, String>, today: LocalDate): Stay {
        fun date(key: String) = params[key]?.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull() ?: throw CrmValidationException("Las fechas deben tener el formato AAAA-MM-DD.")
        }
        val start = date("start") ?: lead.targetStart ?: today.plusDays(1)
        val end = date("end") ?: lead.targetEnd?.takeIf { it.isAfter(start) } ?: start.plusDays(2)
        val adults = params["adults"]?.toIntOrNull()?.coerceIn(1, 50) ?: 2
        val children = params["children"]?.toIntOrNull()?.coerceIn(0, 50) ?: 0
        return Stay(start, end, adults, children)
    }

    // ---- Search of free rooms ----

    fun rooms(context: CrmScreens.Context, lead: CrmLead, params: Map<String, String>): List<UiNode> {
        val stay = try {
            stay(lead, params, context.today)
        } catch (failure: CrmValidationException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        }
        val keep = mapOf("lead" to lead.id.toString(), "start" to stay.start.toString(), "end" to stay.end.toString(), "adults" to stay.adults.toString(), "children" to stay.children.toString())
        val form = UiCard(
            listOf(
                UiForm(
                    "stay",
                    listOf(
                        UiField("start", "Entrada", type = "date", required = true),
                        UiField("end", "Salida", type = "date", required = true),
                        UiField("adults", "Adultos", type = "number"),
                        UiField("children", "Ninos", type = "number"),
                    ),
                    keep - "lead",
                    submit = UiButton("Buscar habitaciones", UiNavigate("rooms", mapOf("lead" to lead.id.toString()), formId = "stay"), style = "secondary"),
                    minFieldWidth = 160,
                ),
            ),
        )
        val chosen = CrmItems.list(context.tenant, lead.id, CrmCloudbeds.Source).filter { it.status == "chosen" }.map { it.externalId }.toSet()
        val result = try {
            CrmCloudbeds.availability(context.tenant, stay.start, stay.end, stay.adults, stay.children)
        } catch (failure: CrmCloudbedsException) {
            return listOf(form, UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        } catch (failure: CrmValidationException) {
            return listOf(form, UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        }
        val nights = stay.end.toEpochDay() - stay.start.toEpochDay()
        val list = UiList(
            result.map { room ->
                val isChosen = room.roomId in chosen
                UiListItem(
                    title = "${room.roomName} - ${room.roomTypeName}",
                    subtitle = listOfNotNull(
                        room.maxGuests.takeIf { it > 0 }?.let { "Hasta $it personas" },
                        room.total?.let { "Total $nights noche(s): ${it.toPlainString()}" },
                    ).joinToString(" - "),
                    trailing = if (isChosen) "Asignada" else "Asignar",
                    badges = listOfNotNull(UiBadge("Asignada", UiTone.Success).takeIf { isChosen }),
                    action = if (isChosen) null else UiRequest(
                        UiRoute + "/rooms",
                        params = keep + mapOf(
                            "op" to "assign", "room" to room.roomId, "type" to room.roomTypeId, "roomName" to room.roomName,
                            "typeName" to room.roomTypeName, "total" to room.total?.toPlainString().orEmpty(), "max" to room.maxGuests.toString(),
                        ),
                    ),
                )
            },
            emptyText = "No hay habitaciones libres para esas fechas.",
        )
        return listOf(
            UiRow(listOf(UiButton("Volver al lead", UiNavigate("lead", mapOf("id" to lead.id.toString())), style = "secondary"), UiText("Habitaciones para ${lead.name}", UiTextStyle.Subtitle))),
            form,
            UiCard(listOf(UiText("Toca una habitacion para asignarla al lead; puedes asignar varias.", UiTextStyle.Caption), list), title = "${result.size} libre(s) del ${stay.start} al ${stay.end}"),
        )
    }

    // ---- Lead detail: chosen rooms and reservation ----

    fun reservation(context: CrmScreens.Context, lead: CrmLead, canEdit: Boolean): UiNode? {
        val settings = CrmCloudbeds.load(context.tenant)
        val reservations = CrmCloudbeds.forLead(context.tenant, lead.id)
        if (!settings.ready && reservations.isEmpty()) return null
        val rooms = CrmItems.list(context.tenant, lead.id, CrmCloudbeds.Source).filter { it.status == "chosen" }
        val existing = reservations.map { reservation ->
            UiStatus(
                "Reserva ${reservation.reservationId} (${CrmCloudbeds.statusLabels[reservation.status] ?: reservation.status}) del ${reservation.startDate} al ${reservation.endDate}" +
                    (reservation.total?.let { " - ${it.toPlainString()}" }.orEmpty()) + if (reservation.welcomeSent) " - bienvenida enviada" else "",
                when (reservation.status) {
                    "canceled", "no_show" -> UiTone.Danger
                    "checked_out" -> UiTone.Neutral
                    else -> UiTone.Success
                },
            )
        }
        val form = if (canEdit && rooms.isNotEmpty()) {
            val fields = rooms.flatMap { room ->
                listOf(
                    UiField("adults_${room.id}", "Adultos en ${room.title}", type = "number", section = "Huespedes por habitacion"),
                    UiField("children_${room.id}", "Ninos en ${room.title}", type = "number", section = "Huespedes por habitacion"),
                )
            } + listOf(
                UiField("country", "Pais (codigo de 2 letras)", placeholder = "NI", section = "Datos del huesped"),
                UiField("zip", "Codigo postal", section = "Datos del huesped"),
                UiField("payment", "Metodo de pago", type = "dropdown", required = true, options = CrmCloudbeds.paymentLabels.values.toList(), section = "Datos del huesped",
                    help = "Cloudbeds lo exige; el cobro se gestiona en Cloudbeds."),
            )
            val values = rooms.flatMap { room ->
                listOf("adults_${room.id}" to room.data["adults"].orEmpty().ifEmpty { "2" }, "children_${room.id}" to room.data["children"].orEmpty().ifEmpty { "0" })
            }.toMap() + ("payment" to (CrmCloudbeds.paymentLabels[settings.paymentMethod] ?: CrmCloudbeds.paymentLabels.getValue("cash")))
            UiForm(
                "reservation", fields, values,
                submit = UiButton(
                    "Confirmar reserva",
                    UiRequest(UiRoute + "/lead", params = mapOf("id" to lead.id.toString(), "op" to "reserve"), formId = "reservation",
                        confirm = "Se crea la reserva en Cloudbeds con ${rooms.size} habitacion(es) y el lead pasa a ganado. Continuar?"),
                ),
                minFieldWidth = 200,
            )
        } else null
        val hint = when {
            !settings.ready -> null
            rooms.isEmpty() && reservations.none { it.status in setOf("confirmed", "not_confirmed", "checked_in") } ->
                UiText("Busca las habitaciones libres para las fechas del cliente y asignale una o varias.", UiTextStyle.Caption)
            lead.email.isBlank() && rooms.isNotEmpty() -> UiText("Cloudbeds exige el correo del huesped: agregalo en los datos del lead.", tone = UiTone.Warning)
            else -> null
        }
        return UiCard(
            existing + listOfNotNull(hint, form),
            title = "Reserva (Cloudbeds)",
            actions = listOfNotNull(UiButton("Buscar habitaciones", UiNavigate("rooms", mapOf("lead" to lead.id.toString())), style = "secondary").takeIf { canEdit && settings.ready }),
        )
    }

    // ---- Settings: Integraciones > Cloudbeds ----

    fun settings(context: CrmScreens.Context): List<UiNode> {
        val settings = CrmCloudbeds.load(context.tenant)
        val templates = CrmMail.templates(context.tenant)
        val on = UiFieldCondition("enabled", listOf("true"))
        val welcome = UiFieldCondition("welcomeEnabled", listOf("true"))
        val status = when {
            !settings.ready -> UiStatus("Cloudbeds desactivado", UiTone.Neutral)
            settings.lastError.isNotEmpty() -> UiStatus("Error: ${settings.lastError}", UiTone.Danger)
            else -> UiStatus("Cloudbeds listo", UiTone.Success)
        }
        return listOf(
            UiText("Cloudbeds (PMS)", UiTextStyle.Subtitle),
            UiCard(
                listOf(
                    status,
                    UiText(
                        "Con la API key de la propiedad (permisos read:room, read:rate, read:reservation, write:reservation) el lead muestra las habitaciones libres en sus fechas; al confirmar se crea la reserva en Cloudbeds. " +
                            "El cobro se gestiona en Cloudbeds; el metodo de pago solo se envia porque Cloudbeds lo exige. Cada 30 minutos se lee el estado de las reservas.",
                    ),
                    UiForm(
                        "cloudbeds",
                        listOf(
                            UiField("enabled", "Usar Cloudbeds", type = "boolean"),
                            UiField("apiKey", "API key", type = "password", visibleWhen = on),
                            UiField("propertyId", "ID de la propiedad", visibleWhen = on, help = "Solo si la API key tiene varias propiedades."),
                            UiField("paymentMethod", "Metodo de pago por defecto", help = "Se puede cambiar en cada reserva.", type = "dropdown", options = CrmCloudbeds.paymentLabels.values.toList(), visibleWhen = on),
                            UiField("archiveOnCheckout", "Archivar el lead cuando el huesped hace check-out", type = "boolean", visibleWhen = on),
                            UiField("welcomeEnabled", "Enviar correo de bienvenida", type = "boolean", visibleWhen = on, section = "Bienvenida"),
                            UiField("welcomeTemplate", "Plantilla", type = "dropdown", options = templates.map { it.name }, visibleWhen = welcome, section = "Bienvenida",
                                help = "Usa {codigo_cliente} para la contrasena del WiFi, {checkin}, {checkout}, {habitaciones} y {reserva}."),
                            UiField("welcomeDaysBefore", "Dias antes del check-in", type = "number", visibleWhen = welcome, section = "Bienvenida"),
                            UiField("welcomeHour", "Hora de envio (0-23)", type = "number", visibleWhen = welcome, section = "Bienvenida"),
                        ),
                        mapOf(
                            "enabled" to settings.enabled.toString(),
                            "apiKey" to if (settings.apiKey.isNullOrBlank()) "" else CrmMail.SecretMask,
                            "propertyId" to settings.propertyId.orEmpty(),
                            "paymentMethod" to (CrmCloudbeds.paymentLabels[settings.paymentMethod] ?: "Efectivo"),
                            "archiveOnCheckout" to settings.archiveOnCheckout.toString(),
                            "welcomeEnabled" to settings.welcomeEnabled.toString(),
                            "welcomeTemplate" to templates.firstOrNull { it.id == settings.welcomeTemplateId }?.name.orEmpty(),
                            "welcomeDaysBefore" to settings.welcomeDaysBefore.toString(),
                            "welcomeHour" to settings.welcomeHour.toString(),
                        ),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "cbSave"), formId = "cloudbeds")),
                    ),
                ),
                actions = listOf(UiButton("Probar conexion", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "cbTest")), style = "secondary", enabled = settings.ready)),
            ),
        )
    }
}
