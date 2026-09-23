package com.ideasdeveloper.idc.app.core.session

import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import java.util.prefs.Preferences

actual object PersistentSessionStore {
    private const val UserIdKey = "userId"
    private const val UsernameKey = "username"
    private const val AccessTokenKey = "accessToken"
    private const val ExpiresAtEpochSecondsKey = "expiresAtEpochSeconds"
    private const val ScopeKey = "scope"
    private const val CompanyCodeKey = "companyCode"
    private const val RoleKey = "role"

    private val preferences = Preferences.userRoot().node("com/ideasdeveloper/idc/session")

    actual fun load(): LoginResponse? {
        val expiresAt = preferences.getLong(ExpiresAtEpochSecondsKey, 0L)
        if (expiresAt <= nowEpochSeconds()) {
            clear()
            return null
        }

        return LoginResponse(
            userId = preferences.get(UserIdKey, "").takeIf { it.isNotBlank() } ?: return null,
            username = preferences.get(UsernameKey, "").takeIf { it.isNotBlank() } ?: return null,
            accessToken = preferences.get(AccessTokenKey, "").takeIf { it.isNotBlank() } ?: return null,
            expiresInSeconds = expiresAt - nowEpochSeconds(),
            scope = preferences.get(ScopeKey, "").takeIf { it.isNotBlank() } ?: return null,
            companyCode = preferences.get(CompanyCodeKey, "").takeIf { it.isNotBlank() },
            role = preferences.get(RoleKey, "").takeIf { it.isNotBlank() } ?: return null,
        )
    }

    actual fun save(response: LoginResponse) {
        preferences.put(UserIdKey, response.userId)
        preferences.put(UsernameKey, response.username)
        preferences.put(AccessTokenKey, response.accessToken)
        preferences.putLong(ExpiresAtEpochSecondsKey, nowEpochSeconds() + response.expiresInSeconds)
        preferences.put(ScopeKey, response.scope)
        response.companyCode?.let {
            preferences.put(CompanyCodeKey, it)
        } ?: preferences.remove(CompanyCodeKey)
        preferences.put(RoleKey, response.role)
    }

    actual fun clear() {
        preferences.remove(UserIdKey)
        preferences.remove(UsernameKey)
        preferences.remove(AccessTokenKey)
        preferences.remove(ExpiresAtEpochSecondsKey)
        preferences.remove(ScopeKey)
        preferences.remove(CompanyCodeKey)
        preferences.remove(RoleKey)
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L
}
