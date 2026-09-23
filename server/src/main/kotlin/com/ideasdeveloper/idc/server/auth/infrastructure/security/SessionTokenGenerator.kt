package com.ideasdeveloper.idc.server.auth.infrastructure.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

class SessionTokenGenerator {

    private val random = SecureRandom()

    fun generate(): GeneratedToken {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)

        val token = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)

        return GeneratedToken(
            value = token,
            hash = hash(token),
        )
    }

    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))

        return HexFormat.of().formatHex(digest)
    }

    class GeneratedToken(
        val value: String,
        val hash: String,
    )
}