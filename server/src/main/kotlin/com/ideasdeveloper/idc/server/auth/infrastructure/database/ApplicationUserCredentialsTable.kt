package com.ideasdeveloper.idc.server.auth.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object ApplicationUserCredentialsTable : Table("application_user_credentials") {
    val userId = reference("user_id", ApplicationUsersTable.id)
    val passwordSalt = text("password_salt")
    val passwordHash = text("password_hash")
    val iterations = integer("iterations")

    override val primaryKey = PrimaryKey(userId)
}
