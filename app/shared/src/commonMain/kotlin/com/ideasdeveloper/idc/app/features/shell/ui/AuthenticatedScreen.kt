package com.ideasdeveloper.idc.app.features.shell.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp

// Margen entre la barra y la vista; deja sitio a la X de ClosableView, que sobresale 20dp hacia arriba.
private val TopBarGap = 24.dp

/**
 * Pantalla autenticada: [topBar] arriba y [content] en el espacio que queda debajo. El area del
 * contenido se recorta a sus bordes, asi ninguna vista se dibuja encima de la barra ni la tapa, sin
 * importar el alto de la ventana. Los menus de la barra se abren en popup y quedan por encima.
 * El fondo lo pone el llamador en [modifier].
 */
@Composable
fun AuthenticatedScreen(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        topBar()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .padding(top = TopBarGap),
            content = content,
        )
    }
}
