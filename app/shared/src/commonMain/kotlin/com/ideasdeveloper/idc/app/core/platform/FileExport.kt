package com.ideasdeveloper.idc.app.core.platform

// Entrega un archivo de texto generado por un modulo (p. ej. CSV) y devuelve un mensaje para el usuario.
expect fun saveTextFile(fileName: String, content: String, mimeType: String): String

// Guarda un archivo descargado (adjuntos) en la carpeta de descargas de la plataforma; devuelve un mensaje.
expect fun saveBinaryFile(fileName: String, bytes: ByteArray, mimeType: String): String
