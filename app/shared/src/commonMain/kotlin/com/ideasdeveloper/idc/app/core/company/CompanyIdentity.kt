package com.ideasdeveloper.idc.app.core.company

// Identidad visual que el cliente usa para pintar pantallas con marca de empresa.
data class CompanyIdentity(
    val id: String,
    val code: String,
    val name: String,
    val logoUrl: String?,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
)

// Identidad por defecto cuando la sesion pertenece al dueno del servidor.
val ServerOwnerIdentity = CompanyIdentity(
    id = "server",
    code = "server",
    name = "IdeasCore",
    logoUrl = null,
    primaryColor = "#667EEA",
    secondaryColor = "#764BA2",
    accentColor = "#FFFFFF",
)
