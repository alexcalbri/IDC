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
// Configuracion SMTP de la empresa; la contrasena nunca vuelve del servidor, solo si existe.
data class CompanyMailForm(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    val from: String = "",
    // starttls, ssl o none.
    val security: String = "starttls",
    val passwordSet: Boolean = false,
)

@Serializable
// Payload para guardar el SMTP. password null conserva la guardada, "" la borra y un valor la reemplaza.
// Con el Json del cliente (encodeDefaults apagado) el null se omite del cuerpo, que es lo que pide el contrato.
data class CompanyMailInput(
    val host: String,
    val port: Int,
    val username: String,
    val from: String,
    val security: String,
    val password: String? = null,
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
    val displayName: String? = null,
    val email: String? = null,
    val userCode: String = "",
    // The company's business owner: no permissions to choose and always active.
    val isBusinessOwner: Boolean = false,
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
    val displayName: String = "",
    val email: String = "",
)

@Serializable
data class UpdateCompanyUserRequest(
    val username: String,
    val displayName: String,
    val email: String,
    val isActive: Boolean = true,
    val permissions: List<String> = emptyList(),
    // Blank keeps the current password.
    val password: String = "",
    // Solo si el business owner cambia su propio correo; nunca se guarda ni se registra.
    val currentPassword: String? = null,
)

@Serializable
data class UpdateCompanyUserPermissionsRequest(
    val permissions: List<String>,
)

// Empresa del servidor que administra el server_owner sin crear una empresa.
@Serializable
data class ServerWorkspaceResponse(
    val code: String,
)

@Serializable
data class ServerErrorLogEntry(
    val timestamp: String,
    val logger: String,
    val message: String,
    val details: String = "",
)

@Serializable
data class ServerLogsResponse(val errors: List<ServerErrorLogEntry>)

// Solicitud de actualizacion del Core (la procesa el ayudante root del servidor).
@Serializable
data class CoreUpdateRequestDto(
    val id: Long,
    val targetVersion: String,
    val fromVersion: String,
    val status: String,
    val requestedAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val message: String? = null,
    val logTail: String? = null,
    val stalePending: Boolean = false,
)

@Serializable
data class CoreUpdateStatusResponse(
    val currentVersion: String,
    val latestVersion: String? = null,
    val updateAvailable: Boolean,
    val request: CoreUpdateRequestDto? = null,
)
