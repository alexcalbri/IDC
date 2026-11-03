package com.ideasdeveloper.idc.app.core.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberFilePicker(maxFileBytes: Long, onPicked: (List<PickedFile>) -> Unit): FilePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onPicked)

    fun deliver(read: () -> List<PickedFile>) {
        scope.launch {
            val files = withContext(Dispatchers.IO) { runCatching(read).getOrDefault(emptyList()) }
            if (files.isNotEmpty()) callback.value(files)
        }
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) deliver { uris.mapNotNull { readDocument(context, it, "", maxFileBytes) } }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) deliver { readTree(context, tree, maxFileBytes) }
    }

    return remember(files, folder) {
        object : FilePicker {
            override val supported = true
            override val supportsFolders = true
            override fun pickFiles() = files.launch(arrayOf("*/*"))
            override fun pickFolder() = folder.launch(null)
        }
    }
}

private fun readDocument(context: Context, uri: Uri, folder: String, maxFileBytes: Long): PickedFile? {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.let { name = it }
            if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
    }
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    if (size > maxFileBytes) return PickedFile(name, folder, mime, size, null)
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return PickedFile(name, folder, mime, bytes.size.toLong(), if (bytes.size > maxFileBytes) null else bytes)
}

// Recorre la carpeta elegida y sus subcarpetas; la ruta de cada archivo empieza con el nombre de la carpeta.
private fun readTree(context: Context, tree: Uri, maxFileBytes: Long): List<PickedFile> {
    val resolver = context.contentResolver
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val rootName = resolver.query(
        DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "Carpeta"
    val result = mutableListOf<PickedFile>()
    val pending = ArrayDeque(listOf(rootId to rootName))
    while (pending.isNotEmpty()) {
        val (documentId, path) = pending.removeFirst()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val childName = cursor.getString(1) ?: continue
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    pending.add(childId to "$path/$childName")
                } else {
                    readDocument(context, DocumentsContract.buildDocumentUriUsingTree(tree, childId), path, maxFileBytes)?.let { result += it }
                }
            }
        }
    }
    return result
}
