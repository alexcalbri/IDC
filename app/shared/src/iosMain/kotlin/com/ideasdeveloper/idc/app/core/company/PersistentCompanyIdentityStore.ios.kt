package com.ideasdeveloper.idc.app.core.company

import platform.Foundation.NSUserDefaults

actual object PersistentCompanyIdentityStore {
    private const val IdKey = "idc.companyIdentity.id"
    private const val CodeKey = "idc.companyIdentity.code"
    private const val NameKey = "idc.companyIdentity.name"
    private const val LogoUrlKey = "idc.companyIdentity.logoUrl"
    private const val PrimaryColorKey = "idc.companyIdentity.primaryColor"
    private const val SecondaryColorKey = "idc.companyIdentity.secondaryColor"
    private const val AccentColorKey = "idc.companyIdentity.accentColor"

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun load(): CompanyIdentity? {
        return CompanyIdentity(
            id = defaults.stringForKey(IdKey)?.takeIf { it.isNotBlank() } ?: return null,
            code = defaults.stringForKey(CodeKey)?.takeIf { it.isNotBlank() } ?: return null,
            name = defaults.stringForKey(NameKey)?.takeIf { it.isNotBlank() } ?: return null,
            logoUrl = defaults.stringForKey(LogoUrlKey)?.takeIf { it.isNotBlank() },
            primaryColor = defaults.stringForKey(PrimaryColorKey) ?: ServerOwnerIdentity.primaryColor,
            secondaryColor = defaults.stringForKey(SecondaryColorKey) ?: ServerOwnerIdentity.secondaryColor,
            accentColor = defaults.stringForKey(AccentColorKey) ?: ServerOwnerIdentity.accentColor,
        )
    }

    actual fun save(identity: CompanyIdentity) {
        defaults.setObject(identity.id, IdKey)
        defaults.setObject(identity.code, CodeKey)
        defaults.setObject(identity.name, NameKey)
        identity.logoUrl?.let {
            defaults.setObject(it, LogoUrlKey)
        } ?: defaults.removeObjectForKey(LogoUrlKey)
        defaults.setObject(identity.primaryColor, PrimaryColorKey)
        defaults.setObject(identity.secondaryColor, SecondaryColorKey)
        defaults.setObject(identity.accentColor, AccentColorKey)
    }

    actual fun clear() {
        defaults.removeObjectForKey(IdKey)
        defaults.removeObjectForKey(CodeKey)
        defaults.removeObjectForKey(NameKey)
        defaults.removeObjectForKey(LogoUrlKey)
        defaults.removeObjectForKey(PrimaryColorKey)
        defaults.removeObjectForKey(SecondaryColorKey)
        defaults.removeObjectForKey(AccentColorKey)
    }
}
