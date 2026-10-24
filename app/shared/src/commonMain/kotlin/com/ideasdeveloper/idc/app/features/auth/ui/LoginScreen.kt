package com.ideasdeveloper.idc.app.features.auth.ui

import com.ideasdeveloper.idc.app.core.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.core.config.ClientConfigurationStore
import com.ideasdeveloper.idc.app.core.version.ClientVersion
import com.ideasdeveloper.idc.app.core.version.VersionApi
import com.ideasdeveloper.idc.app.features.auth.presentation.LoginViewModel

@Composable
// Renderiza el formulario de inicio de sesion y dispara la navegacion cuando el login termina bien.
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    initialServerUrl: String = "",
) {
    // Prepara el estado de login y la configuracion guardada para evitar pedir datos repetidos.
    val loginViewModel: LoginViewModel = viewModel {
        LoginViewModel()
    }
    val state by loginViewModel.state.collectAsState()
    val savedConfiguration = remember { ClientConfigurationStore.load() }
    val companyIdentity = remember { CompanyIdentityStore.current() }
    val colors = AppTheme.colors
    val primaryColor = colors.accent

    var serverUrl by rememberSaveable {
        mutableStateOf(savedConfiguration?.serverUrl ?: initialServerUrl)
    }
    var companyCode by rememberSaveable {
        mutableStateOf(savedConfiguration?.companyCode ?: "")
    }
    var serverAdministration by rememberSaveable {
        mutableStateOf(savedConfiguration?.serverAdministration ?: false)
    }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var keepSignedIn by rememberSaveable { mutableStateOf(false) }
    var versionMessage by remember { mutableStateOf<String?>(null) }
    var administratorClickCount by rememberSaveable { mutableStateOf(0) }
    var administratorToggleVisible by rememberSaveable { mutableStateOf(false) }
    val needsServerConfiguration = savedConfiguration == null

    val isLoading = state.isLoading

    // Cuando el ViewModel confirma el login, limpia la contrasena y avanza al dashboard.
    LaunchedEffect(state.succeeded) {
        if (state.succeeded) {
            password = ""
            onLoginSuccess()
        }
    }

    LaunchedEffect(serverUrl) {
        if (serverUrl.isNotBlank()) {
            val version = VersionApi(serverUrl).use { it.version() }
            versionMessage = version?.latestAppVersion
                ?.takeIf { it.isNotBlank() && it != ClientVersion }
                ?.let { "Nueva version de la app disponible: $it" }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = CenterHorizontally
        ) {
            // Renderiza el logo provisional con la identidad visual configurada.
            Spacer(modifier = Modifier.height(40.dp))
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(AppTheme.colors.surface, AppTheme.colors.surfaceAlt),
                            center = Offset(50f, 50f),
                            radius = 60f
                        ),
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            ) {}

            Text(
                text = companyIdentity.name,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.onScreen,
                modifier = Modifier.padding(top = 16.dp)
            )

            Text(
                text = "Inicia sesión en tu cuenta",
                fontSize = 16.sp,
                color = AppTheme.colors.onScreenMuted,
                modifier = Modifier.padding(vertical = 8.dp).padding(bottom = 32.dp)
            )
            Spacer(Modifier.height(16.dp))
            if (needsServerConfiguration) {
                // Solicita la URL solo cuando no existe configuracion local previa.
                Box(
                    modifier = Modifier
                        .background(AppTheme.colors.fieldOnScreen.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                ) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("URL del servidor") },
                        placeholder = { Text("https://sub.domain.tld o http://sub.domain.tld") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        textStyle = LocalTextStyle.current.copy(color = AppTheme.colors.onScreen),
                        enabled = !isLoading,
                        colors = TextFieldDefaults.colors(
                            focusedLabelColor = AppTheme.colors.onScreen,
                            unfocusedLabelColor = AppTheme.colors.onScreenMuted,
                            cursorColor = AppTheme.colors.onScreen,
                            focusedContainerColor = AppTheme.colors.fieldOnScreenFocused,
                            unfocusedContainerColor = AppTheme.colors.fieldOnScreen,
                        ),
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
                if (!serverAdministration) {
                    // Solicita codigo de empresa solo para sesiones tenant.
                    Box(
                        modifier = Modifier
                            .background(AppTheme.colors.fieldOnScreen.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                    ) {
                        OutlinedTextField(
                            value = companyCode,
                            onValueChange = { companyCode = it },
                            label = { Text("Código de empresa") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            textStyle = LocalTextStyle.current.copy(color = AppTheme.colors.onScreen),
                            enabled = !isLoading,
                            colors = TextFieldDefaults.colors(
                                focusedLabelColor = AppTheme.colors.onScreen,
                                unfocusedLabelColor = AppTheme.colors.onScreenMuted,
                                cursorColor = AppTheme.colors.onScreen,
                                focusedContainerColor = AppTheme.colors.fieldOnScreenFocused,
                                unfocusedContainerColor = AppTheme.colors.fieldOnScreen,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

            // Captura el usuario con el que se autenticara contra el servidor.
            Box(
                modifier = Modifier
                    .background(AppTheme.colors.fieldOnScreen.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario", color = AppTheme.colors.onScreen) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    textStyle = LocalTextStyle.current.copy(color = AppTheme.colors.onScreen),
                    enabled = !isLoading,
                    colors = TextFieldDefaults.colors(
                        focusedLabelColor = AppTheme.colors.onScreen,
                        unfocusedLabelColor = AppTheme.colors.onScreenMuted,
                        cursorColor = AppTheme.colors.onScreen,
                        focusedContainerColor = AppTheme.colors.fieldOnScreenFocused,
                        unfocusedContainerColor = AppTheme.colors.fieldOnScreen,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Captura la contrasena y permite alternar su visibilidad.
            Box(
                modifier = Modifier
                    .background(AppTheme.colors.fieldOnScreen.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña", color = AppTheme.colors.onScreen) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !isLoading,
                    trailingIcon = {
                        val iconText = if (showPassword) "👁️" else "👁️‍🗨️"
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(iconText, color = AppTheme.colors.onScreen)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,

                    textStyle = LocalTextStyle.current.copy(color = AppTheme.colors.onScreen),

                    colors = TextFieldDefaults.colors(
                        focusedLabelColor = AppTheme.colors.onScreen,
                        unfocusedLabelColor = AppTheme.colors.onScreenMuted,
                        cursorColor = AppTheme.colors.onScreen,
                        focusedContainerColor = AppTheme.colors.fieldOnScreenFocused,
                        unfocusedContainerColor = AppTheme.colors.fieldOnScreen,
                    ),
                )
            }


            Spacer(modifier = Modifier.height(16.dp))

            // Permite persistir la sesion para que la app abra directo al dashboard.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = keepSignedIn,
                    onCheckedChange = { keepSignedIn = it },
                    enabled = !isLoading,
                )
                Text("Mantener sesión iniciada", color = AppTheme.colors.onScreen)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Muestra errores de validacion local o del servidor.
            state.error?.let { message ->
                Text(
                    text = message,
                    color = AppTheme.colors.onScreen,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            versionMessage?.let { message ->
                Text(
                    text = message,
                    color = AppTheme.colors.onScreen,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // Envia credenciales, URL y codigo de empresa al ViewModel.
            Button(
                onClick = {
                    loginViewModel.login(
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                        companyCode = if (serverAdministration) null else companyCode,
                        keepSignedIn = keepSignedIn,
                    )
                },
                enabled = serverUrl.isNotBlank() &&
                        username.isNotBlank() &&
                        password.isNotEmpty() &&
                        (serverAdministration || companyCode.isNotBlank()) &&
                        !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.colors.surface,
                    contentColor = primaryColor,
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = primaryColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = "INICIAR SESIÓN",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Renderiza el pie con version y acceso oculto a administracion del servidor.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (administratorToggleVisible) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = serverAdministration,
                        onCheckedChange = { serverAdministration = it },
                        enabled = !isLoading,
                    )
                    Text("Administrar servidor", color = AppTheme.colors.onScreen)
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            Column(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (!administratorToggleVisible) {
                        administratorClickCount += 1
                        if (administratorClickCount >= 6) {
                            administratorToggleVisible = true
                        }
                    }
                },
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "IdeasCore v$ClientVersion",
                    color = AppTheme.colors.onScreen.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Text(
                    text = "Flow Tier",
                    color = AppTheme.colors.onScreen.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun String.toComposeColor(fallback: Color): Color {
    val hex = trim().removePrefix("#")
    if (hex.length != 6) return fallback
    return hex.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) } ?: fallback
}

private suspend inline fun <T> VersionApi.use(block: suspend (VersionApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
