package com.ideasdeveloper.idc.auth

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

class ApplicationSessionRepository(
    private val database: Database,
) {

    fun create(session: ApplicationSession) {
        transaction(db = database) {
            ApplicationSessionsTable.insert {
                it[tokenHash] = session.tokenHash
                it[userId] = session.userId
                it[createdAt] = session.createdAt
                it[expiresAt] = session.expiresAt
                it[revokedAt] = session.revokedAt
            }
        }
    }

    fun findByTokenHash(tokenHash: String): ApplicationSession? {
        return transaction(db = database) {
            ApplicationSessionsTable
                .selectAll()
                .where {
                    ApplicationSessionsTable.tokenHash eq tokenHash
                }
                .singleOrNull()
                ?.let { row ->
                    ApplicationSession(
                        tokenHash = row[ApplicationSessionsTable.tokenHash],
                        userId = row[ApplicationSessionsTable.userId],
                        createdAt = row[ApplicationSessionsTable.createdAt],
                        expiresAt = row[ApplicationSessionsTable.expiresAt],
                        revokedAt = row[ApplicationSessionsTable.revokedAt],
                    )
                }
        }
    }

    fun revoke(tokenHash: String, revokedAt: OffsetDateTime): Boolean {
        return transaction(db = database) {
            val updatedRows = ApplicationSessionsTable.update({
                (ApplicationSessionsTable.tokenHash eq tokenHash) and
                        ApplicationSessionsTable.revokedAt.isNull()
            }) {
                it[ApplicationSessionsTable.revokedAt] = revokedAt
            }

            updatedRows > 0
        }
    }

    fun revokeAllForUser(userId: Uuid, revokedAt: OffsetDateTime): Int {
        return transaction(db = database) {
            ApplicationSessionsTable.update({
                (ApplicationSessionsTable.userId eq userId) and
                        ApplicationSessionsTable.revokedAt.isNull()
            }) {
                it[ApplicationSessionsTable.revokedAt] = revokedAt
            }
        }
    }
}