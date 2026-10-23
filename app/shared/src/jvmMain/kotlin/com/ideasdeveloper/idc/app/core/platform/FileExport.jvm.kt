package com.ideasdeveloper.idc.app.core.platform

import java.io.File

actual fun saveTextFile(fileName: String, content: String, mimeType: String): String {
    val home = File(System.getProperty("user.home"))
    val directory = File(home, "Downloads").takeIf { it.isDirectory } ?: home
    val target = File(directory, File(fileName).name)
    // El BOM permite que Excel abra el CSV como UTF-8.
    target.writeText("﻿" + content, Charsets.UTF_8)
    return "Archivo guardado en ${target.absolutePath}"
}
