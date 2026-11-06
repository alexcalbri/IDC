package com.ideasdeveloper.idc.app.features.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import com.ideasdeveloper.idc.app.features.auth.data.AccountApi
import com.ideasdeveloper.idc.app.features.auth.data.AccountProfile
import com.ideasdeveloper.idc.app.features.auth.data.LoginException
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.auth.data.use
import kotlinx.coroutines.launch

/**
 * Nombre y correo del usuario. [required]: un usuario creado antes de que fueran obligatorios no
 * puede continuar hasta completarlos (solo puede cerrar sesion).
 */
@Composable
fun ProfileDialog(
    serverUrl: String?,
    session: LoginResponse,
    required: Boolean,
    onSaved: (AccountProfile) -> Unit,
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf(session.displayName.orEmpty()) }
    var email by remember { mutableStateOf(session.email.orEmpty()) }
    var userCode by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverUrl, session.accessToken, session.companyCode) {
        val url = serverUrl ?: return@LaunchedEffect
        try {
            userCode = AccountApi(url).use { it.profile(session.accessToken, session.companyCode).userCode }
        } catch (exception: LoginException) {
            error = exception.message
        }
    }

    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        title = { Text(if (required) "Completa tu perfil" else "Mi perfil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (required) {
                    Text("Para continuar escribe tu nombre y tu correo. Tus cambios quedan registrados con tu nombre y el correo sirve para recuperar tu contrasena.")
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Nombre completo") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text("Correo") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (userCode.isNotBlank()) {
                    OutlinedTextField(
                        value = userCode,
                        onValueChange = {},
                        label = { Text("Codigo de usuario") },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = AppTheme.colors.danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && serverUrl != null && displayName.isNotBlank() && email.isNotBlank(),
                onClick = {
                    val url = serverUrl ?: return@TextButton
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val profile = AccountApi(url).use { it.updateProfile(session.accessToken, session.companyCode, displayName, email) }
                            onSaved(profile)
                        } catch (exception: LoginException) {
                            error = exception.message
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text("Guardar") }
        },
        dismissButton = {
            if (required) {
                TextButton(onClick = onLogout, enabled = !saving) { Text("Cerrar sesion") }
            } else {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancelar") }
            }
        },
    )
}

/**
 * Recuperacion de contrasena: pide un codigo que llega al correo del usuario y luego cambia la
 * contrasena con ese codigo. [companyCode] null es el dueno del servidor.
 */
@Composable
fun PasswordResetDialog(
    serverUrl: String,
    companyCode: String?,
    initialUsername: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(initialUsername.trim()) }
    var codeSent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val minimum = if (companyCode == null) 16 else 12

    fun run(block: suspend (AccountApi) -> Unit) {
        working = true
        error = null
        scope.launch {
            try {
                AccountApi(serverUrl).use { block(it) }
            } catch (exception: LoginException) {
                error = exception.message
            } finally {
                working = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Recuperar contrasena") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (companyCode == null) "Dueno del servidor" else "Empresa: $companyCode",
                    color = AppTheme.colors.muted,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("Usuario") },
                    singleLine = true,
                    enabled = !working && !codeSent && !done,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (codeSent && !done) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { value -> code = value.filter(Char::isDigit).take(6) },
                        label = { Text("Codigo de 6 digitos") },
                        singleLine = true,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Nueva contrasena (minimo $minimum)") },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Confirmar contrasena") },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = confirmation.isNotEmpty() && confirmation != newPassword,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                info?.let { Text(it, color = AppTheme.colors.success) }
                error?.let { Text(it, color = AppTheme.colors.danger) }
            }
        },
        confirmButton = {
            when {
                done -> TextButton(onClick = onDismiss) { Text("Iniciar sesion") }
                !codeSent -> TextButton(
                    enabled = !working && username.isNotBlank(),
                    onClick = {
                        run { api ->
                            info = api.requestPasswordReset(username, companyCode)
                            codeSent = true
                        }
                    },
                ) { Text("Enviar codigo") }
                else -> TextButton(
                    enabled = !working && code.length == 6 && newPassword.length >= minimum && newPassword == confirmation,
                    onClick = {
                        run { api ->
                            info = api.confirmPasswordReset(username, companyCode, code, newPassword)
                            done = true
                        }
                    },
                ) { Text("Cambiar contrasena") }
            }
        },
        dismissButton = {
            if (!done) {
                if (codeSent) {
                    TextButton(enabled = !working, onClick = { run { api -> info = api.requestPasswordReset(username, companyCode) } }) {
                        Text("Reenviar codigo")
                    }
                }
                TextButton(enabled = !working, onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
