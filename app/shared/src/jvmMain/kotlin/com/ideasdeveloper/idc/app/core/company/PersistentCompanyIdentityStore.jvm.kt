package com.ideasdeveloper.idc.app.core.company

import java.util.prefs.Preferences

actual object PersistentCompanyIdentityStore {
    private const val IdKey = "id"
    private const val CodeKey = "code"
    private const val NameKey = "name"
    private const val LogoUrlKey = "logoUrl"
    private const val PrimaryColorKey = "primaryColor"
    private const val SecondaryColorKey = "secondaryColor"
    private const val AccentColorKey = "accentColor"

    private val preferences = Preferences.userRoot().node("com/ideasdeveloper/idc/company")

    actual fun load(): CompanyIdentity? {
        return CompanyIdentity(
            id = preferences.get(IdKey, "").takeIf { it.isNotBlank() } ?: return null,
            code = preferences.get(CodeKey, "").takeIf { it.isNotBlank() } ?: return null,
            name = preferences.get(NameKey, "").takeIf { it.isNotBlank() } ?: return null,
            logoUrl = preferences.get(LogoUrlKey, "").takeIf { it.isNotBlank() },
            primaryColor = preferences.get(PrimaryColorKey, ServerOwnerIdentity.primaryColor),
            secondaryColor = preferences.get(SecondaryColorKey, ServerOwnerIdentity.secondaryColor),
            accentColor = preferences.get(AccentColorKey, ServerOwnerIdentity.accentColor),
        )
    }

    actual fun save(identity: CompanyIdentity) {
        preferences.put(IdKey, identity.id)
        preferences.put(CodeKey, identity.code)
        preferences.put(NameKey, identity.name)
        identity.logoUrl?.let {
            preferences.put(LogoUrlKey, it)
        } ?: preferences.remove(LogoUrlKey)
        preferences.put(PrimaryColorKey, identity.primaryColor)
        preferences.put(SecondaryColorKey, identity.secondaryColor)
        preferences.put(AccentColorKey, identity.accentColor)
    }

    actual fun clear() {
        preferences.remove(IdKey)
        preferences.remove(CodeKey)
        preferences.remove(NameKey)
        preferences.remove(LogoUrlKey)
        preferences.remove(PrimaryColorKey)
        preferences.remove(SecondaryColorKey)
        preferences.remove(AccentColorKey)
    }
}
