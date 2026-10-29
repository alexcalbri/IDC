package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmWordPressTest {
    private val secret = "SecretSecretSecretSecretSecretSecret12"

    @Test
    fun signatureMatchesThePhpPlugin() {
        // hash_hmac('sha256', '1761700000.{"event":"ping"}', secret) computed with PHP 8.2.
        val php = "7cc732526fee9fc5bc6095b82cf66bd3d71f2f468ef9421474876d5abf3519c6"
        val body = """{"event":"ping"}""".toByteArray()
        assertEquals(php, CrmWordPress.sign(secret, "1761700000", body))
        val now = Instant.ofEpochSecond(1761700100)
        assertTrue(CrmWordPress.validSignature(secret, "1761700000", php.uppercase(), body, now))
        // Older than 5 minutes, another secret, a changed body or missing headers are rejected.
        assertFalse(CrmWordPress.validSignature(secret, "1761700000", php, body, now.plusSeconds(400)))
        assertFalse(CrmWordPress.validSignature("other$secret", "1761700000", php, body, now))
        assertFalse(CrmWordPress.validSignature(secret, "1761700000", php, """{"event":"form"}""".toByteArray(), now))
        assertFalse(CrmWordPress.validSignature(secret, null, php, body, now))
        assertFalse(CrmWordPress.validSignature(null, "1761700000", php, body, now))
    }

    @Test
    fun readsListingsSentByThePlugin() {
        val element = Json.parseToJsonElement(
            """
            {"id":42,"title":"Casa Las Colinas","url":"https://site.com/listings/casa","price":"250000","price_prefix":"US$",
             "price_postfix":"","mls":"MLS-9","sqft":"180","taxonomies":{"property_type":["Casa"],"ct_status":["For Sale"],
             "beds":["3"],"baths":["2"],"city":["Managua"]},"agent":{"id":7,"name":"Ana","email":"ana@site.com"}}
            """.trimIndent()
        )
        val listing = CrmWordPress.listing(element)!!
        assertEquals(42L, listing.id)
        assertEquals("US$ 250000", listing.priceText)
        assertEquals("250000".toBigDecimal(), listing.priceValue)
        assertEquals("Casa - For Sale - 3 hab. - 2 banos - 180 m2 - Managua - MLS MLS-9", listing.summary)
        assertEquals(7L, listing.agentId)
        assertNull(CrmWordPress.listing(Json.parseToJsonElement("""{"title":"sin id"}""")))
    }

    @Test
    fun pluginZipHasTheWordPressLayout() {
        val zip = CrmPlugin.zip(CrmPlugin::class.java.classLoader)
        val names = ZipInputStream(zip.inputStream()).use { input -> generateSequence { input.nextEntry?.name }.toList() }
        assertEquals(listOf("idc-connector/idc-connector.php", "idc-connector/readme.txt"), names)
        val php = CrmPlugin::class.java.classLoader.getResourceAsStream("modules/crm/wordpress/idc-connector/idc-connector.php")!!.use { it.readBytes().decodeToString() }
        assertTrue(php.contains("Version: ${CrmWordPress.PluginVersion}"), "Plugin header and CrmWordPress.PluginVersion must match")
    }
}
