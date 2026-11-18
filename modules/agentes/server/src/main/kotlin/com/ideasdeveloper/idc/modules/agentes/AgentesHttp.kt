package com.ideasdeveloper.idc.modules.agentes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

// Base publica fija opcional (IDC_PUBLIC_BASE_URL, por ejemplo https://idc.miempresa.com): si existe, el issuer y los endpoints no dependen de ninguna cabecera.
private val configuredBase: String? = System.getenv("IDC_PUBLIC_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

// Public origin of this server (https://host). Prefiere la base configurada; si no, usa el Host que Nginx valida (nunca X-Forwarded-Host, que escribe el cliente).
internal fun ApplicationCall.publicBase(): String {
    configuredBase?.let { return it }
    // X-Forwarded-Proto solo se acepta del Nginx local.
    val proxied = request.local.remoteAddress == "127.0.0.1"
    val scheme = (if (proxied) request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim() else null)
        ?.takeIf { it == "https" || it == "http" } ?: request.origin.scheme
    val host = request.headers["Host"]?.trim()?.takeIf { it.isNotEmpty() && it.none { char -> char.isWhitespace() || char == '/' || char == '@' } } ?: request.origin.serverHost
    return "$scheme://$host"
}

// Address of the client; behind the local Nginx it comes in X-Real-IP (same rule as the login rate limit).
internal fun ApplicationCall.clientIp(): String {
    val remote = request.local.remoteAddress
    return if (remote == "127.0.0.1") request.headers["X-Real-IP"]?.takeIf { it.isNotBlank() } ?: remote else remote
}
