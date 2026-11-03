package com.ideasdeveloper.idc.app.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Pendiente: selector con UIDocumentPickerViewController. Mientras tanto la app explica que se adjunta desde otro dispositivo.
@Composable
actual fun rememberFilePicker(maxFileBytes: Long, onPicked: (List<PickedFile>) -> Unit): FilePicker = remember {
    object : FilePicker {
        override val supported = false
        override val supportsFolders = false
        override fun pickFiles() = Unit
        override fun pickFolder() = Unit
    }
}
