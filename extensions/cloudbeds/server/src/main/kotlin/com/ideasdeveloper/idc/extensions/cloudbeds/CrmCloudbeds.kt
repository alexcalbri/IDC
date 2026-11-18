package com.ideasdeveloper.idc.extensions.cloudbeds

import com.ideasdeveloper.idc.modules.crm.CbGuest
import com.ideasdeveloper.idc.modules.crm.CbReservationResult
import com.ideasdeveloper.idc.modules.crm.CbRoom
import com.ideasdeveloper.idc.modules.crm.CbRoomRequest
import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmCloudbedsException
import com.ideasdeveloper.idc.modules.crm.CrmCloudbedsSettings
import com.ideasdeveloper.idc.modules.crm.CrmCloudbedsStore
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmReservation
import com.ideasdeveloper.idc.modules.crm.CrmReservations
import com.ideasdeveloper.idc.modules.crm.CrmValidationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Cloudbeds PMS (API v1.3 with an API key): availability, reservations and their status. */
object CrmCloudbeds {
    private const val Api = "https://api.cloudbeds.com/api/v1.3"
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    // ---- Settings (la persistencia vive en CloudbedsSettingsStore) ----

    fun load(host: CrmHost, tenant: Connection): CrmCloudbedsSettings = CloudbedsSettingsStore.load(host, tenant)

    // ---- HTTP ----

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private fun send(settings: CrmCloudbedsSettings, request: HttpRequest.Builder): JsonObject {
        // Una llave ilegible (cifrada que no se pudo abrir) no se envia: cuenta como sin llave.
        val key = settings.apiKey?.takeUnless { CrmHost.isSecretUnreadable(it) } ?: throw CrmCloudbedsException("Escribe la API key de Cloudbeds en Integraciones.")
        val response = try {
            http.send(request.timeout(Duration.ofSeconds(25)).header("x-api-key", key).header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString())
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CrmCloudbedsException("Se interrumpio la llamada a Cloudbeds.")
        } catch (failure: IOException) {
            throw CrmCloudbedsException("No se pudo contactar Cloudbeds.")
        }
        val body = runCatching { json.parseToJsonElement(response.body()) }.getOrNull() as? JsonObject
        when {
            response.statusCode() == 401 || response.statusCode() == 403 -> throw CrmCloudbedsException("Cloudbeds rechazo la API key o le faltan permisos (read:room, read:rate, read:reservation, write:reservation).")
            body == null -> throw CrmCloudbedsException("Cloudbeds respondio HTTP ${response.statusCode()}.")
            body["success"].bool() == false -> throw CrmCloudbedsException("Cloudbeds: ${body["message"].str().ifEmpty { "la operacion no se completo" }}")
        }
        return requireNotNull(body)
    }

    private fun get(settings: CrmCloudbedsSettings, method: String, params: Map<String, String>): JsonObject {
        val query = (params + listOfNotNull(settings.propertyId?.let { "propertyIDs" to it }.takeIf { method != "getReservation" })).entries
            .joinToString("&") { "${it.key}=${encode(it.value)}" }
        return send(settings, HttpRequest.newBuilder(URI.create("$Api/$method?$query")).GET())
    }

