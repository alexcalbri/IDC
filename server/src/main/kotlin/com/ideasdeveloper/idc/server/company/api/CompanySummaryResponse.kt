package com.ideasdeveloper.idc.server.company.api

import kotlinx.serialization.Serializable

@Serializable
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
data class CompanyModuleResponse(
    val moduleId: String,
    val displayName: String,
    val enabled: Boolean,
    val locked: Boolean,
)

@Serializable
data class ServerModuleResponse(
    val moduleId: String,
    val displayName: String,
    val description: String,
    val version: String,
    val latestVersion: String? = null,
    val locked: Boolean,
    val installed: Boolean,
    val activeCompanyCount: Int,
    val packageUrl: String? = null,
    val packageSha256: String? = null,
    val installedPackagePath: String? = null,
)

@Serializable
data class ServerModuleCatalogResponse(
    val catalogUrl: String,
)

@Serializable
data class UpdateServerModuleCatalogRequest(
    val catalogUrl: String,
)

@Serializable
data class UpdateCompanyModuleRequest(
    val enabled: Boolean,
)

@Serializable
data class UpdateCompanyStatusRequest(
    val active: Boolean,
)

@Serializable
data class UpdateCompanyRequest(
    val name: String,
    val logoUrl: String? = null,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
)
