package com.ideasdeveloper.idc.app.core.session

import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionStore {

    private val _session = MutableStateFlow<LoginResponse?>(null)
    val session = _session.asStateFlow()

    fun save(response: LoginResponse) {
        _session.value = response
    }

    fun clear() {
        _session.value = null
    }
}
