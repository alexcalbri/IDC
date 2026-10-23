package com.ideasdeveloper.idc.app.core.session

import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import web.storage.localStorage

actual object PersistentSessionStore {
    private const val UserIdKey = "idc.session.userId"
    private const val UsernameKey = "idc.session.username"
    private const val AccessTokenKey = "idc.session.accessToken"
    private const val ExpiresAtEpochSecondsKey = "idc.session.expiresAtEpochSeconds"
    private const val ScopeKey = "idc.session.scope"
    private const val CompanyCodeKey = "idc.session.companyCode"
    private const val RoleKey = "idc.session.role"
    private const val EnabledModulesKey = "idc.session.enabledModules"

    actual fun load(): LoginResponse? {
        val expiresAt = localStorage.getItem(ExpiresAtEpochSecondsKey)?.toLongOrNull() ?: 0L
        if (expiresAt <= nowEpochSeconds()) {
            clear()
            return null
        }

        return LoginResponse(
            userId = localStorage.getItem(UserIdKey)?.takeIf { it.isNotBlank() } ?: return null,
            username = localStorage.getItem(UsernameKey)?.takeIf { it.isNotBlank() } ?: return null,
            accessToken = localStorage.getItem(AccessTokenKey)?.takeIf { it.isNotBlank() } ?: return null,
            expiresInSeconds = expiresAt - nowEpochSeconds(),
            scope = localStorage.getItem(ScopeKey)?.takeIf { it.isNotBlank() } ?: return null,
            companyCode = localStorage.getItem(CompanyCodeKey)?.takeIf { it.isNotBlank() },
            role = localStorage.getItem(RoleKey)?.takeIf { it.isNotBlank() } ?: return null,
            enabledModules = localStorage.getItem(EnabledModulesKey)
                ?.split(",")
                ?.filter { it.isNotBlank() }
                .orEmpty(),
        )
    }

    actual fun save(response: LoginResponse) {
        localStorage.setItem(UserIdKey, response.userId)
        localStorage.setItem(UsernameKey, response.username)
        localStorage.setItem(AccessTokenKey, response.accessToken)
        localStorage.setItem(ExpiresAtEpochSecondsKey, (nowEpochSeconds() + response.expiresInSeconds).toString())
        localStorage.setItem(ScopeKey, response.scope)
        response.companyCode?.let {
            localStorage.setItem(CompanyCodeKey, it)
        } ?: localStorage.removeItem(CompanyCodeKey)
        localStorage.setItem(RoleKey, response.role)
        localStorage.setItem(EnabledModulesKey, response.enabledModules.joinToString(","))
    }

    actual fun clear() {
        localStorage.removeItem(UserIdKey)
        localStorage.removeItem(UsernameKey)
        localStorage.removeItem(AccessTokenKey)
        localStorage.removeItem(ExpiresAtEpochSecondsKey)
        localStorage.removeItem(ScopeKey)
        localStorage.removeItem(CompanyCodeKey)
        localStorage.removeItem(RoleKey)
        localStorage.removeItem(EnabledModulesKey)
    }

    private fun nowEpochSeconds(): Long = (js("Date.now()") as Double).toLong() / 1000L
}
