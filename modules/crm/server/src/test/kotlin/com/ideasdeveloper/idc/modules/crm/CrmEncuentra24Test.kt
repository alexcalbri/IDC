package com.ideasdeveloper.idc.modules.crm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmEncuentra24Test {
    @Test
    fun parsesPaginatedPagesOldestFirst() {
        val body = """
            {"data":[
              {"id":"905","origin":"E24_CHAT","title":"Casa en Leon","adid":"29123456","preview":"Sigue disponible?",
               "chat":"https://www.encuentra24.com/chat/1","created_at":"2026-10-27 14:05:00",
               "contact":{"name":"Luis","email":"luis@gmial.com","phone":"88887777"}},
              {"id":"901","origin":"PHONECALL","title":"Lote","adid":"29000001","contact":{"phone":"+50588880000"}}
            ],"pagination":{"nextid":905}}
        """.trimIndent()
        val (leads, next) = CrmEncuentra24.parsePage(body)
        assertEquals(listOf("901", "905"), leads.map { it.id })
        assertEquals(905L, next)
        val chat = leads[1]
        assertEquals("Luis", chat.name)
        assertEquals("Sigue disponible?", chat.message)
        assertEquals(1793109900L, chat.createdAt?.epochSecond)
        assertEquals(
            "[Encuentra24 - Chat] Casa en Leon (anuncio 29123456)\nSigue disponible?\nChat en Encuentra24: https://www.encuentra24.com/chat/1",
            CrmEncuentra24.messageText(chat),
        )
        assertEquals("[Encuentra24 - Llamada] Lote (anuncio 29000001)\nLlamada desde el anuncio.", CrmEncuentra24.messageText(leads[0]))
    }

    @Test
    fun findsTheListingOfALink() {
        val ad = CrmEncuentra24.adFromText("Hola, me interesa https://www.encuentra24.com/nicaragua-es/bienes-raices-venta-de-propiedades-casas/casa-en-leon/29123456?q=1).")
        assertEquals("29123456", ad?.id)
        assertEquals("https://www.encuentra24.com/nicaragua-es/bienes-raices-venta-de-propiedades-casas/casa-en-leon/29123456?q=1", ad?.url)
        assertEquals("", CrmEncuentra24.adFromText("ver http://encuentra24.com/nicaragua-es/busqueda")?.id)
        assertEquals("29000001", CrmEncuentra24.adFromText("HTTPS://M.ENCUENTRA24.COM/x/29000001")?.id)
        assertNull(CrmEncuentra24.adFromText("lo vi en encuentra24.com"))
        assertNull(CrmEncuentra24.adFromText("https://notencuentra24.com.evil.io/29123456"))
    }

    @Test
    fun acceptsSimpleListsAndRejectsGarbage() {
        val (leads, next) = CrmEncuentra24.parsePage("""[{"id":"1","origin":"E24_MESSAGE","message":"<p>Hola</p>","contact":{"email":"a@b.com"}}]""")
        assertEquals(0L, next)
        assertEquals("[Encuentra24 - Mensaje]\nHola", CrmEncuentra24.messageText(leads.single()))
        assertTrue(CrmEncuentra24.parsePage("""{"data":[{"origin":"x"}]}""").first.isEmpty())
        assertFailsWith<CrmEncuentra24Exception> { CrmEncuentra24.parsePage("<html>") }
    }
}
