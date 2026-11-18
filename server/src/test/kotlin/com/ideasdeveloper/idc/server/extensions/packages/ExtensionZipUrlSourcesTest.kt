package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionSignature
import com.ideasdeveloper.idc.server.extensions.ExtensionSignatureFile
import com.ideasdeveloper.idc.server.extensions.ExtensionSource
import com.ideasdeveloper.idc.server.net.PinnedResponse
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Paquetes instalados desde un ZIP subido o una URL: manifiesto, extraccion segura, verify y descarga anclada. */
class ExtensionZipUrlSourcesTest {
    private val json = Json { encodeDefaults = true }
    private val id = "monday_import"
    private val providerPath = "server/src/main/kotlin/com/ideasdeveloper/idc/extensions/$id/MondayImportServerExtensionProvider.kt"

    private class Keys {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicBase64: String = Base64.getEncoder().encodeToString(pair.public.encoded)

        fun sign(hashHex: String): String {
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(pair.private)
            signer.update(HexFormat.of().parseHex(hashHex))
            return Base64.getEncoder().encodeToString(signer.sign())
        }
    }

    private fun manifestJson(module: String = "crm", sourceType: String = "zip") = json.encodeToString(
        ExtensionManifest.serializer(),
        ExtensionManifest(id = id, module = module, displayName = "Monday", version = "0.1.0", source = ExtensionSource(sourceType, null)),
    )

    private fun content(module: String = "crm"): Map<String, String> = linkedMapOf(
        "extension.json" to manifestJson(module),
        providerPath to "class MondayImportServerExtensionProvider",
    )

    private fun signature(files: Map<String, String>, keys: Keys): String {
        val hash = ExtensionTreeHash.ofEntries(files.mapValues { it.value.toByteArray() })
        return json.encodeToString(ExtensionSignatureFile.serializer(), ExtensionSignatureFile(hash, ExtensionSignature("ideascore-2026", keys.sign(hash))))
    }

