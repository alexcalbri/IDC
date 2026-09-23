package com.ideasdeveloper.idc.server.auth.domain

import kotlinx.serialization.Serializable

@Serializable
class LoginCredentials(
    val username: String,
    val password: String,
    val companyCode: String? = null,
)
