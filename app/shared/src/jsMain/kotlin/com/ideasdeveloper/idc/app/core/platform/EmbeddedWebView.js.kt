package com.ideasdeveloper.idc.app.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement

actual val supportsEmbeddedWebView: Boolean = true

// En la web la interfaz del modulo se muestra en un iframe superpuesto al lienzo de Compose.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun EmbeddedWebView(url: String, modifier: Modifier) {
    HtmlElementView(
        factory = {
            (document.createElement("iframe") as HTMLIFrameElement).apply {
                style.border = "none"
                style.width = "100%"
                style.height = "100%"
                src = url
            }
        },
        modifier = modifier,
    )
}

actual suspend fun prepareEmbeddedWebView(onProgress: (String) -> Unit): Boolean = true
