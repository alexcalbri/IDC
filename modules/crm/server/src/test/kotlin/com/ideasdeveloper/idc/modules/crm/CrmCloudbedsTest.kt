package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CrmCloudbedsTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun keepsOnlyFreeRoomsAndTheCheapestRatePerType() {
        val rooms = CrmCloudbeds.parseRooms(
            json(
                """
                {"success":true,"data":[{"propertyID":"1","rooms":[
                  {"roomID":"101","roomName":"Hab 1","roomTypeID":"10","roomTypeName":"Doble","maxGuests":3,"roomBlocked":false},
                  {"roomID":"102","roomName":"Hab 2","roomTypeID":"10","roomTypeName":"Doble","maxGuests":3,"roomBlocked":true},
                  {"roomID":"201","roomName":"Dorm","roomTypeID":"20","roomTypeName":"Compartida","maxGuests":8,"isVirtual":true}
                ]}]}
                """.trimIndent()
            )
        )
        assertEquals(listOf("101"), rooms.map { it.roomId })
        assertEquals(3, rooms.single().maxGuests)

        val rates = CrmCloudbeds.parseRates(
            json(
                """
                {"success":true,"data":[
                  {"rateID":"r1","roomTypeID":"10","roomRate":100,"totalRate":240,"roomsAvailable":2},
                  {"rateID":"r2","roomTypeID":"10","roomRate":90,"totalRate":200.5,"roomsAvailable":1},
                  {"rateID":"r3","roomTypeID":"30","roomRate":50,"totalRate":50,"roomsAvailable":0}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(mapOf("10" to (BigDecimal("200.5") to "r2")), rates)
    }

    @Test
    fun buildsTheReservationFormWithIndexedRooms() {
        val settings = CrmCloudbedsSettings(true, "key", "123", "cash", false, null, 1, 9, true, "")
        val lead = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val form = CrmCloudbeds.reservationForm(
            settings, lead, LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 27),
            CbGuest("Megan", "Dravet", "megan@example.com", "+16304602072", "US", ""),
            listOf(CbRoomRequest("101", "10", 2, 0), CbRoomRequest("305", "30", 1, 2)),
        ).toMap()
        assertEquals("123", form["propertyID"])
        assertEquals("2026-12-20", form["startDate"])
        assertEquals("false", form["sendEmailConfirmation"])
        assertEquals("idc-$lead", form["thirdPartyIdentifier"])
        assertEquals("cash", form["paymentMethod"])
        assertEquals("305", form["rooms[1][roomID]"])
        assertEquals("30", form["adults[1][roomTypeID]"])
        assertEquals("2", form["children[1][quantity]"])
        assertEquals("1", form["rooms[0][quantity]"])
        assertEquals("+16304602072", form["guestPhone"])
    }

    @Test
    fun readsTheReservationAnswer() {
        val result = CrmCloudbeds.parseReservation(json("""{"success":true,"reservationID":"8899","status":"confirmed","grandTotal":1240.5,"unassigned":[{"roomTypeID":"10"}]}"""))
        assertEquals("8899", result.reservationId)
        assertEquals(BigDecimal("1240.5"), result.total)
        assertEquals(1, result.unassigned)
        assertFailsWith<CrmCloudbedsException> { CrmCloudbeds.parseReservation(json("""{"success":true}""")) }
    }

    @Test
    fun stayUsesTheLeadDatesByDefault() {
        val today = LocalDate.of(2026, 10, 28)
        val lead = CrmLead(
            UUID.randomUUID(), UUID.randomUUID(), "Ana", "", "", UUID.randomUUID(), null, null, "", "", "", "",
            LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6), null, null, Instant.now(), null, Instant.now(),
        )
        val stay = CrmCloudbedsScreens.stay(lead, emptyMap(), today)
        assertEquals(LocalDate.of(2026, 11, 2), stay.start)
        assertEquals(LocalDate.of(2026, 11, 6), stay.end)
        assertEquals(2, stay.adults)
        val asked = CrmCloudbedsScreens.stay(lead, mapOf("start" to "2026-12-01", "end" to "", "adults" to "4", "children" to "1"), today)
        assertEquals(LocalDate.of(2026, 12, 3), asked.end)
        assertEquals(4 to 1, asked.adults to asked.children)
        assertFailsWith<CrmValidationException> { CrmCloudbedsScreens.stay(lead, mapOf("start" to "01/12/2026"), today) }
    }
}
