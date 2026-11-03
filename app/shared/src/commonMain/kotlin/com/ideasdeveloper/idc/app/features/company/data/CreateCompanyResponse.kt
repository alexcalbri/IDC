package com.ideasdeveloper.idc.app.features.company.data

import kotlinx.serialization.Serializable

@Serializable
// Respuesta de creacion de tenant usada para confirmar empresa y modulos iniciales.
data class CreateCompanyResponse(
    val id: String,
    val code: String,
    val name: String,
    val databaseName: String,
    val businessOwnerUsername: String,
    val enabledModules: List<String>,
    // Al duplicar: modulos cuya configuracion no se pudo copiar (la empresa se creo igual).
    val settingsNotCopied: List<String> = emptyList(),
)

// Una empresa a la que la sesion puede cambiar desde el panel.
@Serializable
data class OwnedCompany(val code: String, val name: String)

// serverName viene solo para el dueno del servidor (puede volver al servidor); current es null en el servidor.
@Serializable
data class SwitchTargetsResponse(val serverName: String? = null, val current: String? = null, val companies: List<OwnedCompany> = emptyList())

@Serializable
data class ServerNameRequest(val name: String)

@Serializable
data class ServerNameResponse(val name: String)

// Sin newOwner la copia mantiene el business owner de la empresa original.
@Serializable
data class DuplicateCompanyRequest(val code: String, val newOwner: DuplicateOwnerRequest? = null)

@Serializable
data class DuplicateOwnerRequest(val username: String, val password: String, val name: String, val email: String)

// targetCode null saca a la empresa de su grupo.
@Serializable
data class LinkCompanyRequest(val targetCode: String? = null)

// companyCode null: volver al servidor (solo el dueno del servidor).
@Serializable
data class SwitchCompanyRequest(val companyCode: String? = null)
