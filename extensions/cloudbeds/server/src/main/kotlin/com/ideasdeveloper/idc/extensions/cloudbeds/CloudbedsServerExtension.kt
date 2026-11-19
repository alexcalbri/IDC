package com.ideasdeveloper.idc.extensions.cloudbeds

import com.ideasdeveloper.idc.modules.crm.CbGuest
import com.ideasdeveloper.idc.modules.crm.CbRoom
import com.ideasdeveloper.idc.modules.crm.CbRoomRequest
import com.ideasdeveloper.idc.modules.crm.CloudbedsProvider
import com.ideasdeveloper.idc.modules.crm.CrmActionContext
import com.ideasdeveloper.idc.modules.crm.CrmActionKind
import com.ideasdeveloper.idc.modules.crm.CrmActionNotice
import com.ideasdeveloper.idc.modules.crm.CrmActionResult
import com.ideasdeveloper.idc.modules.crm.CrmActionSpec
import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmCloudbedsException
import com.ideasdeveloper.idc.modules.crm.CrmCloudbedsScreens
import com.ideasdeveloper.idc.modules.crm.CrmCloudbedsSettings
import com.ideasdeveloper.idc.modules.crm.CrmCloudbedsStore
import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.idc.modules.crm.CrmExtensions
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmLead
import com.ideasdeveloper.idc.modules.crm.CrmLeadItemActions
import com.ideasdeveloper.idc.modules.crm.CrmTemplate
import com.ideasdeveloper.idc.modules.crm.CrmValidationException
import com.ideasdeveloper.idc.server.extensions.ExtensionContext
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionSource
import com.ideasdeveloper.idc.server.extensions.ExtensionSourceType
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallDefinition
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallOption
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.extensions.ServerExtensionProvider
import com.ideasdeveloper.idc.server.extensions.UninstallDefault
import com.ideasdeveloper.idc.server.extensions.UninstallItem
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Extension "cloudbeds": habitaciones libres, reservas y check-in en el PMS, correo de bienvenida y archivo tras el check-out.
 * No tiene rutas publicas: todo lo hacen acciones y trabajos que comprueban que la extension siga activa.
 */
