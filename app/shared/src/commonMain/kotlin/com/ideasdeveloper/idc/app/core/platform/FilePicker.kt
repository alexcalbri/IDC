package com.ideasdeveloper.idc.app.core.platform

import androidx.compose.runtime.Composable

/**
 * A file chosen by the user. [path] is its folder inside a picked folder ("Contrato/Anexos"; the
 * picked folder itself included), "" for loose files. [bytes] is null when the file is larger than
 * the limit given to the picker, so it is not read into memory.
 */
class PickedFile(val name: String, val path: String, val mimeType: String, val size: Long, val bytes: ByteArray?)

interface FilePicker {
    // False where the platform has no picker yet; the UI then explains it instead of showing buttons.
    val supported: Boolean
    val supportsFolders: Boolean
    fun pickFiles()
    fun pickFolder()
}

/** Remembers a picker that calls [onPicked] with what the user chose (nothing when canceled). */
@Composable
expect fun rememberFilePicker(maxFileBytes: Long, onPicked: (List<PickedFile>) -> Unit): FilePicker
