package com.ideasdeveloper.idc.server.extensions.packages

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** El flag EXTENSION_UPLOAD_ENABLED apaga solo las 4 rutas de ZIP/URL; el catalogo de GitHub sigue igual. */
class ExtensionUploadGateRouteTest {
    private val store = object : ExtensionPackageStore {
        override fun find(id: String): StoredExtensionPackage? = null
        override fun all(): List<StoredExtensionPackage> = emptyList()
        override fun upsert(built: BuiltExtension) = Unit
        override fun markRemoved(id: String) = false
        override fun loadable(): List<StoredExtensionPackage> = emptyList()
        override fun markInstalled(ids: Collection<String>) = Unit
    }
    private val authorizations = object : UnsignedAuthorizationStore {
        override fun isAuthorized(packageSha256: String) = false
        override fun authorize(packageSha256: String, extensionId: String, sourceUrl: String, actor: String, acknowledgement: String) = Unit
    }

    private fun service() = ExtensionPackageService(
        store = store,
        packagesRoot = Files.createTempDirectory("ext-gate"),
        bundledIds = { emptySet() },
        catalogSource = { emptyList() },
        builder = null,
        authorizations = authorizations,
        requestRestart = {},
        audit = { _, _, _, _, _ -> },
        executor = Executor { it.run() },
    )

    private fun ApplicationTestBuilder.mount(enabled: Boolean, owner: Boolean = true) {
        application {
            install(ContentNegotiation) { json() }
            routing { extensionPackageRoutes(service(), enabled) { if (owner) "owner_role" else null } }
        }
    }

    private suspend fun HttpClient.callAll(): List<Pair<String, HttpResponse>> = listOf(
        "upload" to post("/server/extensions/upload"),
        "from-url" to post("/server/extensions/from-url") { contentType(ContentType.Application.Json); setBody("""{"id":"monday_import","url":"https://example.com/p.zip"}""") },
        "authorize" to post("/server/extensions/staged/tok/authorize-unsigned") { contentType(ContentType.Application.Json); setBody("""{"confirmationText":"autorizar x cccccccc","expectedPackageSha256":"${"c".repeat(64)}"}""") },
        "install" to post("/server/extensions/staged/tok/install"),
    )

    @Test
    fun `con el flag apagado las 4 rutas ZIP URL responden 403 FEATURE_DISABLED`() = testApplication {
        mount(enabled = false)
        client.callAll().forEach { (name, response) ->
            assertEquals(HttpStatusCode.Forbidden, response.status, name)
            val body = response.bodyAsText()
            assertTrue(body.contains("FEATURE_DISABLED"), "$name: $body")
            assertTrue(body.contains("EXTENSION_UPLOAD_ENABLED"), "$name: $body")
        }
    }

    @Test
    fun `con el flag apagado el catalogo de GitHub no se gatea`() = testApplication {
        mount(enabled = false)
        assertEquals(HttpStatusCode.OK, client.get("/server/extensions/catalog").status)
    }

    @Test
    fun `con el flag encendido las rutas ZIP URL ya no responden FEATURE_DISABLED`() = testApplication {
        mount(enabled = true)
        client.callAll().forEach { (name, response) ->
            val body = response.bodyAsText()
            assertTrue(!body.contains("FEATURE_DISABLED"), "$name: $body")
            // Sin builder o sin paquete en staging responden otro error, nunca el de la bandera.
            assertNotEquals(HttpStatusCode.Accepted, response.status, name)
        }
    }

    @Test
    fun `sin server_owner sigue respondiendo SERVER_OWNER_REQUIRED aunque el flag este apagado`() = testApplication {
        mount(enabled = false, owner = false)
        client.callAll().forEach { (name, response) ->
            assertEquals(HttpStatusCode.Forbidden, response.status, name)
            assertTrue(response.bodyAsText().contains("SERVER_OWNER_REQUIRED"), name)
        }
    }
}
