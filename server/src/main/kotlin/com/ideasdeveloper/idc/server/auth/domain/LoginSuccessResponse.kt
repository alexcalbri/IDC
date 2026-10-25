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
    // Server owners: company code of the server workspace where they use modules without a company.
    val workspaceCompanyCode: String? = null,
)
