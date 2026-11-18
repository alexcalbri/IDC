package com.ideasdeveloper.idc.server.auth.infrastructure.database

import com.ideasdeveloper.idc.server.auth.domain.ApplicationUser
import com.ideasdeveloper.idc.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.idc.server.auth.infrastructure.security.PasswordHash
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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
                ?: run {
                    // Usuario inexistente: se calcula un hash ficticio con el mismo coste para igualar el tiempo.
                    ApplicationPasswordHasher.verify(password, DummySalt, DummyHash, DummyIterations)
                    return@transaction false
                }

            val valid = ApplicationPasswordHasher.verify(
                password = password,
                salt = row[ApplicationUserCredentialsTable.passwordSalt],
                expectedHash = row[ApplicationUserCredentialsTable.passwordHash],
                iterations = row[ApplicationUserCredentialsTable.iterations],
            )
            // Hash guardado con menos iteraciones: se re-hashea con el coste actual al iniciar sesion.
            if (valid && row[ApplicationUserCredentialsTable.iterations] < ApplicationPasswordHasher.Iterations) {
                val upgraded = ApplicationPasswordHasher.hash(password)
                ApplicationUserCredentialsTable.update({
                    ApplicationUserCredentialsTable.userId eq row[ApplicationUsersTable.id]
                }) {
                    it[passwordSalt] = upgraded.salt
                    it[passwordHash] = upgraded.hash
                    it[iterations] = upgraded.iterations
                }
            }
            valid
        }
    }

    /** True si el usuario es dueno (business owner o server owner) de la base de este repositorio. */
    fun isOwner(userId: Uuid): Boolean =
        existsIn(BusinessOwnerTable, BusinessOwnerTable.userId, userId) ||
            existsIn(ServerOwnersTable, ServerOwnersTable.userId, userId)

    // Tablas que no existen en esta base (tenant sin server_owners, central sin business_owner). Se recuerdan
    // para no repetir la consulta fallida (Exposed la reintenta) en cada inicio de sesion.
    private val missingTables = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Solo el error "tabla inexistente" (SQLState 42P01) cuenta como "no es dueno"; cualquier otro
    // fallo (conexion, tiempo de espera) se relanza para que el login falle de forma visible.
    private fun existsIn(table: Table, column: Column<Uuid>, userId: Uuid): Boolean {
        if (table.tableName in missingTables) return false
        return try {
            transaction(db = database) { !table.selectAll().where { column eq userId }.empty() }
        } catch (failure: Exception) {
            if (generateSequence<Throwable>(failure) { it.cause }.any { (it as? java.sql.SQLException)?.sqlState == "42P01" }) {
                missingTables.add(table.tableName)
                false
            } else {
                throw failure
            }
        }
    }

    private object BusinessOwnerTable : Table("business_owner") {
        val userId = uuid("user_id")
    }

    private object ServerOwnersTable : Table("server_owners") {
        val userId = uuid("user_id")
    }

    private companion object {
        // Hash ficticio (clave y sal fijas) solo para igualar el tiempo cuando el usuario no existe.
        // Mismo coste que los hashes antiguos (120000), que son los que tienen hoy los usuarios existentes:
        // asi "usuario no existe" tarda igual que "existe con hash antiguo". Cuando todos esten migrados
        // a ApplicationPasswordHasher.Iterations, subir este valor al mismo.
        const val DummyIterations = 120_000
        const val DummySalt = "AAAAAAAAAAAAAAAAAAAAAA=="
        const val DummyHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
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
