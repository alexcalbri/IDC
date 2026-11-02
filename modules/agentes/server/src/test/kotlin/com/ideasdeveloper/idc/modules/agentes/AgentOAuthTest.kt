package com.ideasdeveloper.idc.modules.agentes

import com.ideasdeveloper.idc.server.modules.VerifiedUser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentOAuthTest {
    private val key = ByteArray(32) { it.toByte() }

    private fun registration(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun registeredClientsRoundTripAndCannotBeForged() {
        val (client, secret) = OAuthClients.register(
            key,
            registration("""{"client_name":"Claude","redirect_uris":["https://claude.ai/api/mcp/auth_callback"],"token_endpoint_auth_method":"none"}"""),
        )
        assertNull(secret)
        val parsed = assertNotNull(OAuthClients.parse(key, client.id))
        assertEquals("Claude", parsed.name)
        assertEquals(listOf("https://claude.ai/api/mcp/auth_callback"), parsed.redirectUris)
        assertFalse(parsed.confidential)
        // Another server's key, or an edited payload, is rejected.
        assertNull(OAuthClients.parse(ByteArray(32), client.id))
        assertNull(OAuthClients.parse(key, "x" + client.id))
        assertNull(OAuthClients.parse(key, "garbage"))
    }

    @Test
    fun confidentialClientsGetAVerifiableSecret() {
        // RFC 7591 default method when the client does not send one.
        val (client, secret) = OAuthClients.register(key, registration("""{"redirect_uris":["http://localhost:3334/callback"]}"""))
        assertEquals("client_secret_basic", client.authMethod)
        assertTrue(OAuthClients.secretMatches(key, client.id, secret))
        assertFalse(OAuthClients.secretMatches(key, client.id, "wrong"))
        assertFalse(OAuthClients.secretMatches(key, client.id, null))
    }

    @Test
    fun registrationRejectsUnsafeMetadata() {
        assertFailsWith<OAuthError> { OAuthClients.register(key, registration("""{"redirect_uris":[]}""")) }
        assertFailsWith<OAuthError> { OAuthClients.register(key, registration("""{"redirect_uris":["http://evil.example/cb"]}""")) }
        assertFailsWith<OAuthError> { OAuthClients.register(key, registration("""{"redirect_uris":["https://ok.example/cb"],"grant_types":["client_credentials"]}""")) }
        assertFailsWith<OAuthError> { OAuthClients.register(key, registration("""{"redirect_uris":["https://ok.example/cb"],"token_endpoint_auth_method":"private_key_jwt"}""")) }
    }

    @Test
    fun redirectUris() {
        assertTrue(OAuthClients.validRedirectUri("https://claude.ai/api/mcp/auth_callback"))
        assertTrue(OAuthClients.validRedirectUri("http://127.0.0.1:5555/cb"))
        assertTrue(OAuthClients.validRedirectUri("http://localhost/cb"))
        assertFalse(OAuthClients.validRedirectUri("http://claude.ai/cb"))
        assertFalse(OAuthClients.validRedirectUri("https://claude.ai/cb#frag"))
        assertFalse(OAuthClients.validRedirectUri("javascript:alert(1)"))
        assertFalse(OAuthClients.validRedirectUri("myapp://callback"))
    }

    @Test
    fun pkceS256() {
        val verifier = "a".repeat(20) + "-._~" + "B".repeat(30)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertTrue(Pkce.verify(verifier, challenge))
        assertFalse(Pkce.verify(verifier + "x", challenge))
        assertFalse(Pkce.verify(null, challenge))
        assertFalse(Pkce.verify("short", challenge))
    }

    private fun grant(): OAuthGrant {
        val client = OAuthClient("id", "Claude", listOf("https://claude.ai/cb"), "none")
        val user = VerifiedUser(UUID.randomUUID(), "ana", "acme", serverOwner = false)
        return OAuthGrant(OAuthRequest(client, "https://claude.ai/cb", "s", "c".repeat(43)), user, NewTokenRequest("Claude", null, false, null))
    }

    @Test
    fun codesAreSingleUseAndExpire() {
        var now = Instant.parse("2026-11-01T00:00:00Z")
        val store = OAuthStore { now }
        val code = store.newCode(grant())
        assertNotNull(store.consumeCode(code))
        assertNull(store.consumeCode(code))

        val late = store.newCode(grant())
        now = now.plusSeconds(121)
        assertNull(store.consumeCode(late))
    }

    @Test
    fun ticketsLastTenMinutes() {
        var now = Instant.parse("2026-11-01T00:00:00Z")
        val store = OAuthStore { now }
        val grant = grant()
        val ticket = store.newTicket(OAuthTicket(grant.request, grant.user))
        now = now.plusSeconds(599)
        assertNotNull(store.ticket(ticket))
        now = now.plusSeconds(2)
        assertNull(store.ticket(ticket))
    }

    @Test
    fun metadataPointsClientsToTheEndpoints() {
        val base = "https://idc.example.com"
        val resource = AgentOAuth.protectedResourceMetadata(base)
        assertEquals("$base/modules/agentes/mcp", resource["resource"]!!.jsonPrimitive.content)
        assertEquals(base, resource["authorization_servers"]!!.jsonArray.single().jsonPrimitive.content)
        val server = AgentOAuth.authorizationServerMetadata(base)
        assertEquals(base, server["issuer"]!!.jsonPrimitive.content)
        assertEquals("$base/modules/agentes/oauth/token", server["token_endpoint"]!!.jsonPrimitive.content)
        assertEquals("$base/modules/agentes/oauth/register", server["registration_endpoint"]!!.jsonPrimitive.content)
        assertEquals(listOf("S256"), server["code_challenge_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun pagesEscapeEverything() {
        assertEquals("&lt;script&gt;&quot;x&quot;&amp;&#39;", OAuthPages.esc("<script>\"x\"&'"))
        val client = OAuthClient("id", "<b>Evil</b>", listOf("https://claude.ai/cb"), "none")
        val html = OAuthPages.signIn(OAuthRequest(client, "https://claude.ai/cb", null, "c".repeat(43)), io.ktor.http.Parameters.Empty, null, "")
        assertFalse("<b>Evil</b>" in html)
        assertTrue("&lt;b&gt;Evil&lt;/b&gt;" in html)
    }
}
