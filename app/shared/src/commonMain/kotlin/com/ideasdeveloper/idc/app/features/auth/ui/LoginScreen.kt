package com.ideasdeveloper.idc.app.features.auth.ui

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
    val primaryColor = remember(companyIdentity.primaryColor) {
        companyIdentity.primaryColor.toComposeColor(Color(0xFF667EEA))
    }
    val secondaryColor = remember(companyIdentity.secondaryColor) {
        companyIdentity.secondaryColor.toComposeColor(Color(0xFF764BA2))
    }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(primaryColor, secondaryColor)
                )
            )
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
                            colors = listOf(Color.White, Color(0xFFE0E0E0)),
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
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp)
            )

            Text(
                text = "Inicia sesión en tu cuenta",
                fontSize = 16.sp,
                color = Color(0xFFE0E0E0),
                modifier = Modifier.padding(vertical = 8.dp).padding(bottom = 32.dp)
            )
            Spacer(Modifier.height(16.dp))
            if (needsServerConfiguration) {
                // Solicita la URL solo cuando no existe configuracion local previa.
                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.15f), MaterialTheme.shapes.medium)
                ) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("URL del servidor") },
                        placeholder = { Text("https://sub.domain.tld o http://sub.domain.tld") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        textStyle = LocalTextStyle.current.copy(color = Color.White),
                        enabled = !isLoading,
                        colors = TextFieldDefaults.colors(
                            focusedLabelColor = Color.White,
                            unfocusedLabelColor = Color(0xFFCCCCCC),
                            cursorColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.5f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
                if (!serverAdministration) {
                    // Solicita codigo de empresa solo para sesiones tenant.
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.15f), MaterialTheme.shapes.medium)
                    ) {
                        OutlinedTextField(
                            value = companyCode,
                            onValueChange = { companyCode = it },
                            label = { Text("Código de empresa") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            textStyle = LocalTextStyle.current.copy(color = Color.White),
                            enabled = !isLoading,
                            colors = TextFieldDefaults.colors(
                                focusedLabelColor = Color.White,
                                unfocusedLabelColor = Color(0xFFCCCCCC),
                                cursorColor = Color.White,
                                focusedContainerColor = Color.White.copy(alpha = 0.5f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.3f),
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

            // Captura el usuario con el que se autenticara contra el servidor.
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.15f), MaterialTheme.shapes.medium)
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario", color = Color.White) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    enabled = !isLoading,
                    colors = TextFieldDefaults.colors(
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color(0xFFCCCCCC),
                        cursorColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.5f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Captura la contrasena y permite alternar su visibilidad.
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.15f), MaterialTheme.shapes.medium)
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña", color = Color.White) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !isLoading,
                    trailingIcon = {
                        val iconText = if (showPassword) "👁️" else "👁️‍🗨️"
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(iconText, color = Color.White)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,

                    textStyle = LocalTextStyle.current.copy(color = Color.White),

                    colors = TextFieldDefaults.colors(
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color(0xFFCCCCCC),
                        cursorColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.5f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.3f),
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
                Text("Mantener sesión iniciada", color = Color.White)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Muestra errores de validacion local o del servidor.
            state.error?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
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
                    containerColor = Color.White,
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
                    Text("Administrar servidor", color = Color.White)
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
                    text = "IdeasCore v0.2.0",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Text(
                    text = "Flow Tier",
                    color = Color.White.copy(alpha = 0.5f),
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
