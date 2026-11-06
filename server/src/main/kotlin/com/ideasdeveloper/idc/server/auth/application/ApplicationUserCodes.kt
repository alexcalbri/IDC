package com.ideasdeveloper.idc.server.auth.application

import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Core-owned identifiers shared by user management and optional modules. */
object ApplicationUserCodes {
    private const val Alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val Prefix = "us"
    private const val Length = 8
    private val random = SecureRandom()

    fun generate(): String = Prefix + buildString(Length) {
        repeat(Length) { append(Alphabet[random.nextInt(Alphabet.length)]) }
    }

    fun isValid(code: String): Boolean {
        val normalized = code.trim().uppercase()
        return normalized.length == Prefix.length + Length &&
            normalized.startsWith(Prefix.uppercase()) &&
            normalized.drop(Prefix.length).all { it in Alphabet }
    }

    /**
     * Keeps a valid legacy code when migrating it; otherwise creates the user's code once.
     * The unique index arbitrates concurrent generation and the rare random collision.
     */
    fun ensure(connection: Connection, userId: UUID, preferredCode: String? = null): String {
        repeat(10) {
            find(connection, userId)?.let { return it }
            val candidate = preferredCode?.takeIf(::isValid)?.trim()?.lowercase() ?: generate()
            try {
                connection.prepareStatement(
                    "UPDATE application_users SET user_code = ? WHERE id = ? AND user_code IS NULL"
                ).use { update ->
                    update.setString(1, candidate)
                    update.setObject(2, userId)
                    if (update.executeUpdate() == 1) return candidate
                }
            } catch (failure: SQLException) {
                if (failure.sqlState != "23505") throw failure
                if (preferredCode?.equals(candidate, ignoreCase = true) == true) {
                    throw IllegalStateException("El codigo de usuario existente ya esta asignado.", failure)
                }
            }
        }
        return find(connection, userId)
            ?: throw IllegalStateException("No se pudo generar el codigo de usuario.")
    }

    private fun find(connection: Connection, userId: UUID): String? = connection.prepareStatement(
        "SELECT user_code FROM application_users WHERE id = ?"
    ).use { query ->
        query.setObject(1, userId)
        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
}
