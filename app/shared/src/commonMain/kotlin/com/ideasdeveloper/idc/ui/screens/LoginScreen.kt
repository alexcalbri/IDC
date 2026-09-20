// app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/ui/screens/LoginScreen.kt
package com.ideasdeveloper.idc.ui.screens

import androidx.compose.foundation.background
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var rememberMe by rememberSaveable { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFF667EEA), Color(0xFF764BA2))
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
            // Logo Placeholder (geometric)
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
                text = "IdeasCore",
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

            // Email Field
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.15f), MaterialTheme.shapes.medium)
            ) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Correo electrónico", color = Color.White) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color(0xFFCCCCCC),
                        cursorColor = Color.White,
                        focusedBorderColor = Color.White.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Password Field
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
                    trailingIcon = {
                        val iconText = if (showPassword) "👁️" else "👁️‍🗨️"
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(iconText, color = Color.White)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,

                    textStyle = LocalTextStyle.current.copy(color = Color.White),

                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color(0xFFCCCCCC),
                        cursorColor = Color.White,
                        focusedBorderColor = Color.White.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
            }


            Spacer(modifier = Modifier.height(16.dp))

            // Remember me + Forgot password row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rememberMe,
                        onCheckedChange = { rememberMe = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color.White,
                            uncheckedColor = Color.White,
                            checkmarkColor = Color(0xFF667EEA),
                        )
                    )
                    Text(
                        text = "RecordarMe",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }

                TextButton(onClick = { /* TODO: Navigate to forgot password */ }) {
                    Text(
                        text = "¿Olvidaste tu contraseña?",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Login Button
            Button(
                onClick = {
                    isLoading = true
                    // TODO: Implement actual login API call
                    onLoginSuccess()
                },
                enabled = email.isNotBlank() && password.isNotBlank() && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF667EEA),
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color(0xFF667EEA),
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

            // Sign up link
            TextButton(onClick = { /* TODO: Navigate to sign up */ }) {
                Text(
                    text = "¿No tienes cuenta? Regístrate",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

        // Footer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = CenterHorizontally
        ) {
            Text(
                text = "IdeasCore v0.1.0",
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