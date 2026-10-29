package com.ideasdeveloper.idc.modules.crm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmInboxRulesTest {
    @Test
    fun normalizesPhones() {
        assertEquals("+50588887777", CrmContacts.normalizePhone("8888-7777"))
        assertEquals("+50588887777", CrmContacts.normalizePhone("whatsapp:+505 8888 7777"))
        assertEquals("+34604123456", CrmContacts.normalizePhone("0034 604 123 456"))
        // 10 digits shaped like USA/Canada get +1 instead of being read as another country.
        assertEquals("+12025550123", CrmContacts.normalizePhone("(202) 555-0123"))
        assertEquals("+12025550123", CrmContacts.normalizePhone("+1 202 555 0123"))
        assertEquals("", CrmContacts.normalizePhone("call me"))
        assertEquals("", CrmContacts.normalizePhone("123"))
    }

    @Test
    fun fixesEmailTypos() {
        assertEquals("ana@gmail.com", CrmContacts.fixEmail("ana@gmial.com"))
        assertEquals("ana@gmail.com", CrmContacts.fixEmail("ana@gmail.co"))
        assertEquals("ana@hotmail.com", CrmContacts.fixEmail("ana@hotmail.con"))
        assertEquals("ana@yahoo.com", CrmContacts.fixEmail("ana@YAHOOO.COM"))
        assertEquals("Ana.Perez@empresa.com.ni", CrmContacts.fixEmail(" Ana.Perez@Empresa.com.ni "))
        assertEquals("no-email", CrmContacts.fixEmail("no-email"))
    }

    @Test
    fun splitsNames() {
        assertEquals("Megan" to "Dravet", CrmContacts.splitName(" Megan  Dravet "))
        assertEquals("Ana Maria" to "Lopez", CrmContacts.splitName("Ana Maria Lopez"))
        assertEquals("Isabel" to "Isabel", CrmContacts.splitName("Isabel"))
    }

    private val spam = CrmSpamSettings(listOf("seo", "casino"), 1, true, listOf("spam.ru"), setOf("form"), true)

    @Test
    fun blocksSpamWordsWithVariants() {
        assertNotNull(CrmSpam.check(spam, "We offer SEO services", ""))
        assertNotNull(CrmSpam.check(spam, "best s.e.o packages", ""))
        assertNotNull(CrmSpam.check(spam, "Play c4sino now", ""))
        assertNotNull(CrmSpam.check(spam, "casinos online", ""))
        // Whole words only.
        assertNull(CrmSpam.check(spam, "Looking for a seaside house in Leon", ""))
    }

    @Test
    fun blocksLinksCyrillicAndDomains() {
        assertNotNull(CrmSpam.check(spam, "see https://a.com and www.b.net", ""))
        assertNull(CrmSpam.check(spam, "my listing https://www.encuentra24.com/x and https://a.com", ""))
        assertNull(CrmSpam.check(spam, "write me at ana@gmail.com, see site.com", "ana@gmail.com"))
        assertNotNull(CrmSpam.check(spam, "Привет", ""))
        assertNotNull(CrmSpam.check(spam, "hola", "bot@mail.spam.ru"))
        assertNull(CrmSpam.check(spam, "Hola, quiero informacion del surf camp", "ana@gmail.com"))
    }

    @Test
    fun parsesSpamForm() {
        val parsed = CrmSpam.parse(mapOf("words" to "SEO\ncasino, seo", "maxLinks" to "2", "blockedDomains" to "@spam.ru", "channel_form" to "true", "channel_email" to "false"))
        assertEquals(listOf("seo", "casino"), parsed.words)
        assertEquals(listOf("spam.ru"), parsed.blockedDomains)
        assertEquals(setOf("form"), parsed.channels)
        assertFailsWith<CrmValidationException> { CrmSpam.parse(mapOf("maxLinks" to "x")) }
        assertFailsWith<CrmValidationException> { CrmSpam.parse(mapOf("blockedDomains" to "not a domain")) }
    }

    @Test
    fun rendersTemplatesEscapingValues() {
        val values = mapOf("nombre" to "Ana <b>", "codigo_cliente" to "K7P2QX9M")
        val html = CrmMail.render("Hola {nombre}", "<p>Hola {nombre}, tu codigo WiFi es <strong>{codigo_cliente}</strong>.</p>", values)
        assertEquals("Hola Ana <b>", html.subject)
        assertTrue(html.html.contains("Hola Ana &lt;b&gt;, tu codigo WiFi es <strong>K7P2QX9M</strong>"))
        assertEquals("Hola Ana <b>, tu codigo WiFi es K7P2QX9M.", html.text)

        val text = CrmMail.render("Bienvenida", "Hola {nombre}\n\nCodigo: {codigo_cliente}", values)
        assertTrue(text.html.contains("<p>Hola Ana &lt;b&gt;</p><p>Codigo: K7P2QX9M</p>"))
        assertEquals(setOf("precio"), CrmMail.unknownPlaceholders("{nombre} {precio}"))
    }

    @Test
    fun cutsQuotedReplies() {
        val reply = "Si, me interesa.\n\nEl lun, 3 nov 2026 a las 10:00, Magrock escribio:\n> Hola Ana"
        assertEquals("Si, me interesa.", CrmMail.withoutQuote(reply))
        assertFalse(CrmMail.withoutQuote("Solo texto").isEmpty())
    }
}
