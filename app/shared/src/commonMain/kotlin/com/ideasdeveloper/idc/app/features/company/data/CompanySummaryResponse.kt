package com.ideasdeveloper.idc.app.features.company.data

import kotlinx.serialization.Serializable

@Serializable
// Resumen que usa la pantalla de Empresa para listar tenants administrables.
data class CompanySummaryResponse(
    val code: String,
    val name: String,
    val databaseName: String,
    val isActive: Boolean,
    val modules: List<CompanyModuleResponse>,
    val logoUrl: String? = null,
    val primaryColor: String = "#667EEA",
    val secondaryColor: String = "#764BA2",
    val accentColor: String = "#FFFFFF",
)

@Serializable
// Estado de un modulo instalable o habilitable dentro de una empresa.
data class CompanyModuleResponse(
    val moduleId: String,
    val displayName: String,
    val enabled: Boolean,
    val locked: Boolean,
)

@Serializable
// Estado de un modulo instalado en el servidor y su uso en empresas.
data class ServerModuleResponse(
    val moduleId: String,
    val displayName: String,
    val description: String,
    val locked: Boolean,
    val activeCompanyCount: Int,
)

@Serializable
// Payload para activar o desactivar un modulo de empresa.
data class UpdateCompanyModuleRequest(
    val enabled: Boolean,
)

@Serializable
// Payload para cambiar el estado activo de una empresa.
data class UpdateCompanyStatusRequest(
    val active: Boolean,
)

@Serializable
// Payload para actualizar los datos administrables de una empresa.
data class UpdateCompanyRequest(
    val name: String,
    val logoUrl: String? = null,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
)
