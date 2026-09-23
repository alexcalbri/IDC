package com.ideasdeveloper.idc.auth

import kotlinx.serialization.Serializable

@Serializable
class LoginCredentials(
    val username: String,
    val password: String,
    val companyCode: String? = null,
)
