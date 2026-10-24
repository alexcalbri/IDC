package com.ideasdeveloper.idc.app.features.shell.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.theme.AppTheme

// Altura de AuthenticatedTopBar (boton de 48dp + 18dp arriba y abajo); las vistas empiezan debajo.
val AuthenticatedTopBarHeight = 84.dp

/**
 * Vista abierta con la X de cerrar centrada sobre su esquina superior derecha, por encima del
 * contenido. Sin [onClose] (por ejemplo en el panel principal) no muestra la X.
 */
@Composable
fun ClosableView(onClose: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier) {
        content()
        if (onClose != null) {
            ViewCloseButton(onClose, Modifier.align(Alignment.TopEnd).offset(x = 20.dp, y = (-20).dp))
        }
    }
}

// Circulo visible de 30dp dentro de un area tactil de 40dp; translucido oscuro en temas claros y
// claro en temas oscuros para que se vea sobre cualquier contenido.
@Composable
private fun ViewCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Cerrar" },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(30.dp).background(
                if (AppTheme.colors.dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.45f),
                CircleShape,
            ),
        ) {
            Canvas(modifier = Modifier.size(11.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}
