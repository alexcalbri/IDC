package com.ideasdeveloper.idc.app.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.get
import kotlin.js.Promise

@Composable
actual fun rememberFilePicker(maxFileBytes: Long, onPicked: (List<PickedFile>) -> Unit): FilePicker {
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onPicked)

    fun choose(folders: Boolean) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.multiple = true
        // Chrome, Edge, Firefox y Safari permiten elegir una carpeta con sus subcarpetas.
        if (folders) input.setAttribute("webkitdirectory", "")
        input.style.display = "none"
        input.onchange = {
            val list = input.files
            val files = (0 until (list?.length ?: 0)).mapNotNull { list?.get(it) }
            input.remove()
            scope.launch {
                val picked = files.map { file -> read(file, maxFileBytes) }
                if (picked.isNotEmpty()) callback.value(picked)
            }
        }
        document.body?.appendChild(input)
        input.click()
    }

    return remember {
        object : FilePicker {
            override val supported = true
            override val supportsFolders = true
            override fun pickFiles() = choose(folders = false)
            override fun pickFolder() = choose(folders = true)
        }
    }
}

private suspend fun read(file: File, maxFileBytes: Long): PickedFile {
    // En una carpeta, webkitRelativePath es "Carpeta/sub/archivo.pdf".
    val relative = (file.asDynamic().webkitRelativePath as? String).orEmpty()
    val folder = relative.substringBeforeLast('/', "")
    val size = file.size.toLong()
    val bytes = if (size > maxFileBytes) null else {
        val buffer = (file.asDynamic().arrayBuffer() as Promise<ArrayBuffer>).await()
        Int8Array(buffer).unsafeCast<ByteArray>()
    }
    return PickedFile(file.name, folder, file.type.ifBlank { "application/octet-stream" }, size, bytes)
}
