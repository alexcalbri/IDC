package com.ideasdeveloper.idc.app.core.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import java.io.File

actual val supportsEmbeddedWebView: Boolean = true

/**
 * Chromium embebido (JCEF) para la interfaz web de los modulos en escritorio. Los binarios
 * (~100 MB) se descargan en ~/.idc/jcef la primera vez que se abre un modulo web.
 */
object DesktopBrowser {
    sealed class Status {
        data object Idle : Status()
        data class Preparing(val step: String, val percent: Float?) : Status()
        data object Ready : Status()
        data class Failed(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private var app: CefApp? = null

    // Inicia Chromium una sola vez; bloquea mientras descarga, llamar fuera del hilo de UI.
    @Synchronized
    internal fun start(): CefApp? {
        app?.let { return it }
        if (_status.value is Status.Failed) return null
        return try {
            val builder = CefAppBuilder().apply {
                setInstallDir(File(System.getProperty("user.home"), ".idc/jcef"))
                setProgressHandler { progress, percent ->
                    val step = when (progress) {
                        EnumProgress.DOWNLOADING -> "Descargando navegador integrado (solo la primera vez)"
                        EnumProgress.EXTRACTING, EnumProgress.INSTALL -> "Instalando navegador integrado"
                        else -> "Iniciando navegador integrado"
                    }
                    _status.value = Status.Preparing(step, percent.takeIf { it >= 0f })
                }
                // Ventana nativa (no renderizado fuera de pantalla): mejor rendimiento y teclado completo.
                cefSettings.windowless_rendering_enabled = false
                setAppHandler(object : MavenCefAppHandlerAdapter() {})
            }
            builder.build().also {
                app = it
                _status.value = Status.Ready
            }
        } catch (exception: Exception) {
            _status.value = Status.Failed(exception.message ?: exception::class.simpleName.orEmpty())
            null
        }
    }

    // Cierra Chromium al salir de la app; sin esto el proceso puede quedar abierto.
    @Synchronized
    fun shutdown() {
        app?.dispose()
        app = null
    }
}

@Composable
actual fun EmbeddedWebView(url: String, modifier: Modifier) {
    val status by DesktopBrowser.status.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DesktopBrowser.start() } }

    when (val current = status) {
        DesktopBrowser.Status.Ready -> ChromiumView(url, modifier)
        is DesktopBrowser.Status.Failed -> BrowserMessage(modifier, "No se pudo iniciar el navegador integrado: ${current.message}", url)
        is DesktopBrowser.Status.Preparing -> BrowserMessage(
            modifier,
            current.step + (current.percent?.let { " (${it.toInt()}%)" } ?: "") + "...",
            url = null,
        )
        DesktopBrowser.Status.Idle -> BrowserMessage(modifier, "Iniciando navegador integrado...", url = null)
    }
}

@Composable
private fun ChromiumView(url: String, modifier: Modifier) {
    val browser = remember(url) {
        val app = DesktopBrowser.start() ?: return@remember null
        val client: CefClient = app.createClient()
        client to client.createBrowser(url, false, false)
    }
    DisposableEffect(browser) {
        onDispose {
            browser?.let { (client, view: CefBrowser) ->
                view.close(true)
                client.dispose()
            }
        }
    }
    if (browser == null) {
        BrowserMessage(modifier, "No se pudo iniciar el navegador integrado.", url)
        return
    }
    SwingPanel(factory = { browser.second.uiComponent }, modifier = modifier)
}

@Composable
private fun BrowserMessage(modifier: Modifier, text: String, url: String?) {
    val uriHandler = LocalUriHandler.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
            if (url == null) CircularProgressIndicator(color = Color.White)
            Text(text, color = Color.White)
            // Si el navegador integrado falla, el modulo sigue disponible en el navegador del sistema.
            if (url != null) Button(onClick = { uriHandler.openUri(url) }) { Text("Abrir en el navegador") }
        }
    }
}

actual suspend fun prepareEmbeddedWebView(onProgress: (String) -> Unit): Boolean = coroutineScope {
    val watcher = launch {
        DesktopBrowser.status.collect { status ->
            if (status is DesktopBrowser.Status.Preparing) {
                onProgress(status.step + (status.percent?.let { " (${it.toInt()}%)" } ?: "") + "...")
            }
        }
    }
    try {
        withContext(Dispatchers.IO) { DesktopBrowser.start() != null }
    } finally {
        watcher.cancel()
    }
}
