package com.ideasdeveloper.idc.app.core.session

import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionStore {

    // Expone la sesion activa para que Compose reaccione a login y logout.
    private val _session = MutableStateFlow<LoginResponse?>(null)
    val session = _session.asStateFlow()

    // Recupera la sesion persistida al iniciar la app.
    fun restore(): LoginResponse? {
        val restoredSession = PersistentSessionStore.load()
        _session.value = restoredSession
        return restoredSession
    }

    // Guarda la sesion en memoria y, si el usuario lo pidio, tambien en almacenamiento persistente.
    fun save(response: LoginResponse, keepSignedIn: Boolean = false) {
        _session.value = response
        if (keepSignedIn) {
            PersistentSessionStore.save(response)
        } else {
            PersistentSessionStore.clear()
        }
    }

    // Actualiza la sesion activa (p. ej. modulos del espacio del servidor) sin cambiar si estaba guardada.
    fun update(transform: (LoginResponse) -> LoginResponse) {
        val current = _session.value ?: return
        val updated = transform(current)
        _session.value = updated
        if (PersistentSessionStore.load() != null) PersistentSessionStore.save(updated)
    }

    // Elimina cualquier sesion local antes de volver al login.
    fun clear() {
        _session.value = null
        PersistentSessionStore.clear()
    }
}

// Plataforma concreta para persistir la sesion en cada target de KMP.
expect object PersistentSessionStore {
    fun load(): LoginResponse?
    fun save(response: LoginResponse)
    fun clear()
}
