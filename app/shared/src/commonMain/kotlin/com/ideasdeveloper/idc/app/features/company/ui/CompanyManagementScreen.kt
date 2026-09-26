package com.ideasdeveloper.idc.app.features.company.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentity
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanyBackupResponse
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.CompanyProfileResponse
import com.ideasdeveloper.idc.app.features.company.data.UpdateCompanyProfileRequest
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import kotlinx.coroutines.launch

@Composable
// Renderiza la administracion de la empresa para el usuario business_owner.
fun CompanyManagementScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val companyIdentity = remember { CompanyIdentityStore.current() }
    var primaryColor by remember { mutableStateOf(companyIdentity.primaryColor) }
    var secondaryColor by remember { mutableStateOf(companyIdentity.secondaryColor) }
    val primaryCompose = primaryColor.toComposeColor(Color(0xFF667EEA))
    val secondaryCompose = secondaryColor.toComposeColor(Color(0xFF764BA2))

    var name by remember { mutableStateOf(companyIdentity.name) }
    var logoUrl by remember { mutableStateOf(companyIdentity.logoUrl.orEmpty()) }
    var accentColor by remember { mutableStateOf(companyIdentity.accentColor) }
    var backups by remember { mutableStateOf<List<CompanyBackupResponse>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Recarga los ZIPs disponibles para la empresa autenticada.
    fun refreshBackups() {
        val activeServerUrl = serverUrl ?: return
        val activeSession = session ?: return
        val companyCode = activeSession.companyCode ?: return
        scope.launch {
            try {
                backups = CompanyApi(activeServerUrl).use { api ->
                    api.listBackups(activeSession.accessToken, companyCode)
                }
            } catch (exception: CompanyException) {
                error = exception.message
            }
        }
    }

    // Aplica el perfil recibido del servidor a la UI y a la identidad visual local.
    fun applyProfile(profile: CompanyProfileResponse) {
        name = profile.name
        logoUrl = profile.logoUrl.orEmpty()
        primaryColor = profile.primaryColor
        secondaryColor = profile.secondaryColor
        accentColor = profile.accentColor
        CompanyIdentityStore.save(
            CompanyIdentity(
                id = profile.code,
                code = profile.code,
                name = profile.name,
                logoUrl = profile.logoUrl,
                primaryColor = profile.primaryColor,
                secondaryColor = profile.secondaryColor,
                accentColor = profile.accentColor,
            )
        )
    }

    LaunchedEffect(serverUrl, session?.accessToken, session?.companyCode) {
        val activeServerUrl = serverUrl ?: return@LaunchedEffect
        val activeSession = session ?: return@LaunchedEffect
        val companyCode = activeSession.companyCode ?: return@LaunchedEffect
        isLoading = true
        try {
            CompanyApi(activeServerUrl).use { api ->
                applyProfile(api.companyProfile(activeSession.accessToken, companyCode))
                backups = api.listBackups(activeSession.accessToken, companyCode)
            }
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(primaryCompose, secondaryCompose)))
    ) {
        AuthenticatedTopBar(
            title = "Empresa",
            subtitle = "Perfil y backups",
            primaryColor = primaryCompose,
            onSettings = onSettings,
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            shape = MaterialTheme.shapes.medium,
            color = Color.White,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Datos de empresa", style = MaterialTheme.typography.titleLarge, color = primaryCompose)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = logoUrl,
                    onValueChange = { logoUrl = it },
                    label = { Text("Logo URL") },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = primaryColor,
                        onValueChange = { primaryColor = it },
                        label = { Text("Color primario") },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = secondaryColor,
                        onValueChange = { secondaryColor = it },
                        label = { Text("Color secundario") },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = accentColor,
                    onValueChange = { accentColor = it },
                    label = { Text("Color acento") },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let { Text(it, color = Color(0xFFB00020)) }
                message?.let { Text(it, color = Color(0xFF176B3A)) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onMinimize,
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9E9EF), contentColor = primaryCompose),
                    ) {
                        Text("Minimizar")
                    }
                    Button(
                        enabled = !isLoading && serverUrl != null && session?.companyCode != null,
                        onClick = {
                            val activeServerUrl = serverUrl ?: return@Button
                            val activeSession = session ?: return@Button
                            val companyCode = activeSession.companyCode ?: return@Button
                            isLoading = true
                            error = null
                            message = null
                            scope.launch {
                                try {
                                    val profile = CompanyApi(activeServerUrl).use { api ->
                                        api.updateCompanyProfile(
                                            activeSession.accessToken,
                                            companyCode,
                                            UpdateCompanyProfileRequest(
                                                name = name,
                                                logoUrl = logoUrl.ifBlank { null },
                                                primaryColor = primaryColor,
                                                secondaryColor = secondaryColor,
                                                accentColor = accentColor,
                                            ),
                                        )
                                    }
                                    applyProfile(profile)
                                    message = "Empresa actualizada."
                                } catch (exception: CompanyException) {
                                    error = exception.message
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryCompose),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                        } else {
                            Text("Guardar")
                        }
                    }
                }

                Text("Exportar e importar", style = MaterialTheme.typography.titleMedium, color = primaryCompose)
                Text(
                    "La restauracion completa de base de datos queda pendiente de aprobacion por seguridad. Por ahora se guardan ZIPs de perfil.",
                    color = Color(0xFF666666),
                )
                Button(
                    enabled = !isLoading && serverUrl != null && session?.companyCode != null,
                    onClick = {
                        val activeServerUrl = serverUrl ?: return@Button
                        val activeSession = session ?: return@Button
                        val companyCode = activeSession.companyCode ?: return@Button
                        isLoading = true
                        error = null
                        message = null
                        scope.launch {
                            try {
                                val created = CompanyApi(activeServerUrl).use { api ->
                                    api.createBackup(activeSession.accessToken, companyCode)
                                }
                                message = "Backup ${created.fileName} creado."
                                refreshBackups()
                            } catch (exception: CompanyException) {
                                error = exception.message
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryCompose),
                ) {
                    Text("Exportar ZIP")
                }

                backups.forEach { backup ->
                    BackupRow(
                        backup = backup,
                        serverUrl = serverUrl.orEmpty(),
                        primaryColor = primaryCompose,
                        enabled = !isLoading && session?.companyCode != null,
                        onDelete = {
                            val activeServerUrl = serverUrl
                            val activeSession = session
                            val companyCode = activeSession?.companyCode
                            if (activeServerUrl != null && activeSession != null && companyCode != null) {
                                scope.launch {
                                    try {
                                        CompanyApi(activeServerUrl).use { api ->
                                            api.deleteBackup(activeSession.accessToken, companyCode, backup.fileName)
                                        }
                                        backups = backups.filterNot { it.fileName == backup.fileName }
                                        message = "Backup eliminado."
                                    } catch (exception: CompanyException) {
                                        error = exception.message
                                    }
                                }
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

@Composable
// Renderiza un ZIP disponible con URL de descarga y accion de eliminar.
private fun BackupRow(
    backup: CompanyBackupResponse,
    serverUrl: String,
    primaryColor: Color,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF6F6FA), MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(backup.fileName, fontWeight = FontWeight.SemiBold, color = primaryColor)
        Text("Tamano: ${backup.sizeBytes} bytes", color = Color(0xFF666666))
        Text("Descarga: ${serverUrl.trimEnd('/')}${backup.downloadUrl}", color = Color(0xFF333333))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                enabled = enabled,
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9E9EF), contentColor = Color(0xFFB00020)),
            ) {
                Text("Eliminar")
            }
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
