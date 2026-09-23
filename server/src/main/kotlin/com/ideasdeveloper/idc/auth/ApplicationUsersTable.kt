package com.ideasdeveloper.idc.auth

import org.jetbrains.exposed.v1.core.Table

object ApplicationUsersTable : Table("application_users") {

    val id = uuid("id")

    val postgresRole = varchar("postgres_role", 63).uniqueIndex()

    val isActive = bool("is_active").default(false)

    override val primaryKey = PrimaryKey(id)
}