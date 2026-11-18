package com.ideasdeveloper.idc.modules.crm

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

// Tipos compartidos de la integracion con Cloudbeds. Viven en el CRM (que se queda) porque los usa codigo del CRM y la extension.

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
    // ISO code (e.g. "US") sent when the reservation does not say the guest's country; Cloudbeds requires one.
    val defaultCountry: String = "",
) {
    val ready get() = enabled && !apiKey.isNullOrBlank()

    // Nunca imprimir la API key (este objeto viaja al facade y puede acabar en logs).
    override fun toString(): String =
        "CrmCloudbedsSettings(enabled=$enabled, apiKey=${if (apiKey.isNullOrBlank()) "null" else "***"}, propertyId=$propertyId, " +
            "paymentMethod=$paymentMethod, welcomeEnabled=$welcomeEnabled, welcomeTemplateId=$welcomeTemplateId, " +
            "welcomeDaysBefore=$welcomeDaysBefore, welcomeHour=$welcomeHour, archiveOnCheckout=$archiveOnCheckout, " +
            "lastError=$lastError, defaultCountry=$defaultCountry)"
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
