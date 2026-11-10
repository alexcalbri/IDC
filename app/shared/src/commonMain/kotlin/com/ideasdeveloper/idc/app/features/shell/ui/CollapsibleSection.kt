package com.ideasdeveloper.idc.app.features.shell.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.theme.AppTheme

/**
 * Seccion en acordeon para pantallas largas: el titulo abre y cierra el contenido. Se usa para lo
 * que no hace falta a cada momento (crear un usuario, exportar, configurar una vez), igual que las
 * tarjetas y secciones plegables de los modulos (UiCard.collapsible, UiForm.collapsibleSections).
 */
@Composable
fun CollapsibleSection(
    title: String,
    color: Color,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (open) "Cerrar $title" else "Abrir $title", onClick = onToggle)
                .padding(vertical = 4.dp),
        ) {
            Text("${if (open) "▾" else "▸"}  $title", style = MaterialTheme.typography.titleMedium, color = color)
            subtitle?.takeIf { !open && it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted) }
        }
        if (open) content()
    }
}

/** [CollapsibleSection] que guarda si esta abierta; empieza como [initiallyOpen]. */
@Composable
fun CollapsibleSection(
    title: String,
    color: Color,
    initiallyOpen: Boolean = false,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember(title) { mutableStateOf(initiallyOpen) }
    CollapsibleSection(title, color, open, onToggle = { open = !open }, modifier = modifier, subtitle = subtitle, content = content)
}
