package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

data class CrmCloudbedsSettings(
    val enabled: Boolean,
    val apiKey: String?,
    val propertyId: String?,
    val paymentMethod: String,
    val welcomeEnabled: Boolean,
    val welcomeTemplateId: UUID?,
    val welcomeDaysBefore: Int,
    val welcomeHour: Int,
    val archiveOnCheckout: Boolean,
    val lastError: String,
) {
    val ready get() = enabled && !apiKey.isNullOrBlank()
}

// A free room for the asked dates, with the best total rate of its type for those guests.
data class CbRoom(
    val roomId: String,
    val roomName: String,
    val roomTypeId: String,
    val roomTypeName: String,
    val maxGuests: Int,
    val total: BigDecimal?,
    val rateId: String?,
)

// A room of a reservation: the room and the guests staying in it.
data class CbRoomRequest(val roomId: String, val roomTypeId: String, val adults: Int, val children: Int)

data class CbGuest(val firstName: String, val lastName: String, val email: String, val phone: String, val country: String, val zip: String)

data class CbReservationResult(val reservationId: String, val status: String, val total: BigDecimal?, val unassigned: Int)

data class CrmReservation(
    val id: UUID,
    val leadId: UUID,
    val reservationId: String,
    val status: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val total: BigDecimal?,
    val rooms: String,
    val guestEmail: String,
    val welcomeSent: Boolean,
)

class CrmCloudbedsException(message: String) : Exception(message)

/** Cloudbeds PMS (API v1.3 with an API key): availability, reservations and their status. */
object CrmCloudbeds {
    const val Source = "cloudbeds"
    private const val Api = "https://api.cloudbeds.com/api/v1.3"
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    val paymentLabels = linkedMapOf("cash" to "Efectivo", "credit" to "Tarjeta", "ebanking" to "Transferencia", "pay_pal" to "PayPal")
    val statusLabels = mapOf(
        "not_confirmed" to "Sin confirmar", "confirmed" to "Confirmada", "canceled" to "Cancelada",
        "checked_in" to "En el hotel", "checked_out" to "Salio", "no_show" to "No llego",
    )

    // ---- Settings ----

