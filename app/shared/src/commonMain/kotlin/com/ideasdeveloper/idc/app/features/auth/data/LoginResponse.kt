package com.ideasdeveloper.idc.app.features.auth.data

import kotlinx.serialization.Serializable

@Serializable
class LoginResponse(
    val userId: String,
    val username: String,
    val accessToken: String,
    val expiresInSeconds: Long,
    val scope: String,
    val companyCode: String? = null,
    val role: String,
    val enabledModules: List<String> = emptyList(),
)