    private fun post(settings: CrmCloudbedsSettings, method: String, form: List<Pair<String, String>>): JsonObject {
        val body = form.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
        return send(settings, HttpRequest.newBuilder(URI.create("$Api/$method")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)))
    }

    private fun put(settings: CrmCloudbedsSettings, method: String, form: List<Pair<String, String>>): JsonObject {
        val body = form.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
        return send(settings, HttpRequest.newBuilder(URI.create("$Api/$method")).header("Content-Type", "application/x-www-form-urlencoded").PUT(HttpRequest.BodyPublishers.ofString(body)))
    }

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonElement?.bool() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.let { text -> text == "1" || text == "true" } }

    /** Cloudbeds configurado y con su extension activa en la empresa. */
    fun active(host: CrmHost, tenant: Connection): Boolean = host.cloudbedsSettings(tenant).ready && host.cloudbedsEnabled(tenant)

    /** Igual que [active] con el facade del CRM (para quien aún no recibe el host). */
    fun active(tenant: Connection): Boolean = active(CrmHost, tenant)

    fun test(tenant: Connection): String = test(CrmHost, tenant)

    fun test(host: CrmHost, tenant: Connection): String {
        host.cloudbedsInactiveReason(tenant)?.let { throw CrmCloudbedsException(it) }
        val settings = host.cloudbedsSettings(tenant)
        val types = get(settings, "getRoomTypes", emptyMap())["data"].arr().size
        host.recordCloudbedsError(tenant, "")
        return "$types tipo(s) de habitacion"
    }

    // ---- Availability ----

    /** Rooms of a getRooms answer that are free (unassigned and not blocked) in the period. */
    fun parseRooms(body: JsonObject): List<CbRoom> = body["data"].arr().flatMap { property ->
        property.obj()?.get("rooms").arr().mapNotNull { it.obj() }
    }.filter { it["roomBlocked"].bool() != true && it["isVirtual"].bool() != true }.map { room ->
        CbRoom(
            roomId = room["roomID"].str(),
            roomName = room["roomName"].str(),
            roomTypeId = room["roomTypeID"].str(),
            roomTypeName = room["roomTypeName"].str(),
            maxGuests = room["maxGuests"].str().toIntOrNull() ?: 0,
            total = null,
            rateId = null,
        )
    }.filter { it.roomId.isNotEmpty() && it.roomTypeId.isNotEmpty() }

    /** Cheapest total rate (and its rate id) per room type that still has rooms available. */
    fun parseRates(body: JsonObject): Map<String, Pair<BigDecimal, String>> = body["data"].arr().mapNotNull { it.obj() }
        .filter { (it["roomsAvailable"].str().toIntOrNull() ?: 0) > 0 }
        .mapNotNull { rate ->
            val type = rate["roomTypeID"].str().ifEmpty { return@mapNotNull null }
            val total = (rate["totalRate"].str().ifEmpty { rate["roomRate"].str() }).toBigDecimalOrNull() ?: return@mapNotNull null
            type to (total to rate["rateID"].str())
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, rates) -> rates.minBy { it.first } }

    /** Free rooms for the dates and guests, cheapest first; types without an available rate are left out. */
    fun availability(tenant: Connection, start: LocalDate, end: LocalDate, adults: Int, children: Int): List<CbRoom> =
        availability(CrmHost, tenant, start, end, adults, children)

    fun availability(host: CrmHost, tenant: Connection, start: LocalDate, end: LocalDate, adults: Int, children: Int): List<CbRoom> {
        host.cloudbedsInactiveReason(tenant)?.let { throw CrmCloudbedsException(it) }
        if (!end.isAfter(start)) throw CrmValidationException("La salida debe ser despues de la entrada.")
        val settings = host.cloudbedsSettings(tenant)
        val dates = mapOf("startDate" to start.toString(), "endDate" to end.toString())
        val rooms = parseRooms(get(settings, "getRooms", dates + ("pageSize" to "100")))
        val rates = parseRates(get(settings, "getRatePlans", dates + mapOf("adults" to adults.toString(), "children" to children.toString())))
        return rooms.mapNotNull { room -> rates[room.roomTypeId]?.let { (total, rateId) -> room.copy(total = total, rateId = rateId) } }
            .sortedWith(compareBy({ it.total }, { it.roomName }))
    }

    // ---- Reservations ----

    /** Form fields of postReservation; Cloudbeds expects the arrays as rooms[0][roomTypeID]=... */
    fun reservationForm(
        settings: CrmCloudbedsSettings,
        leadId: UUID,
        start: LocalDate,
        end: LocalDate,
        guest: CbGuest,
        rooms: List<CbRoomRequest>,
        // Chosen on the lead; the default is the one of Integraciones.
        payment: String = settings.paymentMethod,
        // Valor estimado del lead: si viene (> 0), la reserva se crea con ese total (tarifa por noche = valor / noches),
        // repartido entre las habitaciones. Si es null, Cloudbeds usa su tarifa.
        estimatedValue: BigDecimal? = null,
    ): List<Pair<String, String>> {
        val form = mutableListOf(
            "startDate" to start.toString(),
            "endDate" to end.toString(),
            "guestFirstName" to guest.firstName,
            "guestLastName" to guest.lastName,
            "guestEmail" to guest.email,
            "guestCountry" to guest.country,
            "guestZip" to guest.zip,
            "paymentMethod" to (payment.takeIf { it in CrmCloudbedsStore.paymentLabels } ?: settings.paymentMethod),
            "thirdPartyIdentifier" to "idc-$leadId",
            // The CRM sends its own welcome email.
            "sendEmailConfirmation" to "false",
        )
        if (guest.phone.isNotEmpty()) form += "guestPhone" to guest.phone
        settings.propertyId?.let { form.add(0, "propertyID" to it) }
        rooms.forEachIndexed { index, room ->
            form += "rooms[$index][roomTypeID]" to room.roomTypeId
            form += "rooms[$index][quantity]" to "1"
            form += "rooms[$index][roomID]" to room.roomId
            form += "adults[$index][roomTypeID]" to room.roomTypeId
            form += "adults[$index][quantity]" to room.adults.toString()
            form += "adults[$index][roomID]" to room.roomId
            form += "children[$index][roomTypeID]" to room.roomTypeId
            form += "children[$index][quantity]" to room.children.toString()
            form += "children[$index][roomID]" to room.roomId
        }
        // Precio: si el lead trae valor estimado, se fija la tarifa por noche para que el total de la reserva sea ese valor.
        if (estimatedValue != null && estimatedValue.signum() > 0 && rooms.isNotEmpty()) {
            val nights = (end.toEpochDay() - start.toEpochDay()).toInt().coerceAtLeast(1)
            val perRoom = CrmReservations.splitEvenly(estimatedValue, rooms.size)
            rooms.forEachIndexed { index, _ ->
                val perNight = CrmReservations.splitEvenly(perRoom[index], nights)
                val detailed = buildJsonObject {
                    for (n in 0 until nights) put(start.plusDays(n.toLong()).toString(), perNight[n])
                }
                form += "rooms[$index][rateDetailed]" to detailed.toString()
            }
        }
        return form
    }

    fun parseReservation(body: JsonObject): CbReservationResult {
        val id = body["reservationID"].str().ifEmpty { throw CrmCloudbedsException("Cloudbeds no devolvio el numero de reserva.") }
        return CbReservationResult(id, body["status"].str().ifEmpty { "confirmed" }, body["grandTotal"].str().toBigDecimalOrNull(), body["unassigned"].arr().size)
    }

    fun reserve(host: CrmHost, tenant: Connection, leadId: UUID, start: LocalDate, end: LocalDate, guest: CbGuest, rooms: List<CbRoomRequest>, payment: String? = null, estimatedValue: BigDecimal? = null): CbReservationResult {
        host.cloudbedsInactiveReason(tenant)?.let { throw CrmCloudbedsException(it) }
        if (rooms.isEmpty()) throw CrmValidationException("Elige al menos una habitacion.")
        if (!host.isEmail(guest.email)) throw CrmValidationException("Cloudbeds exige el correo del huesped: agregalo al lead.")
        // Check again: a room may have been taken since it was chosen.
        val free = availability(host, tenant, start, end, rooms.sumOf { it.adults }.coerceAtLeast(1), rooms.sumOf { it.children }).map { it.roomId }.toSet()
        rooms.filter { it.roomId !in free }.takeIf { it.isNotEmpty() }?.let { taken ->
            throw CrmValidationException("Ya no estan libres: ${taken.joinToString { it.roomId }}. Busca de nuevo las habitaciones.")
        }
        val settings = host.cloudbedsSettings(tenant)
        return parseReservation(post(settings, "postReservation", reservationForm(settings, leadId, start, end, guest, rooms, payment ?: settings.paymentMethod, estimatedValue)))
    }

    fun reserve(tenant: Connection, leadId: UUID, start: LocalDate, end: LocalDate, guest: CbGuest, rooms: List<CbRoomRequest>, payment: String? = null, estimatedValue: BigDecimal? = null): CbReservationResult =
        reserve(CrmHost, tenant, leadId, start, end, guest, rooms, payment, estimatedValue)

    fun saveReservation(tenant: Connection, leadId: UUID, result: CbReservationResult, start: LocalDate, end: LocalDate, rooms: String, email: String) =
        CrmHost.saveReservation(tenant, leadId, result, start, end, rooms, email)

    /** Form fields of putReservation that mark a reservation as checked in (Cloudbeds API: reservationID and status). */
    fun checkInForm(settings: CrmCloudbedsSettings, reservationId: String): List<Pair<String, String>> = buildList {
        settings.propertyId?.let { add("propertyID" to it) }
        add("reservationID" to reservationId)
        add("status" to "checked_in")
    }

    /**
     * Checks the guest of [reservation] in at Cloudbeds and records the new status. The reservation must still be waiting
     * for its check-in (confirmed or not confirmed).
     */
    fun checkIn(tenant: Connection, reservation: CrmReservation) = checkIn(CrmHost, tenant, reservation)

    fun checkIn(host: CrmHost, tenant: Connection, reservation: CrmReservation) {
        host.cloudbedsInactiveReason(tenant)?.let { throw CrmCloudbedsException(it) }
        val settings = host.cloudbedsSettings(tenant)
        if (!settings.ready) throw CrmCloudbedsException("Cloudbeds no esta activo: configuralo en Integraciones.")
        if (reservation.status !in host.CheckInStatuses) {
            throw CrmValidationException("La reserva ${reservation.reservationId} esta ${CrmReservations.statusLabels[reservation.status]?.lowercase() ?: reservation.status}: no se puede hacer el check-in.")
        }
        put(settings, "putReservation", checkInForm(settings, reservation.reservationId))
        host.markReservationCheckedIn(tenant, reservation.id)
    }

    fun forLead(tenant: Connection, leadId: UUID) = CrmReservations.forLead(tenant, leadId)

    /**
     * Welcome emails: reservations not canceled whose check-in is [CrmCloudbedsSettings.welcomeDaysBefore]
     * days away or less (and not past), once the welcome hour has come. Each goes out once.
     */
    fun sendWelcomes(tenant: Connection, now: LocalDateTime): Int = sendWelcomes(CrmHost, tenant, now)

    fun sendWelcomes(host: CrmHost, tenant: Connection, now: LocalDateTime): Int {
        if (!host.cloudbedsEnabled(tenant)) return 0
        val settings = host.cloudbedsSettings(tenant)
        if (!settings.welcomeEnabled || now.hour < settings.welcomeHour) return 0
        val template = host.templates(tenant).firstOrNull { it.id == settings.welcomeTemplateId } ?: return 0
        val today = now.toLocalDate()
        val due = host.queryReservations(tenant, "welcome_sent_at IS NULL AND status IN ('confirmed', 'not_confirmed') AND start_date >= ? AND start_date <= ?") {
            it.setObject(1, today)
            it.setObject(2, today.plusDays(settings.welcomeDaysBefore.toLong()))
        }
        var queued = 0
        due.forEach { reservation ->
            val lead = host.lead(tenant, reservation.leadId) ?: return@forEach
            val to = lead.email.ifEmpty { reservation.guestEmail }
            if (!host.isEmail(to)) return@forEach
            // Dates, rooms and number of the reservation replace the lead's; translated to the guest's language when the template asks for it.
            val mail = host.renderFor(
                tenant, template, lead,
                mapOf(
                    "checkin" to reservation.startDate.toString(),
                    "checkout" to reservation.endDate.toString(),
                    "habitaciones" to reservation.rooms,
                    "reserva" to reservation.reservationId,
                ),
            )
            val email = mail.toEmail()
            mail.note?.let { host.addActivity(tenant, lead.id, "item", "Bienvenida: $it", CrmActor(null, "Bienvenida automatica")) }
            host.enqueueMail(tenant, "welcome", to, email, lead.id)
            host.inboxAdd(tenant, lead.id, "out", "email", email.subject, email.text, "welcome:${reservation.reservationId}", "sent", "", null, "Bienvenida automatica")
            host.markReservationWelcomeSent(tenant, reservation.id)
            queued++
        }
        return queued
    }

    /**
     * Reads the status of the open reservations in Cloudbeds. A checked-out stay archives its lead
     * (when enabled); a canceled one is recorded in the activity.
     */
    fun syncStatuses(tenant: Connection, today: LocalDate): Int = syncStatuses(CrmHost, tenant, today)

    fun syncStatuses(host: CrmHost, tenant: Connection, today: LocalDate): Int {
        if (!host.cloudbedsEnabled(tenant)) return 0
        val settings = host.cloudbedsSettings(tenant)
        if (!settings.ready) return 0
        val open = host.queryReservations(tenant, "status IN ('not_confirmed', 'confirmed', 'checked_in') AND start_date <= ?") { it.setObject(1, today.plusDays(60)) }
        var changed = 0
        val exit = host.loadExit(tenant)
        try {
            open.forEach { reservation ->
                val status = get(settings, "getReservation", mapOf("reservationID" to reservation.reservationId))["data"].obj()?.get("status").str()
                if (status.isEmpty() || status == reservation.status || status !in CrmReservations.statusLabels) return@forEach
                host.updateReservationStatus(tenant, reservation.id, status)
                val actor = CrmActor(null, "Cloudbeds")
                host.addActivity(tenant, reservation.leadId, "item", "Reserva ${reservation.reservationId}: ${CrmReservations.statusLabels[status]}.", actor)
                // With the exit automation on, only leads in its chosen stages are archived (see CrmExit).
                val lead = if (status == "checked_out" && settings.archiveOnCheckout) host.lead(tenant, reservation.leadId) else null
                if (lead != null && host.archivesOnCheckout(exit, settings.archiveOnCheckout, lead.stageId, lead.archivedAt != null, lead.restoredAt != null)) {
                    val archived = host.archiveLeadIfOpen(tenant, reservation.leadId)
                    if (archived > 0) {
                        host.addActivity(tenant, reservation.leadId, "updated", if (exit.enabled) CrmHost.ExitActivityText else "Lead archivado: el huesped hizo check-out.", actor)
                    }
                }
                changed++
            }
            host.recordCloudbedsError(tenant, "")
        } catch (failure: CrmCloudbedsException) {
            host.recordCloudbedsError(tenant, failure.message.orEmpty())
        }
        return changed
    }
}
