package com.ideasdeveloper.idc.app.core.platform

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

// Contexto de la app para guardar archivos; se inicializa desde MainActivity.
object AndroidFileExport {
    internal var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }
}

actual fun saveTextFile(fileName: String, content: String, mimeType: String): String {
    val context = AndroidFileExport.applicationContext
        ?: return "No se pudo guardar el archivo: la app no esta inicializada."
    val name = File(fileName).name
    // El BOM permite que Excel abra el CSV como UTF-8.
    val bytes = ("\uFEFF" + content).toByteArray(Charsets.UTF_8)
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: carpeta publica Descargas sin pedir permisos.
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.substringBefore(';'))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "No se pudo crear el archivo en Descargas."
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return "No se pudo escribir el archivo en Descargas."
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Archivo guardado en Descargas: $name"
        } else {
            // Android 7-9: carpeta de la app, accesible desde el administrador de archivos.
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val target = File(directory, name)
            target.writeBytes(bytes)
            "Archivo guardado en ${target.absolutePath}"
        }
    } catch (exception: Exception) {
        "No se pudo guardar el archivo: ${exception.message}"
    }
}
