package com.ideasdeveloper.idc.auth

import org.postgresql.ds.PGSimpleDataSource
import java.sql.SQLException

class PostgresCredentialVerifier(jdbcUrl: String) {

    private val dataSource = PGSimpleDataSource().apply {
        setURL(jdbcUrl)
        connectTimeout = 5
        socketTimeout = 5
    }

    fun verify(credentials: LoginCredentials): Boolean {
        if (
            credentials.username.isBlank() ||
            credentials.password.isEmpty()
        ) {
            return false
        }

        return try {
            dataSource.getConnection(
                credentials.username,
                credentials.password,
            ).use {
                true
            }
        } catch (exception: SQLException) {
            if (exception.sqlState == "28P01" || exception.sqlState == "42501") {
                false
            } else {
                throw exception
            }
        }
    }
}
