package com.ideasdeveloper.idc.app.features.company.data

import kotlinx.serialization.Serializable

@Serializable
data class CreateCompanyResponse(
    val id: String,
    val code: String,
    val name: String,
    val databaseName: String,
    val businessOwnerUsername: String,
    val enabledModules: List<String>,
)
