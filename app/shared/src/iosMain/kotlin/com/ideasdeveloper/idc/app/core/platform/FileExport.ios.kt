package com.ideasdeveloper.idc.app.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToURL

// Guarda en Documentos de la app; Info.plist la expone en Archivos > En mi iPhone > IDC.
@OptIn(ExperimentalForeignApi::class)
@Suppress("CAST_NEVER_SUCCEEDS")
actual fun saveTextFile(fileName: String, content: String, mimeType: String): String {
    val target = documentsTarget(fileName) ?: return "No se pudo crear el archivo."
    // El BOM permite que Excel abra el CSV como UTF-8.
    val saved = (("﻿" + content) as NSString).writeToURL(target, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    return if (saved) "Archivo guardado en Archivos > En mi iPhone > IDC: ${target.lastPathComponent}" else "No se pudo guardar el archivo."
}

@OptIn(ExperimentalForeignApi::class)
actual fun saveBinaryFile(fileName: String, bytes: ByteArray, mimeType: String): String {
    val target = documentsTarget(fileName) ?: return "No se pudo crear el archivo."
    val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
    val saved = data.writeToURL(target, atomically = true)
    return if (saved) "Archivo guardado en Archivos > En mi iPhone > IDC: ${target.lastPathComponent}" else "No se pudo guardar el archivo."
}

private fun documentsTarget(fileName: String): NSURL? {
    val directory = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).firstOrNull() as? NSURL ?: return null
    return directory.URLByAppendingPathComponent(fileName.substringAfterLast('/'))
}
