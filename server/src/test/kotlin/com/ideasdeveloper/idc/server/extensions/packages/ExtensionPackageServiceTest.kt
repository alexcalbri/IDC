package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionSource
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.GitHubLocation
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionPackageServiceTest {
    private val commit = "b".repeat(40)
    private val sha = "c".repeat(64)

    // Confirmacion valida para el flujo ZIP/URL: incluye los primeros 8 hex del hash, como manda la app.
    private val confirmation = "autorizar monday_import ${"c".repeat(8)}"

    private fun manifest(id: String = "monday_import") = ExtensionManifest(
        id = id, module = "crm", displayName = "Monday", version = "0.1.0",
        source = ExtensionSource("github", "https://github.com/acme/idc/tree/${"a".repeat(40)}/extensions/$id"),
    )

    private fun remote(id: String = "monday_import", signed: Boolean = true) = RemoteExtensionPackage(
        manifest = manifest(id), source = GitHubLocation("acme", "idc", commit, "extensions"),
        packageSha256 = sha, signed = signed, keyId = if (signed) "ideascore-2026" else null,
    )

    private class FakeStore : ExtensionPackageStore {
        val rows = linkedMapOf<String, StoredExtensionPackage>()
        override fun find(id: String) = rows[id]
        override fun all() = rows.values.toList()
        override fun upsert(built: BuiltExtension) {
            rows[built.manifest.id] = StoredExtensionPackage(
                built.manifest.id, built.manifest.module, built.manifest.version, built.sourceType, "pending_restart",
                built.jar.toString(), built.packageSha256, built.signed,
            )
        }
        override fun markRemoved(id: String): Boolean {
            val row = rows[id] ?: return false
            rows[id] = row.copy(status = "removed")
            return true
        }
        override fun loadable() = rows.values.filter { it.sourceType in setOf("github", "zip", "url") && it.status in setOf("installed", "pending_restart") }
        override fun markInstalled(ids: Collection<String>) {
            ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(status = "installed") } }
        }
    }

    private class FakeBuilder(
        var failure: String? = null,
        var signed: Boolean = false,
        var version: String = "0.1.0",
        var jar: java.nio.file.Path = java.nio.file.Path.of("x.jar"),
        var inspectedSha: String = "c".repeat(64),
        var signatureProblem: String? = null,
    ) : ExtensionPackageBuilding {
        override fun build(extensionId: String, source: GitHubLocation, progress: (String) -> Unit): BuiltExtension {
            failure?.let { throw ExtensionPackageException(it) }
            progress("Compilando")
            return BuiltExtension(
                jar = jar,
                manifest = ExtensionManifest(id = extensionId, module = "crm", displayName = "M", version = version),
                packageSha256 = "c".repeat(64), signed = true, keyId = "ideascore-2026", sourceUrl = "https://github.com/acme/idc/tree/${source.ref}/extensions/$extensionId",
                jarSha256 = "d".repeat(64),
            )
        }
        override fun inspect(extensionId: String, source: GitHubLocation) =
            InspectedPackage(inspectedSha, signed, "https://github.com/acme/idc/tree/${source.ref}/extensions/$extensionId", source.ref, signatureProblem = signatureProblem)

        // ZIP y URL: igual que el builder real, sin firma solo sigue si el hash esta autorizado.
        var isAuthorized: (String) -> Boolean = { false }
        var downloadFailure: String? = null
        val downloaded = mutableListOf<String>()
        val builtFrom = mutableListOf<List<String>>()

        override fun inspectZip(extensionId: String, zip: java.nio.file.Path, sourceUrl: String) =
            InspectedPackage(inspectedSha, signed, sourceUrl, "", signatureProblem = signatureProblem)

        override fun downloadZip(url: String, target: java.nio.file.Path): java.nio.file.Path {
            downloadFailure?.let { throw ExtensionPackageException(it) }
            downloaded += url
            Files.writeString(target, "zip")
            return target
        }

        override fun buildFromZip(extensionId: String, zip: java.nio.file.Path, progress: (String) -> Unit, sourceUrl: String, sourceType: String): BuiltExtension {
            failure?.let { throw ExtensionPackageException(it) }
            if (!signed && !isAuthorized(inspectedSha)) {
                throw ExtensionPackageException("La extension '$extensionId' no esta firmada. El dueno del servidor debe autorizar el hash $inspectedSha antes de instalarla.")
            }
            builtFrom += listOf(sourceType, sourceUrl)
            return BuiltExtension(
                jar = jar, manifest = ExtensionManifest(id = extensionId, module = "crm", displayName = "M", version = version),
                packageSha256 = inspectedSha, signed = signed, keyId = null, sourceUrl = sourceUrl, jarSha256 = "d".repeat(64), sourceType = sourceType,
            )
        }
    }

    private class FakeAuthorizations : UnsignedAuthorizationStore {
        val saved = mutableListOf<List<String>>()
        override fun isAuthorized(packageSha256: String) = saved.any { it[0] == packageSha256 }
        override fun authorize(packageSha256: String, extensionId: String, sourceUrl: String, actor: String, acknowledgement: String) {
            saved += listOf(packageSha256, extensionId, sourceUrl, actor, acknowledgement)
        }
    }

    private class Harness(
        builder: FakeBuilder? = FakeBuilder(),
        packages: List<RemoteExtensionPackage>,
        bundled: Set<String> = emptySet(),
        val root: java.nio.file.Path = Files.createTempDirectory("ext-packages"),
    ) {
        val store = FakeStore()
        val authorizations = FakeAuthorizations()
        val audit = mutableListOf<List<String?>>()
        var restarts = 0
        val service = ExtensionPackageService(
            store = store,
            packagesRoot = root,
            bundledIds = { bundled },
            catalogSource = { packages },
            builder = builder,
            authorizations = authorizations,
            requestRestart = { restarts++ },
            audit = { role, action, company, module, details -> audit += listOf(role, action, company, module, details) },
            executor = Executor { it.run() },
        )
    }

    private fun harness(builder: FakeBuilder? = FakeBuilder(), signed: Boolean = true) = Harness(builder, listOf(remote(signed = signed)))

    @Test
    fun `install stores the package as pending_restart audits and asks for restart`() {
        val h = harness()
        val status = h.service.install("monday_import", "owner_role")
        assertEquals("pending_restart", status.status)
        assertTrue(status.restartRequired)
        assertEquals("pending_restart", h.store.find("monday_import")?.status)
        assertEquals(1, h.restarts)
        val entry = h.audit.single()
        assertEquals(listOf("owner_role", "extension.package.install", null, "crm"), entry.take(4))
        assertTrue(entry[4]!!.contains(sha))
    }

    @Test
    fun `install failure keeps the message and does not store or restart`() {
        val h = harness(FakeBuilder(failure = "Firma rechazada: invalid signature."))
        val status = h.service.install("monday_import", "owner_role")
        assertEquals("error", status.status)
        assertEquals("Firma rechazada: invalid signature.", status.message)
        assertEquals(null, h.store.find("monday_import"))
        assertEquals(0, h.restarts)
        assertTrue(h.audit.isEmpty())
    }

    @Test
    fun `install is refused for unknown bundled or unbuildable extensions`() {
        val h = harness()
        assertEquals("NOT_FOUND", assertFailsWith<ExtensionPackageServiceException> { h.service.install("otra", "r") }.code)
        h.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "1.0.0", "bundled", "installed", null, null, false)
        assertEquals("ALREADY_BUNDLED", assertFailsWith<ExtensionPackageServiceException> { h.service.install("monday_import", "r") }.code)
        val noBuilder = harness(builder = null)
        assertEquals("BUILDER_UNAVAILABLE", assertFailsWith<ExtensionPackageServiceException> { noBuilder.service.install("monday_import", "r") }.code)
    }

    private fun authorize(h: Harness, text: String = "Confirmo la autorizacion", hash: String = sha, expectedCommit: String = commit) =
        h.service.authorizeUnsigned("monday_import", "owner_role", text, hash, expectedCommit)

    @Test
    fun `authorization is rejected when the package changed since it was reviewed`() {
        val h = harness(signed = false)
        assertEquals("PACKAGE_CHANGED", assertFailsWith<ExtensionPackageServiceException> { authorize(h, hash = "f".repeat(64)) }.code)
        assertEquals("PACKAGE_CHANGED", assertFailsWith<ExtensionPackageServiceException> { authorize(h, expectedCommit = "9".repeat(40)) }.code)
        assertEquals("EXPECTED_REQUIRED", assertFailsWith<ExtensionPackageServiceException> { authorize(h, hash = "") }.code)
        assertEquals("EXPECTED_REQUIRED", assertFailsWith<ExtensionPackageServiceException> { authorize(h, expectedCommit = "develop") }.code)
        assertTrue(h.authorizations.saved.isEmpty())
        assertEquals(sha, authorize(h).packageSha256)
    }

    @Test
    fun `a package with an invalid signature cannot be authorized`() {
        val h = Harness(FakeBuilder(signed = false, signatureProblem = "invalid signature"), listOf(remote(signed = false)))
        assertEquals("SIGNATURE_INVALID", assertFailsWith<ExtensionPackageServiceException> { authorize(h) }.code)
        assertTrue(h.authorizations.saved.isEmpty())
    }

    @Test
    fun `authorizing an unsigned package stores the hash with the typed confirmation and audits it`() {
        val h = harness(signed = false)
        val result = authorize(h, "  Entiendo que corre con los mismos privilegios  ")
        assertEquals(sha, result.packageSha256)
        val saved = h.authorizations.saved.single()
        assertEquals(listOf(sha, "monday_import"), saved.take(2))
        assertEquals("owner_role", saved[3])
        assertEquals("Entiendo que corre con los mismos privilegios", saved[4])
        val entry = h.audit.single()
        assertEquals("extension.package.authorize_unsigned", entry[1])
        assertTrue(entry[4]!!.contains("Entiendo"))
    }

    @Test
    fun `authorization needs a real confirmation and an unsigned package`() {
        val unsigned = harness(signed = false)
        assertEquals("CONFIRMATION_REQUIRED", assertFailsWith<ExtensionPackageServiceException> { authorize(unsigned, text = "ok") }.code)
        val signed = Harness(FakeBuilder(signed = true), listOf(remote()))
        assertEquals("ALREADY_SIGNED", assertFailsWith<ExtensionPackageServiceException> { authorize(signed) }.code)
        assertTrue(signed.authorizations.saved.isEmpty() && unsigned.authorizations.saved.isEmpty())
    }

    @Test
    fun `remove marks the package removed and audits it`() {
        val h = harness()
        h.service.install("monday_import", "owner_role")
        val status = h.service.remove("monday_import", "owner_role")
        assertEquals("removed", status.status)
        assertEquals(null, status.installedVersion)
        assertEquals("extension.package.remove", h.audit.last()[1])
        assertEquals("NOT_FOUND", assertFailsWith<ExtensionPackageServiceException> { h.service.remove("monday_import", "r") }.code)
        assertEquals("NOT_FOUND", assertFailsWith<ExtensionPackageServiceException> { h.service.remove("otra", "r") }.code)
    }

    @Test
    fun `catalog shows the installed state of each package`() {
        val h = Harness(FakeBuilder(), listOf(remote("monday_import"), remote("encuentra24")))
        h.service.install("monday_import", "r")
        val entries = h.service.catalog().associateBy { it.id }
        assertEquals("pending_restart", entries.getValue("monday_import").status)
        assertEquals("0.1.0", entries.getValue("monday_import").installedVersion)
        assertEquals("not_installed", entries.getValue("encuentra24").status)
    }

    @Test
    fun `loadInstalled passes the registry data to the loader and moves only loaded packages to installed`() {
        val h = harness()
        val jar = Files.createTempFile(h.root, "ext", ".jar")
        h.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "0.1.0", "github", "pending_restart", jar.toString(), sha, true, "d".repeat(64))
        h.store.rows["lost"] = StoredExtensionPackage("lost", "crm", "0.1.0", "github", "pending_restart", "/no/existe.jar", sha, true, "e".repeat(64))
        h.store.rows["gone"] = StoredExtensionPackage("gone", "crm", "0.1.0", "github", "removed", jar.toString(), sha, true, "d".repeat(64))
        var candidates = emptyList<LoadCandidate>()
        var root: java.nio.file.Path? = null
        val extensions = h.service.loadInstalled(moduleClassLoader = { javaClass.classLoader }, load = { found, packagesRoot, _ ->
            candidates = found
            root = packagesRoot
            listOf(object : ServerExtension { override val manifest = manifest() })
        })
        assertEquals(h.root, root)
        assertEquals(listOf("lost", "monday_import"), candidates.map { it.id }.sorted())
        assertEquals(LoadCandidate(jar, "monday_import", "crm", "0.1.0", "d".repeat(64)), candidates.first { it.id == "monday_import" })
        assertNotNull(extensions.singleOrNull())
        assertEquals("installed", h.store.find("monday_import")?.status)
        assertEquals("pending_restart", h.store.find("lost")?.status)
        assertEquals("removed", h.store.find("gone")?.status)
    }

    @Test
    fun `install refuses ids of bundled extensions`() {
        val h = Harness(FakeBuilder(), listOf(remote()), bundled = setOf("monday_import"))
        assertEquals("ALREADY_BUNDLED", assertFailsWith<ExtensionPackageServiceException> { h.service.install("monday_import", "r") }.code)
        assertEquals(0, h.restarts)
    }

    @Test
    fun `install refuses a downgrade but allows reinstalling the same version`() {
        val h = harness()
        h.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "0.5.0", "github", "installed", null, sha, true, "d".repeat(64))
        assertEquals("DOWNGRADE", assertFailsWith<ExtensionPackageServiceException> { h.service.install("monday_import", "r") }.code)

        val same = Harness(FakeBuilder(version = "0.1.0"), listOf(remote()))
        same.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "0.1.0", "github", "installed", null, sha, true, "d".repeat(64))
        assertEquals("pending_restart", same.service.install("monday_import", "r").status)

        // Un registro quitado no cuenta: se puede volver a instalar una version menor.
        val removed = harness()
        removed.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "0.5.0", "github", "removed", null, sha, true, "d".repeat(64))
        assertEquals("pending_restart", removed.service.install("monday_import", "r").status)
    }

    @Test
    fun `a build whose version is older than the installed one is not stored`() {
        val h = Harness(FakeBuilder(version = "0.0.1"), listOf(remote()))
        h.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "0.1.0", "github", "installed", null, sha, true, "d".repeat(64))
        // El catalogo dice 0.1.0 (pasa la primera revision) pero el paquete compilado trae 0.0.1.
        val status = h.service.install("monday_import", "r")
        assertEquals("error", status.status)
        assertEquals("0.1.0", h.store.find("monday_import")?.version)
        assertEquals(0, h.restarts)
    }

    @Test
    fun `the old jar is deleted only after the new record is stored`() {
        val h = Harness(FakeBuilder(jar = java.nio.file.Path.of("nuevo.jar")), listOf(remote()))
        val old = Files.createTempFile(h.root, "viejo", ".jar")
        val outside = Files.createTempFile("fuera", ".jar")
        h.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "0.1.0", "github", "installed", old.toString(), sha, true, "d".repeat(64))
        h.service.install("monday_import", "r")
        assertTrue(!Files.exists(old))
        assertEquals("nuevo.jar", java.nio.file.Path.of(h.store.find("monday_import")!!.installedPackagePath!!).fileName.toString())

        // Si el registro no se guarda, el jar anterior sigue en su sitio.
        val failing = Harness(FakeBuilder(failure = "boom"), listOf(remote()))
        val kept = Files.createTempFile(failing.root, "viejo", ".jar")
        failing.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "0.1.0", "github", "installed", kept.toString(), sha, true, "d".repeat(64))
        failing.service.install("monday_import", "r")
        assertTrue(Files.exists(kept))

        // Nunca borra fuera de packagesRoot.
        val guarded = Harness(FakeBuilder(jar = java.nio.file.Path.of("otro.jar")), listOf(remote()))
        guarded.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "0.1.0", "github", "installed", outside.toString(), sha, true, "d".repeat(64))
        guarded.service.install("monday_import", "r")
        assertTrue(Files.exists(outside))
    }

    @Test
    fun `catalog exposes commit and commit url and inspect exposes the calculated hash`() {
        val h = harness(FakeBuilder(inspectedSha = "e".repeat(64)), signed = false)
        val entry = h.service.catalog().single()
        assertEquals(commit, entry.commit)
        assertEquals("https://github.com/acme/idc/tree/$commit/extensions/monday_import", entry.commitUrl)
        val inspection = h.service.inspect("monday_import")
        assertEquals(commit, inspection.commit)
        assertEquals("e".repeat(64), inspection.packageSha256)
        assertEquals(false, inspection.signed)
    }

    // --- Paquetes desde un ZIP subido o una URL ---

    private val zipBytes = "zip".toByteArray()

    private fun external(builder: FakeBuilder = FakeBuilder()): Harness {
        val h = Harness(builder, emptyList())
        builder.isAuthorized = { h.authorizations.isAuthorized(it) }
        return h
    }

    private fun code(block: () -> Unit) = assertFailsWith<ExtensionPackageServiceException> { block() }.code

    @Test
    fun `a signed zip is inspected stored as zip source audited and the staged file is removed`() {
        val h = external(FakeBuilder(signed = true))
        val inspection = h.service.inspectZip("monday_import", "mi paquete.zip", zipBytes)
        assertEquals("zip", inspection.source)
        assertEquals("zip:mi_paquete.zip", inspection.sourceUrl)
        assertTrue(inspection.signed && inspection.installable)
        assertEquals(null, h.store.find("monday_import"))

        val status = h.service.installStaged(inspection.stagingToken, "owner_role")
        assertEquals("pending_restart", status.status)
        val row = h.store.find("monday_import")!!
        assertEquals("zip", row.sourceType)
        assertEquals(1, h.restarts)
        assertEquals("extension.package.install", h.audit.single()[1])
        assertTrue(h.audit.single()[4]!!.contains("zip:mi_paquete.zip"))
        assertTrue(Files.list(h.root.resolve("_staging")).use { it.toList().isEmpty() })
        assertEquals("NOT_FOUND", code { h.service.installStaged(inspection.stagingToken, "owner_role") })
    }

    @Test
    fun `an unsigned zip fails to install until the owner authorizes its hash`() {
        val builder = FakeBuilder(signed = false)
        val h = external(builder)
        val inspection = h.service.inspectZip("monday_import", "p.zip", zipBytes)
        assertFalse(inspection.installable)

        val failed = h.service.installStaged(inspection.stagingToken, "owner_role")
        assertEquals("error", failed.status)
        assertTrue(failed.message!!.contains("no esta firmada") && failed.message!!.contains(sha))
        assertEquals(null, h.store.find("monday_import"))
        assertEquals(0, h.restarts)

        assertEquals("CONFIRMATION_REQUIRED", code { h.service.authorizeStaged(inspection.stagingToken, "owner_role", "ok", sha) })
        assertEquals("EXPECTED_REQUIRED", code { h.service.authorizeStaged(inspection.stagingToken, "owner_role", confirmation, "") })
        // Sin los 8 hex del hash en la confirmacion se rechaza, aunque el texto sea largo.
        assertEquals("CONFIRMATION_HASH_REQUIRED", code { h.service.authorizeStaged(inspection.stagingToken, "owner_role", "Confirmo la autorizacion", sha) })
        assertEquals("CONFIRMATION_HASH_REQUIRED", code { h.service.authorizeStaged(inspection.stagingToken, "owner_role", "Confirmo la autorizacion ffffffff", sha) })
        assertEquals("PACKAGE_CHANGED", code { h.service.authorizeStaged(inspection.stagingToken, "owner_role", "Confirmo la autorizacion ffffffff", "f".repeat(64)) })
        assertTrue(h.authorizations.saved.isEmpty())

        // Acepta el hash corto en mayusculas o minusculas.
        val authorized = h.service.authorizeStaged(inspection.stagingToken, "owner_role", "  $confirmation  ".uppercase(), sha)
        assertEquals(sha, authorized.packageSha256)
        assertEquals(listOf(sha, "monday_import", "zip:p.zip", "owner_role", confirmation.uppercase()), h.authorizations.saved.single())
        assertEquals("extension.package.authorize_unsigned", h.audit.single()[1])

        assertEquals("pending_restart", h.service.installStaged(inspection.stagingToken, "owner_role").status)
        assertEquals("zip", h.store.find("monday_import")?.sourceType)
    }

    @Test
    fun `a signed or invalidly signed zip cannot be authorized`() {
        val signed = external(FakeBuilder(signed = true))
        val one = signed.service.inspectZip("monday_import", "p.zip", zipBytes)
        assertEquals("ALREADY_SIGNED", code { signed.service.authorizeStaged(one.stagingToken, "r", confirmation, sha) })
        val invalid = external(FakeBuilder(signed = false, signatureProblem = "invalid signature"))
        val two = invalid.service.inspectZip("monday_import", "p.zip", zipBytes)
        assertEquals("SIGNATURE_INVALID", code { invalid.service.authorizeStaged(two.stagingToken, "r", confirmation, sha) })
        assertTrue(signed.authorizations.saved.isEmpty() && invalid.authorizations.saved.isEmpty())
    }

    @Test
    fun `zip and url sources refuse bundled ids bad ids empty or huge files and servers without a builder`() {
        val h = external()
        assertEquals("INVALID_ID", code { h.service.inspectZip("Bad-Id", "p.zip", zipBytes) })
        assertEquals("INVALID_ZIP", code { h.service.inspectZip("monday_import", "p.zip", ByteArray(0)) })
        assertEquals("PAYLOAD_TOO_LARGE", code { h.service.inspectZip("monday_import", "p.zip", ByteArray(ExtensionPackageService.MaxZipBytes + 1)) })
        assertEquals("NOT_FOUND", code { h.service.installStaged("no-existe", "r") })
        assertEquals("NOT_FOUND", code { h.service.authorizeStaged("no-existe", "r", confirmation, sha) })

        val bundled = Harness(FakeBuilder(), emptyList(), bundled = setOf("monday_import"))
        assertEquals("ALREADY_BUNDLED", code { bundled.service.inspectZip("monday_import", "p.zip", zipBytes) })
        assertEquals("ALREADY_BUNDLED", code { bundled.service.installFromUrl("monday_import", "https://example.com/p.zip", "r") })

        val none = Harness(null, emptyList())
        assertEquals("BUILDER_UNAVAILABLE", code { none.service.inspectZip("monday_import", "p.zip", zipBytes) })
        assertEquals("BUILDER_UNAVAILABLE", code { none.service.inspectUrl("monday_import", "https://example.com/p.zip") })
    }

    @Test
    fun `a url is validated downloaded inspected and installed as url source`() {
        val builder = FakeBuilder(signed = true)
        val h = external(builder)
        listOf("http://example.com/p.zip", "https://user:x@example.com/p.zip", "https://example.com:8443/p.zip", "no es url").forEach {
            assertEquals("URL_REJECTED", code { h.service.inspectUrl("monday_import", it) }, it)
        }
        assertTrue(builder.downloaded.isEmpty())

        builder.downloadFailure = "El servidor apunta a una red privada o local: no se permite."
        assertEquals("PACKAGE_UNAVAILABLE", code { h.service.inspectUrl("monday_import", "https://example.com/p.zip") })
        assertTrue(!Files.exists(h.root.resolve("_staging")) || Files.list(h.root.resolve("_staging")).use { it.toList().isEmpty() })

        builder.downloadFailure = null
        val inspection = h.service.inspectUrl("monday_import", " https://example.com/p.zip ")
        assertEquals(listOf("https://example.com/p.zip"), builder.downloaded)
        assertEquals("url", inspection.source)
        assertEquals("https://example.com/p.zip", inspection.sourceUrl)
        assertEquals("pending_restart", h.service.installStaged(inspection.stagingToken, "owner_role").status)
        assertEquals(listOf("url", "https://example.com/p.zip"), builder.builtFrom.single())
        assertEquals("url", h.store.find("monday_import")?.sourceType)
    }

    @Test
    fun `installFromZip and installFromUrl keep the signature gate the downgrade rule and the shared queue`() {
        val builder = FakeBuilder(signed = false)
        val h = external(builder)
        val zip = Files.createTempFile(h.root, "p", ".zip")
        assertEquals("error", h.service.installFromZip("monday_import", zip, "r").status)
        assertEquals("URL_REJECTED", code { h.service.installFromUrl("monday_import", "http://example.com/p.zip", "r") })

        // Con el hash autorizado instala; una version menor que la instalada no se guarda.
        h.authorizations.authorize(sha, "monday_import", "zip:p.zip", "r", "Confirmo la autorizacion")
        h.store.rows["monday_import"] = StoredExtensionPackage("monday_import", "crm", "0.5.0", "zip", "installed", null, sha, false, "d".repeat(64))
        val downgrade = h.service.installFromZip("monday_import", zip, "r")
        assertEquals("error", downgrade.status)
        assertEquals("0.5.0", h.store.find("monday_import")?.version)

        builder.version = "0.6.0"
        assertEquals("pending_restart", h.service.installFromZip("monday_import", zip, "r").status)
        assertEquals(1, h.restarts)
    }

    @Test
    fun `a package installed from a zip or url can be removed`() {
        val h = external(FakeBuilder(signed = true))
        h.service.installFromZip("monday_import", Files.createTempFile(h.root, "p", ".zip"), "r")
        assertEquals("removed", h.service.remove("monday_import", "r").status)
        assertTrue(h.store.loadable().isEmpty())
    }
}
