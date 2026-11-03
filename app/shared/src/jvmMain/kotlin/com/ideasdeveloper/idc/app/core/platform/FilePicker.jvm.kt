package com.ideasdeveloper.idc.app.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser

@Composable
actual fun rememberFilePicker(maxFileBytes: Long, onPicked: (List<PickedFile>) -> Unit): FilePicker {
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onPicked)

    fun choose(folders: Boolean) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = if (folders) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true
            dialogTitle = if (folders) "Adjuntar carpetas" else "Adjuntar archivos"
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        val chosen = chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) }
        // Leer carpetas grandes no debe congelar la ventana.
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                chosen.flatMap { root ->
                    if (root.isDirectory) {
                        root.walkTopDown().filter { it.isFile }.map { file ->
                            val folder = file.parentFile.relativeTo(root.parentFile ?: root).invariantSeparatorsPath
                            read(file, folder, maxFileBytes)
                        }.toList()
                    } else {
                        listOf(read(root, "", maxFileBytes))
                    }
                }
            }
            if (files.isNotEmpty()) callback.value(files)
        }
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

private fun read(file: File, folder: String, maxFileBytes: Long): PickedFile {
    val size = file.length()
    val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"
    return PickedFile(file.name, folder, mime, size, if (size > maxFileBytes) null else file.readBytes())
}
