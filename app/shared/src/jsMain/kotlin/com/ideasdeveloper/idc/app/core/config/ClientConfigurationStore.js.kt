package com.ideasdeveloper.idc.app.core.config

import web.storage.localStorage

actual object ClientConfigurationStore {
    private const val ServerUrlKey = "idc.serverUrl"
    private const val CompanyCodeKey = "idc.companyCode"

    actual fun load(): ClientConfiguration? {
        val serverUrl = localStorage.getItem(ServerUrlKey)?.takeIf { it.isNotBlank() } ?: return null
        val companyCode = localStorage.getItem(CompanyCodeKey)?.takeIf { it.isNotBlank() }
        return ClientConfiguration(serverUrl = serverUrl, companyCode = companyCode)
    }

    actual fun save(configuration: ClientConfiguration) {
        localStorage.setItem(ServerUrlKey, configuration.serverUrl)
        configuration.companyCode?.let {
            localStorage.setItem(CompanyCodeKey, it)
        } ?: localStorage.removeItem(CompanyCodeKey)
    }

    actual fun clear() {
        localStorage.removeItem(ServerUrlKey)
        localStorage.removeItem(CompanyCodeKey)
    }
}
