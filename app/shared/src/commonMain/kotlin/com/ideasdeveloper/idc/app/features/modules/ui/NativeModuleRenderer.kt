package com.ideasdeveloper.idc.app.features.modules.ui

import com.ideasdeveloper.idc.app.core.theme.AppColors
import com.ideasdeveloper.idc.app.core.layout.AdaptiveGrid
import com.ideasdeveloper.idc.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.idc.app.core.layout.HorizontalScrollArea
import com.ideasdeveloper.idc.app.core.ui.CopyableCode
import com.ideasdeveloper.idc.app.core.ui.DateFieldPicker
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.app.features.modules.data.ModuleException
import com.ideasdeveloper.idc.app.core.platform.rememberFilePicker
import com.ideasdeveloper.idc.app.core.platform.PickedFile
import androidx.compose.runtime.rememberCoroutineScope
import com.ideasdeveloper.idc.ui.UiAction
import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiBarChart
import com.ideasdeveloper.idc.ui.UiBoard
import com.ideasdeveloper.idc.ui.UiBoardAction
import com.ideasdeveloper.idc.ui.UiSuggestion
import com.ideasdeveloper.idc.ui.UiBoardCard
import com.ideasdeveloper.idc.ui.UiBoardColumn
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiColumn
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiDivider
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiImage
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiSpacer
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiSuggest
import com.ideasdeveloper.idc.ui.UiSuggestions
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTimeline
import com.ideasdeveloper.idc.ui.UiTone
import com.ideasdeveloper.idc.ui.UiUnknownAction
import kotlinx.coroutines.delay

// Colores resueltos de una pantalla; lo que el modulo no define usa el tema claro de la app.
data class ModuleColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    // Acento apto para texto y bordes sobre superficies (4.5:1); el acento puro queda para rellenos.
    val accentText: Color,
    val fieldBorder: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
) {
    fun tone(tone: String?): Color = when (tone) {
        UiTone.Success -> success
        UiTone.Warning -> warning
        UiTone.Danger -> danger
        UiTone.Info -> accentText
        else -> muted
    }

    companion object {
        // Las piezas de los modulos siempre usan el tema de la app; UiScreen.theme se ignora.
        fun from(app: AppColors) = ModuleColors(
            background = app.background,
            surface = app.surface,
            surfaceAlt = app.surfaceAlt,
            border = app.border,
            text = app.text,
            muted = app.muted,
            accent = app.accent,
            accentText = app.accentText,
            fieldBorder = app.fieldBorder,
            onAccent = app.onAccent,
            success = app.success,
            warning = app.warning,
            danger = app.danger,
        )
    }
}

// Lo que las piezas necesitan del host: colores, valores de formularios y ejecucion de acciones.
class ModuleRenderContext(
    val colors: ModuleColors,
    val busy: Boolean,
    val formValue: (formId: String, key: String) -> String,
    val setFormValue: (formId: String, key: String, value: String) -> Unit,
    val onAction: (UiAction) -> Unit,
    // Carga imagenes de piezas UiImage; null si no se pudo.
    val loadImage: suspend (url: String) -> ImageBitmap? = { null },
    // Pide sugerencias de un campo UiField.suggest; sin host que las soporte no hay sugerencias.
    val suggest: suspend (route: String, params: Map<String, String>) -> UiSuggestions = { _, _ -> UiSuggestions() },
    // Sube un archivo de un campo "files" y devuelve su referencia; lanza ModuleException si falla.
    val uploadFile: suspend (route: String, file: PickedFile) -> UiFileRef = { _, _ -> throw ModuleException("Este host no puede subir archivos.") },
    // Descarga y guarda un adjunto; devuelve el mensaje para el usuario.
    val downloadFile: suspend (route: String, file: UiFileRef) -> String = { _, _ -> "Este host no puede descargar archivos." },
)

@Composable
fun ModuleNode(node: UiNode, context: ModuleRenderContext) {
    when (node) {
        is UiText -> ModuleText(node, context.colors)
        is UiColumn -> Column(verticalArrangement = Arrangement.spacedBy(node.spacing.dp), modifier = Modifier.fillMaxWidth()) {
            ModuleChildren(node.children, node.spacing.dp, context)
        }
        is UiRow -> ModuleRow(node, context)
        is UiGrid -> ResponsiveGrid(node.children.size, node.minItemWidth.dp, node.spacing.dp) { index -> ModuleNode(node.children[index], context) }
        is UiCard -> ModuleCard(node, context)
        is UiMetric -> ModuleMetric(node, context.colors)
        is UiStatus -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(context.colors.tone(node.tone), CircleShape))
            Text(node.label, color = context.colors.muted, fontSize = 13.sp)
        }
        is UiBarChart -> ModuleBarChart(node, context.colors)
        is UiTable -> ModuleTable(node, context.colors)
        is UiButton -> ModuleButton(node, context)
        is UiForm -> ModuleForm(node, context)
        is UiImage -> ModuleImage(node, context)
        is UiDivider -> HorizontalDivider(color = context.colors.border)
        is UiSpacer -> Spacer(Modifier.height(node.size.dp))
        is UiBoard -> ModuleBoard(node, context)
        is UiList -> ModuleList(node, context)
        is UiTimeline -> ModuleTimeline(node, context.colors)
        // Piezas de un servidor mas nuevo: se omiten para no romper la pantalla.
        else -> Unit
    }
}

// Tarjetas que pueden ir lado a lado: sin tablas, tableros, formularios ni graficos dentro.
private fun isFlowCard(node: UiNode): Boolean =
    node is UiCard && node.children.none { it.isWideNode() } && node.actions.none { it.isWideNode() }

private fun UiNode.isWideNode(): Boolean = when (this) {
    is UiTable, is UiBoard, is UiForm, is UiBarChart, is UiTimeline, is UiGrid, is UiImage -> true
    is UiCard -> children.any { it.isWideNode() }
    is UiColumn -> children.any { it.isWideNode() }
    is UiRow -> children.any { it.isWideNode() }
    else -> false
}

// Agrupa tarjetas consecutivas (se conserva el orden); el resto de nodos queda solo en su grupo.
private fun groupFlowCards(children: List<UiNode>): List<List<UiNode>> {
    val groups = ArrayList<MutableList<UiNode>>()
    var lastWasCard = false
    children.forEach { child ->
        val card = isFlowCard(child)
        if (card && lastWasCard) groups.last().add(child) else groups.add(mutableListOf(child))
        lastWasCard = card
    }
    return groups
}

/**
 * Hijos de una columna. En compacto se apilan como siempre; en ventanas medianas y amplias las
 * tarjetas consecutivas se acomodan en una cuadricula si caben dos o mas columnas.
 */
