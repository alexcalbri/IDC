package com.ideasdeveloper.idc.app.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

actual val supportsEmbeddedWebView: Boolean = true

// Muestra la interfaz web del modulo con WKWebView dentro de la app.
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun EmbeddedWebView(url: String, modifier: Modifier) {
    UIKitView(
        factory = {
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = WKWebViewConfiguration()).apply {
                NSURL.URLWithString(url)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
            }
        },
        modifier = modifier,
    )
}

actual suspend fun prepareEmbeddedWebView(onProgress: (String) -> Unit): Boolean = true
