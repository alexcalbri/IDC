package com.ideasdeveloper.idc.app.core.session

import android.content.Context
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse

actual object PersistentSessionStore {
    private const val PreferencesName = "idc_session"
    private const val UserIdKey = "userId"
    private const val UsernameKey = "username"
    private const val AccessTokenKey = "accessToken"
    private const val ExpiresAtEpochSecondsKey = "expiresAtEpochSeconds"
    private const val ScopeKey = "scope"
    private const val CompanyCodeKey = "companyCode"
    private const val RoleKey = "role"
    private const val EnabledModulesKey = "enabledModules"

    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    actual fun load(): LoginResponse? {
        if (!::applicationContext.isInitialized) return null

        val preferences = applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val expiresAt = preferences.getLong(ExpiresAtEpochSecondsKey, 0L)
        if (expiresAt <= nowEpochSeconds()) {
            clear()
            return null
        }

        return LoginResponse(
            userId = preferences.getString(UserIdKey, null)?.takeIf { it.isNotBlank() } ?: return null,
            username = preferences.getString(UsernameKey, null)?.takeIf { it.isNotBlank() } ?: return null,
            accessToken = preferences.getString(AccessTokenKey, null)?.takeIf { it.isNotBlank() } ?: return null,
            expiresInSeconds = expiresAt - nowEpochSeconds(),
            scope = preferences.getString(ScopeKey, null)?.takeIf { it.isNotBlank() } ?: return null,
            companyCode = preferences.getString(CompanyCodeKey, null)?.takeIf { it.isNotBlank() },
            role = preferences.getString(RoleKey, null)?.takeIf { it.isNotBlank() } ?: return null,
            enabledModules = preferences.getString(EnabledModulesKey, null)
                ?.split(",")
                ?.filter { it.isNotBlank() }
                .orEmpty(),
        )
    }

    actual fun save(response: LoginResponse) {
        if (!::applicationContext.isInitialized) return

        applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(UserIdKey, response.userId)
            .putString(UsernameKey, response.username)
            .putString(AccessTokenKey, response.accessToken)
            .putLong(ExpiresAtEpochSecondsKey, nowEpochSeconds() + response.expiresInSeconds)
            .putString(ScopeKey, response.scope)
            .apply {
                response.companyCode?.let {
                    putString(CompanyCodeKey, it)
                } ?: remove(CompanyCodeKey)
            }
            .putString(RoleKey, response.role)
            .putString(EnabledModulesKey, response.enabledModules.joinToString(","))
            .apply()
    }

    actual fun clear() {
        if (!::applicationContext.isInitialized) return

        applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L
}
