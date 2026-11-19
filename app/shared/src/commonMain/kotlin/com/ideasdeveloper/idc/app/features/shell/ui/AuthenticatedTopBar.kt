package com.ideasdeveloper.idc.app.features.shell.ui

import com.ideasdeveloper.idc.app.core.theme.wcagLuminance
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import com.ideasdeveloper.idc.app.features.notifications.ui.NotificationBell

@Composable
// Renderiza la barra superior compartida por pantallas autenticadas.
fun AuthenticatedTopBar(
    title: String,
    primaryColor: Color,
    subtitle: String? = null,
    onReturnToDashboard: (() -> Unit)? = null,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    // Con opciones, tocar el titulo abre este menu (p. ej. las otras empresas del business owner).
    titleMenu: List<TopBarMenuItem> = emptyList(),
) {
    val colors = AppTheme.colors
    var titleMenuExpanded by remember { mutableStateOf(false) }
    // Controla el menu de acciones y anima el icono cuando se abre.
    var menuExpanded by remember { mutableStateOf(false) }
    val menuRotation = animateFloatAsState(
        // Cerrado parte de 270 grados y al abrirse gira 90 grados mas.
        targetValue = if (menuExpanded) 360f else 270f,
        label = "authenticated-menu-rotation",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Muestra el logo provisional de la empresa o servidor.
        val logoBrush = remember(colors) {
            Brush.radialGradient(
                colors = listOf(colors.surface, colors.surfaceAlt),
                center = Offset(24f, 24f),
                radius = 30f,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(brush = logoBrush, shape = CircleShape)
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Muestra titulo y subtitulo contextual de la pantalla actual.
        Column(modifier = Modifier.weight(1f)) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (titleMenu.isEmpty()) Modifier else Modifier
                        .clickable(
                            onClickLabel = "Cambiar de empresa",
                            role = Role.Button,
                        ) { titleMenuExpanded = true }
                        .semantics(mergeDescendants = true) { contentDescription = "$title, abrir lista de empresas" },
                ) {
                    Text(
                        text = title,
                        color = colors.onScreen,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (titleMenu.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        // Indicador decorativo: lo describe el Row clicable.
                        Text("v", modifier = Modifier.clearAndSetSemantics {}, color = colors.onScreenMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                DropdownMenu(expanded = titleMenuExpanded, onDismissRequest = { titleMenuExpanded = false }) {
                    titleMenu.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.label, fontWeight = if (item.current) FontWeight.Bold else FontWeight.Normal)
                                    item.caption?.let { Text(it, fontSize = 12.sp, color = colors.muted) }
                                }
                            },
                            enabled = !item.current,
                            onClick = {
                                titleMenuExpanded = false
                                item.onClick()
                            },
                        )
                    }
                }
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = colors.onScreenMuted,
                    fontSize = 13.sp,
                )
            }
        }

        // Sobre el degradado, el circulo va del tono opuesto al texto para no restarle contraste.
        val circleColor = remember(colors) {
            if (colors.brandBackground) {
                (if (colors.onScreen.wcagLuminance() > 0.5) Color.Black else Color.White).copy(alpha = 0.18f)
            } else {
                colors.onScreen.copy(alpha = 0.18f)
            }
        }

        // Centro de notificaciones: solo aparece con una sesion de empresa o del espacio del servidor.
        NotificationBell(circleColor = circleColor)
        Spacer(modifier = Modifier.width(8.dp))

        // Agrupa ajustes y cierre de sesion en un menu compacto.
        Box {
            IconButton(
                onClick = { menuExpanded = !menuExpanded },
                modifier = Modifier
                    .size(48.dp)
                    .background(circleColor, CircleShape)
                    .graphicsLayer { rotationZ = menuRotation.value }
                    .semantics { contentDescription = "Menú" },
            ) {
                // Siempre "|||" girado (gira al abrirse) para no confundirse con la X de cerrar.
                // Es decorativo: la descripcion accesible la da el boton.
                Text(
                    modifier = Modifier.clearAndSetSemantics {},
                    text = "|||",
                    color = colors.onScreen,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                onReturnToDashboard?.let { returnToDashboard ->
                    DropdownMenuItem(
                        text = { Text("Volver al panel") },
                        onClick = {
                            menuExpanded = false
                            returnToDashboard()
                        },
                    )
                }
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

// Opcion del menu del titulo; [current] se muestra marcada y no se puede elegir.
data class TopBarMenuItem(val label: String, val caption: String? = null, val current: Boolean = false, val onClick: () -> Unit)

// Convierte colores hex guardados por la empresa a Color de Compose.
fun String.toComposeColor(fallback: Color): Color {
    val hex = trim().removePrefix("#")
    if (hex.length != 6) return fallback
    return hex.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) } ?: fallback
}
