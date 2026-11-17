package com.ideasdeveloper.idc.app.features.notifications.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.app.core.config.ClientConfigurationStore
import com.ideasdeveloper.idc.app.core.session.SessionStore
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import com.ideasdeveloper.idc.app.core.theme.readableOver
import com.ideasdeveloper.idc.app.features.notifications.data.NotificationItem
import com.ideasdeveloper.idc.app.features.shell.ui.AppIcons
import com.ideasdeveloper.idc.ui.UiOpenModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Como abre la app la pantalla de una notificacion; App.kt lo provee con su navegacion.
val LocalNotificationNavigator = staticCompositionLocalOf<(UiOpenModule) -> Unit> { {} }

/**
 * Campana de la barra superior: contador de no leidas y panel con las notificaciones del usuario.
 * Se actualiza al abrir el panel y cada 60 s mientras esta en pantalla (al cerrar sesion la barra
 * desaparece y la actualizacion se cancela). [circleColor] es el fondo del boton, igual al del menu.
 */
@Composable
fun NotificationBell(circleColor: Color, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val session by SessionStore.session.collectAsState()
    val state by NotificationCenter.state.collectAsState()
    val navigate = LocalNotificationNavigator.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }

    val token = session?.accessToken
    // El dueno del servidor usa los modulos en el espacio del servidor.
    val companyCode = session?.companyCode ?: session?.workspaceCompanyCode
    val serverUrl = remember(token) { ClientConfigurationStore.load()?.serverUrl }
    var current by remember { mutableStateOf<NotificationSession?>(null) }

    // La sesion se obtiene del Core (idempotente: reutiliza el cliente HTTP) y se actualiza cada minuto
    // mientras la campana esta en pantalla; al salir de composicion o cerrar sesion se cancela.
    LaunchedEffect(serverUrl, token, companyCode) {
        if (token == null || companyCode == null || serverUrl == null) {
            current = null
            return@LaunchedEffect
        }
        val notifications = NotificationCenter.session(serverUrl, token, companyCode)
        current = notifications
        while (true) {
            NotificationCenter.refresh(notifications)
            delay(NotificationCenter.RefreshSeconds * 1000)
        }
    }
    val notifications = current
    LaunchedEffect(open, notifications) {
        if (open && notifications != null) NotificationCenter.refresh(notifications)
    }
    if (token == null || companyCode == null || serverUrl == null || notifications == null) return

    val label = if (state.unread > 0) "Notificaciones, ${state.unread} sin leer" else "Notificaciones"
    Box(modifier = modifier) {
        IconButton(
            onClick = { open = !open },
            modifier = Modifier
                .size(48.dp)
                .background(circleColor, CircleShape)
                .semantics { contentDescription = label },
        ) {
            Icon(imageVector = AppIcons.Notifications, contentDescription = null, tint = colors.onScreen)
        }
        if (state.unread > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .height(20.dp)
                    .defaultMinSize(minWidth = 20.dp)
                    .background(colors.danger, CircleShape)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.unread > 99) "99+" else state.unread.toString(),
                    color = readableOver(listOf(colors.danger)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .heightIn(max = 460.dp)
                    .background(colors.surface)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Notificaciones", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { scope.launch { NotificationCenter.markAllRead(notifications) } },
                        enabled = state.unread > 0,
                    ) {
                        Text("Marcar todo como leído", fontSize = 13.sp, color = if (state.unread > 0) colors.accentText else colors.muted)
                    }
                }
                HorizontalDivider(color = colors.border)
                when {
                    !state.loaded -> PanelMessage(state.error ?: "Cargando...")
                    state.items.isEmpty() -> PanelMessage("No tienes notificaciones.")
                    else -> state.items.forEach { item ->
                        NotificationRow(item) {
                            open = false
                            NotificationCenter.markReadInBackground(notifications, item.id)
                            item.target?.let { navigate(UiOpenModule(item.moduleId, it.screen, it.params)) }
                        }
                        HorizontalDivider(color = colors.border)
                    }
                }
                if (state.hasMore) {
                    TextButton(
                        onClick = { scope.launch { NotificationCenter.loadMore(notifications) } },
                        enabled = !state.loadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.loadingMore) "Cargando..." else "Ver más", fontSize = 13.sp, color = colors.accentText)
                    }
                }
                state.error?.takeIf { state.loaded }?.let { PanelMessage("No se pudo actualizar: $it") }
            }
        }
    }
}

@Composable
private fun PanelMessage(text: String) {
    Text(text, color = AppTheme.colors.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
}

@Composable
private fun NotificationRow(item: NotificationItem, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (item.read) colors.surface else colors.surfaceAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Punto de no leida (el titulo en negrita tambien lo indica, no solo el color).
        Box(
            modifier = Modifier
                .padding(top = 6.dp, end = 10.dp)
                .size(8.dp)
                .background(if (item.read) Color.Transparent else colors.accentText, CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = if (item.read) FontWeight.Normal else FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.text.isNotBlank()) {
                Text(item.text, color = colors.muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            Text("${item.moduleName} · ${relativeAge(item.ageSeconds)}", color = colors.muted, fontSize = 12.sp)
        }
    }
}
