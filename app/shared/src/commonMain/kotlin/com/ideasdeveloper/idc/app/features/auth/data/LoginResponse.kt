package com.ideasdeveloper.idc.app.features.auth.data

import kotlinx.serialization.Serializable

@Serializable
// Respuesta de autenticacion que alimenta sesion, rol y modulos visibles.
class LoginResponse(
    val userId: String,
    val username: String,
    val accessToken: String,
    val expiresInSeconds: Long,
    val scope: String,
    val companyCode: String? = null,
    val role: String,
    val enabledModules: List<String> = emptyList(),
    // server_owner: codigo del espacio del servidor donde usa modulos sin una empresa.
    val workspaceCompanyCode: String? = null,
)
