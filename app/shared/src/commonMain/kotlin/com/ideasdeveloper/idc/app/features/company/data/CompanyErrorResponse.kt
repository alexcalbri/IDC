package com.ideasdeveloper.idc.app.features.company.data

import kotlinx.serialization.Serializable

@Serializable
data class CompanyErrorResponse(
    val code: String,
    val message: String,
)
