package com.ideasdeveloper.idc.server.auth.application

import com.ideasdeveloper.idc.server.auth.domain.ApplicationSession
import com.ideasdeveloper.idc.server.auth.domain.ApplicationUser
import com.ideasdeveloper.idc.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.idc.server.auth.infrastructure.database.ApplicationSessionRepository
import com.ideasdeveloper.idc.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

class SessionService(
    private val sessionRepository: ApplicationSessionRepository,
    private val userRepository: ApplicationUserRepository,
    private val tokenGenerator: SessionTokenGenerator,
    private val sessionLifetimeSeconds: Long,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")

    init {
        require(sessionLifetimeSeconds > 0) {
            "Session lifetime must be positive"
        }
    }

    fun createSession(user: ApplicationUser): LoginSuccessResponse {
        require(user.isActive) {
            "Cannot create a session for an inactive user"
        }

        val generatedToken = tokenGenerator.generate()
        val now = OffsetDateTime.now(clock)
        val expiresAt = now.plusSeconds(sessionLifetimeSeconds)

        sessionRepository.create(
            ApplicationSession(
                tokenHash = generatedToken.hash,
                userId = user.id,
                createdAt = now,
                expiresAt = expiresAt,
                revokedAt = null,
            )
        )

        val remainingSeconds = Duration.between(
            clock.instant(),
            expiresAt.toInstant(),
        ).seconds.coerceAtLeast(0)

        return LoginSuccessResponse(
            userId = user.id.toString(),
            username = user.postgresRole,
            accessToken = generatedToken.value,
            expiresInSeconds = remainingSeconds,
        )
    }

    fun validateSession(token: String): ApplicationUser? {
        if (!tokenPattern.matches(token)) {
            return null
        }

        val session = sessionRepository.findByTokenHash(
            tokenGenerator.hash(token),
        ) ?: return null

        if (session.revokedAt != null) {
            return null
        }

        if (!session.expiresAt.isAfter(OffsetDateTime.now(clock))) {
            return null
        }

        val user = userRepository.findById(session.userId)
            ?: return null

        if (!user.isActive) {
            return null
        }

        return user
    }

    fun revokeSession(token: String): Boolean {
        if (!tokenPattern.matches(token)) {
            return false
        }

        return sessionRepository.revoke(
            tokenHash = tokenGenerator.hash(token),
            revokedAt = OffsetDateTime.now(clock),
        )
    }

    fun revokeAllForUser(userId: Uuid): Int {
        return sessionRepository.revokeAllForUser(
            userId = userId,
            revokedAt = OffsetDateTime.now(clock),
        )
    }
}
