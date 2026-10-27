package com.ideasdeveloper.idc.app.features.modules.ui

import com.ideasdeveloper.idc.app.core.theme.AppColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import com.ideasdeveloper.idc.ui.UiAction
import com.ideasdeveloper.idc.ui.UiBarChart
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiColumn
import com.ideasdeveloper.idc.ui.UiDivider
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiImage
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiSpacer
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import com.ideasdeveloper.idc.ui.UiUnknownAction

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
        if (node.title != null || node.actions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(node.title.orEmpty().uppercase(), color = colors.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { node.actions.forEach { ModuleNode(it, context) } }
            }
        }
        node.children.forEach { ModuleNode(it, context) }
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        // Agrupa campos consecutivos de la misma seccion bajo un titulo.
        val groups = mutableListOf<Pair<String, MutableList<UiField>>>()
        node.fields.forEach { field ->
            if (groups.isEmpty() || groups.last().first != field.section) groups += field.section to mutableListOf()
            groups.last().second += field
        }
        groups.forEach { (section, fields) ->
            if (section.isNotBlank()) {
                Text(section, color = context.colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
            ResponsiveGrid(fields.size, node.minFieldWidth.dp, 12.dp) { index ->
                val field = fields[index]
                ModuleField(field, context.formValue(node.id, field.key), !context.busy, context.colors) { value ->
                    context.setFormValue(node.id, field.key, value)
                }
            }
        }
        node.submit?.let { submit ->
            val missing = node.fields.firstOrNull { field ->
                field.required && !field.readOnly && field.type != "password" && context.formValue(node.id, field.key).isBlank()
            }
            ModuleButton(submit.copy(enabled = submit.enabled && missing == null), context)
            missing?.let { Text("Completa: ${it.label}", color = context.colors.muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ModuleField(field: UiField, value: String, enabled: Boolean, colors: ModuleColors, onChange: (String) -> Unit) {
    val label = field.label + if (field.required && !field.readOnly) " *" else ""
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            field.readOnly -> {
                Text(field.label, color = colors.muted, fontSize = 12.sp)
                Text(
                    value.ifBlank { "-" },
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

private fun formatValue(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()
