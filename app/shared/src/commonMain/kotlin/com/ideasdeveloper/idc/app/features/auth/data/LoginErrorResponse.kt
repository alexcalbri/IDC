package com.ideasdeveloper.idc.app.features.auth.data

import kotlinx.serialization.Serializable

@Serializable
// Error estructurado que el servidor devuelve cuando falla el login.
data class LoginErrorResponse(
    val code: String,
    val message: String,
)
