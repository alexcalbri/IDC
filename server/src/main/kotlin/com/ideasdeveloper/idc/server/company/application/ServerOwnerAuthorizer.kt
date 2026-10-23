package com.ideasdeveloper.idc.server.company.application

import com.ideasdeveloper.idc.server.auth.application.SessionService
import java.sql.Connection
import javax.sql.DataSource

class ServerOwnerAuthorizer(
    private val central: DataSource,
    private val sessions: SessionService,
) {
    fun serverOwnerRole(accessToken: String): String? {
        val user = sessions.validateSession(accessToken) ?: return null
        val isOwner = central.connection.use { connection ->
            connection.prepareStatement("SELECT 1 FROM server_owners WHERE user_id = ?").use { query ->
                query.setObject(1, java.util.UUID.fromString(user.id.toString()))
                query.executeQuery().use { rows -> rows.next() }
            }
        }
        return if (isOwner) user.postgresRole else null
    }
}

fun Connection.existsBySingleString(table: String, column: String, value: String): Boolean {
    prepareStatement("SELECT 1 FROM $table WHERE $column = ?").use { query ->
        query.setString(1, value)
        query.executeQuery().use { rows -> return rows.next() }
    }
}
