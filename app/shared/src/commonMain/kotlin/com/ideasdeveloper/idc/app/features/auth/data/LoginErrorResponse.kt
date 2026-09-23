package com.ideasdeveloper.idc.app.features.auth.data

import kotlinx.serialization.Serializable

@Serializable
data class LoginErrorResponse(
    val code: String,
    val message: String,
)