@Composable
private fun ModuleChildren(children: List<UiNode>, spacing: Dp, context: ModuleRenderContext) {
    if (!AdaptiveLayout.roomy) {
        children.forEach { ModuleNode(it, context) }
        return
    }
    val groups = remember(children) { groupFlowCards(children) }
    groups.forEach { group ->
        if (group.size == 1) ModuleNode(group[0], context)
        else AdaptiveGrid(modifier = Modifier.fillMaxWidth(), minItemWidth = 360.dp, gap = spacing, equalHeight = false) {
            group.forEach { ModuleNode(it, context) }
        }
    }
}

@Composable
fun ModuleNoticeBanner(notice: UiNotice, colors: ModuleColors) {
    val tone = colors.tone(notice.tone)
    Text(
        notice.text,
        color = colors.text,
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .border(1.dp, tone.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    )
}

@Composable
private fun ModuleText(node: UiText, colors: ModuleColors) {
    val color = node.tone?.let { colors.tone(it) }
    when (node.style) {
        UiTextStyle.Title -> Text(node.text, color = color ?: colors.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        UiTextStyle.Subtitle -> Text(node.text, color = color ?: colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        UiTextStyle.Caption -> Text(node.text.uppercase(), color = color ?: colors.muted, fontSize = 12.sp, letterSpacing = 1.sp)
        else -> Text(node.text, color = color ?: colors.text, fontSize = 14.sp)
    }
}

@Composable
private fun ModuleRow(node: UiRow, context: ModuleRenderContext) {
    if (node.fill) {
        Row(horizontalArrangement = Arrangement.spacedBy(node.spacing.dp), modifier = Modifier.fillMaxWidth()) {
            node.children.forEach { child -> Box(Modifier.weight(1f)) { ModuleNode(child, context) } }
        }
    } else {
        // FlowRow pasa los elementos a la siguiente linea en pantallas angostas.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(node.spacing.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            node.children.forEach { ModuleNode(it, context) }
        }
    }
}

// Tantas columnas como quepan con al menos minItemWidth; en telefono queda una sola.
@Composable
fun ResponsiveGrid(count: Int, minItemWidth: Dp, spacing: Dp, item: @Composable (Int) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + spacing) / (minItemWidth + spacing)).toInt().coerceIn(1, maxOf(1, count))
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            (0 until count).chunked(columns).forEach { rowIndexes ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing), modifier = Modifier.fillMaxWidth()) {
                    rowIndexes.forEach { index -> Box(Modifier.weight(1f)) { item(index) } }
                    repeat(columns - rowIndexes.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(node: UiCard, context: ModuleRenderContext) {
    val colors = context.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        // Acordeon: el titulo abre y cierra la tarjeta.
        val collapsible = node.collapsible && node.title != null
        var open by remember(node.title, node.expanded) { mutableStateOf(node.expanded) }
        if (node.title != null || node.actions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val title = node.title.orEmpty().uppercase()
                Text(
                    if (collapsible) "${if (open) "\u25BE" else "\u25B8"}  $title" else title,
                    color = colors.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    modifier = if (collapsible) Modifier.clickable { open = !open }.padding(vertical = 4.dp) else Modifier,
                )
                if (!collapsible || open) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { node.actions.forEach { ModuleNode(it, context) } }
            }
        }
        if (!collapsible || open) node.children.forEach { ModuleNode(it, context) }
    }
}

@Composable
private fun ModuleImage(node: UiImage, context: ModuleRenderContext) {
    val bitmap by produceState<ImageBitmap?>(null, node.url) { value = context.loadImage(node.url) }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = node.description.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(node.height.dp),
        )
    } else {
        // Reserva el espacio mientras carga o si la imagen no esta disponible.
        Box(Modifier.height(node.height.dp).width(node.height.dp).background(context.colors.surfaceAlt, RoundedCornerShape(8.dp)))
    }
}

