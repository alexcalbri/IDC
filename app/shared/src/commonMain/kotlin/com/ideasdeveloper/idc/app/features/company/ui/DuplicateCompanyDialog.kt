package com.ideasdeveloper.idc.app.features.company.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import com.ideasdeveloper.idc.app.features.company.data.DuplicateOwnerRequest

private val CodePattern = Regex("[a-z][a-z0-9_]{0,62}")
private val EmailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/**
 * Datos para duplicar [sourceName] (solo el dueno del servidor): el codigo de la empresa nueva y si
 * mantiene el business owner de la original o recibe uno nuevo. La copia la hace el servidor.
 */
@Composable
fun DuplicateCompanyDialog(
    sourceName: String,
    busy: Boolean,
    error: String?,
    onConfirm: (code: String, newOwner: DuplicateOwnerRequest?) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var keepOwner by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    val ownerValid = keepOwner ||
        (CodePattern.matches(username) && password.length >= 12 && name.isNotBlank() && EmailPattern.matches(email.trim()))
    val valid = CodePattern.matches(code) && ownerValid

    fun identifier(input: String) = input.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(63)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Duplicar $sourceName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Se crea una empresa nueva con los mismos modulos y su configuracion (etapas, campos, plantillas, redes WiFi...). " +
                        "No se copian clientes, leads ni usuarios del personal, y las integraciones (correo, WhatsApp, UniFi, WordPress...) " +
                        "se conectan de nuevo.",
                    color = AppTheme.colors.muted,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = identifier(it) },
                    label = { Text("Codigo de la nueva empresa") },
                    placeholder = { Text("mi_empresa_2") },
                    singleLine = true,
                    enabled = !busy,
                    isError = code.isNotEmpty() && !CodePattern.matches(code),
                    supportingText = { Text("Empieza con una letra; solo minusculas, numeros y guion bajo.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Business owner", color = AppTheme.colors.text)
                OwnerOption("Mantener el de $sourceName", "Entra con el mismo usuario y contrasena y cambia entre ambas empresas desde el panel.", keepOwner, !busy) { keepOwner = true }
                OwnerOption("Crear uno nuevo", "La empresa queda con su propio dueno, sin vincular a $sourceName.", !keepOwner, !busy) { keepOwner = false }
                if (!keepOwner) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = identifier(it) }, label = { Text("Usuario") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Contrasena (12 caracteres o mas)") },
                        singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text("Nombre") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = email, onValueChange = { email = it }, label = { Text("Correo") },
                        singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (busy) Text("Duplicando... puede tardar un minuto.", color = AppTheme.colors.muted)
                error?.let { Text(it, color = AppTheme.colors.danger) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(code, if (keepOwner) null else DuplicateOwnerRequest(username, password, name.trim(), email.trim())) },
                enabled = valid && !busy,
            ) { Text("Duplicar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        },
    )
}

@Composable
private fun OwnerOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, color = AppTheme.colors.text)
            Text(detail, color = AppTheme.colors.muted)
        }
    }
}
