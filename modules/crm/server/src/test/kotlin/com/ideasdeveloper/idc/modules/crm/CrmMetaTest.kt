package com.ideasdeveloper.idc.modules.crm

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmMetaTest {
    private val settings = CrmMetaSettings(true, "secret", "verify-me-123", "token", null, null, null, "17841400000000000", null, "1234567890", true)

    @Test
    fun parsesWhatsAppMessagesAndEchoes() {
        val body = """
            {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[
              {"field":"messages","value":{"metadata":{"phone_number_id":"1234567890"},
                "contacts":[{"wa_id":"50588887777","profile":{"name":"Ana"}}],
                "messages":[
                  {"from":"50588887777","id":"wamid.A","timestamp":"1761700000","type":"text","text":{"body":"Hola, info del surf camp"}},
                  {"from":"50588887777","id":"wamid.B","type":"image","image":{"caption":"mi pasaporte"}},
                  {"from":"50588887777","id":"wamid.C","type":"reaction","reaction":{"emoji":"x"}}
                ]}},
              {"field":"smb_message_echoes","value":{"metadata":{"phone_number_id":"1234567890"},
                "message_echoes":[{"to":"50588887777","id":"wamid.D","type":"text","text":{"body":"Te enviamos los precios"}}]}}
            ]}]}
        """.trimIndent()
        val events = CrmMeta.parse(body, null)
        assertEquals(3, events.size)
        val first = events[0] as MetaEvent.WhatsAppMessage
        assertEquals("50588887777", first.from)
        assertEquals("Ana", first.name)
        assertEquals("Hola, info del surf camp", first.text)
        assertEquals(1761700000L, first.at?.epochSecond)
        assertEquals("[Imagen de WhatsApp] mi pasaporte", (events[1] as MetaEvent.WhatsAppMessage).text)
        assertEquals("Te enviamos los precios", (events[2] as MetaEvent.WhatsAppEcho).text)
    }

    @Test
    fun parsesMessengerAndInstagramSkippingEchoes() {
        val messenger = """
            {"object":"page","entry":[{"id":"9","time":1,
              "messaging":[
                {"sender":{"id":"111"},"recipient":{"id":"9"},"timestamp":1761700000000,"message":{"mid":"m1","text":"Hola"}},
                {"sender":{"id":"9"},"recipient":{"id":"111"},"message":{"mid":"m2","text":"eco","is_echo":true}}
              ],
              "standby":[{"sender":{"id":"222"},"message":{"mid":"m3","attachments":[{"type":"image"}]}}]
            }]}
        """.trimIndent()
        val events = CrmMeta.parse(messenger, null).map { it as MetaEvent.PageMessage }
        assertEquals(listOf("111", "222"), events.map { it.senderId })
        assertEquals("[Adjunto de Messenger: image]", events[1].text)
        assertEquals(1761700000L, events[0].at?.epochSecond)

        val instagram = """
            {"object":"instagram","entry":[{"messaging":[
              {"sender":{"id":"333"},"message":{"mid":"i1","text":"precio?","reply_to":{"story":{"id":"s"}}}},
              {"sender":{"id":"17841400000000000"},"message":{"mid":"i2","text":"nuestra respuesta"}}
            ]}]}
        """.trimIndent()
        val ig = CrmMeta.parse(instagram, settings.instagramAccountId).map { it as MetaEvent.PageMessage }
        assertEquals(1, ig.size)
        assertEquals("instagram", ig[0].platform)
        assertEquals("[Respuesta a una historia de Instagram] precio?", ig[0].text)
    }

    @Test
    fun acceptsTheTestButtonFormatAndIgnoresGarbage() {
        val test = """{"field":"messages","value":{"sender":{"id":"444"},"message":{"mid":"t","text":"test"}}}"""
        assertEquals("444", (CrmMeta.parse(test, null).single() as MetaEvent.PageMessage).senderId)
        assertTrue(CrmMeta.parse("not json", null).isEmpty())
        assertTrue(CrmMeta.parse("""{"object":"user"}""", null).isEmpty())
    }

    @Test
    fun checksSignatureAndVerification() {
        val body = """{"object":"page"}""".toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(), "HmacSHA256")) }
        val signature = "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
        assertTrue(CrmMeta.validSignature("secret", signature, body))
        assertFalse(CrmMeta.validSignature("other", signature, body))
        assertFalse(CrmMeta.validSignature("secret", null, body))
        assertFalse(CrmMeta.validSignature(null, signature, body))

        assertEquals("abc123", CrmMeta.verify(settings, "subscribe", "verify-me-123", "abc123"))
        assertNull(CrmMeta.verify(settings, "subscribe", "wrong", "abc123"))
        assertNull(CrmMeta.verify(settings, "subscribe", "verify-me-123", "<script>"))
    }

    @Test
    fun splitsLongTextsAndDetectsEncuentra24() {
        val text = ("palabra ".repeat(300)).trim()
        val parts = CrmMeta.split(text, 1000)
        assertTrue(parts.all { it.length <= 1000 })
        assertEquals(text, parts.joinToString(" "))
        assertEquals("Encuentra24 (WhatsApp)", CrmMeta.source("Hola, vi https://www.encuentra24.com/nicaragua-es/x", "WhatsApp"))
        assertEquals("WhatsApp", CrmMeta.source("Hola", "WhatsApp"))
    }

    @Test
    fun mergeKeepsSecretsAndRequiresThemToEnable() {
        val current = settings.copy(pageToken = "page")
        val merged = CrmMeta.merge(current, mapOf("enabled" to "true", "appSecret" to CrmMail.SecretMask, "verifyToken" to "verify-me-123", "systemToken" to "", "recordEchoes" to "true"))
        assertEquals("secret", merged.appSecret)
        assertEquals("token", merged.systemToken)
        assertEquals("page", merged.pageToken)
        // A new token drops the page token so the page is connected again.
        assertNull(CrmMeta.merge(current, mapOf("enabled" to "true", "verifyToken" to "verify-me-123", "systemToken" to "new")).pageToken)
        assertFailsWith<CrmValidationException> {
            CrmMeta.merge(settings.copy(appSecret = null), mapOf("enabled" to "true", "verifyToken" to "verify-me-123"))
        }
        assertFailsWith<CrmValidationException> { CrmMeta.merge(settings, mapOf("wabaId" to "12ab")) }
    }
}
