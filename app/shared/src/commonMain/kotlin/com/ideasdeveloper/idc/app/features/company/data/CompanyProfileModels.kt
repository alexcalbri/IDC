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
