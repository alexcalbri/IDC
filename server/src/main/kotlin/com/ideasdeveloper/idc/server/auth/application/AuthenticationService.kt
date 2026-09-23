package com.ideasdeveloper.idc.server.auth.application

import com.ideasdeveloper.idc.server.auth.domain.ApplicationUser
import com.ideasdeveloper.idc.server.auth.domain.LoginCredentials
import com.ideasdeveloper.idc.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.idc.server.auth.infrastructure.security.PostgresCredentialVerifier

class AuthenticationService(
    private val credentialVerifier: PostgresCredentialVerifier,
    private val userRepository: ApplicationUserRepository,
) {

    fun authenticate(credentials: LoginCredentials): ApplicationUser? {
        if (!credentialVerifier.verify(credentials)) {
            return null
        }

        val user = userRepository.findByPostgresRole(
            credentials.username,
        ) ?: return null

        if (!user.isActive) {
            return null
        }

        return user
    }
}
