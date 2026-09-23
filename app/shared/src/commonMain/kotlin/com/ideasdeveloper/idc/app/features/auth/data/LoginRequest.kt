package com.ideasdeveloper.idc.app.features.auth.data

import kotlinx.serialization.Serializable

@Serializable
class LoginRequest(
    val username: String,
    val password: String,
    val companyCode: String? = null,
)
