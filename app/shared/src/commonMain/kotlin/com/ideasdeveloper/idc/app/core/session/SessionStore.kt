package com.ideasdeveloper.idc.app.core.session

import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionStore {

    private val _session = MutableStateFlow<LoginResponse?>(null)
    val session = _session.asStateFlow()

    fun restore(): LoginResponse? {
        val restoredSession = PersistentSessionStore.load()
        _session.value = restoredSession
        return restoredSession
    }

    fun save(response: LoginResponse, keepSignedIn: Boolean = false) {
        _session.value = response
        if (keepSignedIn) {
            PersistentSessionStore.save(response)
        } else {
            PersistentSessionStore.clear()
        }
    }

    fun clear() {
        _session.value = null
        PersistentSessionStore.clear()
    }
}

expect object PersistentSessionStore {
    fun load(): LoginResponse?
    fun save(response: LoginResponse)
    fun clear()
}
