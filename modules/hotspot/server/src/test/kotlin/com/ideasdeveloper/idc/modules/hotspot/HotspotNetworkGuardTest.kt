package com.ideasdeveloper.idc.modules.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals

class HotspotNetworkGuardTest {
    @Test
    fun guestsOnAnotherNetworkThanTheirAccessAreCaught() {
        val onCustomers = ConnectedGuest("aa:bb:cc:dd:ee:01", "Clientes", true)
        val movedToStaff = ConnectedGuest("aa:bb:cc:dd:ee:02", "Staff", true)
        val unknown = ConnectedGuest("aa:bb:cc:dd:ee:03", "Staff", true)
        val lastSsids = mapOf("aa:bb:cc:dd:ee:01" to "Clientes", "aa:bb:cc:dd:ee:02" to "Clientes")
        assertEquals(
            listOf(movedToStaff to "Clientes"),
            HotspotNetworkGuard.mismatches(listOf(onCustomers, movedToStaff, unknown), lastSsids),
        )
    }

    @Test
    fun theSsidIsComparedExactlyIgnoringOnlySurroundingSpaces() {
        val sameTrimmed = ConnectedGuest("aa:bb:cc:dd:ee:01", " Clientes ", true)
        val differentCase = ConnectedGuest("aa:bb:cc:dd:ee:02", "clientes", true)
        val lastSsids = mapOf("aa:bb:cc:dd:ee:01" to "Clientes", "aa:bb:cc:dd:ee:02" to "Clientes")
        assertEquals(
            listOf(differentCase to "Clientes"),
            HotspotNetworkGuard.mismatches(listOf(sameTrimmed, differentCase), lastSsids),
        )
    }
}
