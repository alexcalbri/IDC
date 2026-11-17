package com.ideasdeveloper.idc.app.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import kotlinx.coroutines.delay

/**
 * Codigo seleccionable con boton "Copiar" (codigo de cliente, de usuario, acceso WiFi...).
 * El boton copia al portapapeles y muestra un aviso breve "Copiado".
 */
@Composable
fun CopyableCode(
    label: String,
    code: String,
    modifier: Modifier = Modifier,
    labelColor: Color = AppTheme.colors.muted,
    codeColor: Color = AppTheme.colors.text,
    codeStyle: TextStyle = TextStyle(fontSize = 14.sp),
) {
    // LocalClipboard (suspend) exige crear un ClipEntry, y en Compose Multiplatform 1.12 ClipEntry.withPlainText no esta en commonMain
    // (cada target lo construye distinto). LocalClipboardManager sigue funcionando en jvm, js, android e ios.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    // Cada clic incrementa el contador y reinicia el temporizador del aviso.
    var clicks by remember(code) { mutableIntStateOf(0) }
    LaunchedEffect(clicks) {
        if (clicks > 0) {
            copied = true
            delay(1500)
            copied = false
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // fill = false: un valor largo se recorta y no empuja el boton fuera de la vista.
        SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (label.isNotBlank()) Text("$label:", color = labelColor, style = codeStyle)
                Text(code, color = codeColor, style = codeStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
        IconButton(
            onClick = {
                @Suppress("DEPRECATION")
                clipboard.setText(AnnotatedString(code))
                clicks++
            },
            modifier = Modifier.size(32.dp).semantics { contentDescription = if (label.isBlank()) "Copiar" else "Copiar $label" },
        ) {
            CopyIcon(AppTheme.colors.accentText)
        }
        if (copied) Text("Copiado", color = AppTheme.colors.success, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

// Sin dependencia de iconos: dos rectangulos superpuestos dibujan el simbolo de copiar.
@Composable
private fun CopyIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx())
        val s = size.width
        val corner = CornerRadius(2.dp.toPx())
        drawRoundRect(color, topLeft = Offset(s * 0.30f, s * 0.30f), size = Size(s * 0.62f, s * 0.62f), cornerRadius = corner, style = stroke)
        drawRoundRect(color, topLeft = Offset(s * 0.08f, s * 0.08f), size = Size(s * 0.62f, s * 0.62f), cornerRadius = corner, style = stroke)
    }
}
