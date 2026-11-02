package com.ideasdeveloper.idc.modules.agentes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

// Public origin of this server (https://host) as the client reached it through Nginx.
internal fun ApplicationCall.publicBase(): String {
    val scheme = request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim() ?: request.origin.scheme
    val host = request.headers["X-Forwarded-Host"]?.substringBefore(',')?.trim() ?: request.headers["Host"] ?: request.origin.serverHost
    return "$scheme://$host"
}

// Address of the client; behind the local Nginx it comes in X-Real-IP (same rule as the login rate limit).
internal fun ApplicationCall.clientIp(): String {
    val remote = request.local.remoteAddress
    return if (remote == "127.0.0.1") request.headers["X-Real-IP"]?.takeIf { it.isNotBlank() } ?: remote else remote
}
