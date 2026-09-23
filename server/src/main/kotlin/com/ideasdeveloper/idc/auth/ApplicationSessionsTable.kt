package com.ideasdeveloper.idc.auth

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object ApplicationSessionsTable : Table("application_sessions") {

    val tokenHash = varchar("token_hash", 64)

    val userId = reference(
        "user_id",
        ApplicationUsersTable.id,
        onDelete = ReferenceOption.CASCADE,
    ).index("application_sessions_user_id_idx")

    val createdAt = timestampWithTimeZone("created_at")
        .defaultExpression(CurrentTimestampWithTimeZone)

    val expiresAt = timestampWithTimeZone("expires_at")

    val revokedAt = timestampWithTimeZone("revoked_at").nullable()

    override val primaryKey = PrimaryKey(tokenHash)

    init {
        check("application_sessions_expiration_check") {
            expiresAt greater createdAt
        }
    }
}