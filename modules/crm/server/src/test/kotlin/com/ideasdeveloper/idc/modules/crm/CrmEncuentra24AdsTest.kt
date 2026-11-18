package com.ideasdeveloper.idc.modules.crm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrmEncuentra24AdsTest {
    @Test
    fun findsTheListingOfALink() {
        val ad = CrmEncuentra24Ads.fromText("Hola, me interesa https://www.encuentra24.com/nicaragua-es/bienes-raices-venta-de-propiedades-casas/casa-en-leon/29123456?q=1).")
        assertEquals("29123456", ad?.id)
        assertEquals("https://www.encuentra24.com/nicaragua-es/bienes-raices-venta-de-propiedades-casas/casa-en-leon/29123456?q=1", ad?.url)
        assertEquals("", CrmEncuentra24Ads.fromText("ver http://encuentra24.com/nicaragua-es/busqueda")?.id)
        assertEquals("29000001", CrmEncuentra24Ads.fromText("HTTPS://M.ENCUENTRA24.COM/x/29000001")?.id)
        assertNull(CrmEncuentra24Ads.fromText("lo vi en encuentra24.com"))
        assertNull(CrmEncuentra24Ads.fromText("https://notencuentra24.com.evil.io/29123456"))
    }
}
