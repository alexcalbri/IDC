package com.ideasdeveloper.idc.app.core.platform

// Entrega un archivo de texto generado por un modulo (p. ej. CSV) y devuelve un mensaje para el usuario.
expect fun saveTextFile(fileName: String, content: String, mimeType: String): String
