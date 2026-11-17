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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
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
 * Contenido ancho con scroll horizontal. Cuando no cabe, en escritorio y web muestra una barra
 * visible y arrastrable; en movil solo hay scroll tactil. El contenido debe fijar sus propios
 * anchos minimos (columnas de ancho fijo) para que se desplace en vez de comprimirse.
 *
 * Si hay un [StickyScrollbarHost] alrededor, la barra no se dibuja aqui: el host la muestra pegada al
 * borde inferior visible (contenido alto, como el pipeline). Sin host, va una sola barra debajo.
 */
@Composable
fun HorizontalScrollArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberScrollState()
    val registry = LocalStickyScrollbars.current
    if (registry != null) {
        val entry = remember(state) { StickyScrollbarEntry(state) }
        DisposableEffect(registry, entry) {
            registry.entries.add(entry)
            onDispose { registry.entries.remove(entry) }
        }
        Box(
            modifier = modifier
                .fillMaxWidth()
                // Posicion y tamano se guardan en la fase de layout, sin recomponer el contenido.
                .onGloballyPositioned { coords ->
                    entry.coords = coords
                    entry.bounds = IntRect(coords.positionInRoot().round(), coords.size)
                }
                .horizontalScroll(state),
        ) { content() }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().horizontalScroll(state)) { content() }
            // La barra lee el estado del scroll por su cuenta: hacer scroll no recompone `content`.
            if (showsHorizontalScrollbar) HorizontalScrollbarBar(state, Modifier.padding(top = 4.dp))
        }
    }
}

/** Area registrada en un [StickyScrollbarHost]: su scroll y donde esta en pantalla. */
internal class StickyScrollbarEntry(val state: ScrollState) {
    var coords: LayoutCoordinates? by mutableStateOf(null)
    var bounds: IntRect by mutableStateOf(IntRect.Zero)
}

internal class StickyScrollbarRegistry {
    val entries = mutableStateListOf<StickyScrollbarEntry>()
    var hostCoords: LayoutCoordinates? by mutableStateOf(null)
}

internal val LocalStickyScrollbars = compositionLocalOf<StickyScrollbarRegistry?> { null }

/**
 * Contenedor que va FUERA del scroll vertical (mismo tamano). Las [HorizontalScrollArea] de dentro
 * registran su scroll y su posicion; el host dibuja una sola barra horizontal superpuesta, pegada al
 * borde inferior visible mientras el area este en pantalla, o justo bajo el area si esta completa a la
 * vista. Conviene dejar ~20 dp de relleno inferior en el contenido para que la barra no tape nada.
 * En movil (sin barra visible) solo pasa el contenido.
 */
@Composable
fun StickyScrollbarHost(
    verticalScroll: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!showsHorizontalScrollbar) {
        Box(modifier) { content() }
        return
    }
    val registry = remember { StickyScrollbarRegistry() }
    Box(modifier = modifier.onGloballyPositioned { registry.hostCoords = it }) {
        CompositionLocalProvider(LocalStickyScrollbars provides registry) { content() }
        StickyScrollbarOverlay(registry, verticalScroll)
    }
}

@Composable
private fun StickyScrollbarOverlay(registry: StickyScrollbarRegistry, verticalScroll: ScrollState) {
    // Solo esta capa se recompone al aparecer o quitarse un area; el scroll se lee en layout.
    val list = registry.entries.toList()
    Layout(
        content = {
            list.forEach { entry -> key(entry) { HorizontalScrollbarBar(entry.state, sticky = true) } }
        },
        modifier = Modifier.fillMaxSize().clipToBounds(),
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { i, m ->
            val width = list[i].bounds.width.coerceIn(0, constraints.maxWidth)
            m.measure(Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = constraints.maxHeight))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Se leen para recolocar la barra al hacer scroll vertical o moverse el area.
            verticalScroll.value
            val host = registry.hostCoords
            placeables.forEachIndexed { i, bar ->
                val entry = list[i]
                entry.bounds
                val area = entry.coords
                if (bar.height == 0 || host == null || !host.isAttached || area == null || !area.isAttached) return@forEachIndexed
                val origin = host.localPositionOf(area, Offset.Zero)
                val top = origin.y.roundToInt()
                val bottom = top + area.size.height
                val hostHeight = constraints.maxHeight
                // Visible solo si el area esta en pantalla; si cabe entera, justo debajo de ella.
                if (bottom <= 0 || top >= hostHeight - bar.height) return@forEachIndexed
                val y = if (bottom + bar.height <= hostHeight) bottom else hostHeight - bar.height
                bar.place(origin.x.roundToInt(), y)
            }
        }
    }
}

// Ancho minimo de la pulgada y calculo compartido por el layout, el desplazamiento y el arrastre.
private const val MinThumbPx = 48f

private fun thumbWidthPx(trackPx: Float, maxScroll: Int): Float =
    (trackPx * trackPx / (trackPx + maxScroll)).coerceAtLeast(MinThumbPx).coerceAtMost(trackPx)

// Area tactil de 16 dp con la pista de 10 dp centrada; solo aparece si el contenido no cabe.
@Composable
private fun HorizontalScrollbarBar(state: ScrollState, modifier: Modifier = Modifier, sticky: Boolean = false) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    // Siempre compuesta: se colapsa en la fase de layout cuando el contenido cabe (o aun no se midio),
    // asi no hay salto ni recomposicion extra. El padding va dentro para que tambien se colapse.
    BoxWithConstraints(
        modifier = Modifier
            .layout { measurable, constraints ->
                val fits = state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE
                if (fits) {
                    layout(0, 0) {}
                } else {
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
            }
            .then(modifier)
            .fillMaxWidth()
            // Superpuesta sobre las tarjetas: fondo sutil para que se lea.
            .then(if (sticky) Modifier.background(colors.surface.copy(alpha = 0.92f), RoundedCornerShape(8.dp)) else Modifier)
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
