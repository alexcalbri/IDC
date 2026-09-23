package com.ideasdeveloper.idc.auth

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