package com.ideasdeveloper.idc.app.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Indica si la plataforma puede mostrar la interfaz web de un modulo dentro de la app.
expect val supportsEmbeddedWebView: Boolean

// Muestra una pagina web dentro de la app; solo se usa si supportsEmbeddedWebView es true.
@Composable
expect fun EmbeddedWebView(url: String, modifier: Modifier)

// Prepara el navegador embebido antes de pedir el enlace del modulo (en escritorio descarga
// Chromium la primera vez). Devuelve false si no esta disponible y hay que usar el navegador del sistema.
expect suspend fun prepareEmbeddedWebView(onProgress: (String) -> Unit): Boolean