@Composable
private fun ModuleMetric(node: UiMetric, colors: ModuleColors) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(node.label.uppercase(), color = colors.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        // UiMetric no tiene un indicador "copiable" (vive en core, fuera de este alcance): solo los valores
        // cuya etiqueta habla de un codigo (p. ej. "Mi codigo WiFi") llevan boton Copiar; el resto es seleccionable.
        val isCode = node.value.isNotBlank() && (node.label.contains("codigo", ignoreCase = true) || node.label.contains("código", ignoreCase = true))
        if (isCode) {
            CopyableCode(
                label = "",
                code = node.value,
                codeColor = colors.text,
                codeStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold),
            )
        } else {
            SelectionContainer {
                Text(node.value, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        if (node.caption.isNotBlank()) Text(node.caption, color = colors.tone(node.captionTone), fontSize = 12.sp)
    }
}

@Composable
private fun ModuleBarChart(node: UiBarChart, colors: ModuleColors) {
    val max = node.points.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    // Reserva espacio para el valor, la etiqueta y el relleno debajo de cada barra.
    val barArea = node.height.dp - 72.dp
    // Pocas barras ocupan todo el ancho; muchas se desplazan horizontalmente.
    val fill = node.points.size <= 14
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .height(node.height.dp)
            .background(colors.surfaceAlt, RoundedCornerShape(14.dp))
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .then(if (fill) Modifier else Modifier.horizontalScroll(rememberScrollState()))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        node.points.forEach { point ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
                modifier = if (fill) Modifier.weight(1f) else Modifier.width(40.dp),
            ) {
                Box(
                    Modifier
                        .widthIn(max = 48.dp)
                        .fillMaxWidth()
                        .height(maxOf(4.dp, barArea * (point.value / max).toFloat()))
                        .background(colors.accent, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                )
                Text(formatValue(point.value), color = colors.muted, fontSize = 11.sp)
                Text(point.label, color = colors.muted, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ModuleTable(node: UiTable, colors: ModuleColors) {
    var shown by remember(node.columns) { mutableIntStateOf(PageSize) }
    if (node.rows.isEmpty()) {
        Text(node.emptyText, color = colors.muted, fontSize = 13.sp)
        return
    }
    val cellWidth = 170.dp
    HorizontalScrollArea { Column {
        Row {
            node.columns.forEach { column ->
                Text(column.label.uppercase(), color = colors.muted, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.width(cellWidth).padding(8.dp))
            }
        }
        node.rows.take(shown).forEach { row ->
            HorizontalDivider(color = colors.border, modifier = Modifier.width(cellWidth * node.columns.size))
            Row {
                node.columns.forEach { column ->
                    Text(row[column.key].orEmpty(), color = colors.text, fontSize = 13.sp, modifier = Modifier.width(cellWidth).padding(8.dp))
                }
            }
        }
    } }
    if (node.rows.size > shown) ShowMoreButton(node.rows.size - shown, colors) { shown += PageSize }
}

@Composable
private fun ModuleButton(node: UiButton, context: ModuleRenderContext) {
    val colors = context.colors
    val enabled = node.enabled && !context.busy && node.action !is UiUnknownAction
    when (node.style) {
        "secondary" -> OutlinedButton(
            onClick = { context.onAction(node.action) },
            enabled = enabled,
            border = BorderStroke(1.dp, if (enabled) colors.accentText else colors.border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentText, disabledContentColor = colors.muted),
        ) { Text(node.label) }
        else -> Button(
            onClick = { context.onAction(node.action) },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (node.style == "danger") colors.danger else colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.surfaceAlt,
                disabledContentColor = colors.muted,
            ),
        ) { Text(node.label, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ModuleForm(node: UiForm, context: ModuleRenderContext) {
    // Valores que puso una sugerencia elegida en claves que no son campos (p. ej. el id del cliente).
    var chosenExtraKeys by remember(node.id) { mutableStateOf(emptySet<String>()) }
    val openSections = remember(node.id) { mutableStateMapOf<String, Boolean>().apply { node.openSections.forEach { put(it, true) } } }
    // Derivados de los valores del formulario: una tecla solo recompone el formulario si cambia la
    // visibilidad de algun campo (igualdad estructural: sin cambios se conserva la misma lista).
    val visible by remember(node, context.formValue) {
        derivedStateOf(structuralEqualityPolicy()) {
            node.fields.filter { field ->
                field.visibleWhen?.let { condition -> context.formValue(node.id, condition.key) in condition.values } ?: true
            }
        }
    }
    // Agrupa campos consecutivos de la misma seccion bajo un titulo; solo se rehace si cambia la visibilidad.
    val groups = remember(visible) {
        buildList<Pair<String, List<UiField>>> {
            var currentSection: String? = null
            var current = mutableListOf<UiField>()
            visible.forEach { field ->
                val previous = currentSection
                if (previous != null && previous != field.section) {
                    add(previous to current)
                    current = mutableListOf()
                }
                currentSection = field.section
                current += field
            }
            currentSection?.let { add(it to current) }
        }
    }
    val missing by remember(node, context.formValue) {
        derivedStateOf(structuralEqualityPolicy()) {
            visible.firstOrNull { field ->
                field.required && !field.readOnly && field.type != "password" && field.type != "boolean" && context.formValue(node.id, field.key).isBlank()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        groups.forEach { (section, fields) ->
            // Con collapsibleSections cada seccion con nombre es un acordeon; empieza cerrado salvo las de openSections.
            val collapsible = node.collapsibleSections && section.isNotBlank()
            val open = !collapsible || openSections[section] == true
            if (section.isNotBlank()) {
                Text(
                    if (collapsible) "${if (open) "\u25BE" else "\u25B8"}  $section" else section,
                    color = context.colors.accentText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp).then(
                        if (collapsible) Modifier.clickable { openSections[section] = !open }.padding(vertical = 4.dp) else Modifier,
                    ),
                )
                node.sectionNotes[section]?.takeIf { it.isNotBlank() }?.let { Text(it, color = context.colors.muted, fontSize = 12.sp) }
            }
            if (open) ResponsiveGrid(fields.size, node.minFieldWidth.dp, 12.dp) { index ->
                val field = fields[index]
                val suggest = field.suggest
                if (field.type == "files") {
                    FilesField(node, field, context)
                } else if (suggest != null && !field.readOnly) {
                    SuggestingField(node, field, suggest, context, chosenExtraKeys) { chosenExtraKeys = it }
                } else {
                    ModuleField(field, context.formValue(node.id, field.key), !context.busy, context.colors) { value ->
                        context.setFormValue(node.id, field.key, value)
                    }
                }
            }
        }
        node.submit?.let { submit ->
            val missingField = missing
            val submitButton = remember(submit, missingField == null) { submit.copy(enabled = submit.enabled && missingField == null) }
            ModuleButton(submitButton, context)
            missingField?.let { Text("Completa: ${it.label}", color = context.colors.muted, fontSize = 12.sp) }
        }
    }
}

/**
 * Campo con sugerencias del servidor: mientras se escribe pide coincidencias (con una pausa para no
 * consultar en cada tecla) y las muestra debajo. Elegir una llena el formulario con sus valores.
 */
@Composable
private fun SuggestingField(
    form: UiForm,
    field: UiField,
    suggest: UiSuggest,
    context: ModuleRenderContext,
    chosenExtraKeys: Set<String>,
    onChosenExtraKeys: (Set<String>) -> Unit,
) {
    // Con trigger (p. ej. "@") se sugiere solo para la palabra bajo el cursor; sin el, todo sigue igual.
    val trigger = suggest.trigger
    if (trigger != null) {
        TriggerSuggestingField(form, field, suggest, trigger, context)
        return
    }
    val colors = context.colors
    val value = context.formValue(form.id, field.key)
    var result by remember(form.id, field.key) { mutableStateOf(UiSuggestions()) }
    // Valor que dejo la ultima sugerencia elegida: no se vuelve a buscar hasta que el usuario lo cambie.
    var chosenValue by remember(form.id, field.key) { mutableStateOf<String?>(null) }
    // Solo se busca lo que el usuario escribe, no los valores iniciales del formulario.
    var typed by remember(form.id, field.key) { mutableStateOf(false) }

    LaunchedEffect(value, typed) {
        val query = value.trim()
        if (!typed || query.length < suggest.minLength || query == chosenValue) {
            result = UiSuggestions()
            return@LaunchedEffect
        }
        delay(350)
        val fieldKeys = form.fields.map { it.key }.toSet()
        val params = (fieldKeys + chosenExtraKeys).associateWith { context.formValue(form.id, it) } + mapOf("q" to query, "field" to field.key)
        result = context.suggest(suggest.route, params)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ModuleField(field, value, !context.busy, colors) { input ->
            typed = true
            if (chosenExtraKeys.isNotEmpty() && input != chosenValue) {
                // El usuario cambio lo que eligio: deja de apuntar al registro sugerido.
                chosenExtraKeys.forEach { context.setFormValue(form.id, it, "") }
                onChosenExtraKeys(emptySet())
            }
            context.setFormValue(form.id, field.key, input)
        }
        if (result.items.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceAlt, RoundedCornerShape(10.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(vertical = 4.dp),
            ) {
                if (result.heading.isNotBlank()) {
                    Text(result.heading, color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                result.items.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !context.busy) {
                                val fieldKeys = form.fields.map { it.key }.toSet()
                                item.values.forEach { (key, itemValue) -> context.setFormValue(form.id, key, itemValue) }
                                onChosenExtraKeys(item.values.keys - fieldKeys)
                                chosenValue = (item.values[field.key] ?: value).trim()
                                result = UiSuggestions()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(item.title, color = colors.text, fontWeight = FontWeight.SemiBold)
                        if (item.subtitle.isNotBlank()) Text(item.subtitle, color = colors.muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// Largo maximo de la palabra que sigue al trigger; los nombres pueden llevar espacios, no saltos de linea.
private const val MaxTriggerToken = 40

/** Mencion en curso: donde empieza el trigger en el texto y lo escrito despues de el hasta el cursor. */
class TriggerToken(val start: Int, val query: String)

/**
 * Busca el ultimo [trigger] antes del cursor que empieza una palabra (al inicio o tras un espacio) y cuya
 * consulta no tiene saltos de linea ni pasa de 40 caracteres. Devuelve null si el cursor no esta en una.
 */
fun findTriggerToken(text: String, cursor: Int, trigger: String): TriggerToken? {
    if (trigger.isEmpty() || cursor < 0 || cursor > text.length) return null
    var i = cursor - trigger.length
    while (i >= 0) {
        if (cursor - i - trigger.length > MaxTriggerToken) return null
        if (text.startsWith(trigger, i) && (i == 0 || text[i - 1].isWhitespace())) {
            val query = text.substring(i + trigger.length, cursor)
            return if ('\n' in query) null else TriggerToken(i, query)
        }
        // Un salto de linea entre el cursor y el trigger corta la busqueda.
        if (text[i] == '\n') return null
        i--
    }
    return null
}

/**
 * Campo (texto o textarea) con sugerencias por trigger: la lista aparece bajo el campo para la palabra que
 * se escribe tras el trigger; elegir una reemplaza "@palabra" por "@" + values["insert"] (o titulo) + espacio.
 * Teclado: flechas, Enter/Tab para elegir y Escape para cerrar; tambien se elige con clic o toque.
 */
@Composable
private fun TriggerSuggestingField(form: UiForm, field: UiField, suggest: UiSuggest, trigger: String, context: ModuleRenderContext) {
    val colors = context.colors
    val value = context.formValue(form.id, field.key)
    // El TextFieldValue local manda (conserva el cursor); solo se resincroniza si el valor cambio por fuera
    // (p. ej. se limpio tras enviar), es decir, si no es el ultimo texto que escribio este campo.
    var state by remember(form.id, field.key) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var lastWritten by remember(form.id, field.key) { mutableStateOf<String?>(null) }
    val external = value != state.text && value != lastWritten
    val current = if (external) TextFieldValue(value, TextRange(value.length)) else state
    SideEffect {
        if (external) {
            state = current
            lastWritten = value
        }
    }
    var result by remember(form.id, field.key) { mutableStateOf(UiSuggestions()) }
    // Consulta a la que pertenece el resultado: una lista de otra palabra nunca se muestra ni se inserta.
    var resultFor by remember(form.id, field.key) { mutableStateOf<String?>(null) }
    var dismissed by remember(form.id, field.key) { mutableStateOf(false) }
    var selectedIndex by remember(form.id, field.key) { mutableIntStateOf(0) }
    // Solo se busca lo que el usuario escribe, no los valores iniciales.
    var typed by remember(form.id, field.key) { mutableStateOf(false) }
    val token = if (typed && current.selection.collapsed) findTriggerToken(current.text, current.selection.end, trigger) else null
    val tokenStart = token?.start
    val tokenQuery = token?.query

    LaunchedEffect(tokenStart, tokenQuery) {
        result = UiSuggestions()
        resultFor = null
        if (tokenQuery == null) return@LaunchedEffect
        // Al cambiar la palabra se cancela la consulta anterior; la pausa evita consultar en cada tecla.
        delay(250)
        // Solo q y field: las notas largas no deben viajar en la URL de un GET.
        val found = context.suggest(suggest.route, mapOf("q" to tokenQuery, "field" to field.key))
        result = found
        resultFor = tokenQuery
        selectedIndex = 0
    }

    val items = if (token != null && !dismissed && resultFor == tokenQuery) result.items else emptyList()

    fun choose(item: UiSuggestion) {
        val start = token?.start ?: return
        val insert = trigger + (item.values["insert"] ?: item.title) + " "
        val text = current.text.substring(0, start) + insert + current.text.substring(current.selection.end)
        state = TextFieldValue(text, TextRange(start + insert.length))
        lastWritten = text
        context.setFormValue(form.id, field.key, text)
        result = UiSuggestions()
        resultFor = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ModuleField(
            field, value, !context.busy, colors,
            textState = current,
            onTextState = { input ->
                typed = true
                if (input.text != current.text) dismissed = false
                state = input
                lastWritten = input.text
                context.setFormValue(form.id, field.key, input.text)
            },
            fieldModifier = Modifier.onPreviewKeyEvent { event ->
                if (items.isEmpty()) return@onPreviewKeyEvent false
                val handled = event.key == Key.DirectionDown || event.key == Key.DirectionUp || event.key == Key.Enter ||
                    event.key == Key.NumPadEnter || event.key == Key.Tab || event.key == Key.Escape
                if (!handled) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionDown -> selectedIndex = (selectedIndex + 1) % items.size
                        Key.DirectionUp -> selectedIndex = (selectedIndex - 1 + items.size) % items.size
                        Key.Escape -> dismissed = true
                        else -> choose(items[selectedIndex.coerceIn(0, items.lastIndex)])
                    }
                }
                true
            },
        ) { input -> context.setFormValue(form.id, field.key, input) }
        if (items.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceAlt, RoundedCornerShape(10.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(vertical = 4.dp),
            ) {
                items.forEachIndexed { index, item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (index == selectedIndex) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable(enabled = !context.busy) { choose(item) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(item.title, color = colors.text, fontWeight = FontWeight.SemiBold)
                        if (item.subtitle.isNotBlank()) Text(item.subtitle, color = colors.muted, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { dismissed = true }) { Text("Cerrar", color = colors.accentText, fontSize = 12.sp) }
            }
        }
    }
}

/**
 * Adjuntos de un campo "files": lista los archivos agrupados por carpeta, los descarga y sube los que
 * el usuario elige (archivos sueltos o carpetas completas). El valor del campo es la lista de referencias.
 */
@Composable
private fun FilesField(form: UiForm, field: UiField, context: ModuleRenderContext) {
    val colors = context.colors
    val files = field.files
    val scope = rememberCoroutineScope()
    var status by remember(form.id, field.key) { mutableStateOf<String?>(null) }
    var uploading by remember(form.id, field.key) { mutableStateOf(false) }
    val rawValue = context.formValue(form.id, field.key)
    val current = remember(rawValue) { UiFieldValues.files(rawValue) }
    val folders = remember(current) { current.groupBy { it.path }.entries.sortedBy { it.key }.map { it.key to it.value } }
    val editable = !field.readOnly && files != null
    val maxBytes = files?.maxFileBytes ?: 0L

    fun setFiles(next: List<UiFileRef>) = context.setFormValue(form.id, field.key, UiFieldValues.files(next))

    val picker = rememberFilePicker(maxBytes) { picked ->
        if (files == null) return@rememberFilePicker
        scope.launch {
            uploading = true
            val problems = mutableListOf<String>()
            var added = 0
            picked.forEachIndexed { index, file ->
                status = "Subiendo ${index + 1} de ${picked.size}: ${file.name}"
                if (file.bytes == null) {
                    problems += "${file.name} supera ${formatBytes(maxBytes)}"
                    return@forEachIndexed
                }
                try {
                    val ref = context.uploadFile(files.upload, file)
                    // Se agrega uno por uno para que un error no pierda los que ya subieron.
                    setFiles(UiFieldValues.files(context.formValue(form.id, field.key)) + ref)
                    added++
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    problems += exception.message ?: "No se pudo subir ${file.name}"
                }
            }
            uploading = false
            status = buildString {
                if (added > 0) append("$added ${if (added == 1) "archivo listo" else "archivos listos"}; se guardan al guardar el formulario.")
                if (problems.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("No se adjuntaron: ").append(problems.take(5).joinToString("; "))
                    if (problems.size > 5) append(" y ${problems.size - 5} mas")
                    append(".")
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(field.label + if (field.required && editable) " *" else "", color = colors.muted, fontSize = 12.sp)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceAlt, RoundedCornerShape(10.dp))
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(10.dp),
        ) {
            if (current.isEmpty()) Text("Sin archivos.", color = colors.muted, fontSize = 13.sp)
            folders.forEach { (folder, items) ->
                if (folder.isNotBlank()) {
                    Text("Carpeta: $folder", color = colors.accentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                }
                items.forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.name, color = colors.text, fontSize = 14.sp)
                            if (file.size > 0) Text(formatBytes(file.size), color = colors.muted, fontSize = 11.sp)
                        }
                        if (files != null) {
                            Text(
                                "Descargar",
                                color = colors.accentText,
                                fontSize = 13.sp,
                                modifier = Modifier.clickable(enabled = !uploading) {
                                    scope.launch { status = context.downloadFile(files.download, file) }
                                }.padding(4.dp),
                            )
                        }
                        if (editable) {
                            Text(
                                "Quitar",
                                color = colors.danger,
                                fontSize = 13.sp,
                                modifier = Modifier.clickable(enabled = !uploading && !context.busy) {
                                    setFiles(current.filter { it.id != file.id })
                                    status = "${file.name} se quita al guardar el formulario."
                                }.padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
        if (editable) {
            if (picker.supported) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = !uploading && !context.busy
                    OutlinedButton(onClick = { picker.pickFiles() }, enabled = enabled, border = BorderStroke(1.dp, if (enabled) colors.accentText else colors.border)) {
                        Text("Adjuntar archivos", color = if (enabled) colors.accentText else colors.muted)
                    }
                    if (files?.folders == true && picker.supportsFolders) {
                        OutlinedButton(onClick = { picker.pickFolder() }, enabled = enabled, border = BorderStroke(1.dp, if (enabled) colors.accentText else colors.border)) {
                            Text("Adjuntar carpeta", color = if (enabled) colors.accentText else colors.muted)
                        }
                    }
                }
            } else {
                Text("En este dispositivo aun no se pueden adjuntar archivos; hazlo desde la app de escritorio, web o Android.", color = colors.muted, fontSize = 12.sp)
            }
        }
        if (uploading) Text("Espera a que terminen de subir antes de guardar.", color = colors.warning, fontSize = 12.sp)
        status?.let { Text(it, color = colors.muted, fontSize = 12.sp) }
        if (field.help.isNotBlank()) Text(field.help, color = colors.muted, fontSize = 12.sp)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
private fun ModuleField(
    field: UiField,
    value: String,
    enabled: Boolean,
    colors: ModuleColors,
    // Con textState el campo de texto usa TextFieldValue (cursor conocido), p. ej. para sugerencias por trigger.
    textState: TextFieldValue? = null,
    onTextState: ((TextFieldValue) -> Unit)? = null,
    fieldModifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    val label = field.label + if (field.required && !field.readOnly) " *" else ""
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            field.readOnly -> {
                val display = if (field.multiple) UiFieldValues.options(value).joinToString(", ") else value
                if (field.multiple || display.isBlank()) {
                    Text(field.label, color = colors.muted, fontSize = 12.sp)
                    Text(
                        display.ifBlank { "-" },
                        color = colors.text,
                        modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt, RoundedCornerShape(10.dp)).padding(12.dp),
                    )
                } else {
                    // Valor de solo lectura (token, codigo, enlace...): seleccionable y con boton de copiar.
                    CopyableCode(
                        label = field.label,
                        code = display,
                        labelColor = colors.muted,
                        codeColor = colors.text,
                        modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt, RoundedCornerShape(10.dp)).padding(12.dp),
                    )
                }
            }
            field.type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = value == "true",
                    onCheckedChange = { onChange(it.toString()) },
                    enabled = enabled,
                    colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted),
                )
                Text(label, color = colors.text)
            }
            field.type == "select" -> {
                Text(label, color = colors.muted, fontSize = 12.sp)
                field.options.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = value == option,
                            onClick = { onChange(option) },
                            enabled = enabled,
                            colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.muted),
                        )
                        Text(option, color = colors.text)
                    }
                }
            }
            field.type == "dropdown" && field.multiple -> {
                var expanded by remember { mutableStateOf(false) }
                val chosen = UiFieldValues.options(value)
                // Opciones que ya tenia el registro aunque el modulo las haya quitado, para poder desmarcarlas.
                val options = field.options + chosen.filter { it !in field.options }
                Text(label, color = colors.muted, fontSize = 12.sp)
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = enabled && options.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            chosen.joinToString(", ").ifBlank { field.placeholder.ifBlank { "Selecciona una o varias opciones" } },
                            color = if (chosen.isEmpty()) colors.muted else colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        Text("v", color = colors.muted)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = option in chosen,
                                            onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(option)
                                    }
                                },
                                // El menu sigue abierto para marcar varias; se cierra tocando fuera.
                                onClick = {
                                    val next = if (option in chosen) chosen - option else options.filter { it in chosen || it == option }
                                    onChange(UiFieldValues.options(next))
                                },
                            )
                        }
                    }
                }
            }
            field.type == "dropdown" -> {
                var expanded by remember { mutableStateOf(false) }
                Text(label, color = colors.muted, fontSize = 12.sp)
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = enabled && field.options.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            value.ifBlank { field.placeholder.ifBlank { "Selecciona una opcion" } },
                            color = if (value.isBlank()) colors.muted else colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        Text("v", color = colors.muted)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        field.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    expanded = false
                                    onChange(option)
                                },
                            )
                        }
                    }
                }
            }
            field.type == "date" || field.type == "datetime" -> DateFieldPicker(
                value = value,
                onValueChange = onChange,
                label = label,
                withTime = field.type == "datetime",
                enabled = enabled,
                placeholder = field.placeholder.ifBlank { if (field.type == "datetime") "AAAA-MM-DDTHH:mm" else "AAAA-MM-DD" },
                textFieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    disabledTextColor = colors.muted,
                    focusedBorderColor = colors.accentText,
                    unfocusedBorderColor = colors.fieldBorder,
                    focusedLabelColor = colors.accentText,
                    unfocusedLabelColor = colors.muted,
                    cursorColor = colors.accentText,
                    focusedPlaceholderColor = colors.muted,
                    unfocusedPlaceholderColor = colors.muted,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fieldLabel: @Composable () -> Unit = { Text(label) }
                val fieldPlaceholder: (@Composable () -> Unit)? = when {
                    field.placeholder.isNotBlank() -> ({ Text(field.placeholder) })
                    field.type == "color" -> ({ Text("#RRGGBB") })
                    else -> null
                }
                val keyboard = KeyboardOptions(
                    keyboardType = when (field.type) {
                        "number" -> KeyboardType.Number
                        "email" -> KeyboardType.Email
                        "phone" -> KeyboardType.Phone
                        "url" -> KeyboardType.Uri
                        "password" -> KeyboardType.Password
                        else -> KeyboardType.Text
                    }
                )
                val textColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    disabledTextColor = colors.muted,
                    focusedBorderColor = colors.accentText,
                    unfocusedBorderColor = colors.fieldBorder,
                    focusedLabelColor = colors.accentText,
                    unfocusedLabelColor = colors.muted,
                    cursorColor = colors.accentText,
                    focusedPlaceholderColor = colors.muted,
                    unfocusedPlaceholderColor = colors.muted,
                )
                val transformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None
                if (textState != null && onTextState != null) {
                    OutlinedTextField(
                        value = textState,
                        onValueChange = onTextState,
                        label = fieldLabel,
                        placeholder = fieldPlaceholder,
                        enabled = enabled,
                        singleLine = field.type != "textarea",
                        minLines = if (field.type == "textarea") 3 else 1,
                        visualTransformation = transformation,
                        keyboardOptions = keyboard,
                        colors = textColors,
                        modifier = Modifier.weight(1f).then(fieldModifier),
                    )
                } else {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { input -> onChange(if (field.type == "number") input.filter { it.isDigit() || it == '-' } else input) },
                        label = fieldLabel,
                        placeholder = fieldPlaceholder,
                        enabled = enabled,
                        singleLine = field.type != "textarea",
                        minLines = if (field.type == "textarea") 3 else 1,
                        visualTransformation = transformation,
                        keyboardOptions = keyboard,
                        colors = textColors,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (field.type == "color") {
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(value.toComposeColor(Color.Transparent), RoundedCornerShape(8.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    )
                }
            }
        }
        if (field.help.isNotBlank()) Text(field.help, color = colors.muted, fontSize = 12.sp)
    }
}

