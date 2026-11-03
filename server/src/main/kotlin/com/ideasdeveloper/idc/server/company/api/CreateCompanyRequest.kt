package com.ideasdeveloper.idc.server.company.api

import kotlinx.serialization.Serializable

@Serializable
data class CreateCompanyRequest(
    val code: String,
    val name: String,
    val businessOwnerUsername: String,
    val businessOwnerPassword: String,
    // Required: changes are recorded with the owner's name, and the email receives password reset codes.
    val businessOwnerName: String = "",
    val businessOwnerEmail: String = "",
    val logoUrl: String? = null,
    val primaryColor: String = "#667EEA",
    val secondaryColor: String = "#764BA2",
    val accentColor: String = "#FFFFFF",
)
