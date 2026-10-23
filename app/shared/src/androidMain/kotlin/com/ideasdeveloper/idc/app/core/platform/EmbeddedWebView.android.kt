package com.ideasdeveloper.idc.app.core.platform

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

actual val supportsEmbeddedWebView: Boolean = true

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun EmbeddedWebView(url: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
    )
}

actual suspend fun prepareEmbeddedWebView(onProgress: (String) -> Unit): Boolean = true
