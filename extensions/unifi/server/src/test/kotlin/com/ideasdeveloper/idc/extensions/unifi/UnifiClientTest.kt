package com.ideasdeveloper.idc.extensions.unifi

import com.ideasdeveloper.idc.modules.hotspot.ConnectedGuest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiClientTest {
    @Test
    fun consolesUseTheReportedNameAndModel() {
        val page = Json.parseToJsonElement(
            """
            {"data": [
              {"id": "70A741:123", "type": "console", "reportedState": {"name": "Oficina", "hostname": "udm", "state": "connected", "hardware": {"name": "UniFi Dream Machine Pro"}}},
              {"id": "80B852:456", "type": "console", "reportedState": null},
              {"type": "console"}
            ]}
            """.trimIndent()
        ).jsonObject
        assertEquals(
            listOf(
                UnifiConsole("70A741:123", "Oficina", "UniFi Dream Machine Pro", "connected"),
                UnifiConsole("80B852:456", "80B852:456", "console", ""),
            ),
            UnifiClient.parseConsoles(page),
        )
    }

    @Test
    fun sitesKeepTheirInternalReference() {
        val page = Json.parseToJsonElement(
            """{"data": [{"id": "88f7af54-98f8-306a-a1c7-c9349722b1f6", "internalReference": "default", "name": "Default"}]}"""
        ).jsonObject
        assertEquals(
            listOf(UnifiSite("88f7af54-98f8-306a-a1c7-c9349722b1f6", "Default", "default")),
            UnifiClient.parseSites(page),
        )
    }

    @Test
    fun stationsReportTheSsidTheDeviceIsOn() {
        val page = Json.parseToJsonElement(
            """{"meta": {"rc": "ok"}, "data": [{"mac": "F2:26:E3:82:E6:F1", "essid": "guest", "is_guest": true, "authorized": false}]}"""
        ).jsonObject
        assertEquals(UnifiStation("f2:26:e3:82:e6:f1", "guest"), UnifiClient.parseStation(page))
    }

    @Test
    fun wiredStationsHaveNoSsidAndErrorsHaveNoStation() {
        val wired = Json.parseToJsonElement("""{"meta": {"rc": "ok"}, "data": [{"mac": "aa:bb:cc:dd:ee:ff", "is_wired": true}]}""").jsonObject
        assertEquals(UnifiStation("aa:bb:cc:dd:ee:ff", ""), UnifiClient.parseStation(wired))
        val unknown = Json.parseToJsonElement(
            """{"meta": {"rc": "error", "msg": "api.err.UnknownStation"}, "data": []}"""
        ).jsonObject
        assertNull(UnifiClient.parseStation(unknown))
    }

    @Test
    fun guestListsKeepOnlyGuestsWithTheirNetworkAndAuthorization() {
        val page = Json.parseToJsonElement(
            """
            {"meta": {"rc": "ok"}, "data": [
              {"mac": "F2:26:E3:82:E6:F1", "essid": "guest", "is_guest": true, "authorized": true},
              {"mac": "aa:bb:cc:dd:ee:01", "essid": "Staff", "is_guest": true, "authorized": false},
              {"mac": "aa:bb:cc:dd:ee:02", "is_guest": true, "is_wired": true, "authorized": true},
              {"mac": "aa:bb:cc:dd:ee:03", "essid": "Oficina", "is_guest": false},
              {"mac": "invalid", "essid": "guest", "is_guest": true, "authorized": true}
            ]}
            """.trimIndent()
        ).jsonObject
        assertEquals(
            listOf(
                ConnectedGuest("f2:26:e3:82:e6:f1", "guest", true),
                ConnectedGuest("aa:bb:cc:dd:ee:01", "Staff", false),
                ConnectedGuest("aa:bb:cc:dd:ee:02", "", true),
            ),
            UnifiClient.parseGuests(page),
        )
    }

    @Test
    fun wifiBroadcastsUseTheirNames() {
        val page = Json.parseToJsonElement(
            """{"offset": 0, "limit": 200, "count": 3, "totalCount": 3, "data": [
                 {"id": "a", "type": "STANDARD", "name": "KFC_WIFI", "enabled": true},
                 {"id": "b", "type": "IOT_OPTIMIZED", "name": "Sensores", "enabled": false},
                 {"id": "c", "type": "STANDARD", "name": ""}
               ]}"""
        ).jsonObject
        assertEquals(listOf("KFC_WIFI", "Sensores"), UnifiClient.parseWifiNetworks(page))
    }

    @Test
    fun networksMustMatchExactly() {
        assertTrue(UnifiClient.sameNetwork("guest", "guest"))
        assertTrue(UnifiClient.sameNetwork("", " "))
        assertFalse(UnifiClient.sameNetwork("Staff", "guest"))
        assertFalse(UnifiClient.sameNetwork("guest", "Guest"))
        assertFalse(UnifiClient.sameNetwork("", "guest"))
    }
}
