package com.ideasdeveloper.idc.app.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToURL

// Guarda en Documentos de la app; Info.plist la expone en Archivos > En mi iPhone > IDC.
@OptIn(ExperimentalForeignApi::class)
@Suppress("CAST_NEVER_SUCCEEDS")
actual fun saveTextFile(fileName: String, content: String, mimeType: String): String {
    val directory = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).firstOrNull() as? NSURL
        ?: return "No se encontro la carpeta Documentos."
    val name = fileName.substringAfterLast('/')
    val target = directory.URLByAppendingPathComponent(name) ?: return "No se pudo crear el archivo."
    // El BOM permite que Excel abra el CSV como UTF-8.
    val saved = (("\uFEFF" + content) as NSString).writeToURL(target, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    return if (saved) "Archivo guardado en Archivos > En mi iPhone > IDC: $name" else "No se pudo guardar el archivo."
}