object CrmCloudbedsExtension : ServerExtension, CloudbedsProvider {
    const val ReservationsOption = "reservations"
    const val RoomsOption = "rooms"
    const val CountryOption = "default_country"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = CrmExtensionIds.Cloudbeds,
        module = "crm",
        displayName = "Cloudbeds",
        description = "Busca habitaciones libres, crea reservas y hace el check-in en Cloudbeds desde el lead; envia la bienvenida y archiva al salir el huesped.",
        version = "1.0.0",
        minModuleVersion = "0.27.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/idc/tree/0000000000000000000000000000000000000000/extensions/cloudbeds",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.cloudbeds.com",
            ExtensionPermissions.SecretsWrite,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.MessagesWrite,
            CrmExtensionPermissions.ItemsWrite,
            CrmExtensionPermissions.ReservationsWrite,
        ),
        // Los ajustes se guardan en ext_cloudbeds__settings y se editan en las pantallas del CRM: ninguno es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(
                ExtensionUninstallOption(ReservationsOption, "Reservas de Cloudbeds registradas en los leads", UninstallDefault.Keep),
                ExtensionUninstallOption(RoomsOption, "Habitaciones de Cloudbeds asignadas a los leads", UninstallDefault.Keep),
                ExtensionUninstallOption(CountryOption, "Pais por defecto del huesped", UninstallDefault.Ask),
            ),
        ),
    )

    override val migrationPaths = listOf("extensions/cloudbeds/migrations/V001__settings.sql")

    override val runtimeTables = listOf(CloudbedsSettingsStore.Table)

    override fun actionSpecs(): List<CrmActionSpec> = listOf(
        CrmActionSpec(CrmActionKind.CloudbedsReserve.id, CrmActionKind.CloudbedsReserve.label),
        CrmActionSpec(CrmActionKind.CloudbedsCheckIn.id, CrmActionKind.CloudbedsCheckIn.label),
    )

    override fun execute(kind: String, context: CrmActionContext): CrmActionResult {
        val tenant = context.tenant
        val lead = context.lead
        val host = CrmHost
        return when (kind) {
            CrmActionKind.CloudbedsReserve.id -> {
                val values = buildMap<String, String> {
                    host.chosenRooms(tenant, lead.id).forEach { room ->
                        put("adults_${room.id}", room.data["adults"].orEmpty().ifEmpty { "1" })
                        put("children_${room.id}", room.data["children"].orEmpty().ifEmpty { "0" })
                    }
                    // Sin pais: reserveForLead usa el pais por defecto de Integraciones > Cloudbeds.
                }
                val reservation = reserveForLead(host, tenant, lead, values, context.actor)
                host.winLead(tenant, lead, context.stages, context.actor)
                CrmActionResult(reservation.message)
            }
            CrmActionKind.CloudbedsCheckIn.id -> CrmActionResult(checkInForLead(host, tenant, lead.id, context.actor))
            else -> throw CrmValidationException("Accion desconocida: $kind.")
        }
    }

    // ---- CloudbedsProvider: lo que el CRM invoca sin depender del código de Cloudbeds ----

    override fun active(host: CrmHost, tenant: Connection): Boolean = CrmCloudbeds.active(host, tenant)

    override fun test(host: CrmHost, tenant: Connection): String = CrmCloudbeds.test(host, tenant)

    override fun availability(host: CrmHost, tenant: Connection, start: LocalDate, end: LocalDate, adults: Int, children: Int): List<CbRoom> =
        CrmCloudbeds.availability(host, tenant, start, end, adults, children)

    override fun saveSettings(host: CrmHost, tenant: Connection, values: Map<String, String>, templates: List<CrmTemplate>) =
        CloudbedsSettingsStore.saveForm(host, tenant, values, templates)

    override fun settings(host: CrmHost, tenant: Connection): CrmCloudbedsSettings = CloudbedsSettingsStore.load(host, tenant)

    override fun recordError(host: CrmHost, tenant: Connection, error: String) = CloudbedsSettingsStore.recordError(host, tenant, error)

    override fun setWelcomeEnabled(host: CrmHost, tenant: Connection, enabled: Boolean) = CloudbedsSettingsStore.setWelcomeEnabled(host, tenant, enabled)

    override fun sendWelcomes(host: CrmHost, tenant: Connection, now: LocalDateTime): Int = CrmCloudbeds.sendWelcomes(host, tenant, now)

    override fun syncStatuses(host: CrmHost, tenant: Connection, today: LocalDate): Int = CrmCloudbeds.syncStatuses(host, tenant, today)

    override fun checkInForLead(host: CrmHost, tenant: Connection, leadId: UUID, actor: CrmActor): String {
        val reservation = host.reservationsForLead(tenant, leadId).firstOrNull { it.status in host.CheckInStatuses }
            ?: throw CrmValidationException("El lead no tiene una reserva de Cloudbeds pendiente de check-in.")
        CrmCloudbeds.checkIn(host, tenant, reservation)
        host.addActivity(tenant, leadId, "item", "Check-in hecho en Cloudbeds: reserva ${reservation.reservationId}.", actor)
        return "Check-in hecho en la reserva ${reservation.reservationId}."
    }

    /**
     * Asigna una habitación libre al lead como elegida para una estadía. [params]: start, end, adults,
     * children (ver [CrmCloudbedsScreens.stay]), room, type, roomName, typeName, total, max.
     */
    override fun assignRoom(host: CrmHost, tenant: Connection, lead: CrmLead, params: Map<String, String>, actor: CrmActor, today: LocalDate): CrmLeadItemActions.Reservation {
        host.cloudbedsInactiveReason(tenant)?.let { throw CrmActionNotice(it, warning = true) }
        val stay = try {
            CrmCloudbedsScreens.stay(lead, params, today)
        } catch (failure: CrmValidationException) {
            throw CrmActionNotice(failure.message.orEmpty())
        }
        val room = params["room"].orEmpty().takeIf { it.isNotBlank() && it.length <= 60 } ?: throw CrmActionNotice("Habitacion invalida.")
        val chosenDates = host.chosenRooms(tenant, lead.id).map { it.startsOn to it.endsOn }.toSet()
        if (chosenDates.isNotEmpty() && chosenDates != setOf(stay.start to stay.end)) {
            throw CrmActionNotice("Las habitaciones de una reserva comparten fechas: quita las otras o busca con sus fechas.", warning = true)
        }
        val title = "${params["roomName"].orEmpty()} - ${params["typeName"].orEmpty()}".trim(' ', '-').ifEmpty { "Habitacion $room" }
        host.linkItem(
            tenant, lead.id, CrmCloudbedsStore.Source, room, title,
            subtitle = params["max"]?.toIntOrNull()?.takeIf { it > 0 }?.let { "Hasta $it personas" }.orEmpty(),
            price = params["total"]?.toBigDecimalOrNull(), status = "chosen", startsOn = stay.start, endsOn = stay.end,
            data = mapOf("roomTypeId" to params["type"].orEmpty(), "max" to params["max"].orEmpty(), "adults" to stay.adults.toString(), "children" to stay.children.toString()),
        )
        host.addActivity(tenant, lead.id, "item", "Habitacion asignada: $title, del ${stay.start} al ${stay.end}.", actor)
        return CrmLeadItemActions.Reservation("$title asignada a ${lead.name}.", warning = false)
    }

    /**
     * Crea en Cloudbeds la reserva de las habitaciones elegidas del lead. [values]:
     * adults_<itemId>, children_<itemId>, country, zip, payment (etiqueta de [CrmCloudbedsStore.paymentLabels]).
     * Lanza [CrmActionNotice]; mover el lead a ganado lo hace quien llama.
     */
    override fun reserveForLead(host: CrmHost, tenant: Connection, lead: CrmLead, values: Map<String, String>, actor: CrmActor): CrmLeadItemActions.Reservation {
        val rooms = host.chosenRooms(tenant, lead.id)
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
        // Cloudbeds rejects a reservation without the guest's country; the automations rely on the default of Integraciones.
        val country = values["country"].orEmpty().trim().uppercase().ifEmpty { host.cloudbedsSettings(tenant).defaultCountry }
        if (country.isEmpty()) throw CrmActionNotice("Falta el pais del huesped: escribelo o pon un pais por defecto en Integraciones > Cloudbeds.", warning = true)
        if (!country.matches(CrmCloudbedsStore.CountryCode)) throw CrmActionNotice("El pais es un codigo de 2 letras, por ejemplo NI o US.")
        val (first, last) = host.splitName(lead.name)
        val guest = CbGuest(first, last, lead.email, host.normalizePhone(lead.phone), country, values["zip"].orEmpty().trim().take(20))
        val result = try {
            val payment = CrmCloudbedsStore.paymentLabels.entries.firstOrNull { it.value == values["payment"] }?.key
            CrmCloudbeds.reserve(host, tenant, lead.id, start, end, guest, requests, payment, lead.estimatedValue)
        } catch (failure: CrmCloudbedsException) {
            throw CrmActionNotice("La reserva no se creo: ${failure.message}")
        } catch (failure: CrmValidationException) {
            throw CrmActionNotice(failure.message.orEmpty())
        }
        val roomNames = rooms.joinToString { it.title }
        host.saveReservation(tenant, lead.id, result, start, end, roomNames, lead.email)
        rooms.zip(requests).forEach { (room, request) ->
            host.setItemStatus(tenant, room, "booked")
            host.setItemData(tenant, room, room.data + mapOf("adults" to request.adults.toString(), "children" to request.children.toString(), "reservation" to result.reservationId))
        }
        host.addActivity(tenant, lead.id, "item", "Reserva ${result.reservationId} creada en Cloudbeds: $roomNames, del $start al $end${result.total?.let { " - total ${it.toPlainString()}" }.orEmpty()}.", actor)
        return if (result.unassigned > 0) {
            CrmLeadItemActions.Reservation("Reserva ${result.reservationId} creada, pero Cloudbeds dejo ${result.unassigned} habitacion(es) sin asignar (puede que se ocupara o que la propiedad no permita elegir habitacion). Revisala en Cloudbeds.", warning = true)
        } else {
            CrmLeadItemActions.Reservation("Reserva ${result.reservationId} creada en Cloudbeds.", warning = false)
        }
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            CrmExtensions.item(null, "Conexion con Cloudbeds y su API key (se borran)", CrmExtensions.count(tenant, "SELECT count(*) FROM ext_cloudbeds__settings WHERE enabled OR coalesce(api_key, '') <> ''"), UninstallDefault.Delete),
            CrmExtensions.item(ReservationsOption, "Reservas de Cloudbeds registradas en los leads", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_reservations"), UninstallDefault.Keep),
            CrmExtensions.item(RoomsOption, "Habitaciones de Cloudbeds asignadas a los leads", CrmExtensions.count(tenant, "SELECT count(*) FROM crm_lead_items WHERE source = ?", CrmCloudbedsStore.Source), UninstallDefault.Keep),
            CrmExtensions.item(CountryOption, "Pais por defecto del huesped", CrmExtensions.count(tenant, "SELECT count(*) FROM ext_cloudbeds__settings WHERE default_country <> ''"), UninstallDefault.Ask),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // Los ajustes viven en la tabla de la extension: se borra su fila (la tabla antigua del CRM no se toca).
        CrmExtensions.update(tenant, "DELETE FROM ext_cloudbeds__settings WHERE id = 1")
        if (choices[ReservationsOption] == UninstallDefault.Delete) CrmExtensions.update(tenant, "DELETE FROM crm_reservations")
        if (choices[RoomsOption] == UninstallDefault.Delete) CrmExtensions.update(tenant, "DELETE FROM crm_lead_items WHERE source = ?", CrmCloudbedsStore.Source)
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class CloudbedsServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = CrmCloudbedsExtension
}
