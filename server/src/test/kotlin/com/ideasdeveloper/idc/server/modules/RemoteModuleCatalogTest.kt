package com.ideasdeveloper.idc.server.modules

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteModuleCatalogTest {
    private val catalog = """{"modules":[{"id":"agentes","displayName":"Agentes IA","description":"MCP","version":"0.1.0"},""" +
        """{"id":"crm","displayName":"CRM","description":"Leads","version":"0.9.0"}]}"""

    // GitHub answers 403 once its hourly limit runs out; the screen polls while a module installs.
    @Test
    fun readingsAreCachedAndSurviveTheRateLimit() {
        val hits = AtomicInteger()
        var limited = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/catalog.json") { exchange ->
                hits.incrementAndGet()
                val (status, body) = if (limited) 403 to """{"message":"API rate limit exceeded"}""" else 200 to catalog
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        var now = 0L
        try {
            RemoteModuleCatalog(clock = { now }).use { remote ->
                val url = "http://127.0.0.1:${server.address.port}/catalog.json"
                assertEquals(listOf("agentes", "crm"), remote.modules(url).map { it.definition.id })
                repeat(20) { remote.modules(url) }
                assertEquals(1, hits.get())

                // After the cache expires, a rate-limited answer keeps the last good reading.
                limited = true
                now += 6 * 60_000L
                assertEquals(listOf("agentes", "crm"), remote.modules(url).map { it.definition.id })
                assertEquals(2, hits.get())

                // Saving the catalog URL reads it again right away.
                limited = false
                remote.forget(url)
                remote.modules(url)
                assertEquals(3, hits.get())
            }
        } finally {
            server.stop(0)
        }
    }

    // The token is a credential: only GitHub over HTTPS may receive it.
    @Test
    fun githubTokenGoesOnlyToGitHub() {
        assertEquals("Bearer t", GitHubAuth.header("t", "https://api.github.com/repos/o/r/contents/modules?ref=develop"))
        assertEquals("Bearer t", GitHubAuth.header(" t ", "https://codeload.github.com/o/r/zip/develop"))
        assertNull(GitHubAuth.header("t", "http://api.github.com/repos/o/r"))
        assertNull(GitHubAuth.header("t", "https://api.github.com.evil.example/x"))
        assertNull(GitHubAuth.header("t", "https://example.com/catalog.json"))
        assertNull(GitHubAuth.header("", "https://api.github.com/x"))
        assertNull(GitHubAuth.header(null, "https://api.github.com/x"))
    }

    @Test
    fun catalogsOutsideGitHubNeverReceiveTheToken() {
        var authorization: String? = "not called"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/catalog.json") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                val bytes = catalog.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            RemoteModuleCatalog("secret").use { it.modules("http://127.0.0.1:${server.address.port}/catalog.json") }
            assertNull(authorization)
        } finally {
            server.stop(0)
        }
    }
}
