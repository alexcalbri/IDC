package com.ideasdeveloper.idc.app.core.platform

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

actual fun saveTextFile(fileName: String, content: String, mimeType: String): String {
    // El BOM permite que Excel abra el CSV como UTF-8.
    val blob = Blob(arrayOf("\uFEFF" + content), BlobPropertyBag(type = mimeType))
    val url = URL.createObjectURL(blob)
    val link = document.createElement("a") as HTMLAnchorElement
    link.href = url
    link.download = fileName
    document.body?.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    return "Descarga iniciada: $fileName"
}
