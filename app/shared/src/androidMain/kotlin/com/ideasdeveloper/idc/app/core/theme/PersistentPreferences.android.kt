package com.ideasdeveloper.idc.app.core.theme

import android.content.Context

actual object PersistentPreferences {
    private const val PreferencesName = "idc_preferences"

    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    actual fun get(key: String): String? {
        if (!::applicationContext.isInitialized) return null
        return applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).getString(key, null)
    }

    actual fun set(key: String, value: String) {
        if (!::applicationContext.isInitialized) return
        applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }
}
