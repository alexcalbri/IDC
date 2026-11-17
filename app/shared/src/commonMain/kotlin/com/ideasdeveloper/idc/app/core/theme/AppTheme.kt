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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import com.ideasdeveloper.idc.app.core.company.CompanyIdentity
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.core.company.ServerOwnerIdentity
import com.ideasdeveloper.idc.app.core.layout.ProvideWidthClass
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
    // Valores derivados: se calculan una sola vez al crear el AppColors (tambien con copy), no en cada lectura.
    // Texto sobre el degradado completo: sirve para los dos extremos del color de marca.
    val onBrandBackground: Color = readableOver(listOf(accent, accentSecondary))

    // El degradado conserva el color de marca, pero si ningun texto llega a 4.5:1 sobre alguno de sus
    // extremos se oscurece (o aclara) lo minimo necesario para que el texto sobre el fondo sea legible.
    val screenBrush: Brush = if (brandBackground) {
        Brush.horizontalGradient(listOf(accent.legibleWith(onBrandBackground), accentSecondary.legibleWith(onBrandBackground)))
    } else {
        SolidColor(background)
    }

    // Texto pintado directamente sobre el fondo de pantalla, fuera de tarjetas.
    val onScreen: Color = if (brandBackground) onBrandBackground else text

    // Secundario sobre el fondo: se atenua solo hasta quedar justo por encima de 4.5:1 sobre el degradado ajustado.
    val onScreenMuted: Color = if (brandBackground) {
        mutedOver(onScreen, listOf(accent.legibleWith(onScreen), accentSecondary.legibleWith(onScreen)))
    } else {
        muted
    }

    // Campos pintados sobre el fondo de pantalla: ya no son translucidos, usan superficies del tema.
    val fieldOnScreen: Color get() = surface
    val fieldOnScreenFocused: Color get() = surfaceAlt

    // Acento para texto, iconos y bordes sobre surface y surfaceAlt (4.5:1) o, si no llega, el texto del tema.
    val accentText: Color =
        if (contrastRatio(accent, surface) >= 4.5 && contrastRatio(accent, surfaceAlt) >= 4.5) accent else text

    // Borde de campo con al menos 3:1 sobre la superficie (WCAG 1.4.11) y borde con foco.
    val fieldBorder: Color = strongEnough(border, surface, text, 3.0)
    val fieldBorderFocused: Color = if (contrastRatio(accent, surface) >= 3.0) accent else text
}

// Luminancia relativa WCAG de un color.
fun Color.wcagLuminance(): Double {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

// Relacion de contraste WCAG entre dos colores opacos (de 1 a 21).
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.wcagLuminance()
    val lb = b.wcagLuminance()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private val InkDark = Color.Black
private val InkLight = Color.White

// Elige texto oscuro o claro: el que da mejor contraste en el peor de los fondos dados.
fun readableOver(backgrounds: List<Color>): Color {
    val dark = backgrounds.minOf { contrastRatio(InkDark, it) }
    val light = backgrounds.minOf { contrastRatio(InkLight, it) }
    return if (dark >= light) InkDark else InkLight  // Negro o blanco: el mejor da al menos 4.58:1 sobre un solo fondo
}

// Mueve [this] hacia negro o blanco (el opuesto de [ink]) hasta que [ink] sea legible (4.5:1) sobre el.
fun Color.legibleWith(ink: Color): Color {
    val toward = if (ink.wcagLuminance() > 0.5) Color.Black else Color.White
    for (step in 0..20) {
        val candidate = lerp(this, toward, step / 20f)
        if (contrastRatio(ink, candidate) >= 4.5) return candidate
    }
    return toward
}

// Acerca [base] al color [toward] hasta que, sobre [over], el contraste llegue a [ratio]:1.
internal fun strongEnough(base: Color, over: Color, toward: Color, ratio: Double): Color {
    for (step in 0..10) {
        val color = lerp(base, toward, step / 10f).compositeOver(over)
        if (contrastRatio(color, over) >= ratio) return color
    }
    return toward
}

// Version atenuada de [base] que aun cumple 4.5:1 sobre todos los fondos.
internal fun mutedOver(base: Color, backgrounds: List<Color>): Color {
    var best = base
    for (step in 1..6) {
        val candidate = base.copy(alpha = 1f - step * 0.04f)
        val ok = backgrounds.all { bg -> contrastRatio(candidate.compositeOver(bg), bg) >= 4.5 }
        if (ok) best = candidate else break
    }
    return best
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
    warning = "#975700",
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
    val primary = identity.primaryColor.toComposeColor(Color(0xFF667EEA))
    val secondary = identity.secondaryColor.toComposeColor(Color(0xFF764BA2))
    return AppColors(
        dark = theme.dark,
        background = pick(colors.background, base.background),
        surface = pick(colors.surface, base.surface),
        surfaceAlt = pick(colors.surfaceAlt, base.surfaceAlt),
        border = pick(colors.border, base.border),
        text = pick(colors.text, base.text),
        muted = pick(colors.muted, base.muted),
        accent = primary,
        accentSecondary = secondary,
        // El color de texto de la empresa solo se respeta si cumple 4.5:1 sobre el acento (botones);
        // si no, se elige negro o blanco, que sobre un color solo alcanza al menos 4.58:1.
        onAccent = identity.accentColor.toComposeColor(Color.White).let { preferred ->
            if (contrastRatio(preferred, primary) >= 4.5) preferred else readableOver(listOf(primary))
        },
        success = pick(colors.success, base.success),
        warning = pick(colors.warning, base.warning),
        danger = pick(colors.danger, base.danger),
        brandBackground = theme.brandBackground,
    )
}

private fun AppColors.toColorScheme(): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    // Acento apto para texto (TextButton, enlaces, foco): si el color de marca no llega a 4.5:1 sobre las
    // superficies, el esquema usa el color de texto del tema y los botones rellenos con el acento puro
    // siguen usando [onAccent].
    val primary = accentText
    return scheme.copy(
        primary = primary,
        onPrimary = if (primary == accent) onAccent else readableOver(listOf(primary)),
        secondary = accentSecondary,
        onSecondary = readableOver(listOf(accentSecondary)),
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
        outline = fieldBorder,
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
    val scheme = remember(colors) { colors.toColorScheme() }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme) { ProvideWidthClass(content) }
    }
}
