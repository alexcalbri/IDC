package com.ideasdeveloper.idc.app.features.modules.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.platform.saveTextFile
import com.ideasdeveloper.idc.app.features.modules.data.ModuleApi
import com.ideasdeveloper.idc.app.features.modules.data.ModuleException
import com.ideasdeveloper.idc.app.features.modules.data.ModuleFieldDefinition
import com.ideasdeveloper.idc.app.features.modules.data.ModuleReport
import com.ideasdeveloper.idc.app.features.modules.data.ModuleReportChart
import com.ideasdeveloper.idc.app.features.modules.data.ModuleReportTable
import com.ideasdeveloper.idc.app.features.modules.data.ModuleViewDefinition
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Datos de sesion necesarios para ejecutar acciones de un modulo de empresa.
data class ModuleCallContext(val serverUrl: String, val accessToken: String, val companyCode: String)

private val ReportJson = Json { ignoreUnknownKeys = true }
private val ErrorColor = Color(0xFFB00020)
private val SuccessColor = Color(0xFF176B3A)
private val MutedColor = Color(0xFF666666)
private val PanelColor = Color(0xFFF6F6FA)

/** Vista kind = "form": carga valores con la accion "load" y los guarda con "save". */
@Composable
fun ModuleFormView(view: ModuleViewDefinition, context: ModuleCallContext, primaryColor: Color) {
    val scope = rememberCoroutineScope()
    val loadAction = view.actions.firstOrNull { it.id == "load" }
    val saveAction = view.actions.firstOrNull { it.id == "save" }
    var values by remember(view.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember(view.id) { mutableStateOf(false) }
    var message by remember(view.id) { mutableStateOf<String?>(null) }
    var error by remember(view.id) { mutableStateOf<String?>(null) }

    fun applyResponse(text: String) {
        values = Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
    }

    LaunchedEffect(view.id, context) {
        val action = loadAction ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            applyResponse(ModuleApi(context.serverUrl).use { it.action(context.accessToken, context.companyCode, action) })
        } catch (exception: ModuleException) {
            error = exception.message
        } finally {
            loading = false
        }
    }

    if (loading && values.isEmpty()) {
        CircularProgressIndicator(color = primaryColor)
        return
    }

    var currentSection = ""
    view.fields.forEach { field ->
        if (field.section.isNotBlank() && field.section != currentSection) {
            currentSection = field.section
            Text(
                field.section,
                style = MaterialTheme.typography.titleMedium,
                color = primaryColor,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        ModuleField(
            field = field,
            value = values[field.key].orEmpty(),
            enabled = saveAction != null && !loading,
            primaryColor = primaryColor,
            onChange = { value -> values = values + (field.key to value) },
        )
    }

    message?.let { Text(it, color = SuccessColor) }
    error?.let { Text(it, color = ErrorColor) }
    if (saveAction != null) {
        val missing = view.fields.firstOrNull { field ->
            field.required && !field.readOnly && field.type != "password" && values[field.key].isNullOrBlank()
        }
        Button(
            enabled = !loading && missing == null,
            onClick = {
                loading = true
                message = null
                error = null
                val body = JsonObject(
                    view.fields
                        .filter { !it.readOnly }
                        .associate { it.key to JsonPrimitive(values[it.key].orEmpty()) }
                )
                scope.launch {
                    try {
                        val response = ModuleApi(context.serverUrl).use {
                            it.action(context.accessToken, context.companyCode, saveAction, body = body)
                        }
                        if (response.isNotBlank()) applyResponse(response)
                        message = "Cambios guardados."
                    } catch (exception: ModuleException) {
                        error = exception.message
                    } finally {
                        loading = false
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
        ) { Text(if (loading) "Guardando..." else saveAction.title) }
        missing?.let { Text("Completa: ${it.label}", color = MutedColor) }
    }
}

@Composable
private fun ModuleField(
    field: ModuleFieldDefinition,
    value: String,
    enabled: Boolean,
    primaryColor: Color,
    onChange: (String) -> Unit,
) {
    val label = field.label + if (field.required && !field.readOnly) " *" else ""
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            field.readOnly -> {
                Text(field.label, color = MutedColor, style = MaterialTheme.typography.labelMedium)
                Text(
                    value.ifBlank { "-" },
                    color = Color(0xFF333333),
                    modifier = Modifier.fillMaxWidth().background(PanelColor, MaterialTheme.shapes.small).padding(10.dp),
                )
            }
            field.type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = value == "true", onCheckedChange = { onChange(it.toString()) }, enabled = enabled)
                Text(label)
            }
            field.type == "select" -> {
                Text(label, color = MutedColor, style = MaterialTheme.typography.labelMedium)
                field.options.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == option, onClick = { onChange(option) }, enabled = enabled)
                        Text(option)
                    }
                }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        onChange(if (field.type == "number") input.filter { it.isDigit() || it == '-' } else input)
                    },
                    label = { Text(label) },
                    placeholder = when (field.type) {
                        "date" -> ({ Text("AAAA-MM-DD") })
                        "color" -> ({ Text("#RRGGBB") })
                        else -> null
                    },
                    enabled = enabled,
                    singleLine = field.type != "textarea",
                    minLines = if (field.type == "textarea") 3 else 1,
                    visualTransformation = if (field.type == "password") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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
                    modifier = Modifier.weight(1f),
                )
                if (field.type == "color") {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(value.toComposeColor(Color.Transparent), MaterialTheme.shapes.small)
                            .border(1.dp, Color(0xFFCCCCCC), MaterialTheme.shapes.small)
                    )
                }
            }
        }
        if (field.help.isNotBlank()) {
            Text(field.help, color = MutedColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Vista kind = "report": los campos son filtros; "load" trae el reporte y "export" un archivo. */
@Composable
fun ModuleReportView(moduleId: String, view: ModuleViewDefinition, context: ModuleCallContext, primaryColor: Color) {
    val scope = rememberCoroutineScope()
    val loadAction = view.actions.firstOrNull { it.id == "load" }
    val exportAction = view.actions.firstOrNull { it.id == "export" }
    var filters by remember(view.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var report by remember(view.id) { mutableStateOf<ModuleReport?>(null) }
    var loading by remember(view.id) { mutableStateOf(false) }
    var message by remember(view.id) { mutableStateOf<String?>(null) }
    var error by remember(view.id) { mutableStateOf<String?>(null) }

    fun load() {
        val action = loadAction ?: return
        loading = true
        error = null
        message = null
        scope.launch {
            try {
                val text = ModuleApi(context.serverUrl).use { it.action(context.accessToken, context.companyCode, action, query = filters) }
                report = ReportJson.decodeFromString(ModuleReport.serializer(), text)
            } catch (exception: ModuleException) {
                error = exception.message
            } catch (_: IllegalArgumentException) {
                error = "El servidor devolvio un reporte que esta version de la app no entiende."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(view.id, context) { load() }

    view.fields.forEach { field ->
        ModuleField(field, filters[field.key].orEmpty(), enabled = !loading, primaryColor = primaryColor) { value ->
            filters = filters + (field.key to value)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loadAction != null) {
            Button(enabled = !loading, onClick = { load() }, colors = ButtonDefaults.buttonColors(containerColor = primaryColor)) {
                Text(loadAction.title)
            }
        }
        if (exportAction != null) {
            Button(
                enabled = !loading,
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            val text = ModuleApi(context.serverUrl).use {
                                it.action(context.accessToken, context.companyCode, exportAction, query = filters)
                            }
                            message = saveTextFile("$moduleId-${view.id}.csv", text, "text/csv;charset=utf-8")
                        } catch (exception: ModuleException) {
                            error = exception.message
                        } catch (exception: Exception) {
                            error = "No se pudo guardar el archivo: ${exception.message}"
                        } finally {
                            loading = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9E9EF), contentColor = primaryColor),
            ) { Text(exportAction.title) }
        }
    }
    message?.let { Text(it, color = SuccessColor) }
    error?.let { Text(it, color = ErrorColor) }
    if (loading) CircularProgressIndicator(color = primaryColor)

    val current = report ?: return
    current.metrics.chunked(2).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { metric ->
                Column(modifier = Modifier.weight(1f).background(PanelColor, MaterialTheme.shapes.small).padding(12.dp)) {
                    Text(metric.label, color = MutedColor, style = MaterialTheme.typography.labelMedium)
                    Text(metric.value, color = primaryColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (metric.help.isNotBlank()) Text(metric.help, color = MutedColor, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (row.size == 1) Box(modifier = Modifier.weight(1f))
        }
    }
    current.charts.forEach { chart -> ReportBarChart(chart, primaryColor) }
    current.tables.forEach { table -> ReportTableView(table, primaryColor) }
}

@Composable
private fun ReportBarChart(chart: ModuleReportChart, primaryColor: Color) {
    Text(chart.title, style = MaterialTheme.typography.titleMedium, color = primaryColor, modifier = Modifier.fillMaxWidth())
    val max = chart.points.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(PanelColor, MaterialTheme.shapes.small).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        chart.points.forEach { point ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
                Text(formatNumber(point.value), style = MaterialTheme.typography.labelSmall, color = MutedColor)
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height((4 + 136 * (point.value / max)).dp)
                        .background(primaryColor, MaterialTheme.shapes.extraSmall)
                )
                Text(point.label, style = MaterialTheme.typography.labelSmall, color = MutedColor)
            }
        }
    }
}

@Composable
private fun ReportTableView(table: ModuleReportTable, primaryColor: Color) {
    Text(table.title, style = MaterialTheme.typography.titleMedium, color = primaryColor, modifier = Modifier.fillMaxWidth())
    if (table.rows.isEmpty()) {
        Text("Sin datos en este rango.", color = MutedColor)
        return
    }
    Column(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(modifier = Modifier.background(PanelColor)) {
            table.columns.forEach { column ->
                Text(column.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(170.dp).padding(8.dp))
            }
        }
        table.rows.forEach { row ->
            Row {
                table.columns.forEach { column ->
                    Text(row[column.key].orEmpty(), color = Color(0xFF333333), modifier = Modifier.width(170.dp).padding(8.dp))
                }
            }
        }
    }
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()

private suspend inline fun <T> ModuleApi.use(block: suspend (ModuleApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
