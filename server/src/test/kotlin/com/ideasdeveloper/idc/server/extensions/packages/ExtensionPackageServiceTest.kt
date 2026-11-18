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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionPackageServiceTest {
    private val commit = "b".repeat(40)
    private val sha = "c".repeat(64)

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
                built.manifest.id, built.manifest.module, built.manifest.version, "github", "pending_restart",
                built.jar.toString(), built.packageSha256, built.signed,
            )
        }
        override fun markRemoved(id: String): Boolean {
            val row = rows[id] ?: return false
            rows[id] = row.copy(status = "removed")
            return true
        }
        override fun loadable() = rows.values.filter { it.sourceType == "github" && it.status in setOf("installed", "pending_restart") }
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
}
