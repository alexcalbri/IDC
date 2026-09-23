package com.ideasdeveloper.idc.auth

fun interface LoginScope {
    fun login(credentials: LoginCredentials): LoginSuccessResponse?
}

/** Company codes are lookup keys, never database names or connection URLs. */
class ScopedLoginService(
    private val serverScope: LoginScope,
    private val findActiveDatabase: (String) -> String?,
    private val companyScope: (String) -> LoginScope?,
) {
    fun login(credentials: LoginCredentials): LoginSuccessResponse? {
        val code = credentials.companyCode ?: return serverScope.login(credentials)
        if (!code.matches(Regex("[a-z][a-z0-9_]{0,62}"))) return null
        val database = findActiveDatabase(code) ?: return null
        return companyScope(database)?.login(credentials)
    }
}