@Composable
private fun ModuleBadge(badge: UiBadge, colors: ModuleColors) {
    val tone = colors.tone(badge.tone)
    Text(
        badge.label,
        color = colors.text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .background(tone.copy(alpha = 0.18f), RoundedCornerShape(50))
            .border(1.dp, tone.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ModuleBadges(badges: List<UiBadge>, colors: ModuleColors) {
    if (badges.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.forEach { ModuleBadge(it, colors) }
    }
}

// Tamano de pagina de las listas largas: se muestran 50 y "Ver mas" suma otros 50.
private const val PageSize = 50

@Composable
private fun ShowMoreButton(remaining: Int, colors: ModuleColors, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text("Ver m\u00e1s ($remaining restantes)", color = colors.accentText) }
}

/**
 * Estado del arrastre de una tarjeta del tablero. Las coordenadas de columnas y tarjetas viven en mapas
 * no observables (solo se leen al iniciar o terminar el arrastre); lo observable es `dragging`, `pointer`
 * y `dragOffset`, que cambian durante el gesto.
 */
@Stable
private class BoardDragState {
    val columns = mutableMapOf<String, LayoutCoordinates>()
    val cards = mutableMapOf<String, LayoutCoordinates>()
    var dragging by mutableStateOf<String?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var dragOffset by mutableStateOf(Offset.Zero)

    // Only the horizontal position counts: a column is as tall as its cards, and dropping below
    // the last card of a short column must still land in it.
    fun columnAt(position: Offset): String? = columns.entries.firstOrNull { (_, coordinates) ->
        coordinates.isAttached && coordinates.boundsInRoot().let { position.x >= it.left && position.x < it.right }
    }?.key

    fun dropTarget(): String? = columnAt(pointer + dragOffset)

    fun start(cardId: String, origin: Offset, downPosition: Offset, startPosition: Offset) {
        dragging = cardId
        pointer = origin + downPosition
        dragOffset = startPosition - downPosition
    }

    fun stop() {
        dragging = null
        dragOffset = Offset.Zero
    }
}

// Tablero kanban: columnas con desplazamiento horizontal. Una tarjeta se arrastra a otra columna
// (con el raton directamente; en pantallas tactiles tras mantenerla presionada, para no chocar con
// el desplazamiento) o se mueve con su menu "Mover a". Con acciones, tambien se seleccionan tarjetas
// para aplicarlas en bloque.
@Composable
private fun ModuleBoard(node: UiBoard, context: ModuleRenderContext) {
    val colors = context.colors
    if (node.columns.isEmpty()) {
        Text(node.emptyText, color = colors.muted, fontSize = 13.sp)
        return
    }
    val drag = remember { BoardDragState() }
    // La columna de destino se deriva de los estados del arrastre: este composable solo recompone al
    // cambiar de columna, no en cada frame del gesto.
    val target by remember(drag) { derivedStateOf { drag.dragging?.let { drag.dropTarget() } } }
    val dragging = drag.dragging
    // Columna que contiene la tarjeta arrastrada; se calcula una vez por cambio de arrastre.
    val draggedColumnId = remember(node, dragging) {
        dragging?.let { id -> node.columns.firstOrNull { column -> column.cards.any { it.id == id } }?.id }
    }
    val onMove = remember(node.move, context.onAction) {
        { cardId: String, columnId: String ->
            node.move?.let { move -> context.onAction(move.copy(params = move.params + mapOf("card" to cardId, "column" to columnId))) }
            Unit
        }
    }
    val canMove = node.move != null && !context.busy

    // Seleccion: mapa observable por id de tarjeta; cada tarjeta lee solo si ella esta marcada (derivedStateOf),
    // asi marcar una no redibuja las demas. Sobrevive a "Ver mas" porque vive aqui y no en la columna.
    var selecting by remember { mutableStateOf(false) }
    val selection = remember { mutableStateMapOf<String, Boolean>() }
    // Ids del tablero: se recalculan solo cuando llega un tablero nuevo, y la poda depende de este conjunto.
    val cardIds = remember(node) { node.columns.flatMapTo(HashSet()) { column -> column.cards.map { it.id } } }
    LaunchedEffect(cardIds) {
        // Las tarjetas que ya no estan en el tablero salen de la seleccion.
        selection.keys.retainAll(cardIds)
    }
    // Accion con confirmacion en espera; la seleccion se conserva hasta que de verdad se ejecute.
    var pendingRun by remember { mutableStateOf<PendingBoardRun?>(null) }
    val onRun = remember(context.onAction, selection) {
        { action: UiBoardAction, ids: List<String>, value: String?, bulk: Boolean ->
            if (ids.isNotEmpty()) {
                val params = action.request.params + mapOf("cards" to ids.joinToString(",")) + (value?.let { mapOf("value" to it) } ?: emptyMap())
                val request = action.request.copy(params = params)
                if (request.confirm != null) {
                    pendingRun = PendingBoardRun(request, bulk)
                } else {
                    // Solo la accion sobre la seleccion sale del modo seleccion; la de una tarjeta no la toca.
                    if (bulk) {
                        selection.clear()
                        selecting = false
                    }
                    context.onAction(request)
                }
            }
        }
    }
    pendingRun?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingRun = null },
            text = { Text(pending.request.confirm.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRun = null
                    if (pending.bulk) {
                        selection.clear()
                        selecting = false
                    }
                    context.onAction(pending.request.copy(confirm = null))
                }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { pendingRun = null }) { Text("Cancelar") } },
        )
    }
    val hasActions = node.actions.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        // Barra fija sobre el area desplazable: siempre visible aunque el tablero se desplace de lado.
        if (hasActions) {
            if (selecting) {
                BoardSelectionBar(node.actions, selection, colors, !context.busy, onRun) {
                    selection.clear()
                    selecting = false
                }
            } else {
                TextButton(onClick = { selecting = true }, enabled = !context.busy) {
                    Text("Seleccionar", color = colors.accentText, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Las columnas mantienen su ancho (columnWidth) y el tablero se desplaza con barra visible en escritorio y web.
        HorizontalScrollArea { Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            node.columns.forEach { column ->
                val hostsDragged = draggedColumnId == column.id
                BoardColumn(
                    node = node,
                    column = column,
                    drag = drag,
                    highlighted = target == column.id && !hostsDragged,
                    hostsDragged = hostsDragged,
                    draggedCardId = if (hostsDragged) dragging else null,
                    canMove = canMove,
                    onMove = onMove,
                    selecting = selecting,
                    selection = selection,
                    onRun = onRun,
                    context = context,
                )
            }
        } }
    }
}

// Accion de tablero que espera confirmacion; bulk indica si se aplica a la seleccion o a una sola tarjeta.
private class PendingBoardRun(val request: UiRequest, val bulk: Boolean)

// Barra del modo seleccion: contador, acciones (con submenu si traen opciones) y "Cancelar".
@Composable
private fun BoardSelectionBar(
    actions: List<UiBoardAction>,
    selection: SnapshotStateMap<String, Boolean>,
    colors: ModuleColors,
    enabled: Boolean,
    onRun: (UiBoardAction, List<String>, String?, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    // Solo esta barra lee el tamano de la seleccion.
    val count = selection.size
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            if (count == 1) "1 seleccionado" else "$count seleccionados",
            color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        actions.forEach { action ->
            BoardActionButton(action, colors, enabled && count > 0) { value -> onRun(action, selection.keys.toList(), value, true) }
        }
        TextButton(onClick = onCancel) { Text("Cancelar", color = colors.muted) }
    }
}

// Boton de una accion: sin opciones la ejecuta; con opciones abre un menu con ellas.
@Composable
private fun BoardActionButton(action: UiBoardAction, colors: ModuleColors, enabled: Boolean, onRun: (String?) -> Unit) {
    val color = if (action.tone == UiTone.Danger) colors.danger else colors.accentText
    val options = action.options
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { if (options.isNullOrEmpty()) onRun(null) else open = true },
            enabled = enabled,
        ) {
            Text(if (options.isNullOrEmpty()) action.label else "${action.label} ▾", color = if (enabled) color else colors.muted, fontWeight = FontWeight.SemiBold)
        }
        if (!options.isNullOrEmpty()) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            open = false
                            onRun(option.value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardColumn(
    node: UiBoard,
    column: UiBoardColumn,
    drag: BoardDragState,
    highlighted: Boolean,
    hostsDragged: Boolean,
    draggedCardId: String?,
    canMove: Boolean,
    onMove: (String, String) -> Unit,
    selecting: Boolean,
    selection: SnapshotStateMap<String, Boolean>,
    onRun: (UiBoardAction, List<String>, String?, Boolean) -> Unit,
    context: ModuleRenderContext,
) {
    val colors = context.colors
    var shown by remember(column.id) { mutableIntStateOf(PageSize) }
    DisposableEffect(drag, column.id) { onDispose { drag.columns.remove(column.id) } }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(node.columnWidth.dp)
            .zIndex(if (hostsDragged) 1f else 0f)
            .onGloballyPositioned { drag.columns[column.id] = it }
            .background(if (highlighted) colors.accent.copy(alpha = 0.14f) else colors.surfaceAlt, RoundedCornerShape(16.dp))
            .border(1.dp, if (highlighted) colors.accent else colors.border, RoundedCornerShape(16.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(colors.tone(column.tone), CircleShape))
            Text(column.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(column.cards.size.toString(), color = colors.muted, fontSize = 12.sp)
        }
        if (column.caption.isNotBlank()) Text(column.caption, color = colors.muted, fontSize = 12.sp)
        if (column.cards.isEmpty()) Text("Sin elementos.", color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        if (selecting && column.cards.isNotEmpty()) {
            // Solo las tarjetas ya cargadas (las que muestra "Ver mas") entran en "Seleccionar todos".
            val loaded = column.cards.take(shown)
            val allSelected by remember(selection, loaded) { derivedStateOf { loaded.all { selection.containsKey(it.id) } } }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !context.busy) {
                    if (allSelected) loaded.forEach { selection.remove(it.id) } else loaded.forEach { selection[it.id] = true }
                },
            ) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted, checkmarkColor = colors.onAccent),
                )
                Text("Seleccionar todos", color = colors.text, fontSize = 12.sp)
            }
        }
        column.cards.take(shown).forEach { card ->
            key(card.id) {
                BoardCard(
                    card = card,
                    column = column,
                    columns = node.columns,
                    actions = node.actions,
                    drag = drag,
                    isDragged = draggedCardId == card.id,
                    canMove = canMove,
                    onMove = onMove,
                    selecting = selecting,
                    selection = selection,
                    onRun = onRun,
                    context = context,
                )
            }
        }
        if (column.cards.size > shown) ShowMoreButton(column.cards.size - shown, colors) { shown += PageSize }
        column.footer?.let { ModuleButton(it, context) }
    }
}

