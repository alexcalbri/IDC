package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.extensions.ExtensionSignature
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat

/** Llaves publicas del Core por keyId. Cada valor es base64 de un X.509 SubjectPublicKeyInfo (vacio = sin configurar). */
object IdeasCorePublicKeys {
    const val DefaultKeyId = "ideascore-2026"

    // Recurso extensions/ideascore-2026.pub; vacio hasta que el usuario genere la llave (scripts/extensions/keygen.sh).
    val keys: Map<String, String> by lazy {
        mapOf(DefaultKeyId to read("/extensions/$DefaultKeyId.pub"))
    }

    private fun read(resource: String): String =
        IdeasCorePublicKeys::class.java.getResourceAsStream(resource)?.use { it.readBytes().decodeToString() }
            ?.lineSequence()?.filterNot { it.trimStart().startsWith("#") }?.joinToString("")?.filterNot { it.isWhitespace() }
            .orEmpty()
}

/** Verifica la firma Ed25519 de un paquete: se firma el hash del arbol (32 bytes), no su texto hex. */
class ExtensionSignatureVerifier(private val publicKeys: Map<String, String> = IdeasCorePublicKeys.keys) {
    /** Devuelve null cuando la firma es valida, o el motivo del rechazo. */
    fun verify(packageSha256: String, signature: ExtensionSignature): String? {
        val encoded = publicKeys[signature.keyId] ?: return "unknown signing key '${signature.keyId}'"
        if (encoded.isBlank()) return "signing key not configured"
        val hash = runCatching { HexFormat.of().parseHex(packageSha256) }.getOrNull()
        if (hash == null || hash.size != 32) return "packageSha256 must be 64 hex characters"
        return try {
            val key: PublicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(hash)
            if (verifier.verify(Base64.getDecoder().decode(signature.value))) null else "invalid signature"
        } catch (_: Exception) {
            "invalid signature"
        }
    }
}
