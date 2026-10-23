package com.ideasdeveloper.idc.app.core.config

import java.util.prefs.Preferences

actual object ClientConfigurationStore {
    private const val ServerUrlKey = "serverUrl"
    private const val CompanyCodeKey = "companyCode"

    private val preferences = Preferences.userRoot().node("com/ideasdeveloper/idc/client")

    actual fun load(): ClientConfiguration? {
        val serverUrl = preferences.get(ServerUrlKey, "").takeIf { it.isNotBlank() } ?: return null
        val companyCode = preferences.get(CompanyCodeKey, "").takeIf { it.isNotBlank() }
        return ClientConfiguration(serverUrl = serverUrl, companyCode = companyCode)
    }

    actual fun save(configuration: ClientConfiguration) {
        preferences.put(ServerUrlKey, configuration.serverUrl)
        configuration.companyCode?.let {
            preferences.put(CompanyCodeKey, it)
        } ?: preferences.remove(CompanyCodeKey)
    }

    actual fun clear() {
        preferences.remove(ServerUrlKey)
        preferences.remove(CompanyCodeKey)
    }
}
