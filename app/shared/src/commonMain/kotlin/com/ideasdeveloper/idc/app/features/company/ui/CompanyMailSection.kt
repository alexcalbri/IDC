package com.ideasdeveloper.idc.app.features.company.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.CompanyMailForm
import com.ideasdeveloper.idc.app.features.company.data.CompanyMailInput
import com.ideasdeveloper.idc.app.features.shell.ui.CollapsibleSection
import kotlinx.coroutines.launch

// Opciones de seguridad del SMTP: valor del contrato y texto visible.
private val MailSecurityOptions = listOf("starttls" to "STARTTLS", "ssl" to "SSL", "none" to "Ninguna")

@Composable
// Seccion plegable "Correo / SMTP" de la empresa. Carga la configuracion al entrar en la pantalla.
// La contrasena vacia conserva la guardada; para borrarla hay una casilla explicita.
fun CompanyMailSection(
    serverUrl: String?,
    accessToken: String?,
    companyCode: String?,
    primaryColor: Color,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("587") }
    var username by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var security by remember { mutableStateOf("starttls") }
    var password by remember { mutableStateOf("") }
    var passwordSet by remember { mutableStateOf(false) }
    var clearPassword by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Quita el aviso de exito o error anterior cuando el usuario vuelve a editar.
    fun clearFeedback() {
        message = null
        error = null
    }

    // Aplica la respuesta del servidor y limpia los campos de un solo uso.
    fun apply(form: CompanyMailForm) {
        host = form.host
        port = form.port.toString()
        username = form.username
        from = form.from
        security = form.security
        passwordSet = form.passwordSet
        password = ""
        clearPassword = false
    }

    // El token se lee actualizado sin relanzar la carga: un refresh no pisa lo que se escribe.
    val currentToken by rememberUpdatedState(accessToken)

    LaunchedEffect(serverUrl, companyCode) {
        val url = serverUrl ?: return@LaunchedEffect
        val token = currentToken ?: return@LaunchedEffect
        val code = companyCode ?: return@LaunchedEffect
        isBusy = true
        try {
            val api = CompanyApi(url)
            try {
                apply(api.mailSettings(token, code))
                error = null
            } finally {
                api.close()
            }
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            isBusy = false
        }
    }

    val canEdit = !isBusy && serverUrl != null && accessToken != null && companyCode != null
    val portValue = port.toIntOrNull()

    CollapsibleSection(
        title = "Correo / SMTP",
        color = primaryColor,
        modifier = modifier,
        subtitle = "Servidor de salida para los correos de la empresa",
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it; clearFeedback() },
            label = { Text("Servidor (host)") },
            singleLine = true,
            enabled = canEdit,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { value -> port = value.filter { it.isDigit() }.take(5); clearFeedback() },
                label = { Text("Puerto") },
                singleLine = true,
                isError = portValue == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = canEdit,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; clearFeedback() },
                label = { Text("Usuario") },
                singleLine = true,
                enabled = canEdit,
                modifier = Modifier.weight(2f),
            )
        }
        OutlinedTextField(
            value = from,
            onValueChange = { from = it; clearFeedback() },
            label = { Text("Remitente (from)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = canEdit,
            modifier = Modifier.fillMaxWidth(),
        )

        // No hay selector reutilizable en la app: grupo de FilterChip de Material 3.
        Text("Seguridad", style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MailSecurityOptions.forEach { (value, label) ->
                FilterChip(
                    selected = security == value,
                    onClick = { security = value; clearFeedback() },
                    label = { Text(label) },
                    enabled = canEdit,
                )
            }
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; if (it.isNotEmpty()) clearPassword = false; clearFeedback() },
            label = { Text("Contrasena") },
            placeholder = { if (passwordSet) Text("••••• (sin cambios)") },
            supportingText = {
                Text(
                    if (passwordSet) "Vacio conserva la contrasena guardada." else "Aun no hay contrasena guardada.",
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = canEdit && !clearPassword,
            modifier = Modifier.fillMaxWidth(),
        )
        if (passwordSet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = clearPassword,
                    onCheckedChange = { clearPassword = it; if (it) password = ""; clearFeedback() },
                    enabled = canEdit,
                )
                Text("Borrar la contrasena guardada", color = AppTheme.colors.text)
            }
        }

        // Aviso, no bloqueo: sin cifrado las credenciales viajan en claro.
        if (security == "none" && (username.isNotBlank() || password.isNotEmpty())) {
            Text("Sin cifrado, las credenciales viajan en claro.", color = AppTheme.colors.danger)
        }
        error?.let { Text(it, color = AppTheme.colors.danger) }
        message?.let { Text(it, color = AppTheme.colors.success) }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                enabled = canEdit && portValue != null,
                onClick = {
                    val url = serverUrl ?: return@Button
                    val token = currentToken ?: return@Button
                    val code = companyCode ?: return@Button
                    val currentPort = portValue ?: return@Button
                    isBusy = true
                    error = null
                    message = null
                    scope.launch {
                        try {
                            val api = CompanyApi(url)
                            try {
                                val saved = api.updateMailSettings(
                                    token,
                                    code,
                                    CompanyMailInput(
                                        host = host.trim(),
                                        port = currentPort,
                                        username = username.trim(),
                                        from = from.trim(),
                                        security = security,
                                        // null conserva la guardada, "" la borra, un valor la reemplaza.
                                        password = when {
                                            clearPassword -> ""
                                            password.isNotEmpty() -> password
                                            else -> null
                                        },
                                    ),
                                )
                                apply(saved)
                                message = "Correo actualizado."
                            } finally {
                                api.close()
                            }
                        } catch (exception: CompanyException) {
                            error = exception.message
                        } finally {
                            isBusy = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(color = AppTheme.colors.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text("Guardar correo")
                }
            }
        }
    }
}
