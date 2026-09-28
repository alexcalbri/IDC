package com.ideasdeveloper.idc.server.auth.infrastructure.database

import com.ideasdeveloper.idc.server.auth.domain.ApplicationUser
import com.ideasdeveloper.idc.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.idc.server.auth.infrastructure.security.PasswordHash
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
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

    fun verifyApplicationPassword(postgresRole: String, password: String): Boolean {
        return transaction(db = database) {
            val row = ApplicationUsersTable
                .innerJoin(ApplicationUserCredentialsTable)
                .selectAll()
                .where {
                    (ApplicationUsersTable.postgresRole eq postgresRole) and
                        (ApplicationUsersTable.isActive eq true)
                }
                .singleOrNull()
                ?: return@transaction false

            ApplicationPasswordHasher.verify(
                password = password,
                salt = row[ApplicationUserCredentialsTable.passwordSalt],
                expectedHash = row[ApplicationUserCredentialsTable.passwordHash],
                iterations = row[ApplicationUserCredentialsTable.iterations],
            )
        }
    }

    fun setApplicationPassword(userId: Uuid, passwordHash: PasswordHash) {
        transaction(db = database) {
            ApplicationUserCredentialsTable.insert {
                it[ApplicationUserCredentialsTable.userId] = userId
                it[passwordSalt] = passwordHash.salt
                it[ApplicationUserCredentialsTable.passwordHash] = passwordHash.hash
                it[iterations] = passwordHash.iterations
            }
        }
    }
}