    fun load(tenant: Connection): CrmCloudbedsSettings = tenant.prepareStatement(
        """
        SELECT enabled, api_key, property_id, payment_method, welcome_enabled, welcome_template_id, welcome_days_before,
               welcome_hour, archive_on_checkout, last_error
        FROM crm_cloudbeds_settings WHERE id = 1
        """.trimIndent()
    ).use { query ->
        query.executeQuery().use { rows ->
            rows.next()
            CrmCloudbedsSettings(
                rows.getBoolean(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getBoolean(5),
                rows.getObject(6, UUID::class.java), rows.getInt(7), rows.getInt(8), rows.getBoolean(9), rows.getString(10),
            )
        }
    }

    fun saveForm(tenant: Connection, values: Map<String, String>, templates: List<CrmTemplate>) {
        val current = load(tenant)
        val key = values["apiKey"]?.trim()?.takeIf { it.isNotEmpty() && it != CrmMail.SecretMask } ?: current.apiKey
        val property = values["propertyId"].orEmpty().trim().ifEmpty { null }
        if (property != null && !property.matches(Regex("^[0-9]{1,30}$"))) throw CrmValidationException("El ID de la propiedad solo lleva numeros.")
        val enabled = values["enabled"] == "true"
        if (enabled && key.isNullOrBlank()) throw CrmValidationException("Para activar Cloudbeds escribe la API key.")
        val payment = paymentLabels.entries.firstOrNull { it.value == values["paymentMethod"] }?.key ?: current.paymentMethod
        val template = values["welcomeTemplate"]?.let { name -> templates.firstOrNull { it.name == name }?.id }
        val welcome = values["welcomeEnabled"] == "true"
        if (welcome && template == null) throw CrmValidationException("Elige la plantilla del correo de bienvenida (creala en Plantillas).")
        val days = values["welcomeDaysBefore"].orEmpty().trim().ifEmpty { "1" }.toIntOrNull()?.takeIf { it in 0..60 }
            ?: throw CrmValidationException("Los dias antes del check-in deben ser un numero entre 0 y 60.")
        val hour = values["welcomeHour"].orEmpty().trim().ifEmpty { "9" }.toIntOrNull()?.takeIf { it in 0..23 }
            ?: throw CrmValidationException("La hora de envio debe ser un numero entre 0 y 23.")
        tenant.prepareStatement(
            """
            UPDATE crm_cloudbeds_settings SET enabled = ?, api_key = ?, property_id = ?, payment_method = ?, welcome_enabled = ?,
                welcome_template_id = ?, welcome_days_before = ?, welcome_hour = ?, archive_on_checkout = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = 1
            """.trimIndent()
        ).use { update ->
            update.setBoolean(1, enabled)
            update.setString(2, key)
            update.setString(3, property)
            update.setString(4, payment)
            update.setBoolean(5, welcome)
            update.setObject(6, template)
            update.setInt(7, days)
            update.setInt(8, hour)
            update.setBoolean(9, values["archiveOnCheckout"] == "true")
            update.executeUpdate()
        }
    }

    private fun recordError(tenant: Connection, error: String) {
        tenant.prepareStatement("UPDATE crm_cloudbeds_settings SET last_error = ?, last_sync = CURRENT_TIMESTAMP WHERE id = 1").use {
            it.setString(1, error.take(500))
            it.executeUpdate()
        }
    }

    // ---- HTTP ----

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private fun send(settings: CrmCloudbedsSettings, request: HttpRequest.Builder): JsonObject {
        val key = settings.apiKey ?: throw CrmCloudbedsException("Escribe la API key de Cloudbeds en Integraciones.")
        val response = try {
            http.send(request.timeout(Duration.ofSeconds(25)).header("x-api-key", key).header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString())
        } catch (failure: Exception) {
            throw CrmCloudbedsException("No se pudo contactar Cloudbeds.")
        }
        val body = runCatching { json.parseToJsonElement(response.body()) }.getOrNull() as? JsonObject
        when {
            response.statusCode() == 401 || response.statusCode() == 403 -> throw CrmCloudbedsException("Cloudbeds rechazo la API key o le faltan permisos (read:room, read:rate, read:reservation, write:reservation).")
            body == null -> throw CrmCloudbedsException("Cloudbeds respondio HTTP ${response.statusCode()}.")
            body["success"].bool() == false -> throw CrmCloudbedsException("Cloudbeds: ${body["message"].str().ifEmpty { "la operacion no se completo" }}")
        }
        return body!!
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

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonElement?.bool() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.let { text -> text == "1" || text == "true" } }

    fun test(tenant: Connection): String {
        val settings = load(tenant)
        val types = get(settings, "getRoomTypes", emptyMap())["data"].arr().size
        recordError(tenant, "")
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
    fun availability(tenant: Connection, start: LocalDate, end: LocalDate, adults: Int, children: Int): List<CbRoom> {
        if (!end.isAfter(start)) throw CrmValidationException("La salida debe ser despues de la entrada.")
        val settings = load(tenant)
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
    ): List<Pair<String, String>> {
        val form = mutableListOf(
            "startDate" to start.toString(),
            "endDate" to end.toString(),
            "guestFirstName" to guest.firstName,
            "guestLastName" to guest.lastName,
            "guestEmail" to guest.email,
            "guestCountry" to guest.country,
            "guestZip" to guest.zip,
            "paymentMethod" to (payment.takeIf { it in paymentLabels } ?: settings.paymentMethod),
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
        return form
    }

    fun parseReservation(body: JsonObject): CbReservationResult {
        val id = body["reservationID"].str().ifEmpty { throw CrmCloudbedsException("Cloudbeds no devolvio el numero de reserva.") }
        return CbReservationResult(id, body["status"].str().ifEmpty { "confirmed" }, body["grandTotal"].str().toBigDecimalOrNull(), body["unassigned"].arr().size)
    }

    fun reserve(tenant: Connection, leadId: UUID, start: LocalDate, end: LocalDate, guest: CbGuest, rooms: List<CbRoomRequest>, payment: String? = null): CbReservationResult {
        if (rooms.isEmpty()) throw CrmValidationException("Elige al menos una habitacion.")
        if (!CrmContacts.isEmail(guest.email)) throw CrmValidationException("Cloudbeds exige el correo del huesped: agregalo al lead.")
        // Check again: a room may have been taken since it was chosen.
        val free = availability(tenant, start, end, rooms.sumOf { it.adults }.coerceAtLeast(1), rooms.sumOf { it.children }).map { it.roomId }.toSet()
        rooms.filter { it.roomId !in free }.takeIf { it.isNotEmpty() }?.let { taken ->
            throw CrmValidationException("Ya no estan libres: ${taken.joinToString { it.roomId }}. Busca de nuevo las habitaciones.")
        }
        val settings = load(tenant)
        return parseReservation(post(settings, "postReservation", reservationForm(settings, leadId, start, end, guest, rooms, payment ?: settings.paymentMethod)))
    }

    fun saveReservation(tenant: Connection, leadId: UUID, result: CbReservationResult, start: LocalDate, end: LocalDate, rooms: String, email: String) {
        tenant.prepareStatement(
            """
            INSERT INTO crm_reservations (id, lead_id, reservation_id, status, start_date, end_date, total, rooms, guest_email)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, leadId)
            insert.setString(3, result.reservationId)
            insert.setString(4, result.status.takeIf { it in statusLabels } ?: "confirmed")
            insert.setObject(5, start)
            insert.setObject(6, end)
            insert.setBigDecimal(7, result.total)
            insert.setString(8, rooms.take(1000))
            insert.setString(9, email)
            insert.executeUpdate()
        }
    }

    private fun reservations(tenant: Connection, condition: String, bind: (java.sql.PreparedStatement) -> Unit = {}): List<CrmReservation> = tenant.prepareStatement(
        "SELECT id, lead_id, reservation_id, status, start_date, end_date, total, rooms, guest_email, welcome_sent_at FROM crm_reservations WHERE $condition ORDER BY start_date"
    ).use { query ->
        bind(query)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(
                    CrmReservation(
                        rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getString(3), rows.getString(4),
                        rows.getObject(5, LocalDate::class.java), rows.getObject(6, LocalDate::class.java), rows.getBigDecimal(7),
                        rows.getString(8), rows.getString(9), rows.getTimestamp(10) != null,
                    )
                )
            }
        }
    }

    fun forLead(tenant: Connection, leadId: UUID) = reservations(tenant, "lead_id = ?") { it.setObject(1, leadId) }

    /**
     * Welcome emails: reservations not canceled whose check-in is [CrmCloudbedsSettings.welcomeDaysBefore]
     * days away or less (and not past), once the welcome hour has come. Each goes out once.
     */
    fun sendWelcomes(tenant: Connection, now: LocalDateTime): Int {
        val settings = load(tenant)
        if (!settings.welcomeEnabled || now.hour < settings.welcomeHour) return 0
        val template = CrmMail.templates(tenant).firstOrNull { it.id == settings.welcomeTemplateId } ?: return 0
        val today = now.toLocalDate()
        val due = reservations(tenant, "welcome_sent_at IS NULL AND status IN ('confirmed', 'not_confirmed') AND start_date >= ? AND start_date <= ?") {
            it.setObject(1, today)
            it.setObject(2, today.plusDays(settings.welcomeDaysBefore.toLong()))
        }
        val names = CrmNames(CrmRepository.users(tenant), CrmRepository.interests(tenant))
        val stages = CrmRepository.stages(tenant)
        var queued = 0
        due.forEach { reservation ->
            val lead = CrmRepository.lead(tenant, reservation.leadId) ?: return@forEach
            val to = lead.email.ifEmpty { reservation.guestEmail }
            if (!CrmContacts.isEmail(to)) return@forEach
            val values = CrmInbox.templateValues(
                tenant, lead, names, stages,
                mapOf(
                    "checkin" to reservation.startDate.toString(),
                    "checkout" to reservation.endDate.toString(),
                    "habitaciones" to reservation.rooms,
                    "reserva" to reservation.reservationId,
                ),
            )
            val email = CrmMail.render(template.subject, template.body, values)
            CrmNotify.enqueue(tenant, "welcome", to, email, lead.id)
            CrmInbox.addMessage(tenant, lead.id, "out", "email", email.subject, email.text, "welcome:${reservation.reservationId}", "sent", "", null, "Bienvenida automatica")
            tenant.prepareStatement("UPDATE crm_reservations SET welcome_sent_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, reservation.id); it.executeUpdate() }
            queued++
        }
        return queued
    }

    /**
     * Reads the status of the open reservations in Cloudbeds. A checked-out stay archives its lead
     * (when enabled); a canceled one is recorded in the activity.
     */
    fun syncStatuses(tenant: Connection, today: LocalDate): Int {
        val settings = load(tenant)
        if (!settings.ready) return 0
        val open = reservations(tenant, "status IN ('not_confirmed', 'confirmed', 'checked_in') AND start_date <= ?") { it.setObject(1, today.plusDays(60)) }
        var changed = 0
        try {
            open.forEach { reservation ->
                val status = get(settings, "getReservation", mapOf("reservationID" to reservation.reservationId))["data"].obj()?.get("status").str()
                if (status.isEmpty() || status == reservation.status || status !in statusLabels) return@forEach
                tenant.prepareStatement(
                    "UPDATE crm_reservations SET status = ?, checked_out_at = CASE WHEN ? = 'checked_out' THEN CURRENT_TIMESTAMP END, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
                ).use { update ->
                    update.setString(1, status)
                    update.setString(2, status)
                    update.setObject(3, reservation.id)
                    update.executeUpdate()
                }
                val actor = CrmActor(null, "Cloudbeds")
                CrmRepository.addActivity(tenant, reservation.leadId, "item", "Reserva ${reservation.reservationId}: ${statusLabels[status]}.", actor)
                if (status == "checked_out" && settings.archiveOnCheckout) {
                    tenant.prepareStatement("UPDATE crm_leads SET archived_at = CURRENT_TIMESTAMP WHERE id = ? AND archived_at IS NULL").use { it.setObject(1, reservation.leadId); it.executeUpdate() }
                    CrmRepository.addActivity(tenant, reservation.leadId, "updated", "Lead archivado: el huesped hizo check-out.", actor)
                }
                changed++
            }
            recordError(tenant, "")
        } catch (failure: CrmCloudbedsException) {
            recordError(tenant, failure.message.orEmpty())
        }
        return changed
    }
}
