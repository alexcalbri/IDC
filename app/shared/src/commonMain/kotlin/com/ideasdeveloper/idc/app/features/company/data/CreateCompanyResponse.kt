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

// Una empresa del mismo business owner, para cambiar entre ellas desde el panel.
@Serializable
data class OwnedCompany(val code: String, val name: String)

@Serializable
data class OwnedCompaniesResponse(val current: String, val companies: List<OwnedCompany>)

// Sin newOwner la copia mantiene el business owner de la empresa original.
@Serializable
data class DuplicateCompanyRequest(val code: String, val newOwner: DuplicateOwnerRequest? = null)

@Serializable
data class DuplicateOwnerRequest(val username: String, val password: String, val name: String, val email: String)

// targetCode null saca a la empresa de su grupo.
@Serializable
data class LinkCompanyRequest(val targetCode: String? = null)

@Serializable
data class SwitchCompanyRequest(val companyCode: String)
