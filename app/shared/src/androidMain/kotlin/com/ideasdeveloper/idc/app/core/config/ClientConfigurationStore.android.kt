package com.ideasdeveloper.idc.app.core.config

import android.content.Context

actual object ClientConfigurationStore {
    private const val PreferencesName = "idc_client_configuration"
    private const val ServerUrlKey = "serverUrl"
    private const val CompanyCodeKey = "companyCode"

    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    actual fun load(): ClientConfiguration? {
        if (!::applicationContext.isInitialized) return null

        val preferences = applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val serverUrl = preferences.getString(ServerUrlKey, null)?.takeIf { it.isNotBlank() } ?: return null
        val companyCode = preferences.getString(CompanyCodeKey, null)?.takeIf { it.isNotBlank() }
        return ClientConfiguration(serverUrl = serverUrl, companyCode = companyCode)
    }

    actual fun save(configuration: ClientConfiguration) {
        if (!::applicationContext.isInitialized) return

        applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(ServerUrlKey, configuration.serverUrl)
            .apply {
                configuration.companyCode?.let {
                    putString(CompanyCodeKey, it)
                } ?: remove(CompanyCodeKey)
            }
            .apply()
    }

    actual fun clear() {
        if (!::applicationContext.isInitialized) return

        applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
