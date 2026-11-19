package com.ideasdeveloper.idc.modules.compras

import io.ktor.http.ContentType

/**
 * Tipo de contenido con el que se sirven los adjuntos. Solo se respeta el tipo guardado si esta en una lista
 * corta y segura; cualquier otro (en especial image/svg+xml y text/html, que pueden ejecutar codigo) se sirve
 * como application/octet-stream. La descarga siempre va con `X-Content-Type-Options: nosniff`.
 */
object ComprasDownloads {
    val AllowedTypes: Set<String> = setOf(
        "application/pdf",
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp",
        "text/plain",
        "text/csv",
        "application/vnd.ms-excel",
        "application/msword",
    )

    private const val OpenXmlPrefix = "application/vnd.openxmlformats-officedocument."

    /** Tipo a servir para el [stored] guardado: el mismo si esta permitido, si no octet-stream. */
    fun safeType(stored: String?): String {
        val type = stored?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val allowed = type in AllowedTypes || (type.startsWith(OpenXmlPrefix) && type.length > OpenXmlPrefix.length && type.none { it.isWhitespace() })
        return if (allowed) type else "application/octet-stream"
    }

    fun contentType(stored: String?): ContentType = ContentType.parse(safeType(stored))
}
