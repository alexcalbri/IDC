package com.ideasdeveloper.idc.server.auth.domain

import kotlinx.serialization.Serializable

@Serializable
class LoginSuccessResponse(
    val userId: String,
    val username: String,
    val accessToken: String,
    val expiresInSeconds: Long,
    val scope: String = "company",
    val companyCode: String? = null,
    val role: String = "user",
    val enabledModules: List<String> = emptyList(),
)
