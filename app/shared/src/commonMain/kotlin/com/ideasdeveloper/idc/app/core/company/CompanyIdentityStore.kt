package com.ideasdeveloper.idc.app.core.company

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CompanyIdentityStore {
    private val _identity = MutableStateFlow<CompanyIdentity?>(null)
    val identity = _identity.asStateFlow()

    fun current(): CompanyIdentity = _identity.value ?: ServerOwnerIdentity

    fun save(identity: CompanyIdentity) {
        _identity.value = identity
        PersistentCompanyIdentityStore.save(identity)
    }

    fun restore(): CompanyIdentity? {
        val restored = PersistentCompanyIdentityStore.load()
        _identity.value = restored
        return restored
    }

    fun clear() {
        _identity.value = null
        PersistentCompanyIdentityStore.clear()
    }
}

expect object PersistentCompanyIdentityStore {
    fun load(): CompanyIdentity?
    fun save(identity: CompanyIdentity)
    fun clear()
}
