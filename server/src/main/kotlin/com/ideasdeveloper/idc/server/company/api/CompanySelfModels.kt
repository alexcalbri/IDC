package com.ideasdeveloper.idc.server.company.api

import kotlinx.serialization.Serializable

@Serializable
// Perfil editable de empresa expuesto al business_owner.
data class CompanyProfileResponse(
    val code: String,
    val name: String,
    val logoUrl: String?,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
)

@Serializable
// Payload usado por el business_owner para cambiar datos visuales de su empresa.
data class UpdateCompanyProfileRequest(
    val name: String,
    val logoUrl: String? = null,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
)

@Serializable
// Describe un archivo ZIP disponible en el almacenamiento de backups de la empresa.
data class CompanyBackupResponse(
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: String,
    val downloadUrl: String,
)

@Serializable
// Respuesta de listado para los ZIPs de una empresa.
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
