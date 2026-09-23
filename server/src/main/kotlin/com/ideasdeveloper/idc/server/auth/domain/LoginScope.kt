package com.ideasdeveloper.idc.server.auth.domain

fun interface LoginScope {
    fun login(credentials: LoginCredentials): LoginSuccessResponse?
}
