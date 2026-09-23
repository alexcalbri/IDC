package com.ideasdeveloper.idc.app.core.company

import web.storage.localStorage

actual object PersistentCompanyIdentityStore {
    private const val IdKey = "idc.companyIdentity.id"
    private const val CodeKey = "idc.companyIdentity.code"
    private const val NameKey = "idc.companyIdentity.name"
    private const val LogoUrlKey = "idc.companyIdentity.logoUrl"
    private const val PrimaryColorKey = "idc.companyIdentity.primaryColor"
    private const val SecondaryColorKey = "idc.companyIdentity.secondaryColor"
    private const val AccentColorKey = "idc.companyIdentity.accentColor"

    actual fun load(): CompanyIdentity? {
        return CompanyIdentity(
            id = localStorage.getItem(IdKey)?.takeIf { it.isNotBlank() } ?: return null,
            code = localStorage.getItem(CodeKey)?.takeIf { it.isNotBlank() } ?: return null,
            name = localStorage.getItem(NameKey)?.takeIf { it.isNotBlank() } ?: return null,
            logoUrl = localStorage.getItem(LogoUrlKey)?.takeIf { it.isNotBlank() },
            primaryColor = localStorage.getItem(PrimaryColorKey) ?: ServerOwnerIdentity.primaryColor,
            secondaryColor = localStorage.getItem(SecondaryColorKey) ?: ServerOwnerIdentity.secondaryColor,
            accentColor = localStorage.getItem(AccentColorKey) ?: ServerOwnerIdentity.accentColor,
        )
    }

    actual fun save(identity: CompanyIdentity) {
        localStorage.setItem(IdKey, identity.id)
        localStorage.setItem(CodeKey, identity.code)
        localStorage.setItem(NameKey, identity.name)
        identity.logoUrl?.let {
            localStorage.setItem(LogoUrlKey, it)
        } ?: localStorage.removeItem(LogoUrlKey)
        localStorage.setItem(PrimaryColorKey, identity.primaryColor)
        localStorage.setItem(SecondaryColorKey, identity.secondaryColor)
        localStorage.setItem(AccentColorKey, identity.accentColor)
    }

    actual fun clear() {
        localStorage.removeItem(IdKey)
        localStorage.removeItem(CodeKey)
        localStorage.removeItem(NameKey)
        localStorage.removeItem(LogoUrlKey)
        localStorage.removeItem(PrimaryColorKey)
        localStorage.removeItem(SecondaryColorKey)
        localStorage.removeItem(AccentColorKey)
    }
}
