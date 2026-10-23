package com.ideasdeveloper.idc.modules.empresa

object EmpresaModule {
    const val id: String = "empresa"
    const val displayName: String = "Empresa"
    val visibleDashboardRoles: Set<String> = setOf("server_owner", "business_owner")
}
