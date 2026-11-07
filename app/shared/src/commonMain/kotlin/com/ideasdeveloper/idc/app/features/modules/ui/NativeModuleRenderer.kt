package com.ideasdeveloper.idc.app.features.modules.ui

import com.ideasdeveloper.idc.app.core.theme.AppColors
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiColumn
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
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
) {
    fun tone(tone: String?): Color = when (tone) {
        UiTone.Success -> success
        UiTone.Warning -> warning
        UiTone.Danger -> danger
        UiTone.Info -> accent
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
            node.children.forEach { ModuleNode(it, context) }
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
        Text(node.value, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
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
    if (node.rows.isEmpty()) {
        Text(node.emptyText, color = colors.muted, fontSize = 13.sp)
        return
    }
    val cellWidth = 170.dp
    Column(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row {
            node.columns.forEach { column ->
                Text(column.label.uppercase(), color = colors.muted, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.width(cellWidth).padding(8.dp))
            }
        }
        node.rows.forEach { row ->
            HorizontalDivider(color = colors.border, modifier = Modifier.width(cellWidth * node.columns.size))
            Row {
                node.columns.forEach { column ->
                    Text(row[column.key].orEmpty(), color = colors.text, fontSize = 13.sp, modifier = Modifier.width(cellWidth).padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ModuleButton(node: UiButton, context: ModuleRenderContext) {
    val colors = context.colors
    val enabled = node.enabled && !context.busy && node.action !is UiUnknownAction
    when (node.style) {
        "secondary" -> OutlinedButton(
            onClick = { context.onAction(node.action) },
            enabled = enabled,
            border = BorderStroke(1.dp, if (enabled) colors.accent else colors.border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent, disabledContentColor = colors.muted),
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
    val openSections = remember(node.id) { mutableStateMapOf<String, Boolean>() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        // Agrupa campos consecutivos de la misma seccion bajo un titulo.
        val groups = mutableListOf<Pair<String, MutableList<UiField>>>()
        val visible = node.fields.filter { field ->
            field.visibleWhen?.let { condition -> context.formValue(node.id, condition.key) in condition.values } ?: true
        }
        visible.forEach { field ->
            if (groups.isEmpty() || groups.last().first != field.section) groups += field.section to mutableListOf()
            groups.last().second += field
        }
        groups.forEach { (section, fields) ->
            // Con collapsibleSections cada seccion con nombre es un acordeon que empieza cerrado.
            val collapsible = node.collapsibleSections && section.isNotBlank()
            val open = !collapsible || openSections[section] == true
            if (section.isNotBlank()) {
                Text(
                    if (collapsible) "${if (open) "\u25BE" else "\u25B8"}  $section" else section,
                    color = context.colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
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
            val missing = visible.firstOrNull { field ->
                field.required && !field.readOnly && field.type != "password" && field.type != "boolean" && context.formValue(node.id, field.key).isBlank()
            }
            ModuleButton(submit.copy(enabled = submit.enabled && missing == null), context)
            missing?.let { Text("Completa: ${it.label}", color = context.colors.muted, fontSize = 12.sp) }
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
    val current = UiFieldValues.files(context.formValue(form.id, field.key))
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
            current.groupBy { it.path }.entries.sortedBy { it.key }.forEach { (folder, items) ->
                if (folder.isNotBlank()) {
                    Text("Carpeta: $folder", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
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
                                color = colors.accent,
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
                    OutlinedButton(onClick = { picker.pickFiles() }, enabled = enabled, border = BorderStroke(1.dp, if (enabled) colors.accent else colors.border)) {
                        Text("Adjuntar archivos", color = if (enabled) colors.accent else colors.muted)
                    }
                    if (files?.folders == true && picker.supportsFolders) {
                        OutlinedButton(onClick = { picker.pickFolder() }, enabled = enabled, border = BorderStroke(1.dp, if (enabled) colors.accent else colors.border)) {
                            Text("Adjuntar carpeta", color = if (enabled) colors.accent else colors.muted)
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
private fun ModuleField(field: UiField, value: String, enabled: Boolean, colors: ModuleColors, onChange: (String) -> Unit) {
    val label = field.label + if (field.required && !field.readOnly) " *" else ""
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            field.readOnly -> {
                Text(field.label, color = colors.muted, fontSize = 12.sp)
                Text(
                    (if (field.multiple) UiFieldValues.options(value).joinToString(", ") else value).ifBlank { "-" },
                    color = colors.text,
                    modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt, RoundedCornerShape(10.dp)).padding(12.dp),
                )
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
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { input -> onChange(if (field.type == "number") input.filter { it.isDigit() || it == '-' } else input) },
                    label = { Text(label) },
                    placeholder = when {
                        field.placeholder.isNotBlank() -> ({ Text(field.placeholder) })
                        field.type == "date" -> ({ Text("AAAA-MM-DD") })
                        field.type == "color" -> ({ Text("#RRGGBB") })
                        else -> null
                    },
                    enabled = enabled,
                    singleLine = field.type != "textarea",
                    minLines = if (field.type == "textarea") 3 else 1,
                    visualTransformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.type) {
                            "number" -> KeyboardType.Number
                            "email" -> KeyboardType.Email
                            "phone" -> KeyboardType.Phone
                            "url" -> KeyboardType.Uri
                            "password" -> KeyboardType.Password
                            else -> KeyboardType.Text
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        disabledTextColor = colors.muted,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedLabelColor = colors.accent,
                        unfocusedLabelColor = colors.muted,
                        cursorColor = colors.accent,
                        focusedPlaceholderColor = colors.muted,
                        unfocusedPlaceholderColor = colors.muted,
                    ),
                    modifier = Modifier.weight(1f),
                )
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

// Tablero kanban: columnas con desplazamiento horizontal. Una tarjeta se arrastra a otra columna
// (con el raton directamente; en pantallas tactiles tras mantenerla presionada, para no chocar con
// el desplazamiento) o se mueve con su menu "Mover a".
@Composable
private fun ModuleBoard(node: UiBoard, context: ModuleRenderContext) {
    val colors = context.colors
    if (node.columns.isEmpty()) {
        Text(node.emptyText, color = colors.muted, fontSize = 13.sp)
        return
    }
    // Limites de cada columna en coordenadas de la ventana, para saber donde se suelta la tarjeta.
    val columnBounds = remember(node) { mutableStateMapOf<String, Rect>() }
    var dragging by remember(node) { mutableStateOf<String?>(null) }
    var pointer by remember(node) { mutableStateOf(Offset.Zero) }
    var dragOffset by remember(node) { mutableStateOf(Offset.Zero) }
    // Only the horizontal position counts: a column is as tall as its cards, and dropping below
    // the last card of a short column must still land in it.
    fun columnAt(position: Offset) = columnBounds.entries.firstOrNull { (_, bounds) -> position.x >= bounds.left && position.x < bounds.right }?.key
    val target = dragging?.let { columnAt(pointer + dragOffset) }

    fun move(cardId: String, columnId: String) {
        node.move?.let { move -> context.onAction(move.copy(params = move.params + mapOf("card" to cardId, "column" to columnId))) }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
    ) {
        node.columns.forEach { column ->
            val hostsDragged = column.cards.any { it.id == dragging }
            val highlighted = target == column.id && !hostsDragged
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .width(node.columnWidth.dp)
                    .zIndex(if (hostsDragged) 1f else 0f)
                    .onGloballyPositioned { columnBounds[column.id] = it.boundsInRoot() }
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
                column.cards.forEach { card -> key(card.id) {
                    var origin by remember(card.id) { mutableStateOf(Offset.Zero) }
                    var menu by remember(card.id) { mutableStateOf(false) }
                    val isDragged = dragging == card.id
                    val canMove = node.move != null && !context.busy
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { origin = it.positionInRoot() }
                            .graphicsLayer {
                                if (isDragged) {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y
                                    shadowElevation = 12f
                                }
                            }
                            .background(colors.surface, RoundedCornerShape(12.dp))
                            .border(1.dp, if (isDragged) colors.accent else colors.border, RoundedCornerShape(12.dp))
                            .then(card.action?.let { action -> Modifier.clickable(enabled = !context.busy) { context.onAction(action) } } ?: Modifier)
                            // After clickable so the drag sees the pointer first and its consumed events
                            // cancel the click (opening the card) and the board scroll.
                            .then(
                                if (canMove) Modifier.pointerInput(card.id, column.id) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val start = if (down.type == PointerType.Mouse) {
                                            awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                        } else {
                                            awaitLongPressOrCancellation(down.id)
                                        } ?: return@awaitEachGesture
                                        dragging = card.id
                                        pointer = origin + down.position
                                        dragOffset = start.position - down.position
                                        var dropped: String? = null
                                        try {
                                            val completed = drag(start.id) { change ->
                                                dragOffset += change.positionChange()
                                                change.consume()
                                            }
                                            if (completed) {
                                                // The release must not also open the card.
                                                currentEvent.changes.forEach { it.consume() }
                                                dropped = columnAt(pointer + dragOffset)
                                            }
                                        } finally {
                                            dragging = null
                                            dragOffset = Offset.Zero
                                        }
                                        dropped?.takeIf { it != column.id }?.let { move(card.id, it) }
                                    }
                                } else Modifier
                            )
                            .padding(10.dp),
                    ) {
                        Text(card.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (card.subtitle.isNotBlank()) Text(card.subtitle, color = colors.muted, fontSize = 12.sp, maxLines = 2)
                        card.details.forEach { Text(it, color = colors.muted, fontSize = 12.sp) }
                        ModuleBadges(card.badges, colors)
                        if (canMove && node.columns.size > 1) {
                            Box {
                                Text(
                                    "Mover a...",
                                    color = colors.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { menu = true }.padding(vertical = 2.dp),
                                )
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    node.columns.filter { it.id != column.id }.forEach { other ->
                                        DropdownMenuItem(
                                            text = { Text(other.title) },
                                            onClick = {
                                                menu = false
                                                move(card.id, other.id)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                } }
                column.footer?.let { ModuleButton(it, context) }
            }
        }
    }
}

@Composable
private fun ModuleList(node: UiList, context: ModuleRenderContext) {
    val colors = context.colors
    if (node.items.isEmpty()) {
        Text(node.emptyText, color = colors.muted, fontSize = 13.sp)
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        node.items.forEachIndexed { index, item ->
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
    }
}

@Composable
private fun ModuleTimeline(node: UiTimeline, colors: ModuleColors) {
    if (node.entries.isEmpty()) {
        Text(node.emptyText, color = colors.muted, fontSize = 13.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        node.entries.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 4.dp).size(10.dp).background(colors.tone(entry.tone), CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text(entry.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (entry.body.isNotBlank()) Text(entry.body, color = colors.text, fontSize = 13.sp)
                    if (entry.caption.isNotBlank()) Text(entry.caption, color = colors.muted, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun formatValue(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()
