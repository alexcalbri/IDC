// app/src/commonMain/kotlin/com/ideasdeveloper/app/ui/theme/Themes.kt
package com.ideasdeveloper.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF667EEA),
    onPrimary = Color.White,
    secondary = Color(0xFF764BA2),
    onSecondary = Color.White,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun IdeasCoreTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography(),
        content = content
    )
}
