package com.ideasdeveloper.idc.app.core.layout

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Clase de ancho de la ventana (no de la plataforma): compacto conserva el comportamiento movil,
 * mediano y amplio limitan el ancho del contenido y compactan el relleno.
 */
enum class WidthClass { Compact, Medium, Expanded }

fun widthClassFor(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.Compact
    width < 1000.dp -> WidthClass.Medium
    else -> WidthClass.Expanded
}

// Anchos maximos legibles por tipo de pantalla.
object ContentWidth {
    val Card = 420.dp
    val Form = 640.dp
    val Content = 880.dp
    val Wide = 1120.dp
}

private val LocalWidthClass = staticCompositionLocalOf { WidthClass.Compact }

object AdaptiveLayout {
    val widthClass: WidthClass
        @Composable
        @ReadOnlyComposable
        get() = LocalWidthClass.current

    // Verdadero en ventanas medianas y amplias (escritorio, web ancha, tablet).
    val roomy: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalWidthClass.current != WidthClass.Compact

    // Relleno y separacion mas compactos que en movil cuando hay espacio.
    val screenPadding: Dp
        @Composable
        @ReadOnlyComposable
        get() = if (roomy) 16.dp else 20.dp

    val gap: Dp
        @Composable
        @ReadOnlyComposable
        get() = if (roomy) 10.dp else 14.dp
}

/**
 * Publica la [WidthClass] de la ventana a toda la app. Usa el tamano de la ventana (sin envolver el
 * contenido en otro layout), asi no cambia el layout raiz ni los insets.
 */
@Composable
fun ProvideWidthClass(content: @Composable () -> Unit) {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current
    val widthClass = remember(widthPx, density) { widthClassFor(with(density) { widthPx.toDp() }) }
    CompositionLocalProvider(LocalWidthClass provides widthClass) { content() }
}

/**
 * Ancho del contenido de una pantalla: en compacto usa [compactFraction] del ancho (como antes) y en
 * mediano y amplio se detiene en [max]. Combinalo con `align(Alignment.Center)` o `TopCenter`.
 */
fun Modifier.contentWidth(max: Dp = ContentWidth.Content, compactFraction: Float = 0.9f): Modifier =
    // fillMaxWidth fija el ancho exacto; sin wrapContentWidth el widthIn posterior no puede achicarlo
    // (asi el login ocupaba el 90% en pantallas anchas). wrapContentWidth suelta el minimo y centra.
    fillMaxWidth(compactFraction).wrapContentWidth().widthIn(max = max).fillMaxWidth()
