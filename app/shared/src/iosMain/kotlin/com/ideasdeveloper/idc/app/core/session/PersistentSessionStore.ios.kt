package com.ideasdeveloper.idc.app.core.session

import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults

actual object PersistentSessionStore {
    private const val UserIdKey = "idc.session.userId"
    private const val UsernameKey = "idc.session.username"
    private const val AccessTokenKey = "idc.session.accessToken"
    private const val ExpiresAtEpochSecondsKey = "idc.session.expiresAtEpochSeconds"
    private const val ScopeKey = "idc.session.scope"
    private const val CompanyCodeKey = "idc.session.companyCode"
    private const val RoleKey = "idc.session.role"

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun load(): LoginResponse? {
        val expiresAt = defaults.stringForKey(ExpiresAtEpochSecondsKey)?.toLongOrNull() ?: 0L
        if (expiresAt <= nowEpochSeconds()) {
            clear()
            return null
        }

        return LoginResponse(
            userId = defaults.stringForKey(UserIdKey)?.takeIf { it.isNotBlank() } ?: return null,
            username = defaults.stringForKey(UsernameKey)?.takeIf { it.isNotBlank() } ?: return null,
            accessToken = defaults.stringForKey(AccessTokenKey)?.takeIf { it.isNotBlank() } ?: return null,
            expiresInSeconds = expiresAt - nowEpochSeconds(),
            scope = defaults.stringForKey(ScopeKey)?.takeIf { it.isNotBlank() } ?: return null,
            companyCode = defaults.stringForKey(CompanyCodeKey)?.takeIf { it.isNotBlank() },
            role = defaults.stringForKey(RoleKey)?.takeIf { it.isNotBlank() } ?: return null,
        )
    }

    actual fun save(response: LoginResponse) {
        defaults.setObject(response.userId, UserIdKey)
        defaults.setObject(response.username, UsernameKey)
        defaults.setObject(response.accessToken, AccessTokenKey)
        defaults.setObject((nowEpochSeconds() + response.expiresInSeconds).toString(), ExpiresAtEpochSecondsKey)
        defaults.setObject(response.scope, ScopeKey)
        response.companyCode?.let {
            defaults.setObject(it, CompanyCodeKey)
        } ?: defaults.removeObjectForKey(CompanyCodeKey)
        defaults.setObject(response.role, RoleKey)
    }

    actual fun clear() {
        defaults.removeObjectForKey(UserIdKey)
        defaults.removeObjectForKey(UsernameKey)
        defaults.removeObjectForKey(AccessTokenKey)
        defaults.removeObjectForKey(ExpiresAtEpochSecondsKey)
        defaults.removeObjectForKey(ScopeKey)
        defaults.removeObjectForKey(CompanyCodeKey)
        defaults.removeObjectForKey(RoleKey)
    }

    private fun nowEpochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()
}
