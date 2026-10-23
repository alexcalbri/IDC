package com.ideasdeveloper.idc.app.core.company

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CompanyIdentityStore {
    // Mantiene la identidad visual activa para pintar pantallas con la marca de la empresa.
    private val _identity = MutableStateFlow<CompanyIdentity?>(null)
    val identity = _identity.asStateFlow()

    // Devuelve la identidad de empresa o la identidad base del dueno del servidor.
    fun current(): CompanyIdentity = _identity.value ?: ServerOwnerIdentity

    // Actualiza la identidad visual en memoria y en almacenamiento persistente.
    fun save(identity: CompanyIdentity) {
        _identity.value = identity
        PersistentCompanyIdentityStore.save(identity)
    }

    // Recupera la identidad visual guardada al iniciar la app.
    fun restore(): CompanyIdentity? {
        val restored = PersistentCompanyIdentityStore.load()
        _identity.value = restored
        return restored
    }

    // Limpia la identidad visual local cuando la app vuelve a estado base.
    fun clear() {
        _identity.value = null
        PersistentCompanyIdentityStore.clear()
    }
}

// Plataforma concreta para persistir la identidad visual en cada target de KMP.
expect object PersistentCompanyIdentityStore {
    fun load(): CompanyIdentity?
    fun save(identity: CompanyIdentity)
    fun clear()
}
