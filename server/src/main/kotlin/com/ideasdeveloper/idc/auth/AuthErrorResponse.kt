package com.ideasdeveloper.idc.auth

import kotlinx.serialization.Serializable

@Serializable
data class AuthErrorResponse(
    val code: String,
    val message: String,
)