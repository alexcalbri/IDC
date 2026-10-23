package com.ideasdeveloper.idc.server.auth.infrastructure.database

import org.jetbrains.exposed.v1.core.*

object ApplicationUsersTable : Table("application_users") {

    val id = uuid("id")

    val postgresRole = varchar("postgres_role", 63).uniqueIndex()

    val isActive = bool("is_active").default(false)

    override val primaryKey = PrimaryKey(id)
}
