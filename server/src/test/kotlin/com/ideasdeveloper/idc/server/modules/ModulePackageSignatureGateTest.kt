package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.server.extensions.packages.ExtensionSignatureVerifier
import com.ideasdeveloper.idc.server.extensions.packages.ExtensionTreeHash
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Compuerta de firma y hash autorizado que se aplica a un paquete de modulo antes de compilarlo. */
class ModulePackageSignatureGateTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicBase64 = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val verifier = ExtensionSignatureVerifier(mapOf("ideascore-2026" to publicBase64))

    private class MemoryAuthorizations : ModuleUnsignedAuthorizationStore {
        val hashes = mutableSetOf<String>()
        override fun isAuthorized(packageSha256: String) = packageSha256 in hashes
        override fun authorize(packageSha256: String, moduleId: String, commit: String, actor: String, acknowledgement: String) {
            hashes += packageSha256
        }
    }

    private fun builder(root: Path, authorizations: ModuleUnsignedAuthorizationStore) =
        ModulePackageBuilder(root.resolve("pkgs"), root.resolve("compiler"), "", "java", verifier = verifier, authorizations = authorizations)

    private fun moduleDir(root: Path): Path {
        val dir = Files.createDirectories(root.resolve("crm"))
        Files.writeString(dir.resolve("module.json"), """{"id":"crm","version":"1.0.0"}""")
        return dir
    }

    private fun sign(hash: String): String {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(HexFormat.of().parseHex(hash))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("modsig")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun signedPackagePasses() = withRoot { root ->
        val dir = moduleDir(root)
        val hash = ExtensionTreeHash.compute(dir)
        Files.writeString(
            dir.resolve(ExtensionTreeHash.SignatureFileName),
            """{"packageSha256":"$hash","signature":{"keyId":"ideascore-2026","value":"${sign(hash)}"}}""",
        )
        assertEquals(hash, builder(root, DenyAllModuleAuthorizations).verify("crm", dir))
    }

    @Test
    fun signedPackageChangedAfterSigningIsRejected() = withRoot { root ->
        val dir = moduleDir(root)
        val hash = ExtensionTreeHash.compute(dir)
        Files.writeString(
            dir.resolve(ExtensionTreeHash.SignatureFileName),
            """{"packageSha256":"$hash","signature":{"keyId":"ideascore-2026","value":"${sign(hash)}"}}""",
        )
        Files.writeString(dir.resolve("extra.txt"), "codigo nuevo")
        assertFailsWith<ModuleBuildException> { builder(root, DenyAllModuleAuthorizations).verify("crm", dir) }
    }

    @Test
    fun unsignedAndUnauthorizedIsRejected() = withRoot { root ->
        val dir = moduleDir(root)
        val failure = assertFailsWith<ModuleBuildException> { builder(root, MemoryAuthorizations()).verify("crm", dir) }
        assertTrue(failure.message!!.contains("no esta firmado"))
    }

    @Test
    fun unsignedWithAuthorizedHashPasses() = withRoot { root ->
        val dir = moduleDir(root)
        val authorizations = MemoryAuthorizations()
        val hash = ExtensionTreeHash.compute(dir)
        authorizations.authorize(hash, "crm", "a".repeat(40), "owner", "revise el codigo")
        assertEquals(hash, builder(root, authorizations).verify("crm", dir))
    }

    @Test
    fun invalidSignatureIsRejectedEvenIfHashIsAuthorized() = withRoot { root ->
        val dir = moduleDir(root)
        val authorizations = MemoryAuthorizations()
        val hash = ExtensionTreeHash.compute(dir)
        authorizations.authorize(hash, "crm", "a".repeat(40), "owner", "revise el codigo")
        Files.writeString(
            dir.resolve(ExtensionTreeHash.SignatureFileName),
            """{"packageSha256":"$hash","signature":{"keyId":"ideascore-2026","value":"AAAA"}}""",
        )
        assertFailsWith<ModuleBuildException> { builder(root, authorizations).verify("crm", dir) }
    }
}
