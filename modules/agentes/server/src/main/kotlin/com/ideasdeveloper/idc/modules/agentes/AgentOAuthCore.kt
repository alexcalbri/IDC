package com.ideasdeveloper.idc.modules.agentes

import com.ideasdeveloper.idc.server.modules.VerifiedUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// An OAuth error answered to the client (RFC 6749 section 5.2 / RFC 7591 section 3.2.2).
class OAuthError(val error: String, val description: String) : Exception(description)

/**
 * A client registered through Dynamic Client Registration (RFC 7591). Registration is stateless:
 * the client id carries the client's metadata signed with the server key, so nothing is stored
 * centrally before a user of some company authorizes it.
 */
data class OAuthClient(val id: String, val name: String, val redirectUris: List<String>, val authMethod: String) {
    val confidential: Boolean get() = authMethod != "none"
}

object OAuthClients {
    val AuthMethods = listOf("none", "client_secret_basic", "client_secret_post")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    @Serializable
    private data class Payload(val n: String, val r: List<String>, val a: String, val t: Long)

    /** Validates a registration request and returns the client with its secret (confidential clients only). */
    fun register(key: ByteArray, request: JsonObject, now: Instant = Instant.now()): Pair<OAuthClient, String?> {
        val uris = (request["redirect_uris"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        if (uris.isEmpty() || uris.size > 5) throw OAuthError("invalid_redirect_uri", "Envia entre 1 y 5 redirect_uris.")
        uris.firstOrNull { !validRedirectUri(it) }?.let { throw OAuthError("invalid_redirect_uri", "redirect_uri no permitida: $it") }
        val name = (request["client_name"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(100)?.ifEmpty { null } ?: "Agente IA"
        // RFC 7591 default when the client does not say.
        val method = (request["token_endpoint_auth_method"] as? JsonPrimitive)?.contentOrNull ?: "client_secret_basic"
        if (method !in AuthMethods) throw OAuthError("invalid_client_metadata", "token_endpoint_auth_method no soportado: $method")
        val grants = (request["grant_types"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        if (grants != null && grants.any { it !in setOf("authorization_code", "refresh_token") }) {
            throw OAuthError("invalid_client_metadata", "Solo se admiten authorization_code y refresh_token.")
        }
        val responses = (request["response_types"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        if (responses != null && responses.any { it != "code" }) throw OAuthError("invalid_client_metadata", "Solo se admite response_type code.")
        val payload = encoder.encodeToString(Json.encodeToString(Payload.serializer(), Payload(name, uris, method, now.epochSecond)).toByteArray())
        val id = "$payload.${sign(key, "client:$payload")}"
        val client = OAuthClient(id, name, uris, method)
        return client to if (client.confidential) secret(key, id) else null
    }

    // The client of a signed client id, or null when it was not issued by this server.
    fun parse(key: ByteArray, id: String): OAuthClient? {
        if (id.length > 4_000) return null
        val payload = id.substringBefore('.', "")
        val signature = id.substringAfter('.', "")
        if (payload.isEmpty() || !constantEquals(signature, sign(key, "client:$payload"))) return null
        val data = runCatching { Json.decodeFromString(Payload.serializer(), String(decoder.decode(payload))) }.getOrNull() ?: return null
        return OAuthClient(id, data.n, data.r, data.a)
    }

    fun secret(key: ByteArray, clientId: String): String = sign(key, "secret:$clientId")

    fun secretMatches(key: ByteArray, clientId: String, secret: String?): Boolean =
        secret != null && constantEquals(secret, secret(key, clientId))

    /** HTTPS redirects, or loopback HTTP for local clients (Claude Code, desktop apps); never fragments. */
    fun validRedirectUri(uri: String): Boolean {
        if (uri.length > 500) return false
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
        if (parsed.fragment != null || parsed.host.isNullOrEmpty()) return false
        return when (parsed.scheme?.lowercase()) {
            "https" -> true
            "http" -> parsed.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
            else -> false
        }
    }

    private fun sign(key: ByteArray, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(value.toByteArray()))
    }

    private fun constantEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

object Pkce {
    private val verifierPattern = Regex("[A-Za-z0-9._~-]{43,128}")

    // S256: BASE64URL(SHA256(verifier)) == challenge (RFC 7636).
    fun verify(verifier: String?, challenge: String): Boolean {
        if (verifier == null || !verifierPattern.matches(verifier)) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return MessageDigest.isEqual(Base64.getUrlEncoder().withoutPadding().encodeToString(digest).toByteArray(), challenge.toByteArray())
    }
}

// A validated authorization request: what the client asked for, kept while the user signs in and consents.
data class OAuthRequest(
    val client: OAuthClient,
    val redirectUri: String,
    val state: String?,
    val codeChallenge: String,
)

// A signed-in user that has not consented yet.
data class OAuthTicket(val request: OAuthRequest, val user: VerifiedUser)

// What the user approved, exchanged once for tokens.
data class OAuthGrant(val request: OAuthRequest, val user: VerifiedUser, val consent: NewTokenRequest)

/**
 * Short-lived state of the authorization flow, in memory: sign-in tickets (10 minutes) and
 * authorization codes (2 minutes, single use). A restart only cancels flows in progress.
 */
class OAuthStore(private val clock: () -> Instant = Instant::now) {
    private class Entry<T>(val value: T, val expiresAt: Instant)

    private val random = SecureRandom()
    private val tickets = ConcurrentHashMap<String, Entry<OAuthTicket>>()
    private val codes = ConcurrentHashMap<String, Entry<OAuthGrant>>()

    fun newTicket(ticket: OAuthTicket): String = put(tickets, ticket, 600)

    fun ticket(id: String): OAuthTicket? = tickets[id]?.takeIf { it.expiresAt.isAfter(clock()) }?.value

    fun dropTicket(id: String) {
        tickets.remove(id)
    }

    fun newCode(grant: OAuthGrant): String = put(codes, grant, 120)

    // Removes the code whatever happens next, so it can never be used twice.
    fun consumeCode(code: String): OAuthGrant? = codes.remove(code)?.takeIf { it.expiresAt.isAfter(clock()) }?.value

    private fun <T> put(map: ConcurrentHashMap<String, Entry<T>>, value: T, seconds: Long): String {
        val now = clock()
        if (map.size > 1_000) map.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        val bytes = ByteArray(32).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        map[id] = Entry(value, now.plusSeconds(seconds))
        return id
    }
}
