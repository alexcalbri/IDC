package com.ideasdeveloper.idc.server.security

import java.sql.Connection

/*
 * Encrypts secrets before they are stored (EXTENSIONS.md, "Secrets"). Each company database has its
 * own data key in tenant_data_keys, wrapped with the server master key IDC_MASTER_KEY (server.env).
 * Sealed values look like "enc:v1:<base64(nonce | ciphertext+tag)>" and are never returned by the API.
 */
interface SecretBox {
    /** False when IDC_MASTER_KEY is missing or invalid: seal() then throws SecretsUnavailableException. */
    val available: Boolean

    /** Encrypts with the data key of the database behind [tenant] (created on first use). A value that already looks sealed is only accepted when it really opens with the current key (else IllegalArgumentException). */
    fun seal(tenant: Connection, plain: String): String

    /** Decrypts a sealed value; a value that is not sealed (legacy plaintext) is returned as it is. */
    fun open(tenant: Connection, value: String): String

    /** Forgets what is kept in memory for a database (call it when the company is deleted). */
    fun forget(databaseName: String) {
    }

    fun isSealed(value: String?): Boolean = value != null && value.startsWith(SealedPrefix)

    companion object {
        const val SealedPrefix = "enc:v1:"
        const val Mask = "********"

        /** What the API shows instead of a stored secret: the mask when something is stored, else "". */
        fun mask(value: String?): String = if (value.isNullOrEmpty()) "" else Mask
    }
}

class SecretsUnavailableException(message: String = "SECRETS_UNAVAILABLE: IDC_MASTER_KEY is not configured") :
    IllegalStateException(message)
