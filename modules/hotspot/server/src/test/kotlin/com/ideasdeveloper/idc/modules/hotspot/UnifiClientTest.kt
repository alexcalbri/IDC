package com.ideasdeveloper.idc.modules.hotspot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
