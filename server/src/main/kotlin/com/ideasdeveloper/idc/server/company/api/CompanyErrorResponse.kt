package com.ideasdeveloper.idc.server.company.api

import kotlinx.serialization.Serializable

@Serializable
data class CompanyErrorResponse(
    val code: String,
    val message: String,
)
