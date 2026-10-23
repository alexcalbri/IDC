package com.ideasdeveloper.idc.app.features.auth.data

import kotlinx.serialization.Serializable

@Serializable
// Payload que envia el cliente al servidor para autenticar una sesion.
class LoginRequest(
    val username: String,
    val password: String,
    val companyCode: String? = null,
)
