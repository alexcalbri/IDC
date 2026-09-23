package com.ideasdeveloper.idc.app.core.company

import android.content.Context

actual object PersistentCompanyIdentityStore {
    private const val PreferencesName = "idc_company_identity"
    private const val IdKey = "id"
    private const val CodeKey = "code"
    private const val NameKey = "name"
    private const val LogoUrlKey = "logoUrl"
    private const val PrimaryColorKey = "primaryColor"
    private const val SecondaryColorKey = "secondaryColor"
    private const val AccentColorKey = "accentColor"

    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    actual fun load(): CompanyIdentity? {
        if (!::applicationContext.isInitialized) return null

        val preferences = applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        return CompanyIdentity(
            id = preferences.getString(IdKey, null)?.takeIf { it.isNotBlank() } ?: return null,
            code = preferences.getString(CodeKey, null)?.takeIf { it.isNotBlank() } ?: return null,
            name = preferences.getString(NameKey, null)?.takeIf { it.isNotBlank() } ?: return null,
            logoUrl = preferences.getString(LogoUrlKey, null)?.takeIf { it.isNotBlank() },
            primaryColor = preferences.getString(PrimaryColorKey, ServerOwnerIdentity.primaryColor) ?: ServerOwnerIdentity.primaryColor,
            secondaryColor = preferences.getString(SecondaryColorKey, ServerOwnerIdentity.secondaryColor) ?: ServerOwnerIdentity.secondaryColor,
            accentColor = preferences.getString(AccentColorKey, ServerOwnerIdentity.accentColor) ?: ServerOwnerIdentity.accentColor,
        )
    }

    actual fun save(identity: CompanyIdentity) {
        if (!::applicationContext.isInitialized) return

        applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(IdKey, identity.id)
            .putString(CodeKey, identity.code)
            .putString(NameKey, identity.name)
            .apply {
                identity.logoUrl?.let {
                    putString(LogoUrlKey, it)
                } ?: remove(LogoUrlKey)
            }
            .putString(PrimaryColorKey, identity.primaryColor)
            .putString(SecondaryColorKey, identity.secondaryColor)
            .putString(AccentColorKey, identity.accentColor)
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
