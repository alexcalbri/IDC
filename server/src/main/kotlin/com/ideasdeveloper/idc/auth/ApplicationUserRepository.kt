package com.ideasdeveloper.idc.auth

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ApplicationUserRepository(
    private val database: Database,
) {

    fun findByPostgresRole(postgresRole: String): ApplicationUser? {
        return transaction(db = database) {
            ApplicationUsersTable
                .selectAll()
                .where {
                    ApplicationUsersTable.postgresRole eq postgresRole
                }
                .singleOrNull()
                ?.let { row ->
                    ApplicationUser(
                        id = row[ApplicationUsersTable.id],
                        postgresRole = row[ApplicationUsersTable.postgresRole],
                        isActive = row[ApplicationUsersTable.isActive],
                    )
                }
        }
    }
    fun findById(userId: Uuid): ApplicationUser? {
        return transaction(db = database) {
            ApplicationUsersTable
                .selectAll()
                .where {
                    ApplicationUsersTable.id eq userId
                }
                .singleOrNull()
                ?.let { row ->
                    ApplicationUser(
                        id = row[ApplicationUsersTable.id],
                        postgresRole = row[ApplicationUsersTable.postgresRole],
                        isActive = row[ApplicationUsersTable.isActive],
                    )
                }
        }
    }
}