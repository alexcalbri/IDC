package com.ideasdeveloper.idc.app.features.company.data

import kotlinx.serialization.Serializable

@Serializable
data class CompanySummaryResponse(
    val code: String,
    val name: String,
    val databaseName: String,
    val isActive: Boolean,
    val modules: List<CompanyModuleResponse>,
)

@Serializable
data class CompanyModuleResponse(
    val moduleId: String,
    val displayName: String,
    val enabled: Boolean,
    val locked: Boolean,
)

@Serializable
data class UpdateCompanyModuleRequest(
    val enabled: Boolean,
)
