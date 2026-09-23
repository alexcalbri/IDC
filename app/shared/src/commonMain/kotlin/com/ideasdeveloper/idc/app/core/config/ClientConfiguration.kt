package com.ideasdeveloper.idc.app.core.config

data class ClientConfiguration(
    val serverUrl: String,
    val companyCode: String?,
) {
    val serverAdministration: Boolean = companyCode == null
}

expect object ClientConfigurationStore {
    fun load(): ClientConfiguration?
    fun save(configuration: ClientConfiguration)
    fun clear()
}
