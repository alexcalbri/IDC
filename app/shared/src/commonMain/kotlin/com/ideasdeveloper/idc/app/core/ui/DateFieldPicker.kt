package com.ideasdeveloper.idc.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.theme.AppTheme

// Fecha "AAAA-MM-DD" o fecha y hora "AAAA-MM-DDTHH:mm" ya separadas y validadas contra el calendario.
private class ParsedDate(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int)

private const val MillisPerDay = 86_400_000L
private val DatePickerYears = 1900..2100

private fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (isLeap(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private fun parseDateValue(value: String, withTime: Boolean): ParsedDate? {
    val text = value.trim()
    val datePart = if (withTime) text.substringBefore('T') else text
    val timePart = if (withTime) text.substringAfter('T', "") else ""
    val pieces = datePart.split('-')
    if (pieces.size != 3 || pieces[0].length != 4 || pieces[1].length != 2 || pieces[2].length != 2) return null
    val year = pieces[0].toIntOrNull() ?: return null
    val month = pieces[1].toIntOrNull() ?: return null
    val day = pieces[2].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    var hour = 0
    var minute = 0
    if (withTime) {
        val clock = timePart.split(':')
        if (clock.size != 2 || clock[0].length != 2 || clock[1].length != 2) return null
        hour = clock[0].toIntOrNull() ?: return null
        minute = clock[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
    }
    return ParsedDate(year, month, day, hour, minute)
}

// Dias desde 1970-01-01 (algoritmo civil de Howard Hinnant); evita depender de kotlinx-datetime.
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146_097 + doe - 719_468
}

private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = (z - era * 146_097).toInt()
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = (yoe + era * 400).toInt() + if (month <= 2) 1 else 0
    return Triple(year, month, day)
}

private fun two(value: Int): String = value.toString().padStart(2, '0')

/**
 * Campo de fecha (`withTime = false`, valor "AAAA-MM-DD") o de fecha y hora (`withTime = true`, valor "AAAA-MM-DDTHH:mm").
 * Se puede escribir a mano (se marca error si el formato no es valido) o abrir el calendario con el boton del extremo.
 * El estado del dialogo es local: escribir en otros campos del formulario no lo toca.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateFieldPicker(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    withTime: Boolean = false,
    enabled: Boolean = true,
    placeholder: String = if (withTime) "AAAA-MM-DDTHH:mm" else "AAAA-MM-DD",
    textFieldColors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val colors = AppTheme.colors
    // 0 = cerrado, 1 = calendario, 2 = hora (solo datetime).
    var step by remember { mutableStateOf(0) }
    var pickedDay by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    val parsed = remember(value, withTime) { parseDateValue(value, withTime) }
    val invalid = value.isNotBlank() && parsed == null

    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() || it == '-' || it == ':' || it == 'T' }) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text("Formato $placeholder") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        trailingIcon = {
            TextButton(onClick = { step = 1 }, enabled = enabled) {
                Text("Calendario", color = if (enabled) colors.accentText else colors.muted)
            }
        },
        colors = textFieldColors,
        modifier = modifier,
    )

    if (step == 1) {
        // Un valor tipeado fuera del rango del calendario se ignora (abre en hoy); si no, el estado lanza excepcion.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = parsed?.takeIf { it.year in DatePickerYears }?.let { daysFromCivil(it.year, it.month, it.day) * MillisPerDay },
            yearRange = DatePickerYears,
        )
        val dismiss = { step = 0 }
        DatePickerDialog(
            onDismissRequest = dismiss,
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        val millis = state.selectedDateMillis ?: return@TextButton
                        val day = civilFromDays(millis.floorDiv(MillisPerDay))
                        if (withTime) {
                            pickedDay = day
                            step = 2
                        } else {
                            onValueChange("${day.first.toString().padStart(4, '0')}-${two(day.second)}-${two(day.third)}")
                            step = 0
                        }
                    },
                ) { Text(if (withTime) "Siguiente" else "Aceptar", color = colors.accentText) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onValueChange(""); step = 0 }) { Text("Borrar", color = colors.danger) }
                    TextButton(onClick = dismiss) { Text("Cancelar", color = colors.accentText) }
                }
            },
            colors = DatePickerDefaults.colors(containerColor = colors.surface),
        ) {
            DatePicker(
                state = state,
                colors = DatePickerDefaults.colors(
                    containerColor = colors.surface,
                    titleContentColor = colors.muted,
                    headlineContentColor = colors.text,
                    weekdayContentColor = colors.muted,
                    subheadContentColor = colors.muted,
                    navigationContentColor = colors.text,
                    yearContentColor = colors.text,
                    currentYearContentColor = colors.accentText,
                    selectedYearContentColor = colors.onAccent,
                    selectedYearContainerColor = colors.accent,
                    dayContentColor = colors.text,
                    selectedDayContentColor = colors.onAccent,
                    selectedDayContainerColor = colors.accent,
                    todayContentColor = colors.accentText,
                    todayDateBorderColor = colors.accentText,
                ),
            )
        }
    }

    val day = pickedDay
    if (step == 2 && day != null) {
        val time = rememberTimePickerState(
            initialHour = parsed?.hour ?: 12,
            initialMinute = parsed?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { step = 0 },
            containerColor = colors.surface,
            title = { Text("Hora", color = colors.text) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    TimePicker(
                        state = time,
                        colors = TimePickerDefaults.colors(
                            clockDialColor = colors.surfaceAlt,
                            clockDialSelectedContentColor = colors.onAccent,
                            clockDialUnselectedContentColor = colors.text,
                            selectorColor = colors.accent,
                            containerColor = colors.surface,
                            timeSelectorSelectedContainerColor = colors.accent,
                            timeSelectorUnselectedContainerColor = colors.surfaceAlt,
                            timeSelectorSelectedContentColor = colors.onAccent,
                            timeSelectorUnselectedContentColor = colors.text,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange("${day.first.toString().padStart(4, '0')}-${two(day.second)}-${two(day.third)}T${two(time.hour)}:${two(time.minute)}")
                    step = 0
                }) { Text("Aceptar", color = colors.accentText) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onValueChange(""); step = 0 }) { Text("Borrar", color = colors.danger) }
                    TextButton(onClick = { step = 0 }) { Text("Cancelar", color = colors.accentText) }
                }
            },
        )
    }
}
