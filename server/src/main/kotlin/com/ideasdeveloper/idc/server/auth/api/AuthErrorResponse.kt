package com.ideasdeveloper.idc.server.auth.api

import kotlinx.serialization.Serializable

@Serializable
data class AuthErrorResponse(
    val code: String,
    val message: String,
)