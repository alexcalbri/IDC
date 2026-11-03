package com.ideasdeveloper.idc.app.core.session

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

// Cualquier 401 a una peticion con token significa que la sesion ya no vale (vencio o fue revocada):
// se cierra localmente y la app regresa al login avisando al usuario.
val SessionExpiry = createClientPlugin("SessionExpiry") {
    onResponse { response ->
        val authenticated = response.call.request.headers[HttpHeaders.Authorization]?.startsWith("Bearer ") == true
        if (authenticated && response.status == HttpStatusCode.Unauthorized) {
            SessionStore.expire()
        }
    }
}
