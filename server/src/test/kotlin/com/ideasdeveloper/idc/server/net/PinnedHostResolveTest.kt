package com.ideasdeveloper.idc.server.net

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Solo literales de IP: no hay consultas DNS reales.
class PinnedHostResolveTest {
    @Test
    fun `una IP privada se rechaza por defecto`() {
        assertFailsWith<PinnedHostException> { PinnedHost.resolveValidated("127.0.0.1") }
        assertFailsWith<PinnedHostException> { PinnedHost.resolveValidated("127.0.0.1", allowPrivate = false) }
    }

    @Test
    fun `una IP privada se acepta con allowPrivate`() {
        assertEquals(InetAddress.getByName("127.0.0.1"), PinnedHost.resolveValidated("127.0.0.1", allowPrivate = true))
    }

    @Test
    fun `una IP publica se devuelve tal cual`() {
        assertEquals(InetAddress.getByName("8.8.8.8"), PinnedHost.resolveValidated("8.8.8.8"))
    }
}
