package com.ideasdeveloper.idc.app.core.platform

actual fun saveTextFile(fileName: String, content: String, mimeType: String): String =
    "La exportacion de archivos aun no esta disponible en esta plataforma; usa la app web o de escritorio."
