package com.ideasdeveloper.idc.app.core.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.remember
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colores de los campos de texto sobre superficies del tema (tarjetas, formularios, dialogos):
 * texto 4.5:1, borde 3:1 y sin transparencias sobre el degradado.
 */
@Composable
fun appFieldColors(): TextFieldColors {
    val c = AppTheme.colors
    val base = OutlinedTextFieldDefaults.colors()
    return remember(c, base) {
        base.copy(
            focusedTextColor = c.text,
            unfocusedTextColor = c.text,
            disabledTextColor = c.muted,
            focusedLabelColor = c.text,
            unfocusedLabelColor = c.muted,
            cursorColor = c.text,
            focusedContainerColor = c.surface,
            unfocusedContainerColor = c.surface,
            focusedIndicatorColor = c.fieldBorderFocused,
            unfocusedIndicatorColor = c.fieldBorder,
            focusedPlaceholderColor = c.muted,
            unfocusedPlaceholderColor = c.muted,
            focusedTrailingIconColor = c.text,
            unfocusedTrailingIconColor = c.muted,
        )
    }
}

/** Texto de acento legible (4.5:1) sobre [surface]; si el [accent] no alcanza, cae al color de texto del tema. */
fun AppColors.accentTextOn(surface: Color, accent: Color = this.accent): Color =
    if (contrastRatio(accent, surface) >= 4.5) accent else text

/** Boton relleno con el acento de marca puro y su texto legible (no depende del primary del esquema). */
@Composable
fun accentButtonColors(): ButtonColors {
    val c = AppTheme.colors
    return ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)
}
