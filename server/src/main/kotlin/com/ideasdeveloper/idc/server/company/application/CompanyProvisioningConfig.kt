package com.ideasdeveloper.idc.server.company.application

data class CompanyProvisioningConfig(
    val administrationJdbcUrl: String,
    val runtimeUser: String,
    val runtimePassword: String,
    val tenantJdbcUrlPrefix: String,
    val migrationsRoot: String,
)
