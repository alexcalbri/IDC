package com.ideasdeveloper.idc.app.core.company

data class CompanyIdentity(
    val id: String,
    val code: String,
    val name: String,
    val logoUrl: String?,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
)

val ServerOwnerIdentity = CompanyIdentity(
    id = "server",
    code = "server",
    name = "IdeasCore",
    logoUrl = null,
    primaryColor = "#667EEA",
    secondaryColor = "#764BA2",
    accentColor = "#FFFFFF",
)