    private fun zip(entries: Map<String, String>): Path {
        val file = Files.createTempFile("ext-zip", ".zip")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun builder(keys: Keys? = null, authorized: Set<String> = emptySet()): ExtensionPackageBuilder {
        val root = Files.createTempDirectory("ext-root")
        val authorizations = object : UnsignedAuthorizationStore {
            override fun isAuthorized(packageSha256: String) = packageSha256 in authorized
            override fun authorize(packageSha256: String, extensionId: String, sourceUrl: String, actor: String, acknowledgement: String) = Unit
        }
        return ExtensionPackageBuilder(
            packagesRoot = root, compilerDir = root.resolve("compiler"), compileClasspath = "", javaExecutable = "java",
            validator = ExtensionManifestValidator({ if (it == "crm") "0.24.0" else null }, { emptyList() }),
            verifier = ExtensionSignatureVerifier(mapOf("ideascore-2026" to (keys?.publicBase64 ?: ""))),
            authorizations = authorizations, moduleJar = { null },
        )
    }

    private fun assertRejected(fragment: String, block: () -> Unit) {
        val failure = assertFailsWith<ExtensionPackageException> { block() }
        assertTrue(failure.message.orEmpty().contains(fragment), "esperaba '$fragment' en '${failure.message}'")
    }

    // --- Manifiesto ---

    private val validator = ExtensionManifestValidator({ "0.24.0" }, { emptyList() })

    private fun problems(type: String, url: String?) = validator.validate(
        ExtensionManifest(id = id, module = "crm", displayName = "X", version = "0.1.0", source = ExtensionSource(type, url)),
        id, Files.createTempDirectory("ext-val"),
    )

    @Test
    fun `zip and url sources do not need a github url but unknown types are rejected`() {
        assertEquals(emptyList(), problems("zip", null))
        assertEquals(emptyList(), problems("url", "https://example.com/paquete.zip"))
        assertTrue(problems("docker", null).any { it.contains("source.type") })
        assertTrue(problems("github", "https://github.com/acme/idc/tree/develop/extensions/$id").any { it.contains("una rama no se acepta") })
    }

    // --- Extraccion generica segura ---

    @Test
    fun `the extension folder is found at the root in extensions id or inside a repo root folder`() {
        val layouts = listOf("", "extensions/$id/", "$id/", "idc-${"a".repeat(40)}/extensions/$id/")
        val hashes = layouts.map { prefix ->
            val archive = zip(content().mapKeys { prefix + it.key })
            val extracted = ExtensionPackageSafety.extractGeneric(archive, id, Files.createTempDirectory("ext-out"))
            assertTrue(Files.isRegularFile(extracted.resolve("extension.json")), "layout '$prefix'")
            assertTrue(Files.isRegularFile(extracted.resolve(providerPath)), "layout '$prefix'")
            ExtensionTreeHash.compute(extracted)
        }
        assertEquals(1, hashes.distinct().size)
    }

    @Test
    fun `zip slip entries are rejected and nothing is written outside the target`() {
        val parent = Files.createTempDirectory("ext-slip")
        val target = parent.resolve("out")
        listOf("../evil.txt", "a/../../evil.txt", "/etc/evil.txt", "a\\..\\evil.txt").forEach { name ->
            val archive = zip(content() + (name to "x"))
            assertRejected("ruta no permitida") { ExtensionPackageSafety.extractGeneric(archive, id, target) }
        }
        assertFalse(Files.exists(parent.resolve("evil.txt")))
    }

    @Test
    fun `size entry count and structure limits apply`() {
        val big = zip(content() + ("server/resources/big.txt" to "x".repeat(2000)))
        assertRejected("demasiado grande") { ExtensionPackageSafety.extractGeneric(big, id, Files.createTempDirectory("o"), maxEntryBytes = 1000) }
        assertRejected("demasiado grande") { ExtensionPackageSafety.extractGeneric(big, id, Files.createTempDirectory("o"), maxTotalBytes = 1500) }
        assertRejected("demasiados archivos") { ExtensionPackageSafety.extractGeneric(big, id, Files.createTempDirectory("o"), maxEntries = 2) }
        assertRejected("no contiene el extension.json") { ExtensionPackageSafety.extractGeneric(zip(mapOf("a.txt" to "x")), id, Files.createTempDirectory("o")) }
        val ambiguous = zip(mapOf("uno/extension.json" to "{}", "dos/extension.json" to "{}"))
        assertRejected("ambiguo") { ExtensionPackageSafety.extractGeneric(ambiguous, id, Files.createTempDirectory("o")) }
    }

    @Test
    fun `a file that is not a zip is rejected with a clear message`() {
        val notZip = Files.createTempFile("ext-bad", ".zip").also { Files.writeString(it, "esto no es un zip") }
        assertRejected("no es un ZIP valido") { builder().inspectZip(id, notZip) }
    }

    // --- verify: firma o autorizacion por hash ---

    @Test
    fun `a signed zip is inspected as signed and tampering is rejected`() {
        val keys = Keys()
        val files = content()
        val signed = zip(files + ("extension.sig.json" to signature(files, keys)))
        val inspected = builder(keys).inspectZip(id, signed)
        assertTrue(inspected.signed)
        assertEquals("zip:" + signed.fileName, inspected.sourceUrl)
        assertEquals(null, inspected.signatureProblem)

        // El contenido cambia despues de firmar: ya no esta firmado y compilar falla.
        val tampered = zip(files.mapValues { it.value + " " } + ("extension.sig.json" to signature(files, keys)))
        assertFalse(builder(keys).inspectZip(id, tampered).signed)
        assertRejected("no coincide") { builder(keys).buildFromZip(id, tampered, {}) }
    }

    @Test
    fun `an unsigned zip is refused until its hash is authorized`() {
        val archive = zip(content(module = "hotel"))
        val hash = builder().inspectZip(id, archive).also { assertFalse(it.signed) }.packageSha256
        assertRejected("no esta firmada") { builder().buildFromZip(id, archive, {}) }
        assertRejected(hash) { builder().buildFromZip(id, archive, {}) }
        // Con el hash autorizado pasa verify y llega a la validacion del manifiesto (aqui falla porque 'hotel' no esta instalado).
        assertRejected("no cumple las reglas") { builder(authorized = setOf(hash)).buildFromZip(id, archive, {}) }
    }

    @Test
    fun `zip paths cannot smuggle files past the hash`() {
        val archive = zip(content() + ("../evil.kt" to "x"))
        assertRejected("ruta no permitida") { builder().buildFromZip(id, archive, {}) }
    }

    @Test
    fun `zip label keeps only safe characters`() {
        assertEquals("zip:paquete.zip", ExtensionPackageBuilder.zipLabel(null))
        assertEquals("zip:mi_pkg.zip", ExtensionPackageBuilder.zipLabel("C:\\temp\\mi pkg.zip"))
        assertEquals("zip:evil.zip", ExtensionPackageBuilder.zipLabel("../../evil.zip"))
        assertTrue(ExtensionPackageBuilder.zipLabel("a".repeat(300)).length <= 84)
    }

    // --- Descarga por URL anclada anti-SSRF ---

    @Test
    fun `urls must be https without credentials and on the default port`() {
        listOf("http://example.com/p.zip", "ftp://example.com/p.zip", "https://user:pass@example.com/p.zip", "https://example.com:8443/p.zip", "https:///p.zip", "no es url", "").forEach {
            assertFailsWith<ExtensionPackageException>(it) { ExtensionUrlDownloader.validate(it) }
        }
        assertEquals("example.com", ExtensionUrlDownloader.validate("https://example.com/p.zip").host)
    }

    @Test
    fun `internal hosts are rejected by the pinned resolver without any network access`() {
        val target = Files.createTempDirectory("ext-dl").resolve("p.zip")
        listOf("https://127.0.0.1/p.zip", "https://10.0.0.5/p.zip", "https://192.168.1.10/p.zip", "https://169.254.169.254/latest", "https://[::1]/p.zip", "https://localhost/p.zip").forEach { url ->
            assertFailsWith<ExtensionPackageException>(url) { ExtensionUrlDownloader.download(url, target) }
            assertFalse(Files.exists(target))
        }
    }

    private val publicAddress = InetAddress.getByAddress("example.com", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))

