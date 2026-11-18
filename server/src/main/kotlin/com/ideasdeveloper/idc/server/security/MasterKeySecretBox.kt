package com.ideasdeveloper.idc.server.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Falla de descifrado. El mensaje nunca incluye el valor ni las llaves. */
class SecretDecryptionException(message: String = "SECRET_DECRYPTION_FAILED: no se pudo descifrar el valor") :
    IllegalStateException(message)

/**
 * Primitivas criptograficas puras (sin base de datos): AES-256-GCM con nonce aleatorio de 12 bytes y
 * AAD fija. El formato guardado es base64(nonce | cifrado+tag).
 */
object SecretCrypto {
    const val KeyBytes = 32
    private const val NonceBytes = 12
    private const val TagBits = 128
    private val Aad = "idc-secret-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun newDataKey(): ByteArray = ByteArray(KeyBytes).also { random.nextBytes(it) }

    /** Cifra [plain] con [key]; devuelve base64(nonce | cifrado+tag). */
    fun encrypt(key: ByteArray, plain: ByteArray): String {
        val nonce = ByteArray(NonceBytes).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, nonce))
        cipher.updateAAD(Aad)
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain))
    }

    fun decrypt(key: ByteArray, encoded: String): ByteArray {
        try {
            val data = Base64.getDecoder().decode(encoded)
            if (data.size <= NonceBytes) throw SecretDecryptionException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, data.copyOfRange(0, NonceBytes)))
            cipher.updateAAD(Aad)
            return cipher.doFinal(data, NonceBytes, data.size - NonceBytes)
        } catch (failure: SecretDecryptionException) {
            throw failure
        } catch (failure: GeneralSecurityException) {
            throw SecretDecryptionException()
        } catch (failure: IllegalArgumentException) {
            throw SecretDecryptionException()
        }
    }

    /** Envuelve una llave de datos con la llave maestra. */
    fun wrap(masterKey: ByteArray, dataKey: ByteArray): String = encrypt(masterKey, dataKey)

    fun unwrap(masterKey: ByteArray, wrapped: String): ByteArray = decrypt(masterKey, wrapped)

    fun sealWith(dataKey: ByteArray, plain: String): String =
        SecretBox.SealedPrefix + encrypt(dataKey, plain.toByteArray(Charsets.UTF_8))

    /** Un valor sin prefijo es texto plano heredado y se devuelve igual. */
    fun openWith(dataKey: ByteArray, value: String): String =
        if (!value.startsWith(SecretBox.SealedPrefix)) value
        else String(decrypt(dataKey, value.removePrefix(SecretBox.SealedPrefix)), Charsets.UTF_8)

    /** Decodifica IDC_MASTER_KEY (base64 de 32 bytes); null si falta o es invalida. */
    fun parseMasterKey(raw: String?): ByteArray? {
        if (raw.isNullOrBlank()) return null
        val decoded = try {
            Base64.getDecoder().decode(raw.trim())
        } catch (_: IllegalArgumentException) {
            return null
        }
        return decoded.takeIf { it.size == KeyBytes }
    }
}

/**
 * SecretBox con llave maestra de IDC_MASTER_KEY. La llave de datos de cada base vive envuelta en
 * tenant_data_keys y se guarda en memoria por nombre de base.
 */
class MasterKeySecretBox(masterKey: String?) : SecretBox {
    private val master: ByteArray? = SecretCrypto.parseMasterKey(masterKey)

    // La llave desenvuelta con el valor envuelto del que salio: se valida contra la fila en cada uso.
    private class CachedKey(val wrapped: String, val key: ByteArray)

    private val dataKeys = ConcurrentHashMap<String, CachedKey>()

    override val available: Boolean get() = master != null

    override fun seal(tenant: Connection, plain: String): String {
        val masterKey = master ?: throw SecretsUnavailableException()
        if (isSealed(plain)) {
            // El prefijo esta reservado: solo se acepta un valor que de verdad abre con la llave actual.
            val key = dataKey(tenant, masterKey, create = false) ?: throw IllegalArgumentException("valor reservado")
            try {
                SecretCrypto.openWith(key, plain)
            } catch (_: SecretDecryptionException) {
                throw IllegalArgumentException("valor reservado")
            }
            return plain
        }
        return SecretCrypto.sealWith(dataKey(tenant, masterKey, create = true)!!, plain)
    }

    override fun open(tenant: Connection, value: String): String {
        if (!isSealed(value)) return value
        val masterKey = master ?: throw SecretsUnavailableException()
        return SecretCrypto.openWith(dataKey(tenant, masterKey, create = false) ?: throw SecretDecryptionException(), value)
    }

    /** Olvida la llave en memoria de una base (al borrarla o recrearla). */
    override fun forget(databaseName: String) {
        dataKeys.remove(databaseName)
    }

    private fun dataKey(tenant: Connection, masterKey: ByteArray, create: Boolean): ByteArray? {
        val database = tenant.catalog ?: ""
        var wrapped = readWrapped(tenant)
        if (wrapped == null && create) {
            val fresh = SecretCrypto.wrap(masterKey, SecretCrypto.newDataKey())
            tenant.prepareStatement("INSERT INTO tenant_data_keys (id, wrapped_key, key_version) VALUES (1, ?, 1) ON CONFLICT DO NOTHING").use {
                it.setString(1, fresh)
                it.executeUpdate()
            }
            wrapped = readWrapped(tenant)
        }
        if (wrapped == null) {
            // Sin fila no hay llave: lo guardado en memoria ya no corresponde a esta base.
            dataKeys.remove(database)
            return null
        }
        dataKeys[database]?.takeIf { it.wrapped == wrapped }?.let { return it.key }
        val key = SecretCrypto.unwrap(masterKey, wrapped)
        dataKeys[database] = CachedKey(wrapped, key)
        return key
    }

    private fun readWrapped(tenant: Connection): String? =
        tenant.prepareStatement("SELECT wrapped_key FROM tenant_data_keys WHERE id = 1").use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    companion object {
        fun fromEnvironment(): MasterKeySecretBox = MasterKeySecretBox(System.getenv("IDC_MASTER_KEY"))
    }
}
