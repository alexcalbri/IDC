package com.ideasdeveloper.idc.app.core.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Verdadero donde hay raton (escritorio y web): alli la barra de scroll horizontal es visible.
 * En Android e iOS basta con el scroll tactil y no se muestra.
 */
expect val showsHorizontalScrollbar: Boolean

/**
 * Contenido ancho con scroll horizontal. Cuando no cabe, en escritorio y web muestra debajo una
 * barra visible y arrastrable; en movil solo hay scroll tactil. El contenido debe fijar sus propios
 * anchos minimos (columnas de ancho fijo) para que se desplace en vez de comprimirse.
 */
@Composable
fun HorizontalScrollArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberScrollState()
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().horizontalScroll(state)) { content() }
        // La barra lee el estado del scroll por su cuenta: hacer scroll no recompone `content`.
        if (showsHorizontalScrollbar) HorizontalScrollbarBar(state)
    }
}

// Ancho minimo de la pulgada y calculo compartido por el layout, el desplazamiento y el arrastre.
private const val MinThumbPx = 48f

private fun thumbWidthPx(trackPx: Float, maxScroll: Int): Float =
    (trackPx * trackPx / (trackPx + maxScroll)).coerceAtLeast(MinThumbPx).coerceAtMost(trackPx)

// Area tactil de 16 dp con la pista de 10 dp centrada; solo aparece si el contenido no cabe.
@Composable
private fun HorizontalScrollbarBar(state: ScrollState) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    // Solo cambia al medir (no en cada frame de scroll); el resto se lee en layout.
    if (state.maxValue <= 0) return
    BoxWithConstraints(
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .height(16.dp)
            // Clic en la pista: salta a la posicion proporcional.
            .pointerInput(state) {
                detectTapGestures { tap ->
                    val fraction = (tap.x / size.width).coerceIn(0f, 1f)
                    scope.launch { state.scrollTo((fraction * state.maxValue).roundToInt()) }
                }
            },
    ) {
        val trackPx = with(LocalDensity.current) { maxWidth.toPx() }
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(10.dp)
                .background(colors.surfaceAlt, RoundedCornerShape(5.dp)),
        )
        // Tamano y posicion de la pulgada se leen en layout, sin recomponer.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .layout { measurable, constraints ->
                    val thumb = thumbWidthPx(trackPx, state.maxValue).roundToInt()
                    val placeable = measurable.measure(constraints.copy(minWidth = thumb, maxWidth = thumb))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .offset {
                    val max = state.maxValue.coerceAtLeast(1)
                    val thumb = thumbWidthPx(trackPx, state.maxValue)
                    IntOffset(((trackPx - thumb) * state.value / max).roundToInt(), 0)
                }
                .height(10.dp)
                .background(colors.muted, RoundedCornerShape(5.dp))
                .pointerInput(state, trackPx) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val thumb = thumbWidthPx(trackPx, state.maxValue)
                        // Arrastrar la pulgada desplaza el contenido en proporcion.
                        state.dispatchRawDelta(drag.x * state.maxValue / (trackPx - thumb).coerceAtLeast(1f))
                    }
                },
        )
    }
}