    private fun response(status: Int, body: String = "", vararg headers: Pair<String, String>) = PinnedResponse(
        status, headers.associate { it.first to listOf(it.second) }, ByteArrayInputStream(body.toByteArray()), Closeable { },
    )

    @Test
    fun `a redirect is validated again and cannot go to http or to an internal host`() {
        val target = Files.createTempDirectory("ext-dl").resolve("p.zip")
        val resolver: (String) -> InetAddress = { host ->
            if (host == "interno.example") throw com.ideasdeveloper.idc.server.net.PinnedHostException("El servidor apunta a una red privada o local: no se permite.")
            publicAddress
        }
        assertRejected("https") {
            ExtensionUrlDownloader.download("https://example.com/p.zip", target, resolve = resolver, fetch = { _, _ -> response(302, "", "location" to "http://example.com/p.zip") })
        }
        assertRejected("red privada") {
            ExtensionUrlDownloader.download("https://example.com/p.zip", target, resolve = resolver, fetch = { _, _ -> response(302, "", "location" to "https://interno.example/p.zip") })
        }
        assertRejected("Demasiadas redirecciones") {
            ExtensionUrlDownloader.download("https://example.com/p.zip", target, resolve = resolver, fetch = { _, _ -> response(302, "", "location" to "/otra") })
        }
        assertFalse(Files.exists(target))
    }

    @Test
    fun `a download follows a safe redirect and enforces the size limit and the status`() {
        val target = Files.createTempDirectory("ext-dl").resolve("p.zip")
        var calls = 0
        val saved = ExtensionUrlDownloader.download(
            "https://example.com/p.zip", target, resolve = { publicAddress },
            fetch = { uri, _ -> if (calls++ == 0) response(301, "", "location" to "/final.zip") else { assertEquals("/final.zip", uri.path); response(200, "contenido") } },
        )
        assertEquals("contenido", Files.readString(saved))

        assertRejected("demasiado grande") {
            ExtensionUrlDownloader.download("https://example.com/p.zip", target, maxBytes = 5, resolve = { publicAddress }, fetch = { _, _ -> response(200, "0123456789", "content-length" to "10") })
        }
        assertRejected("HTTP 404") {
            ExtensionUrlDownloader.download("https://example.com/p.zip", target, resolve = { publicAddress }, fetch = { _, _ -> response(404) })
        }
    }
}
