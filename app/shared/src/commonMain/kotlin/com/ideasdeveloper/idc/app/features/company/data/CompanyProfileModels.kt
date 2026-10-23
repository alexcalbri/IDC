package com.ideasdeveloper.idc.app.features.company.data

import kotlinx.serialization.Serializable

@Serializable
// Perfil editable de la empresa que consume la pantalla business_owner.
data class CompanyProfileResponse(
    val code: String,
    val name: String,
    val logoUrl: String? = null,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
)

@Serializable
// Payload para actualizar nombre, logo y colores de la empresa actual.
data class UpdateCompanyProfileRequest(
    val name: String,
    val logoUrl: String? = null,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
)

@Serializable
// Representa un ZIP de backup disponible para descargar o eliminar.
data class CompanyBackupResponse(
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: String,
    val downloadUrl: String,
)

@Serializable
// Contenedor de la lista de ZIPs devuelta por el servidor.
data class CompanyBackupListResponse(
    val backups: List<CompanyBackupResponse>,
)

@Serializable
data class CompanyPermissionResponse(
    val permissionId: String,
    val moduleId: String,
    val title: String,
    val description: String,
)

@Serializable
data class CompanyUserResponse(
    val userId: String,
    val username: String,
    val isActive: Boolean,
    val permissions: List<String>,
)

@Serializable
data class CompanyUsersResponse(
    val users: List<CompanyUserResponse>,
    val permissions: List<CompanyPermissionResponse>,
)

@Serializable
data class CreateCompanyUserRequest(
    val username: String,
    val password: String,
    val permissions: List<String> = emptyList(),
)

@Serializable
data class UpdateCompanyUserPermissionsRequest(
    val permissions: List<String>,
)
