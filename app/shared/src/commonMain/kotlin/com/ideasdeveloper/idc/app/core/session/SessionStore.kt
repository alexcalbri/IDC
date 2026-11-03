package com.ideasdeveloper.idc.app.core.session

import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

object SessionStore {

    // Expone la sesion activa para que Compose reaccione a login y logout.
    private val _session = MutableStateFlow<LoginResponse?>(null)
    val session = _session.asStateFlow()

    // Se activa cuando la sesion vence (por tiempo o porque el servidor la rechazo) para avisar en el login.
    private val _expired = MutableStateFlow(false)
    val expired = _expired.asStateFlow()

    // Momento (reloj monotono) en que vence el token de la sesion activa.
    private var expiresAt: TimeSource.Monotonic.ValueTimeMark? = null

    // Recupera la sesion persistida al iniciar la app.
    fun restore(): LoginResponse? {
        val restoredSession = PersistentSessionStore.load()
        expiresAt = restoredSession?.let { TimeSource.Monotonic.markNow() + it.expiresInSeconds.seconds }
        _session.value = restoredSession
        return restoredSession
    }

    // Guarda la sesion en memoria y, si el usuario lo pidio, tambien en almacenamiento persistente.
    fun save(response: LoginResponse, keepSignedIn: Boolean = false) {
        expiresAt = TimeSource.Monotonic.markNow() + response.expiresInSeconds.seconds
        _expired.value = false
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

    // Tiempo que le queda a la sesion activa; null si no hay sesion.
    fun remaining(): Duration? = expiresAt?.let { -it.elapsedNow() }

    // Cierra la sesion porque vencio; solo avisa si habia una sesion abierta.
    fun expire() {
        if (_session.value == null) return
        clear()
        _expired.value = true
    }

    // El login ya mostro el aviso de sesion vencida.
    fun acknowledgeExpired() {
        _expired.value = false
    }

    // Elimina cualquier sesion local antes de volver al login.
    fun clear() {
        expiresAt = null
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
