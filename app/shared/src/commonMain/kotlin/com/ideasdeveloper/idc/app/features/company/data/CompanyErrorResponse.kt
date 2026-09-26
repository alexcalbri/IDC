package com.ideasdeveloper.idc.app.features.company.data

import kotlinx.serialization.Serializable

@Serializable
// Error estructurado que el servidor devuelve para operaciones de empresa.
data class CompanyErrorResponse(
    val code: String,
    val message: String,
)
