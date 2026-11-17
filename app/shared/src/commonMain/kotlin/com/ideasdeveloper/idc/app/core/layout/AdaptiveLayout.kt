package com.ideasdeveloper.idc.app.core.layout

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
    // Pantallas de listas y tarjetas: usan casi todo el ancho de escritorio.
    val Max = 1600.dp
}

/**
 * Cuadricula adaptable: el numero de columnas sale del ancho disponible y de [minItemWidth]. Cada hijo
 * directo de [content] es una celda; las filas toman el alto del hijo mas alto y la ultima fila
 * incompleta no estira sus celdas. Con una sola columna se apila como una Column normal.
 */
@Composable
fun AdaptiveGrid(
    modifier: Modifier = Modifier,
    minItemWidth: Dp = 360.dp,
    gap: Dp = 12.dp,
    // Iguala el alto de las celdas de una fila con medidas intrinsecas. Desactivalo si alguna celda
    // contiene BoxWithConstraints, listas perezosas o scroll (no admiten medidas intrinsecas).
    equalHeight: Boolean = true,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        // Sin ancho acotado (p. ej. dentro de un scroll horizontal) se usa una columna de minItemWidth.
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else minItemWidth.roundToPx()
        val columns = ((maxWidth + gapPx) / (minItemWidth.roundToPx() + gapPx)).coerceAtLeast(1)
        val cellWidth = ((maxWidth - gapPx * (columns - 1)) / columns).coerceAtLeast(0)
        val rows = measurables.chunked(columns)
        val placeables = rows.map { row ->
            // Con una sola celda en la fila no hace falta igualar: se evita medir dos veces.
            if (equalHeight && row.size > 1) {
                // El alto de la fila es el del hijo mas alto (intrinseco); asi las celdas quedan iguales.
                val rowHeight = row.maxOf { it.maxIntrinsicHeight(cellWidth) }
                row.map { it.measure(Constraints.fixed(cellWidth, rowHeight)) } to rowHeight
            } else {
                // Cada celda conserva su alto; la fila toma el de la mas alta.
                val measured = row.map { it.measure(Constraints(minWidth = cellWidth, maxWidth = cellWidth)) }
                measured to measured.maxOf { it.height }
            }
        }
        val height = placeables.sumOf { it.second } + gapPx * (placeables.size - 1).coerceAtLeast(0)
        layout(maxWidth.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            placeables.forEach { (row, rowHeight) ->
                row.forEachIndexed { index, placeable -> placeable.placeRelative(index * (cellWidth + gapPx), y) }
                y += rowHeight + gapPx
            }
        }
    }
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
