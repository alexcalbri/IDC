package com.ideasdeveloper.idc.modules.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmMetaServerTest {
    private val routes = mapOf(
        "page" to mapOf("111" to "acme", "222" to "beta"),
        "instagram" to mapOf("333" to "acme"),
        "waba" to mapOf("444" to "beta"),
    )
    private fun companyOf(kind: String, id: String) = routes[kind]?.get(id)

    @Test
    fun splitsEntriesByTheCompanyOfTheirAsset() {
        val body = """{"object":"page","entry":[{"id":"111","messaging":[{"a":1}]},{"id":"222","messaging":[]},{"id":"999","messaging":[]},{"id":"111","messaging":[{"b":2}]}]}"""
        val parts = CrmMetaServer.split(body, ::companyOf)
        assertEquals(setOf("acme", "beta"), parts.keys)
        val acme = Json.parseToJsonElement(parts.getValue("acme")).jsonObject
        assertEquals("page", acme["object"]!!.jsonPrimitive.content)
        assertEquals(listOf("111", "111"), acme["entry"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        val whatsApp = CrmMetaServer.split("""{"object":"whatsapp_business_account","entry":[{"id":"444","changes":[]}]}""", ::companyOf)
        assertEquals(setOf("beta"), whatsApp.keys)
        assertEquals(setOf("acme"), CrmMetaServer.split("""{"object":"instagram","entry":[{"id":"333"}]}""", ::companyOf).keys)
        // Unknown objects, unknown assets and broken bodies go nowhere.
        assertTrue(CrmMetaServer.split("""{"object":"user","entry":[{"id":"111"}]}""", ::companyOf).isEmpty())
        assertTrue(CrmMetaServer.split("""{"object":"page","entry":[{"id":"x1"}]}""", ::companyOf).isEmpty())
        assertTrue(CrmMetaServer.split("not json", ::companyOf).isEmpty())
    }

    @Test
    fun routedPartsAreParsedLikeTheCompanyWebhook() {
        val body = """{"object":"page","entry":[{"id":"111","messaging":[{"sender":{"id":"5551"},"recipient":{"id":"111"},"timestamp":1730000000000,"message":{"mid":"m1","text":"Hola"}}]}]}"""
        val part = CrmMetaServer.split(body, ::companyOf).getValue("acme")
        val events = CrmMeta.parse(part, null)
        assertEquals(1, events.size)
        assertEquals("Hola", (events.single() as MetaEvent.PageMessage).text)
    }

    private fun signed(secret: String, payload: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val signature = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }.doFinal(encoded.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature) + "." + encoded
    }

    @Test
    fun signedRequestsNeedTheAppSecret() {
        val request = signed("secret", """{"algorithm":"HMAC-SHA256","user_id":"123456"}""")
        assertEquals("123456", CrmMetaServer.signedUser("secret", request))
        assertNull(CrmMetaServer.signedUser("other", request))
        assertNull(CrmMetaServer.signedUser("secret", "broken"))
        assertNull(CrmMetaServer.signedUser(null, request))
        assertNull(CrmMetaServer.signedUser("secret", signed("secret", """{"user_id":"abc"}""")))
    }

    @Test
    fun serverAppFormKeepsSecretsAndValidates() {
        val values = mapOf(
            "serverEnabled" to "true", "serverAppId" to "2104", "serverAppSecret" to "s3cret", "serverVerifyToken" to "verify-me-123",
            "serverPagesConfigId" to "77", "serverWhatsAppConfigId" to "",
        )
        val app = CrmMetaServer.mergeApp(CrmMetaServerApp(), values)
        assertTrue(app.ready)
        assertNull(app.whatsAppConfigId)
        assertEquals(CrmMail.SecretMask, CrmMetaServer.appFormValues(app)["serverAppSecret"])
        // A masked secret keeps the saved one.
        assertEquals("s3cret", CrmMetaServer.mergeApp(app, values + ("serverAppSecret" to CrmMail.SecretMask)).appSecret)
        assertFailsWith<CrmValidationException> { CrmMetaServer.mergeApp(app, values + ("serverAppId" to "12a")) }
        assertFailsWith<CrmValidationException> { CrmMetaServer.mergeApp(app, values + ("serverPagesConfigId" to "")) }
        assertFailsWith<CrmValidationException> { CrmMetaServer.mergeApp(CrmMetaServerApp(), values - "serverAppSecret") }
        // Off, nothing is required.
        assertFalse(CrmMetaServer.mergeApp(CrmMetaServerApp(), mapOf("serverEnabled" to "false")).ready)
    }

    @Test
    fun connectPageEscapesItsValues() {
        val page = CrmMetaServer.connectPage(CrmMetaServerApp(true, "2104", "s", "verify-me-123", "77", "88"), "acme", "tick</script>et")
        assertTrue(page.contains("appId: \"2104\""))
        assertTrue(page.contains("ticket: \"tick\\u003c/script\\u003eet\""))
        assertFalse(page.contains("tick</script>et"))
        // The App Secret never reaches the browser.
        assertFalse(page.contains("\"s\""))
    }
}
