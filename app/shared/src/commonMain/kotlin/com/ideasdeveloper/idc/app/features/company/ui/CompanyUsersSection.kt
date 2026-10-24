package com.ideasdeveloper.idc.app.features.company.ui

import com.ideasdeveloper.idc.app.core.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.CompanyPermissionResponse
import com.ideasdeveloper.idc.app.features.company.data.CompanyUserResponse
import com.ideasdeveloper.idc.app.features.company.data.CreateCompanyUserRequest
import kotlinx.coroutines.launch

/**
 * Usuarios y permisos de una base de empresa. La usa el business_owner para su empresa y el
 * server_owner para el personal del espacio del servidor; los permisos vienen de los modulos activos.
 */
@Composable
fun CompanyUsersSection(
    serverUrl: String?,
    accessToken: String?,
    companyCode: String?,
    primaryColor: Color,
    description: String,
) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<CompanyUserResponse>>(emptyList()) }
    var permissions by remember { mutableStateOf<List<CompanyPermissionResponse>>(emptyList()) }
    var newUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var selectedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverUrl, accessToken, companyCode) {
        val activeServerUrl = serverUrl ?: return@LaunchedEffect
        val token = accessToken ?: return@LaunchedEffect
        val code = companyCode ?: return@LaunchedEffect
        isLoading = true
        try {
            val userData = CompanyApi(activeServerUrl).use { api -> api.companyUsers(token, code) }
            users = userData.users
            permissions = userData.permissions
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            isLoading = false
        }
    }

    Text("Usuarios y permisos", style = MaterialTheme.typography.titleMedium, color = primaryColor)
    Text(description, color = AppTheme.colors.muted)
    OutlinedTextField(
        value = newUsername,
        onValueChange = { newUsername = it },
        label = { Text("Usuario") },
        singleLine = true,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = newPassword,
        onValueChange = { newPassword = it },
        label = { Text("Contrasena inicial") },
        singleLine = true,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    )
    permissions.forEach { permission ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selectedPermissions.contains(permission.permissionId),
                onCheckedChange = { checked ->
                    selectedPermissions = if (checked) {
                        selectedPermissions + permission.permissionId
                    } else {
                        selectedPermissions - permission.permissionId
                    }
                },
                enabled = !isLoading,
            )
            Column {
                Text(permission.title, fontWeight = FontWeight.SemiBold, color = primaryColor)
                Text("${permission.moduleId}: ${permission.description}", color = AppTheme.colors.muted)
            }
        }
    }
    Button(
        enabled = !isLoading && serverUrl != null && accessToken != null && companyCode != null && newUsername.isNotBlank() && newPassword.isNotBlank(),
        onClick = {
            val activeServerUrl = serverUrl ?: return@Button
            val token = accessToken ?: return@Button
            val code = companyCode ?: return@Button
            isLoading = true
            error = null
            message = null
            scope.launch {
                try {
                    CompanyApi(activeServerUrl).use { api ->
                        api.createCompanyUser(
                            token,
                            code,
                            CreateCompanyUserRequest(
                                username = newUsername,
                                password = newPassword,
                                permissions = selectedPermissions.toList(),
                            ),
                        )
                        val userData = api.companyUsers(token, code)
                        users = userData.users
                        permissions = userData.permissions
                    }
                    newUsername = ""
                    newPassword = ""
                    selectedPermissions = emptySet()
                    message = "Usuario creado."
                } catch (exception: CompanyException) {
                    error = exception.message
                } finally {
                    isLoading = false
                }
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
    ) {
        Text("Crear usuario")
    }
    message?.let { Text(it, color = AppTheme.colors.success) }
    error?.let { Text(it, color = AppTheme.colors.danger) }
    users.forEach { user ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surfaceAlt, MaterialTheme.shapes.small)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(user.username, fontWeight = FontWeight.SemiBold, color = primaryColor)
            Text(if (user.isActive) "Activo" else "Inactivo", color = AppTheme.colors.muted)
            Text(
                "Permisos: ${user.permissions.ifEmpty { listOf("sin permisos") }.joinToString()}",
                color = AppTheme.colors.text,
            )
        }
    }
}

private suspend inline fun <T> CompanyApi.use(block: suspend (CompanyApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
