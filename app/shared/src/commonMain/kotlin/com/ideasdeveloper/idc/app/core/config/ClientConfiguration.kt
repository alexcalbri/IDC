package com.ideasdeveloper.idc.app.core.config

// Guarda los datos minimos que el cliente necesita para volver al mismo servidor.
data class ClientConfiguration(
    val serverUrl: String,
    val companyCode: String?,
) {
    // Un codigo nulo indica que el login apunta a la administracion global del servidor.
    val serverAdministration: Boolean = companyCode == null
}

// Plataforma concreta para guardar configuracion local en cada target de KMP.
expect object ClientConfigurationStore {
    fun load(): ClientConfiguration?
    fun save(configuration: ClientConfiguration)
    fun clear()
}
