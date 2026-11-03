package com.ideasdeveloper.idc.app.features.auth.data

import kotlinx.serialization.Serializable

@Serializable
// Respuesta de autenticacion que alimenta sesion, rol y modulos visibles.
data class LoginResponse(
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
    val displayName: String? = null,
    val email: String? = null,
    // Falso hasta que el usuario tenga nombre y correo; la app los pide antes de continuar.
    val profileComplete: Boolean = true,
)
