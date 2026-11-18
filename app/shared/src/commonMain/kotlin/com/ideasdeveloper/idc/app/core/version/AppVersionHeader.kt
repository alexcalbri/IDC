package com.ideasdeveloper.idc.app.core.version

import com.ideasdeveloper.idc.ModuleVersion
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header

// Header con la version de la app; el servidor lo usa para validar la version minima de los modulos.
const val AppVersionHeader = "X-App-Version"

// Agrega el header a todas las peticiones del cliente. Se usa en cada HttpClient que habla con el servidor.
fun HttpClientConfig<*>.installAppVersionHeader() {
    defaultRequest {
        header(AppVersionHeader, ClientVersion)
    }
}

// True si la version indicada es mayor que la de esta app (es decir, la app es demasiado vieja).
fun isAppOlderThan(minAppVersion: String?): Boolean =
    !minAppVersion.isNullOrBlank() && ModuleVersion.compare(ClientVersion, minAppVersion) < 0