@Composable
private fun BoardCard(
    card: UiBoardCard,
    column: UiBoardColumn,
    columns: List<UiBoardColumn>,
    actions: List<UiBoardAction>,
    drag: BoardDragState,
    isDragged: Boolean,
    canMove: Boolean,
    onMove: (String, String) -> Unit,
    selecting: Boolean,
    selection: SnapshotStateMap<String, Boolean>,
    onRun: (UiBoardAction, List<String>, String?, Boolean) -> Unit,
    context: ModuleRenderContext,
) {
    val colors = context.colors
    var menu by remember(card.id) { mutableStateOf(false) }
    // Accion con opciones elegida en el menu: muestra sus opciones como submenu.
    var submenu by remember(card.id) { mutableStateOf<UiBoardAction?>(null) }
    val currentOnMove by rememberUpdatedState(onMove)
    // Solo cambia cuando esta tarjeta se marca o desmarca; las demas no se redibujan.
    val selected by remember(selection, card.id) { derivedStateOf { selection.containsKey(card.id) } }
    // El arrastre se desactiva mientras se selecciona.
    val dragEnabled = canMove && !selecting
    DisposableEffect(drag, card.id) { onDispose { drag.cards.remove(card.id) } }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Solo las tarjetas movibles guardan su posicion; es un holder no observable.
            .then(if (dragEnabled) Modifier.onGloballyPositioned { drag.cards[card.id] = it } else Modifier)
            .graphicsLayer {
                if (isDragged) {
                    translationX = drag.dragOffset.x
                    translationY = drag.dragOffset.y
                    shadowElevation = 12f
                }
            }
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, if (isDragged || selected) colors.accent else colors.border, RoundedCornerShape(12.dp))
            .then(
                if (selecting) {
                    // En modo seleccion tocar la tarjeta la marca en vez de abrirla.
                    Modifier.clickable(enabled = !context.busy) {
                        if (selection.containsKey(card.id)) selection.remove(card.id) else selection[card.id] = true
                    }
                } else {
                    card.action?.let { action -> Modifier.clickable(enabled = !context.busy) { context.onAction(action) } } ?: Modifier
                }
            )
            // After clickable so the drag sees the pointer first and its consumed events
            // cancel the click (opening the card) and the board scroll.
            .then(
                if (dragEnabled) Modifier.pointerInput(card.id, column.id, drag) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = if (down.type == PointerType.Mouse) {
                            awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        } else {
                            awaitLongPressOrCancellation(down.id)
                        } ?: return@awaitEachGesture
                        val origin = drag.cards[card.id]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                        drag.start(card.id, origin, down.position, start.position)
                        var dropped: String? = null
                        try {
                            val completed = drag(start.id) { change ->
                                drag.dragOffset += change.positionChange()
                                change.consume()
                            }
                            if (completed) {
                                // The release must not also open the card.
                                currentEvent.changes.forEach { it.consume() }
                                dropped = drag.dropTarget()
                            }
                        } finally {
                            drag.stop()
                        }
                        dropped?.takeIf { it != column.id }?.let { currentOnMove(card.id, it) }
                    }
                } else Modifier
            )
            .padding(10.dp),
    ) {
        if (selecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted, checkmarkColor = colors.onAccent),
                )
                Text(card.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
        } else {
            Text(card.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (card.subtitle.isNotBlank()) Text(card.subtitle, color = colors.muted, fontSize = 12.sp, maxLines = 2)
        card.details.forEach { Text(it, color = colors.muted, fontSize = 12.sp) }
        ModuleBadges(card.badges, colors)
        val canPickColumn = canMove && columns.size > 1
        if (!selecting && (canPickColumn || actions.isNotEmpty())) {
            Box {
                Text(
                    when {
                        canPickColumn && actions.isNotEmpty() -> "Mover / Acciones"
                        canPickColumn -> "Mover a..."
                        else -> "Acciones"
                    },
                    color = colors.accentText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { submenu = null; menu = true }.padding(vertical = 2.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false; submenu = null }) {
                    val chosen = submenu
                    if (chosen != null) {
                        DropdownMenuItem(text = { Text("‹ ${chosen.label}", fontWeight = FontWeight.SemiBold) }, onClick = { submenu = null })
                        chosen.options.orEmpty().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    menu = false
                                    submenu = null
                                    onRun(chosen, listOf(card.id), option.value, false)
                                },
                            )
                        }
                    } else {
                        if (canPickColumn) columns.filter { it.id != column.id }.forEach { other ->
                            DropdownMenuItem(
                                text = { Text(other.title) },
                                onClick = {
                                    menu = false
                                    onMove(card.id, other.id)
                                },
                            )
                        }
                        if (canPickColumn && actions.isNotEmpty()) HorizontalDivider(color = colors.border)
                        actions.forEach { action ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (action.options.isNullOrEmpty()) action.label else "${action.label} ›",
                                        color = if (action.tone == UiTone.Danger) colors.danger else Color.Unspecified,
                                    )
                                },
                                enabled = !context.busy,
                                onClick = {
                                    if (action.options.isNullOrEmpty()) {
                                        menu = false
                                        onRun(action, listOf(card.id), null, false)
                                    } else {
                                        submenu = action
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleList(node: UiList, context: ModuleRenderContext) {
    val colors = context.colors
    var shown by remember { mutableIntStateOf(PageSize) }
    if (node.items.isEmpty()) {
        Text(node.emptyText, color = colors.muted, fontSize = 13.sp)
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        node.items.take(shown).forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = colors.border)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(item.action?.let { action -> Modifier.clickable(enabled = !context.busy) { context.onAction(action) } } ?: Modifier)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    Text(item.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (item.subtitle.isNotBlank()) Text(item.subtitle, color = colors.muted, fontSize = 12.sp, maxLines = 2)
                    ModuleBadges(item.badges, colors)
                }
                if (item.trailing.isNotBlank()) Text(item.trailing, color = colors.muted, fontSize = 12.sp)
            }
        }
        if (node.items.size > shown) ShowMoreButton(node.items.size - shown, colors) { shown += PageSize }
    }
}

@Composable
private fun ModuleTimeline(node: UiTimeline, colors: ModuleColors) {
    var shown by remember { mutableIntStateOf(PageSize) }
    if (node.entries.isEmpty()) {
        Text(node.emptyText, color = colors.muted, fontSize = 13.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        node.entries.take(shown).forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 4.dp).size(10.dp).background(colors.tone(entry.tone), CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text(entry.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (entry.body.isNotBlank()) Text(entry.body, color = colors.text, fontSize = 13.sp)
                    if (entry.caption.isNotBlank()) Text(entry.caption, color = colors.muted, fontSize = 11.sp)
                }
            }
        }
        if (node.entries.size > shown) ShowMoreButton(node.entries.size - shown, colors) { shown += PageSize }
    }
}

private fun formatValue(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()
