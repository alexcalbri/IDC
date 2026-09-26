package com.ideasdeveloper.idc.app.features.company.data

import kotlinx.serialization.Serializable

@Serializable
// Payload para crear un tenant con su identidad visual y usuario dueno inicial.
data class CreateCompanyRequest(
    val code: String,
    val name: String,
    val businessOwnerUsername: String,
    val businessOwnerPassword: String,
    val logoUrl: String? = null,
    val primaryColor: String = "#667EEA",
    val secondaryColor: String = "#764BA2",
    val accentColor: String = "#FFFFFF",
)
