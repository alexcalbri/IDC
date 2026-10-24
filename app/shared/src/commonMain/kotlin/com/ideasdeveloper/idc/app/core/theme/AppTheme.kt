package com.ideasdeveloper.idc.app.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import com.ideasdeveloper.idc.app.core.company.CompanyIdentity
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.core.company.ServerOwnerIdentity
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import com.ideasdeveloper.idc.ui.UiAppTheme
import com.ideasdeveloper.idc.ui.UiTheme

/**
 * Colores de toda la app: pantallas del core y piezas de los modulos. El tema pone fondos,
 * superficies, textos y estados; el acento siempre es el color de marca de la empresa.
 */
@Immutable
data class AppColors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentSecondary: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    // Fondo de pantalla con el degradado de marca (tema Claro) en lugar de [background].
    val brandBackground: Boolean,
) {
    val screenBrush: Brush
        get() = if (brandBackground) Brush.horizontalGradient(listOf(accent, accentSecondary)) else SolidColor(background)

    // Texto pintado directamente sobre el fondo de pantalla, fuera de tarjetas.
    val onScreen: Color
        get() = if (brandBackground) onAccent else text

    val onScreenMuted: Color
        get() = onScreen.copy(alpha = 0.76f)
}

// Parametros que recibe la primera pagina de un modulo web para pintarse con el tema de la app.
fun AppColors.webThemeQuery(): String = buildList {
    add("idc-theme" to if (dark) "dark" else "light")
    listOf(
        "background" to background, "surface" to surface, "surfaceAlt" to surfaceAlt, "border" to border,
        "text" to text, "muted" to muted, "accent" to accent, "onAccent" to onAccent,
        "success" to success, "warning" to warning, "danger" to danger,
    ).forEach { (key, color) -> add("idc-$key" to "%23" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()) }
}.joinToString("&", prefix = "&") { (key, value) -> "$key=$value" }

// Paleta base del core: tarjetas blancas sobre el degradado de marca.
private val LightPalette = UiTheme(
    background = "#F4F5FA",
    surface = "#FFFFFF",
    surfaceAlt = "#F6F6FA",
    border = "#E1E3EA",
    text = "#1A1A1A",
    muted = "#666666",
    success = "#176B3A",
    warning = "#B26A00",
    danger = "#B00020",
)

// Paleta del panel de hotspot.
private val DarkPalette = UiTheme(
    background = "#05060B",
    surface = "#11121B",
    surfaceAlt = "#181A2A",
    border = "#26293A",
    text = "#F5F5F5",
    muted = "#9EA3B5",
    success = "#3DD68C",
    warning = "#FF9B4A",
    danger = "#FF6B6B",
)

object BuiltInThemes {
    const val System = "system"
    const val Light = "light"
    const val Dark = "dark"

    val light = UiAppTheme(Light, "Claro", dark = false, colors = LightPalette, brandBackground = true)
    val dark = UiAppTheme(Dark, "Oscuro", dark = true, colors = DarkPalette)
}

// Tema elegido; un tema de modulo que ya no existe cae en Claro u Oscuro segun el sistema.
fun resolveTheme(selection: String, moduleThemes: List<UiAppTheme>, systemDark: Boolean): UiAppTheme = when (selection) {
    BuiltInThemes.Light -> BuiltInThemes.light
    BuiltInThemes.Dark -> BuiltInThemes.dark
    else -> moduleThemes.firstOrNull { it.id == selection }
        ?: if (systemDark) BuiltInThemes.dark else BuiltInThemes.light
}

fun appColors(theme: UiAppTheme, identity: CompanyIdentity): AppColors {
    // Lo que un tema de modulo no define se toma de la paleta base de su modo.
    val base = if (theme.dark) DarkPalette else LightPalette
    fun pick(value: String?, fallback: String?) = (value ?: fallback).orEmpty().toComposeColor(Color.Magenta)
    val colors = theme.colors
    return AppColors(
        dark = theme.dark,
        background = pick(colors.background, base.background),
        surface = pick(colors.surface, base.surface),
        surfaceAlt = pick(colors.surfaceAlt, base.surfaceAlt),
        border = pick(colors.border, base.border),
        text = pick(colors.text, base.text),
        muted = pick(colors.muted, base.muted),
        accent = identity.primaryColor.toComposeColor(Color(0xFF667EEA)),
        accentSecondary = identity.secondaryColor.toComposeColor(Color(0xFF764BA2)),
        onAccent = identity.accentColor.toComposeColor(Color.White),
        success = pick(colors.success, base.success),
        warning = pick(colors.warning, base.warning),
        danger = pick(colors.danger, base.danger),
        brandBackground = theme.brandBackground,
    )
}

private fun AppColors.toColorScheme(): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = accent,
        onPrimary = onAccent,
        secondary = accentSecondary,
        onSecondary = onAccent,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceAlt,
        onSurfaceVariant = muted,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceAlt,
        surfaceContainerHighest = surfaceAlt,
        outline = border,
        outlineVariant = border,
        error = danger,
    )
}

private val LocalAppColors = staticCompositionLocalOf { appColors(BuiltInThemes.light, ServerOwnerIdentity) }

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

// Aplica el tema elegido en Ajustes (o el del sistema) con los colores de marca de la empresa.
@Composable
fun IdeasCoreTheme(content: @Composable () -> Unit) {
    val selection by ThemeStore.selection.collectAsState()
    val moduleThemes by ThemeStore.moduleThemes.collectAsState()
    val identity by CompanyIdentityStore.identity.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val colors = remember(selection, moduleThemes, identity, systemDark) {
        appColors(resolveTheme(selection, moduleThemes, systemDark), identity ?: ServerOwnerIdentity)
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = colors.toColorScheme(), content = content)
    }
}
