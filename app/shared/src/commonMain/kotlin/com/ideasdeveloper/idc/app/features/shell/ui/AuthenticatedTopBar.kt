package com.ideasdeveloper.idc.app.features.shell.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
// Renderiza la barra superior compartida por pantallas autenticadas.
fun AuthenticatedTopBar(
    title: String,
    primaryColor: Color,
    subtitle: String? = null,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Controla el menu de acciones y anima el icono cuando se abre.
    var menuExpanded by remember { mutableStateOf(false) }
    val menuRotation by animateFloatAsState(
        targetValue = if (menuExpanded) 90f else 0f,
        label = "authenticated-menu-rotation",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Muestra el logo provisional de la empresa o servidor.
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, Color(0xFFE0E0E0)),
                        center = Offset(24f, 24f),
                        radius = 30f,
                    ),
                    shape = CircleShape,
                )
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Muestra titulo y subtitulo contextual de la pantalla actual.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.76f),
                    fontSize = 13.sp,
                )
            }
        }

        // Agrupa ajustes y cierre de sesion en un menu compacto.
        Box {
            IconButton(
                onClick = { menuExpanded = !menuExpanded },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    .rotate(menuRotation),
            ) {
                Text(
                    text = if (menuExpanded) "X" else "|||",
                    color = Color.White,
                    fontSize = if (menuExpanded) 22.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Ajustes") },
                    onClick = {
                        menuExpanded = false
                        onSettings()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Cerrar sesion") },
                    onClick = {
                        menuExpanded = false
                        onLogout()
                    },
                )
            }
        }
    }
}

// Convierte colores hex guardados por la empresa a Color de Compose.
fun String.toComposeColor(fallback: Color): Color {
    val hex = trim().removePrefix("#")
    if (hex.length != 6) return fallback
    return hex.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) } ?: fallback
}
